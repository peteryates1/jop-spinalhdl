package hw

/**
 * A validated DDR3 memory-clock setting for the Xilinx MIG, and the whole clock
 * chain that follows from it.
 *
 * WHY AN ENUM AND NOT A CALCULATION. The MIG does not let you pick a memory
 * period and derive everything else: for a given `TimePeriod` it DICTATES the
 * sys_clk it wants, from an internal table, and silently retunes you if you
 * disagree ("Invalid Input Clock Period 100.0. Setting to nearest possible
 * Input Clock Period value 97.787"). Those pairings cannot be computed — each
 * one is learned by running MIG once and reading what it demanded.
 *
 * So this is a set of KNOWN-GOOD points, not a parameter space. Every field of
 * every case below was observed, not derived, and the ui_clk figures have been
 * run on hardware. **To add a profile: set the period, run
 * `make ddr3-create-ip`, read the CRITICAL WARNING for the input frequency it
 * insists on, put that number here, and re-run until it generates silently.**
 * Do not interpolate — a plausible-looking pair that MIG has not blessed will
 * build cleanly and run at the wrong frequency.
 *
 * The whole chain, for reference:
 * {{{
 *   PLL MigSys output -> MIG sys_clk -> memory clock -> ui_clk = memory / 4
 * }}}
 * The PLL is the design's (`JopConfig.migPll`), and a preset whose PLL feeds
 * MIG another input than its profile demands is refused (JopConfig).
 * and `ui_clk` clocks the entire JOP cluster, because the DDR3 path has no
 * system PLL of its own (`Board.scala`, `JopTop.scala`).
 */
sealed abstract class MigProfile(
  /** DDR3 clock period in picoseconds — the MIG's `TimePeriod`. */
  val timePeriodPs: Int,
  /** The sys_clk MIG demands for that period. Observed, never computed. */
  val migInputMhz: Double,
  /** What this buys, for the summary line. */
  val note: String
) {
  /** Memory clock in MHz. */
  def memoryMhz: Double = 1e6 / timePeriodPs
  /** MIG PHY ratio is 4:1 on this board. */
  def uiClkHz: Long = math.round(memoryMhz * 1e6 / 4)
  def uiClkMhz: Double = uiClkHz / 1e6
  def name: String = getClass.getSimpleName.stripSuffix("$")
}

object MigProfile {

  /**
   * Stock: 400 MHz memory, ui_clk 100 MHz. What every checked-in preset uses.
   * Validated to 4 cores (`wukongDdr3Smp 4`, WNS +0.081).
   */
  case object Ddr3_400 extends MigProfile(2500, 100.0,
    "stock; 4 cores at 100 MHz")

  /**
   * 366.6 MHz memory, ui_clk 91.65 MHz. Buys ~8 % of clock for ~8 % of DDR3
   * bandwidth, which is what 6 cores needs. Validated on hardware 2026-08-16:
   * SMPGC OK 4/4 and DoAll 66/66, both generational, WNS +0.018.
   *
   * The UART used to need `... 2037000` here, because an integer divisor of
   * this ui_clk lands on 2.0367 Mbaud rather than 2. Fixed 2026-08-18 by the
   * fractional baud generator (jop.io.UartBaudTick); download at a plain
   * 2000000 like every other board.
   */
  case object Ddr3_366 extends MigProfile(2727, 97.787,
    "6 cores at 91.68 MHz")

  val all: Seq[MigProfile] = Seq(Ddr3_400, Ddr3_366)

  /**
   * Rejected, and recorded so it is not retried: 2778 ps would give a 90 MHz
   * ui_clk and therefore an exact 2 Mbaud, which is tempting. MIG demands a
   * 102.848 MHz sys_clk for it, which this board's clk_wiz cannot sensibly
   * provide from its 50 MHz oscillator alongside the 200 MHz reference. The
   * awkward 2.0367 Mbaud of Ddr3_366 stands.
   *
   * MOOT since 2026-08-18: the UART no longer divides the clock by an integer
   * (jop.io.UartBaudTick), so an awkward ui_clk costs nothing at the serial
   * port and 2 Mbaud is exact here. The sys_clk argument against 2778 ps
   * stands on its own; the baud argument for or against any profile does not.
   */
  val rejected: String = "2778 ps (ui_clk 90 MHz) — MIG demands 102.848 MHz in"
}
