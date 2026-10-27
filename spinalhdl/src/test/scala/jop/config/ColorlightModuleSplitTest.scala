package jop.config

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import jop.generate.{LpfGenerator, PinResolver, XdcGenerator}

/**
 * Colorlight modules and the boards they plug into are separate Boards, so any
 * module can sit on any base board (2026-10-10). This pins down what that
 * means, for every pairing that exists:
 *
 *   i5  module on the i5/i9 ext board      -- colorlightI5Sdram, on hardware
 *   i9+ module on the Atari 800 base board -- colorlightDbI9PlusSdram, on hardware
 *   i5  module on the Atari 800 base board -- colorlightDbI5Sdram, on the
 *       SECOND base board (its own stamp, so its own aliases); the balls come
 *       from the two SODIMM tables
 *
 * The split itself was checked by regenerating RTL, constraints, summary and
 * hw_verify descriptor for the four existing configs before and after: the
 * constraints are byte-identical, the Verilog is identical once SpinalHDL's
 * source-line name tags are normalised, and only the summary/descriptor board
 * NAME changed (colorlight-db-i9plus -> colorlight-i9plus, the module).
 */
class ColorlightModuleSplitTest extends AnyFunSuite {

  private def pins(constraints: String, re: scala.util.matching.Regex): Map[String, String] =
    re.findAllMatchIn(constraints).map(m => m.group(2) -> m.group(1)).toMap
  private val xdcPin = """set_property PACKAGE_PIN (\S+) \[get_ports \{([^}]+)\}\]""".r
  private val lpfPin = """LOCATE COMP "([^"]+)" SITE "([^"]+)";""".r
  private def lpfPins(lpf: String): Map[String, String] =
    lpfPin.findAllMatchIn(lpf).map(m => m.group(1) -> m.group(2)).toMap

  /** The i5 on the Atari base board: its preset, looked up by name so that,
    * before the preset existed, this failed at run time rather than not
    * compiling. */
  private def i5OnAtariBase = jop.system.JopTopVerilog.resolvePreset("colorlightDbI5Sdram")

  test("every base-board pin resolves on every module it can carry") {
    val pairs = Seq(SystemAssembly.colorlightI5, SystemAssembly.colorlightDbI9Plus, SystemAssembly.colorlightDbI5)
    val unresolved = for {
      a <- pairs
      b <- a.boards if !b.hasFpga
      ref <- b.devices.flatMap(_.mapping.values) ++ b.parkedPins.values
      if a.resolvePin(ref).isEmpty
    } yield s"${a.name}: ${b.name} $ref"
    // PinResolver drops a pin that does not resolve, so a missing SODIMM pin
    // would build a design with the port silently unconstrained.
    assert(unresolved.isEmpty, unresolved.mkString("\n"))
  }

  test("i5 on its ext board: DAPLink console J17/H18, the module's LED, parking on 69-79") {
    val lpf = LpfGenerator.generate(JopConfig.colorlightI5Sdram)
    val p = lpfPins(lpf)
    assert(p.get("ser_txd").contains("J17") && p.get("ser_rxd").contains("H18"), p)
    assert(p.get("led[0]").contains("U16"), p)
    assert(SystemAssembly.colorlightI5.ledCount == 1 && !SystemAssembly.colorlightI5.ledActiveHigh)
    assert(Seq("sdram_CKE" -> "U18", "sdram_CSn" -> "U17", "sdram_DQM[0]" -> "P18",
      "sdram_DQM[3]" -> "M18").forall { case (k, v) => p.get(k).contains(v) }, p)
    assert(SystemAssembly.colorlightI5.probeAlias.contains("i5"))
  }

