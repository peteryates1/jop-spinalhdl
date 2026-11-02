package jop.config

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import java.nio.file.{Files, Paths}
import scala.util.Try

/**
 * The clock-wizard builder: Artix-7 (status item 173, step 8).
 *
 * Vivado picks the MMCM's counters when it creates the IP, so what the builder
 * writes is only what the spec states -- each output's frequency and phase,
 * and the input -- in the clk_wiz configuration the hand-written scripts used.
 * CI cannot run Vivado; these tests hold the generated definition to the spec
 * and to the configurations that ran on hardware. What Vivado then makes of it
 * is checked by the definition itself, when Vivado runs it.
 */
class XilinxClkWizTest extends AnyFunSuite {

  private def read(p: String) = new String(Files.readAllBytes(Paths.get(p)), "UTF-8")

  private def definition(spec: PllSpec, module: String = "pll_x"): String = {
    val dir = Files.createTempDirectory("clkwiz").toString
    read(FpgaFamily.Artix7.pll.get.generate(spec, module, dir))
  }

  /** The CONFIG.<name> {value} pairs the definition sets on the IP. */
  private def config(tcl: String): Map[String, String] =
    """CONFIG\.(\w+)\s+\{([^}]*)\}""".r.findAllMatchIn(tcl).map(m => m.group(1) -> m.group(2)).toMap

  /** Equal as numbers where both are numbers (`100.0` is `100.000`). */
  private def sameValue(a: String, b: String) =
    Try(BigDecimal(a) == BigDecimal(b)).getOrElse(a == b)

  private def assertConfig(got: Map[String, String], want: Map[String, String]): Unit = {
    val differ = (got.keySet ++ want.keySet).toSeq.sorted.filterNot(k =>
      got.contains(k) && want.contains(k) && sameValue(got(k), want(k)))
    assert(differ.isEmpty, differ.map(k =>
      s"\n  $k: generated ${got.getOrElse(k, "-")}, validated ${want.getOrElse(k, "-")}").mkString)
  }

  // ---------------------------------------------------------------------------
  // The configurations the hand-written scripts set, RECORDED: those scripts go
  // as the designs move onto the builder, and a test whose oracle is deleted
  // does not fail, it cancels. Each ran on hardware:
  //   DDR3  create_ddr3_clk_wiz.tcl (Wukong, the XC7A100T's copy): DoAll on
  //         wukongDdr3, wukongFull (GMII 1000M) and xc7a100tDbSerial
  //   Au    alchitry-au create_clk_wiz.tcl, the same at a 100 MHz input
  //   SDR   create_sdram_clk_wiz.tcl: DoAll on wukongSdram at 100 MHz, and on
  //         the i9+ at 50 (25 MHz in, -eth_mhz 50, RGMII on CLKOUT3)
  //   BRAM  create_clk_wiz.tcl (wukongBram)
  // ---------------------------------------------------------------------------

  private val validatedDdr3 = Map(
    "PRIM_IN_FREQ" -> "50.000", "PRIMARY_PORT" -> "clk_in", "NUM_OUT_CLKS" -> "3",
    "CLKOUT1_REQUESTED_OUT_FREQ" -> "100.000", "CLK_OUT1_PORT" -> "clk_100", "CLKOUT1_DRIVES" -> "BUFG",
    "CLKOUT2_USED" -> "true", "CLKOUT2_REQUESTED_OUT_FREQ" -> "200.000", "CLK_OUT2_PORT" -> "clk_200",
    "CLKOUT2_DRIVES" -> "BUFG",
    "CLKOUT3_USED" -> "true", "CLKOUT3_REQUESTED_OUT_FREQ" -> "125.000", "CLK_OUT3_PORT" -> "clk_125",
    "CLKOUT3_DRIVES" -> "BUFG",
    "USE_RESET" -> "true", "RESET_PORT" -> "resetn", "USE_LOCKED" -> "true", "LOCKED_PORT" -> "locked")

