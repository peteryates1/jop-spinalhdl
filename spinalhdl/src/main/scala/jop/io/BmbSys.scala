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
case class BmbSys(clkFreqHz: Long, cpuId: Int = 0, cpuCnt: Int = 1) extends Component {
  val io = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)
    val wd     = out Bits(32 bits)
    val exc    = out Bool()  // Exception pulse to bcfetch
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

  // Lock register: monitorenter microcode reads IO_LOCK and branches on
  // value==0 (lock acquired). For single-CPU without sync unit, VHDL
  // returns sync_out.halted=0 + sync_out.status=0 → 0x00000000.
  // So we return 0 (lock always available, monitorenter succeeds).

  // Read mux (combinational)
  io.rdData := 0
  switch(io.addr) {
    is(0)  { io.rdData := clockCntReg.asBits }          // IO_CNT
    is(1)  { io.rdData := usCntReg.asBits }             // IO_US_CNT
    is(4)  { io.rdData := excTypeReg.resized }           // IO_EXCEPTION
    is(5)  { io.rdData := B(0, 32 bits) }               // IO_LOCK: 0 = lock acquired
    is(6)  { io.rdData := B(cpuId, 32 bits) }           // IO_CPU_ID
    is(7)  { io.rdData := B(0, 32 bits) }               // IO_SIGNAL
    is(11) { io.rdData := B(cpuCnt, 32 bits) }          // IO_CPUCNT
  }

  // Write handling
  when(io.wr) {
    switch(io.addr) {
      is(1)  { timerReg := io.wrData.asUInt }            // IO_TIMER
      is(3)  { wdReg := io.wrData }                      // IO_WD
      is(4)  { excTypeReg := io.wrData(7 downto 0); excPend := True }  // IO_EXCEPTION
      is(8)  { intMaskReg := io.wrData }                 // IO_INTMASK
      // Addresses 0 (INT_ENA), 2 (SWINT), 5 (LOCK), 6 (UNLOCK),
      // 9 (INTCLEARALL), 12 (PERFCNT): silently accepted
    }
  }

  io.wd := wdReg
}
