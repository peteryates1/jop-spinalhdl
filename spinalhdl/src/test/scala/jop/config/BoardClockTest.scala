package jop.config

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import java.nio.file.{Files, Paths}
import scala.util.matching.Regex

/**
 * A BOARD OFFERS ITS CLOCKS, A DESIGN PICKS ONE, AND EVERY PLL IS BUILT FOR
 * THE CLOCK THAT FEEDS IT -- status item 173.
 *
 * The user's model (2026-11-02): a board declares every clock it has, a
 * design picks the one it takes in, and the FPGA family builds the PLL from
 * it. Until the family builders exist, most PLLs are files made by hand or by
 * a vendor script, and each states the input frequency it was built for: a
 * second copy of a board fact. Nothing compared the two, and the A-E115FB
 * declared 50 MHz on AB11 while both PLLs fed from that pin were built for its
 * 25 MHz. This test was red on exactly those two rows before the board's
 * clocks were corrected.
 *
 * The table maps every such statement in the tree to the board clock that
 * feeds it, and the completeness test refuses a statement the table does not
 * cover. Rows leave as the family builders replace the files.
 */
class BoardClockTest extends AnyFunSuite {

  private def read(path: String) = new String(Files.readAllBytes(Paths.get(path)), "UTF-8")

  /** Every value `re` captures in `path`, which must state at least one. */
  private def stated(path: String, re: Regex): Seq[BigDecimal] = {
    val found = re.findAllMatchIn(read(path)).map(m => BigDecimal(m.group(1))).toSeq
    assert(found.nonEmpty, s"$path states no input frequency matching $re")
    found
  }

  // How each kind of source states its input, in MHz.
  private val altpllPs  = """inclk0_input_frequency\s*(?:=>|=|\()\s*(\d+)""".r  // picoseconds
  private val clkWizTcl = """PRIM_IN_FREQ\s*\{([0-9.]+)\}""".r
  private val tclInMhz  = """set\s+in_mhz\s+([0-9.]+)""".r
  private val inFreqArg = """-in_freq\s+([0-9.]+)""".r
  private val memPhyRef = """name\s*=\s*"pll_ref_clk_mhz"\s+value\s*=\s*"([0-9.]+)"""".r

  private def altpll(path: String)   = stated(path, altpllPs).map(ps => BigDecimal(1000000) / ps)
  private def megahertz(re: Regex)(path: String) = stated(path, re)

  /** A file that states a PLL's input, and the board clock that feeds it. */
  private case class Source(path: String, inputs: String => Seq[BigDecimal], board: String, clock: String)

  private val sources = Seq(
    // The DDR2 controller's own PLL, read from its committed MegaWizard
    // variation: the generated IP under ip/ is not in the tree.
    Source("fpga/a-e115fb-ddr2/ip-src/ddr2_64bit.v", megahertz(memPhyRef), "a-e115fb", "CLK25M"),
    Source("fpga/cyc5000-sdram/cyc5000_pll.vhd", altpll, "cyc5000", "CLK12M"),
    Source("fpga/alchitry-au/vivado/tcl/create_clk_wiz.tcl", megahertz(clkWizTcl), "alchitry-au-v2", "CLK100M"),
    Source("fpga/qmtech-xc7a100t-dbfpga-v5/vivado/tcl/create_ddr3_clk_wiz.tcl", megahertz(clkWizTcl),
      "qmtech-xc7a100t", "CLK50M"),
    Source("fpga/qmtech-xc7a100t-wukong/vivado/tcl/create_clk_wiz.tcl", megahertz(clkWizTcl),
      "qmtech-wukong-xc7a100t", "CLK50M"),
    Source("fpga/qmtech-xc7a100t-wukong/vivado/tcl/create_ddr3_clk_wiz.tcl", megahertz(clkWizTcl),
      "qmtech-wukong-xc7a100t", "CLK50M"),
    // The SDR clk_wiz script's default input serves the Wukong, whose calls
    // pass none; the i9+ passes its own.
    Source("fpga/qmtech-xc7a100t-wukong/vivado/tcl/create_sdram_clk_wiz.tcl", megahertz(tclInMhz),
      "qmtech-wukong-xc7a100t", "CLK50M"),
    Source("fpga/colorlight-db-i9plus/Makefile", megahertz(inFreqArg), "colorlight-i9plus", "CLK25M"))

