package jop.memory

import spinal.core._
import spinal.lib._
import spinal.lib.memory.sdram._
import spinal.lib.memory.sdram.sdr._

/**
 * Adapter between SpinalHDL's SdramCtrlBus and Altera's altera_sdram_tri_controller.
 *
 * Drop-in replacement for SdramCtrl — exposes the same SdramCtrlBus[T] + SdramInterface.
 * The Altera controller uses Avalon-MM (read/write/waitrequest/readdatavalid), so we need
 * context tracking FIFOs since Avalon has no context passthrough.
 *
 * Write responses are generated locally (Altera doesn't produce readdatavalid for writes).
 */
case class AlteraSdramAdapter[T <: Data](
  layout: SdramLayout,
  alteraCfg: AlteraSdramConfig,
  contextType: T
) extends Component {

  val io = new Bundle {
    val bus = slave(SdramCtrlBus(layout, contextType))
    val sdram = master(SdramInterface(layout))
  }

  // Altera SDRAM controller BlackBox
  val altera = AlteraSdramBlackBox(alteraCfg)

  // TCM not used (TRISTATE_EN=0)
  altera.io.tcm_grant := True

  // ==========================================================================
  // Command side: SdramCtrlBus.cmd -> Avalon-MM
  // ==========================================================================

  val isRead  = io.bus.cmd.valid && !io.bus.cmd.write
  val isWrite = io.bus.cmd.valid &&  io.bus.cmd.write

  altera.io.avs_read      := isRead && !altera.io.avs_waitrequest
  altera.io.avs_write     := isWrite && !altera.io.avs_waitrequest
  altera.io.avs_address   := io.bus.cmd.address.resize(alteraCfg.ctrlAddrWidth)
  altera.io.avs_writedata := io.bus.cmd.data
  altera.io.avs_byteenable := io.bus.cmd.mask

  // Command accepted when Avalon not stalling
  io.bus.cmd.ready := io.bus.cmd.valid && !altera.io.avs_waitrequest

  // ==========================================================================
  // Context tracking FIFOs
  // ==========================================================================

  // Read context FIFO: push on read acceptance, pop on readdatavalid
  val readCtxFifo = StreamFifo(cloneOf(contextType), depth = 8)
  readCtxFifo.io.push.valid   := isRead && !altera.io.avs_waitrequest
  readCtxFifo.io.push.payload := io.bus.cmd.context

  // Write context FIFO: push on write acceptance, pop when generating write response
  val writeCtxFifo = StreamFifo(cloneOf(contextType), depth = 4)
  writeCtxFifo.io.push.valid   := isWrite && !altera.io.avs_waitrequest
  writeCtxFifo.io.push.payload := io.bus.cmd.context

  // ==========================================================================
  // Response side: Avalon -> SdramCtrlBus.rsp
  // ==========================================================================

  // Read responses from Altera have priority over write responses
  val readRspPending = altera.io.avs_readdatavalid

  when(readRspPending) {
    // Read response
    io.bus.rsp.valid   := True
    io.bus.rsp.data    := altera.io.avs_readdata
    io.bus.rsp.context := readCtxFifo.io.pop.payload
    readCtxFifo.io.pop.ready  := io.bus.rsp.ready
    writeCtxFifo.io.pop.ready := False
  }.otherwise {
    // Write response (when no read pending and write ctx available)
    io.bus.rsp.valid   := writeCtxFifo.io.pop.valid
    io.bus.rsp.data    := 0
    io.bus.rsp.context := writeCtxFifo.io.pop.payload
    readCtxFifo.io.pop.ready  := False
    writeCtxFifo.io.pop.ready := io.bus.rsp.ready && writeCtxFifo.io.pop.valid
  }

  // ==========================================================================
  // SDRAM pin mapping: Altera -> SdramInterface
  // ==========================================================================

  io.sdram.ADDR := altera.io.sdram_addr.resized
  io.sdram.BA   := altera.io.sdram_ba
  io.sdram.DQM  := altera.io.sdram_dqm
  io.sdram.CASn := altera.io.sdram_cas_n
  io.sdram.RASn := altera.io.sdram_ras_n
  io.sdram.WEn  := altera.io.sdram_we_n
  io.sdram.CSn  := altera.io.sdram_cs_n.lsb
  io.sdram.CKE  := altera.io.sdram_cke

  // DQ tristate: SpinalHDL TriStateArray <-> Altera separate in/out/oe
  io.sdram.DQ.write := altera.io.sdram_dq_out
  when(altera.io.sdram_dq_oe) {
    io.sdram.DQ.writeEnable.setAll()
  } otherwise {
    io.sdram.DQ.writeEnable.clearAll()
  }
  altera.io.sdram_dq_in := io.sdram.DQ.read
}
