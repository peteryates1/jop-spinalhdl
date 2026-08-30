package jop.config

import org.scalatest.funsuite.AnyFunSuite
import jop.generate.{TimingConstraints, QsfGenerator, QuartusProject}
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
    "ep4cgx150Bram"       -> "qmtech-ep4cgx150-bram",
    "ep4cgx150BramSerial" -> "qmtech-ep4cgx150-bram-serial",
    "ae115fbDdr2"         -> "a-e115fb-ddr2",
    "max1000Sdram"        -> "max1000",
    "wukongDdr3"          -> "qmtech-xc7a100t-wukong",
    "wukongSdram"         -> "qmtech-xc7a100t-wukong",
    "wukongBram"          -> "qmtech-xc7a100t-wukong",
    "auSerial"            -> "alchitry-au",
    "colorlightI5Sdram"   -> "colorlight-i5",
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
