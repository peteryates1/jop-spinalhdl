package jop.io

import spinal.core._

/**
 * System I/O slave (slave 0) — matches VHDL sc_sys.vhd
 *
 * Provides clock cycle counter, prescaled microsecond counter,
 * watchdog register, CPU ID, signal register, and CMP lock interface.
 *
 * For multicore (cpuCnt > 1):
 *   - IO_LOCK (addr 5) read: returns halted/status from CmpSync
 *   - IO_LOCK (addr 5) write: acquires lock (sets lockReq)
 *   - IO_UNLOCK (addr 6) write: releases lock (clears lockReq)
 *   - io.halted output: pipeline stall when this core is halted by CmpSync
 *
 * @param clkFreqHz System clock frequency in Hz (for microsecond prescaler)
 * @param cpuId     CPU identifier (for multi-core; 0 for single-core)
 * @param cpuCnt    Total number of CPUs (1 for single-core)
 */
case class BmbSys(clkFreqHz: Long, cpuId: Int = 0, cpuCnt: Int = 1) extends Component {
  val io = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)
    val wd     = out Bits(32 bits)
    val exc    = out Bool()  // Exception pulse to bcfetch

    // CMP sync interface (active when cpuCnt > 1)
    val syncIn  = in(SyncOut())   // From CmpSync: halted status
    val syncOut = out(SyncIn())   // To CmpSync: lock request
    val halted  = out Bool()      // Pipeline stall signal
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

  // Timer register (write: set timer value, read: us counter)
  val timerReg = Reg(UInt(32 bits)) init(0)

  // Watchdog register
  val wdReg = Reg(Bits(32 bits)) init(0)

  // Interrupt mask register (write-only, addr 8)
  val intMaskReg = Reg(Bits(32 bits)) init(0)

  // Exception type register (addr 4, matching VHDL sc_sys exc_type)
  // Written by memory controller on null pointer / array bounds violations.
  // Read by JVMHelp.exception() to determine exception type.
  val excTypeReg = Reg(Bits(8 bits)) init(0)
  val excPend = Reg(Bool()) init(False)
  excPend := False  // default: cleared each cycle (set True on write to addr 4)

  // One-cycle exc pulse: fires the cycle after excPend is set
  val excDly = RegNext(excPend) init(False)
  io.exc := excPend && !excDly

  // Lock request register: set on write to addr 5 (acquire), cleared on write to addr 6 (release).
  // In VHDL, req is held by pipeline stall (wr stays high while halted). In our design,
  // I/O writes are one-cycle pulses, so we use a held register instead.
  val lockReqReg = Reg(Bool()) init(False)

  // Boot signal register: set on write to addr 7 (IO_SIGNAL).
  // VHDL uses a one-cycle pulse (sync_in.s_in defaults to '0' each cycle).
  // We use a held register for robustness — once set, stays high so polling cores
  // always catch it regardless of timing alignment.
  val signalReg = Reg(Bool()) init(False)

  // Sync output to CmpSync
  io.syncOut.req  := lockReqReg
  io.syncOut.s_in := signalReg

  // Halted output: combinational from CmpSync
  io.halted := io.syncIn.halted

  // Read mux (combinational)
  io.rdData := 0
  switch(io.addr) {
    is(0)  { io.rdData := clockCntReg.asBits }          // IO_CNT
    is(1)  { io.rdData := usCntReg.asBits }             // IO_US_CNT
    is(4)  { io.rdData := excTypeReg.resized }           // IO_EXCEPTION
    is(5)  {                                              // IO_LOCK
      // VHDL: rd_data(0) <= sync_out.halted; rd_data(1) <= sync_out.status
      io.rdData(0) := io.syncIn.halted
      io.rdData(31 downto 1) := B(0, 31 bits)
    }
    is(6)  { io.rdData := B(cpuId, 32 bits) }           // IO_CPU_ID
    is(7)  { io.rdData := io.syncIn.s_out.asBits.resized } // IO_SIGNAL
    is(11) { io.rdData := B(cpuCnt, 32 bits) }          // IO_CPUCNT
  }

  // Write handling
  when(io.wr) {
    switch(io.addr) {
      is(1)  { timerReg := io.wrData.asUInt }            // IO_TIMER
      is(3)  { wdReg := io.wrData }                      // IO_WD
      is(4)  { excTypeReg := io.wrData(7 downto 0); excPend := True }  // IO_EXCEPTION
      is(5)  { lockReqReg := True }                      // IO_LOCK: acquire
      is(6)  { lockReqReg := False }                     // IO_UNLOCK: release
      is(7)  { signalReg := io.wrData(0) }                 // IO_SIGNAL: boot sync
      is(8)  { intMaskReg := io.wrData }                 // IO_INTMASK
      // Addresses 0 (INT_ENA), 2 (SWINT),
      // 9 (INTCLEARALL), 12 (PERFCNT): silently accepted
    }
  }

  io.wd := wdReg
}