  test("i9+ on the Atari base: the base board's console, eight LEDs, reset and aliases") {
    val p = pins(XdcGenerator.generate(JopConfig.colorlightDbI9PlusSdram()), xdcPin)
    assert(p.get("ser_txd").contains("AB21") && p.get("ser_rxd").contains("Y21"), p)
    assert((0 until 8).map(i => p(s"led[$i]")) == Seq("M1", "L1", "K1", "J1", "H3", "U1", "V5", "U5"), p)
    assert(p.get("resetn").contains("P5"), p)
    val a = SystemAssembly.colorlightDbI9Plus
    assert(a.ledCount == 8 && a.ledActiveHigh)
    assert(a.probeAlias.contains("colorlight-db") && a.consoleAlias.contains("colorlight-db-0"))
    assert(a.fpgaBoard.name == "colorlight-i9plus")
  }

  test("i5 on the Atari base: the SAME base board, the i5's balls") {
    // Expected balls: ColorlightSodimm.SodimmI5 at the base board's SODIMM pins
    // (153/155 console, 66..50 LEDs, 42 reset, 57..67 parking), which also
    // match the i5 column of the base board review's pinmap.csv.
    val p = lpfPins(LpfGenerator.generate(i5OnAtariBase))
    assert(p.get("ser_txd").contains("E1") && p.get("ser_rxd").contains("F2"), p)
    assert((0 until 8).map(i => p(s"led[$i]")) == Seq("T3", "M3", "T2", "N3", "N2", "M1", "V1", "W1"), p)
    assert(Seq("sdram_CKE" -> "T18", "sdram_CSn" -> "R18", "sdram_DQM[0]" -> "R17",
      "sdram_DQM[3]" -> "T17").forall { case (k, v) => p.get(k).contains(v) }, p)
    // The module's own LED is NOT driven here -- the base board's eight are.
    assert(!p.values.exists(_ == "U16"), s"the i5's on-module LED leaked in: $p")
    val a = SystemAssembly.colorlightDbI5
    assert(a.ledCount == 8 && a.ledActiveHigh)
    // The second base board: its own stamp, so its own probe and console.
    assert(a.probeAlias.contains("colorlight-db-i5") && a.consoleAlias.contains("colorlight-db-i5-0"),
      s"${a.probeAlias} / ${a.consoleAlias}")
    assert(i5OnAtariBase.assembly.name == a.name)
  }

  test("the LED source follows ledBoard, not the order the boards are listed in") {
    // Both boards name their LEDs led0..; the pre-split rule merged them by
    // name, and the LAST board's led0 won. With the module listed first that
    // happened to be the base board -- so the tests above pass under the old
    // rule too (checked 2026-10-10). Listed the other way round, the old rule
    // puts the i5's own U16 on led[0].
    val reversed = SystemAssembly("colorlight-db-i5-reversed",
      Seq(Board.ColorlightAtari800BaseV1, Board.ColorlightI5),
      ledBoard = Some(Board.ColorlightAtari800BaseV1.name))
    val leds = PinResolver.ledPins(reversed).map(p => p.verilogPort -> p.fpgaPin).toMap
    assert((0 until 8).map(i => leds(s"led[$i]")) == Seq("T3", "M3", "T2", "N3", "N2", "M1", "V1", "W1"), leds)
  }

  test("no base-board pin lands on a ball the module itself uses") {
    // A SODIMM pin is free FPGA I/O, never the module's oscillator or SDRAM.
    for (a <- Seq(SystemAssembly.colorlightI5, SystemAssembly.colorlightDbI9Plus, SystemAssembly.colorlightDbI5)) {
      val module = a.fpgaBoard.devices.filter(_.part != "LED").flatMap(_.mapping.values).toSet
      val base = a.boards.filterNot(_.hasFpga)
        .flatMap(b => b.devices.flatMap(_.mapping.values) ++ b.parkedPins.values)
        .flatMap(a.resolvePin)
      val clash = base.filter(module)
      assert(clash.isEmpty, s"${a.name}: base-board pins on module balls: $clash")
    }
  }

  test("boards that disagree on how an assembly is reached are refused") {
    val other = Board.ColorlightAtari800BaseV1.copy(name = "other", probeAlias = Some("elsewhere"))
    val a = SystemAssembly("bad", Seq(Board.ColorlightI9Plus, Board.ColorlightAtari800BaseV1, other))
    intercept[IllegalArgumentException](a.probeAlias)
  }
}
