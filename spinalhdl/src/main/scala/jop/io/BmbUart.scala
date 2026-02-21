package jop.io

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._

/**
 * UART I/O slave (slave 1) — matches VHDL sc_uart.vhd
 *
 * Provides buffered UART TX and RX with JOP I/O interface.
 * TX and RX each have 16-entry FIFOs.
 *
 * Address map:
 *   0x0 read  — Status: bit 0 = TX ready (TDRE), bit 1 = RX data available (RDRF)
 *   0x1 read  — RX data (consumes from RX FIFO)
 *   0x1 write — TX data (pushes to TX FIFO)
 *
 * @param baudRate UART baud rate in Hz
 * @param clkFreqHz Clock frequency in Hz (avoids dependency on ClockDomain frequency)
 */
case class BmbUart(baudRate: Int = 1000000, clkFreqHz: Long = 100000000L) extends Component {
  val io = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)
    val txd    = out Bool()
    val rxd    = in Bool()
  }

  // UART controller with 5x oversampling
  val uartCtrl = new UartCtrl(UartCtrlGenerics(
    preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1
  ))
  uartCtrl.io.config.setClockDivider(baudRate Hz, clkFreqHz Hz)
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
  val rxFifo = StreamFifo(Bits(8 bits), 16)
  rxFifo.io.push.valid := uartCtrl.io.read.valid
  rxFifo.io.push.payload := uartCtrl.io.read.payload
  uartCtrl.io.read.ready := rxFifo.io.push.ready

  // UART pins
  io.txd := uartCtrl.io.uart.txd
  uartCtrl.io.uart.rxd := io.rxd

  // Read mux (combinational)
  io.rdData := 0
  rxFifo.io.pop.ready := False
  switch(io.addr) {
    is(0) {
      // Status: bit 0 = TX FIFO not full (TDRE), bit 1 = RX data available (RDRF)
      io.rdData := B(0, 30 bits) ## rxFifo.io.pop.valid.asBits ## txFifo.io.availability.orR.asBits
    }
    is(1) {
      // Data read: return RX byte and consume from FIFO
      io.rdData := B(0, 24 bits) ## rxFifo.io.pop.payload
      when(io.rd) {
        rxFifo.io.pop.ready := True
      }
    }
  }

  // Write handling
  txFifo.io.push.valid := False
  txFifo.io.push.payload := 0
  when(io.wr) {
    switch(io.addr) {
      is(1) {
        txFifo.io.push.valid := True
        txFifo.io.push.payload := io.wrData(7 downto 0)
      }
    }
  }
}
