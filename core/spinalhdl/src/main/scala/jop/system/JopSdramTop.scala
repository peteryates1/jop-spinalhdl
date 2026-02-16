package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import spinal.lib.io.InOutWrapper
import spinal.lib.memory.sdram.sdr._
import jop.utils.JopFileLoader

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

  // ========================================================================
  // Reset Generator (on PLL c1 = 100 MHz)
  // ========================================================================

  // Raw clock domain from PLL c1 (no reset yet)
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

    val config = JopSystemConfig()

    // JBC init: empty (zeros) — BC_FILL loads bytecodes dynamically from SDRAM
    val jbcInit = Seq.fill(2048)(BigInt(0))

    // JOP System with SDRAM backend
    val jopSdram = JopSystemWithSdram(
      config = config,
      romInit = Some(romInit),
      ramInit = Some(ramInit),
      jbcInit = Some(jbcInit)
    )

    // Connect SDRAM interface
    io.sdram <> jopSdram.io.sdram

    // Interrupts (disabled)
    jopSdram.io.irq := False
    jopSdram.io.irqEna := False

    // ======================================================================
    // UART (TX + RX)
    // ======================================================================

    // 5x oversampling: 100MHz / (100 * 5) = 200,000... no.
    // At 100 MHz: clockDivider for 1 Mbaud with 5x oversampling
    // divider = 100MHz / (1Mbaud * 5) = 20 => exact 1 Mbaud
    val uartCtrl = new UartCtrl(UartCtrlGenerics(
      preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1  // 5 samples/bit
    ))
    uartCtrl.io.config.setClockDivider(1000000 Hz)
    uartCtrl.io.config.frame.dataLength := 7  // 8 bits (0-indexed)
    uartCtrl.io.config.frame.parity := UartParityType.NONE
    uartCtrl.io.config.frame.stop := UartStopType.ONE
    uartCtrl.io.writeBreak := False

    // TX FIFO: 16-entry buffer between JOP I/O writes and UART
    val txFifo = StreamFifo(Bits(8 bits), 16)

    // Connect FIFO output to UART TX
    uartCtrl.io.write.valid := txFifo.io.pop.valid
    uartCtrl.io.write.payload := txFifo.io.pop.payload
    txFifo.io.pop.ready := uartCtrl.io.write.ready

    // UART TX output
    io.ser_txd := uartCtrl.io.uart.txd

    // UART RX input
    uartCtrl.io.uart.rxd := io.ser_rxd

    // RX: default ready = False, asserted for one cycle on I/O read
    uartCtrl.io.read.ready := False

    // ======================================================================
    // I/O Decode (combinational, matching JopBramTop)
    // ======================================================================

    // System counter (free-running)
    val sysCntReg = Reg(UInt(32 bits)) init(0)
    sysCntReg := sysCntReg + 1

    // Watchdog register
    val wdReg = Reg(Bits(32 bits)) init(0)

    // I/O read data - COMBINATIONAL
    val ioRdData = Bits(32 bits)
    ioRdData := 0

    val ioSubAddr = jopSdram.io.ioAddr(3 downto 0)
    val ioSlaveId = jopSdram.io.ioAddr(5 downto 4)

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
          is(0) {
            // Status: bit 0 = TX FIFO not full (TDRE), bit 1 = RX data available (RDRF)
            ioRdData := B(0, 30 bits) ## uartCtrl.io.read.valid.asBits ## txFifo.io.availability.orR.asBits
          }
          is(1) {
            // Data read: return RX byte, consume from stream
            ioRdData := B(0, 24 bits) ## uartCtrl.io.read.payload
            when(jopSdram.io.ioRd) {
              uartCtrl.io.read.ready := True
            }
          }
        }
      }
    }
    jopSdram.io.ioRdData := ioRdData

    // I/O write handling
    txFifo.io.push.valid := False
    txFifo.io.push.payload := 0

    when(jopSdram.io.ioWr) {
      switch(ioSlaveId) {
        is(0) {  // System
          switch(ioSubAddr) {
            is(3) { wdReg := jopSdram.io.ioWrData }    // Watchdog
          }
        }
        is(1) {  // UART
          switch(ioSubAddr) {
            is(1) {  // UART data write
              txFifo.io.push.valid := True
              txFifo.io.push.payload := jopSdram.io.ioWrData(7 downto 0)
            }
          }
        }
      }
    }

    // ======================================================================
    // LED Driver
    // ======================================================================

    // LEDs from watchdog register (active low on QMTECH board)
    io.led := ~wdReg(1 downto 0)
  }
}

/**
 * Generate Verilog for JopSdramTop
 */
object JopSdramTopVerilog extends App {
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/serial/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/serial/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generate(InOutWrapper(JopSdramTop(romData, ramData)))

  println("Generated: generated/JopSdramTop.v")
}
