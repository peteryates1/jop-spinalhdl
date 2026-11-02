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
    require(primary.phaseDeg == 0 && primary.phasePs == 0,
            "ecppll cannot phase-shift the primary output; put the shifted clock on a secondary")
    // ecppll takes an angle. Converting a delay would round it, and an SDRAM
    // clock a few degrees off its stated delay still builds and still locks.
    spec.outputs.filter(_.phasePs != 0).foreach(o => throw new IllegalArgumentException(
      s"ecppll takes a phase in degrees; state the ${o.role} output's ${o.phasePs} ps as phaseDeg"))

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

/**
 * Altera's ALTPLL: Cyclone IV E, Cyclone IV GX and MAX 10, one instance per
 * family (`FpgaFamily.pll`), named for `intended_device_family`.
 *
 * The generated module instantiates the `altpll` megafunction with the spec's
 * ratios, and Quartus chooses M, N and the output counters when it compiles
 * the design. So the divider search is still the vendor tool's, as with
 * ecppll; it runs at synthesis instead of before it, and an unreachable spec
 * fails there. What the file states is only what the spec states: each
 * output's multiply/divide (reduced), its phase in picoseconds, the input
 * period. Everything else is the parameter set of the hand-written
 * `pll_125.v`, which ran GMII on the A-E115FB and the EP4CGX150;
 * AlteraAltpllTest holds the two to each other.
 *
 * This replaces template-patching (DramPllGen rewrote five generics of a
 * 441-line MegaWizard file), which froze the output count and order in the
 * template and in a matching blackbox.
 */
