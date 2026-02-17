package jop.io

import spinal.core._

/**
 * System I/O slave (slave 0) — matches VHDL sc_sys.vhd
 *
 * Provides clock cycle counter, prescaled microsecond counter,
 * watchdog register, CPU ID, and signal register.
 *
 * @param clkFreqHz System clock frequency in Hz (for microsecond prescaler)
 * @param cpuId     CPU identifier (for multi-core; 0 for single-core)
 */
case class BmbSys(clkFreqHz: Long, cpuId: Int = 0) extends Component {
  val io = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)
    val wd     = out Bits(32 bits)
  }

  // Clock cycle counter (free-running, every cycle)
  val clockCntReg = Reg(UInt(32 bits)) init(0)
  clockCntReg := clockCntReg + 1

  // Microsecond counter (prescaled from system clock)
  // Matches VHDL: div_val = clk_freq / 1_000_000 - 1
  val divVal = (clkFreqHz / 1000000 - 1).toInt
  val preScale = Reg(UInt(8 bits)) init(divVal)
  val usCntReg = Reg(UInt(32 bits)) init(0)
  preScale := preScale - 1
  when(preScale === 0) {
    preScale := divVal
    usCntReg := usCntReg + 1
  }

  // Watchdog register
  val wdReg = Reg(Bits(32 bits)) init(0)

  // Read mux (combinational)
  io.rdData := 0
  switch(io.addr) {
    is(0) { io.rdData := clockCntReg.asBits }
    is(1) { io.rdData := usCntReg.asBits }
    is(6) { io.rdData := B(cpuId, 32 bits) }
    is(7) { io.rdData := B(0, 32 bits) }
  }

  // Write handling
  when(io.wr) {
    switch(io.addr) {
      is(3) { wdReg := io.wrData }
    }
  }

  io.wd := wdReg
}
