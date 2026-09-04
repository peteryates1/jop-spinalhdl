package jop.system

import spinal.core._
import jop.config._

/**
 * SpinalConfig factory -- produces the correct SpinalConfig for a JopConfig.
 *
 * Handles manufacturer-specific differences:
 * - Altera: reset active-HIGH (default SpinalHDL behavior)
 * - Xilinx: reset active-LOW (requires explicit config)
 *
 * The default clock domain frequency is set to the board oscillator frequency.
 * For DDR3 boards, the actual system clock comes from MIG ui_clk, but the
 * default CD frequency matches the board oscillator (which drives the PLL input).
 */
/*
 * THE TARGET DIRECTORY IS A REQUIRED ARGUMENT, NOT A DEFAULT.
 *
 * It used to default to `spinalhdl/generated` -- the source tree. Both callers
 * immediately overrode it with `.copy(targetDirectory = ...)`, so the default
 * was never the path anything actually used; it existed only to be replaced.
 * A default that is always overridden is not a convenience, it is a trap
 * waiting for the next caller who forgets to override, and that caller would
 * have written generated RTL back into the source tree with nothing to catch
 * it. Making it explicit costs one argument at two call sites and removes the
 * failure mode entirely.
 */
object JopSpinalConfig {
  def apply(config: JopConfig, targetDir: String): SpinalConfig = {
    val boardFreq = config.assembly.boardClockFreq
    SpinalConfig(
      mode = Verilog,
      targetDirectory = targetDir,
      defaultClockDomainFrequency = FixedFrequency(boardFreq),
      defaultConfigForClockDomains = ClockDomainConfig(
        resetKind = SYNC,
        resetActiveLevel = if (config.fpgaFamily.manufacturer.resetActiveLow) LOW else HIGH
      )
    )
  }
}