  /** Files that mention a PLL input without stating one a design is built with. */
  private val notSources = Map(
    "fpga/bringup/"                     -> "bring-up designs: standalone Verilog, built from no board data",
    "fpga/scripts/check_clk_ip.py"      -> "reads a built clk_wiz; states nothing",
    "fpga/scripts/test_check_clk_ip.py" -> "its test",
    "spinalhdl/src/main/scala/jop/config/Board.scala" -> "quotes the i9+ flow's -in_freq in a comment",
    "spinalhdl/src/main/scala/jop/config/PllVendor.scala" ->
      "the ALTPLL builder writes inclk0_input_frequency from a design's definition, checked below through it",
    "spinalhdl/src/test/scala/jop/config/AlteraAltpllTest.scala" -> "the builder's test, asserting on what it writes",
    "spinalhdl/src/test/scala/jop/config/AlteraDesignPllTest.scala" -> "asserts on the generated PLLs' inputs",
    "spinalhdl/src/test/scala/jop/config/BoardClockTest.scala" -> "this test",
    "spinalhdl/src/test/scala/jop/config/XilinxClkWizTest.scala" -> "the clk_wiz builder's test, asserting on what it writes")

  private lazy val boards: Map[String, Board] =
    SystemAssembly.all.flatMap(_.boards).map(b => b.name -> b).toMap

  private def board(name: String): Board =
    boards.getOrElse(name, fail(s"no board named $name (boards: ${boards.keys.toSeq.sorted.mkString(", ")})"))

  private def mhz(c: BoardClock): BigDecimal = c.freq.toBigDecimal / 1000000

  private def clockOf(boardName: String, clock: String): BoardClock =
    board(boardName).clocks.find(_.name == clock)
      .getOrElse(fail(s"$boardName offers no clock $clock (it offers ${board(boardName).clocks.map(_.name).mkString(", ")})"))

  private def same(a: BigDecimal, b: BigDecimal) = (a - b).abs < BigDecimal("0.001")

  test("every PLL source states the frequency of the board clock that feeds it") {
    val wrong = for {
      s <- sources
      in <- s.inputs(s.path).distinct
      c = clockOf(s.board, s.clock)
      if !same(in, mhz(c))
    } yield s"${s.path} is built for $in MHz, but ${s.board}'s ${c.name} is ${mhz(c)} MHz"
    assert(wrong.isEmpty, wrong.mkString("\n", "\n", ""))
  }

  test("a generated PLL's input is the frequency of the board clock it takes") {
    // A design's PLL definition names its clock, and its spec is that clock's
    // frequency by construction (PllDefinition.spec); what can still go wrong
    // is naming a clock the assembly lacks, or another than the design takes in.
    val defined = for {
      m <- JopConfig.getClass.getMethods.toSeq
      if m.getParameterCount == 0 && m.getReturnType == classOf[JopConfig]
      cfg = m.invoke(JopConfig).asInstanceOf[JopConfig]
      s <- cfg.systems; p <- s.plls
    } yield (m.getName, cfg, p)
    assert(defined.size >= 4, s"only ${defined.size} designs define their PLL")
    for ((n, cfg, p) <- defined) {
      val c = cfg.assembly.clock(p.reference)
      assert(c == cfg.inputClock, s"$n: its PLL takes ${c.name}, the design takes in ${cfg.inputClock.name}")
      assert(same(BigDecimal(p.spec(cfg.assembly).inputMhz), mhz(c)), n)
    }
  }

  test("every file in the tree that states a PLL input is checked or excused") {
    import scala.sys.process._
    val files = Seq("git", "grep", "-l", "-E", "inclk0_input_frequency|PRIM_IN_FREQ|pll_ref_clk_mhz|-in_freq",
      "--", ":!docs", ":!*.md").lineStream_!.toSeq
    val known = sources.map(_.path).toSet
    val loose = files.filterNot(f => known(f) || notSources.keys.exists(f.startsWith))
    assert(loose.isEmpty, s"PLL inputs no row checks: ${loose.mkString(", ")}")
  }

  test("every PLL file a board names is checked or excused") {
    val named = boards.values.toSeq.flatMap { b =>
      b.pllType.flatMap(_.ipFile).collect { case PllIpFile.Static(p) => p }.toSeq
    }
    val known = sources.map(_.path).toSet
    assert(named.nonEmpty)
    assert(named.filterNot(known).isEmpty, s"PLL files no row checks: ${named.filterNot(known).mkString(", ")}")
  }

