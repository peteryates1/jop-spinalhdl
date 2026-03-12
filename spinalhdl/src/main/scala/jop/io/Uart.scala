package jop.io

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._

/**
 * UART I/O device — matches VHDL sc_uart.vhd
 *
 * Provides buffered UART TX and RX with register bus interface.
 * TX and RX each have FIFOs.
 *
 * Register map (bus.addr):
 *   0x0 read  — Status: bit 0 = TDRE, bit 1 = RDRF, bit 2 = TX int enabled, bit 3 = RX int enabled
 *   0x1 read  — RX data (consumes from RX FIFO)
 *   0x1 write — TX data (pushes to TX FIFO)
 *   0x2 write — Interrupt control: bit 0 = TX int enable, bit 1 = RX int enable
 *
 * @param baudRate UART baud rate in Hz
 * @param clkFreq Clock frequency (avoids dependency on ClockDomain frequency)
 */
case class Uart(baudRate: Int = 1000000, clkFreq: HertzNumber = HertzNumber(100000000)) extends Component with HasBusIo {
  val bus = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)
    val rxInterrupt = out Bool()
    val txInterrupt = out Bool()
  }

  val io = new Bundle {
    val txd = out Bool()
    val rxd = in  Bool()
  }

  // UART controller with 5x oversampling
  val uartCtrl = new UartCtrl(UartCtrlGenerics(
    preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1
  ))
  uartCtrl.io.config.setClockDivider(baudRate Hz, clkFreq)
  uartCtrl.io.config.frame.dataLength := 7  // 8 bits (0-indexed)
  uartCtrl.io.config.frame.parity := UartParityType.NONE
  uartCtrl.io.config.frame.stop := UartStopType.ONE
  uartCtrl.io.writeBreak := False

  // TX FIFO
  val txFifo = StreamFifo(Bits(8 bits), 16)
  uartCtrl.io.write.valid := txFifo.io.pop.valid
  uartCtrl.io.write.payload := txFifo.io.pop.payload
  txFifo.io.pop.ready := uartCtrl.io.write.ready

  // RX FIFO: UartCtrl.read.valid is a ONE-CYCLE PULSE (RegNext(False)),
  // so we must buffer it. Matches VHDL sc_uart which uses a FIFO for RX.
  // 512 entries: at 2 Mbaud, provides ~2.5 ms of buffering. This prevents
  // overflow during DDR3 write stalls where the microcode blocks on 'wait'
  // while the host continues streaming.
  val rxFifo = StreamFifo(Bits(8 bits), 512)
  rxFifo.io.push.valid := uartCtrl.io.read.valid
  rxFifo.io.push.payload := uartCtrl.io.read.payload
  uartCtrl.io.read.ready := rxFifo.io.push.ready

  // UART pins
  io.txd := uartCtrl.io.uart.txd
  uartCtrl.io.uart.rxd := io.rxd

  // Interrupt enable registers
  val rxIntEnaReg = Reg(Bool()) init(False)
  val txIntEnaReg = Reg(Bool()) init(False)

  // Interrupt generation — single-cycle pulses (Sys intstate is an SR flip-flop)
  // RX: pulse on rising edge of "RX FIFO non-empty" when enabled
  val rxNonEmpty = rxFifo.io.pop.valid
  val rxNonEmptyDly = RegNext(rxNonEmpty) init(False)
  bus.rxInterrupt := rxIntEnaReg && rxNonEmpty && !rxNonEmptyDly

  // TX: pulse on rising edge of "TX FIFO empty" when enabled
  val txEmpty = txFifo.io.occupancy === 0
  val txEmptyDly = RegNext(txEmpty) init(True)
  bus.txInterrupt := txIntEnaReg && txEmpty && !txEmptyDly

  // Read mux (combinational)
  bus.rdData := 0
  rxFifo.io.pop.ready := False
  switch(bus.addr) {
    is(0) {
      // Status: bit 0 = TDRE, bit 1 = RDRF, bit 2 = TX int enabled, bit 3 = RX int enabled
      bus.rdData := B(0, 28 bits) ## rxIntEnaReg.asBits ## txIntEnaReg.asBits ## rxFifo.io.pop.valid.asBits ## txFifo.io.availability.orR.asBits
    }
    is(1) {
      // Data read: return RX byte and consume from FIFO
      bus.rdData := B(0, 24 bits) ## rxFifo.io.pop.payload
      when(bus.rd) {
        rxFifo.io.pop.ready := True
      }
    }
  }

  // Write handling
  txFifo.io.push.valid := False
  txFifo.io.push.payload := 0
  when(bus.wr) {
    switch(bus.addr) {
      is(1) {
        txFifo.io.push.valid := True
        txFifo.io.push.payload := bus.wrData(7 downto 0)
      }
      is(2) {
        // Interrupt control: bit 0 = TX int enable, bit 1 = RX int enable
        txIntEnaReg := bus.wrData(0)
        rxIntEnaReg := bus.wrData(1)
      }
    }
  }

  // HasBusIo implementation
  override def busAddr: UInt   = bus.addr
  override def busRd: Bool     = bus.rd
  override def busWr: Bool     = bus.wr
  override def busWrData: Bits = bus.wrData
  override def busRdData: Bits = bus.rdData
  override def busInterrupts: Seq[Bool] = Seq(bus.rxInterrupt, bus.txInterrupt)
  override def busExternalIo: Option[Bundle] = Some(io)
}
