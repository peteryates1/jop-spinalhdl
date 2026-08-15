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
 *
 * READ DATA MUST BE BUFFERED, NOT FORWARDED. Avalon-MM `readdatavalid` is a
 * PULSE: the slave presents `avs_readdata` for exactly one cycle and there is no
 * way to stall it -- `avs_waitrequest` backpressures COMMANDS only. `io.bus.rsp`
 * is a Stream and its consumer does deassert `ready`: BmbSdramCtrl32 drives
 * `rsp.ready` low for a high half whenever its assembly pipe is occupied and the
 * BMB side is not draining.
 *
 * This adapter used to wire the two together directly. When they coincided the
 * read data was presented, not accepted, and LOST -- and the next cycle, with
 * `readdatavalid` gone, the `otherwise` branch below emitted a substitute
 * response carrying `data := 0`, which the bridge duly assembled as the read
 * result. On the EP4CGX150 that surfaced as an array-bounds exception on a valid
 * index against a length of 0 for an array that is 4 long, which killed a
 * publisher thread and wedged the whole 4-core cluster.
 *
 * The substitution is what made it so hard to find: because a response still
 * came back, commands and responses stayed BALANCED, so every "did the response
 * stream slip a beat?" check said no. Only the DATA was wrong, and only ever to
 * zero.
 *
 * It needs sustained back-to-back traffic for `rsp.ready` to drop at all, which
 * is why 2 cores never showed it and 4 did; and it is invisible in simulation,
 * because the Altera controller is a BlackBox that Verilator cannot build, so
 * every sim substitutes SdramCtrlNoCke -- a proper Stream that honours `ready`.
 * This file therefore has NO simulation coverage on any board that uses it.
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

  // ==========================================================================
  // Context and read-data FIFOs
  // ==========================================================================

  /** One buffered read response: the data Avalon gave us plus the context that
    * belongs to it. Both have to be held together, because the data can only be
    * captured on the cycle Avalon offers it while the consumer may take many
    * cycles to accept the response. */
  case class ReadRsp() extends Bundle {
    val data    = Bits(layout.dataWidth bits)
    val context = cloneOf(contextType)
  }

  // Read context FIFO: pushed when a read is accepted, popped when its DATA
  // arrives (not when the response is consumed -- that is the pairing that was
  // wrong before, and it is what the original comment here already described).
  val readCtxFifo = StreamFifo(cloneOf(contextType), depth = 8)
  // Read data FIFO: pushed on readdatavalid, which cannot be stalled.
  val readRspFifo = StreamFifo(ReadRsp(), depth = 8)
  // Write context FIFO: push on write acceptance, pop when generating a response
  val writeCtxFifo = StreamFifo(cloneOf(contextType), depth = 4)
  // Command-order FIFO: one entry per accepted command, true = read. Declared
  // here with the others because Scala initialises vals in source order and the
  // command side below reads its `push.ready`.
  val orderFifo = StreamFifo(Bool(), depth = 16)

  // ==========================================================================
  // Command side: SdramCtrlBus.cmd -> Avalon-MM
  // ==========================================================================
  //
  // A read may only be ISSUED if there is somewhere to put its data when it
  // comes back, for this read and for every read already in flight. Without
  // that the buffering above just moves the overflow one stage later. The FIFO
  // pushes were previously driven with no regard for `push.ready` at all.

  val wantRead  = io.bus.cmd.valid && !io.bus.cmd.write
  val wantWrite = io.bus.cmd.valid &&  io.bus.cmd.write

  val roomForRead  = readRspFifo.io.availability > readCtxFifo.io.occupancy &&
                     readCtxFifo.io.push.ready && orderFifo.io.push.ready
  val roomForWrite = writeCtxFifo.io.push.ready && orderFifo.io.push.ready

  val isRead  = wantRead  && roomForRead
  val isWrite = wantWrite && roomForWrite

  altera.io.avs_read      := isRead && !altera.io.avs_waitrequest
  altera.io.avs_write     := isWrite && !altera.io.avs_waitrequest
  altera.io.avs_address   := io.bus.cmd.address.resize(alteraCfg.ctrlAddrWidth)
  altera.io.avs_writedata := io.bus.cmd.data
  altera.io.avs_byteenable := io.bus.cmd.mask

  // Command accepted when Avalon is not stalling AND we can hold the result
  io.bus.cmd.ready := (isRead || isWrite) && !altera.io.avs_waitrequest

  readCtxFifo.io.push.valid   := isRead && !altera.io.avs_waitrequest
  readCtxFifo.io.push.payload := io.bus.cmd.context

  writeCtxFifo.io.push.valid   := isWrite && !altera.io.avs_waitrequest
  writeCtxFifo.io.push.payload := io.bus.cmd.context

  // ==========================================================================
  // Response side: Avalon -> SdramCtrlBus.rsp
  // ==========================================================================

  // CAPTURE, unconditionally, on the one cycle Avalon offers the data. The
  // command side above guarantees there is room, so this push cannot be refused.
  readRspFifo.io.push.valid           := altera.io.avs_readdatavalid
  readRspFifo.io.push.payload.data    := altera.io.avs_readdata
  readRspFifo.io.push.payload.context := readCtxFifo.io.pop.payload
  readCtxFifo.io.pop.ready            := altera.io.avs_readdatavalid

  // RESPONSES MUST COME BACK IN COMMAND ORDER.
  //
  // The consumer matches responses to commands by ORDER, not by type or by any
  // tag it checks -- BmbMemoryController simply waits for the next
  // `io.bmb.rsp.fire` in whatever state it is in. But the two response sources
  // here have completely different latencies: a write response is manufactured
  // locally and is ready the moment the write is accepted, while a read
  // response has to wait for the SDRAM to return data.
  //
  // Emitting whichever is available therefore let a write response OVERTAKE an
  // outstanding read, and since a write response carries `data := 0` the read
  // came back as 0. That is the second half of the array-length bug: buffering
  // the read data (above) stopped data being dropped, and a plain rdMem stopped
  // failing, but a read with a write behind it could still be answered by the
  // write.
  //
  // `orderFifo` records the type of every accepted command and responses are
  // released strictly in that order.
  orderFifo.io.push.valid   := io.bus.cmd.fire
  orderFifo.io.push.payload := !io.bus.cmd.write

  val headIsRead  = orderFifo.io.pop.payload
  val headValid   = orderFifo.io.pop.valid
  val readAtHead  = headValid &&  headIsRead
  val writeAtHead = headValid && !headIsRead

  when(readAtHead) {
    io.bus.rsp.valid   := readRspFifo.io.pop.valid
    io.bus.rsp.data    := readRspFifo.io.pop.payload.data
    io.bus.rsp.context := readRspFifo.io.pop.payload.context
    readRspFifo.io.pop.ready  := io.bus.rsp.ready && readRspFifo.io.pop.valid
    writeCtxFifo.io.pop.ready := False
  }.elsewhen(writeAtHead) {
    io.bus.rsp.valid   := writeCtxFifo.io.pop.valid
    io.bus.rsp.data    := 0
    io.bus.rsp.context := writeCtxFifo.io.pop.payload
    readRspFifo.io.pop.ready  := False
    writeCtxFifo.io.pop.ready := io.bus.rsp.ready && writeCtxFifo.io.pop.valid
  }.otherwise {
    io.bus.rsp.valid   := False
    io.bus.rsp.data    := 0
    io.bus.rsp.context := readRspFifo.io.pop.payload.context
    readRspFifo.io.pop.ready  := False
    writeCtxFifo.io.pop.ready := False
  }
  orderFifo.io.pop.ready := io.bus.rsp.valid && io.bus.rsp.ready

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
