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
 * file. `fpga/qmtech-ep4cgx150-sdram/dram_pll.vhd` was 441 lines of the same
 * thing, text-patched for frequency (DramPllGen, until 2026-11-02).
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
    * from `clkIn`: one clock per output in the spec's order, and its lock.
    * `reset`, where given, holds the PLL in reset (active high); a builder
    * whose module has no reset refuses one rather than dropping it. */
  def instantiate(spec: PllSpec, moduleName: String, instance: String, clkIn: Bool,
                  reset: Option[Bool] = None): PllClocks

  /** True when `generate` only writes text, so the Verilog generator writes
    * the PLL beside the design (DesignPlls); false when it runs a vendor tool,
    * which the board flow runs instead (PllGenMain -- ecppll is not on every
    * host that elaborates). */
  def generatedWithVerilog: Boolean = false

  /** The file `generate` writes for `moduleName`. */
  def fileName(moduleName: String): String = s"$moduleName.v"

  /** How a timing constraint names output `n` of `instance`, where the
    * toolchain needs it named (Quartus); None where it derives PLL outputs
    * itself. */
  def clockPath(instance: String, n: Int): Option[String] = None
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

  def instantiate(spec: PllSpec, moduleName: String, instance: String, clkIn: Bool,
                  reset: Option[Bool]): PllClocks = {
    command(spec, moduleName, "-")   // the same refusals as `generate`
    require(reset.isEmpty, s"PLL $moduleName: the module ecppll writes has no reset")
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
 * `pll_125.v` that ran GMII on the A-E115FB and the EP4CGX150; the files are
 * generated now, and AlteraAltpllTest holds the builder to that set.
 *
 * This replaces template-patching (DramPllGen rewrote five generics of a
 * 441-line MegaWizard file), which froze the output count and order in the
 * template and in a matching blackbox.
 */
case class AlteraAltpll(deviceFamily: String) extends PllVendor {

  /** c0..c4: ALTPLL has five output counters on these families. */
  val maxOutputs = 5

  /** altpll's multiply/divide for `o`, reduced -- the fraction Quartus reports
    * back as "clock multiplication of M, clock division of D". Exact for a
    * fractional output too: 97.787 MHz from 50 is 97787/50000. */
  def ratio(spec: PllSpec, o: PllOutput): (Int, Int) = {
    val f     = o.mhz.bigDecimal.stripTrailingZeros
    val scale = math.max(f.scale, 0)
    val num   = f.movePointRight(scale).toBigIntegerExact
    val den   = java.math.BigInteger.valueOf(spec.inputMhz).multiply(java.math.BigInteger.TEN.pow(scale))
    val g     = num.gcd(den)
    (num.divide(g).intValueExact, den.divide(g).intValueExact)
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

  def instantiate(spec: PllSpec, moduleName: String, instance: String, clkIn: Bool,
                  reset: Option[Bool]): PllClocks = {
    module(spec, moduleName)   // the same refusals as `generate`
    require(reset.isEmpty, s"PLL $moduleName: the altpll module written here ties areset off; it takes no reset")
    val pll = new Altpll(moduleName, spec.outputs.size)
    pll.setName(instance)
    pll.inclk0 := clkIn
    PllClocks(pll.c, pll.locked)
  }

  override def generatedWithVerilog = true

  /** Quartus names the altpll's counters
    * `<inst>|altpll_component|auto_generated|pll1|clk[n]`. */
  override def clockPath(instance: String, n: Int): Option[String] =
    Some(s"$instance|altpll_component|auto_generated|pll1|clk[$n]")
}

/**
 * Xilinx 7-series (Artix-7), through Vivado's clocking wizard, clk_wiz.
 *
 * What `generate` writes is the IP's DEFINITION: a Tcl file that creates the
 * clk_wiz with the spec's outputs, sourced by the Vivado flow
 * (fpga/scripts/vivado_build_nonproject.tcl and vivado_create_project.tcl,
 * `JOP_PLL_TCL`), which then generates and synthesises the IP in the build
 * that uses it. So the divider search is still the vendor tool's, as with
 * ecppll and ALTPLL, and the IP can never be older than the design: until
 * 2026-11 each board created its clk_wiz by hand (`make create-ip`), from
 * scripts that knew nothing of the preset, into per-frequency directories
 * that `check_clk_ip.py` had to match against the build afterwards.
 *
 * Unlike ALTPLL's counters, which make each ratio exactly or fail to compile,
 * clk_wiz picks ONE VCO for every output and the nearest divide for each --
 * and says nothing when that misses. Asked for 80 MHz and its SDRAM copy
 * beside a 125 MHz output, it made 80.09 and 80.80 MHz: timing met, every
 * SDRAM access corrupt (status item 164). So the definition reads back the
 * MMCM's settings and refuses any output further from its request than the
 * design's tolerance (`PllOutput.tolerancePpm`, exact by default).
 *
 * The rest is the configuration of the hand-written scripts that ran on
 * hardware (XilinxClkWizTest holds it), with two changes. Outputs are named
 * by ROLE (`clk_sys`, `clk_sdram`, `clk_eth`, `clk_mig_sys`, `clk_mig_ref`,
 * `clk_vga`), and Vivado names the clocks it derives after them --
 * `clk_eth_<module>`: the scripts named them by frequency, so the i9+'s
 * 50 MHz RGMII clock was `clk_125`. And the reset is `reset`, active high,
 * on every IP: the DDR3 and BRAM IPs were active high under the name
 * `resetn`, the SDR one active low.
 */
object XilinxClkWiz extends PllVendor {

  /** An MMCM has seven outputs; clk_wiz drives them all. */
  val maxOutputs = 7

  /** The output port for `role` -- and so, in Vivado, the derived clock's
    * name: `<port>_<module>`. */
  def port(role: PllRole): String = role match {
    case PllRole.System => "clk_sys"
    case PllRole.Sdram  => "clk_sdram"
    case PllRole.Eth    => "clk_eth"
    case PllRole.Vga    => "clk_vga"
    case PllRole.MigSys => "clk_mig_sys"
    case PllRole.MigRef => "clk_mig_ref"
  }

  /** The phase clk_wiz is asked for, in degrees of `o`: it takes an angle,
    * and a delay is converted at the output's own frequency (-3000 ps is
    * -108 deg at 100 MHz, -86.4 at 80). */
  def phaseDeg(o: PllOutput): BigDecimal =
    if (o.phasePs != 0) BigDecimal(o.phasePs) * 360 * o.mhz / 1000000 else BigDecimal(o.phaseDeg)

  private def num(x: BigDecimal): String = x.setScale(3, BigDecimal.RoundingMode.HALF_UP).toString

  /** The MMCM setting that divides the VCO down to clk_wiz output `n` (from
    * 1): its CLKOUT1 is the MMCM's CLKOUT0, the only one with a fractional
    * divide. clk_wiz keeps its computed settings in these MMCM_* properties
    * whichever primitive it uses. */
  private def divider(n: Int): String =
    if (n == 1) "MMCM_CLKOUT0_DIVIDE_F" else s"MMCM_CLKOUT${n - 1}_DIVIDE"

  /** The definition's text. */
  def definition(spec: PllSpec, moduleName: String): String = {
    require(spec.outputs.size <= maxOutputs,
      s"PLL $moduleName: an MMCM has $maxOutputs outputs, the spec asks for ${spec.outputs.size}")
    val twice = spec.outputs.map(_.role).diff(spec.outputs.map(_.role).distinct).distinct
    require(twice.isEmpty, s"PLL $moduleName: ${twice.mkString(", ")} asked for twice; " +
      "a clk_wiz port is named for its role, so each role is one output")
    val outs = spec.outputs.zipWithIndex.map { case (o, i) => (o, i + 1) }

    val header = Seq(
      s"# GENERATED by jop.config.XilinxClkWiz from a design's PLL definition",
      s"# (status item 173) -- do not edit; change the design and regenerate.",
      s"# $moduleName: clk_wiz, MMCM, ${spec.inputMhz} MHz in. Vivado picks the counters",
      s"# when it creates the IP; the check at the end refuses a choice that misses",
      s"# an output by more than the design allows.") ++
      outs.map { case (o, n) =>
        val deg = phaseDeg(o)
        f"#   ${port(o.role)}%-12s ${o.describe}" + (if (o.phasePs != 0) s" = ${num(deg)} deg" else "")
      } ++ Seq(
      "#",
      "# Sourced by the Vivado flow (JOP_PLL_TCL), with jop_ip_dir set to the",
      "# directory the IP goes in; the flow generates and synthesises it.")

    val props =
      Seq("PRIMITIVE" -> "MMCM", "PRIM_IN_FREQ" -> num(spec.inputMhz), "PRIMARY_PORT" -> "clk_in",
          "NUM_OUT_CLKS" -> outs.size.toString) ++
      outs.flatMap { case (o, n) => Seq(
        s"CLKOUT${n}_USED" -> "true",
        s"CLKOUT${n}_REQUESTED_OUT_FREQ" -> num(o.mhz),
        s"CLKOUT${n}_REQUESTED_PHASE" -> num(phaseDeg(o)),
        s"CLK_OUT${n}_PORT" -> port(o.role),
        s"CLKOUT${n}_DRIVES" -> "BUFG") } ++
      Seq("USE_RESET" -> "true", "RESET_TYPE" -> "ACTIVE_HIGH", "RESET_PORT" -> "reset",
          "USE_LOCKED" -> "true", "LOCKED_PORT" -> "locked")

    (header ++ Seq("",
      s"create_ip -name clk_wiz -vendor xilinx.com -library ip -module_name $moduleName -dir $$jop_ip_dir",
      "set_property -dict [list \\") ++
      props.map { case (k, v) => s"  CONFIG.$k {$v} \\" } ++
      Seq(s"] [get_ips $moduleName]", "",
        "# What the MMCM Vivado chose makes: one VCO serves every output, and each",
        "# output's divide is the nearest it has. Refuse an output outside its",
        "# tolerance -- an SDRAM clock 1 % off its system clock met timing and",
        "# corrupted every access (status item 164).",
        s"set ip  [get_ips $moduleName]",
        s"set vco [expr {${num(spec.inputMhz)} * [get_property CONFIG.MMCM_CLKFBOUT_MULT_F $$ip] / " +
          "[get_property CONFIG.MMCM_DIVCLK_DIVIDE $ip]}]",
        "foreach {n divider want ppm} {") ++
      outs.map { case (o, n) => s"  $n ${divider(n)} ${num(o.mhz)} ${o.tolerancePpm}" } ++
      Seq("} {",
        "  set have [expr {$vco / [get_property CONFIG.$divider $ip]}]",
        "  set off  [expr {abs($have - $want) / $want * 1e6}]",
        "  if {$off > $ppm + 0.001} {",
        s"""    error "$moduleName: output $$n makes [format %.4f $$have] MHz, [format %.0f $$off] ppm from the $$want MHz the design asks for (it allows $$ppm ppm)"""",
        "  }",
        s"""  puts "$moduleName: output $$n makes [format %.4f $$have] MHz for $$want MHz -- ok"""",
        "}", "")).mkString("\n")
  }

  def generate(spec: PllSpec, moduleName: String, outDir: String): String = {
    val text = definition(spec, moduleName)
    Files.createDirectories(Paths.get(outDir))
    val out = Paths.get(outDir, fileName(moduleName))
    Files.write(out, text.getBytes("UTF-8"))
    out.toString
  }

  override def fileName(moduleName: String): String = s"$moduleName.tcl"

  /** The module the IP has: `clk_in`, `reset`, an output per role, `locked`. */
  private class ClkWiz(moduleName: String, ports: Seq[String]) extends BlackBox {
    setDefinitionName(moduleName)
    val clk_in = in Bool()
    val reset  = in Bool()
    val clk    = ports.map(p => out(Bool()).setName(p))
    val locked = out Bool()
  }

  def instantiate(spec: PllSpec, moduleName: String, instance: String, clkIn: Bool,
                  reset: Option[Bool]): PllClocks = {
    definition(spec, moduleName)   // the same refusals as `generate`
    val w = new ClkWiz(moduleName, spec.outputs.map(o => port(o.role)))
    w.setName(instance)
    w.clk_in := clkIn
    w.reset  := reset.getOrElse(False)
    PllClocks(w.clk, w.locked)
  }

  override def generatedWithVerilog = true
}
