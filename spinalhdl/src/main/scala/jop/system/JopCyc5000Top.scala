package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.io.InOutWrapper
import spinal.lib.memory.sdram.sdr._
import jop.io.{BmbSys, BmbUart}
import jop.utils.JopFileLoader
import jop.memory.{JopMemoryConfig, W9864G6JT}
import jop.pipeline.JumpTableInitData

/**
 * CYC5000 PLL BlackBox
 *
 * Wraps a Cyclone V altera_pll megafunction.
 * 12 MHz input -> c0=80MHz (JOP system), c1=80MHz/-2.5ns (SDRAM clock pin)
 */
case class Cyc5000Pll() extends BlackBox {
  setDefinitionName("cyc5000_pll")

  val io = new Bundle {
    val refclk   = in Bool()
    val rst      = in Bool()
    val outclk_0 = out Bool()
    val outclk_1 = out Bool()
    val locked   = out Bool()
  }

  noIoPrefix()
}

/**
 * JOP SDRAM FPGA Top-Level for Trenz CYC5000
 *
 * Board: Trenz CYC5000 (TEI0050)
 * FPGA: Altera Cyclone V E (5CEBA2U15C8N)
 * SDRAM: W9864G6JT-6 (64Mbit, 16-bit, 12-bit addr, 4 banks)
 * Clock: 12 MHz oscillator -> PLL -> 80 MHz system, 80 MHz/-2.5ns SDRAM
 *
 * Architecture matches JopSdramTop (QMTECH) for direct SDRAM comparison.
 * Serial-boot: same download protocol as other SDRAM/DDR3 boards.
 *
 * @param romInit Microcode ROM initialization data (serial-boot)
 * @param ramInit Stack RAM initialization data (serial-boot)
 */
case class JopCyc5000Top(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt]
) extends Component {

  val io = new Bundle {
    val clk_in    = in Bool()
    val ser_txd   = out Bool()
    val ser_rxd   = in Bool()
    val led       = out Bits(8 bits)
    val sdram_clk = out Bool()
    val sdram     = master(SdramInterface(W9864G6JT.layout))
  }

  noIoPrefix()

  // ========================================================================
  // PLL: 12 MHz -> 80 MHz system, 80 MHz/-2.5ns SDRAM clock
  // ========================================================================

  val pll = Cyc5000Pll()
  pll.io.refclk := io.clk_in
  pll.io.rst    := False

  // SDRAM clock output (PLL outclk_1: 80 MHz with -2.5ns phase shift)
  io.sdram_clk := pll.io.outclk_1

  // ========================================================================
  // Reset Generator (on PLL outclk_0 = 80 MHz)
  // ========================================================================

  val rawClockDomain = ClockDomain(
    clock = pll.io.outclk_0,
    config = ClockDomainConfig(resetKind = BOOT)
  )

  val resetGen = new ClockingArea(rawClockDomain) {
    val res_cnt = Reg(UInt(3 bits)) init(0)
    when(pll.io.locked && res_cnt =/= 7) {
      res_cnt := res_cnt + 1
    }
    val int_res = !pll.io.locked || !res_cnt(0) || !res_cnt(1) || !res_cnt(2)
  }

  val mainClockDomain = ClockDomain(
    clock = pll.io.outclk_0,
    reset = resetGen.int_res,
    frequency = FixedFrequency(80 MHz),
    config = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  // ========================================================================
  // Main Design Area (80 MHz)
  // ========================================================================

  val mainArea = new ClockingArea(mainClockDomain) {

    val config = JopCoreConfig(
      memConfig = JopMemoryConfig(burstLen = 0),
      jumpTable = JumpTableInitData.serial
    )

    // JBC init: empty (zeros) — BC_FILL loads bytecodes dynamically from SDRAM
    val jbcInit = Seq.fill(2048)(BigInt(0))

    // JOP System with SDRAM backend (W9864G6JT layout + timing)
    val jopCoreWithSdram = JopCoreWithSdram(
      config = config,
      sdramLayout = W9864G6JT.layout,
      sdramTiming = W9864G6JT.timingGrade6,
      CAS = 2,
      useAlteraCtrl = true,
      clockFreqHz = 80000000L,
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
    val bmbSys = BmbSys(clkFreqHz = 80000000L)
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
    // LED Driver (Debug Mode)
    // ======================================================================

    // Heartbeat: ~1 Hz toggle (40M cycles at 80 MHz)
    val heartbeat = Reg(Bool()) init(False)
    val heartbeatCnt = Reg(UInt(26 bits)) init(0)
    heartbeatCnt := heartbeatCnt + 1
    when(heartbeatCnt === 39999999) {
      heartbeatCnt := 0
      heartbeat := ~heartbeat
    }

    // CYC5000 LEDs are active low
    // LED[7:3] = memory controller state (5 bits)
    // LED[2]   = memBusy
    // LED[1]   = heartbeat (proves clock is running)
    // LED[0]   = watchdog bit 0 (proves Java code is running)
    io.led(7 downto 3) := ~jopCoreWithSdram.io.debugMemState.asBits.resized
    io.led(2) := ~jopCoreWithSdram.io.memBusy
    io.led(1) := ~heartbeat
    io.led(0) := ~bmbSys.io.wd(0)
  }
}

/**
 * Generate Verilog for JopCyc5000Top
 */
object JopCyc5000TopVerilog extends App {
  val romFilePath = "asm/generated/serial/mem_rom.dat"
  val ramFilePath = "asm/generated/serial/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(80 MHz)
  ).generate(InOutWrapper(JopCyc5000Top(romData, ramData)))

  println("Generated: spinalhdl/generated/JopCyc5000Top.v")
}
