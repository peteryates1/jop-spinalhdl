package jop.debug

import spinal.core._
import spinal.lib._

/**
 * Debug transport byte-stream interface.
 *
 * Abstraction point between physical transports (UART, TCP socket bridge)
 * and the debug protocol layer. Both directions are Stream[Bits(8)] for
 * flow-controlled byte transfer.
 */
case class DebugTransport() extends Bundle with IMasterSlave {
  val rxByte = Stream(Bits(8 bits))  // Bytes received from host
  val txByte = Stream(Bits(8 bits))  // Bytes to send to host

  override def asMaster(): Unit = {
    master(rxByte)
    slave(txByte)
  }
}
