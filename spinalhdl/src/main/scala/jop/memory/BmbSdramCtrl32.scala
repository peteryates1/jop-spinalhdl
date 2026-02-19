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
  case class SdramContext() extends Bundle {
    val source = UInt(bmbParameter.access.sourceWidth bits)
    val context = Bits(bmbParameter.access.contextWidth bits)
    val isHigh = Bool()
  }

  val ctrlBus: SdramCtrlBus[SdramContext] = if (!useAlteraCtrl) {
    val ctrl = SdramCtrl(layout, timing, CAS, SdramContext(), produceRspOnWrite = true)
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

  // Detect burst read (length > 3 means more than one 32-bit word)
  val isBurstRead = io.bmb.cmd.valid && !io.bmb.cmd.isWrite &&
                    io.bmb.cmd.fragment.length > 3

  when(burstActive) {
    // Burst mode: issue SDRAM read commands from latched state
    io.bmb.cmd.ready := False
    ctrlBus.cmd.write := False
    ctrlBus.cmd.data := 0
    ctrlBus.cmd.mask := 0
    ctrlBus.cmd.context.source := burstSource
    ctrlBus.cmd.context.context := burstContext

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

  val lowHalfData = Reg(Bits(16 bits))

  when(ctrlBus.rsp.fire && !ctrlBus.rsp.context.isHigh) {
    lowHalfData := ctrlBus.rsp.data
  }

  // Forward to BMB only on high-half response (both halves available)
  io.bmb.rsp.valid := ctrlBus.rsp.valid && ctrlBus.rsp.context.isHigh
  io.bmb.rsp.setSuccess()
  io.bmb.rsp.source := ctrlBus.rsp.context.source
  io.bmb.rsp.context := ctrlBus.rsp.context.context
  io.bmb.rsp.data := ctrlBus.rsp.data ## lowHalfData  // {high16, low16}

  // rsp.last: burst-aware
  when(burstActive) {
    io.bmb.rsp.last := (burstWordsSent + 1 >= burstWordTotal)
    when(io.bmb.rsp.fire) {
      burstWordsSent := burstWordsSent + 1
      when(burstWordsSent + 1 >= burstWordTotal) {
        burstActive := False
      }
    }
  }.otherwise {
    io.bmb.rsp.last := True  // single-word: always last
  }

  // Accept low-half responses immediately (just buffer them);
  // accept high-half responses only when BMB rsp is consumed
  ctrlBus.rsp.ready := Mux(
    ctrlBus.rsp.context.isHigh,
    io.bmb.rsp.ready,
    True
  )

  // Debug outputs
  io.debug.sendingHigh   := sendingHigh
  io.debug.burstActive   := burstActive
  io.debug.ctrlCmdValid  := ctrlBus.cmd.valid
  io.debug.ctrlCmdReady  := ctrlBus.cmd.ready
  io.debug.ctrlCmdWrite  := ctrlBus.cmd.write
  io.debug.ctrlRspValid  := ctrlBus.rsp.valid
  io.debug.ctrlRspIsHigh := ctrlBus.rsp.context.isHigh
  io.debug.lowHalfData   := lowHalfData

}
