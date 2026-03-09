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
object JopSpinalConfig {
  def apply(config: JopConfig): SpinalConfig = {
    val boardFreq = config.assembly.boardClockFreq
    val isXilinx = config.fpgaFamily.manufacturer == Manufacturer.Xilinx

    SpinalConfig(
      mode = Verilog,
      targetDirectory = "spinalhdl/generated",
      defaultClockDomainFrequency = FixedFrequency(boardFreq),
      defaultConfigForClockDomains = if (isXilinx)
        ClockDomainConfig(resetKind = SYNC, resetActiveLevel = LOW)
      else
        ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
    )
  }
}
