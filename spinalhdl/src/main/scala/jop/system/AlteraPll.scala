package jop.system

import spinal.core._

/**
 * Ethernet 125 MHz PLL BlackBox
 *
 * Wraps the pll_125 Verilog module (Altera altpll megafunction).
 * 50 MHz input -> c0=125 MHz (TX logic + PHY GTXC)
 */
case class EthPll() extends BlackBox {
  setDefinitionName("pll_125")

  val io = new Bundle {
    val inclk0 = in Bool()
    val c0     = out Bool()    // 125 MHz, 0° phase (TX)
    val locked = out Bool()
  }

  noIoPrefix()
}
