package jop.config

import org.scalatest.funsuite.AnyFunSuite
import jop.generate.{TimingConstraints, QsfGenerator, QuartusProject, XdcGenerator}
import jop.system.JopTopVerilog

/**
 * Every preset a board actually builds must generate constraints that SAY
 * SOMETHING.
 *
 * WHY THIS AND NOT A DIFF AGAINST AN ORACLE. `ConstraintDriftTest` compares
 * generator output against tracked files, which is the stronger check where an
 * oracle exists -- but it exists for exactly two Wukong presets. The Quartus
 * generators, which are the ones the build-once `.sdc`/`.qsf` defect actually
 * affected, had no coverage at all, and the surviving tracked `.qsf` files are
 * BOARD pin references whose port names are the board's rather than the
 * design's, so diffing them would need a brittle name mapping.
 *
 * So this asserts completeness instead, which is the failure class that
 * matters: a generator that emits a WELL-FORMED but empty or wrong-device file
 * and lets the build succeed. Every silent-fallback finding in the 2026-08-30
 * review was that shape.
 *
 * The clock assertion is the one with teeth. `create_clock` carries the period
 * the whole timing sign-off is measured against, and a design fitted against a
 * period that does not match its preset is precisely how a board reports
 * "timing met" while running at a frequency nobody checked.
 */
class GeneratedConstraintsTest extends AnyFunSuite {

  /** Presets that a board Makefile builds, one per flow that has hardware. */
  private val boardPresets: Seq[(String, String)] = Seq(
    "cyc5000Serial"       -> "cyc5000-sdram",
    "ep4cgx150Serial"     -> "qmtech-ep4cgx150-sdram",
    "ep4cgx150DbFull"     -> "qmtech-ep4cgx150-sdram",
    "ep4cgx150Bram"       -> "qmtech-ep4cgx150-bram",
    "ep4cgx150BramSerial" -> "qmtech-ep4cgx150-bram-serial",
    "ae115fbDdr2"         -> "a-e115fb-ddr2",
    "max1000Sdram"        -> "max1000",
    "wukongDdr3"          -> "qmtech-xc7a100t-wukong",
    "wukongSdram"         -> "qmtech-xc7a100t-wukong",
    "wukongBram"          -> "qmtech-xc7a100t-wukong",
    "auSerial"            -> "alchitry-au",
    "colorlightI5Sdram"   -> "colorlight-i5",
    "colorlightDbI9PlusSdram" -> "colorlight-db-i9plus",
  )

  private def cfgOf(name: String): JopConfig =
    JopTopVerilog.resolvePreset(name, Array(name))

  test("every board preset emits a clock constraint at its declared period") {
    for ((name, board) <- boardPresets) {
      val cfg = cfgOf(name)
      val tc  = TimingConstraints.forConfig(cfg)
      val body = cfg.fpgaFamily.manufacturer match {
        case Manufacturer.Altera => tc.toSdc
        case Manufacturer.Xilinx => tc.toXdc
        case _                   => tc.toLpf
      }

      assert(body.trim.nonEmpty, s"$name ($board): generated an EMPTY constraint file")

      // A CLOCK CONSTRAINT IS REQUIRED ONLY WHERE THERE IS A CLOCK PORT TO
      // CONSTRAIN. ae115fbDdr2 is the exception that taught this: it has no
      // top-level clock pin, because the design is clocked from the DDR2 IP's
      // own PLL, and TimingConstraints.forConfig correctly emits no
      // create_clock. Its STA report confirms the design is fully constrained
      // anyway -- "Unconstrained Clocks: 0", worst-case setup slack +1.050 ns.
      //
      // The first version of this test asserted a clock unconditionally and
      // failed that board. Asserting the wrong invariant would have invited
      // "fixing" a correct generator, which is worse than no test.
      // The generator deliberately emits NO clock for an Altera DDR2 design:
      // TimingConstraints.forConfig calls this `phyOwnsClocks`, because the
      // memory interface is constrained by the IP's own .sdc. Verified end to
      // end rather than taken on trust -- the ae115fbDdr2 STA report says
      // "Unconstrained Clocks: 0" with worst-case setup slack +1.050 ns.
      //
      // This restates the generator's condition, which is a second copy of a
      // rule and would normally be a smell. It is kept because the alternative
      // is exempting the board by NAME, which would silently exempt any future
      // board that adopts the same PHY.
      val phyOwnsClocks =
        cfg.memType.contains(MemoryType.SDRAM_DDR2) &&
        cfg.fpgaFamily.manufacturer == Manufacturer.Altera
      val hasClockPort =
        jop.generate.PinResolver.clockFpgaPin(cfg.assembly).isDefined && !phyOwnsClocks
      if (hasClockPort) {
        val hasClock = body.contains("create_clock") || body.toLowerCase.contains("frequency")
        assert(hasClock,
          s"$name ($board): has a top-level clock pin but no clock constraint — " +
          s"the fitter signs off against nothing.\n$body")

        // THE PERIOD IS THE BOARD OSCILLATOR, NOT THE SYSTEM CLOCK. The other
        // thing the first version got wrong: create_clock constrains the INPUT
        // PIN, and the post-PLL frequency the preset declares is reached via
        // derive_pll_clocks. cyc5000Serial declares 80 MHz and correctly emits
        // -period 83.333, its 12 MHz crystal.
        val oscHz    = cfg.assembly.boardClockFreq.toBigDecimal
        val periodNs = (BigDecimal(1e9) / oscHz).setScale(3, BigDecimal.RoundingMode.HALF_UP)
        val periodRe = raw"-period\s+([0-9.]+)".r
        periodRe.findFirstMatchIn(body).foreach { m =>
          val got = BigDecimal(m.group(1))
          assert((got - periodNs).abs < BigDecimal("0.01"),
            s"$name ($board): create_clock says $got ns but the board oscillator " +
            s"is ${oscHz / 1e6} MHz ($periodNs ns)")
        }
      }
    }
  }

