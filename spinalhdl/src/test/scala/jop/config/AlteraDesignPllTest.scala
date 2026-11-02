package jop.config

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}
import jop.generate.{DesignPlls, QuartusProject, TimingConstraints}

/**
 * Altera designs define their PLLs, and the ALTPLL builder makes them (status
 * item 173, step 7): the MAX1000 and the generic EP4CE6 first -- the boards
 * whose PLL files were a pass-through stub and a file never committed.
 */
class AlteraDesignPllTest extends AnyFunSuite {

  private def read(p: String) = new String(Files.readAllBytes(Paths.get(p)), "UTF-8")

  /** 80 MHz system, and the SDRAM's copy 3 ns early -- what the two boards'
    * PllTypes described and neither file delivered. */
  private val sdram80 = Seq(PllOutput(PllRole.System, 80), PllOutput(PllRole.Sdram, 80, phasePs = -3000))

  private val small = Seq(
    ("max1000Sdram", JopConfig.max1000Sdram, PllDefinition("CLK12M", sdram80, "max1000_pll", "pll")),
    ("ep4ce6Sdram", JopConfig.ep4ce6Sdram, PllDefinition("CLK50M", sdram80, "ep4ce6_pll", "pll")))

  test("the MAX1000's and the EP4CE6's PLLs belong to their designs, not their boards") {
    assert(Board.MAX1000.pllType.isEmpty, "the MAX1000 still declares a PLL")
    assert(Board.GenericEP4CE6.pllType.isEmpty, "the EP4CE6 still declares a PLL")
    for ((name, cfg, want) <- small) {
      assert(cfg.system.plls == Seq(want), s"$name defines ${cfg.system.plls}")
      assert(cfg.plls == Seq(want), name)
    }
  }

  test("the EP4CE6 is a Cyclone IV E part, and its project and its PLL say so") {
    // Filed under Cyclone IV GX until 2026-11-02: its project named a family
    // the device is not in, and its PLL would have too.
    assert(FpgaDevice.EP4CE6E22.family == FpgaFamily.CycloneIVE)
    val tcl = QuartusProject.generate(JopConfig.ep4ce6Sdram, "test_rev", "ep4ce6Sdram", Seq("ep4ce6Sdram"))
    assert(tcl.contains("FAMILY \"Cyclone IV E\""), tcl.linesIterator.filter(_.contains("FAMILY")).mkString)
  }

  test("Verilog generation writes each design's PLL into its ip/, and the project reads that file") {
    for ((name, cfg, want) <- small) {
      val dir = Files.createTempDirectory("ip").toString
      val written = DesignPlls.emit(cfg, dir)
      assert(written == Seq(Paths.get(dir, s"${want.module}.v").toString), name)
      val v = read(written.head)
      assert(v.contains(s"module ${want.module} ("), name)
      assert(v.contains(s".intended_device_family(\"${cfg.fpgaFamily.quartusFamilyName}\")"), name)
      assert(v.contains(".clk1_phase_shift(\"-3000\")"), name)

      val tcl = QuartusProject.generate(cfg, "test_rev", name, Seq(name))
      val pllFile = s"build/$name/ip/${want.module}.v"
      assert(tcl.linesIterator.exists(l => l.contains("VERILOG_FILE") && l.contains(pllFile)),
        s"$name's project does not read $pllFile")
    }
    assert(!Files.exists(Paths.get("fpga/max1000/max1000_pll.v")), "the MAX1000's pass-through stub is back")
  }

  test("the MAX1000's PLL multiplies its 12 MHz clock to 80 MHz") {
    // The stub passed 12 MHz through, so an image built for 80 ran 6.7x slow.
    val v = read(DesignPlls.emit(JopConfig.max1000Sdram, Files.createTempDirectory("ip").toString).head)
    assert(v.contains(".inclk0_input_frequency(83333)"))
    assert(v.contains(".clk0_multiply_by(20)") && v.contains(".clk0_divide_by(3)"))
    assert(!v.contains("assign c0 = inclk0"))
  }

  test("the SDC names a design PLL's outputs by its instance and their order") {
    // One PLL, one group: a lone group constrains nothing and is not written,
    // as before -- but the group is the design's, not a fixed numbering.
    val tc = TimingConstraints.forConfig(JopConfig.max1000Sdram)
    assert(tc.asyncGroups == Seq(Seq(
      "pll|altpll_component|auto_generated|pll1|clk[0]",
      "pll|altpll_component|auto_generated|pll1|clk[1]")))
    assert(tc.derivePllClocks)
  }

