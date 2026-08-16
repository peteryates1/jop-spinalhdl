package jop.config

import java.nio.file.{Files, Paths}

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
 *   clk_wiz CLKOUT1 -> MIG sys_clk -> memory clock -> ui_clk = memory / 4
 * }}}
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
   * NOTE the UART: `clkFreq / (baud x 5)` lands on 2.0367 Mbaud here, not 2 —
   * download with `... 2037000`.
   */
  case object Ddr3_366 extends MigProfile(2727, 97.787,
    "6 cores at 91.68 MHz; UART runs at 2.0372 Mbaud")

  val all: Seq[MigProfile] = Seq(Ddr3_400, Ddr3_366)

  /**
   * Rejected, and recorded so it is not retried: 2778 ps would give a 90 MHz
   * ui_clk and therefore an exact 2 Mbaud, which is tempting. MIG demands a
   * 102.848 MHz sys_clk for it, which this board's clk_wiz cannot sensibly
   * provide from its 50 MHz oscillator alongside the 200 MHz reference. The
   * awkward 2.0367 Mbaud of Ddr3_366 stands.
   */
  val rejected: String = "2778 ps (ui_clk 90 MHz) — MIG demands 102.848 MHz in"

  /**
   * Write the MIG and clk_wiz configuration for `profile` into the board's
   * generated/ directory. `mig.prj` is used as the template so the rest of the
   * MIG configuration — memory part, pinout, all of it — stays byte-identical
   * to the version known to work.
   *
   * Returns a summary line. The caller still has to run `make ddr3-create-ip`;
   * this only emits the inputs to it.
   */
  def emit(boardDir: String, profile: MigProfile): String = {
    val template = Paths.get(boardDir, "vivado/ip/mig.prj")
    val outDir   = Paths.get(boardDir, "vivado/ip/generated")
    if (!Files.exists(template))
      return s"MIG:         template not found at $template — not generated"

    var text = new String(Files.readAllBytes(template), "UTF-8")
    def sub(tag: String, value: String): Unit = {
      val re = s"<$tag>[^<]*<"
      require(re.r.findAllIn(text).size == 1,
        s"expected exactly one <$tag> in mig.prj")
      text = re.r.replaceAllIn(text, s"<$tag>$value<")
    }
    sub("TimePeriod", profile.timePeriodPs.toString)
    sub("InputClkFreq", profile.migInputMhz.toString)

    Files.createDirectories(outDir)
    Files.write(outDir.resolve("mig.prj"), text.getBytes("UTF-8"))

    // The clk_wiz frequency travels as a tcl fragment the wizard script sources,
    // because it lives inline in a create_ip call rather than in a config file.
    val tcl =
      s"""|# GENERATED FROM THE PRESET's MigProfile — DO NOT EDIT.
          |# Profile ${profile.name}: ${profile.note}
          |# MIG demands this exact sys_clk for TimePeriod ${profile.timePeriodPs} ps;
          |# feeding it anything else makes MIG retune itself and the memory clock
          |# lands off target while still building cleanly.
          |set ddr3_clkwiz_mhz ${profile.migInputMhz}
          |""".stripMargin
    Files.write(outDir.resolve("ddr3_clocks.tcl"), tcl.getBytes("UTF-8"))

    f"MIG:         ${profile.name} — ${profile.timePeriodPs} ps, memory " +
    f"${profile.memoryMhz}%.1f MHz, ui_clk ${profile.uiClkMhz}%.2f MHz " +
    f"(${profile.note})"
  }
}
