package jop.system

import jop.config._

import spinal.core._
import spinal.lib._
import spinal.lib.io.{InOutWrapper, TriState}
import spinal.lib.memory.sdram.SdramLayout
import spinal.lib.memory.sdram.sdr._
import jop.memory.W9864G6JT
import jop.utils.JopFileLoader
import jop.memory.{JopMemoryConfig, BmbSdramCtrl32}
import jop.pipeline.JumpTableInitData
import jop.debug.{DebugConfig, DebugUart}
import jop.ddr3.{MigBlackBox, StartupE2}
import jop.system.pll.{Pll, PllResult}
import jop.system.memory.{MemoryControllerFactory, SdrMemCtrl, Ddr3MemCtrl}
import jop.io.{DeviceTopWirings, TopWiringContext, DeviceClockDomains}

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
  private val isAltera = config.fpgaFamily.manufacturer == Manufacturer.Altera
  private val isXilinx = config.fpgaFamily.manufacturer == Manufacturer.Xilinx
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
    val clk_in = (!simulation && isAltera) generate (in Bool())

    // UART
    val ser_txd = out Bool()
    val ser_rxd = in Bool()

    // LEDs
    val led = out Bits(ledCount bits)

    // SDR SDRAM (conditional)
    val sdram_clk = isSdr generate (out Bool())
    val sdram = isSdr generate master(SdramInterface(sdrLayout))

    // DDR3 (conditional) — Alchitry Au V2 variant (with CS pin)
    val ddr3_dq      = (isDdr3 && board.name == "alchitry-au-v2") generate inout(Analog(Bits(16 bits)))
    val ddr3_dqs_n   = (isDdr3 && board.name == "alchitry-au-v2") generate inout(Analog(Bits(2 bits)))
    val ddr3_dqs_p   = (isDdr3 && board.name == "alchitry-au-v2") generate inout(Analog(Bits(2 bits)))
    val ddr3_addr    = (isDdr3 && board.name == "alchitry-au-v2") generate (out Bits(14 bits))
    val ddr3_ba      = (isDdr3 && board.name == "alchitry-au-v2") generate (out Bits(3 bits))
    val ddr3_ras_n   = (isDdr3 && board.name == "alchitry-au-v2") generate (out Bool())
    val ddr3_cas_n   = (isDdr3 && board.name == "alchitry-au-v2") generate (out Bool())
    val ddr3_we_n    = (isDdr3 && board.name == "alchitry-au-v2") generate (out Bool())
    val ddr3_reset_n = (isDdr3 && board.name == "alchitry-au-v2") generate (out Bool())
    val ddr3_ck_p    = (isDdr3 && board.name == "alchitry-au-v2") generate (out Bits(1 bits))
    val ddr3_ck_n    = (isDdr3 && board.name == "alchitry-au-v2") generate (out Bits(1 bits))
    val ddr3_cke     = (isDdr3 && board.name == "alchitry-au-v2") generate (out Bits(1 bits))
    val ddr3_cs_n    = (isDdr3 && board.name == "alchitry-au-v2") generate (out Bits(1 bits))
    val ddr3_dm      = (isDdr3 && board.name == "alchitry-au-v2") generate (out Bits(2 bits))
    val ddr3_odt     = (isDdr3 && board.name == "alchitry-au-v2") generate (out Bits(1 bits))

    // DDR3 — Wukong variant (no CS pin)
    val wk_ddr3_dq      = (isDdr3 && board.name == "qmtech-wukong-xc7a100t") generate inout(Analog(Bits(16 bits)))
    val wk_ddr3_dqs_n   = (isDdr3 && board.name == "qmtech-wukong-xc7a100t") generate inout(Analog(Bits(2 bits)))
    val wk_ddr3_dqs_p   = (isDdr3 && board.name == "qmtech-wukong-xc7a100t") generate inout(Analog(Bits(2 bits)))
    val wk_ddr3_addr    = (isDdr3 && board.name == "qmtech-wukong-xc7a100t") generate (out Bits(14 bits))
    val wk_ddr3_ba      = (isDdr3 && board.name == "qmtech-wukong-xc7a100t") generate (out Bits(3 bits))
    val wk_ddr3_ras_n   = (isDdr3 && board.name == "qmtech-wukong-xc7a100t") generate (out Bool())
    val wk_ddr3_cas_n   = (isDdr3 && board.name == "qmtech-wukong-xc7a100t") generate (out Bool())
    val wk_ddr3_we_n    = (isDdr3 && board.name == "qmtech-wukong-xc7a100t") generate (out Bool())
    val wk_ddr3_reset_n = (isDdr3 && board.name == "qmtech-wukong-xc7a100t") generate (out Bool())
    val wk_ddr3_ck_p    = (isDdr3 && board.name == "qmtech-wukong-xc7a100t") generate (out Bits(1 bits))
    val wk_ddr3_ck_n    = (isDdr3 && board.name == "qmtech-wukong-xc7a100t") generate (out Bits(1 bits))
    val wk_ddr3_cke     = (isDdr3 && board.name == "qmtech-wukong-xc7a100t") generate (out Bits(1 bits))
    val wk_ddr3_dm      = (isDdr3 && board.name == "qmtech-wukong-xc7a100t") generate (out Bits(2 bits))
    val wk_ddr3_odt     = (isDdr3 && board.name == "qmtech-wukong-xc7a100t") generate (out Bits(1 bits))

    // Ethernet (EP4CGX150 with DB_FPGA only)
    val e_txd    = sys.hasEth generate (out Bits(sys.phyDataWidth bits))
    val e_txen   = sys.hasEth generate (out Bool())
    val e_txer   = sys.hasEth generate (out Bool())
    val e_txc    = (sys.hasEth && !sys.ethGmii) generate (in Bool())
    val e_gtxc   = sys.hasEth generate (out Bool())
    val e_rxd    = sys.hasEth generate (in Bits(sys.phyDataWidth bits))
    val e_rxdv   = sys.hasEth generate (in Bool())
    val e_rxer   = sys.hasEth generate (in Bool())
    val e_rxc    = sys.hasEth generate (in Bool())
    val e_mdc    = sys.hasEth generate (out Bool())
    val e_mdio   = sys.hasEth generate master(TriState(Bool()))
    val e_resetn = sys.hasEth generate (out Bool())

    // VGA (optional)
    val vga_hs = sys.hasVga generate (out Bool())
    val vga_vs = sys.hasVga generate (out Bool())
    val vga_r  = sys.hasVga generate (out Bits(5 bits))
    val vga_g  = sys.hasVga generate (out Bits(6 bits))
    val vga_b  = sys.hasVga generate (out Bits(5 bits))

    // SD Native (optional)
    val sd_clk   = sys.hasSdNative generate (out Bool())
    val sd_cmd   = sys.hasSdNative generate master(TriState(Bool()))
    val sd_dat_0 = sys.hasSdNative generate master(TriState(Bool()))
    val sd_dat_1 = sys.hasSdNative generate master(TriState(Bool()))
    val sd_dat_2 = sys.hasSdNative generate master(TriState(Bool()))
    val sd_dat_3 = sys.hasSdNative generate master(TriState(Bool()))
    val sd_cd    = sys.hasSdNative generate (in Bool())

    // SD SPI (optional)
    val sd_spi_clk  = sys.hasSdSpi generate (out Bool())
    val sd_spi_mosi = sys.hasSdSpi generate (out Bool())
    val sd_spi_miso = sys.hasSdSpi generate (in Bool())
    val sd_spi_cs   = sys.hasSdSpi generate (out Bool())
    val sd_spi_cd   = sys.hasSdSpi generate (in Bool())

    // Config flash SPI (optional)
    val cf_dclk  = (sys.hasConfigFlash && isAltera) generate (out Bool())
    val cf_ncs   = (sys.hasConfigFlash && isAltera) generate (out Bool())
    val cf_asdo  = (sys.hasConfigFlash && isAltera) generate (out Bool())
    val cf_data0 = (sys.hasConfigFlash && isAltera) generate (in Bool())
    val cf_mosi  = (sys.hasConfigFlash && isXilinx) generate (out Bool())
    val cf_miso  = (sys.hasConfigFlash && isXilinx) generate (in Bool())
    val cf_cs    = (sys.hasConfigFlash && isXilinx) generate (out Bool())

    // Per-core UART TX (optional, for SMP debug)
    val jp1_txd = sys.perCoreUart generate (out Bits(sys.cpuCnt bits))
    val jp1_wd  = sys.perCoreUart generate (out Bits(sys.cpuCnt bits))
  }

  noIoPrefix()

  // Build io pin map for DeviceTopWiring dispatch
  private val ioPinMap: Map[String, Data] = {
    var m = Map.empty[String, Data]
    if (sys.hasEth) {
      m ++= Map(
        "e_txd" -> io.e_txd, "e_txen" -> io.e_txen, "e_txer" -> io.e_txer,
        "e_gtxc" -> io.e_gtxc, "e_rxd" -> io.e_rxd, "e_rxdv" -> io.e_rxdv,
        "e_rxer" -> io.e_rxer, "e_rxc" -> io.e_rxc, "e_mdc" -> io.e_mdc,
        "e_mdio" -> io.e_mdio, "e_resetn" -> io.e_resetn)
      if (!sys.ethGmii) m += "e_txc" -> io.e_txc
    }
    if (sys.hasVga) m ++= Map(
      "vga_hs" -> io.vga_hs, "vga_vs" -> io.vga_vs,
      "vga_r" -> io.vga_r, "vga_g" -> io.vga_g, "vga_b" -> io.vga_b)
    if (sys.hasSdNative) m ++= Map(
      "sd_clk" -> io.sd_clk, "sd_cmd" -> io.sd_cmd,
      "sd_dat_0" -> io.sd_dat_0, "sd_dat_1" -> io.sd_dat_1,
      "sd_dat_2" -> io.sd_dat_2, "sd_dat_3" -> io.sd_dat_3,
      "sd_cd" -> io.sd_cd)
    if (sys.hasSdSpi) m ++= Map(
      "sd_spi_clk" -> io.sd_spi_clk, "sd_spi_mosi" -> io.sd_spi_mosi,
      "sd_spi_miso" -> io.sd_spi_miso, "sd_spi_cs" -> io.sd_spi_cs,
      "sd_spi_cd" -> io.sd_spi_cd)
    if (sys.hasConfigFlash && isAltera) m ++= Map(
      "cf_dclk" -> io.cf_dclk, "cf_ncs" -> io.cf_ncs,
      "cf_asdo" -> io.cf_asdo, "cf_data0" -> io.cf_data0)
    if (sys.hasConfigFlash && isXilinx) m ++= Map(
      "cf_miso" -> io.cf_miso)
    m
  }

  // ========================================================================
  // SDR SDRAM Layout (needed for io bundle, resolved at class construction)
  // ========================================================================

  private def sdrLayout: SdramLayout = {
    memDevice.map { md =>
      SdramLayout(
        generation = spinal.lib.memory.sdram.SdramGeneration.SDR,
        bankWidth = md.bankWidth,
        columnWidth = md.columnWidth,
        rowWidth = md.rowWidth,
        dataWidth = md.dataWidth
      )
    }.getOrElse(W9825G6JH6.layout)  // fallback
  }

  // ========================================================================
  // 1-6. Clock, PLL, Reset, MIG, Clock Domains (FPGA only — skipped in sim)
  // ========================================================================

  // In simulation: use default clock domain directly, no PLL/reset/MIG
  // On FPGA: full PLL -> reset -> clock domain pipeline

  // Forward declarations for FPGA-only values (null in sim)
  private var pllResult: PllResult = null
  private var systemReset: Bool = null
  private var auMig: MigBlackBox = null
  private var wkMig: WukongMigBlackBox = null
  private var ethPll: EthPll = null

  private val effectiveMainCd: ClockDomain = if (simulation) {
    ClockDomain.current
  } else {
    // 1. Board Clock Source
    val boardClk = if (isAltera) io.clk_in else ClockDomain.current.readClockWire

    // 2. PLL
    pllResult = Pll.create(board.name, memType, boardClk)

    // 3. Ethernet PLL (EP4CGX150 GMII only)
    if (sys.ethGmii && board.name == "qmtech-ep4cgx150") {
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
    if (isDdr3 && board.name == "alchitry-au-v2") {
      auMig = new MigBlackBox
      io.ddr3_dq    <> auMig.io.ddr3_dq
      io.ddr3_dqs_n <> auMig.io.ddr3_dqs_n
      io.ddr3_dqs_p <> auMig.io.ddr3_dqs_p
      io.ddr3_addr    := auMig.io.ddr3_addr
      io.ddr3_ba      := auMig.io.ddr3_ba
      io.ddr3_ras_n   := auMig.io.ddr3_ras_n
      io.ddr3_cas_n   := auMig.io.ddr3_cas_n
      io.ddr3_we_n    := auMig.io.ddr3_we_n
      io.ddr3_reset_n := auMig.io.ddr3_reset_n
      io.ddr3_ck_p    := auMig.io.ddr3_ck_p
      io.ddr3_ck_n    := auMig.io.ddr3_ck_n
      io.ddr3_cke     := auMig.io.ddr3_cke
      io.ddr3_cs_n    := auMig.io.ddr3_cs_n
      io.ddr3_dm      := auMig.io.ddr3_dm
      io.ddr3_odt     := auMig.io.ddr3_odt
      auMig.io.sys_clk_i := pllResult.migSysClk.get
      auMig.io.clk_ref_i := pllResult.migRefClk.get
      auMig.io.sys_rst   := !pllResult.locked
      auMig.io.app_sr_req  := False
      auMig.io.app_ref_req := False
      auMig.io.app_zq_req  := False
    }

    if (isDdr3 && board.name == "qmtech-wukong-xc7a100t") {
      wkMig = new WukongMigBlackBox
      io.wk_ddr3_dq    <> wkMig.io.ddr3_dq
      io.wk_ddr3_dqs_n <> wkMig.io.ddr3_dqs_n
      io.wk_ddr3_dqs_p <> wkMig.io.ddr3_dqs_p
      io.wk_ddr3_addr    := wkMig.io.ddr3_addr
      io.wk_ddr3_ba      := wkMig.io.ddr3_ba
      io.wk_ddr3_ras_n   := wkMig.io.ddr3_ras_n
      io.wk_ddr3_cas_n   := wkMig.io.ddr3_cas_n
      io.wk_ddr3_we_n    := wkMig.io.ddr3_we_n
      io.wk_ddr3_reset_n := wkMig.io.ddr3_reset_n
      io.wk_ddr3_ck_p    := wkMig.io.ddr3_ck_p
      io.wk_ddr3_ck_n    := wkMig.io.ddr3_ck_n
      io.wk_ddr3_cke     := wkMig.io.ddr3_cke
      io.wk_ddr3_dm      := wkMig.io.ddr3_dm
      io.wk_ddr3_odt     := wkMig.io.ddr3_odt
      wkMig.io.sys_clk_i := pllResult.migSysClk.get
      wkMig.io.clk_ref_i := pllResult.migRefClk.get
      wkMig.io.sys_rst   := !pllResult.locked
      wkMig.io.app_sr_req  := False
      wkMig.io.app_ref_req := False
      wkMig.io.app_zq_req  := False
    }

    // DDR3 UI Clock Domain
    val ddr3MainCd: ClockDomain = if (isDdr3) {
      val migUiClk = if (board.name == "alchitry-au-v2") auMig.io.ui_clk
                     else wkMig.io.ui_clk
      val migUiRst = if (board.name == "alchitry-au-v2") auMig.io.ui_clk_sync_rst
                     else wkMig.io.ui_clk_sync_rst
      ClockDomain(
        clock = migUiClk,
        reset = migUiRst,
        frequency = FixedFrequency(sys.clkFreq),
        config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
      )
    } else null

    if (isDdr3) ddr3MainCd else mainClockDomain
  }

  // 6. Device Clock Domains (delegated to DeviceTopWiring)
  val deviceClockDomains: DeviceClockDomains = {
    val ctx = TopWiringContext(config, isAltera, isXilinx, simulation, systemReset, pllResult, ethPll)
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
    val burstLen = if (sys.cpuCnt > 1 && (isSdr || isDdr3)) 4
                   else 0

    val coreConfigs = sys.coreConfigs.map(cc => cc.copy(
      memConfig = if (isDdr3) cc.memConfig.copy(
        addressWidth = 28,
        mainMemSize = 256L * 1024 * 1024,
        burstLen = burstLen,
        stackRegionWordsPerCore = 8192
      ) else if (isBram) cc.memConfig.copy(
        mainMemSize = mainMemSize,
        burstLen = 0
      ) else cc.memConfig.copy(
        burstLen = burstLen,
        stackRegionWordsPerCore = if (board.name == "qmtech-wukong-xc7a100t") 8192 else 0
      ),
      supersetJumpTable = sys.baseJumpTable,
      clkFreq = sys.clkFreq,
      devices = sys.effectiveDevices,
      useStackCache = isDdr3 || (isSdr && board.name == "qmtech-wukong-xc7a100t")
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
      perCoreUart = sys.perCoreUart,
      perCoreConfigs = if (coreConfigs.length > 1 || coreConfigs.head != JopCoreConfig())
        Some(coreConfigs) else None
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
        isAltera = isAltera,
        clockFreqHz = sys.clkFreq.toLong
      )
      sdrCtrl.ctrl.io.bmb <> cluster.io.bmb
      io.sdram <> sdrCtrl.ctrl.io.sdram
    }

    if (isDdr3) {
      val ddr3Path = MemoryControllerFactory.createDdr3Path(cluster.bmbParameter)
      ddr3Path.bmbBridge.io.bmb <> cluster.io.bmb

      if (board.name == "alchitry-au-v2") {
        MemoryControllerFactory.wireMig(ddr3Path.adapter, auMig)
      } else {
        MemoryControllerFactory.wireWukongMig(ddr3Path.adapter, wkMig)
      }
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
    if (sys.perCoreUart) {
      for (i <- 0 until sys.cpuCnt) {
        io.jp1_txd(i) := cluster.io.perCoreTxd.get(i)
        io.jp1_wd(i)  := cluster.io.wd(i)(0)
      }
    }

    // ==================================================================
    // Device Wiring (delegated to DeviceTopWiring)
    // ==================================================================

    val wiringCtx = TopWiringContext(config, isAltera, isXilinx, simulation, systemReset, pllResult, ethPll)
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

  } else if (!isDdr3 && isAltera) {
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

  } else if (isDdr3 || isXilinx) {
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

  private def sdrTiming: SdramTimings = {
    memDevice.map { md =>
      md.name match {
        case "W9825G6JH6"  => W9825G6JH6.timingGrade7
        case "IS42S16160G" => W9825G6JH6.timingGrade7  // Same geometry/timing as W9825G6JH6
        case "W9864G6JT"   => W9864G6JT.timingGrade6
        case other => throw new RuntimeException(s"No SDRAM timing for device '$other'")
      }
    }.getOrElse(W9825G6JH6.timingGrade7)
  }
}
