package jop.memory

import spinal.core._

/**
 * Altera LPM ROM BlackBox — wraps arom.vhd (lpm_rom megafunction).
 *
 * Uses direct megafunction instantiation with .mif initialization,
 * bypassing the Quartus RAM inference engine.  Required for MAX10
 * where $readmemb / MIF is not supported through inference.
 *
 * LPM_ADDRESS_CONTROL = REGISTERED: address captured on clock rising edge.
 * LPM_OUTDATA = UNREGISTERED: output is combinational from internal register.
 * Caller feeds combinational address (e.g. pcMux); the LPM does registration.
 *
 * @param width     Data width in bits
 * @param addrWidth Address width in bits (depth = 2^addrWidth)
 * @param mifPath   Path to .mif initialization file (relative to Quartus project)
 */
case class AlteraLpmRom(width: Int, addrWidth: Int, mifPath: String) extends BlackBox {
  setDefinitionName("rom")

  addGeneric("width", width)
  addGeneric("addr_width", addrWidth)

  val io = new Bundle {
    val clk     = in Bool()
    val address = in UInt(addrWidth bits)
    val q       = out Bits(width bits)
  }

  noIoPrefix()
  mapClockDomain(clock = io.clk)
}


/**
 * Altera LPM dual-port RAM BlackBox — wraps aram.vhd (lpm_ram_dp megafunction).
 *
 * Uses direct megafunction instantiation with .mif initialization,
 * bypassing the Quartus RAM inference engine.  Required for MAX10
 * where $readmemb / MIF is not supported through inference.
 *
 * All LPM controls are REGISTERED.  aram.vhd has internal wraddr_dly/wren_dly
 * delay registers and inverted write clock (matching jopmin).
 * Caller feeds UNREGISTERED (combinational) signals; the VHDL wrapper and
 * LPM megafunction handle all registration and write-delay internally.
 *
 * @param width     Data width in bits
 * @param addrWidth Address width in bits (depth = 2^addrWidth)
 * @param mifPath   Path to .mif initialization file (relative to Quartus project)
 */
case class AlteraLpmRam(width: Int, addrWidth: Int, mifPath: String) extends BlackBox {
  setDefinitionName("ram")

  addGeneric("width", width)
  addGeneric("addr_width", addrWidth)

  val io = new Bundle {
    val reset     = in Bool()
    val clock     = in Bool()
    val data      = in Bits(width bits)
    val wraddress = in UInt(addrWidth bits)
    val rdaddress = in UInt(addrWidth bits)
    val wren      = in Bool()
    val q         = out Bits(width bits)
  }

  noIoPrefix()
  mapClockDomain(clock = io.clock, reset = io.reset, resetActiveLevel = HIGH)
}
