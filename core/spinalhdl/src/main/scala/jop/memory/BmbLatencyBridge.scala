package jop.memory

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._

/**
 * BMB Latency Bridge
 *
 * Passes BMB commands straight through and delays BMB responses by
 * `latency` clock cycles. Used to simulate SDRAM-like response latency
 * with a simple BRAM backend.
 *
 * - latency=0: pure pass-through (no extra delay)
 * - latency=1: adds 1 cycle to response path
 * - latency=N: adds N cycles to response path
 *
 * Each cycle of latency is implemented as a Stream m2sPipe() stage,
 * which registers valid+payload and passes ready combinationally.
 *
 * @param p       BMB bus parameters
 * @param latency Number of extra response cycles to add
 */
case class BmbLatencyBridge(p: BmbParameter, latency: Int) extends Component {
  require(latency >= 0, "Latency must be non-negative")

  val io = new Bundle {
    val input  = slave(Bmb(p))   // Master (JopSystem) side
    val output = master(Bmb(p))  // Slave (BmbOnChipRam) side
  }

  // Command path: straight through, no extra latency
  io.output.cmd << io.input.cmd

  // Response path: add N pipeline stages
  if (latency == 0) {
    io.input.rsp << io.output.rsp
  } else {
    var rsp: Stream[Fragment[BmbRsp]] = io.output.rsp
    for (_ <- 0 until latency) {
      rsp = rsp.m2sPipe()
    }
    io.input.rsp << rsp
  }
}