  // ---- the model ----

  test("every assembly offers a clock, and every clock reaches an FPGA pin") {
    for (a <- SystemAssembly.all) {
      assert(a.clocks.nonEmpty, s"${a.name} offers no clock")
      for (c <- a.clocks)
        assert(a.resolvePin(c.pin).isDefined, s"${a.name}: clock ${c.name} on ${c.pin} reaches no FPGA pin")
    }
  }

  test("the A-E115FB offers both of its clocks, and its designs take the 25 MHz one") {
    val a = SystemAssembly.ae115fb
    assert(a.clocks == Seq(BoardClock("CLK25M", 25 MHz, "PIN_AB11"), BoardClock("CLK100M", 100 MHz, "PIN_A11")))
    for (d <- Seq[BoardDesign](JopConfig.ae115fbDdr2, JopConfig.ae115fbDdr2Eth, JopConfig.ae115fbDdr2SmpMshr(8),
                               jop.system.Ddr2ExerciserDesign))
      assert(d.inputClock.name == "CLK25M", d.designName)
    assert(jop.system.Ddr2ExerciserDesign.clkMhz == 25)
  }

  private val twoClocks = SystemAssembly("two-clocks", Seq(Board.AE115FB))
  private val oneClock  = SystemAssembly("one-clock", Seq(Board.CYC5000))

  test("a design on an assembly with one clock takes it without naming it") {
    assert(BoardDesign.inputClock(oneClock, None) == BoardClock("CLK12M", 12 MHz, "PIN_F14"))
  }

  test("a design on an assembly with several clocks must name one") {
    val e = intercept[IllegalArgumentException](BoardDesign.inputClock(twoClocks, None))
    assert(e.getMessage.contains("CLK25M") && e.getMessage.contains("CLK100M") && e.getMessage.contains("clockName"),
      e.getMessage)
    assert(BoardDesign.inputClock(twoClocks, Some("CLK100M")).freq.toBigDecimal == 100000000)
  }

  test("a clock the assembly does not offer is refused, naming the ones it does") {
    val e = intercept[IllegalArgumentException](BoardDesign.inputClock(twoClocks, Some("CLK50M")))
    assert(e.getMessage.contains("CLK50M") && e.getMessage.contains("CLK25M, CLK100M"), e.getMessage)
  }

  test("two clocks of one name are refused, on a board and across an assembly") {
    intercept[IllegalArgumentException](Board.CYC5000.copy(clocks = Seq(
      BoardClock("CLK12M", 12 MHz, "PIN_F14"), BoardClock("CLK12M", 12 MHz, "PIN_A1"))))
    val e = intercept[IllegalArgumentException](SystemAssembly("dup", Seq(Board.CYC5000,
      Board.QmtechFpgaDbV4.copy(clocks = Seq(BoardClock("CLK12M", 12 MHz, "J2:5"))))))
    assert(e.getMessage.contains("CLK12M"), e.getMessage)
  }

  test("every preset and design takes in a clock its assembly offers") {
    val presets = JopConfig.getClass.getMethods.toSeq
      .filter(m => m.getParameterCount == 0 && m.getReturnType == classOf[JopConfig])
      .map(m => m.getName -> m.invoke(JopConfig).asInstanceOf[JopConfig])
    assert(presets.size > 40, s"only ${presets.size} presets found")
    val designs = presets ++ Seq[(String, BoardDesign)](
      "ConfigFlashExerciserDesign" -> jop.system.ConfigFlashExerciserDesign,
      "Ddr2ExerciserDesign"        -> jop.system.Ddr2ExerciserDesign,
      "FlashProgrammerDesign"      -> jop.system.FlashProgrammerDesign,
      "SdNativeExerciserDesign"    -> jop.system.SdNativeExerciserDesign,
      "SdSpiExerciserDesign"       -> jop.system.SdSpiExerciserDesign,
      "SdramExerciserDesign"       -> jop.system.SdramExerciserDesign,
      "UartTestDesign"             -> jop.system.UartTestDesign)
    for ((n, d) <- designs) {
      val c = d.inputClock
      assert(d.assembly.clocks.contains(c), s"$n takes ${c.name}, which ${d.assembly.name} does not offer")
      assert(jop.generate.PinResolver.clockFpgaPin(d).nonEmpty, n)
    }
  }
}
