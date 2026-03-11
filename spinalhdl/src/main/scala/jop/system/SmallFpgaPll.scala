package jop.system

import spinal.core._

/**
 * MAX1000 PLL BlackBox
 *
 * Wraps an altpll megafunction for Arrow MAX1000 (MAX10 10M08).
 * 12 MHz input -> c0=80MHz system, c1=80MHz/-3ns SDRAM clock
 *
 * For fit-check: provide a stub VHDL/Verilog file in the Quartus project.
 */
case class Max1000Pll() extends BlackBox {
  setDefinitionName("max1000_pll")

  val io = new Bundle {
    val inclk0 = in Bool()
    val areset = in Bool()
    val c0     = out Bool()   // 80 MHz system clock
    val c1     = out Bool()   // 80 MHz SDRAM clock (phase-shifted)
    val locked = out Bool()
  }

  noIoPrefix()
}

/**
 * EP4CE6 PLL BlackBox
 *
 * Wraps an altpll megafunction for generic EP4CE6 boards.
 * 50 MHz input -> c0=80MHz system, c1=80MHz/-3ns SDRAM clock
 *
 * For fit-check: provide a stub VHDL/Verilog file in the Quartus project.
 */
case class Ep4ce6Pll() extends BlackBox {
  setDefinitionName("ep4ce6_pll")

  val io = new Bundle {
    val inclk0 = in Bool()
    val areset = in Bool()
    val c0     = out Bool()   // 80 MHz system clock
    val c1     = out Bool()   // 80 MHz SDRAM clock (phase-shifted)
    val locked = out Bool()
  }

  noIoPrefix()
}
