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
  CAS: Int,
  useAlteraCtrl: Boolean = false,
  clockFreqHz: Long = 100000000L
) extends Component {
  assert(bmbParameter.access.dataWidth == 32, "BMB data width must be 32")
  assert(layout.dataWidth == 16, "SDRAM data width must be 16")

  val io = new Bundle {
    val bmb = slave(Bmb(bmbParameter))
    val sdram = master(SdramInterface(layout))
    // Block-fill (memset) sideband: controller requests a fast zero of a word
    // range; we stream writes into the SDRAM controller at full speed. Word
    // address width = BMB byte-address width - 2.
    val fill = slave(MemFill(bmbParameter.access.addressWidth - 2))
    val debug = out(new Bundle {
      val sendingHigh   = Bool()
      val burstActive   = Bool()
      val ctrlCmdValid  = Bool()
      val ctrlCmdReady  = Bool()
      val ctrlCmdWrite  = Bool()
      val ctrlRspValid  = Bool()
      val ctrlRspIsHigh = Bool()
      val lowHalfData   = Bits(16 bits)
    })
  }

  // Context carries BMB transaction info through SdramCtrl pipeline.
  // isHigh distinguishes the two SDRAM ops per 32-bit transaction.
  // isBurst marks burst read responses so they aren't confused with
  // stale single-word responses still in the CAS pipeline.
  case class SdramContext() extends Bundle {
    val source = UInt(bmbParameter.access.sourceWidth bits)
    val context = Bits(bmbParameter.access.contextWidth bits)
    val isHigh = Bool()
    val isBurst = Bool()
    val isFill = Bool()   // fill-DMA write: response is swallowed + counted, not sent to BMB
  }

  val ctrlBus: SdramCtrlBus[SdramContext] = if (!useAlteraCtrl) {
    // Use local SdramCtrlNoCke to avoid CKE gating bug in SpinalHDL's SdramCtrl.
    // The library controller gates CKE when rsp.ready is low with reads in-flight,
    // but the 2-cycle remoteCke delay creates a window where commands are issued
    // while the SDRAM ignores them (CKE=0), causing data shifts.
    val ctrl = SdramCtrlNoCke(layout, timing, CAS, SdramContext(), produceRspOnWrite = true)
    io.sdram <> ctrl.io.sdram
    ctrl.io.bus
  } else {
    val periodNs = 1e9 / clockFreqHz
    def toCycles(t: TimeNumber): Int = scala.math.ceil(t.toDouble / (1.0 / clockFreqHz)).toInt.max(1)
    val refreshPeriod = ((timing.tREF.toDouble * clockFreqHz) / (1 << layout.rowWidth)).toInt

    val alteraCfg = AlteraSdramConfig(
      numChipSelects = 1,
      sdramBankWidth = layout.bankWidth,
      sdramRowWidth  = layout.rowWidth,
      sdramColWidth  = layout.columnWidth,
      sdramDataWidth = layout.dataWidth,
      casLatency     = CAS,
      initRefresh    = timing.bootRefreshCount,
      refreshPeriod  = refreshPeriod,
      powerupDelay   = toCycles(timing.tPOW),
      tRFC           = toCycles(timing.tRFC),
      tRP            = toCycles(timing.tRP),
      tRCD           = toCycles(timing.tRCD),
      tWR            = scala.math.max(toCycles(timing.tWR), timing.cWR + 1),
      maxRecTime     = 1
    )

    val adapter = AlteraSdramAdapter(layout, alteraCfg, SdramContext())
    io.sdram <> adapter.io.sdram
    adapter.io.bus
  }

  val rsp = ctrlBus.rsp

  // ==========================================================================
  // Command side: split 32-bit BMB into two 16-bit SDRAM commands
  // ==========================================================================

  val sendingHigh = RegInit(False)

  // SDRAM word address = BMB byte address >> 1 (for 16-bit SDRAM)
  val sdramWordAddr = (io.bmb.cmd.address >> log2Up(layout.bytePerWord)).resize(layout.wordAddressWidth)

  // --------------------------------------------------------------------------
  // Burst read state: handles multi-word BMB reads (length > 3)
  // --------------------------------------------------------------------------

  val lw = bmbParameter.access.sources.values.head.lengthWidth
  val burstActive = RegInit(False)
  val burstBaseAddr = Reg(UInt(layout.wordAddressWidth bits))
  val burstCmdIdx = Reg(UInt(lw bits))       // current SDRAM cmd index (0..2*N-1)
  val burstCmdTotal = Reg(UInt(lw bits))     // total SDRAM cmds = 2 * wordCount
  val burstWordTotal = Reg(UInt(lw bits))    // total 32-bit words
  val burstWordsSent = Reg(UInt(lw bits))    // 32-bit words sent to BMB so far
  val burstSource = Reg(UInt(bmbParameter.access.sourceWidth bits))
  val burstContext = Reg(Bits(bmbParameter.access.contextWidth bits))

  // --------------------------------------------------------------------------
  // Block-fill state: stream 16-bit writes to zero a word range at full speed.
  // A 32-bit word w maps to SDRAM 16-bit words [2w, 2w+1]. We issue writes for
  // [start<<1, end<<1) and hold `busy` until every write has been acknowledged
  // (response received), so the range is committed before the controller resumes.
  // --------------------------------------------------------------------------
  val fw = layout.wordAddressWidth
  val fillActive  = RegInit(False)
  val fillAddr16  = Reg(UInt(fw bits)) init(0)        // next 16-bit SDRAM word to write
  val fillEnd16   = Reg(UInt(fw + 1 bits)) init(0)    // exclusive end (16-bit words)
  val fillTotal   = Reg(UInt(fw + 1 bits)) init(0)    // total 16-bit writes
  val fillRspRcvd = Reg(UInt(fw + 1 bits)) init(0)    // fill responses received
  val fillValueReg = Reg(Bits(32 bits)) init(0)

  io.fill.busy := fillActive

  // Launch on request pulse when idle. An empty range (start==end) still enters
  // fillActive and completes immediately (fillTotal==0 => done next cycle), so
  // the controller's busy handshake always terminates instead of hanging.
  when(!fillActive && io.fill.cmd) {
    val start16 = (io.fill.start << 1).resize(fw)
    val total16 = ((io.fill.end - io.fill.start) << 1).resize(fw + 1)
    fillActive   := True
    fillAddr16   := start16
    fillEnd16    := (io.fill.end << 1).resize(fw + 1)
    fillTotal    := total16
    fillRspRcvd  := 0
    fillValueReg := io.fill.value
  }
  // Complete when all issued writes have been acknowledged.
  when(fillActive && fillRspRcvd === fillTotal) {
    fillActive := False
  }

  // Detect burst read (length > 3 means more than one 32-bit word)
  val isBurstRead = io.bmb.cmd.valid && !io.bmb.cmd.isWrite &&
                    io.bmb.cmd.fragment.length > 3

  when(fillActive) {
    // Block-fill: stream write commands into the SDRAM controller at full speed.
    io.bmb.cmd.ready := False
    ctrlBus.cmd.write := True
    ctrlBus.cmd.context.source := 0
    ctrlBus.cmd.context.context := 0
    ctrlBus.cmd.context.isBurst := False
    ctrlBus.cmd.context.isFill := True
    when(fillAddr16 =/= fillEnd16) {
      ctrlBus.cmd.valid := True
      ctrlBus.cmd.address := fillAddr16
      ctrlBus.cmd.data := Mux(fillAddr16(0), fillValueReg(31 downto 16), fillValueReg(15 downto 0))
      ctrlBus.cmd.mask := B"11"
      ctrlBus.cmd.context.isHigh := fillAddr16(0)
      when(ctrlBus.cmd.fire) {
        fillAddr16 := fillAddr16 + 1
      }
    }.otherwise {
      // All writes issued; wait for responses to drain (fillActive clears above).
      ctrlBus.cmd.valid := False
      ctrlBus.cmd.address := 0
      ctrlBus.cmd.data := 0
      ctrlBus.cmd.mask := 0
      ctrlBus.cmd.context.isHigh := False
    }

  }.elsewhen(burstActive) {
    // Burst mode: issue SDRAM read commands from latched state
    io.bmb.cmd.ready := False
    ctrlBus.cmd.write := False
    ctrlBus.cmd.data := 0
    ctrlBus.cmd.mask := 0
    ctrlBus.cmd.context.source := burstSource
    ctrlBus.cmd.context.context := burstContext

    ctrlBus.cmd.context.isBurst := True
    ctrlBus.cmd.context.isFill := False

    when(burstCmdIdx < burstCmdTotal) {
      ctrlBus.cmd.valid := True
      ctrlBus.cmd.address := burstBaseAddr + burstCmdIdx
      ctrlBus.cmd.context.isHigh := burstCmdIdx(0)
      when(ctrlBus.cmd.fire) {
        burstCmdIdx := burstCmdIdx + 1
      }
    }.otherwise {
      // All SDRAM cmds issued — wait for responses to drain
      ctrlBus.cmd.valid := False
      ctrlBus.cmd.address := 0
      ctrlBus.cmd.context.isHigh := False
    }

  }.elsewhen(isBurstRead) {
    // Accept burst BMB cmd, latch parameters, start burst next cycle
    io.bmb.cmd.ready := True
    ctrlBus.cmd.valid := False
    ctrlBus.cmd.write := False
    ctrlBus.cmd.address := 0
    ctrlBus.cmd.data := 0
    ctrlBus.cmd.mask := 0
    ctrlBus.cmd.context.source := io.bmb.cmd.source
    ctrlBus.cmd.context.context := io.bmb.cmd.context
    ctrlBus.cmd.context.isHigh := False
    ctrlBus.cmd.context.isBurst := False
    ctrlBus.cmd.context.isFill := False

    burstActive := True
    burstBaseAddr := sdramWordAddr
    burstCmdIdx := 0
    val totalBytes = io.bmb.cmd.fragment.length +^ 1  // +^ expands by 1 bit
    burstCmdTotal := (totalBytes >> 1).resized         // 16-bit SDRAM reads
    burstWordTotal := (totalBytes >> 2).resized        // 32-bit BMB words
    burstWordsSent := 0
    burstSource := io.bmb.cmd.source
    burstContext := io.bmb.cmd.context

  }.otherwise {
    // Single-word path (existing logic unchanged)
    ctrlBus.cmd.valid := io.bmb.cmd.valid
    ctrlBus.cmd.write := io.bmb.cmd.isWrite
    ctrlBus.cmd.context.source := io.bmb.cmd.source
    ctrlBus.cmd.context.context := io.bmb.cmd.context
    ctrlBus.cmd.context.isBurst := False
    ctrlBus.cmd.context.isFill := False

    when(!sendingHigh) {
      // Low half: even SDRAM word
      ctrlBus.cmd.address := sdramWordAddr
      ctrlBus.cmd.data := io.bmb.cmd.data(15 downto 0)
      ctrlBus.cmd.mask := io.bmb.cmd.mask(1 downto 0)
      ctrlBus.cmd.context.isHigh := False
    } otherwise {
      // High half: odd SDRAM word (address + 1)
      ctrlBus.cmd.address := sdramWordAddr + 1
      ctrlBus.cmd.data := io.bmb.cmd.data(31 downto 16)
      ctrlBus.cmd.mask := io.bmb.cmd.mask(3 downto 2)
      ctrlBus.cmd.context.isHigh := True
    }

    // Accept BMB command only after both halves sent
    io.bmb.cmd.ready := ctrlBus.cmd.ready && sendingHigh

    when(ctrlBus.cmd.fire) {
      sendingHigh := !sendingHigh
    }
  }

  // ==========================================================================
  // Response side: collect two 16-bit responses into one 32-bit response
  // ==========================================================================
  //
  // Pipeline register between SDRAM response and BMB response bus.
  // The Altera SDRAM controller's output (za_valid/avs_readdata) has a long
  // combinational path through the response assembly.  Without pipelining,
  // any register capturing BMB response data sees -2.7ns setup violation at
  // 80 MHz.  The pipeline register adds 1 cycle latency to all reads.
  // ==========================================================================

  val lowHalfData = Reg(Bits(16 bits))

  when(rsp.fire && !rsp.context.isHigh && !rsp.context.isFill) {
    lowHalfData := rsp.data
  }

  // Fill responses are swallowed (never assembled into a BMB response) and
  // counted so the fill can signal completion once all writes are committed.
  when(rsp.fire && rsp.context.isFill) {
    fillRspRcvd := fillRspRcvd + 1
  }

  // --- Pipeline stage: register assembled 32-bit response ---
  val pipeValid = RegInit(False)
  val pipeData  = Reg(Bits(32 bits))
  val pipeSource = Reg(UInt(bmbParameter.access.sourceWidth bits))
  val pipeContext = Reg(Bits(bmbParameter.access.contextWidth bits))
  val pipeIsBurst = Reg(Bool()) init(False)

  // Capture into pipeline on high-half response from SDRAM controller
  val highHalfFire = rsp.valid && rsp.context.isHigh && !rsp.context.isFill
  when(highHalfFire && (!pipeValid || io.bmb.rsp.fire)) {
    // Capture when pipe is empty OR pipe is being consumed this cycle
    pipeValid := True
    pipeData := rsp.data ## lowHalfData
    pipeSource := rsp.context.source
    pipeContext := rsp.context.context
    pipeIsBurst := rsp.context.isBurst
  }.elsewhen(io.bmb.rsp.fire && !highHalfFire) {
    // Pipe consumed but no new data — clear valid
    pipeValid := False
  }

  // Forward pipelined response to BMB
  io.bmb.rsp.valid := pipeValid
  io.bmb.rsp.setSuccess()
  io.bmb.rsp.source := pipeSource
  io.bmb.rsp.context := pipeContext
  io.bmb.rsp.data := pipeData

  // rsp.last: burst-aware, gated by isBurst context to avoid counting
  // stale single-word responses still in the CAS pipeline when a burst starts.
  when(burstActive && pipeIsBurst) {
    io.bmb.rsp.last := (burstWordsSent + 1 >= burstWordTotal)
    when(io.bmb.rsp.fire) {
      burstWordsSent := burstWordsSent + 1
      when(burstWordsSent + 1 >= burstWordTotal) {
        burstActive := False
      }
    }
  }.otherwise {
    io.bmb.rsp.last := True  // single-word or stale non-burst: always last
  }

  // Accept low-half responses immediately (just buffer them);
  // accept high-half responses when pipe is empty or being consumed
  rsp.ready := Mux(
    rsp.context.isFill,
    True,                                  // fill responses: always swallow
    Mux(
      rsp.context.isHigh,
      !pipeValid || io.bmb.rsp.ready,
      True
    )
  )

  // Debug outputs
  io.debug.sendingHigh   := sendingHigh
  io.debug.burstActive   := burstActive
  io.debug.ctrlCmdValid  := ctrlBus.cmd.valid
  io.debug.ctrlCmdReady  := ctrlBus.cmd.ready
  io.debug.ctrlCmdWrite  := ctrlBus.cmd.write
  io.debug.ctrlRspValid  := rsp.valid
  io.debug.ctrlRspIsHigh := rsp.context.isHigh
  io.debug.lowHalfData   := lowHalfData

}
