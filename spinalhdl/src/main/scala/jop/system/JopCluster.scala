package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.io.CmpSync
import jop.debug._

/**
 * JOP Cluster: N JopCores with shared bus arbitration and synchronization.
 *
 * Encapsulates the common SMP wiring that was duplicated across FPGA top-levels:
 *   - Core instantiation with per-core cpuId/cpuCnt/hasUart override
 *   - BMB parameter calculation (single-core direct vs. arbiter output)
 *   - Single-core path: direct BMB, sync tie-offs
 *   - SMP path: BmbArbiter + CmpSync
 *   - Common tie-offs: debugRamAddr, debugHalt
 *   - UART routing: core 0 TXD/RXD to io; cores 1+ RXD tied to True
 *   - Optional debug subsystem (DebugController + DebugProtocol + DebugUart)
 *
 * All per-core signals are routed through io as Vecs to avoid hierarchy
 * violations when top-levels or test harnesses read them. Unused outputs
 * are pruned by SpinalHDL during Verilog generation.
 *
 * For simulation, cores and cmpSync are public vals for simPublic() access.
 *
 * @param cpuCnt      Number of CPU cores (1 = single-core, 2+ = SMP)
 * @param baseConfig  Base JopCoreConfig (cpuId/cpuCnt/hasUart overridden per core)
 * @param debugConfig Optional debug subsystem configuration (None = no debug, zero cost)
 * @param romInit     Optional microcode ROM initialization data
 * @param ramInit     Optional stack RAM initialization data
 * @param jbcInit     Optional JBC RAM initialization data
 */