  private def validatedSdr(inMhz: String, sysMhz: String, shiftDeg: String, ethMhz: String) = Map(
    "PRIMITIVE" -> "MMCM", "PRIM_IN_FREQ" -> inMhz, "PRIMARY_PORT" -> "clk_in", "NUM_OUT_CLKS" -> "3",
    "CLKOUT1_REQUESTED_OUT_FREQ" -> sysMhz, "CLK_OUT1_PORT" -> "clk_100", "CLKOUT1_DRIVES" -> "BUFG",
    "CLKOUT2_USED" -> "true", "CLKOUT2_REQUESTED_OUT_FREQ" -> sysMhz, "CLKOUT2_REQUESTED_PHASE" -> shiftDeg,
    "CLK_OUT2_PORT" -> "clk_100_shift", "CLKOUT2_DRIVES" -> "BUFG",
    "CLKOUT3_USED" -> "true", "CLKOUT3_REQUESTED_OUT_FREQ" -> ethMhz, "CLK_OUT3_PORT" -> "clk_125",
    "CLKOUT3_DRIVES" -> "BUFG",
    "USE_RESET" -> "true", "RESET_TYPE" -> "ACTIVE_LOW", "RESET_PORT" -> "resetn",
    "USE_LOCKED" -> "true", "LOCKED_PORT" -> "locked")

  private val validatedBram = Map(
    "PRIM_IN_FREQ" -> "50.000", "PRIMARY_PORT" -> "clk_in", "NUM_OUT_CLKS" -> "1",
    "CLKOUT1_REQUESTED_OUT_FREQ" -> "100.000", "CLK_OUT1_PORT" -> "clk_100", "CLKOUT1_DRIVES" -> "BUFG",
    "USE_RESET" -> "true", "RESET_PORT" -> "resetn", "USE_LOCKED" -> "true", "LOCKED_PORT" -> "locked")

  /** What the builder states that the scripts did not, or states otherwise:
    * outputs named by role, one reset convention (active high, `reset`; the
    * DDR3 and BRAM IPs were already active high, under the name `resetn`),
    * and the defaults it relies on spelled out. */
  private def stated(ports: Seq[String], phases: Seq[String]) = Map(
    "PRIMITIVE" -> "MMCM", "RESET_TYPE" -> "ACTIVE_HIGH", "RESET_PORT" -> "reset") ++
    ports.zipWithIndex.flatMap { case (p, i) => Seq(
      s"CLK_OUT${i + 1}_PORT" -> p, s"CLKOUT${i + 1}_USED" -> "true",
      s"CLKOUT${i + 1}_REQUESTED_PHASE" -> phases(i)) }

  private val ddr3Outputs = Seq(PllOutput(PllRole.MigSys, 100), PllOutput(PllRole.MigRef, 200),
                                PllOutput(PllRole.Eth, 125))

  test("a DDR3 spec gives the clk_wiz configuration that ran the MIG designs") {
    assertConfig(config(definition(PllSpec(50, ddr3Outputs))),
      validatedDdr3 ++ stated(Seq("clk_mig_sys", "clk_mig_ref", "clk_eth"), Seq("0", "0", "0")))
  }

  test("the Au's 100 MHz input gives the Au's configuration") {
    assertConfig(config(definition(PllSpec(100, ddr3Outputs))),
      validatedDdr3 ++ Map("PRIM_IN_FREQ" -> "100.000") ++
        stated(Seq("clk_mig_sys", "clk_mig_ref", "clk_eth"), Seq("0", "0", "0")))
  }

  for ((inMhz, sys, eth, deg) <- Seq((50, 100, 125, "-108.0"), (25, 50, 50, "-54.0"), (25, 80, 80, "-86.4")))
    test(s"an SDR spec at $sys MHz from $inMhz gives create_sdram_clk_wiz.tcl's configuration") {
      val spec = PllSpec(inMhz, Seq(PllOutput(PllRole.System, sys),
        PllOutput(PllRole.Sdram, sys, phasePs = -3000), PllOutput(PllRole.Eth, eth)))
      assertConfig(config(definition(spec)),
        validatedSdr(inMhz.toString, sys.toString, deg, eth.toString) ++
          stated(Seq("clk_sys", "clk_sdram", "clk_eth"), Seq("0", deg, "0")))
    }

  test("a system-only spec gives the BRAM design's configuration") {
    assertConfig(config(definition(PllSpec(50, Seq(PllOutput(PllRole.System, 100))))),
      validatedBram ++ stated(Seq("clk_sys"), Seq("0")))
  }

