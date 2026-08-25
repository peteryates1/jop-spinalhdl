package jop.config

import org.scalatest.funsuite.AnyFunSuite
import jop.generate.LatticeEcpPll
import java.nio.file.{Files, Paths}

/**
 * Generating the ECP5 PLL from its declared spec must reproduce the file that
 * was checked in -- which is what makes deleting that file safe.
 *
 * This is the open-source arm, so CI can run it for real rather than checking a
 * committed artefact against a spec the way the Quartus and Vivado arms must
 * (see PllSpecConsistencyTest).
 */
class PllVendorTest extends AnyFunSuite {

  private val tracked = "fpga/colorlight-i5/pll_jop_i5.v"
  private val ecppllPresent = new java.io.File("/usr/bin/ecppll").canExecute

  /** Module body only: the tracked file carries a hand-written header
    * explaining the divider choice, which is documentation, not netlist. */
  private def body(text: String): String =
    text.linesIterator.dropWhile(!_.startsWith("module")).mkString("\n")

  test("the i5 spec regenerates the tracked PLL bit-identically") {
    assume(ecppllPresent, "ecppll not installed (Debian: fpga-trellis)")
    assume(Files.exists(Paths.get(tracked)), "tracked PLL not present")

    val spec = Board.ColorlightI5.pllType.flatMap(_.spec)
      .getOrElse(fail("Board.ColorlightI5 declares no PLL spec"))

    val dir = Files.createTempDirectory("ecppll").toString
    val out = LatticeEcpPll.generate(spec, "pll_jop_i5", dir)

    val got  = body(new String(Files.readAllBytes(Paths.get(out)), "UTF-8"))
    val want = body(new String(Files.readAllBytes(Paths.get(tracked)), "UTF-8"))
    assert(got == want,
      "generated ECP5 PLL differs from the tracked file -- the spec and the " +
      "committed IP have diverged")
  }

  test("the command follows the spec's roles and phases") {
    val spec = PllSpec(25, Seq(
      PllOutput(PllRole.System, 40),
      PllOutput(PllRole.Sdram, 40, phaseDeg = 315)))
    val cmd = LatticeEcpPll.command(spec, "m", "/tmp/m.v").mkString(" ")
    assert(cmd.contains("--clkin 25"))
    assert(cmd.contains("--clkout0 40"))
    assert(cmd.contains("--clkout1 40"))
    assert(cmd.contains("--phase1 315"))
  }

  test("a phase-shifted primary output is refused, not silently dropped") {
    // ecppll cannot shift clkout0. Accepting it would emit a PLL whose SDRAM
    // clock is in phase with the system clock -- which looks fine and fails on
    // hardware.
    val bad = PllSpec(25, Seq(PllOutput(PllRole.System, 40, phaseDeg = 90)))
    assertThrows[IllegalArgumentException](LatticeEcpPll.command(bad, "m", "/tmp/m.v"))
  }
}
