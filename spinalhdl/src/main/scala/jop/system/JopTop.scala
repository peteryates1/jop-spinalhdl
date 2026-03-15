package jop.system

import jop.config._

import spinal.core._
import spinal.lib._
import spinal.lib.io.TriState
import spinal.lib.memory.sdram.SdramLayout
import spinal.lib.memory.sdram.sdr._
import jop.memory.SdramDeviceInfo
import jop.ddr3.MigBlackBox
import jop.system.pll.{Pll, PllResult}
import jop.system.memory.MemoryControllerFactory
import jop.io.{DeviceTopWirings, TopWiringContext, DeviceClockDomains, TopPinType}

/**
 * Unified JOP FPGA Top-Level — replaces all 7 board-specific top files.
 *
 * Composition with config-driven factories:
 *   PLL factory       -> PllResult (board-specific BlackBox)
 *   ResetGenerator    -> mainClockDomain
 *   JopCluster        -> N cores with arbiter + CmpSync
 *   MemCtrlFactory    -> SDR/DDR3 controller
 *   HangDetector      -> diagnostic UART mux (non-BRAM)
 *
 * Supports multi-system configurations (e.g., DDR3 + SDR on Wukong) where
 * each system gets its own PLL, clock domain, cluster, and memory controller.
 *
 * Entity name is set from JopConfig.entityName for backward compatibility
 * with existing Quartus/Vivado projects and Makefiles.
 *
 * @param config    Top-level JOP configuration
 * @param romInit   Microcode ROM initialization data
 * @param ramInit   Stack RAM initialization data
 */