  // ---- the EP4CGX150 (step 7c) ----

  private def isVga(d: DeviceInstance) =
    d.deviceType.key == DeviceType.VgaText.key || d.deviceType.key == DeviceType.VgaDma.key

  /** Every EP4CGX150 preset, and the parameterised ones at the points they run
    * (ep4cgx150Smp: 4 cores at 60 MHz, 8 at 50, 12 at 36). */
  private lazy val ep4cgx150Presets: Seq[(String, JopConfig)] = {
    val named = for {
      m <- JopConfig.getClass.getMethods.toSeq
      if m.getParameterCount == 0 && m.getReturnType == classOf[JopConfig]
      cfg = m.invoke(JopConfig).asInstanceOf[JopConfig]
      if cfg.assembly.fpgaBoard.name == "qmtech-ep4cgx150"
    } yield (m.getName, cfg)
    named ++ Seq(
      "ep4cgx150Smp(2)"         -> JopConfig.ep4cgx150Smp(2),
      "ep4cgx150Smp(4, 60)"     -> JopConfig.ep4cgx150Smp(4, 60),
      "ep4cgx150Smp(8, 50)"     -> JopConfig.ep4cgx150Smp(8, 50),
      "ep4cgx150Smp(12, 36)"    -> JopConfig.ep4cgx150Smp(12, 36),
      "ep4cgx150BramSmp(4, 50)" -> JopConfig.ep4cgx150BramSmp(4, 50))
  }

  test("the EP4CGX150's PLL belongs to its designs, not its board") {
    assert(Board.QmtechEP4CGX150.pllType.isEmpty, "the EP4CGX150 still declares a PLL")
  }

  test("an EP4CGX150 design's PLL: its clock, the SDRAM's copy 3 ns early, VGA's 25 MHz, nothing else") {
    // What DramPllGen's template gave every design, c1..c3, less the outputs
    // a design does not use: its unused 50 MHz c0, and c3 without VGA.
    assert(ep4cgx150Presets.size >= 17, ep4cgx150Presets.map(_._1).mkString(", "))
    for ((name, cfg) <- ep4cgx150Presets; s <- cfg.systems) {
      val mhz = (s.clkFreq.toBigDecimal / 1000000).toIntExact
      val want = PllDefinition("CLK50M",
        Seq(PllOutput(PllRole.System, mhz)) ++
          (if (s.memory == "sdr") Seq(PllOutput(PllRole.Sdram, mhz, phasePs = -3000)) else Nil) ++
          (if (s.effectiveDevices.values.exists(isVga)) Seq(PllOutput(PllRole.Vga, 25)) else Nil),
        module = "ep4cgx150_pll", instance = "pll")
      // The GMII designs' second PLL, 125 MHz, is checked below.
      val main = s.plls.filterNot(_.outputs.exists(_.role == PllRole.Eth))
      assert(main == Seq(want), s"$name defines ${s.plls}")
    }
  }

  test("an EP4CGX150 project reads the generated PLL, and its SDC names the outputs by role") {
    val tcl = QuartusProject.generate(JopConfig.ep4cgx150Serial, "rev", "ep4cgx150Serial", Seq("ep4cgx150Serial"))
    assert(tcl.linesIterator.exists(l => l.contains("VERILOG_FILE") && l.contains("build/ep4cgx150Serial/ip/ep4cgx150_pll.v")))
    assert(!tcl.contains("dram_pll"), "the project still names DramPllGen's file")
    // VGA is its own domain (status item 65): system and SDRAM together, 25 MHz apart.
    val groups = TimingConstraints.forConfig(JopConfig.ep4cgx150DbFull).asyncGroups
    assert(groups.contains(Seq(
      "pll|altpll_component|auto_generated|pll1|clk[0]",
      "pll|altpll_component|auto_generated|pll1|clk[1]")), groups)
    assert(groups.contains(Seq("pll|altpll_component|auto_generated|pll1|clk[2]")), groups)
  }

