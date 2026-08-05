package jop.memory

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import spinal.lib.memory.sdram._
import spinal.lib.memory.sdram.sdr._

/**
 * 32-bit BMB to **32-bit** SDRAM bridge — one SDRAM operation per BMB beat.
 *
 * The sibling of [[BmbSdramCtrl32]], which bridges a 32-bit BMB onto a *16-bit*
 * SDRAM and therefore issues two SDRAM operations per BMB transaction, splitting
 * and reassembling halves. Here the buses are the same width, so the mapping is
 * 1:1 and all of that machinery disappears: no `sendingHigh`, no `isHigh`
 * context, no half-assembly register, and burst counts are word counts rather
 * than doubled.
 *
 * Written as a separate component rather than as a width parameter on
 * BmbSdramCtrl32. That component is hardware-validated on three boards and its
 * command/response FSM is subtle (the CKE gating and stale-response hazards are
 * commented at length there); threading a width switch through every state would
 * put those boards at risk to save a file. The 1:1 case is small enough to state
 * plainly instead.
 *
 * Built for the Colorlight i5's EM638325BK-6H. On that board CKE is tied to VCC,
 * CS to GND and all four DQM to GND, so:
 *   - `SdramCtrlNoCke` is used (as elsewhere), CKE simply going unconnected;
 *   - **byte masking does not physically exist on that board**. This component
 *     still drives `io.bmb.cmd.mask` onto `DQM`, so it remains correct on a
 *     board that does wire DQM (and `BmbSdramCtrlWideTest` checks that against
 *     an SdramModel, which honours DQM). But on the i5 those pins are strapped,
 *     so a sub-word write would silently write all four bytes.
 *
 *     That is safe only because JOP never issues one: every write path —
 *     `BmbMemoryController:317`, `StackCacheDma`, `DebugController` — drives
 *     mask := B"1111" unconditionally. This is a property of JOP, not of this
 *     bridge, and it cannot be checked at elaboration because the mask is a
 *     runtime value. If a narrower write path is ever added to JOP, this board
 *     is the one that breaks, and it will break silently.
 *
 * @param bmbParameter BMB interface parameters (must be 32-bit data width)
 * @param layout       SDRAM layout (must be 32-bit data width)
 * @param timing       SDRAM timing parameters
 * @param CAS          CAS latency
 */
