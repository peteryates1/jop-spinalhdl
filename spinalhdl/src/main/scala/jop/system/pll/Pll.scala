package jop.system.pll

import spinal.core._
import jop.config._
import jop.system.{DramPll, Max1000Pll, Ep4ce6Pll, SdramExerciserClkWiz}
import jop.ddr3.ClkWizBlackBox

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
class WukongClkWizBlackBox extends BlackBox {
  val io = new Bundle {
    val resetn  = in Bool()
    val clk_in  = in Bool()
    val clk_100 = out Bool()
    val locked  = out Bool()
  }

  setBlackBoxName("clk_wiz_0")
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
 * PLL factory -- instantiates the correct PLL BlackBox based on board and memory type.
 *
 * Existing BlackBox classes stay in their current files unchanged.
 * This factory wraps them with a uniform PllResult interface.
 */
object Pll {

  /**
   * Create a PLL for the given configuration.
   *
   * @param boardName  Board identifier (from Board.name)
   * @param memType    Memory type being used
   * @param inputClock Input clock signal (explicit port for Altera, default CD wire for Xilinx)
   * @return PllResult with all available clock outputs
   */
  def create(boardName: String, memType: MemoryType, inputClock: Bool): PllResult = {
    (boardName, memType) match {

      // ================================================================
      // QMTECH EP4CGX150 (Cyclone IV) -- DramPll
      // SDR: c1=80MHz system, c2=80MHz/-3ns SDRAM
      // BRAM: c1=100MHz system (same PLL, different interpretation)
      // ================================================================
      case ("qmtech-ep4cgx150", MemoryType.SDRAM_SDR) =>
        val pll = DramPll()
        pll.io.inclk0 := inputClock
        pll.io.areset := False
        PllResult(
          systemClk = Some(pll.io.c1),
          locked = pll.io.locked,
          sdramClk = Some(pll.io.c2),
          ethClk = Some(pll.io.c3),   // Not actually eth -- c3 is 25 MHz VGA pixel clock
          vgaClk = Some(pll.io.c3)
        )

      case ("qmtech-ep4cgx150", MemoryType.BRAM) =>
        val pll = DramPll()
        pll.io.inclk0 := inputClock
        pll.io.areset := False
        PllResult(
          systemClk = Some(pll.io.c1),
          locked = pll.io.locked
        )

      // ================================================================
      // CYC5000 (Cyclone V) -- Cyc5000Pll
      // outclk_0=80MHz system, outclk_1=80MHz/-2.5ns SDRAM
      // ================================================================
      case ("cyc5000", _) =>
        val pll = Cyc5000Pll()
        pll.io.refclk := inputClock
        pll.io.rst := False
        PllResult(
          systemClk = Some(pll.io.outclk_0),
          locked = pll.io.locked,
          sdramClk = Some(pll.io.outclk_1)
        )

      // ================================================================
      // Wukong XC7A100T SDR -- SdramExerciserClkWiz
      // clk_100=100MHz system, clk_100_shift=100MHz/-108 deg SDRAM
      // ================================================================
      case ("qmtech-wukong-xc7a100t", MemoryType.SDRAM_SDR) =>
        val clkWiz = new SdramExerciserClkWiz
        clkWiz.io.clk_in := inputClock
        clkWiz.io.resetn := True
        PllResult(
          systemClk = Some(clkWiz.io.clk_100),
          locked = clkWiz.io.locked,
          sdramClk = Some(clkWiz.io.clk_100_shift),
          ethClk = Some(clkWiz.io.clk_125)
        )

      // ================================================================
      // Wukong XC7A100T DDR3 -- ClkWizBlackBox
      // clk_100=MIG sys, clk_200=MIG ref. System clock comes from MIG ui_clk.
      // ================================================================
      case ("qmtech-wukong-xc7a100t", MemoryType.SDRAM_DDR3) =>
        val clkWiz = new ClkWizBlackBox
        clkWiz.io.clk_in := inputClock
        // ClkWiz reset is active-HIGH despite port name "resetn".
        // For Xilinx boards, default CD reset is active-LOW, so invert.
        clkWiz.io.resetn := !ClockDomain.current.readResetWire
        PllResult(
          locked = clkWiz.io.locked,
          migSysClk = Some(clkWiz.io.clk_100),
          migRefClk = Some(clkWiz.io.clk_200),
          ethClk = Some(clkWiz.io.clk_125)
        )

      // ================================================================
      // Wukong XC7A100T BRAM -- WukongClkWizBlackBox
      // clk_100=100MHz system
      // ================================================================
      case ("qmtech-wukong-xc7a100t", MemoryType.BRAM) =>
        val clkWiz = new WukongClkWizBlackBox
        clkWiz.io.clk_in := inputClock
        clkWiz.io.resetn := False  // No reset needed
        PllResult(
          systemClk = Some(clkWiz.io.clk_100),
          locked = clkWiz.io.locked
        )

      // ================================================================
      // Alchitry Au V2 DDR3 -- ClkWizBlackBox
      // clk_100=MIG sys, clk_200=MIG ref. System clock comes from MIG ui_clk.
      // ================================================================
      case ("alchitry-au-v2", _) =>
        val clkWiz = new ClkWizBlackBox
        clkWiz.io.clk_in := inputClock
        clkWiz.io.resetn := !ClockDomain.current.readResetWire
        PllResult(
          locked = clkWiz.io.locked,
          migSysClk = Some(clkWiz.io.clk_100),
          migRefClk = Some(clkWiz.io.clk_200),
          ethClk = Some(clkWiz.io.clk_125)
        )

      // ================================================================
      // Arrow MAX1000 (MAX10) -- Max1000Pll
      // c0=80MHz system, c1=80MHz/-3ns SDRAM
      // ================================================================
      case ("max1000", MemoryType.SDRAM_SDR) =>
        val pll = Max1000Pll()
        pll.io.inclk0 := inputClock
        pll.io.areset := False
        PllResult(
          systemClk = Some(pll.io.c0),
          locked = pll.io.locked,
          sdramClk = Some(pll.io.c1)
        )

      // ================================================================
      // Generic EP4CE6 (Cyclone IV E) -- Ep4ce6Pll
      // c0=80MHz system, c1=80MHz/-3ns SDRAM
      // ================================================================
      case ("generic-ep4ce6", MemoryType.SDRAM_SDR) =>
        val pll = Ep4ce6Pll()
        pll.io.inclk0 := inputClock
        pll.io.areset := False
        PllResult(
          systemClk = Some(pll.io.c0),
          locked = pll.io.locked,
          sdramClk = Some(pll.io.c1)
        )

      case _ =>
        throw new RuntimeException(s"No PLL configuration for board '$boardName' with memory type '$memType'")
    }
  }
}
