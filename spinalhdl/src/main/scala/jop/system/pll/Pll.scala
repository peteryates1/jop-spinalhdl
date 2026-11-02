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
 * Wukong BRAM ClkWiz BlackBox — Vivado `bram_clk`: 50 MHz -> 100 MHz.
 */
class WukongClkWizBlackBox(instanceName: String = "bram_clk") extends BlackBox {
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
 * @param ethClk        Ethernet TX: 125 MHz for GMII, 50 MHz for RGMII at 100 Mb/s
 *                      (jop.io.Rgmii100.TxClockMhz)
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
 * PLL factory. A PLL the DESIGN defines is built by its FPGA family's builder
 * (`build`, status item 173); a board whose family has no builder yet still
 * declares its own (`create`, delegating to PllType.create()).
 */
object Pll {

  def create(board: Board, memType: MemoryType, inputClock: Bool, systemIndex: Int = 0): PllResult = {
    val pllType = board.pllType.getOrElse(
      throw new RuntimeException(s"Board '${board.name}' has no pllType defined"))
    pllType.create(memType, inputClock, systemIndex)
  }

  /** The PLL `d` defines, fed from `clock` (its reference) on `inputClock`,
    * built by `family`'s builder; its outputs are handed out by role. */
  def build(d: PllDefinition, family: FpgaFamily, clock: BoardClock, inputClock: Bool): PllResult = {
    val vendor = family.pll.getOrElse(throw new IllegalArgumentException(
      s"PLL ${d.module}: $family has no PLL builder yet, so its boards still declare their PLLs"))
    val spec   = d.spec(clock)
    val clocks = vendor.instantiate(spec, d.module, d.instance, inputClock)
    def out(r: PllRole): Option[Bool] =
      Some(spec.outputs.indexWhere(_.role == r)).filter(_ >= 0).map(clocks.outputs)
    PllResult(systemClk = out(PllRole.System), locked = clocks.locked, sdramClk = out(PllRole.Sdram),
      migSysClk = out(PllRole.MigSys), migRefClk = out(PllRole.MigRef), ethClk = out(PllRole.Eth),
      vgaClk = out(PllRole.Vga))
  }

  /** The PLL of a design that wants only a system clock, at `systemMhz`, from
    * the board's one clock -- the exercisers. Where the board's FPGA family
    * has a builder, that is the design's PLL; elsewhere the board's own, which
    * must run at `systemMhz` until its family is converted. */
  def forDesign(board: Board, memType: MemoryType, inputClock: Bool, systemMhz: Int): PllResult =
    if (board.pllType.isDefined) create(board, memType, inputClock)
    else {
      require(memType == MemoryType.BRAM,
        s"Board '${board.name}' declares no PLL; a $memType design must define its own (PllDefinition)")
      val family = board.fpga.map(_.family).getOrElse(throw new IllegalArgumentException(
        s"Board '${board.name}' carries no FPGA"))
      val clock = board.clocks match {
        case Seq(only) => only
        case cs => throw new IllegalArgumentException(
          s"Board '${board.name}' offers ${cs.size} clocks (${cs.map(_.name).mkString(", ")}); " +
            "a design on it must define its PLL and name the one it takes")
      }
      build(PllDefinition(clock.name, Seq(PllOutput(PllRole.System, systemMhz)),
        module = s"pll_sys_${systemMhz}mhz", instance = "pll"), family, clock, inputClock)
    }
}
