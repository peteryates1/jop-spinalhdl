package jop.config

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

/**
 * A DESIGN DEFINES ITS PLL; THE FPGA FAMILY BUILDS IT -- status item 173,
 * step 6, the first family: ECP5.
 *
 * The i5's PLL was a `PllType` on its board, so the BOARD chose the clocks
 * JOP wanted (40 MHz system, 40 MHz SDRAM at 315 deg, 50 MHz RGMII TX) and
 * the blackbox JOP instantiated -- and `Board.scala` imported JOP's RGMII
 * code to say so. Now each design says which board clock its PLL takes and
 * what it must produce, and the ECP5 family's builder generates and
 * instantiates it.
 */
class PllDefinitionTest extends AnyFunSuite {

  private val i5Designs = Seq(JopConfig.colorlightI5Bram, JopConfig.colorlightI5Sdram,
                              JopConfig.colorlightDbI5Sdram, JopConfig.colorlightDbI5Eth)

  test("the i5's PLL is its designs', not its board's") {
    for (d <- i5Designs) {
      val p = d.system.plls.headOption.getOrElse(fail(s"${d.designName} defines no PLL"))
      assert(p.reference == "CLK25M" && p.module == "pll_jop_i5" && p.instance == "i5Pll")
      // The hardware-validated PLL (PllVendorTest), unchanged by the move.
      assert(p.spec(d.assembly) == PllSpec(25, Seq(
        PllOutput(PllRole.System, 40),
        PllOutput(PllRole.Sdram, 40, phaseDeg = 315),
        PllOutput(PllRole.Eth, jop.io.Rgmii100.TxClockMhz))))
    }
  }

  test("the ECP5 family supplies the PLL builder") {
    assert(FpgaFamily.ECP5.pll.contains(LatticeEcpPll))
    assert(Board.ColorlightI5.fpga.map(_.family).contains(FpgaFamily.ECP5))
  }

  test("a PLL's reference must be a clock the assembly offers") {
    val d = PllDefinition("CLK50M", Seq(PllOutput(PllRole.System, 40)), module = "m", instance = "i")
    val e = intercept[IllegalArgumentException](d.spec(SystemAssembly.colorlightI5))
    assert(e.getMessage.contains("CLK50M") && e.getMessage.contains("CLK25M"), e.getMessage)
  }

  test("a design whose PLL takes another clock than the one it takes in is refused") {
    // One clock input today: a PLL fed from a second board clock needs a second port.
    val base = JopConfig.ae115fbDdr2
    val pll = PllDefinition("CLK100M", Seq(PllOutput(PllRole.System, 75)), module = "m", instance = "i")
    val e = intercept[IllegalArgumentException](
      base.copy(systems = Seq(base.system.copy(plls = Seq(pll)))))
    assert(e.getMessage.contains("CLK100M") && e.getMessage.contains("CLK25M"), e.getMessage)
  }

  test("a PLL's system output must be the clock its system is built for") {
    // clkFreq feeds the UART divider and the timers; a PLL running the system
    // at another frequency detunes them, which reads as a dead board.
    val base = JopConfig.colorlightI5Sdram
    val e = intercept[IllegalArgumentException](base.copy(systems = Seq(base.system.copy(clkFreq = 50 MHz))))
    assert(e.getMessage.contains("40 MHz") && e.getMessage.contains(base.system.name), e.getMessage)
  }

  test("a PllSpec takes whole megahertz: a fractional board clock is refused, not rounded") {
    val odd = SystemAssembly("odd", Seq(Board.ColorlightI5.copy(clocks = Seq(BoardClock("CLK24M576", 24.576 MHz, "P3")))))
    val d = PllDefinition("CLK24M576", Seq(PllOutput(PllRole.System, 40)), module = "m", instance = "i")
    val e = intercept[IllegalArgumentException](d.spec(odd))
    assert(e.getMessage.contains("CLK24M576"), e.getMessage)
  }
}