case class AlteraAltpll(deviceFamily: String) extends PllVendor {

  /** c0..c4: ALTPLL has five output counters on these families. */
  val maxOutputs = 5

  private def gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

  /** altpll's multiply/divide for `o`, reduced -- the fraction Quartus reports
    * back as "clock multiplication of M, clock division of D". */
  def ratio(spec: PllSpec, o: PllOutput): (Int, Int) = {
    val g = gcd(o.mhz, spec.inputMhz)
    (o.mhz / g, spec.inputMhz / g)
  }

  /** The input period in whole picoseconds, as MegaWizard writes it: 12 MHz
    * is 83333. */
  def inputPeriodPs(spec: PllSpec): Int =
    (BigDecimal(1000000) / spec.inputMhz).setScale(0, BigDecimal.RoundingMode.HALF_UP).toInt

  def phasePs(o: PllOutput): Int = o.shiftPs.setScale(0, BigDecimal.RoundingMode.HALF_UP).toInt

  /** The module's text. */
  def module(spec: PllSpec, moduleName: String): String = {
    require(spec.outputs.size <= maxOutputs,
      s"ALTPLL on $deviceFamily has $maxOutputs output counters, the spec asks for ${spec.outputs.size}")
    val n = spec.outputs.size
    val outs = spec.outputs.zipWithIndex
    def p(name: String, value: Any) = s"\t\t.$name($value),"
    def q(name: String, value: String) = p(name, "\"" + value + "\"")
    def port(name: String, used: Boolean) = q(s"port_$name", if (used) "PORT_USED" else "PORT_UNUSED")

    val header = Seq(
      s"// GENERATED by jop.config.AlteraAltpll from a design's PLL definition",
      s"// (status item 173) -- do not edit; change the design and regenerate.",
      s"// $deviceFamily, ${spec.inputMhz} MHz in. Quartus chooses the counters when it",
      s"// compiles the design.") ++ outs.map { case (o, i) =>
        val (m, d) = ratio(spec, o)
        f"//   c$i%-2d ${o.describe}%-28s x$m/$d, ${phasePs(o)} ps"
      }
    val ports = Seq("\tinput  inclk0,") ++ outs.map { case (_, i) => s"\toutput c$i," } ++ Seq("\toutput locked")
    val params =
      Seq(q("bandwidth_type", "AUTO")) ++
      outs.flatMap { case (o, i) =>
        val (m, d) = ratio(spec, o)
        Seq(p(s"clk${i}_divide_by", d), p(s"clk${i}_duty_cycle", 50),
            p(s"clk${i}_multiply_by", m), q(s"clk${i}_phase_shift", phasePs(o).toString))
      } ++ Seq(
        q("compensate_clock", "CLK0"),
        p("inclk0_input_frequency", inputPeriodPs(spec)),
        q("intended_device_family", deviceFamily),
        q("lpm_hint", s"CBX_MODULE_PREFIX=$moduleName"),
        q("lpm_type", "altpll"),
        q("operation_mode", "NORMAL"),
        q("pll_type", "AUTO")) ++
      Seq("activeclock", "areset", "clkbad0", "clkbad1", "clkloss", "clkswitch", "configupdate",
          "fbin").map(port(_, used = false)) ++
      Seq(port("inclk0", used = true), port("inclk1", used = false), port("locked", used = true)) ++
      Seq("pfdena", "phasecounterselect", "phasedone", "phasestep", "phaseupdown", "pllena",
          "scanaclr", "scanclk", "scanclkena", "scandata", "scandataout", "scandone", "scanread",
          "scanwrite").map(port(_, used = false)) ++
      (0 to 5).map(i => port(s"clk$i", used = i < n)) ++
      (0 to 5).map(i => port(s"clkena$i", used = false)) ++
      (0 to 3).map(i => port(s"extclk$i", used = false)) ++
      Seq(q("self_reset_on_loss_lock", "OFF"), "\t\t.width_clock(5)")

    (header ++ Seq("", s"module $moduleName (") ++ ports ++ Seq(");", "",
      "\twire [4:0] sub_wire0;") ++
      outs.map { case (_, i) => s"\tassign c$i = sub_wire0[$i];" } ++
      Seq("", "\taltpll #(") ++ params ++ Seq(
      "\t) altpll_component (",
      "\t\t.inclk({1'b0, inclk0}),",
      "\t\t.clk(sub_wire0),",
      "\t\t.locked(locked),",
      "\t\t.activeclock(),",
      "\t\t.areset(1'b0),",
      "\t\t.clkbad(),",
      "\t\t.clkena({6{1'b1}}),",
      "\t\t.clkloss(),",
      "\t\t.clkswitch(1'b0),",
      "\t\t.configupdate(1'b0),",
      "\t\t.enable0(),",
      "\t\t.enable1(),",
      "\t\t.extclk(),",
      "\t\t.extclkena({4{1'b1}}),",
      "\t\t.fbin(1'b1),",
      "\t\t.fbmimicbidir(),",
      "\t\t.fbout(),",
      "\t\t.fref(),",
      "\t\t.icdrclk(),",
      "\t\t.pfdena(1'b1),",
      "\t\t.phasecounterselect({4{1'b1}}),",
      "\t\t.phasedone(),",
      "\t\t.phasestep(1'b1),",
      "\t\t.phaseupdown(1'b1),",
      "\t\t.pllena(1'b1),",
      "\t\t.scanaclr(1'b0),",
      "\t\t.scanclk(1'b0),",
      "\t\t.scanclkena(1'b1),",
      "\t\t.scandata(1'b0),",
      "\t\t.scandataout(),",
      "\t\t.scandone(),",
      "\t\t.scanread(1'b0),",
      "\t\t.scanwrite(1'b0),",
      "\t\t.sclkout0(),",
      "\t\t.sclkout1(),",
      "\t\t.vcooverrange(),",
      "\t\t.vcounderrange()",
      "\t);", "", "endmodule", "")).mkString("\n")
  }

  def generate(spec: PllSpec, moduleName: String, outDir: String): String = {
    val text = module(spec, moduleName)
    Files.createDirectories(Paths.get(outDir))
    val out = Paths.get(outDir, s"$moduleName.v")
    Files.write(out, text.getBytes("UTF-8"))
    out.toString
  }

  /** The module `generate` writes. */
  private class Altpll(moduleName: String, outputs: Int) extends BlackBox {
    setDefinitionName(moduleName)
    val inclk0 = in Bool()
    val c      = (0 until outputs).map(i => out(Bool()).setName(s"c$i"))
    val locked = out Bool()
  }

  def instantiate(spec: PllSpec, moduleName: String, instance: String, clkIn: Bool): PllClocks = {
    module(spec, moduleName)   // the same refusals as `generate`
    val pll = new Altpll(moduleName, spec.outputs.size)
    pll.setName(instance)
    pll.inclk0 := clkIn
    PllClocks(pll.c, pll.locked)
  }

  /** How the generated SDC names output `n` of the instance: Quartus calls the
    * altpll's counters `<inst>|altpll_component|auto_generated|pll1|clk[n]`. */
  def clockPath(instance: String, n: Int): String =
    s"$instance|altpll_component|auto_generated|pll1|clk[$n]"
}
