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
      assert(cfg.system.pll.contains(want), s"$name defines ${cfg.system.pll}")
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
}
