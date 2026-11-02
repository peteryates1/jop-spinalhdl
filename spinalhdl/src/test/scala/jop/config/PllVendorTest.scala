package jop.config

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
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

  /** The i5 designs' PLL, which the ECP5 family builds (status item 173). */
  private def i5Spec = JopConfig.colorlightI5Pll.spec(SystemAssembly.colorlightI5)

  private val ecppllPresent = new java.io.File("/usr/bin/ecppll").canExecute

  /**
   * The divider set that was HARDWARE-VALIDATED: DoAll 66/66 on the i5 at
   * 49.40 MHz, 2026-08-25.
   *
   * Recorded here because the file it used to be compared against is now
   * generated and therefore deleted -- and a test whose oracle has been removed
   * does not fail, it CANCELS, which is worse than having no test. This is the
   * same trap that makes deleting ConstraintDriftTest's hand-written .xdc
   * oracles a deliberate act rather than cleanup.
   *
   * Not circular: these numbers came from the artefact that ran on hardware, so
   * they catch a spec change, an ecppll version whose divider search moves, and
   * a hand-edit. What they cannot catch is whether a DIFFERENT valid divider set
   * would also work -- that is fine, because the point is that this design keeps
   * running the PLL that was validated.
   */
  private val validatedDividers = Map(
    "CLKI_DIV"     -> 5,   // 25 MHz / 5   = 5 MHz phase detector
    "CLKFB_DIV"    -> 8,   // 5 MHz * 8 * 15 = 600 MHz VCO, inside the 400-800 band
    "CLKOP_DIV"    -> 15,  // 600 / 15 = 40 MHz system
    "CLKOS_DIV"    -> 15,  // 600 / 15 = 40 MHz SDRAM
    "CLKOP_CPHASE" -> 7,
    "CLKOS_CPHASE" -> 20)  // the 315 deg shift

  private def dividers(v: String): Map[String, Int] =
    """\.([A-Z_]+)\((\d+)\)""".r.findAllMatchIn(v)
      .map(m => m.group(1) -> m.group(2).toInt).toMap

  test("the i5 spec regenerates the validated divider set") {
    assume(ecppllPresent, "ecppll not installed (Debian: fpga-trellis)")
    val spec = i5Spec
    val dir = Files.createTempDirectory("ecppll").toString
    val out = LatticeEcpPll.generate(spec, "pll_jop_i5", dir)
    val got = dividers(new String(Files.readAllBytes(Paths.get(out)), "UTF-8"))

    validatedDividers.foreach { case (k, want) =>
      assert(got.get(k).contains(want),
        s"$k is ${got.getOrElse(k, "absent")}, hardware-validated value is $want -- " +
        "the spec, the tool, or the file has moved")
    }
  }

  test("the builder instantiates the module it generates, port for port") {
    // ecppll is told the port names (`command`); the blackbox `instantiate`
    // declares them again. They must agree, and the mismatch would otherwise
    // appear only at synthesis.
    assume(ecppllPresent, "ecppll not installed")
    val spec = i5Spec
    val dir = Files.createTempDirectory("ecppll").toString
    val module = new String(Files.readAllBytes(
      Paths.get(LatticeEcpPll.generate(spec, "pll_x", dir))), "UTF-8")
    val declared = """(?m)^\s*(?:input|output)\s+(\w+)""".r.findAllMatchIn(module).map(_.group(1)).toSet

    SpinalConfig(mode = Verilog, targetDirectory = dir).generate(new Component {
      setDefinitionName("PllTop")
      val clk  = in Bool()
      val outs = out Bits(spec.outputs.size + 1 bits)
      val pll  = LatticeEcpPll.instantiate(spec, "pll_x", "thePll", clk)
      outs := Cat(pll.outputs :+ pll.locked)
    })
    val top = new String(Files.readAllBytes(Paths.get(dir, "PllTop.v")), "UTF-8")
    val inst = """(?s)pll_x\s+thePll\s*\((.*?)\);""".r.findFirstMatchIn(top)
      .getOrElse(fail(s"no `pll_x thePll` instance in\n$top")).group(1)
    val connected = """\.(\w+)\s*\(""".r.findAllMatchIn(inst).map(_.group(1)).toSet
    assert(connected == declared, s"instance connects $connected, the module declares $declared")
    assert(declared == Set("clkin", "locked") ++ spec.outputs.indices.map(i => s"clkout$i"))
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
