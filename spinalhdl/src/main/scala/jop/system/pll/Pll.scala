package jop.system.pll

import spinal.core._
import jop.config._

/**
 * CYC5000 PLL BlackBox — Cyclone V altera_pll megafunction.
 * 12 MHz input -> c0=80MHz (JOP system), c1=80MHz/-2.5ns (SDRAM clock pin)
 */
case class Cyc5000Pll() extends BlackBox {
  setDefinitionName("cyc5000_pll")

  val io = new Bundle {
    val refclk   = in Bool()
    val rst      = in Bool()
    val outclk_0 = out Bool()
    val outclk_1 = out Bool()
    val locked   = out Bool()
  }

  noIoPrefix()
}

/**
 * Wukong BRAM ClkWiz BlackBox — Vivado clk_wiz_0: 50 MHz -> 100 MHz.
 */
class WukongClkWizBlackBox(instanceName: String = "clk_wiz_0") extends BlackBox {
  val io = new Bundle {
    val resetn  = in Bool()
    val clk_in  = in Bool()
    val clk_100 = out Bool()
    val locked  = out Bool()
  }

  setBlackBoxName(instanceName)
  noIoPrefix()
}

/**
 * Colorlight i5 PLL BlackBox — Lattice ECP5 EHXPLLL.
 *
 *   clkin   25 MHz board oscillator (P3)
 *   clkout0 40 MHz JOP system clock
 *   clkout1 40 MHz SDRAM clock, shifted 315 deg (-3.1 ns), unused by the BRAM
 *           preset
 *
 * The Verilog wrapper (fpga/colorlight-i5/pll_jop_i5.v) is `ecppll` output
 * verbatim, so the divider set is one the tool guarantees will lock:
 * Fpfd = 25/5 = 5 MHz, Fvco = 5 * 8 * 15 = 600 MHz (legal band 400-800),
 * CLKOP = 600/15 = 40 MHz. Hand-picked dividers are the usual way to get an
 * illegal Fvco and a PLL that never asserts LOCK, which parks the whole core
 * in reset with no other symptom.
 */
case class I5Pll() extends BlackBox {
  setDefinitionName("pll_jop_i5")

  val io = new Bundle {
    val clkin   = in Bool()
    val clkout0 = out Bool()
    val clkout1 = out Bool()
    val locked  = out Bool()
  }

  noIoPrefix()
}

/**
 * Unified PLL result -- all clock outputs from the PLL subsystem.
 *
 * Different boards produce different subsets of clocks:
 * - SDR SDRAM boards: systemClk + sdramClk (phase-shifted)
 * - DDR3 boards: migSysClk + migRefClk (system clock comes from MIG ui_clk, not PLL)
 * - BRAM boards: systemClk only
 *
 * @param systemClk     Main system clock output (non-DDR3 only)
 * @param locked        PLL locked indicator
 * @param sdramClk      Phase-shifted SDRAM clock (SDR only)
 * @param migSysClk     100 MHz for MIG sys_clk_i (DDR3 only)
 * @param migRefClk     200 MHz for MIG clk_ref_i (DDR3 only)
 * @param ethClk        125 MHz for Ethernet GMII TX (EP4CGX150 only)
 * @param vgaClk        25 MHz for VGA pixel clock (EP4CGX150 only)
 */
case class PllResult(
  systemClk: Option[Bool] = None,
  locked: Bool,
  sdramClk: Option[Bool] = None,
  migSysClk: Option[Bool] = None,
  migRefClk: Option[Bool] = None,
  ethClk: Option[Bool] = None,
  vgaClk: Option[Bool] = None
)

/**
 * PLL factory — delegates to PllType.create().
 */
object Pll {

  def create(board: Board, memType: MemoryType, inputClock: Bool, systemIndex: Int = 0): PllResult = {
    val pllType = board.pllType.getOrElse(
      throw new RuntimeException(s"Board '${board.name}' has no pllType defined"))
    pllType.create(memType, inputClock, systemIndex)
  }
}
