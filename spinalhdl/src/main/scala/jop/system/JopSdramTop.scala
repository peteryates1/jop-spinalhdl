package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.io.InOutWrapper
import spinal.lib.memory.sdram.sdr._
import jop.io.{BmbSys, BmbUart}
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import jop.pipeline.JumpTableInitData

/**
 * DRAM PLL BlackBox
 *
 * Wraps the dram_pll VHDL entity (Altera altpll megafunction).
 * 50 MHz input -> c0=50MHz, c1=100MHz, c2=100MHz/-3ns phase shift
 */
case class DramPll() extends BlackBox {
  setDefinitionName("dram_pll")

  val io = new Bundle {
    val inclk0 = in Bool()
    val areset = in Bool()
    val c0     = out Bool()
    val c1     = out Bool()
    val c2     = out Bool()
    val locked = out Bool()
  }

  noIoPrefix()
}

/**
 * JOP SDRAM FPGA Top-Level for QMTECH EP4CGX150
 *
 * Runs JOP processor with SDRAM-backed memory at 100 MHz.
 * PLL: 50 MHz input -> 100 MHz system clock, 100 MHz/-3ns SDRAM clock.
 * Full UART TX+RX for serial download protocol.
 *
 * @param romInit Microcode ROM initialization data (serial-boot)
 * @param ramInit Stack RAM initialization data (serial-boot)
 */