case class BmbSdramCtrlWide(
  bmbParameter: BmbParameter,
  layout: SdramLayout,
  timing: SdramTimings,
  CAS: Int
) extends Component with SdramBridge {
  assert(bmbParameter.access.dataWidth == 32, "BMB data width must be 32")
  assert(layout.dataWidth == 32,
    s"SDRAM data width must be 32 for BmbSdramCtrlWide (got ${layout.dataWidth}); " +
    "use BmbSdramCtrl32 for a 16-bit SDRAM")

  val io = new Bundle {
    val bmb = slave(Bmb(bmbParameter))
    val sdram = master(SdramInterface(layout))
    // Block-fill (memset) sideband: controller requests a fast zero of a word
    // range; we stream writes into the SDRAM controller at full speed. Word
    // address width = BMB byte-address width - 2.
    val fill = slave(MemFill(bmbParameter.access.addressWidth - 2))
    val debug = out(new Bundle {
      val burstActive  = Bool()
      val fillActive   = Bool()
      val ctrlCmdValid = Bool()
      val ctrlCmdReady = Bool()
      val ctrlCmdWrite = Bool()
      val ctrlRspValid = Bool()
    })
  }

  /** Context carried through the SdramCtrl pipeline.
    *
    * `isBurst` marks burst read responses so they are not confused with a stale
    * single-word response still in the CAS pipeline when a burst starts —
    * the same hazard BmbSdramCtrl32 guards. `isFill` marks block-fill writes,
    * whose responses are swallowed and counted rather than forwarded to BMB. */
  case class SdramContext() extends Bundle {
    val source = UInt(bmbParameter.access.sourceWidth bits)
    val context = Bits(bmbParameter.access.contextWidth bits)
    val isBurst = Bool()
    val isFill = Bool()
  }

  // produceRspOnWrite: BMB requires a response for writes as well as reads, and
  // with a 1:1 mapping each SDRAM response is exactly one BMB response.
  val ctrl = SdramCtrlNoCke(layout, timing, CAS, SdramContext(), produceRspOnWrite = true)
  io.sdram <> ctrl.io.sdram
  val ctrlBus = ctrl.io.bus
  val rsp = ctrlBus.rsp

  // ==========================================================================
  // Command side
  // ==========================================================================

  // SDRAM word address = BMB byte address >> 2 (32-bit words)
  val sdramWordAddr = (io.bmb.cmd.address >> log2Up(layout.bytePerWord))
    .resize(layout.wordAddressWidth)

  // --- Burst read state (multi-word BMB reads) ---
  val lw = bmbParameter.access.sources.values.head.lengthWidth
  val burstActive = RegInit(False)
  val burstBaseAddr = Reg(UInt(layout.wordAddressWidth bits))
  val burstCmdIdx = Reg(UInt(lw bits))      // SDRAM cmds issued so far
  val burstWordTotal = Reg(UInt(lw bits))   // total words (== total SDRAM cmds)
  val burstWordsSent = Reg(UInt(lw bits))   // words forwarded to BMB so far
  val burstSource = Reg(UInt(bmbParameter.access.sourceWidth bits))
  val burstContext = Reg(Bits(bmbParameter.access.contextWidth bits))

  // --- Block-fill state ---
  // One SDRAM write per 32-bit word, so the fill range needs no scaling — unlike
  // the 16-bit bridge, where each word became two writes at [2w, 2w+1].
  val fw = layout.wordAddressWidth
  val fillActive = RegInit(False)
  val fillAddr = Reg(UInt(fw bits)) init (0)         // next word to write
  val fillEnd = Reg(UInt(fw + 1 bits)) init (0)      // exclusive end
  val fillTotal = Reg(UInt(fw + 1 bits)) init (0)    // total writes issued
  val fillRspRcvd = Reg(UInt(fw + 1 bits)) init (0)  // responses received
  val fillValueReg = Reg(Bits(32 bits)) init (0)

  io.fill.busy := fillActive

  // Launch on request pulse when idle. An empty range (start == end) still
  // enters fillActive and completes immediately (fillTotal == 0 => done next
  // cycle), so the controller's busy handshake always terminates.
  when(!fillActive && io.fill.cmd) {
    // Guard an inverted/empty range (end <= start): the unsigned subtraction
    // would otherwise underflow and the fill would run essentially forever.
    val total = Mux(io.fill.end > io.fill.start,
      (io.fill.end - io.fill.start).resize(fw + 1), U(0, fw + 1 bits))
    fillActive := True
    fillAddr := io.fill.start.resize(fw)
    fillEnd := io.fill.end.resize(fw + 1)
    fillTotal := total
    fillRspRcvd := 0
    fillValueReg := io.fill.value
  }
  // Complete only when every issued write has been acknowledged, so the range is
  // committed before the controller resumes.
  when(fillActive && fillRspRcvd === fillTotal) {
    fillActive := False
  }

  // A BMB length is in bytes and encodes length-1, so > 3 means more than one
  // 32-bit word.
  val isBurstRead = io.bmb.cmd.valid && !io.bmb.cmd.isWrite &&
                    io.bmb.cmd.fragment.length > 3

  when(fillActive) {
    // Stream write commands into the SDRAM controller at full speed.
    io.bmb.cmd.ready := False
    ctrlBus.cmd.write := True
    ctrlBus.cmd.data := fillValueReg
    ctrlBus.cmd.mask := B(layout.bytePerWord bits, default -> True)
    ctrlBus.cmd.context.source := 0
    ctrlBus.cmd.context.context := 0
    ctrlBus.cmd.context.isBurst := False
    ctrlBus.cmd.context.isFill := True
    when(fillAddr =/= fillEnd) {
      ctrlBus.cmd.valid := True
      ctrlBus.cmd.address := fillAddr
      when(ctrlBus.cmd.fire) {
        fillAddr := fillAddr + 1
      }
    } otherwise {
      // All writes issued; wait for responses to drain (fillActive clears above).
      ctrlBus.cmd.valid := False
      ctrlBus.cmd.address := 0
    }

  } elsewhen (burstActive) {
    // Issue one SDRAM read per word from latched state.
    io.bmb.cmd.ready := False
    ctrlBus.cmd.write := False
    ctrlBus.cmd.data := 0
    ctrlBus.cmd.mask := 0
    ctrlBus.cmd.context.source := burstSource
    ctrlBus.cmd.context.context := burstContext
    ctrlBus.cmd.context.isBurst := True
    ctrlBus.cmd.context.isFill := False

    when(burstCmdIdx < burstWordTotal) {
      ctrlBus.cmd.valid := True
      ctrlBus.cmd.address := burstBaseAddr + burstCmdIdx
      when(ctrlBus.cmd.fire) {
        burstCmdIdx := burstCmdIdx + 1
      }
    } otherwise {
      // All commands issued — wait for responses to drain.
      ctrlBus.cmd.valid := False
      ctrlBus.cmd.address := 0
    }

  } elsewhen (isBurstRead) {
    // Accept the burst BMB command, latch its parameters, start next cycle.
    io.bmb.cmd.ready := True
    ctrlBus.cmd.valid := False
    ctrlBus.cmd.write := False
    ctrlBus.cmd.address := 0
    ctrlBus.cmd.data := 0
    ctrlBus.cmd.mask := 0
    ctrlBus.cmd.context.source := io.bmb.cmd.source
    ctrlBus.cmd.context.context := io.bmb.cmd.context
    ctrlBus.cmd.context.isBurst := False
    ctrlBus.cmd.context.isFill := False

    burstActive := True
    burstBaseAddr := sdramWordAddr
    burstCmdIdx := 0
    val totalBytes = io.bmb.cmd.fragment.length +^ 1  // +^ expands by one bit
    burstWordTotal := (totalBytes >> 2).resized
    burstWordsSent := 0
    burstSource := io.bmb.cmd.source
    burstContext := io.bmb.cmd.context

  } otherwise {
    // Single word: straight through, one SDRAM command per BMB command.
    ctrlBus.cmd.valid := io.bmb.cmd.valid
    ctrlBus.cmd.write := io.bmb.cmd.isWrite
    ctrlBus.cmd.address := sdramWordAddr
    ctrlBus.cmd.data := io.bmb.cmd.data
    ctrlBus.cmd.mask := io.bmb.cmd.mask
    ctrlBus.cmd.context.source := io.bmb.cmd.source
    ctrlBus.cmd.context.context := io.bmb.cmd.context
    ctrlBus.cmd.context.isBurst := False
    ctrlBus.cmd.context.isFill := False
    io.bmb.cmd.ready := ctrlBus.cmd.ready
  }

  // ==========================================================================
  // Response side
  // ==========================================================================
  //
  // A pipeline register sits between the SDRAM response and the BMB response
  // bus. BmbSdramCtrl32 needs one because its response-assembly path is long;
  // this one is kept for a different reason: the SDRAM read data arrives
  // straight off the DQ input registers, and driving the BMB response bus
  // combinationally from there makes the cache/controller fabric part of the
  // same path. One cycle of read latency is cheap next to an SDRAM access.
  // ==========================================================================

  val pipeValid = RegInit(False)
  val pipeData = Reg(Bits(32 bits))
  val pipeSource = Reg(UInt(bmbParameter.access.sourceWidth bits))
  val pipeContext = Reg(Bits(bmbParameter.access.contextWidth bits))
  val pipeIsBurst = Reg(Bool()) init (False)

  // Fill responses are swallowed and counted, never forwarded to BMB.
  when(rsp.fire && rsp.context.isFill) {
    fillRspRcvd := fillRspRcvd + 1
  }

  val rspFire = rsp.valid && !rsp.context.isFill
  when(rspFire && (!pipeValid || io.bmb.rsp.fire)) {
    // Capture when the pipe is empty, or is being consumed this cycle.
    pipeValid := True
    pipeData := rsp.data
    pipeSource := rsp.context.source
    pipeContext := rsp.context.context
    pipeIsBurst := rsp.context.isBurst
  } elsewhen (io.bmb.rsp.fire && !rspFire) {
    pipeValid := False
  }

  io.bmb.rsp.valid := pipeValid
  io.bmb.rsp.setSuccess()
  io.bmb.rsp.source := pipeSource
  io.bmb.rsp.context := pipeContext
  io.bmb.rsp.data := pipeData

  // rsp.last is burst-aware and gated by isBurst, so a stale single-word
  // response still in the CAS pipeline when a burst starts is not miscounted as
  // a burst beat.
  when(burstActive && pipeIsBurst) {
    io.bmb.rsp.last := (burstWordsSent + 1 >= burstWordTotal)
    when(io.bmb.rsp.fire) {
      burstWordsSent := burstWordsSent + 1
      when(burstWordsSent + 1 >= burstWordTotal) {
        burstActive := False
      }
    }
  } otherwise {
    io.bmb.rsp.last := True  // single word, or a stale non-burst response
  }

  // Fill responses are always swallowed; real responses are accepted when the
  // pipe is empty or is being consumed.
  rsp.ready := Mux(rsp.context.isFill, True, !pipeValid || io.bmb.rsp.ready)

  def bmbPort = io.bmb
  def sdramPort = io.sdram
  def fillPort = io.fill

  io.debug.burstActive  := burstActive
  io.debug.fillActive   := fillActive
  io.debug.ctrlCmdValid := ctrlBus.cmd.valid
  io.debug.ctrlCmdReady := ctrlBus.cmd.ready
  io.debug.ctrlCmdWrite := ctrlBus.cmd.write
  io.debug.ctrlRspValid := rsp.valid
}
