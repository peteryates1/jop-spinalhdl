package jop.system
import jop.config._

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.io.{CmpSync, Ihlu, IhluConfig}
import jop.memory.CardTable
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
 *   - Dynamic device pin passthrough from core 0 (UART, Ethernet, SD, VGA, etc.)
 *   - Cores 1+: UART RXD tied to True (idle)
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
  jbcInit: Option[Seq[BigInt]] = None,
  ethTxCd: Option[ClockDomain] = None,
  ethRxCd: Option[ClockDomain] = None,
  vgaCd:   Option[ClockDomain] = None,
  separateStackDmaBus: Boolean = false,
  perCoreConfigs: Option[Seq[JopCoreConfig]] = None
) extends Component {
  require(cpuCnt >= 1, "cpuCnt must be at least 1")
  // THE CROSS-CORE ROOT PORT SETS THE CEILING. `Sys.rootSel` is 14 bits and the
  // target field is bits 11..8 -- four bits, so 16 addressable cores. Past that
  // the field aliases: a collector asking for core 16's stack reads core 0's,
  // silently and with no way to notice, which would hand the GC another core's
  // roots and collect live objects. This is the real limit that the GC's old
  // `cpuCnt <= N` guard was standing in for; it belongs here, where it is
  // checked at elaboration rather than assumed at runtime. Raising it means
  // widening rootSel and the decode in the root mux below.
  require(cpuCnt <= 16,
    s"cpuCnt = $cpuCnt exceeds 16: the cross-core GC root port's target field " +
    "is 4 bits (Sys.rootSel(11 downto 8)), so cores above 15 alias onto lower " +
    "ones and the collector would read the wrong core's stack. Widen rootSel " +
    "and the root mux in JopCluster to go further.")

  // Validate per-core configs if provided
  perCoreConfigs.foreach { configs =>
    require(configs.length == cpuCnt, s"perCoreConfigs length (${configs.length}) must match cpuCnt ($cpuCnt)")
    configs.foreach { c =>
      require(c.memConfig == baseConfig.memConfig, "All cores must share memConfig")
      require(c.pcWidth == baseConfig.pcWidth, "All cores must share pcWidth")
      require(c.jpcWidth == baseConfig.jpcWidth, "All cores must share jpcWidth")
      require(c.ramWidth == baseConfig.ramWidth, "All cores must share ramWidth")
      require(c.dataWidth == baseConfig.dataWidth, "All cores must share dataWidth")
      require(c.blockBits == baseConfig.blockBits, "All cores must share blockBits")
    }
  }

  // True when cpuCnt > 1 and any core other than 0 has a UART device.
  val hasPerCoreUart = cpuCnt > 1 && (1 until cpuCnt).exists { i =>
    val cc = perCoreConfigs.map(_(i)).getOrElse(baseConfig)
    cc.effectiveDevices.values.exists(_.deviceType == DeviceType.Uart)
  }

  // Number of BMB inputs: cores + optional debug controller + DMA devices (core 0) + optional stack DMA (per-core)
  // When separateStackDmaBus=true, DMA uses its own bus (not the main arbiter)
  val hasDebugMem = debugConfig.exists(_.hasMemAccess)
  val core0Config = perCoreConfigs.map(_(0)).getOrElse(baseConfig)
  val dmaDeviceCount = {
    import jop.io.DeviceTypes
    DeviceTypes.dmaCount(core0Config.effectiveDevices)
  }
  val hasStackDma = baseConfig.useStackCache
  val stackDmaInArbiter = hasStackDma && !separateStackDmaBus
  val totalBmbInputs = cpuCnt + (if (hasDebugMem) 1 else 0) + dmaDeviceCount + (if (stackDmaInArbiter) cpuCnt else 0)

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
    val debugBcFillAddr = out UInt(baseConfig.memConfig.addressWidth bits)
    val debugBcFillLen = out UInt(10 bits)
    val debugBcFillCount = out UInt(10 bits)
    val debugBcRdCapture = out Bits(32 bits)
    val uartTxData    = out Bits(8 bits)
    val uartTxValid   = out Bool()
    val debugExc      = out Bool()
    // Per-core array-bounds fault, live. Pruned in FPGA builds (nothing drives a
    // pin from them); exists so a simulation can STOP on the fault instead of
    // discovering it thousands of cycles later from a printed counter.
    val debugAbFire   = out Vec(Bool(), cpuCnt)
    val debugAbIndex  = out Vec(UInt(baseConfig.memConfig.addressWidth bits), cpuCnt)
    val debugAbLength = out Vec(UInt(baseConfig.memConfig.addressWidth bits), cpuCnt)
    val debugAbHandle = out Vec(UInt(baseConfig.memConfig.addressWidth bits), cpuCnt)

    // Stack cache debug (core 0, optional)
    val scDebugRotState     = if (baseConfig.useStackCache) Some(out UInt(3 bits)) else None
    val scDebugActiveBankIdx = if (baseConfig.useStackCache) Some(out UInt(2 bits)) else None
    val scDebugBankBase     = if (baseConfig.useStackCache) Some(out Vec(UInt(baseConfig.stackConfig.spWidth bits), 3)) else None
    val scDebugBankResident = if (baseConfig.useStackCache) Some(out Bits(3 bits)) else None
    val scDebugBankDirty    = if (baseConfig.useStackCache) Some(out Bits(3 bits)) else None
    val scDebugNeedsRot     = if (baseConfig.useStackCache) Some(out Bool()) else None
    val scDebugSp           = if (baseConfig.useStackCache) Some(out UInt(baseConfig.stackConfig.spWidth bits)) else None
    val scDebugVp           = if (baseConfig.useStackCache) Some(out UInt(baseConfig.stackConfig.spWidth bits)) else None

    // Write-snoop debug (core 0)
    val scDebugPipeWrAddr = if (baseConfig.useStackCache) Some(out UInt(baseConfig.stackConfig.spWidth bits)) else None
    val scDebugPipeWrData = if (baseConfig.useStackCache) Some(out Bits(baseConfig.dataWidth bits)) else None
    val scDebugPipeWrEn   = if (baseConfig.useStackCache) Some(out Bool()) else None
    val scDebugVp0Data    = if (baseConfig.useStackCache) Some(out Bits(baseConfig.dataWidth bits)) else None

    // Separate DMA BMB (when separateStackDmaBus=true, DMA uses its own bus)
    val stackDmaBmb = if (separateStackDmaBus && hasStackDma) Some(master(Bmb(inputParam))) else None

    // Block-fill sideband (optional, when the backend provides a fast fill).
    // GC runs only on core 0 during STW, so core 0's fill is routed out.
    val fill = if (baseConfig.memConfig.hasBackendFill) Some(master(jop.memory.MemFill(baseConfig.memConfig.addressWidth))) else None

    // Debug transport byte interface (byte-stream abstraction point).
    // FPGA top-levels connect DebugUart to this; sim harnesses connect directly.
    val debugTransport = if (debugConfig.isDefined) Some(slave(DebugTransport())) else None

    // Per-core UART TX (optional, for SMP debug via JP1 header)
    val perCoreTxd = if (hasPerCoreUart) Some(out Vec(Bool(), cpuCnt)) else None

  }

  // ==================================================================
  // Instantiate N JOP Cores
  // ==================================================================

  // Caches (A$ and O$) are safe for SMP: cross-core snoop invalidation
  // is wired below — each core's iastore/putfield broadcasts on the snoop
  // bus, and all other cores check tags and selectively invalidate.

  val cores = (0 until cpuCnt).map { i =>
    // Per-core devices are set declaratively in the config (by JopSystem.coreConfigs).
    val base = perCoreConfigs.map(_(i)).getOrElse(baseConfig)
    val coreConfig = base.copy(cpuId = i, cpuCnt = cpuCnt)
    JopCore(
      config = coreConfig,
      romInit = romInit,
      ramInit = ramInit,
      jbcInit = jbcInit,
      ethTxCd = if (i == 0) ethTxCd else None,
      ethRxCd = if (i == 0) ethRxCd else None,
      vgaCd   = if (i == 0) vgaCd else None
    )
  }

  // Block-fill sideband: route core 0's fill master to the cluster output.
  // Other cores never fill (GC is single-threaded on core 0), so tie their
  // fill busy input low.
  io.fill.foreach { f => f <> cores(0).io.fill.get }
  for (i <- 1 until cpuCnt) cores(i).io.fill.foreach { _.busy := False }

  // ==================================================================
  // Memory Bus: direct (single-core, no debug mem) or arbitrated
  // ==================================================================

  val cmpSync: Option[CmpSync] = if (cpuCnt == 1 && !needsArbiter) {
    // Single-core, no arbiter needed: direct BMB connection
    io.bmb <> cores(0).io.bmb
    cores(0).io.syncIn.halted := False
    cores(0).io.syncIn.s_out  := False
    cores(0).io.syncIn.status := False
    None
  } else if (cpuCnt == 1) {
    // Single-core but debug needs memory: 2-input arbiter (core + debug)
    // Arbiter is created below after debug subsystem instantiation.
    // Sync tie-offs for single-core:
    cores(0).io.syncIn.halted := False
    cores(0).io.syncIn.s_out  := False
    cores(0).io.syncIn.status := False
    None
  } else if (baseConfig.useCmpSync) {
    // SMP with global lock (CmpSync)
    val sync = CmpSync(cpuCnt)
    for (i <- 0 until cpuCnt) {
      sync.io.syncIn(i) := cores(i).io.syncOut
      cores(i).io.syncIn := sync.io.syncOut(i)
    }
    Some(sync)
  } else {
    // SMP with per-object lock (IHLU)
    None
  }

  val ihlu: Option[Ihlu] = if (cpuCnt >= 2 && !baseConfig.useCmpSync) {
    val lock = Ihlu(IhluConfig(cpuCnt = cpuCnt))
    for (i <- 0 until cpuCnt) {
      lock.io.syncIn(i) := cores(i).io.syncOut
      cores(i).io.syncIn := lock.io.syncOut(i)
    }
    Some(lock)
  } else None

  // ==================================================================
  // Debug Subsystem (optional)
  // ==================================================================

  // DECLARED HERE, ASSIGNED FAR BELOW, and the order matters.
  //
  // The debug controller below reads gcRootRamAddr at
  //   cores(i).io.debugRamAddr := ctrl.io.debugRamAddr(i) | gcRootRamAddr(i)
  // but the Vec used to be declared several hundred lines further down, with
  // the cross-core GC root logic that drives it. A Scala `val` read before its
  // initialiser has run is null, so any configuration with a debugConfig died
  // during ELABORATION:
  //
  //   NullPointerException: Cannot invoke "spinal.core.Vec.apply(int)" because
  //   the return value of "jop.system.JopCluster.gcRootRamAddr()" is null
  //
  // Nothing caught it because no preset enables the debug controller -- only
  // JopDebugProtocolSim does, and that sim is not in CI. Declaration hoisted;
  // the single assignment stays with the logic that computes it, since
  // SpinalHDL rejects a second one as an assignment overlap.
  val gcRootRamAddr = Vec(UInt(8 bits), cpuCnt)

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
      cores(i).io.debugRamAddr := ctrl.io.debugRamAddr(i) | gcRootRamAddr(i)
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
  // PER-CORE BUS COUNTERS, for the 4-core SDRAM stall (item 34).
  //
  // The stall needs BOTH the SDRAM path AND four masters: 2 cores on the same
  // memory passes, and 4 cores passes on BRAM and in BOTH simulations. It does
  // not reproduce anywhere a waveform can reach, so the only place to measure it
  // is the board. These separate the two possibilities that look identical from
  // the application:
  //   req climbing, gnt flat -> the core IS asking and not being served:
  //                             starvation in the arbiter or the controller
  //   req flat               -> it is not asking, so it is wedged elsewhere and
  //                             the bus is a red herring
  //   busy                   -> cycles spent with a request outstanding and
  //                             ungranted, to tell a slow path from a stopped one
  //
  // Saturating, not wrapping: a wrapped counter read once after a stall cannot
  // be told from a small one.
  //
  // EXACTLY ONE assignment per element. A default `:= 0` here plus a conditional
  // drive inside the arbiter block is an ASSIGNMENT OVERLAP, which `compile`
  // does not catch because it never elaborates — the same way gcRootRamAddr
  // broke SMP elaboration silently.
  val busCounters = Vec.fill(cpuCnt)(Vec(Bits(32 bits), 4))
  // SECOND BANK: where the core IS, not how much bus it used. The req/gnt
  // counters showed the wedged core stops asking, and the halt counter did not
  // discriminate (healthy and wedged cores were within 0.004%), so the open
  // question is simply what it is executing.
  //   0 = pc (microcode), 1 = jpc (bytecode), 2 = exception count, 3 = spare
  // pc/jpc are sampled live, so a wedged core shows a fixed value while a
  // running one shows whatever it happened to be doing. The exception COUNT is
  // the one that could answer outright: this session already found the
  // exception path derailing a core, and a non-zero count here on the wedged
  // core names the cause immediately.
  // See the root-port decode below: the probe banks only fit while the cores
  // leave enough of the 4-bit target field free, which stops at 4 cores.
  val hasProbeBanks = cpuCnt <= 4
  val stateCounters = Vec.fill(cpuCnt)(Vec(Bits(32 bits), 4))
  for (i <- 0 until cpuCnt) {
    val reqCnt  = Reg(UInt(32 bits)) init(0)
    val gntCnt  = Reg(UInt(32 bits)) init(0)
    val busyCnt = Reg(UInt(32 bits)) init(0)
    val req = cores(i).io.bmb.cmd.valid
    val gnt = cores(i).io.bmb.cmd.valid && cores(i).io.bmb.cmd.ready
    when(req && reqCnt  =/= U(reqCnt.maxValue))  { reqCnt  := reqCnt + 1 }
    when(gnt && gntCnt  =/= U(gntCnt.maxValue))  { gntCnt  := gntCnt + 1 }
    when(req && !gnt && busyCnt =/= U(busyCnt.maxValue)) { busyCnt := busyCnt + 1 }
    // THE ARRAY-BOUNDS FAULT'S OPERANDS, latched on the FIRST fault per core.
    //
    // These take over slots 0..2 from req/gnt/busy. That is deliberate: the bus
    // counters have already returned their answer (the stalled core is not
    // starved, it stops asking — d3a634b) and they saturate to -1 within a
    // second, so they are dead weight, while the fault operands are the open
    // question. `halt` stays in slot 3. Restore reqCnt/gntCnt/busyCnt here if a
    // starvation question ever comes back; the counters themselves still run.
    val abSeen   = Reg(Bool()) init(False)
    val abIndex  = Reg(UInt(cores(i).io.debugAbIndex.getWidth bits)) init(0)
    val abLength = Reg(UInt(cores(i).io.debugAbLength.getWidth bits)) init(0)
    val abHandle = Reg(UInt(cores(i).io.debugAbHandle.getWidth bits)) init(0)
    when(cores(i).io.debugAbFire && !abSeen) {
      abSeen   := True
      abIndex  := cores(i).io.debugAbIndex
      abLength := cores(i).io.debugAbLength
      abHandle := cores(i).io.debugAbHandle
    }
    io.debugAbFire(i)   := cores(i).io.debugAbFire
    io.debugAbIndex(i)  := cores(i).io.debugAbIndex
    io.debugAbLength(i) := cores(i).io.debugAbLength
    io.debugAbHandle(i) := cores(i).io.debugAbHandle
    busCounters(i)(0) := abIndex.asBits.resized
    busCounters(i)(1) := abLength.asBits.resized
    busCounters(i)(2) := abHandle.asBits.resized
    // Keep the counters driven so they are not pruned and can be restored by
    // swapping three lines above.
    val busCountersUnused = reqCnt.asBits ## gntCnt.asBits ## busyCnt.asBits
    busCountersUnused.allowPruning()
    // Slot 3: cycles HALTED BY THE LOCK MANAGER (Sys.io.halted, driven from
    // syncIn.halted — Ihlu or CmpSync, and gcHalt during a stop-the-world).
    //
    // This is the follow-on question after the req/gnt counters showed the
    // stalled core stops ASKING rather than being starved: a core waiting on a
    // monitor issues no memory traffic at all, so it looks identical from the
    // bus. If this counter is huge on the stalled core it is blocked in the lock
    // manager; if it is small the core is spinning somewhere in ordinary code
    // and neither the bus nor the lock is responsible.
    val haltCnt = Reg(UInt(32 bits)) init(0)
    when(cores(i).io.debugSyncHalted && haltCnt =/= U(haltCnt.maxValue)) { haltCnt := haltCnt + 1 }
    busCounters(i)(3) := haltCnt.asBits

    val excCnt = Reg(UInt(32 bits)) init(0)
    val excPrev = RegNext(cores(i).io.debugExc) init(False)
    when(cores(i).io.debugExc && !excPrev && excCnt =/= U(excCnt.maxValue)) {
      excCnt := excCnt + 1   // rising edges only: debugExc is a level
    }
    // WHERE THE FIRST EXCEPTION LANDED. The live pc/jpc say where a wedged core
    // is sitting NOW, which is downstream of whatever derailed it; the first
    // exception is the event itself. Latched once and never updated, so a core
    // that goes on to take more exceptions (or to wedge somewhere unrelated)
    // cannot overwrite the origin. `excType` is valid on the same cycle as the
    // pulse — Sys writes excTypeReg and excPend together.
    val excSeen  = Reg(Bool()) init(False)
    val excPc    = Reg(UInt(cores(i).io.pc.getWidth bits)) init(0)
    val excJpc   = Reg(UInt(cores(i).io.jpc.getWidth bits)) init(0)
    val excFirst = Reg(Bits(8 bits)) init(0)
    when(cores(i).io.debugExc && !excPrev && !excSeen) {
      excSeen  := True
      excPc    := cores(i).io.pc
      excJpc   := cores(i).io.jpc
      excFirst := cores(i).io.debugExcType
    }
    // Two values per slot: the root port has only 4 bits of target and 12..15
    // are all this bank has, so pc and jpc share a word. pc is 12 bits and jpc
    // fits in 16, which is checked here rather than silently truncated.
    require(cores(i).io.pc.getWidth <= 16 && cores(i).io.jpc.getWidth <= 16,
      s"state counter packing assumes pc/jpc <= 16 bits, got ${cores(i).io.pc.getWidth}/${cores(i).io.jpc.getWidth}")
    stateCounters(i)(0) := cores(i).io.pc.asBits.resize(16) ## cores(i).io.jpc.asBits.resize(16)
    stateCounters(i)(1) := excPc.asBits.resize(16) ## excJpc.asBits.resize(16)
    stateCounters(i)(2) := excCnt.asBits
    // BMB COMMAND/RESPONSE BALANCE. Every BMB command this core issues must
    // produce exactly one response, and the controller consumes exactly one per
    // command, so `issued - received` is bounded by the outstanding depth and
    // returns to 0. If it DRIFTS, responses are off by one and every read
    // afterwards returns the previous transaction's data — which is exactly the
    // shape of the fault: the array-length read came back 0 (a write response
    // carries no data) for a word that really holds 4.
    //
    // Sits in the top 24 bits of the excType slot, which had 24 spare.
    val cmdCnt  = Reg(UInt(24 bits)) init(0)
    val rspCnt  = Reg(UInt(24 bits)) init(0)
    when(cores(i).io.bmb.cmd.fire && cores(i).io.bmb.cmd.last) { cmdCnt := cmdCnt + 1 }
    when(cores(i).io.bmb.rsp.fire && cores(i).io.bmb.rsp.last) { rspCnt := rspCnt + 1 }
    stateCounters(i)(3) := (cmdCnt - rspCnt).asBits ## excFirst
  }

  // Arbiter (deferred to here so debug BMB controller can be included)
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

    var nextPort = cpuCnt
    // Debug BMB controller
    if (hasDebugMem) {
      debugCtrl.foreach { ctrl =>
        ctrl.io.bmb.foreach { debugBmb =>
          arbiter.io.inputs(nextPort) << debugBmb
          nextPort += 1
        }
      }
    }
    // DMA BMB masters (core 0 only — devices with hasDma, e.g. VGA framebuffer)
    for (dmaIdx <- 0 until dmaDeviceCount) {
      arbiter.io.inputs(nextPort) << cores(0).io.dmaBmb(dmaIdx)
      nextPort += 1
    }
    // Stack cache DMA BMB masters (one per core) — only in arbiter when not separated
    if (stackDmaInArbiter) {
      for (i <- 0 until cpuCnt) {
        cores(i).io.stackDmaBmb.foreach { dmaBmb =>
          arbiter.io.inputs(nextPort) << dmaBmb
          nextPort += 1
        }
      }
    }
    io.bmb <> arbiter.io.output
  }

  // ==================================================================
  // Card table — ONE per cluster, fed from the memory-side bus (item 1)
  // ==================================================================
  //
  // It used to live inside each JopCore, snooping that core's own BMB command.
  // That snoop sits AHEAD of the arbiter, so each table saw only its own core's
  // writes: on SMP a tenured->nursery store by another core marked the wrong
  // table, the minor GC never traced the young object, and it was collected
  // while live. java/apps/SmpGcTest loses 192/192 cross-core references that
  // way with GC.java's `cpuCnt0 <= 1` guard removed.
  //
  // io.bmb is the right snoop point in BOTH topologies, which is why this is one
  // code path and not two: on SMP it is the arbiter output, and on single-core
  // it is the core's own port wired straight through — so single-core behaviour
  // is bit-for-bit what it was.
  //
  // Cores reach the table through CardCtrlPort, the same shape as CmpSync: a
  // cluster-level resource addressed through per-core I/O. Config writes are
  // priority-muxed by core index. That is safe because every writer is the
  // collector, which is stop-the-world — but it is a real constraint, so it is
  // stated here rather than left to be rediscovered.
  val cardTable: Option[CardTable] = if (baseConfig.memConfig.hasCardTable) {
    val mc = baseConfig.memConfig
    val ct = new CardTable(mc.cardCount, mc.cardShift, mc.addressWidth)
    val idxW = ct.idxWidth

    val cmdIsWrite = io.bmb.cmd.fragment.opcode === Bmb.Cmd.Opcode.WRITE
    ct.io.markValid := io.bmb.cmd.fire && cmdIsWrite
    ct.io.markAddr  := (io.bmb.cmd.fragment.address >> 2).resize(mc.addressWidth)

    val ports = cores.map(_.io.card.get)

    // Lowest core index wins a same-cycle collision.
    val wrValidC = ports.map(_.wr).reduce(_ || _)
    val wrSelC   = UInt(3 bits)
    val wrDataC  = Bits(32 bits)
    wrSelC  := 0
    wrDataC := 0
    for (i <- (cpuCnt - 1) to 0 by -1) {
      when(ports(i).wr) {
        wrSelC  := ports(i).sel
        wrDataC := ports(i).wrData
      }
    }

    // Register the muxed control write before it reaches the table.
    //
    // Without this the critical path was, measured on EP4CGX150 SMP:
    //   memCtrl|handleIndex -> a ~46-stage LessThan carry chain (the I/O address
    //   decode) -> io_ioAddr -> core's card.wr -> this priority mux -> clrIdx ->
    //   the card-table BRAM address pin
    // i.e. -0.490 ns at 100 MHz. The decode chain was always that long; what
    // this change added was carrying its result across the core->cluster
    // boundary and through a mux into a BRAM address, which is what tipped it.
    //
    // A cycle of latency here is free: every one of these writes is the
    // stop-the-world collector configuring the tenure window, setting a read
    // index, or clearing a card word. Nothing is waiting on it in the same
    // cycle, and the mark path is untouched (it has its own input register).
    val wrValid = RegNext(wrValidC) init (False)
    val wrSel   = RegNext(wrSelC)   init (0)
    val wrData  = RegNext(wrDataC)  init (0)

    val cardLo    = Reg(UInt(mc.addressWidth bits)) init (0)  // tenure base word
    val cardHi    = Reg(UInt(mc.addressWidth bits)) init (0)  // tenure top word
    val cardRdIdx = Reg(UInt(idxW bits)) init (0)             // word index for DATA read
    when(wrValid) {
      switch(wrSel) {
        is(0) { cardLo    := wrData(mc.addressWidth - 1 downto 0).asUInt }
        is(1) { cardHi    := wrData(mc.addressWidth - 1 downto 0).asUInt }
        is(2) { cardRdIdx := wrData(idxW - 1 downto 0).asUInt }
      }
    }
    ct.io.baseWord := cardLo
    ct.io.topWord  := cardHi
    ct.io.rdIdx    := cardRdIdx

    // CARD_CLEAR write: data = word index to clear, or all-ones (-1) => clear all.
    val clrWr   = wrValid && (wrSel === U(6, 3 bits))
    val clrAllV = wrData.andR
    ct.io.clrEn  := clrWr && !clrAllV
    ct.io.clrAll := clrWr && clrAllV
    ct.io.clrIdx := wrData(idxW - 1 downto 0).asUInt

    // One rdIdx, so every core sees the same word — broadcast is correct.
    ports.foreach(_.rdData := ct.io.rdData)
    Some(ct)
  } else None

  // Separate DMA bus: wire core(s)' DMA BMB to the dedicated IO port
  if (separateStackDmaBus && hasStackDma) {
    // For cpuCnt=1: direct wire. For cpuCnt>1: would need a DMA-only arbiter.
    require(cpuCnt == 1 || !separateStackDmaBus,
      "separateStackDmaBus with cpuCnt > 1 not yet supported")
    cores(0).io.stackDmaBmb.foreach { dmaBmb =>
      io.stackDmaBmb.get <> dmaBmb
    }
  }

  // ==================================================================
  // Cross-Core Snoop Invalidation
  // ==================================================================
  //
  // Each core's snoopOut broadcasts store events (iastore/putfield).
  // Each core's snoopIn receives all OTHER cores' snoop events so it
  // can invalidate matching cache lines. Only one core writes per cycle
  // (BMB arbiter serializes), so MuxOH is safe for data fields.

  val hasSnoopBus = baseConfig.memConfig.useAcache || baseConfig.memConfig.useOcache

  if (hasSnoopBus && cpuCnt > 1) {
    for (i <- 0 until cpuCnt) {
      cores(i).io.snoopIn.foreach { si =>
        val otherSnoops = (0 until cpuCnt).filter(_ != i).map(j => cores(j).io.snoopOut.get)
        si.valid   := otherSnoops.map(_.valid).reduce(_ || _)
        si.isArray := MuxOH(otherSnoops.map(_.valid), otherSnoops.map(_.isArray))
        si.handle  := MuxOH(otherSnoops.map(_.valid), otherSnoops.map(_.handle))
        si.index   := MuxOH(otherSnoops.map(_.valid), otherSnoops.map(_.index))
      }
    }
  } else if (hasSnoopBus) {
    // Single-core: tie off snoop input (no other cores)
    cores(0).io.snoopIn.foreach { si =>
      si.valid   := False
      si.isArray := False
      si.handle  := 0
      si.index   := 0
    }
  }

  // ==================================================================
  // Tie-offs when debug is not present
  // ==================================================================

  // ==========================================================================
  // CROSS-CORE GC ROOTS
  // ==========================================================================
  //
  // A collector can only see its own stack: stacks are core-private RAM and
  // `Native.rdIntMem` reads the calling core's. Objects live only in another
  // core's stack were collected while still reachable (current-status item 1,
  // proven by the STACKROOT probe). This lets a collector read a HALTED core's
  // roots — stack words via the per-core debug read port, plus SP and the A/B
  // top-of-stack registers, which live in no RAM at all.
  //
  // Only the collector issues requests (it holds the allocation monitor and
  // every other core is halted), so requests to a given target are simply
  // OR-reduced rather than arbitrated.
  // Exactly ONE assignment per element: a default plus a conditional loop
  // double-assigns whenever cpuCnt > 1, which SpinalHDL rejects as an
  // ASSIGNMENT OVERLAP at elaboration. That slipped through CI once because
  // every CI sim is single-core, where only the default ran.
    // gcRootRamAddr is DECLARED above the debug controller; see there.
  for (t <- 0 until cpuCnt) {
    var a = U(0, 8 bits)
    if (cpuCnt > 1) for (r <- 0 until cpuCnt if r != t) {
      val sel = cores(r).io.rootSel
      val hit = sel(11 downto 8).asUInt === U(t, 4 bits)
      a = a | Mux(hit, sel(7 downto 0).asUInt, U(0, 8 bits))
    }
    gcRootRamAddr(t) := a
  }
  if (cpuCnt > 1) for (r <- 0 until cpuCnt) {
    val sel  = cores(r).io.rootSel
    val tgt  = sel(11 downto 8).asUInt
    val what = sel(13 downto 12)
    val word = Bits(32 bits)
    val sp   = Bits(32 bits)
    val ra   = Bits(32 bits)
    val rb   = Bits(32 bits)
    if (cpuCnt > 1) {
      word := cores(0).io.debugRamData
      sp   := cores(0).io.debugSp.asBits.resized
      ra   := cores(0).io.stackA
      rb   := cores(0).io.stackB
      for (t <- 1 until cpuCnt) {
        when(tgt === U(t, 4 bits)) {
          word := cores(t).io.debugRamData
          sp   := cores(t).io.debugSp.asBits.resized
          ra   := cores(t).io.stackA
          rb   := cores(t).io.stackB
        }
      }
    } else {
      word := 0; sp := 0; ra := 0; rb := 0
    }
    // The probe banks share the root port rather than taking new I/O addresses:
    // Sys decodes 4 bits and all 16 names are already used. `tgt` is 4 bits and
    // cores occupy 0..cpuCnt-1, so tgt >= 8 was free and carries the bus
    // counters (8 + core) and the state bank (12 + core).
    //
    // THAT ONLY WORKS UP TO 4 CORES. At 8, the cores themselves need targets
    // 0..7, leaving 8..15 — room for exactly ONE bank of eight, not two, and
    // `12 + core` runs off the end of a 4-bit field and wraps onto another
    // core's slot. Rather than ship a probe that silently reads the wrong core,
    // the banks are omitted above 4 cores; `hasProbeBanks` is the single switch
    // and SmpGcTest checks the same bound before reading them. Restoring them
    // for a wider cluster means widening `rootSel`, which is a Sys change.
    if (hasProbeBanks) {
      val ctrTgt = (tgt - 8).resize(log2Up(cpuCnt) max 1)
      val ctr    = Bits(32 bits)
      ctr := busCounters(0)(what.asUInt)
      for (t <- 1 until cpuCnt) {
        when(ctrTgt === U(t, ctrTgt.getWidth bits)) { ctr := busCounters(t)(what.asUInt) }
      }

      val stTgt = (tgt - 12).resize(log2Up(cpuCnt) max 1)
      val st    = Bits(32 bits)
      st := stateCounters(0)(what.asUInt)
      for (t <- 1 until cpuCnt) {
        when(stTgt === U(t, stTgt.getWidth bits)) { st := stateCounters(t)(what.asUInt) }
      }

      cores(r).io.rootData := Mux(tgt >= U(12, 4 bits), st,
                              Mux(tgt >= U(8, 4 bits), ctr, what.mux(
        B"00" -> word,
        B"01" -> sp,
        B"10" -> ra,
        default -> rb
      )))
    } else {
      cores(r).io.rootData := what.mux(
        B"00" -> word,
        B"01" -> sp,
        B"10" -> ra,
        default -> rb
      )
    }
  }

  if (debugConfig.isEmpty) {
    for (i <- 0 until cpuCnt) {
      cores(i).io.debugRamAddr := gcRootRamAddr(i)
      cores(i).io.debugHalt := False
    }
  }

  // ==================================================================
  // Dynamic Pin Passthrough from Core 0
  // ==================================================================
  // All device-specific external pins (UART, Ethernet, SD, VGA, etc.)
  // are passed through from core 0's devicePins map.

  val devicePins: Map[String, Bundle] =
    cores(0).devicePins.map { case (name, corePins) =>
      val clusterPins = cloneOf(corePins).setName(s"io_${name}")
      for ((cpSig, ccSig) <- clusterPins.flatten.zip(corePins.flatten)) {
        if (ccSig.isOutput) {
          out(cpSig)
          cpSig := ccSig
        } else {
          in(cpSig)
          ccSig := cpSig
        }
      }
      name -> clusterPins
    }

  /** Typed accessor for individual device pin signals */
  def devicePin[T <: Data](deviceName: String, pinName: String): T = {
    val bundle = devicePins.getOrElse(deviceName,
      throw new NoSuchElementException(s"No device pins for '$deviceName'. Available: ${devicePins.keys.mkString(", ")}"))
    bundle.elements.find(_._1 == pinName).getOrElse(
      throw new NoSuchElementException(s"No pin '$pinName' on device '$deviceName'. Available: ${bundle.elements.map(_._1).mkString(", ")}"))
      ._2.asInstanceOf[T]
  }

  // Cores 1+: tie UART RXD input to idle (True) since only core 0 gets external RX
  for (i <- 1 until cpuCnt) {
    if (cores(i).devicePins.contains("uart")) {
      cores(i).devicePin[Bool]("uart", "rxd") := True
    }
  }

  // Per-core UART TX routing (for SMP debug via JP1)
  if (hasPerCoreUart) {
    for (i <- 0 until cpuCnt) {
      if (cores(i).devicePins.contains("uart")) {
        io.perCoreTxd.get(i) := cores(i).devicePin[Bool]("uart", "txd")
      } else {
        io.perCoreTxd.get(i) := True
      }
    }
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
  io.debugBcFillAddr := cores(0).io.debugBcFillAddr
  io.debugBcFillLen := cores(0).io.debugBcFillLen
  io.debugBcFillCount := cores(0).io.debugBcFillCount
  io.debugBcRdCapture := cores(0).io.debugBcRdCapture
  io.uartTxData    := cores(0).io.uartTxData
  io.uartTxValid   := cores(0).io.uartTxValid
  io.debugExc      := cores(0).io.debugExc

  if (baseConfig.useStackCache) {
    io.scDebugRotState.get := cores(0).io.scDebugRotState.get
    io.scDebugActiveBankIdx.get := cores(0).io.scDebugActiveBankIdx.get
    io.scDebugBankBase.get := cores(0).io.scDebugBankBase.get
    io.scDebugBankResident.get := cores(0).io.scDebugBankResident.get
    io.scDebugBankDirty.get := cores(0).io.scDebugBankDirty.get
    io.scDebugNeedsRot.get := cores(0).io.scDebugNeedsRot.get
    io.scDebugSp.get := cores(0).io.debugSp
    io.scDebugVp.get := cores(0).io.debugVp

    // Write-snoop debug passthrough
    io.scDebugPipeWrAddr.get := cores(0).io.scDebugPipeWrAddr.get
    io.scDebugPipeWrData.get := cores(0).io.scDebugPipeWrData.get
    io.scDebugPipeWrEn.get := cores(0).io.scDebugPipeWrEn.get
    io.scDebugVp0Data.get := cores(0).io.scDebugVp0Data.get
  }
}