  test("the EP4CGX150 exercisers define their PLLs, and the Verilog generator writes them") {
    import jop.system._
    val sys80 = PllDefinition("CLK50M", Seq(PllOutput(PllRole.System, 80)), "pll_sys_80mhz", "pll")
    for (d <- Seq[BoardDesign](UartTestDesign, ConfigFlashExerciserDesign, FlashProgrammerDesign,
                               SdSpiExerciserDesign, SdNativeExerciserDesign))
      assert(d.plls == Seq(sys80), d.designName)
    assert(SdramExerciserDesign.plls == Seq(PllDefinition("CLK50M",
      Seq(PllOutput(PllRole.System, 100), PllOutput(PllRole.Sdram, 100, phasePs = -3000)),
      "ep4cgx150_pll", "pll")))
    val dir = Files.createTempDirectory("ip").toString
    assert(DesignPlls.emit(SdramExerciserDesign, dir) == Seq(Paths.get(dir, "ep4cgx150_pll.v").toString))
  }

  test("DramPllGen and its template are gone") {
    assert(getClass.getResource("/jop/config/dram_pll.template.vhd") == null)
    assert(scala.util.Try(Class.forName("jop.config.DramPllGen$")).isFailure)
  }

  // ---- the GMII Ethernet PLL (step 7d) ----

  /** 125 MHz for the GMII transmit clock, from the clock the design takes in:
    * what the boards' pll_125.v files were, now a design's second PLL. */
  private def eth125(reference: String) =
    PllDefinition(reference, Seq(PllOutput(PllRole.Eth, 125)), "pll_125", "ethPll")

  test("a GMII design on an ALTPLL family defines its 125 MHz PLL; an MII design does not") {
    val onAltpll = for {
      m <- JopConfig.getClass.getMethods.toSeq
      if m.getParameterCount == 0 && m.getReturnType == classOf[JopConfig]
      cfg = m.invoke(JopConfig).asInstanceOf[JopConfig]
      if cfg.fpgaFamily.pll.exists(_.isInstanceOf[AlteraAltpll])
    } yield (m.getName, cfg)
    val names = onAltpll.map(_._1)
    assert(names.contains("ae115fbDdr2Eth") && names.contains("ep4cgx150DbV5Eth") &&
      names.contains("ep4cgx150DbV5Mii"), names)
    for ((name, cfg) <- onAltpll; s <- cfg.systems) {
      val eth = s.plls.filter(_.outputs.exists(_.role == PllRole.Eth))
      if (s.ethGmii) assert(eth == Seq(eth125(cfg.inputClock.name)), s"$name: ${s.plls}")
      else assert(eth.isEmpty, s"$name defines an Ethernet PLL it does not use: $eth")
    }
  }

  test("the A-E115FB's GMII design has only the 125 MHz PLL: DDR2's PHY makes the system clock") {
    assert(JopConfig.ae115fbDdr2Eth.system.plls == Seq(eth125("CLK25M")))
    assert(JopConfig.ae115fbDdr2.system.plls.isEmpty)
    assert(JopConfig.ep4cgx150DbV5Eth.system.plls ==
      Seq(JopConfig.ep4cgx150Pll(80, sdram = true, vga = true), eth125("CLK50M")))
  }

  test("a design's PLLs give each role once") {
    val sys = JopConfig.ep4cgx150DbV5Eth.system
    assertThrows[IllegalArgumentException](sys.copy(plls = sys.plls :+ eth125("CLK50M").copy(module = "m2", instance = "i2")))
  }

  test("the GMII designs read the generated pll_125 and still name its clock ethPll's clk[0]") {
    for ((name, cfg) <- Seq("ae115fbDdr2Eth" -> JopConfig.ae115fbDdr2Eth, "ep4cgx150DbV5Eth" -> JopConfig.ep4cgx150DbV5Eth)) {
      val tcl = QuartusProject.generate(cfg, "rev", name, Seq(name))
      assert(tcl.linesIterator.exists(l => l.contains("VERILOG_FILE") && l.contains(s"build/$name/ip/pll_125.v")), name)
      assert(!tcl.contains("fpga/a-e115fb-ddr2/pll_125.v") && !tcl.contains("fpga/qmtech-ep4cgx150-sdram/pll_125.v"), name)
      val sdc = TimingConstraints.forConfig(cfg).toSdc
      assert(sdc.contains("ethPll|altpll_component|auto_generated|pll1|clk[0]"), s"$name\n$sdc")
    }
    for (f <- Seq("fpga/a-e115fb-ddr2/pll_125.v", "fpga/qmtech-ep4cgx150-sdram/pll_125.v"))
      assert(!Files.exists(Paths.get(f)), s"$f is back")
  }
}