  test("a phase in picoseconds becomes degrees at that output's frequency, a phase in degrees stays") {
    // -3000 ps is -108 deg at 100 MHz, -86.4 at 80 and -81 at 75; clk_wiz takes degrees.
    for ((mhz, deg) <- Seq(100 -> "-108", 80 -> "-86.4", 75 -> "-81"))
      assert(sameValue(XilinxClkWiz.phaseDeg(PllOutput(PllRole.Sdram, mhz, phasePs = -3000)).toString, deg))
    val c = config(definition(PllSpec(25, Seq(PllOutput(PllRole.System, 40),
      PllOutput(PllRole.Sdram, 40, phaseDeg = 315)))))
    assert(sameValue(c("CLKOUT2_REQUESTED_PHASE"), "315"))
  }

  test("a fractional output is asked for as the design states it") {
    // MigProfile.Ddr3_366: MIG demands 97.787 MHz at its sys_clk input.
    val c = config(definition(PllSpec(50, Seq(PllOutput(PllRole.MigSys, BigDecimal("97.787"), tolerancePpm = 2500),
      PllOutput(PllRole.MigRef, 200, tolerancePpm = 2500)))))
    assert(c("CLKOUT1_REQUESTED_OUT_FREQ") == "97.787")
  }

  test("the definition refuses an IP whose outputs miss the spec, each by its own tolerance") {
    // Item 164: asked for 80 MHz and its SDRAM copy, clk_wiz made 80.09 and
    // 80.80 MHz -- timing met, every SDRAM access corrupt. The definition
    // computes what the MMCM it got actually makes and stops there.
    val tcl = definition(PllSpec(25, Seq(PllOutput(PllRole.System, 80),
      PllOutput(PllRole.Sdram, 80, phasePs = -3000), PllOutput(PllRole.MigRef, 200, tolerancePpm = 5000))))
    val checked = """(?m)^\s*(\d) (MMCM_CLKOUT\d_DIVIDE(?:_F)?) ([0-9.]+) (\d+)\s*$""".r
      .findAllMatchIn(tcl).map(m => (m.group(1), m.group(2), m.group(3), m.group(4))).toSeq
    assert(checked == Seq(("1", "MMCM_CLKOUT0_DIVIDE_F", "80.000", "0"), ("2", "MMCM_CLKOUT1_DIVIDE", "80.000", "0"),
                          ("3", "MMCM_CLKOUT2_DIVIDE", "200.000", "5000")), tcl)
    assert(tcl.contains("MMCM_CLKFBOUT_MULT_F") && tcl.contains("MMCM_DIVCLK_DIVIDE") && tcl.contains("error "))
  }

  test("the builder instantiates the module the IP has, port for port") {
    val spec = PllSpec(50, Seq(PllOutput(PllRole.System, 100), PllOutput(PllRole.Sdram, 100, phasePs = -3000)))
    val vendor = FpgaFamily.Artix7.pll.get
    val dir = Files.createTempDirectory("clkwiz").toString
    val c = config(read(vendor.generate(spec, "pll_x", dir)))
    val declared = Set(c("PRIMARY_PORT"), c("RESET_PORT"), c("LOCKED_PORT")) ++
      (1 to c("NUM_OUT_CLKS").toInt).map(i => c(s"CLK_OUT${i}_PORT"))

    SpinalConfig(mode = Verilog, targetDirectory = dir).generate(new Component {
      setDefinitionName("PllTop")
      val clk  = in Bool()
      val rst  = in Bool()
      val outs = out Bits(spec.outputs.size + 1 bits)
      val pll  = vendor.instantiate(spec, "pll_x", "thePll", clk, reset = Some(rst))
      outs := Cat(pll.outputs :+ pll.locked)
    })
    val top = read(Paths.get(dir, "PllTop.v").toString)
    val inst = """(?s)pll_x\s+thePll\s*\((.*?)\);""".r.findFirstMatchIn(top)
      .getOrElse(fail(s"no `pll_x thePll` instance in\n$top")).group(1)
    val connected = """\.(\w+)\s*\(""".r.findAllMatchIn(inst).map(_.group(1)).toSet
    assert(connected == declared, s"instance connects $connected, the IP declares $declared")
    assert(declared == Set("clk_in", "reset", "locked", "clk_sys", "clk_sdram"))
  }