case class JopCluster(
  cpuCnt: Int,
  baseConfig: JopCoreConfig,
  debugConfig: Option[DebugConfig] = None,
  romInit: Option[Seq[BigInt]] = None,
  ramInit: Option[Seq[BigInt]] = None,
  jbcInit: Option[Seq[BigInt]] = None
) extends Component {
  require(cpuCnt >= 1, "cpuCnt must be at least 1")

  // Number of BMB inputs: cores + optional debug master
  val hasDebugMem = debugConfig.exists(_.hasMemAccess)
  val totalBmbInputs = cpuCnt + (if (hasDebugMem) 1 else 0)

  // BMB parameter: passthrough for single-core (no debug mem), arbiter output otherwise
  val inputParam = baseConfig.memConfig.bmbParameter
  val needsArbiter = totalBmbInputs > 1

  val bmbParameter: BmbParameter = if (!needsArbiter) {
    inputParam
  } else {
    val sourceRouteWidth = log2Up(totalBmbInputs)
    val outputSourceCount = 1 << sourceRouteWidth
    val inputSourceParam = inputParam.access.sources.values.head
    BmbParameter(
      access = BmbAccessParameter(
        addressWidth = inputParam.access.addressWidth,
        dataWidth = inputParam.access.dataWidth
      ).addSources(outputSourceCount, BmbSourceParameter(
        contextWidth = inputSourceParam.contextWidth,
        lengthWidth = inputSourceParam.lengthWidth,
        canWrite = true,
        canRead = true,
        alignment = BmbParameter.BurstAlignement.WORD
      )),
      invalidation = BmbInvalidationParameter()
    )
  }

  val io = new Bundle {
    val bmb = master(Bmb(bmbParameter))
    val txd = out Bool()
    val rxd = in Bool()
    val wd  = out Vec(Bits(32 bits), cpuCnt)

    // Per-core debug outputs (routed through to avoid hierarchy violations)
    val pc      = out Vec(UInt(baseConfig.pcWidth bits), cpuCnt)
    val jpc     = out Vec(UInt((baseConfig.jpcWidth + 1) bits), cpuCnt)
    val aout    = out Vec(Bits(baseConfig.dataWidth bits), cpuCnt)
    val bout    = out Vec(Bits(baseConfig.dataWidth bits), cpuCnt)
    val memBusy = out Vec(Bool(), cpuCnt)
    val halted  = out Vec(Bool(), cpuCnt)

    // Core 0 debug signals
    val debugMemState = out UInt(5 bits)
    val uartTxData    = out Bits(8 bits)
    val uartTxValid   = out Bool()
    val debugExc      = out Bool()

    // Debug transport byte interface (byte-stream abstraction point).
    // FPGA top-levels connect DebugUart to this; sim harnesses connect directly.
    val debugTransport = if (debugConfig.isDefined) Some(slave(DebugTransport())) else None
  }

  // ==================================================================
  // Instantiate N JOP Cores
  // ==================================================================

  val cores = (0 until cpuCnt).map { i =>
    val coreConfig = baseConfig.copy(
      cpuId = i,
      cpuCnt = cpuCnt,
      hasUart = (i == 0)
    )
    JopCore(
      config = coreConfig,
      romInit = romInit,
      ramInit = ramInit,
      jbcInit = jbcInit
    )
  }

  // ==================================================================
  // Memory Bus: direct (single-core, no debug mem) or arbitrated
  // ==================================================================

  val cmpSync: Option[CmpSync] = if (cpuCnt == 1 && !needsArbiter) {
    // Single-core, no arbiter needed: direct BMB connection
    io.bmb <> cores(0).io.bmb
    cores(0).io.syncIn.halted := False
    cores(0).io.syncIn.s_out  := False
    None
  } else if (cpuCnt == 1) {
    // Single-core but debug needs memory: 2-input arbiter (core + debug)
    // Arbiter is created below after debug subsystem instantiation.
    // Sync tie-offs for single-core:
    cores(0).io.syncIn.halted := False
    cores(0).io.syncIn.s_out  := False
    None
  } else {
    // SMP: CmpSync needed regardless of debug
    val sync = CmpSync(cpuCnt)
    for (i <- 0 until cpuCnt) {
      sync.io.syncIn(i) := cores(i).io.syncOut
      cores(i).io.syncIn := sync.io.syncOut(i)
    }
    Some(sync)
  }

  // ==================================================================
  // Debug Subsystem (optional)
  // ==================================================================

  val debugCtrl: Option[DebugController] = debugConfig.map { cfg =>
    val debugBmbParam = if (cfg.hasMemAccess) Some(inputParam) else None

    val ctrl = DebugController(
      config = cfg,
      cpuCnt = cpuCnt,
      pcWidth = baseConfig.pcWidth,
      jpcWidth = baseConfig.jpcWidth,
      dataWidth = baseConfig.dataWidth,
      ramWidth = baseConfig.ramWidth,
      addrWidth = baseConfig.memConfig.addressWidth,
      bmbParameter = debugBmbParam
    )

    val proto = DebugProtocol()

    // Wire protocol <-> controller
    proto.io.cmdValid     <> ctrl.io.cmdValid
    proto.io.cmdType      <> ctrl.io.cmdType
    proto.io.cmdCore      <> ctrl.io.cmdCore
    proto.io.cmdPayload   <> ctrl.io.cmdPayload
    proto.io.cmdPayloadLen <> ctrl.io.cmdPayloadLen
    proto.io.cmdReady     <> ctrl.io.cmdReady

    proto.io.streamByte   <> ctrl.io.streamByte
    proto.io.streamValid  <> ctrl.io.streamValid
    proto.io.streamReady  <> ctrl.io.streamReady

    ctrl.io.rspValid      <> proto.io.rspValid
    ctrl.io.rspType       <> proto.io.rspType
    ctrl.io.rspCore       <> proto.io.rspCore
    ctrl.io.rspPayload    <> proto.io.rspPayload
    ctrl.io.rspPayloadLen <> proto.io.rspPayloadLen
    ctrl.io.rspReady      <> proto.io.rspReady
    ctrl.io.txBusy        <> proto.io.txBusy

    // Configuration info
    ctrl.io.stackDepth := (1 << baseConfig.ramWidth)
    ctrl.io.memorySize := (baseConfig.memConfig.mainMemWords.toInt)

    // Wire per-core debug signals
    for (i <- 0 until cpuCnt) {
      // Debug halt
      cores(i).io.debugHalt := ctrl.io.debugHalt(i)

      // Core signals
      ctrl.io.coreSignals(i).pc        := cores(i).io.pc
      ctrl.io.coreSignals(i).jpc       := cores(i).io.jpc
      ctrl.io.coreSignals(i).aout      := cores(i).io.aout
      ctrl.io.coreSignals(i).bout      := cores(i).io.bout
      ctrl.io.coreSignals(i).sp        := cores(i).io.debugSp
      ctrl.io.coreSignals(i).vp        := cores(i).io.debugVp
      ctrl.io.coreSignals(i).ar        := cores(i).io.debugAr
      ctrl.io.coreSignals(i).flags     := cores(i).io.debugFlags
      ctrl.io.coreSignals(i).mulResult := cores(i).io.debugMulResult
      ctrl.io.coreSignals(i).addrReg   := cores(i).io.debugAddrReg
      ctrl.io.coreSignals(i).rdDataReg := cores(i).io.debugRdDataReg
      ctrl.io.coreSignals(i).instr     := cores(i).io.debugInstr
      ctrl.io.coreSignals(i).bcopd     := cores(i).io.debugBcopd
      ctrl.io.coreSignals(i).jfetch    := cores(i).io.jfetch
      ctrl.io.coreSignals(i).memBusy   := cores(i).io.memBusy

      // Stack RAM debug port
      cores(i).io.debugRamAddr := ctrl.io.debugRamAddr(i)
      ctrl.io.coreSignals(i).debugRamData := cores(i).io.debugRamData
    }

    // Breakpoints (per-core)
    if (cfg.numBreakpoints > 0) {
      val breakpoints = (0 until cpuCnt).map { i =>
        val bp = DebugBreakpoints(cfg.numBreakpoints, baseConfig.pcWidth, baseConfig.jpcWidth)
        bp.io.pc     := cores(i).io.pc
        bp.io.jpc    := cores(i).io.jpc
        bp.io.jfetch := cores(i).io.jfetch
        bp.io.halted := ctrl.io.debugHalt(i)

        // SET dispatch: route from controller when targeting this core
        val isTarget = ctrl.io.bpTargetCore === U(i, 8 bits)
        bp.io.setValid   := ctrl.io.bpSetValid && isTarget
        bp.io.setType    := ctrl.io.bpSetType
        bp.io.setAddr    := ctrl.io.bpSetAddr

        // CLEAR dispatch: route from controller when targeting this core
        bp.io.clearValid := ctrl.io.bpClearValid && isTarget
        bp.io.clearSlot  := ctrl.io.bpClearSlot

        // Query: no strobe needed, data is combinational
        bp.io.queryValid := False

        // Hit signals
        ctrl.io.bpHit(i) := bp.io.hit
        ctrl.io.bpHitSlot(i) := bp.io.hitSlot
        bp
      }

      // Mux breakpoint feedback from target core to controller
      val bpTarget = ctrl.io.bpTargetCore

      // Defaults
      ctrl.io.bpSetOk := False
      ctrl.io.bpSetSlot := 0
      ctrl.io.bpQueryCount := 0
      for (s <- 0 until cfg.numBreakpoints) {
        ctrl.io.bpQuerySlotData(s) := 0
        ctrl.io.bpQuerySlotEnabled(s) := False
      }

      // Mux from target core
      for (i <- 0 until cpuCnt) {
        when(bpTarget === U(i, 8 bits)) {
          ctrl.io.bpSetOk := breakpoints(i).io.setOk
          ctrl.io.bpSetSlot := breakpoints(i).io.setSlot
          ctrl.io.bpQueryCount := breakpoints(i).io.queryCount
          for (s <- 0 until cfg.numBreakpoints) {
            ctrl.io.bpQuerySlotData(s) := breakpoints(i).io.queryData(s)
            ctrl.io.bpQuerySlotEnabled(s) := breakpoints(i).io.slotEnabled(s)
          }
        }
      }
    } else {
      for (i <- 0 until cpuCnt) {
        ctrl.io.bpHit(i) := False
        ctrl.io.bpHitSlot(i) := 0
      }
      // Tie off breakpoint management inputs when no breakpoints
      ctrl.io.bpSetOk := False
      ctrl.io.bpSetSlot := 0
      ctrl.io.bpQueryCount := 0
      for (s <- 0 until cfg.numBreakpoints.max(1)) {
        ctrl.io.bpQuerySlotData(s) := 0
        ctrl.io.bpQuerySlotEnabled(s) := False
      }
    }

    // Transport: expose byte-stream interface. Top-levels connect either
    // DebugUart (FPGA) or a TCP socket bridge (simulation).
    proto.io.transport <> io.debugTransport.get

    ctrl
  }

  // ==================================================================
  // Arbiter (deferred to here so debug BMB master can be included)
  // ==================================================================

  if (needsArbiter) {
    val arbiter = BmbArbiter(
      inputsParameter = Seq.fill(totalBmbInputs)(inputParam),
      outputParameter = bmbParameter,
      lowerFirstPriority = false  // Round-robin
    )
    for (i <- 0 until cpuCnt) {
      arbiter.io.inputs(i) << cores(i).io.bmb
    }
    // Debug BMB master as lowest priority input (last port)
    if (hasDebugMem) {
      debugCtrl.foreach { ctrl =>
        ctrl.io.bmb.foreach { debugBmb =>
          arbiter.io.inputs(cpuCnt) << debugBmb
        }
      }
    }
    io.bmb <> arbiter.io.output
  }

  // ==================================================================
  // Tie-offs when debug is not present
  // ==================================================================

  if (debugConfig.isEmpty) {
    for (i <- 0 until cpuCnt) {
      cores(i).io.debugRamAddr := 0
      cores(i).io.debugHalt := False
    }
  }

  // ==================================================================
  // UART (core 0 only)
  // ==================================================================

  io.txd := cores(0).io.txd
  cores(0).io.rxd := io.rxd
  for (i <- 1 until cpuCnt) {
    cores(i).io.rxd := True  // No UART on cores 1+
  }

  // ==================================================================
  // Per-core Output Routing
  // ==================================================================

  for (i <- 0 until cpuCnt) {
    io.wd(i)      := cores(i).io.wd
    io.pc(i)      := cores(i).io.pc
    io.jpc(i)     := cores(i).io.jpc
    io.aout(i)    := cores(i).io.aout
    io.bout(i)    := cores(i).io.bout
    io.memBusy(i) := cores(i).io.memBusy
    io.halted(i)  := cores(i).io.debugHalted
  }

  // ==================================================================
  // Core 0 Debug Outputs
  // ==================================================================

  io.debugMemState := cores(0).io.debugMemState
  io.uartTxData    := cores(0).io.uartTxData
  io.uartTxValid   := cores(0).io.uartTxValid
  io.debugExc      := cores(0).io.debugExc
}
