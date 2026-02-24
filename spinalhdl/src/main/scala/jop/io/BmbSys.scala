package jop.io

import spinal.core._

/**
 * System I/O device — matches VHDL sc_sys.vhd
 *
 * Provides clock cycle counter, prescaled microsecond counter,
 * watchdog register, CPU ID, signal register, CMP lock interface,
 * and interrupt generation (timer + SW interrupts).
 *
 * Interrupt chain (matching VHDL sc_sys.vhd):
 *   timer_equ -> timer_int -> intstate(0) -> priority encoder -> irq_gate -> irq pulse
 *   SW write addr 2 -> swreq -> intstate -> priority encoder -> irq_gate -> irq pulse
 *   int_ena register gates the final irq output; cleared on ackIrq or ackExc.
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
 * @param numIoInt  Number of external I/O interrupt sources (default 2, matching VHDL)
 */
case class BmbSys(clkFreqHz: Long, cpuId: Int = 0, cpuCnt: Int = 1, numIoInt: Int = 2) extends Component {
  val io = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)
    val wd     = out Bits(32 bits)
    val exc    = out Bool()  // Exception pulse to bcfetch

    // Interrupt outputs (to pipeline via JopCore)
    val irq    = out Bool()  // Interrupt request pulse
    val irqEna = out Bool()  // Interrupt enable (to bcfetch)

    // Interrupt acknowledge inputs (from bcfetch via JopCore)
    val ackIrq = in Bool()   // Interrupt acknowledged by bcfetch
    val ackExc = in Bool()   // Exception acknowledged by bcfetch

    // External I/O interrupt inputs
    val ioInt  = in Bits(numIoInt bits)

    // CMP sync interface (active when cpuCnt > 1)
    val syncIn  = in(SyncOut())   // From CmpSync: halted status
    val syncOut = out(SyncIn())   // To CmpSync: lock request
    val halted  = out Bool()      // Pipeline stall signal
  }

  // ==========================================================================
  // Counters
  // ==========================================================================

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

  // ==========================================================================
  // Timer interrupt generation (matching VHDL sc_sys.vhd)
  // ==========================================================================

  // Compare timer value and us counter, generate single-shot pulse
  val timerEqu = (usCntReg === timerReg)
  val timerDly = RegNext(timerEqu) init(False)
  val timerInt = timerEqu && !timerDly

  // ==========================================================================
  // Interrupt state machines (matching VHDL intstate entity)
  // ==========================================================================

  // NUM_INT = numIoInt + 1 (timer interrupt is source 0)
  val NUM_INT = numIoInt + 1

  // Hardware request sources: timer (index 0) + I/O interrupts (indices 1..numIoInt)
  val hwReq = Bits(NUM_INT bits)
  hwReq(0) := timerInt
  if (numIoInt > 0) {
    hwReq(NUM_INT - 1 downto 1) := io.ioInt
  }

  // Software request (one-cycle pulse from addr 2 write)
  val swReq = Reg(Bits(NUM_INT bits)) init(0)

  // Interrupt mask (written at addr 8)
  val mask = Reg(Bits(NUM_INT bits)) init(0)

  // Clear all (one-cycle pulse from addr 9 write)
  val clearAll = Reg(Bool()) init(False)

  // Per-source acknowledge: decode prioint on ackIrq
  val prioInt = Bits(5 bits)  // forward declaration, assigned below
  val ack = Bits(NUM_INT bits)
  for (i <- 0 until NUM_INT) {
    ack(i) := io.ackIrq && (prioInt === B(i, 5 bits))
  }

  // Interrupt request = hardware OR software
  val intReq = hwReq | swReq

  // Instantiate intstate logic inline for each interrupt source
  // (VHDL uses a separate entity; we inline the SR flip-flop)
  val flag = Reg(Bits(NUM_INT bits)) init(0)
  val pending = Bits(NUM_INT bits)
  for (i <- 0 until NUM_INT) {
    when(ack(i) || clearAll) {
      flag(i) := False
    } elsewhen (intReq(i)) {
      flag(i) := True
    }
    pending(i) := flag(i) && mask(i)
  }

  // ==========================================================================
  // Priority encoder: find highest-priority pending interrupt
  // Matching VHDL: scan from NUM_INT-1 downto 0, first (highest index) wins
  // ==========================================================================

  val intPend = Bool()
  intPend := False
  prioInt := B(0, 5 bits)
  for (i <- 0 until NUM_INT) {
    when(pending(i)) {
      intPend := True
      prioInt := B(i, 5 bits)
    }
  }
  // Last assignment wins in SpinalHDL (like VHDL process with loop + exit).
  // The loop above gives priority to the HIGHEST index. VHDL uses downto with
  // exit, which gives priority to the highest index too. Both match.

  // ==========================================================================
  // Interrupt processing (matching VHDL sc_sys.vhd lines 326-349)
  // ==========================================================================

  val intEna  = Reg(Bool()) init(False)
  val irqGate = intPend && intEna
  val irqDly  = RegNext(irqGate) init(False)
  val intNr   = Reg(Bits(5 bits)) init(0)

  // Save processing interrupt number on acknowledge
  when(io.ackIrq) {
    intNr := prioInt
  }

  // IRQ output: single-cycle pulse on rising edge of irqGate
  io.irq := irqGate && !irqDly

  // IRQ enable output to pipeline (bcfetch uses this to gate interrupt acceptance)
  io.irqEna := intEna

  // Disable interrupts on taken interrupt or exception
  when(io.ackIrq || io.ackExc) {
    intEna := False
  }

  // ==========================================================================
  // Watchdog register
  // ==========================================================================

  val wdReg = Reg(Bits(32 bits)) init(0)

  // ==========================================================================
  // Exception handling
  // ==========================================================================

  // Exception type register (addr 4, matching VHDL sc_sys exc_type)
  // Written by memory controller on null pointer / array bounds violations.
  // Read by JVMHelp.exception() to determine exception type.
  val excTypeReg = Reg(Bits(8 bits)) init(0)
  val excPend = Reg(Bool()) init(False)
  excPend := False  // default: cleared each cycle (set True on write to addr 4)

  // Exception pulse: single-cycle on rising edge of excPend (matching VHDL)
  val excDly = RegNext(excPend) init(False)
  io.exc := excPend && !excDly

  // ==========================================================================
  // CMP sync registers
  // ==========================================================================

  // Lock request register: set on write to addr 5 (acquire), cleared on write to addr 6 (release).
  // In VHDL, req is held by pipeline stall (wr stays high while halted). In our design,
  // I/O writes are one-cycle pulses, so we use a held register instead.
  // Used by CmpSync (held level signal for lock ownership).
  val lockReqReg = Reg(Bool()) init(False)

  // IHLU request pulse: one-cycle pulse on each IO_LOCK or IO_UNLOCK write.
  // Used by IHLU (pulse-triggered per-object lock operations).
  val lockReqPulseReg = Reg(Bool()) init(False)

  // Lock data register: the write data from IO_LOCK/IO_UNLOCK write.
  // For IHLU, this is the object handle address (lock identifier).
  // For CmpSync, this value is ignored.
  val lockDataReg = Reg(Bits(32 bits)) init(0)

  // Lock operation register: False = lock (addr 5), True = unlock (addr 6).
  val lockOpReg = Reg(Bool()) init(False)

  // Boot signal register: set on write to addr 7 (IO_SIGNAL).
  // VHDL uses a one-cycle pulse (sync_in.s_in defaults to '0' each cycle).
  // We use a held register for robustness — once set, stays high so polling cores
  // always catch it regardless of timing alignment.
  val signalReg = Reg(Bool()) init(False)

  // GC halt register: when set, CmpSync/IHLU halts all OTHER cores.
  // Used for stop-the-world GC: the collecting core sets this before gc(),
  // clears it after gc(). All other cores' pipelines are frozen.
  val gcHaltReg = Reg(Bool()) init(False)

  // Clear one-cycle pulse by default
  lockReqPulseReg := False

  // Sync output to CmpSync/IHLU
  io.syncOut.req      := lockReqReg
  io.syncOut.reqPulse := lockReqPulseReg
  io.syncOut.s_in     := signalReg
  io.syncOut.gcHalt   := gcHaltReg
  io.syncOut.data     := lockDataReg
  io.syncOut.op       := lockOpReg

  // Halted output: combinational from CmpSync/IHLU
  io.halted := io.syncIn.halted

  // ==========================================================================
  // Read mux (combinational, matching VHDL sc_sys.vhd)
  // ==========================================================================

  io.rdData := 0
  switch(io.addr) {
    is(0)  { io.rdData := clockCntReg.asBits }          // IO_CNT
    is(1)  { io.rdData := usCntReg.asBits }             // IO_US_CNT
    is(2)  {                                              // IO_INT_SRC
      io.rdData(4 downto 0) := intNr
      io.rdData(31 downto 5) := B(0, 27 bits)
    }
    is(4)  { io.rdData := excTypeReg.resized }           // IO_EXCEPTION
    is(5)  {                                              // IO_LOCK
      // VHDL: rd_data(0) <= sync_out.halted; rd_data(1) <= sync_out.status
      // bit 0: halted (CmpSync/IHLU stall status)
      // bit 1: status (IHLU lock table full error; always 0 for CmpSync)
      io.rdData(0) := io.syncIn.halted
      io.rdData(1) := io.syncIn.status
      io.rdData(31 downto 2) := B(0, 30 bits)
    }
    is(6)  { io.rdData := B(cpuId, 32 bits) }           // IO_CPU_ID
    is(7)  { io.rdData := io.syncIn.s_out.asBits.resized } // IO_SIGNAL
    is(11) { io.rdData := B(cpuCnt, 32 bits) }          // IO_CPUCNT
  }

  // ==========================================================================
  // Write handling (matching VHDL sc_sys.vhd)
  // ==========================================================================

  // Default: clear one-cycle pulses
  swReq := 0
  clearAll := False

  when(io.wr) {
    switch(io.addr) {
      is(0)  { intEna := io.wrData(0) }                 // IO_INT_ENA
      is(1)  { timerReg := io.wrData.asUInt }            // IO_TIMER
      is(2)  {                                            // IO_SWINT (yield)
        // Set swReq bit addressed by wrData (matching VHDL: swreq(to_integer(unsigned(wr_data))) <= '1')
        for (i <- 0 until NUM_INT) {
          when(io.wrData(log2Up(NUM_INT) - 1 downto 0).asUInt === i) {
            swReq(i) := True
          }
        }
      }
      is(3)  { wdReg := io.wrData }                      // IO_WD
      is(4)  {
        excTypeReg := io.wrData(7 downto 0); excPend := True   // IO_EXCEPTION
      }
      is(5)  {                                              // IO_LOCK: acquire
        lockReqReg := True
        lockReqPulseReg := True           // One-cycle pulse for IHLU
        lockDataReg := io.wrData          // Lock identifier (objectref) for IHLU
        lockOpReg := False                // op=lock
      }
      is(6)  {                                              // IO_UNLOCK: release
        lockReqReg := False
        lockReqPulseReg := True           // One-cycle pulse for IHLU
        lockDataReg := io.wrData          // Lock identifier (objectref) for IHLU
        lockOpReg := True                 // op=unlock
      }
      is(7)  { signalReg := io.wrData(0) }                 // IO_SIGNAL: boot sync
      is(8)  { mask := io.wrData(NUM_INT - 1 downto 0) } // IO_INTMASK
      is(9)  { clearAll := True }                         // IO_INTCLEARALL
      is(13) { gcHaltReg := io.wrData(0) }                // IO_GC_HALT
    }
  }

  io.wd := wdReg
}
