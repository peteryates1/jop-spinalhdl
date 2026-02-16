package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import spinal.lib.com.uart._
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig

/**
 * JOP BRAM FPGA Top-Level for QMTECH EP4CGX150
 *
 * Runs JOP processor with BRAM-backed memory and real UART output.
 * Uses PLL to run at 100 MHz from 50 MHz input clock.
 *
 * @param romInit Microcode ROM initialization data
 * @param ramInit Stack RAM initialization data
 * @param mainMemInit Main memory initialization data (from .jop file)
 */
case class JopBramTop(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt]
) extends Component {

  val io = new Bundle {
    val clk_in  = in Bool()
    val ser_txd = out Bool()
    val led     = out Bits(2 bits)
  }

  // Remove auto-generated clock/reset - we manage our own
  noIoPrefix()

  // ========================================================================
  // PLL: 50 MHz -> 100 MHz system clock
  // ========================================================================

  val pll = DramPll()
  pll.io.inclk0 := io.clk_in
  pll.io.areset := False

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
  // Main Design Area
  // ========================================================================

  val mainArea = new ClockingArea(mainClockDomain) {

    val config = JopSystemConfig(
      memConfig = JopMemoryConfig(mainMemSize = 32 * 1024)  // 32KB BRAM
    )

    // Extract JBC init from main memory (same logic as JopSystemTestHarness)
    val mpAddr = if (mainMemInit.length > 1) mainMemInit(1).toInt else 0
    val bootMethodStructAddr = if (mainMemInit.length > mpAddr) mainMemInit(mpAddr).toInt else 0
    val bootMethodStartLen = if (mainMemInit.length > bootMethodStructAddr) mainMemInit(bootMethodStructAddr).toLong else 0
    val bootCodeStart = (bootMethodStartLen >> 10).toInt
    val bytecodeStartWord = if (bootCodeStart > 0) bootCodeStart else 35
    val bytecodeWords = mainMemInit.slice(bytecodeStartWord, bytecodeStartWord + 512)

    // Convert words to bytes (big-endian)
    val jbcInit = bytecodeWords.flatMap { word =>
      val w = word.toLong & 0xFFFFFFFFL
      Seq(
        BigInt((w >> 24) & 0xFF),
        BigInt((w >> 16) & 0xFF),
        BigInt((w >> 8) & 0xFF),
        BigInt((w >> 0) & 0xFF)
      )
    }.padTo(2048, BigInt(0))

    // JOP System core
    val jopSystem = JopSystem(
      config = config,
      romInit = Some(romInit),
      ramInit = Some(ramInit),
      jbcInit = Some(jbcInit)
    )

    // Block RAM with BMB interface
    val ram = BmbOnChipRam(
      p = config.memConfig.bmbParameter,
      size = config.memConfig.mainMemSize,
      hexInit = null
    )

    // Initialize RAM
    val memWords = config.memConfig.mainMemWords.toInt
    val initData = mainMemInit.take(memWords).padTo(memWords, BigInt(0))
    ram.ram.init(initData.map(v => B(v, 32 bits)))

    // Connect BMB
    ram.io.bus << jopSystem.io.bmb

    // Drive debug RAM port (unused)
    jopSystem.io.debugRamAddr := 0

    // Interrupts (disabled)
    jopSystem.io.irq := False
    jopSystem.io.irqEna := False

    // ======================================================================
    // UART
    // ======================================================================

    // At 100 MHz: clockDivider for 1 Mbaud with 5x oversampling
    // divider = 100MHz / (1Mbaud * 5) = 20 => exact 1 Mbaud
    val uartCtrl = new UartCtrl(UartCtrlGenerics(
      preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1  // 5 samples/bit
    ))
    uartCtrl.io.config.setClockDivider(1000000 Hz)
    uartCtrl.io.config.frame.dataLength := 7  // 8 bits (0-indexed)
    uartCtrl.io.config.frame.parity := UartParityType.NONE
    uartCtrl.io.config.frame.stop := UartStopType.ONE

    // TX FIFO: 16-entry buffer between JOP I/O writes and UART
    val txFifo = StreamFifo(Bits(8 bits), 16)

    // Connect FIFO output to UART TX
    uartCtrl.io.write.valid := txFifo.io.pop.valid
    uartCtrl.io.write.payload := txFifo.io.pop.payload
    txFifo.io.pop.ready := uartCtrl.io.write.ready

    // Unused UART signals
    uartCtrl.io.uart.rxd := True
    uartCtrl.io.read.ready := False
    uartCtrl.io.writeBreak := False

    // UART TX output
    io.ser_txd := uartCtrl.io.uart.txd

    // ======================================================================
    // I/O Decode (combinational, matching JopSystemTestHarness)
    // ======================================================================

    // System counter (free-running)
    val sysCntReg = Reg(UInt(32 bits)) init(0)
    sysCntReg := sysCntReg + 1

    // Watchdog register
    val wdReg = Reg(Bits(32 bits)) init(0)

    // I/O read data - COMBINATIONAL
    val ioRdData = Bits(32 bits)
    ioRdData := 0

    val ioSubAddr = jopSystem.io.ioAddr(3 downto 0)
    val ioSlaveId = jopSystem.io.ioAddr(5 downto 4)

    // I/O read handling
    switch(ioSlaveId) {
      is(0) {  // System
        switch(ioSubAddr) {
          is(0) { ioRdData := sysCntReg.asBits }        // Counter
          is(1) { ioRdData := sysCntReg.asBits }        // Microsecond counter
          is(6) { ioRdData := B(0, 32 bits) }           // CPU ID
          is(7) { ioRdData := B(0, 32 bits) }           // Signal
        }
      }
      is(1) {  // UART
        switch(ioSubAddr) {
          is(0) { ioRdData := B(0, 31 bits) ## txFifo.io.availability.orR.asBits }  // Status: bit 0 = TX FIFO not full
        }
      }
    }
    jopSystem.io.ioRdData := ioRdData

    // I/O write handling
    // FIFO push defaults
    txFifo.io.push.valid := False
    txFifo.io.push.payload := 0

    when(jopSystem.io.ioWr) {
      switch(ioSlaveId) {
        is(0) {  // System
          switch(ioSubAddr) {
            is(3) { wdReg := jopSystem.io.ioWrData }    // Watchdog
          }
        }
        is(1) {  // UART
          switch(ioSubAddr) {
            is(1) {  // UART data write
              txFifo.io.push.valid := True
              txFifo.io.push.payload := jopSystem.io.ioWrData(7 downto 0)
            }
          }
        }
      }
    }

    // ======================================================================
    // LED Driver
    // ======================================================================

    // LEDs directly from watchdog register (active low on QMTECH board)
    io.led := ~wdReg(1 downto 0)
  }
}

/**
 * Generate Verilog for JopBramTop
 */
object JopBramTopVerilog extends App {
  val jopFilePath = "/home/peter/git/jopmin/java/Smallest/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 32 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "core/spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generate(JopBramTop(romData, ramData, mainMemData))

  println("Generated: generated/JopBramTop.v")
}
