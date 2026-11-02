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
    * built by `family`'s builder; its outputs are handed out by role. `reset`
    * holds it in reset (active high), where the family's PLL takes one. */
  def build(d: PllDefinition, family: FpgaFamily, clock: BoardClock, inputClock: Bool,
            reset: Option[Bool]): PllResult = {
    val vendor = family.pll.getOrElse(throw new IllegalArgumentException(
      s"PLL ${d.module}: $family has no PLL builder yet, so its boards still declare their PLLs"))
    val spec   = d.spec(clock)
    val clocks = vendor.instantiate(spec, d.module, d.instance, inputClock, reset)
    def out(r: PllRole): Option[Bool] =
      Some(spec.outputs.indexWhere(_.role == r)).filter(_ >= 0).map(clocks.outputs)
    PllResult(systemClk = out(PllRole.System), locked = clocks.locked, sdramClk = out(PllRole.Sdram),
      migSysClk = out(PllRole.MigSys), migRefClk = out(PllRole.MigRef), ethClk = out(PllRole.Eth),
      vgaClk = out(PllRole.Vga))
  }

  /** The PLLs `ds` define, each built by `family` from its reference clock on
    * `assembly`, merged by ROLE: a role comes from one output (JopSystem holds
    * that), and `locked` is the system clock's PLL's, so the reset waits on the
    * clock it releases, as with one PLL. Where none makes the system clock --
    * the A-E115FB's DDR2 design defines only its GMII PLL -- the first's. */
  def build(ds: Seq[PllDefinition], family: FpgaFamily, assembly: SystemAssembly, inputClock: Bool,
            reset: Option[Bool] = None): PllResult = {
    require(ds.nonEmpty, "no PLLs to build")
    val built = ds.map(d => build(d, family, assembly.clock(d.reference), inputClock, reset))
    def one(role: String, f: PllResult => Option[Bool]): Option[Bool] = built.flatMap(f(_)) match {
      case Seq()  => None
      case Seq(c) => Some(c)
      case cs     => throw new IllegalArgumentException(s"${cs.size} PLLs give the $role clock")
    }
    PllResult(
      systemClk = one("system", _.systemClk),
      locked    = built.find(_.systemClk.isDefined).getOrElse(built.head).locked,
      sdramClk  = one("SDRAM", _.sdramClk),
      migSysClk = one("MIG system", _.migSysClk),
      migRefClk = one("MIG reference", _.migRefClk),
      ethClk    = one("Ethernet", _.ethClk),
      vgaClk    = one("VGA", _.vgaClk))
  }

  /** The PLL `d` defines, on `board`: built by the board's FPGA family, from
    * the clock of the board's that `d` names. */
  def build(d: PllDefinition, board: Board, inputClock: Bool): PllResult = {
    val family = board.fpga.map(_.family).getOrElse(throw new IllegalArgumentException(
      s"Board '${board.name}' carries no FPGA"))
    val clock = board.clocks.find(_.name == d.reference).getOrElse(throw new IllegalArgumentException(
      s"PLL ${d.module} takes ${d.reference}; board '${board.name}' offers " +
        board.clocks.map(_.name).mkString(", ")))
    build(d, family, clock, inputClock, None)
  }

  /** A PLL making only a system clock at `mhz` from `clock`: what a design on
    * one clock needs -- the exercisers. Its module is named for what it makes,
    * so designs at different clocks never share a file. A design declares it
    * (`BoardDesign.plls`) so the generators write and list it; `forDesign`
    * builds the same definition. */
  def systemOnly(clock: BoardClock, mhz: Int): PllDefinition =
    PllDefinition(clock.name, Seq(PllOutput(PllRole.System, mhz)), module = s"pll_sys_${mhz}mhz", instance = "pll")

  /** The PLL of a design that wants only a system clock, at `systemMhz`, from
    * the board's one clock -- the exercisers. Where the board's FPGA family
    * has a builder, that is `systemOnly`; elsewhere the board's own, which
    * must run at `systemMhz` until its family is converted. */
  def forDesign(board: Board, memType: MemoryType, inputClock: Bool, systemMhz: Int): PllResult =
    if (board.pllType.isDefined) create(board, memType, inputClock)
    else {
      require(memType == MemoryType.BRAM,
        s"Board '${board.name}' declares no PLL; a $memType design must define its own (PllDefinition)")
      val clock = board.clocks match {
        case Seq(only) => only
        case cs => throw new IllegalArgumentException(
          s"Board '${board.name}' offers ${cs.size} clocks (${cs.map(_.name).mkString(", ")}); " +
            "a design on it must define its PLL and name the one it takes")
      }
      build(systemOnly(clock, systemMhz), board, inputClock)
    }
}
