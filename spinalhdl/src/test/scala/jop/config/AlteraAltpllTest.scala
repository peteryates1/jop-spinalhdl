package jop.config

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import java.nio.file.{Files, Paths}

/**
 * The ALTPLL builder: Cyclone IV E, Cyclone IV GX and MAX 10 (status item 173).
 *
 * Quartus picks the counters when it compiles the design, so what the builder
 * writes is only what the spec states -- each output's ratio and phase, and the
 * input period -- in the altpll instantiation the hand-written PLLs here use.
 * CI cannot run Quartus; these tests hold the generated text to the spec and
 * to the files that ran on hardware.
 */
class AlteraAltpllTest extends AnyFunSuite {

  private def params(v: String): Map[String, String] =
    """\.(\w+)\(("[^"]*"|[^()]*)\)""".r.findAllMatchIn(
      v.substring(v.indexOf("altpll #("), v.indexOf(") altpll_component")))
      .map(m => m.group(1) -> m.group(2).trim).toMap

  private def read(p: String) = new String(Files.readAllBytes(Paths.get(p)), "UTF-8")

  private def generated(spec: PllSpec, family: FpgaFamily, module: String): String = {
    val dir = Files.createTempDirectory("altpll").toString
    read(family.pll.get.generate(spec, module, dir))
  }

  /** Every altpll parameter of the hand-written fpga/a-e115fb-ddr2/pll_125.v,
    * which ran GMII at 1 Gb/s on the A-E115FB (NetTest). RECORDED, because
    * the file is generated now and was deleted (status item 173, step 7d): a
    * test whose oracle is removed does not fail, it cancels. Until then this
    * compared against that file and the EP4CGX150's copy, which differed only
    * in the three values its 50 MHz input and family set. */
  private val validatedPll125: Map[String, String] = Map(
    "bandwidth_type" -> "\"AUTO\"",
    "clk0_divide_by" -> "1",
    "clk0_duty_cycle" -> "50",
    "clk0_multiply_by" -> "5",
    "clk0_phase_shift" -> "\"0\"",
    "compensate_clock" -> "\"CLK0\"",
    "inclk0_input_frequency" -> "40000",
    "intended_device_family" -> "\"Cyclone IV E\"",
    "lpm_hint" -> "\"CBX_MODULE_PREFIX=pll_125\"",
    "lpm_type" -> "\"altpll\"",
    "operation_mode" -> "\"NORMAL\"",
    "pll_type" -> "\"AUTO\"",
    "port_activeclock" -> "\"PORT_UNUSED\"",
    "port_areset" -> "\"PORT_UNUSED\"",
    "port_clkbad0" -> "\"PORT_UNUSED\"",
    "port_clkbad1" -> "\"PORT_UNUSED\"",
    "port_clkloss" -> "\"PORT_UNUSED\"",
    "port_clkswitch" -> "\"PORT_UNUSED\"",
    "port_configupdate" -> "\"PORT_UNUSED\"",
    "port_fbin" -> "\"PORT_UNUSED\"",
    "port_inclk0" -> "\"PORT_USED\"",
    "port_inclk1" -> "\"PORT_UNUSED\"",
    "port_locked" -> "\"PORT_USED\"",
    "port_pfdena" -> "\"PORT_UNUSED\"",
    "port_phasecounterselect" -> "\"PORT_UNUSED\"",
    "port_phasedone" -> "\"PORT_UNUSED\"",
    "port_phasestep" -> "\"PORT_UNUSED\"",
    "port_phaseupdown" -> "\"PORT_UNUSED\"",
    "port_pllena" -> "\"PORT_UNUSED\"",
    "port_scanaclr" -> "\"PORT_UNUSED\"",
    "port_scanclk" -> "\"PORT_UNUSED\"",
    "port_scanclkena" -> "\"PORT_UNUSED\"",
    "port_scandata" -> "\"PORT_UNUSED\"",
    "port_scandataout" -> "\"PORT_UNUSED\"",
    "port_scandone" -> "\"PORT_UNUSED\"",
    "port_scanread" -> "\"PORT_UNUSED\"",
    "port_scanwrite" -> "\"PORT_UNUSED\"",
    "port_clk0" -> "\"PORT_USED\"",
    "port_clk1" -> "\"PORT_UNUSED\"",
    "port_clk2" -> "\"PORT_UNUSED\"",
    "port_clk3" -> "\"PORT_UNUSED\"",
    "port_clk4" -> "\"PORT_UNUSED\"",
    "port_clk5" -> "\"PORT_UNUSED\"",
    "port_clkena0" -> "\"PORT_UNUSED\"",
    "port_clkena1" -> "\"PORT_UNUSED\"",
    "port_clkena2" -> "\"PORT_UNUSED\"",
    "port_clkena3" -> "\"PORT_UNUSED\"",
    "port_clkena4" -> "\"PORT_UNUSED\"",
    "port_clkena5" -> "\"PORT_UNUSED\"",
    "port_extclk0" -> "\"PORT_UNUSED\"",
    "port_extclk1" -> "\"PORT_UNUSED\"",
    "port_extclk2" -> "\"PORT_UNUSED\"",
    "port_extclk3" -> "\"PORT_UNUSED\"",
    "self_reset_on_loss_lock" -> "\"OFF\"",
    "width_clock" -> "5"
  )

