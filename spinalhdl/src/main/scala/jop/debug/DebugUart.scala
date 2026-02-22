package jop.debug

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._

/**
 * Dedicated debug UART with small FIFOs.
 *
 * Wraps SpinalHDL's UartCtrl to provide Stream[Bits(8)] in/out.
 * Separate from the main JOP UART to avoid contention with the serial
 * boot protocol.
 *
 * @param baudRate  Baud rate in Hz
 * @param clkFreqHz System clock frequency in Hz
 */
case class DebugUart(
  baudRate: Int = 1000000,
  clkFreqHz: Long = 100000000L
) extends Component {

  val io = new Bundle {
    val transport = master(DebugTransport())
    val txd = out Bool()
    val rxd = in Bool()
  }

  // UART controller with 5x oversampling (matching BmbUart)
  val uartCtrl = new UartCtrl(UartCtrlGenerics(
    preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1
  ))
  uartCtrl.io.config.setClockDivider(baudRate Hz, clkFreqHz Hz)
  uartCtrl.io.config.frame.dataLength := 7  // 8 bits (0-indexed)
  uartCtrl.io.config.frame.parity := UartParityType.NONE
  uartCtrl.io.config.frame.stop := UartStopType.ONE

  // Physical UART pins
  io.txd := uartCtrl.io.uart.txd
  uartCtrl.io.uart.rxd := io.rxd

  // RX: UART -> transport.rxByte (with small FIFO)
  val rxFifo = StreamFifo(Bits(8 bits), 16)
  rxFifo.io.push.valid := uartCtrl.io.read.valid
  rxFifo.io.push.payload := uartCtrl.io.read.payload
  uartCtrl.io.read.ready := rxFifo.io.push.ready

  io.transport.rxByte.valid := rxFifo.io.pop.valid
  io.transport.rxByte.payload := rxFifo.io.pop.payload
  rxFifo.io.pop.ready := io.transport.rxByte.ready

  // TX: transport.txByte -> UART (with small FIFO)
  val txFifo = StreamFifo(Bits(8 bits), 16)
  txFifo.io.push.valid := io.transport.txByte.valid
  txFifo.io.push.payload := io.transport.txByte.payload
  io.transport.txByte.ready := txFifo.io.push.ready

  uartCtrl.io.write.valid := txFifo.io.pop.valid
  uartCtrl.io.write.payload := txFifo.io.pop.payload
  txFifo.io.pop.ready := uartCtrl.io.write.ready

  // No write break
  uartCtrl.io.writeBreak := False
}
