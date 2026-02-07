package jop.memory

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import spinal.lib.memory.sdram._
import spinal.lib.memory.sdram.sdr._

/**
 * Fixed version of BmbSdramCtrl that properly drives rsp.last.
 *
 * BmbSdramCtrl in SpinalHDL 1.12.2 doesn't drive the rsp.last signal,
 * causing "NO DRIVER" errors. This is essentially a copy with the fix.
 *
 * @param bmbParameter BMB interface parameters
 * @param layout SDRAM layout (chip organization)
 * @param timing SDRAM timing parameters
 * @param CAS CAS latency
 */
case class BmbSdramCtrlFixed(
  bmbParameter: BmbParameter,
  layout: SdramLayout,
  timing: SdramTimings,
  CAS: Int
) extends Component {

  val io = new Bundle {
    val bmb = slave(Bmb(bmbParameter))
    val sdram = master(SdramInterface(layout))
  }

  case class Context() extends Bundle {
    val source = UInt(bmbParameter.access.sourceWidth bits)
    val context = Bits(bmbParameter.access.contextWidth bits)
  }

  val ctrl = SdramCtrl(layout, timing, CAS, Context(), produceRspOnWrite = true)

  // CMD
  ctrl.io.bus.cmd.arbitrationFrom(io.bmb.cmd)
  ctrl.io.bus.cmd.address := io.bmb.cmd.address >> log2Up(layout.bytePerWord)
  ctrl.io.bus.cmd.write := io.bmb.cmd.isWrite
  ctrl.io.bus.cmd.data := io.bmb.cmd.data
  ctrl.io.bus.cmd.mask := io.bmb.cmd.mask
  ctrl.io.bus.cmd.context.source := io.bmb.cmd.source
  ctrl.io.bus.cmd.context.context := io.bmb.cmd.context

  // RSP
  io.bmb.rsp.arbitrationFrom(ctrl.io.bus.rsp)
  io.bmb.rsp.last := True  // FIX: Always last for single-beat responses
  io.bmb.rsp.setSuccess()
  io.bmb.rsp.source := ctrl.io.bus.rsp.context.source
  io.bmb.rsp.data := ctrl.io.bus.rsp.data
  io.bmb.rsp.context := ctrl.io.bus.rsp.context.context

  io.sdram <> ctrl.io.sdram
}
