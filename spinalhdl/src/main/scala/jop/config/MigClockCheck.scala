package jop.config

import java.nio.file.{Files, Paths}

/**
 * Cross-checks the Wukong DDR3 clock chain against the preset at generation time.
 *
 * WHY A CHECK AND NOT A GENERATOR. The EP4CGX150 PLL is generated from the
 * preset (see `DramPllGen`) because the multiply/divide pair is a pure function
 * of the target frequency — reduce the ratio against the input and you are done.
 * The MIG is not like that. For a given memory `TimePeriod` it dictates its own
 * `InputClkFreq` from an internal table we cannot compute:
 *
 *     TimePeriod 2500 -> wants 100.0 MHz in     (stock)
 *     TimePeriod 2727 -> wants  97.787 MHz in
 *     TimePeriod 2778 -> wants 102.848 MHz in
 *
 * discovered only by running MIG and reading the CRITICAL WARNING it emits when
 * it overrides you. A generator would therefore be a lookup table with two
 * validated entries and guesses everywhere else — worse than nothing, because it
 * would look authoritative. So the frequency stays a deliberate human edit, and
 * this catches the three ways of getting it wrong:
 *
 *   1. clk_wiz does not feed MIG what MIG was configured to expect, so MIG
 *      silently retunes and the memory clock lands a few percent off.
 *   2. The preset's `clkFreq` does not match the resulting ui_clk, so the
 *      microsecond prescaler and the UART divider are computed for the wrong
 *      clock and the board goes quiet — indistinguishable from a dead build.
 *   3. Someone regenerates the IP and forgets to re-generate the Verilog, or the
 *      reverse.
 *
 * All three are silent at build time and only show up as "FPGA not responding".
 */
object MigClockCheck {

  /** MIG PHY ratio: ui_clk = memory clock / 4. */
  val phyRatio = 4

  private def readNum(text: String, re: String): Option[Double] =
    re.r.findFirstMatchIn(text).map(_.group(1).toDouble)

  /**
   * Verify the DDR3 clock chain for `boardDir` against a preset asking for
   * `presetClkHz`. Returns a summary line; throws with a specific, actionable
   * message when the chain is inconsistent.
   */
  def check(boardDir: String, presetClkHz: Long): String = {
    val migPath = Paths.get(boardDir, "vivado/ip/mig.prj")
    val wizPath = Paths.get(boardDir, "vivado/tcl/create_ddr3_clk_wiz.tcl")
    if (!Files.exists(migPath) || !Files.exists(wizPath))
      return "MIG check:   skipped (mig.prj or clk_wiz script not found)"

    val migText = new String(Files.readAllBytes(migPath), "UTF-8")
    val wizText = new String(Files.readAllBytes(wizPath), "UTF-8")

    val periodPs = readNum(migText, """<TimePeriod>([0-9.]+)<""")
      .getOrElse(return "MIG check:   skipped (no TimePeriod in mig.prj)")
    val migInMhz = readNum(migText, """<InputClkFreq>([0-9.]+)<""")
      .getOrElse(return "MIG check:   skipped (no InputClkFreq in mig.prj)")
    val wizMhz = readNum(wizText, """CLKOUT1_REQUESTED_OUT_FREQ \{([0-9.]+)\}""")
      .getOrElse(return "MIG check:   skipped (no CLKOUT1 frequency in the clk_wiz script)")

    // 1. The clk_wiz must feed MIG exactly what MIG expects, or MIG retunes.
    if (math.abs(wizMhz - migInMhz) > 0.01)
      throw new IllegalStateException(
        f"""|DDR3 CLOCK CHAIN INCONSISTENT.
            |  clk_wiz CLKOUT1      = $wizMhz%.3f MHz   (create_ddr3_clk_wiz.tcl)
            |  MIG InputClkFreq     = $migInMhz%.3f MHz   (mig.prj)
            |MIG dictates its own sys_clk for a given TimePeriod and will silently
            |retune a mismatch, putting the memory clock -- and therefore ui_clk and
            |the whole cluster -- off target while still building cleanly. Set both
            |to the same value and re-run `make ddr3-create-ip`.""".stripMargin)

    val memMhz = 1e6 / periodPs           // ps -> MHz
    val uiMhz  = memMhz / phyRatio
    val presetMhz = presetClkHz / 1e6

    // 2. The preset must know the real ui_clk, or the UART divider is wrong.
    if (math.abs(uiMhz - presetMhz) > 0.05)
      throw new IllegalStateException(
        f"""|PRESET CLOCK DOES NOT MATCH ui_clk.
            |  mig.prj TimePeriod   = $periodPs%.0f ps -> memory $memMhz%.2f MHz
            |  ui_clk (memory / $phyRatio)  = $uiMhz%.3f MHz
            |  preset clkFreq       = $presetMhz%.3f MHz
            |The DDR3 path has no system PLL: JopTop clocks the cluster from
            |ddr3Mig.io.ui_clk, so the preset must declare that frequency. It only
            |feeds the microsecond prescaler and the UART divider, so a mismatch does
            |NOT fail the build -- the board simply goes quiet, which looks exactly
            |like a dead bitstream. Generate with the right value, e.g.
            |  sbt "runMain jop.system.JopTopVerilog wukongDdr3Smp <cores> ${(uiMhz * 1e6).round}"
            |(the argument is Hz, because ui_clk is rarely an integer MHz.)""".stripMargin)

    f"MIG check:   ok — $periodPs%.0f ps -> memory $memMhz%.1f MHz -> ui_clk $uiMhz%.2f MHz"
  }
}