  // ITEM 68, THE EP4CGX150 HALF. The generator named the PHY's receive clock in
  // set_clock_groups and never created it. Quartus answers a group that names
  // no clock with a warning and nothing else:
  //   Warning (332174): Ignored filter at jop_dbfpga.sdc(10): e_rxc could not be matched with a clock
  // so the whole 125 MHz receive domain went untimed. The fitter left e_rxc on
  // local routing, 2.8 ns of skew across its 136 registers, and fixed no hold
  // in it: timed at 8 ns afterwards, that placement fails hold by 1.763 ns.
  // On hardware the MAC handed over frames whose words were out of order, with
  // wrong top bytes -- behind a CRC check that passed.
  test("every clock a generated .sdc groups is a clock it creates or derives") {
    for ((name, board) <- boardPresets) {
      val cfg = cfgOf(name)
      if (cfg.fpgaFamily.manufacturer == Manufacturer.Altera) {
        val sdc = TimingConstraints.forConfig(cfg).toSdc
        val created = raw"create_clock\b[^\n]*-name\s+(\S+)".r
          .findAllMatchIn(sdc).map(_.group(1)).toSet
        val derives = sdc.contains("derive_pll_clocks")
        val grouped = raw"-group\s+\{([^}]*)\}".r
          .findAllMatchIn(sdc).flatMap(_.group(1).trim.split("\\s+")).toSeq
        for (clk <- grouped) {
          val pllOutput = clk.contains("|altpll_component|")
          assert(created(clk) || (derives && pllOutput),
            s"$name ($board): set_clock_groups names $clk, which this .sdc " +
            "neither creates nor derives -- Quartus drops it with a warning and " +
            s"that domain is never timed.\n$sdc")
        }
      }
    }
  }

  test("an Ethernet design constrains the PHY receive clock at 125 MHz") {
    val sdc = TimingConstraints.forConfig(cfgOf("ep4cgx150DbFull")).toSdc
    assert(sdc.contains("create_clock -period 8.000 -name e_rxc [get_ports e_rxc]"), sdc)
  }

  // THE OTHER HALF OF THE OLD GMII FILES. rtl8211eg_gmii.qsf put the receive
  // capture flops in the I/O element, and the generated project lost it along
  // with the hand-written .qsf (9dfd889, 2026-08-26). The Wukong's
  // rtl8211eg_gmii.xdc does the same with IOB TRUE. On the EP4CGX150 it took
  // the CRC loss on 98-byte frames from 4.6 % to about 3.1 % and a 50 KB TCP
  // echo from cut short to identical; the rest is status item 166.
  test("a GMII design captures its receive inputs in the I/O element") {
    def fastInputs(preset: String): Set[String] = {
      val tcl = QuartusProject.generate(cfgOf(preset), "test_rev", preset, Seq(preset))
      raw"FAST_INPUT_REGISTER ON -to (\S+)".r.findAllMatchIn(tcl).map(_.group(1)).toSet
    }
    val gmiiRx = ((0 until 8).map(i => s"e_rxd[$i]") ++ Seq("e_rxdv", "e_rxer")).toSet
    assert(fastInputs("ep4cgx150DbFull") == gmiiRx)
    assert(fastInputs("ep4cgx150Serial").isEmpty,
      "a design without Ethernet has no e_rx* ports to assign")
  }