  test("a clk_wiz with no reset given is held out of reset") {
    val dir = Files.createTempDirectory("clkwiz").toString
    val spec = PllSpec(50, Seq(PllOutput(PllRole.System, 100)))
    SpinalConfig(mode = Verilog, targetDirectory = dir).generate(new Component {
      setDefinitionName("PllTop")
      val clk = in Bool()
      val o   = out Bool()
      o := FpgaFamily.Artix7.pll.get.instantiate(spec, "pll_x", "thePll", clk).outputs.head
    })
    val inst = """(?s)pll_x\s+thePll\s*\((.*?)\);""".r.findFirstMatchIn(read(Paths.get(dir, "PllTop.v").toString)).get.group(1)
    assert("""\.reset\s*\(\s*1'b0\s*\)""".r.findFirstIn(inst).isDefined, inst)
  }

  test("Artix-7 has the builder; its definition is a Tcl file") {
    assert(FpgaFamily.Artix7.pll.contains(XilinxClkWiz))
    assert(XilinxClkWiz.generatedWithVerilog)
    assert(XilinxClkWiz.fileName("sdr_clk") == "sdr_clk.tcl")
    assert(Board.WukongXC7A100T.fpga.map(_.family).contains(FpgaFamily.Artix7))
  }

  test("more outputs than an MMCM has are refused") {
    val roles = Seq(PllRole.System, PllRole.Sdram, PllRole.Eth, PllRole.Vga, PllRole.MigSys, PllRole.MigRef)
    val seven = PllSpec(50, roles.map(PllOutput(_, 50)) :+ PllOutput(PllRole.System, 25))
    assertThrows[IllegalArgumentException](XilinxClkWiz.definition(seven, "pll_x"))
  }

  test("two outputs for one role are refused: they would be one port") {
    val twice = PllSpec(50, Seq(PllOutput(PllRole.System, 100), PllOutput(PllRole.System, 50)))
    val e = intercept[IllegalArgumentException](XilinxClkWiz.definition(twice, "pll_x"))
    assert(e.getMessage.contains("System"), e.getMessage)
  }

  test("ECP5 and ALTPLL take no reset here: one given is refused, not dropped") {
    val spec = PllSpec(25, Seq(PllOutput(PllRole.System, 40)))
    for (v <- Seq(LatticeEcpPll, AlteraAltpll("Cyclone IV GX"))) {
      val e = intercept[Throwable](SpinalConfig(mode = Verilog,
        targetDirectory = Files.createTempDirectory("rst").toString).generate(new Component {
          val clk = in Bool(); val rst = in Bool(); val o = out Bool()
          o := v.instantiate(spec, "m", "i", clk, reset = Some(rst)).outputs.head
        }))
      // SpinalHDL may wrap what elaboration throws.
      val messages = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).map(t => String.valueOf(t.getMessage))
      assert(messages.exists(_.contains("reset")), s"$v: ${e.getMessage}")
    }
  }

  test("the Verilog generator writes the definition, and removes one an earlier generation left") {
    // The Vivado flow builds every definition in the directory (JOP_PLL_TCL),
    // so a stale one -- a PLL renamed or dropped since -- would be built too.
    val base = JopConfig.wukongBram
    val cfg = base.copy(systems = Seq(base.system.copy(plls = Seq(PllDefinition("CLK50M",
      Seq(PllOutput(PllRole.System, 100)), module = "bram_pll", instance = "pll")))))
    val dir = Files.createTempDirectory("clkwiz")
    Files.write(dir.resolve("old_pll.tcl"), "# stale".getBytes("UTF-8"))
    Files.write(dir.resolve("notes.txt"), "kept".getBytes("UTF-8"))
    val written = jop.generate.DesignPlls.emit(cfg, dir.toString)
    assert(written == Seq(dir.resolve("bram_pll.tcl").toString))
    assert(jop.generate.DesignPlls.files(cfg) == Seq("bram_pll.tcl"))
    val left = Files.list(dir).toArray.map(_.toString.split('/').last).toSet
    assert(left == Set("bram_pll.tcl", "notes.txt"), left)
  }

  test("an output's tolerance is a design statement: never negative") {
    assertThrows[IllegalArgumentException](PllOutput(PllRole.MigSys, 100, tolerancePpm = -1))
  }
}
