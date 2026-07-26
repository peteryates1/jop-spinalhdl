package jop.memory

import spinal.core._
import spinal.lib._

/**
 * Sideband "fill" (block memset) interface between the memory controller and a
 * memory backend, threaded alongside the BMB bus.
 *
 * The controller (master) requests that the backend write `value` to every word
 * in `[start, end)` (word addresses in the BMB word-address space) at the
 * backend's native full speed — SDR burst, DDR3 128-bit MIG burst, etc. This
 * keeps the fast-zero mechanism in each swappable backend rather than in the
 * portable controller, which otherwise falls back to a per-word BMB loop.
 *
 * Handshake (req/busy): the master holds `cmd` until the backend raises `busy`
 * (command latched), then drops `cmd` and waits for `busy` to fall (fill done).
 * Because a fill is only issued during stop-the-world GC, blocking is fine.
 *
 * @param addressWidth width of a word address (matches memConfig.addressWidth)
 */
case class MemFill(addressWidth: Int) extends Bundle with IMasterSlave {
  val cmd   = Bool()                    // master holds high to request a fill
  val start = UInt(addressWidth bits)   // first word address (inclusive)
  val end   = UInt(addressWidth bits)   // last word address (exclusive)
  val value = Bits(32 bits)             // fill value (0 for zeroing)
  val busy  = Bool()                    // backend asserts while a fill is in progress

  override def asMaster(): Unit = {
    out(cmd, start, end, value)
    in(busy)
  }
}
