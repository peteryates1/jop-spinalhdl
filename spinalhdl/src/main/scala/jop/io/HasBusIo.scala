package jop.io

import spinal.core._

/**
 * Trait for I/O devices with a standard register bus interface.
 *
 * All I/O peripherals share the same bus pattern:
 *   - 4-bit sub-address (within the device's allocated address range)
 *   - Read/write strobes
 *   - 32-bit read/write data
 *
 * Devices mix in this trait and implement the accessors to return
 * references to their bus bundle signals. JopCore's I/O wiring loop
 * uses these accessors to connect address decoding, data muxing,
 * interrupts, and pipeline stall signals automatically.
 *
 * External pins (SPI, VGA, UART, etc.) go in the device's io bundle.
 * Override busExternalIo to return the io bundle and JopCore will
 * auto-create passthrough ports for it.
 *
 * Special signals:
 *   - busInterrupts: Device interrupt outputs wired to Sys
 *   - busBusy: Pipeline stall (for compute-bound devices)
 *   - busBoutSink: Direct NOS (bottom-of-stack) wire for auto-capture
 */
trait HasBusIo { self: Component =>
  /** 4-bit sub-address input */
  def busAddr: UInt
  /** Read strobe (active for one cycle) */
  def busRd: Bool
  /** Write strobe (active for one cycle) */
  def busWr: Bool
  /** 32-bit write data from pipeline (= TOS / aout) */
  def busWrData: Bits
  /** 32-bit read data to pipeline */
  def busRdData: Bits
  /** Interrupt outputs to Sys (default: none) */
  def busInterrupts: Seq[Bool] = Seq.empty
  /** Pipeline busy/stall signal (default: none) */
  def busBusy: Option[Bool] = None
  /** NOS (bout) sink for operand auto-capture (default: none) */
  def busBoutSink: Option[Bits] = None
  /** External pin bundle for auto-passthrough (default: none) */
  def busExternalIo: Option[Bundle] = None
}