case class JopSdramTop(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt]
) extends Component {

  val io = new Bundle {
    val clk_in    = in Bool()
    val ser_txd   = out Bool()
    val ser_rxd   = in Bool()
    val led       = out Bits(2 bits)
    val sdram_clk = out Bool()
    val sdram     = master(SdramInterface(W9825G6JH6.layout))

    // SignalTap debug ports (directly readable by Quartus SignalTap)
    val stp_memState     = out UInt(5 bits)
    val stp_memBusy      = out Bool()
    val stp_bmbCmdValid  = out Bool()
    val stp_bmbCmdReady  = out Bool()
    val stp_bmbCmdOpcode = out Bits(1 bits)
    val stp_bmbRspValid  = out Bool()
    val stp_bmbRspLast   = out Bool()
    val stp_sdramSendingHigh   = out Bool()
    val stp_sdramBurstActive   = out Bool()
    val stp_sdramCtrlCmdValid  = out Bool()
    val stp_sdramCtrlCmdReady  = out Bool()
    val stp_sdramCtrlCmdWrite  = out Bool()
    val stp_sdramCtrlRspValid  = out Bool()
    val stp_sdramCtrlRspIsHigh = out Bool()
    val stp_sdramLowHalfData   = out Bits(16 bits)
    val stp_hangDetected = out Bool()
    val stp_pc           = out UInt(11 bits)
    val stp_jpc          = out UInt(12 bits)
  }

  noIoPrefix()

  // ========================================================================
  // PLL: 50 MHz -> 100 MHz system, 100 MHz/-3ns SDRAM clock
  // ========================================================================

  val pll = DramPll()
  pll.io.inclk0 := io.clk_in
  pll.io.areset := False

  // SDRAM clock output (PLL c2: 100 MHz with -3ns phase shift)
  io.sdram_clk := pll.io.c2

  // Note: LED driven from mainArea watchdog register (line 242)

  // ========================================================================
  // Reset Generator (on PLL c1 = 100 MHz)
  // ========================================================================

  // Raw clock domain from PLL c1 (100 MHz, no reset yet)
  val rawClockDomain = ClockDomain(
    clock = pll.io.c1,
    config = ClockDomainConfig(resetKind = BOOT)
  )

  // Reset active (high) until counter saturates AND PLL locked
  val resetGen = new ClockingArea(rawClockDomain) {
    val res_cnt = Reg(UInt(3 bits)) init(0)
    when(pll.io.locked && res_cnt =/= 7) {
      res_cnt := res_cnt + 1
    }
    val int_res = !pll.io.locked || !res_cnt(0) || !res_cnt(1) || !res_cnt(2)
  }

  // Main clock domain: 100 MHz with generated reset
  val mainClockDomain = ClockDomain(
    clock = pll.io.c1,
    reset = resetGen.int_res,
    frequency = FixedFrequency(100 MHz),
    config = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  // ========================================================================
  // Main Design Area (100 MHz)
  // ========================================================================

  val mainArea = new ClockingArea(mainClockDomain) {

    val config = JopCoreConfig(
      memConfig = JopMemoryConfig(burstLen = 4),
      jumpTable = JumpTableInitData.serial
    )

    // JBC init: empty (zeros) — BC_FILL loads bytecodes dynamically from SDRAM
    val jbcInit = Seq.fill(2048)(BigInt(0))

    // JOP System with SDRAM backend
    val jopCoreWithSdram = JopCoreWithSdram(
      config = config,
      useAlteraCtrl = true,
      clockFreqHz = 100000000L,
      romInit = Some(romInit),
      ramInit = Some(ramInit),
      jbcInit = Some(jbcInit)
    )

    // Connect SDRAM interface
    io.sdram <> jopCoreWithSdram.io.sdram

    // Interrupts (disabled)
    jopCoreWithSdram.io.irq := False
    jopCoreWithSdram.io.irqEna := False

    // ======================================================================
    // I/O Slaves
    // ======================================================================

    val ioSubAddr = jopCoreWithSdram.io.ioAddr(3 downto 0)
    val ioSlaveId = jopCoreWithSdram.io.ioAddr(5 downto 4)

    // System I/O (slave 0)
    val bmbSys = BmbSys(clkFreqHz = 100000000L)
    bmbSys.io.addr := ioSubAddr
    bmbSys.io.rd := jopCoreWithSdram.io.ioRd && ioSlaveId === 0
    bmbSys.io.wr := jopCoreWithSdram.io.ioWr && ioSlaveId === 0
    bmbSys.io.wrData := jopCoreWithSdram.io.ioWrData
    bmbSys.io.syncIn.halted := False  // Single-core: no CmpSync
    bmbSys.io.syncIn.s_out := False

    // UART (slave 1)
    val bmbUart = BmbUart()
    bmbUart.io.addr := ioSubAddr
    bmbUart.io.rd := jopCoreWithSdram.io.ioRd && ioSlaveId === 1
    bmbUart.io.wr := jopCoreWithSdram.io.ioWr && ioSlaveId === 1
    bmbUart.io.wrData := jopCoreWithSdram.io.ioWrData
    io.ser_txd := bmbUart.io.txd
    bmbUart.io.rxd := io.ser_rxd

    // I/O read mux
    val ioRdData = Bits(32 bits)
    ioRdData := 0
    switch(ioSlaveId) {
      is(0) { ioRdData := bmbSys.io.rdData }
      is(1) { ioRdData := bmbUart.io.rdData }
    }
    jopCoreWithSdram.io.ioRdData := ioRdData

    // Exception signal from BmbSys
    jopCoreWithSdram.io.exc := bmbSys.io.exc

    // ======================================================================
    // LED Driver
    // ======================================================================

    // Heartbeat: ~1 Hz toggle (50M cycles at 100 MHz)
    val heartbeat = Reg(Bool()) init(False)
    val heartbeatCnt = Reg(UInt(26 bits)) init(0)
    heartbeatCnt := heartbeatCnt + 1
    when(heartbeatCnt === 49999999) {
      heartbeatCnt := 0
      heartbeat := ~heartbeat
    }

    // QMTECH LEDs are active low
    // LED[1] = heartbeat (proves clock is running)
    // LED[0] = watchdog bit 0 (proves Java code is running)
    io.led(1) := ~heartbeat
    io.led(0) := ~bmbSys.io.wd(0)

    // ======================================================================
    // Hang Detector
    // ======================================================================

    // Count cycles while memBusy is high; reset when it goes low.
    // Asserts hangDetected when counter reaches 2^20 (~10ms at 100 MHz).
    val hangCounter = Reg(UInt(21 bits)) init(0)
    val hangDetected = RegInit(False)

    when(jopCoreWithSdram.io.memBusy) {
      when(!hangCounter(20)) {
        hangCounter := hangCounter + 1
      } otherwise {
        hangDetected := True
      }
    } otherwise {
      hangCounter := 0
    }

    // ======================================================================
    // SignalTap Debug Ports
    // ======================================================================

    io.stp_memState     := jopCoreWithSdram.io.debugMemState
    io.stp_memBusy      := jopCoreWithSdram.io.memBusy
    io.stp_bmbCmdValid  := jopCoreWithSdram.io.bmbCmdValid
    io.stp_bmbCmdReady  := jopCoreWithSdram.io.bmbCmdReady
    io.stp_bmbCmdOpcode := jopCoreWithSdram.io.bmbCmdOpcode
    io.stp_bmbRspValid  := jopCoreWithSdram.io.bmbRspValid
    io.stp_bmbRspLast   := jopCoreWithSdram.io.bmbRspLast

    // SDRAM controller debug
    io.stp_sdramSendingHigh   := jopCoreWithSdram.io.debugSdramCtrl.sendingHigh
    io.stp_sdramBurstActive   := jopCoreWithSdram.io.debugSdramCtrl.burstActive
    io.stp_sdramCtrlCmdValid  := jopCoreWithSdram.io.debugSdramCtrl.ctrlCmdValid
    io.stp_sdramCtrlCmdReady  := jopCoreWithSdram.io.debugSdramCtrl.ctrlCmdReady
    io.stp_sdramCtrlCmdWrite  := jopCoreWithSdram.io.debugSdramCtrl.ctrlCmdWrite
    io.stp_sdramCtrlRspValid  := jopCoreWithSdram.io.debugSdramCtrl.ctrlRspValid
    io.stp_sdramCtrlRspIsHigh := jopCoreWithSdram.io.debugSdramCtrl.ctrlRspIsHigh
    io.stp_sdramLowHalfData   := jopCoreWithSdram.io.debugSdramCtrl.lowHalfData

    io.stp_hangDetected := hangDetected
    io.stp_pc           := jopCoreWithSdram.io.pc.resized
    io.stp_jpc          := jopCoreWithSdram.io.jpc.resized
  }
}

/**
 * Generate Verilog for JopSdramTop
 */
object JopSdramTopVerilog extends App {
  val romFilePath = "asm/generated/serial/mem_rom.dat"
  val ramFilePath = "asm/generated/serial/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generate(InOutWrapper(JopSdramTop(romData, ramData)))

  println("Generated: generated/JopSdramTop.v")
}