case class JopTop(
  config: JopConfig,
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Option[Seq[BigInt]] = None,
  mainMemSize: Int = 64 * 1024,
  simulation: Boolean = false
) extends Component {

  private val isMultiSystem = config.systems.length > 1
  private val board = config.assembly.fpgaBoard
  private val manufacturer = config.fpgaFamily.manufacturer
  private val ledCount = board.ledCount
  private val activeHigh = board.ledActiveHigh

  // Single-system fields (null/false when isMultiSystem)
  private val sys: JopSystem = if (!isMultiSystem) config.system else null
  private val memDevice = if (!isMultiSystem) config.resolveMemory(sys) else None
  private val memType = if (!isMultiSystem) memDevice.map(_.memType).getOrElse(MemoryType.BRAM) else null
  private val isSdr = !isMultiSystem && memType == MemoryType.SDRAM_SDR
  private val isDdr3 = !isMultiSystem && memType == MemoryType.SDRAM_DDR3
  private val isBram = !isMultiSystem && memType == MemoryType.BRAM

  // Multi-system: presence of any SDR/DDR3 system (for IO ports)
  private val anySdr = if (isMultiSystem)
    config.systems.exists(s => config.resolveMemory(s).exists(_.memType == MemoryType.SDRAM_SDR))
  else isSdr
  private val anyDdr3 = if (isMultiSystem)
    config.systems.exists(s => config.resolveMemory(s).exists(_.memType == MemoryType.SDRAM_DDR3))
  else isDdr3

  // Backward-compatible entity name
  setDefinitionName(config.entityName)

  // ========================================================================
  // I/O Bundle (conditional ports based on board and memory type)
  // ========================================================================

  val io = new Bundle {
    // Clock input (Altera: explicit port; Xilinx: uses default clock domain; Sim: none)
    val clk_in = (!simulation && manufacturer.explicitClockPort) generate (in Bool())

    // UART (primary — system 0)
    val ser_txd = out Bool()
    val ser_rxd = in Bool()

    // UART (secondary — system 1, multi-system only)
    val ser_txd_1 = isMultiSystem generate (out Bool())
    val ser_rxd_1 = isMultiSystem generate (in Bool())

    // LEDs
    val led = out Bits(ledCount bits)

    // SDR SDRAM (conditional)
    val sdram_clk = anySdr generate (out Bool())
    val sdram = anySdr generate master(SdramInterface(sdrLayoutForIo))

    // DDR3 (conditional — Au V2 has CS pin, Wukong does not)
    val ddr3HasCs = anyDdr3 && board.ddr3HasCs
    val ddr3_dq      = anyDdr3 generate inout(Analog(Bits(16 bits)))
    val ddr3_dqs_n   = anyDdr3 generate inout(Analog(Bits(2 bits)))
    val ddr3_dqs_p   = anyDdr3 generate inout(Analog(Bits(2 bits)))
    val ddr3_addr    = anyDdr3 generate (out Bits(14 bits))
    val ddr3_ba      = anyDdr3 generate (out Bits(3 bits))
    val ddr3_ras_n   = anyDdr3 generate (out Bool())
    val ddr3_cas_n   = anyDdr3 generate (out Bool())
    val ddr3_we_n    = anyDdr3 generate (out Bool())
    val ddr3_reset_n = anyDdr3 generate (out Bool())
    val ddr3_ck_p    = anyDdr3 generate (out Bits(1 bits))
    val ddr3_ck_n    = anyDdr3 generate (out Bits(1 bits))
    val ddr3_cke     = anyDdr3 generate (out Bits(1 bits))
    val ddr3_cs_n    = ddr3HasCs generate (out Bits(1 bits))
    val ddr3_dm      = anyDdr3 generate (out Bits(2 bits))
    val ddr3_odt     = anyDdr3 generate (out Bits(1 bits))

    // Per-core UART TX (optional, for SMP debug — single-system only)
    val jp1_txd = (!isMultiSystem && sys != null && sys.hasPerCoreUart) generate (out Bits(sys.cpuCnt bits))
    val jp1_wd  = (!isMultiSystem && sys != null && sys.hasPerCoreUart) generate (out Bits(sys.cpuCnt bits))
  }

  noIoPrefix()

  // Auto-generated device IO ports from DeviceTopWiring.topPins.
  // Adding a new device type only requires a DeviceTopWiring implementation — no JopTop changes.
  // Public for simulation access (e.g. dut.ioPinMap("vga_hs")).
  val ioPinMap: Map[String, Data] = {
    val manufacturer = config.fpgaFamily.manufacturer
    val ports = scala.collection.mutable.LinkedHashMap.empty[String, Data]
    // Iterate all systems' devices for multi-system support
    val allDevices = if (isMultiSystem) {
      config.resolvedSystems.flatMap(_.effectiveDevices)
    } else {
      sys.effectiveDevices.toSeq
    }
    for ((_, inst) <- allDevices) {
      DeviceTopWirings.forInstance(inst).foreach { wiring =>
        for (pin <- wiring.topPins(inst, manufacturer)) {
          if (!ports.contains(pin.name)) {
            val signal: Data = pin.pinType match {
              case TopPinType.Out(1) => out(Bool())
              case TopPinType.Out(w) => out(Bits(w bits))
              case TopPinType.In(1)  => in(Bool())
              case TopPinType.In(w)  => in(Bits(w bits))
              case TopPinType.TriStateBool => master(TriState(Bool()))
            }
            signal.setName(pin.name)
            ports(pin.name) = signal
          }
        }
      }
    }
    ports.toMap
  }

  // ========================================================================
  // SDR SDRAM Layout (needed for io bundle, resolved at class construction)
  // ========================================================================

  /** Find the SDR layout from either single-system or multi-system config */
  private def sdrLayoutForIo: SdramLayout = {
    val sdrDevice = if (isMultiSystem) {
      config.systems
        .flatMap(s => config.resolveMemory(s))
        .find(_.memType == MemoryType.SDRAM_SDR)
        .get
    } else {
      memDevice.get
    }
    SdramDeviceInfo.layoutFor(sdrDevice)
  }

  // ========================================================================
  // Device clock domains (accessible from tests, e.g. VGA sim)
  // Set in single-system path; null for multi-system.
  // ========================================================================

  var deviceClockDomains: DeviceClockDomains = null

  // ========================================================================
  // Single-system path
  // ========================================================================

  if (!isMultiSystem) {

    // ======================================================================
    // 1-6. Clock, PLL, Reset, MIG, Clock Domains (FPGA only — skipped in sim)
    // ======================================================================

    // Forward declarations for FPGA-only values (null in sim)
    var pllResult: PllResult = null
    var systemReset: Bool = null
    var ddr3Mig: MigBlackBox = null
    var ethPll: EthPll = null

    val effectiveMainCd: ClockDomain = if (simulation) {
      ClockDomain.current
    } else {
      // 1. Board Clock Source
      val boardClk = if (manufacturer.explicitClockPort) io.clk_in else ClockDomain.current.readClockWire

      // 2. PLL
      pllResult = Pll.create(board, memType, boardClk)

      // 3. Ethernet PLL (boards with dedicated Ethernet PLL)
      if (sys.ethGmii && board.hasEthPll) {
        ethPll = EthPll()
        ethPll.io.inclk0 := boardClk
      }

      // 4. Reset Generation + Main Clock Domain
      val systemClk = if (!isDdr3) pllResult.systemClk.get else null
      systemReset = if (!isDdr3) ResetGenerator(pllResult.locked, systemClk)
                    else !pllResult.locked  // DDR3: reset when PLL not locked (for Eth/peripheral CDs)

      val mainClockDomain: ClockDomain = if (!isDdr3) {
        ClockDomain(
          clock = systemClk,
          reset = systemReset,
          frequency = FixedFrequency(sys.clkFreq),
          config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
        )
      } else null

      // 5. DDR3 MIG (provides ui_clk for system clock)
      if (isDdr3) {
        val hasCs = board.ddr3HasCs
        ddr3Mig = new MigBlackBox(hasCs)
        io.ddr3_dq    <> ddr3Mig.io.ddr3_dq
        io.ddr3_dqs_n <> ddr3Mig.io.ddr3_dqs_n
        io.ddr3_dqs_p <> ddr3Mig.io.ddr3_dqs_p
        io.ddr3_addr    := ddr3Mig.io.ddr3_addr
        io.ddr3_ba      := ddr3Mig.io.ddr3_ba
        io.ddr3_ras_n   := ddr3Mig.io.ddr3_ras_n
        io.ddr3_cas_n   := ddr3Mig.io.ddr3_cas_n
        io.ddr3_we_n    := ddr3Mig.io.ddr3_we_n
        io.ddr3_reset_n := ddr3Mig.io.ddr3_reset_n
        io.ddr3_ck_p    := ddr3Mig.io.ddr3_ck_p
        io.ddr3_ck_n    := ddr3Mig.io.ddr3_ck_n
        io.ddr3_cke     := ddr3Mig.io.ddr3_cke
        if (hasCs) io.ddr3_cs_n := ddr3Mig.io.ddr3_cs_n
        io.ddr3_dm      := ddr3Mig.io.ddr3_dm
        io.ddr3_odt     := ddr3Mig.io.ddr3_odt
        ddr3Mig.io.sys_clk_i := pllResult.migSysClk.get
        ddr3Mig.io.clk_ref_i := pllResult.migRefClk.get
        ddr3Mig.io.sys_rst   := !pllResult.locked
        ddr3Mig.io.app_sr_req  := False
        ddr3Mig.io.app_ref_req := False
        ddr3Mig.io.app_zq_req  := False
      }

      // DDR3 UI Clock Domain
      val ddr3MainCd: ClockDomain = if (isDdr3) {
        ClockDomain(
          clock = ddr3Mig.io.ui_clk,
          reset = ddr3Mig.io.ui_clk_sync_rst,
          frequency = FixedFrequency(sys.clkFreq),
          config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
        )
      } else null

      if (isDdr3) ddr3MainCd else mainClockDomain
    }

    // 6. Device Clock Domains (delegated to DeviceTopWiring)
    deviceClockDomains = {
      val ctx = TopWiringContext(config, simulation, systemReset, pllResult, ethPll)
      sys.effectiveDevices.values.flatMap { inst =>
        DeviceTopWirings.forInstance(inst).map(_.createClockDomains(ctx, ioPinMap))
      }.foldLeft(DeviceClockDomains()) { (acc, cd) =>
        DeviceClockDomains(
          ethTxCd = acc.ethTxCd.orElse(cd.ethTxCd),
          ethRxCd = acc.ethRxCd.orElse(cd.ethRxCd),
          vgaCd = acc.vgaCd.orElse(cd.vgaCd)
        )
      }
    }

    // ==================================================================
    // 7. Main Design Area
    // ==================================================================

    val mainArea = new ClockingArea(effectiveMainCd) {

      // Build per-core configs
      val burstLen = if (sys.cpuCnt > 1 && isSdr) 4
                     else 0

      val coreConfigs = sys.coreConfigs.map(cc => cc.copy(
        memConfig = if (isDdr3) { val md = memDevice.get; cc.memConfig.copy(
          addressWidth = log2Up((md.sizeBytes / 4).toInt) + 2,
          mainMemSize = md.sizeBytes,
          burstLen = burstLen,
          stackRegionWordsPerCore = 8192
        ) } else if (isBram) cc.memConfig.copy(
          mainMemSize = mainMemSize,
          burstLen = 0
        ) else cc.memConfig.copy(
          burstLen = burstLen,
          stackRegionWordsPerCore = if (board.useStackCache) 8192 else 0
        ),
        supersetJumpTable = sys.baseJumpTable,
        clkFreq = sys.clkFreq,
        useStackCache = (isDdr3 && sys.cpuCnt == 1) || (isSdr && board.useStackCache)
      ))

      // ==============================================================
      // JOP Cluster
      // ==============================================================

      val cluster = JopCluster(
        cpuCnt = sys.cpuCnt,
        baseConfig = coreConfigs.head,
        romInit = Some(romInit),
        ramInit = Some(ramInit),
        jbcInit = Some(Seq.fill(2048)(BigInt(0))),
        ethTxCd = deviceClockDomains.ethTxCd,
        ethRxCd = deviceClockDomains.ethRxCd,
        vgaCd = deviceClockDomains.vgaCd,
        perCoreConfigs = Some(coreConfigs)
      )

      // ==============================================================
      // Memory Controller
      // ==============================================================

      if (isSdr) {
        val md = memDevice.get
        val sdrCtrl = MemoryControllerFactory.createSdr(
          bmbParameter = cluster.bmbParameter,
          layout = sdrLayoutForIo,
          timing = SdramDeviceInfo.timingFor(md),
          cas = md.casLatency,
          useAlteraCtrl = manufacturer == Manufacturer.Altera,
          clockFreqHz = sys.clkFreq.toLong
        )
        sdrCtrl.ctrl.io.bmb <> cluster.io.bmb
        io.sdram <> sdrCtrl.ctrl.io.sdram
      }

      if (isDdr3) {
        val ddr3Path = MemoryControllerFactory.createDdr3Path(cluster.bmbParameter)
        ddr3Path.bmbBridge.io.bmb <> cluster.io.bmb
        MemoryControllerFactory.wireMig(ddr3Path.adapter, ddr3Mig)
      }

      if (isBram) {
        val bramCtrl = MemoryControllerFactory.createBram(
          bmbParameter = cluster.bmbParameter,
          memSize = mainMemSize,
          initData = mainMemInit
        )
        bramCtrl.ram.io.bus <> cluster.io.bmb
      }

      // ==============================================================
      // UART
      // ==============================================================

      cluster.devicePin[Bool]("uart", "rxd") := io.ser_rxd

      // Per-core UART TX (when enabled)
      if (sys.hasPerCoreUart) {
        for (i <- 0 until sys.cpuCnt) {
          io.jp1_txd(i) := cluster.io.perCoreTxd.get(i)
          io.jp1_wd(i)  := cluster.io.wd(i)(0)
        }
      }

      // ==============================================================
      // Device Wiring (delegated to DeviceTopWiring)
      // ==============================================================

      val wiringCtx = TopWiringContext(config, simulation, systemReset, pllResult, ethPll)
      for ((instanceName, inst) <- sys.effectiveDevices) {
        DeviceTopWirings.forInstance(inst).foreach { wiring =>
          wiring.wireDevice(instanceName, cluster, ioPinMap, deviceClockDomains, wiringCtx)
        }
      }
    }

    // ==================================================================
    // 8. SDR SDRAM Clock Output (FPGA only)
    // ==================================================================

    if (!simulation && isSdr) {
      io.sdram_clk := pllResult.sdramClk.get
    }

    // ==================================================================
    // 9. Board Clock Domain: Heartbeat, Hang Detector, LED, UART TX
    // ==================================================================

    if (simulation) {
      io.ser_txd := mainArea.cluster.devicePin[Bool]("uart", "txd")
      io.led := 0
      io.led(0) := mainArea.cluster.io.wd(0)(0)

    } else if (!isDdr3 && manufacturer.explicitClockPort) {
      val boardClkFreqHz = board.clockFreq.toLong
      val alteraMainHeartbeat = new ClockingArea(effectiveMainCd) {
        val hb = Reg(Bool()) init(False)
        val cnt = Reg(UInt(26 bits)) init(0)
        val halfPeriod = (sys.clkFreq.toLong / 2 - 1).toInt
        cnt := cnt + 1
        when(cnt === halfPeriod) {
          cnt := 0
          hb := ~hb
        }
      }

      io.ser_txd := mainArea.cluster.devicePin[Bool]("uart", "txd")

      if (sys.cpuCnt == 1) {
        io.led(1) := (if (activeHigh) alteraMainHeartbeat.hb else ~alteraMainHeartbeat.hb)
        io.led(0) := (if (activeHigh) mainArea.cluster.io.wd(0)(0) else ~mainArea.cluster.io.wd(0)(0))
        for (i <- 2 until ledCount) {
          io.led(i) := (if (activeHigh) False else True)
        }
      } else {
        for (i <- 0 until sys.cpuCnt.min(ledCount)) {
          io.led(i) := (if (activeHigh) mainArea.cluster.io.wd(i)(0) else ~mainArea.cluster.io.wd(i)(0))
        }
        for (i <- sys.cpuCnt until ledCount) {
          io.led(i) := (if (activeHigh) False else True)
        }
      }

    } else if (isDdr3 || !manufacturer.explicitClockPort) {
      val boardClkFreqHz = board.clockFreq.toLong
      val halfPeriod = (boardClkFreqHz / 2 - 1).toInt
      val hbCounter = Reg(UInt(26 bits)) init(0)
      val heartbeat = Reg(Bool()) init(False)
      hbCounter := hbCounter + 1
      when(hbCounter === halfPeriod) {
        hbCounter := 0
        heartbeat := ~heartbeat
      }

      val wdSync = (0 until sys.cpuCnt).map(i => BufferCC(mainArea.cluster.io.wd(i)(0), init = False))
      val jopTxdSync = BufferCC(mainArea.cluster.devicePin[Bool]("uart", "txd"), init = True)

      if (!isBram) {
        val memStateSync = BufferCC(mainArea.cluster.io.debugMemState, init = U(0, 5 bits))
        val memBusySync = BufferCC(mainArea.cluster.io.memBusy(0), init = False)
        val pcSync = BufferCC(mainArea.cluster.io.pc(0), init = U(0, sys.coreConfigs.head.pcWidth bits))
        val jpcSync = BufferCC(mainArea.cluster.io.jpc(0), init = U(0, 12 bits))

        val hangDet = HangDetector(
          boardClkFreqHz = boardClkFreqHz,
          hasCacheState = isDdr3,
          hasAdapterState = isDdr3
        )
        hangDet.io.memBusy := memBusySync
        hangDet.io.memState := memStateSync
        hangDet.io.pc := pcSync
        hangDet.io.jpc := jpcSync
        if (isDdr3) {
          hangDet.io.cacheState := U(0, 3 bits)
          hangDet.io.adapterState := U(0, 3 bits)
        }
        hangDet.io.jopTxd := jopTxdSync

        io.ser_txd := hangDet.io.muxedTxd

        when(!hangDet.io.hangDetected) {
          io.led(ledCount - 1) := (if (activeHigh) heartbeat else ~heartbeat)
          io.led(0) := (if (activeHigh) wdSync(0) else ~wdSync(0))
        } otherwise {
          io.led(ledCount - 1) := heartbeat
          io.led(0) := ~heartbeat
        }
        for (i <- 1 until ledCount - 1) {
          if (i < sys.cpuCnt)
            io.led(i) := (if (activeHigh) wdSync(i) else ~wdSync(i))
          else
            io.led(i) := (if (activeHigh) False else True)
        }
      } else {
        io.ser_txd := jopTxdSync

        io.led(ledCount - 1) := (if (activeHigh) heartbeat else ~heartbeat)
        io.led(0) := (if (activeHigh) wdSync(0) else ~wdSync(0))
        for (i <- 1 until ledCount - 1) {
          io.led(i) := (if (activeHigh) False else True)
        }
      }
    }

  } // end single-system path

  // ========================================================================
  // Multi-system path (e.g., Wukong DDR3 + SDR dual-subsystem)
  // ========================================================================

  if (isMultiSystem) {

    val resolvedSystems = config.resolvedSystems
    require(resolvedSystems.length == 2, s"Multi-system only supports 2 systems (have ${resolvedSystems.length})")

    // Find DDR3 and SDR systems
    val sys0 = resolvedSystems(0)  // Expected: DDR3
    val sys1 = resolvedSystems(1)  // Expected: SDR

    val memDevice0 = config.resolveMemory(sys0).get
    val memDevice1 = config.resolveMemory(sys1).get
    val isDdr3_0 = memDevice0.memType == MemoryType.SDRAM_DDR3
    val isSdr_1 = memDevice1.memType == MemoryType.SDRAM_SDR
    require(isDdr3_0, s"System 0 '${sys0.name}' must be DDR3 (got ${memDevice0.memType})")
    require(isSdr_1, s"System 1 '${sys1.name}' must be SDR (got ${memDevice1.memType})")

    val boardClk = if (manufacturer.explicitClockPort) io.clk_in else ClockDomain.current.readClockWire

    // Helper: build per-core configs for a system
    def buildMultiCoreConfigs(s: JopSystem, md: MemoryDevice, isSdr: Boolean, isDdr3: Boolean): Seq[JopCoreConfig] = {
      val burstLen = if (s.cpuCnt > 1 && isSdr) 4 else 0
      s.coreConfigs.map(cc => cc.copy(
        memConfig = if (isDdr3) cc.memConfig.copy(
          addressWidth = log2Up((md.sizeBytes / 4).toInt) + 2,
          mainMemSize = md.sizeBytes,
          burstLen = burstLen,
          stackRegionWordsPerCore = 8192
        ) else cc.memConfig.copy(
          burstLen = burstLen,
          stackRegionWordsPerCore = if (board.useStackCache) 8192 else 0
        ),
        supersetJumpTable = s.baseJumpTable,
        clkFreq = s.clkFreq,
        useStackCache = (isDdr3 && s.cpuCnt == 1) || (isSdr && board.useStackCache)
      ))
    }

    // ==================================================================
    // System 0 (DDR3): PLL0 -> MIG -> cd0 -> cluster0
    // ==================================================================

    val pll0Result = Pll.create(board, MemoryType.SDRAM_DDR3, boardClk, systemIndex = 0)

    val ddr3Mig = new MigBlackBox(board.ddr3HasCs)
    io.ddr3_dq    <> ddr3Mig.io.ddr3_dq
    io.ddr3_dqs_n <> ddr3Mig.io.ddr3_dqs_n
    io.ddr3_dqs_p <> ddr3Mig.io.ddr3_dqs_p
    io.ddr3_addr    := ddr3Mig.io.ddr3_addr
    io.ddr3_ba      := ddr3Mig.io.ddr3_ba
    io.ddr3_ras_n   := ddr3Mig.io.ddr3_ras_n
    io.ddr3_cas_n   := ddr3Mig.io.ddr3_cas_n
    io.ddr3_we_n    := ddr3Mig.io.ddr3_we_n
    io.ddr3_reset_n := ddr3Mig.io.ddr3_reset_n
    io.ddr3_ck_p    := ddr3Mig.io.ddr3_ck_p
    io.ddr3_ck_n    := ddr3Mig.io.ddr3_ck_n
    io.ddr3_cke     := ddr3Mig.io.ddr3_cke
    if (board.ddr3HasCs) io.ddr3_cs_n := ddr3Mig.io.ddr3_cs_n
    io.ddr3_dm      := ddr3Mig.io.ddr3_dm
    io.ddr3_odt     := ddr3Mig.io.ddr3_odt
    ddr3Mig.io.sys_clk_i := pll0Result.migSysClk.get
    ddr3Mig.io.clk_ref_i := pll0Result.migRefClk.get
    ddr3Mig.io.sys_rst   := !pll0Result.locked
    ddr3Mig.io.app_sr_req  := False
    ddr3Mig.io.app_ref_req := False
    ddr3Mig.io.app_zq_req  := False

    val cd0 = ClockDomain(
      clock = ddr3Mig.io.ui_clk,
      reset = ddr3Mig.io.ui_clk_sync_rst,
      frequency = FixedFrequency(sys0.clkFreq),
      config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
    )

    val ddr3Area = new ClockingArea(cd0) {
      val coreConfigs0 = buildMultiCoreConfigs(sys0, memDevice0, isSdr = false, isDdr3 = true)

      val cluster = JopCluster(
        cpuCnt = sys0.cpuCnt,
        baseConfig = coreConfigs0.head,
        romInit = Some(romInit),
        ramInit = Some(ramInit),
        jbcInit = Some(Seq.fill(2048)(BigInt(0))),
        perCoreConfigs = Some(coreConfigs0)
      )

      // DDR3 memory path
      val ddr3Path = MemoryControllerFactory.createDdr3Path(cluster.bmbParameter)
      ddr3Path.bmbBridge.io.bmb <> cluster.io.bmb
      MemoryControllerFactory.wireMig(ddr3Path.adapter, ddr3Mig)

      // Primary UART RX
      cluster.devicePin[Bool]("uart", "rxd") := io.ser_rxd
    }

    // ==================================================================
    // System 1 (SDR): PLL1 -> reset gen -> cd1 -> cluster1
    // ==================================================================

    val pll1Result = Pll.create(board, MemoryType.SDRAM_SDR, boardClk, systemIndex = 1)

    val sdr1SystemClk = pll1Result.systemClk.get
    val sdr1Reset = ResetGenerator(pll1Result.locked, sdr1SystemClk)

    val cd1 = ClockDomain(
      clock = sdr1SystemClk,
      reset = sdr1Reset,
      frequency = FixedFrequency(sys1.clkFreq),
      config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
    )

    val sdrArea = new ClockingArea(cd1) {
      val coreConfigs1 = buildMultiCoreConfigs(sys1, memDevice1, isSdr = true, isDdr3 = false)

      val cluster = JopCluster(
        cpuCnt = sys1.cpuCnt,
        baseConfig = coreConfigs1.head,
        romInit = Some(romInit),
        ramInit = Some(ramInit),
        jbcInit = Some(Seq.fill(2048)(BigInt(0))),
        perCoreConfigs = Some(coreConfigs1)
      )

      // SDR SDRAM controller
      val sdrLayout = SdramDeviceInfo.layoutFor(memDevice1)
      val sdrTiming = SdramDeviceInfo.timingFor(memDevice1)
      val sdrCtrl = MemoryControllerFactory.createSdr(
        bmbParameter = cluster.bmbParameter,
        layout = sdrLayout,
        timing = sdrTiming,
        cas = memDevice1.casLatency,
        useAlteraCtrl = manufacturer == Manufacturer.Altera,
        clockFreqHz = sys1.clkFreq.toLong
      )
      sdrCtrl.ctrl.io.bmb <> cluster.io.bmb
      io.sdram <> sdrCtrl.ctrl.io.sdram

      // Secondary UART RX
      cluster.devicePin[Bool]("uart", "rxd") := io.ser_rxd_1
    }

    // SDR SDRAM clock output
    io.sdram_clk := pll1Result.sdramClk.get

    // ==================================================================
    // Board Clock Domain: Heartbeat + Dual CDC + Dual HangDetector
    // ==================================================================

    val boardClkFreqHz = board.clockFreq.toLong
    val halfPeriod = (boardClkFreqHz / 2 - 1).toInt
    val hbCounter = Reg(UInt(26 bits)) init(0)
    val heartbeat = Reg(Bool()) init(False)
    hbCounter := hbCounter + 1
    when(hbCounter === halfPeriod) {
      hbCounter := 0
      heartbeat := ~heartbeat
    }

    // System 0 (DDR3) CDC + HangDetector
    val wd0Sync = BufferCC(ddr3Area.cluster.io.wd(0)(0), init = False)
    val txd0Sync = BufferCC(ddr3Area.cluster.devicePin[Bool]("uart", "txd"), init = True)
    val memState0Sync = BufferCC(ddr3Area.cluster.io.debugMemState, init = U(0, 5 bits))
    val memBusy0Sync = BufferCC(ddr3Area.cluster.io.memBusy(0), init = False)
    val pc0Sync = BufferCC(ddr3Area.cluster.io.pc(0), init = U(0, sys0.coreConfigs.head.pcWidth bits))
    val jpc0Sync = BufferCC(ddr3Area.cluster.io.jpc(0), init = U(0, 12 bits))

    val hangDet0 = HangDetector(
      boardClkFreqHz = boardClkFreqHz,
      hasCacheState = true,
      hasAdapterState = true
    )
    hangDet0.io.memBusy := memBusy0Sync
    hangDet0.io.memState := memState0Sync
    hangDet0.io.pc := pc0Sync
    hangDet0.io.jpc := jpc0Sync
    hangDet0.io.cacheState := U(0, 3 bits)
    hangDet0.io.adapterState := U(0, 3 bits)
    hangDet0.io.jopTxd := txd0Sync
    io.ser_txd := hangDet0.io.muxedTxd

    // System 1 (SDR) CDC + HangDetector
    val wd1Sync = BufferCC(sdrArea.cluster.io.wd(0)(0), init = False)
    val txd1Sync = BufferCC(sdrArea.cluster.devicePin[Bool]("uart", "txd"), init = True)
    val memState1Sync = BufferCC(sdrArea.cluster.io.debugMemState, init = U(0, 5 bits))
    val memBusy1Sync = BufferCC(sdrArea.cluster.io.memBusy(0), init = False)
    val pc1Sync = BufferCC(sdrArea.cluster.io.pc(0), init = U(0, sys1.coreConfigs.head.pcWidth bits))
    val jpc1Sync = BufferCC(sdrArea.cluster.io.jpc(0), init = U(0, 12 bits))

    val hangDet1 = HangDetector(
      boardClkFreqHz = boardClkFreqHz,
      hasCacheState = false,
      hasAdapterState = false
    )
    hangDet1.io.memBusy := memBusy1Sync
    hangDet1.io.memState := memState1Sync
    hangDet1.io.pc := pc1Sync
    hangDet1.io.jpc := jpc1Sync
    hangDet1.io.jopTxd := txd1Sync
    io.ser_txd_1 := hangDet1.io.muxedTxd

    // LEDs: LED[0] = DDR3 WD/hang, LED[1] = SDR WD/hang
    when(!hangDet0.io.hangDetected) {
      io.led(0) := (if (activeHigh) wd0Sync else ~wd0Sync)
    } otherwise {
      io.led(0) := ~heartbeat
    }
    when(!hangDet1.io.hangDetected) {
      io.led(ledCount - 1) := (if (activeHigh) wd1Sync else ~wd1Sync)
    } otherwise {
      io.led(ledCount - 1) := ~heartbeat
    }
    for (i <- 1 until ledCount - 1) {
      io.led(i) := (if (activeHigh) False else True)
    }

  } // end multi-system path
}