  // THE XILINX HALF. XdcGenerator constrained e_rxc only for RGMII; the GMII
  // facts lived in the hand-written rtl8211eg_gmii.xdc, which only the Wukong's
  // Makefile read. The DB_FPGA V5 flow reads the generated file alone, so
  // `xc7a100tDbFull` would have built with its receive domain untimed -- the
  // EP4CGX150's defect, on the board meant to compare against it.
  test("a GMII design's XDC creates the receive clock and captures in the IOBs") {
    for (preset <- Seq("xc7a100tDbFull", "wukongFull")) {
      val xdc = XdcGenerator.generate(cfgOf(preset))
      assert(xdc.contains("create_clock -period 8.000 -name e_rxc [get_ports {e_rxc}]"),
        s"$preset\n$xdc")
      for (p <- Seq("e_rxd[*]", "e_rxdv", "e_rxer"))
        assert(xdc.contains(s"set_property IOB TRUE [get_ports {$p}]"), s"$preset: $p\n$xdc")
    }
    assert(!XdcGenerator.generate(cfgOf("xc7a100tDbSerial")).contains("e_rxc"),
      "a design without Ethernet has no e_rxc to constrain")
  }

  // THE TRANSMIT CLOCK TOO. A GMII MAC on a Xilinx board transmits on the clock
  // wizard's clk_125 output, which Vivado names clk_125_<IP> (clk_125_clk_wiz_0
  // on the DB_FPGA V5, clk_125_ddr3_clk on the Wukong), and reaches it through
  // CC FIFOs. The Wukong's hand-written XDC grouped it; the V5's generated file
  // did not, and `xc7a100tDbEth` missed timing by 2.591 ns on exactly those
  // crossings -- clk_pll_i to clk_125_clk_wiz_0, timed as if synchronous.
  test("a GMII design's XDC makes the clock wizard's Ethernet clock asynchronous") {
    val group = "set_clock_groups -asynchronous -group [get_clocks clk_125_*]"
    for (preset <- Seq("xc7a100tDbEth", "wukongFull"))
      assert(XdcGenerator.generate(cfgOf(preset)).contains(group), preset)
    assert(!XdcGenerator.generate(cfgOf("xc7a100tDbSerial")).contains("clk_125"),
      "a design without Ethernet does not name the Ethernet clock")
  }

  // THE DB_FPGA PUTS RX_CLK ON AN ORDINARY PIN: J3:37 is L5 on the XC7A100T core
  // board, not clock-capable, and Vivado refuses the IBUF->BUFG route
  // (Place 30-574) unless the design says it may take a general one. Only a
  // preset that declares it gets the override: on the Wukong RXC is on a
  // clock-capable pin, and the same line there would hide a real misplacement.
  test("only a board that routes RX_CLK to an ordinary pin waives the dedicated route") {
    val waiver = "set_property CLOCK_DEDICATED_ROUTE FALSE [get_nets e_rxc_IBUF]"
    assert(XdcGenerator.generate(cfgOf("xc7a100tDbEth")).contains(waiver))
    assert(!XdcGenerator.generate(cfgOf("wukongFull")).contains("CLOCK_DEDICATED_ROUTE"))
  }

  test("every Altera board preset emits pins and the right device") {
    for ((name, board) <- boardPresets) {
      val cfg = cfgOf(name)
      if (cfg.fpgaFamily.manufacturer == Manufacturer.Altera) {
        val pins = QsfGenerator.pinAssignments(cfg)
        assert(pins.nonEmpty,
          s"$name ($board): no pin assignments — the fitter places every port " +
          "wherever it likes, which is what the MAX1000's empty .qsf did")

        val globals = QsfGenerator.globalAssignments(cfg)
        assert(globals.contains(cfg.fpga.name),
          s"$name ($board): device string missing from the global assignments. " +
          "A wrong device makes every correct pin come back as an illegal " +
          s"location assignment.\n$globals")
        assert(globals.contains(cfg.entityName),
          s"$name ($board): TOP_LEVEL_ENTITY is not ${cfg.entityName}")
      }
    }
  }

  test("every Altera board preset produces a project script") {
    for ((name, board) <- boardPresets) {
      val cfg = cfgOf(name)
      if (cfg.fpgaFamily.manufacturer == Manufacturer.Altera) {
        val tcl = QuartusProject.generate(cfg, "test_rev", name, Seq(name))
        assert(tcl.trim.nonEmpty, s"$name ($board): empty project script")
        assert(tcl.contains(cfg.entityName),
          s"$name ($board): project script does not name ${cfg.entityName}")
      }
    }
  }
}
