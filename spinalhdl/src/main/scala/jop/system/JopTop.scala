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

  private val sys = config.system
  private val board = config.assembly.fpgaBoard
  private val manufacturer = config.fpgaFamily.manufacturer
  private val memDevice = config.resolveMemory(sys)
  private val memType = memDevice.map(_.memType).getOrElse(MemoryType.BRAM)
  private val isSdr = memType == MemoryType.SDRAM_SDR
  private val isDdr3 = memType == MemoryType.SDRAM_DDR3
  private val isBram = memType == MemoryType.BRAM
  private val ledCount = board.ledCount
  private val activeHigh = board.ledActiveHigh

  // Backward-compatible entity name
  setDefinitionName(config.entityName)

  // ========================================================================
  // I/O Bundle (conditional ports based on board and memory type)
  // ========================================================================

  val io = new Bundle {
    // Clock input (Altera: explicit port; Xilinx: uses default clock domain; Sim: none)
    val clk_in = (!simulation && manufacturer.explicitClockPort) generate (in Bool())

    // UART
    val ser_txd = out Bool()
    val ser_rxd = in Bool()

    // LEDs
    val led = out Bits(ledCount bits)

    // SDR SDRAM (conditional)
    val sdram_clk = isSdr generate (out Bool())
    val sdram = isSdr generate master(SdramInterface(sdrLayout))

    // DDR3 (conditional — Au V2 has CS pin, Wukong does not)
    val ddr3HasCs = isDdr3 && board.ddr3HasCs
    val ddr3_dq      = isDdr3 generate inout(Analog(Bits(16 bits)))
    val ddr3_dqs_n   = isDdr3 generate inout(Analog(Bits(2 bits)))
    val ddr3_dqs_p   = isDdr3 generate inout(Analog(Bits(2 bits)))
    val ddr3_addr    = isDdr3 generate (out Bits(14 bits))
    val ddr3_ba      = isDdr3 generate (out Bits(3 bits))
    val ddr3_ras_n   = isDdr3 generate (out Bool())
    val ddr3_cas_n   = isDdr3 generate (out Bool())
    val ddr3_we_n    = isDdr3 generate (out Bool())
    val ddr3_reset_n = isDdr3 generate (out Bool())
    val ddr3_ck_p    = isDdr3 generate (out Bits(1 bits))
    val ddr3_ck_n    = isDdr3 generate (out Bits(1 bits))
    val ddr3_cke     = isDdr3 generate (out Bits(1 bits))
    val ddr3_cs_n    = ddr3HasCs generate (out Bits(1 bits))
    val ddr3_dm      = isDdr3 generate (out Bits(2 bits))
    val ddr3_odt     = isDdr3 generate (out Bits(1 bits))

    // Per-core UART TX (optional, for SMP debug)
    val jp1_txd = sys.hasPerCoreUart generate (out Bits(sys.cpuCnt bits))
    val jp1_wd  = sys.hasPerCoreUart generate (out Bits(sys.cpuCnt bits))
  }

  noIoPrefix()

  // Auto-generated device IO ports from DeviceTopWiring.topPins.
  // Adding a new device type only requires a DeviceTopWiring implementation — no JopTop changes.
  // Public for simulation access (e.g. dut.ioPinMap("vga_hs")).
  val ioPinMap: Map[String, Data] = {
    val manufacturer = config.fpgaFamily.manufacturer
    val ports = scala.collection.mutable.LinkedHashMap.empty[String, Data]
    for ((_, inst) <- sys.effectiveDevices) {
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

  private def sdrLayout: SdramLayout =
    SdramDeviceInfo.layoutFor(memDevice.get)

  // ========================================================================
  // 1-6. Clock, PLL, Reset, MIG, Clock Domains (FPGA only — skipped in sim)
  // ========================================================================

  // In simulation: use default clock domain directly, no PLL/reset/MIG
  // On FPGA: full PLL -> reset -> clock domain pipeline

  // Forward declarations for FPGA-only values (null in sim)
  private var pllResult: PllResult = null
  private var systemReset: Bool = null
  private var ddr3Mig: MigBlackBox = null
  private var ethPll: EthPll = null

  private val effectiveMainCd: ClockDomain = if (simulation) {
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
  val deviceClockDomains: DeviceClockDomains = {
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

  // ========================================================================
  // 7. Main Design Area
  // ========================================================================

  val mainArea = new ClockingArea(effectiveMainCd) {

    // Build per-core configs
    // Burst reads prevent A$ interleaving corruption on SDR SDRAM SMP.
    // DDR3 doesn't need bursts: LruCacheCore serializes all access.
    val burstLen = if (sys.cpuCnt > 1 && isSdr) 4
                   else 0

    // Per-core configs: devices are already distributed by sys.coreConfigs
    // (core 0 gets system devices, cores 1+ get empty unless overridden).
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
      // TODO: DDR3 SMP hangs with stack cache — investigate DMA+arbiter interaction
      useStackCache = (isDdr3 && sys.cpuCnt == 1) || (isSdr && board.useStackCache)
    ))

    // ==================================================================
    // JOP Cluster
    // ==================================================================

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

    // ==================================================================
    // Memory Controller
    // ==================================================================

    if (isSdr) {
      val md = memDevice.get
      val sdrCtrl = MemoryControllerFactory.createSdr(
        bmbParameter = cluster.bmbParameter,
        layout = sdrLayout,
        timing = sdrTiming,
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

    // ==================================================================
    // UART
    // ==================================================================

    cluster.devicePin[Bool]("uart", "rxd") := io.ser_rxd

    // Per-core UART TX (when enabled)
    if (sys.hasPerCoreUart) {
      for (i <- 0 until sys.cpuCnt) {
        io.jp1_txd(i) := cluster.io.perCoreTxd.get(i)
        io.jp1_wd(i)  := cluster.io.wd(i)(0)
      }
    }

    // ==================================================================
    // Device Wiring (delegated to DeviceTopWiring)
    // ==================================================================

    val wiringCtx = TopWiringContext(config, simulation, systemReset, pllResult, ethPll)
    for ((instanceName, inst) <- sys.effectiveDevices) {
      DeviceTopWirings.forInstance(inst).foreach { wiring =>
        wiring.wireDevice(instanceName, cluster, ioPinMap, deviceClockDomains, wiringCtx)
      }
    }
  }

  // ========================================================================
  // 8. SDR SDRAM Clock Output (FPGA only)
  // ========================================================================

  if (!simulation && isSdr) {
    io.sdram_clk := pllResult.sdramClk.get
  }

  // ========================================================================
  // 9. Board Clock Domain: Heartbeat, Hang Detector, LED, UART TX
  // ========================================================================

  if (simulation) {
    // --- Simulation: direct wiring, no PLL/CDC/hang-detector ---
    io.ser_txd := mainArea.cluster.devicePin[Bool]("uart", "txd")
    io.led := 0
    io.led(0) := mainArea.cluster.io.wd(0)(0)

  } else if (!isDdr3 && manufacturer.explicitClockPort) {
    // --- Altera non-DDR3: everything in mainArea clock domain ---
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

    // UART TX: direct from cluster (same clock domain, no CDC)
    io.ser_txd := mainArea.cluster.devicePin[Bool]("uart", "txd")

    // LED driver (active-low for QMTECH/CYC5000)
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
    // --- DDR3 or Xilinx: board clock domain with CDC ---
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

  // ========================================================================
  // Helper: SDR SDRAM Timing
  // ========================================================================

  private def sdrTiming: SdramTimings =
    SdramDeviceInfo.timingFor(memDevice.get)
}
