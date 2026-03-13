package jop.system

import spinal.core._

/**
 * DRAM PLL BlackBox
 *
 * Wraps the dram_pll VHDL entity (Altera altpll megafunction).
 * 50 MHz input -> c0=50MHz, c1=80MHz, c2=80MHz/-3ns phase shift
 */
case class DramPll() extends BlackBox {
  setDefinitionName("dram_pll")

  val io = new Bundle {
    val inclk0 = in Bool()
    val areset = in Bool()
    val c0     = out Bool()
    val c1     = out Bool()
    val c2     = out Bool()
    val c3     = out Bool()   // 25 MHz VGA pixel clock
    val locked = out Bool()
  }

  noIoPrefix()
}

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
