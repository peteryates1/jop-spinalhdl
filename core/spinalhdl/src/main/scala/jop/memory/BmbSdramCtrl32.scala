package jop.memory

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import spinal.lib.memory.sdram._
import spinal.lib.memory.sdram.sdr._

/**
 * 32-bit BMB to 16-bit SDRAM bridge.
 *
 * Handles the width conversion internally by issuing two 16-bit SDRAM
 * operations per 32-bit BMB transaction:
 *   - Read: issues two SdramCtrl reads, assembles 32-bit response
 *   - Write: issues two SdramCtrl writes with low/high halves
 *
 * This replaces the BmbDownSizerBridge + BmbSdramCtrl approach which
 * doesn't work because SdramCtrl is a one-command-one-response controller
 * that can't produce multi-beat BMB responses.
 *
 * @param bmbParameter BMB interface parameters (must be 32-bit data width)
 * @param layout SDRAM layout (must be 16-bit data width)
 * @param timing SDRAM timing parameters
 * @param CAS CAS latency
 */
case class BmbSdramCtrl32(
  bmbParameter: BmbParameter,
  layout: SdramLayout,
  timing: SdramTimings,
  CAS: Int
) extends Component {
  assert(bmbParameter.access.dataWidth == 32, "BMB data width must be 32")
  assert(layout.dataWidth == 16, "SDRAM data width must be 16")

  val io = new Bundle {
    val bmb = slave(Bmb(bmbParameter))
    val sdram = master(SdramInterface(layout))
  }

  // Context carries BMB transaction info through SdramCtrl pipeline.
  // isHigh distinguishes the two SDRAM ops per 32-bit transaction.
  case class SdramContext() extends Bundle {
    val source = UInt(bmbParameter.access.sourceWidth bits)
    val context = Bits(bmbParameter.access.contextWidth bits)
    val isHigh = Bool()
  }

  val ctrl = SdramCtrl(layout, timing, CAS, SdramContext(), produceRspOnWrite = true)

  // ==========================================================================
  // Command side: split 32-bit BMB into two 16-bit SDRAM commands
  // ==========================================================================

  val sendingHigh = RegInit(False)

  // SDRAM word address = BMB byte address >> 1 (for 16-bit SDRAM)
  val sdramWordAddr = (io.bmb.cmd.address >> log2Up(layout.bytePerWord)).resize(layout.wordAddressWidth)

  ctrl.io.bus.cmd.valid := io.bmb.cmd.valid
  ctrl.io.bus.cmd.write := io.bmb.cmd.isWrite
  ctrl.io.bus.cmd.context.source := io.bmb.cmd.source
  ctrl.io.bus.cmd.context.context := io.bmb.cmd.context

  when(!sendingHigh) {
    // Low half: even SDRAM word
    ctrl.io.bus.cmd.address := sdramWordAddr
    ctrl.io.bus.cmd.data := io.bmb.cmd.data(15 downto 0)
    ctrl.io.bus.cmd.mask := io.bmb.cmd.mask(1 downto 0)
    ctrl.io.bus.cmd.context.isHigh := False
  } otherwise {
    // High half: odd SDRAM word (address + 1)
    ctrl.io.bus.cmd.address := sdramWordAddr + 1
    ctrl.io.bus.cmd.data := io.bmb.cmd.data(31 downto 16)
    ctrl.io.bus.cmd.mask := io.bmb.cmd.mask(3 downto 2)
    ctrl.io.bus.cmd.context.isHigh := True
  }

  // Accept BMB command only after both halves sent
  io.bmb.cmd.ready := ctrl.io.bus.cmd.ready && sendingHigh

  when(ctrl.io.bus.cmd.fire) {
    sendingHigh := !sendingHigh
  }

  // ==========================================================================
  // Response side: collect two 16-bit responses into one 32-bit response
  // ==========================================================================

  val lowHalfData = Reg(Bits(16 bits))

  when(ctrl.io.bus.rsp.fire && !ctrl.io.bus.rsp.context.isHigh) {
    lowHalfData := ctrl.io.bus.rsp.data
  }

  // Forward to BMB only on high-half response (both halves available)
  io.bmb.rsp.valid := ctrl.io.bus.rsp.valid && ctrl.io.bus.rsp.context.isHigh
  io.bmb.rsp.last := True
  io.bmb.rsp.setSuccess()
  io.bmb.rsp.source := ctrl.io.bus.rsp.context.source
  io.bmb.rsp.context := ctrl.io.bus.rsp.context.context
  io.bmb.rsp.data := ctrl.io.bus.rsp.data ## lowHalfData  // {high16, low16}

  // Accept low-half responses immediately (just buffer them);
  // accept high-half responses only when BMB rsp is consumed
  ctrl.io.bus.rsp.ready := Mux(
    ctrl.io.bus.rsp.context.isHigh,
    io.bmb.rsp.ready,
    True
  )

  io.sdram <> ctrl.io.sdram
}