  for ((family, inMhz, changes) <- Seq(
         (FpgaFamily.CycloneIVE, 25, Map.empty[String, String]),
         (FpgaFamily.CycloneIV, 50, Map("clk0_divide_by" -> "2", "inclk0_input_frequency" -> "20000",
                                        "intended_device_family" -> "\"Cyclone IV GX\""))))
    test(s"a 125 MHz spec on $family gives the altpll parameters that ran GMII") {
      val gen  = params(generated(PllSpec(inMhz, Seq(PllOutput(PllRole.Eth, 125))), family, "pll_125"))
      val want = validatedPll125 ++ changes
      assert(gen == want, s"\n  generated: ${(gen.toSet -- want.toSet).toSeq.sorted}\n  " +
        s"validated: ${(want.toSet -- gen.toSet).toSeq.sorted}")
    }

  test("each output states its ratio, phase and port, in the spec's order") {
    val v = params(generated(PllSpec(50, Seq(
      PllOutput(PllRole.System, 80),
      PllOutput(PllRole.Sdram, 80, phasePs = -3000),
      PllOutput(PllRole.Vga, 25))), FpgaFamily.CycloneIV, "pll_x"))
    assert(v("clk0_multiply_by") == "8" && v("clk0_divide_by") == "5" && v("clk0_phase_shift") == "\"0\"")
    assert(v("clk1_multiply_by") == "8" && v("clk1_divide_by") == "5" && v("clk1_phase_shift") == "\"-3000\"")
    assert(v("clk2_multiply_by") == "1" && v("clk2_divide_by") == "2" && v("clk2_phase_shift") == "\"0\"")
    assert(Seq(0, 1, 2).forall(i => v(s"port_clk$i") == "\"PORT_USED\""))
    assert(Seq(3, 4, 5).forall(i => v(s"port_clk$i") == "\"PORT_UNUSED\""))
    assert(!v.contains("clk3_multiply_by"), "an output the spec does not have was configured")
    assert(v("inclk0_input_frequency") == "20000")
    assert(v("intended_device_family") == "\"Cyclone IV GX\"")
    assert(v("lpm_hint") == "\"CBX_MODULE_PREFIX=pll_x\"")
  }

  test("a phase in degrees becomes picoseconds at that output's frequency") {
    // 315 degrees of 40 MHz (25000 ps) is 21875 ps.
    val v = params(generated(PllSpec(25, Seq(PllOutput(PllRole.System, 40),
      PllOutput(PllRole.Sdram, 40, phaseDeg = 315))), FpgaFamily.CycloneIVE, "pll_x"))
    assert(v("clk1_phase_shift") == "\"21875\"")
  }

  test("an input that is not a whole number of picoseconds is rounded, as MegaWizard does") {
    // The MAX1000's 12 MHz: 83333.3 ps.
    val v = params(generated(PllSpec(12, Seq(PllOutput(PllRole.System, 80))), FpgaFamily.MAX10, "pll_x"))
    assert(v("inclk0_input_frequency") == "83333")
    assert(v("clk0_multiply_by") == "20" && v("clk0_divide_by") == "3")
    assert(v("intended_device_family") == "\"MAX 10\"")
  }

  test("the builder instantiates the module it generates, port for port") {
    val spec = PllSpec(50, Seq(PllOutput(PllRole.System, 80), PllOutput(PllRole.Sdram, 80, phasePs = -3000)))
    val vendor = FpgaFamily.CycloneIV.pll.get
    val dir = Files.createTempDirectory("altpll").toString
    val module = read(vendor.generate(spec, "pll_x", dir))
    val declared = """(?m)^\s*(?:input|output)\s+(\w+)""".r.findAllMatchIn(module).map(_.group(1)).toSet

    SpinalConfig(mode = Verilog, targetDirectory = dir).generate(new Component {
      setDefinitionName("PllTop")
      val clk  = in Bool()
      val outs = out Bits(spec.outputs.size + 1 bits)
      val pll  = vendor.instantiate(spec, "pll_x", "thePll", clk)
      outs := Cat(pll.outputs :+ pll.locked)
    })
    val top = read(Paths.get(dir, "PllTop.v").toString)
    val inst = """(?s)pll_x\s+thePll\s*\((.*?)\);""".r.findFirstMatchIn(top)
      .getOrElse(fail(s"no `pll_x thePll` instance in\n$top")).group(1)
    val connected = """\.(\w+)\s*\(""".r.findAllMatchIn(inst).map(_.group(1)).toSet
    assert(connected == declared, s"instance connects $connected, the module declares $declared")
    assert(declared == Set("inclk0", "c0", "c1", "locked"))
  }

  test("the families on ALTPLL have the builder, under their Quartus names") {
    for (f <- Seq(FpgaFamily.CycloneIV, FpgaFamily.CycloneIVE, FpgaFamily.MAX10))
      assert(f.pll.contains(AlteraAltpll(f.quartusFamilyName)), s"$f")
    assert(FpgaFamily.CycloneV.pll.isEmpty, "Cyclone V is altera_pll, not ALTPLL: its builder is a later step")
  }

  test("more outputs than ALTPLL has counters are refused") {
    val six = PllSpec(50, (1 to 6).map(i => PllOutput(PllRole.System, 10 * i)))
    assertThrows[IllegalArgumentException](
      FpgaFamily.CycloneIV.pll.get.generate(six, "pll_x", Files.createTempDirectory("altpll").toString))
  }

  test("an output's phase is in degrees or in picoseconds, not both") {
    assertThrows[IllegalArgumentException](PllOutput(PllRole.Sdram, 80, phaseDeg = -86, phasePs = -3000))
  }

  test("ecppll takes degrees: a picosecond phase on the ECP5 is refused, not converted") {
    val spec = PllSpec(25, Seq(PllOutput(PllRole.System, 40), PllOutput(PllRole.Sdram, 40, phasePs = -3125)))
    assertThrows[IllegalArgumentException](LatticeEcpPll.command(spec, "m", "/tmp/m.v"))
  }
}
