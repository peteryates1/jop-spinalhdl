package jop.config

import spinal.core._
import java.nio.file.{Files, Paths}

/**
 * How an FPGA family builds a PLL: it GENERATES the IP for a spec with the
 * vendor's own tool, and INSTANTIATES that IP in a design. A design states
 * what it wants (`PllDefinition`); the family supplies this (`FpgaFamily.pll`),
 * and every design on the family reuses it (status item 173). It lives with the
 * FPGA families, not in JOP's generators: nothing in it is JOP's.
 *
 * The alternative -- and what this replaces -- is hand-written IP checked into
 * the tree with a Scala blackbox that must match it. `fpga/colorlight-i5/
 * pll_jop_i5.v` was 60 lines of EHXPLLL parameters whose own header records the
 * `ecppll` command that produced it: a generator invocation written down as a
 * file. `fpga/qmtech-ep4cgx150-sdram/dram_pll.vhd` is 441 lines of the same
 * thing, text-patched for frequency.
 *
 * Divider selection is exactly the part not to do by hand. ecppll picks a set it
 * guarantees will lock -- Fvco inside the legal band -- and hand-tuning that, or
 * the CPHASE/FPHASE pair implementing a phase shift, is the standard way to get
 * a PLL that never asserts LOCK. That presents as the core sitting silently in
 * reset with no other symptom.
 */
trait PllVendor {
  /** Emit the IP for `spec` as `moduleName` into `outDir`. Returns the file. */
  def generate(spec: PllSpec, moduleName: String, outDir: String): String

  /** Instantiate the module `generate` writes for `spec`, as `instance`, fed
    * from `clkIn`: one clock per output in the spec's order, and its lock. */
  def instantiate(spec: PllSpec, moduleName: String, instance: String, clkIn: Bool): PllClocks
}

/** What an instantiated PLL gives the design: its outputs in the spec's order,
  * and its lock. */
case class PllClocks(outputs: Seq[Bool], locked: Bool)

/**
 * Lattice ECP5, via `ecppll` from Project Trellis.
 *
 * The open-source arm, and the only one CI can run: ecppll is a ~200 KB apt
 * package (fpga-trellis), so CI regenerates and diffs outright rather than
 * checking a spec against a committed artefact.
 */
object LatticeEcpPll extends PllVendor {

  /** ecppll names outputs clkout0..3 in declaration order, and only the
    * secondary ones take a phase. */
  def command(spec: PllSpec, moduleName: String, outFile: String): Seq[String] = {
    require(spec.outputs.size <= 4,
            s"ECP5 PLLs have at most 4 outputs, spec asks for ${spec.outputs.size}")
    val primary = spec.outputs.head
    require(primary.phaseDeg == 0,
            "ecppll cannot phase-shift the primary output; put the shifted clock on a secondary")

    // Port names must match the blackbox `instantiate` declares: `clkin`,
    // `clkout0`.. and `locked`. Stated rather than left to ecppll's defaults:
    // the two agreeing is load-bearing, and a silent default is not the place
    // to record that.
    val base = Seq("ecppll",
      "--module", moduleName,
      "--clkin_name", "clkin",
      "--clkin", spec.inputMhz.toString,
      "--clkout0_name", "clkout0",
      "--clkout0", primary.mhz.toString)

    val rest = spec.outputs.tail.zipWithIndex.flatMap { case (o, i) =>
      val n = i + 1
      Seq(s"--clkout${n}_name", s"clkout$n", s"--clkout$n", o.mhz.toString) ++
        (if (o.phaseDeg != 0) Seq(s"--phase$n", o.phaseDeg.toString) else Nil)
    }
    base ++ rest ++ Seq("--file", outFile)
  }

  def generate(spec: PllSpec, moduleName: String, outDir: String): String = {
    Files.createDirectories(Paths.get(outDir))
    val out = Paths.get(outDir, s"$moduleName.v").toString
    val cmd = command(spec, moduleName, out)
    val pb  = new ProcessBuilder(cmd: _*).redirectErrorStream(true)
    val p   = pb.start()
    val log = scala.io.Source.fromInputStream(p.getInputStream).mkString
    val rc  = p.waitFor()
    require(rc == 0, s"ecppll failed (rc=$rc):\n$log\ncommand: ${cmd.mkString(" ")}")
    require(Files.exists(Paths.get(out)), s"ecppll reported success but wrote no $out")
    out
  }

  /** The module ecppll writes, with the port names `command` gives it. */
  private class Ehxplll(moduleName: String, outputs: Int) extends BlackBox {
    setDefinitionName(moduleName)
    val clkin  = in Bool()
    val clkout = (0 until outputs).map(i => out(Bool()).setName(s"clkout$i"))
    val locked = out Bool()
  }

  def instantiate(spec: PllSpec, moduleName: String, instance: String, clkIn: Bool): PllClocks = {
    command(spec, moduleName, "-")   // the same refusals as `generate`
    val pll = new Ehxplll(moduleName, spec.outputs.size)
    pll.setName(instance)
    pll.clkin := clkIn
    PllClocks(pll.clkout, pll.locked)
  }
}
