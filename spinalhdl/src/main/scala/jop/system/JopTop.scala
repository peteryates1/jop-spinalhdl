package jop.system

import jop.config._

import spinal.core._
import spinal.lib._
import spinal.lib.io.{InOutWrapper, TriState}
import spinal.lib.memory.sdram.SdramLayout
import spinal.lib.memory.sdram.sdr._
import jop.memory.W9864G6JT
import spinal.lib.com.eth._
import jop.utils.JopFileLoader
import jop.memory.{JopMemoryConfig, BmbSdramCtrl32}
import jop.pipeline.JumpTableInitData
import jop.debug.{DebugConfig, DebugUart}
import jop.ddr3.{MigBlackBox, StartupE2}
import jop.system.pll.{Pll, PllResult}
import jop.system.memory.{MemoryControllerFactory, SdrMemCtrl, Ddr3MemCtrl}

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
    val e_txd    = sys.ioConfig.hasEth generate (out Bits(sys.ioConfig.phyDataWidth bits))
    val e_txen   = sys.ioConfig.hasEth generate (out Bool())
    val e_txer   = sys.ioConfig.hasEth generate (out Bool())
    val e_txc    = (sys.ioConfig.hasEth && !sys.ioConfig.ethGmii) generate (in Bool())
    val e_gtxc   = sys.ioConfig.hasEth generate (out Bool())
    val e_rxd    = sys.ioConfig.hasEth generate (in Bits(sys.ioConfig.phyDataWidth bits))
    val e_rxdv   = sys.ioConfig.hasEth generate (in Bool())
    val e_rxer   = sys.ioConfig.hasEth generate (in Bool())
    val e_rxc    = sys.ioConfig.hasEth generate (in Bool())
    val e_mdc    = sys.ioConfig.hasEth generate (out Bool())
    val e_mdio   = sys.ioConfig.hasEth generate master(TriState(Bool()))
    val e_resetn = sys.ioConfig.hasEth generate (out Bool())

    // VGA (optional)
    val vga_hs = sys.ioConfig.hasVga generate (out Bool())
    val vga_vs = sys.ioConfig.hasVga generate (out Bool())
    val vga_r  = sys.ioConfig.hasVga generate (out Bits(5 bits))
    val vga_g  = sys.ioConfig.hasVga generate (out Bits(6 bits))
    val vga_b  = sys.ioConfig.hasVga generate (out Bits(5 bits))

    // SD Native (optional)
    val sd_clk   = sys.ioConfig.hasSdNative generate (out Bool())
    val sd_cmd   = sys.ioConfig.hasSdNative generate master(TriState(Bool()))
    val sd_dat_0 = sys.ioConfig.hasSdNative generate master(TriState(Bool()))
    val sd_dat_1 = sys.ioConfig.hasSdNative generate master(TriState(Bool()))
    val sd_dat_2 = sys.ioConfig.hasSdNative generate master(TriState(Bool()))
    val sd_dat_3 = sys.ioConfig.hasSdNative generate master(TriState(Bool()))
    val sd_cd    = sys.ioConfig.hasSdNative generate (in Bool())

    // SD SPI (optional)
    val sd_spi_clk  = sys.ioConfig.hasSdSpi generate (out Bool())
    val sd_spi_mosi = sys.ioConfig.hasSdSpi generate (out Bool())
    val sd_spi_miso = sys.ioConfig.hasSdSpi generate (in Bool())
    val sd_spi_cs   = sys.ioConfig.hasSdSpi generate (out Bool())
    val sd_spi_cd   = sys.ioConfig.hasSdSpi generate (in Bool())

    // Config flash SPI (optional)
    val cf_dclk  = (sys.ioConfig.hasConfigFlash && isAltera) generate (out Bool())
    val cf_ncs   = (sys.ioConfig.hasConfigFlash && isAltera) generate (out Bool())
    val cf_asdo  = (sys.ioConfig.hasConfigFlash && isAltera) generate (out Bool())
    val cf_data0 = (sys.ioConfig.hasConfigFlash && isAltera) generate (in Bool())
    val cf_mosi  = (sys.ioConfig.hasConfigFlash && isXilinx) generate (out Bool())
    val cf_miso  = (sys.ioConfig.hasConfigFlash && isXilinx) generate (in Bool())
    val cf_cs    = (sys.ioConfig.hasConfigFlash && isXilinx) generate (out Bool())

    // Per-core UART TX (optional, for SMP debug)
    val jp1_txd = sys.perCoreUart generate (out Bits(sys.cpuCnt bits))
    val jp1_wd  = sys.perCoreUart generate (out Bits(sys.cpuCnt bits))
  }

  noIoPrefix()

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
    if (sys.ioConfig.ethGmii && board.name == "qmtech-ep4cgx150") {
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

  // 6. Ethernet/VGA Clock Domains (FPGA only, not in sim)

  val ethTxClk = if (!simulation && sys.ioConfig.ethGmii && ethPll != null) ethPll.io.c0
                 else if (!simulation && sys.ioConfig.ethGmii && pllResult != null && pllResult.ethClk.isDefined) pllResult.ethClk.get
                 else if (!simulation && sys.ioConfig.hasEth) io.e_txc
                 else null

  val ethTxCd = (!simulation && sys.ioConfig.hasEth) generate {
    val txBootCd = ClockDomain(ethTxClk, config = ClockDomainConfig(resetKind = BOOT))
    val txReset = ResetCtrl.asyncAssertSyncDeassert(
      input = systemReset,
      clockDomain = txBootCd,
      inputPolarity = HIGH,
      outputPolarity = HIGH
    )
    ClockDomain(
      clock = ethTxClk,
      reset = txReset,
      config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
    )
  }

  val ethRxClk = if (!simulation && sys.ioConfig.hasEth) io.e_rxc else null

  val ethRxCd = (!simulation && sys.ioConfig.hasEth) generate {
    val rxBootCd = ClockDomain(ethRxClk, config = ClockDomainConfig(resetKind = BOOT))
    val rxReset = ResetCtrl.asyncAssertSyncDeassert(
      input = systemReset,
      clockDomain = rxBootCd,
      inputPolarity = HIGH,
      outputPolarity = HIGH
    )
    ClockDomain(
      clock = ethRxClk,
      reset = rxReset,
      config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
    )
  }

  val vgaCd = sys.ioConfig.hasVga generate {
    if (simulation) {
      ClockDomain.external("vgaCd", withReset = false,
        config = ClockDomainConfig(resetKind = BOOT))
    } else {
      val vgaBootCd = ClockDomain(pllResult.vgaClk.get, config = ClockDomainConfig(resetKind = BOOT))
      val vgaReset = ResetCtrl.asyncAssertSyncDeassert(
        input = systemReset,
        clockDomain = vgaBootCd,
        inputPolarity = HIGH,
        outputPolarity = HIGH
      )
      ClockDomain(
        clock = pllResult.vgaClk.get,
        reset = vgaReset,
        config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
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
      ioConfig = sys.ioConfig,
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
      ethTxCd = if (!simulation && sys.ioConfig.hasEth) Some(ethTxCd) else None,
      ethRxCd = if (!simulation && sys.ioConfig.hasEth) Some(ethRxCd) else None,
      vgaCd = if (sys.ioConfig.hasVga) Some(vgaCd) else None,
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
    // Ethernet (optional)
    // ==================================================================

    if (cluster.devicePins.contains("eth")) {
      val dataWidth = sys.ioConfig.phyDataWidth

      // GTX clock (125 MHz from EthPll on Altera, PLL ethClk on Xilinx)
      if (sys.ioConfig.ethGmii) {
        io.e_gtxc := ethTxClk
      } else {
        io.e_gtxc := False
      }

      // MDIO
      io.e_mdc := cluster.devicePin[Bool]("eth", "mdc")
      io.e_mdio.write := cluster.devicePin[Bool]("eth", "mdioOut")
      io.e_mdio.writeEnable := cluster.devicePin[Bool]("eth", "mdioOe")
      cluster.devicePin[Bool]("eth", "mdioIn") := io.e_mdio.read

      // PHY interface from cluster's devicePins (Eth.io contains PhyIo as "phy")
      val ethPhyIo = cluster.devicePins("eth").elements.find(_._1 == "phy").get._2.asInstanceOf[PhyIo]

      ethPhyIo.colision := False
      ethPhyIo.busy := False

      // PHY hardware reset
      val phyRstCnt = Reg(UInt(20 bits)) init(0)
      val phyRstDone = phyRstCnt.andR
      when(!phyRstDone) { phyRstCnt := phyRstCnt + 1 }
      io.e_resetn := phyRstDone && cluster.devicePin[Bool]("eth", "phyReset")

      // TX adapter
      val txArea = new ClockingArea(ethTxCd) {
        val interframe = MacTxInterFrame(dataWidth)
        interframe.io.input << ethPhyIo.tx
        io.e_txen := RegNext(interframe.io.output.valid) init(False)
        io.e_txd  := RegNext(interframe.io.output.fragment.data) init(0)
        io.e_txer := False
      }

      // RX adapter
      val rxArea = new ClockingArea(ethRxCd) {
        val unbuffered = Flow(PhyRx(dataWidth))
        unbuffered.valid := io.e_rxdv
        unbuffered.data  := io.e_rxd
        unbuffered.error := io.e_rxer

        val buffered = unbuffered.stage()

        val rxFlow = Flow(Fragment(PhyRx(dataWidth)))
        rxFlow.valid          := buffered.valid
        rxFlow.fragment       := buffered.payload
        rxFlow.last           := !unbuffered.valid && buffered.valid

        ethPhyIo.rx << rxFlow.toStream
      }
    }

    // ==================================================================
    // VGA (optional)
    // ==================================================================

    // VGA: check both vgaText and vgaDma device names
    val vgaDeviceName = if (cluster.devicePins.contains("vgaDma")) "vgaDma"
                        else if (cluster.devicePins.contains("vgaText")) "vgaText"
                        else ""
    if (vgaDeviceName.nonEmpty) {
      io.vga_hs := cluster.devicePin[Bool](vgaDeviceName, "vgaHsync")
      io.vga_vs := cluster.devicePin[Bool](vgaDeviceName, "vgaVsync")
      io.vga_r  := cluster.devicePin[Bits](vgaDeviceName, "vgaR")
      io.vga_g  := cluster.devicePin[Bits](vgaDeviceName, "vgaG")
      io.vga_b  := cluster.devicePin[Bits](vgaDeviceName, "vgaB")
    }

    // ==================================================================
    // SD Native (optional)
    // ==================================================================

    if (cluster.devicePins.contains("sdNative")) {
      val sdPins = cluster.devicePins("sdNative")
      val sdCmd = sdPins.elements.find(_._1 == "sdCmd").get._2.asInstanceOf[Bundle]
      val sdDat = sdPins.elements.find(_._1 == "sdDat").get._2.asInstanceOf[Bundle]

      io.sd_clk := cluster.devicePin[Bool]("sdNative", "sdClk")
      io.sd_cmd.write       := sdCmd.elements.find(_._1 == "write").get._2.asInstanceOf[Bool]
      io.sd_cmd.writeEnable := sdCmd.elements.find(_._1 == "writeEnable").get._2.asInstanceOf[Bool]
      sdCmd.elements.find(_._1 == "read").get._2.asInstanceOf[Bool]  := io.sd_cmd.read
      val sdDatWrite   = sdDat.elements.find(_._1 == "write").get._2.asInstanceOf[Bits]
      val sdDatWriteEn = sdDat.elements.find(_._1 == "writeEnable").get._2.asInstanceOf[Bits]
      val sdDatRead    = sdDat.elements.find(_._1 == "read").get._2.asInstanceOf[Bits]
      io.sd_dat_0.write       := sdDatWrite(0)
      io.sd_dat_0.writeEnable := sdDatWriteEn(0)
      io.sd_dat_1.write       := sdDatWrite(1)
      io.sd_dat_1.writeEnable := sdDatWriteEn(1)
      io.sd_dat_2.write       := sdDatWrite(2)
      io.sd_dat_2.writeEnable := sdDatWriteEn(2)
      io.sd_dat_3.write       := sdDatWrite(3)
      io.sd_dat_3.writeEnable := sdDatWriteEn(3)
      sdDatRead := io.sd_dat_3.read ## io.sd_dat_2.read ## io.sd_dat_1.read ## io.sd_dat_0.read
      cluster.devicePin[Bool]("sdNative", "sdCd") := io.sd_cd
    }

    // ==================================================================
    // SD SPI (optional)
    // ==================================================================

    if (cluster.devicePins.contains("sdSpi")) {
      io.sd_spi_clk  := cluster.devicePin[Bool]("sdSpi", "sclk")
      io.sd_spi_mosi := cluster.devicePin[Bool]("sdSpi", "mosi")
      io.sd_spi_cs   := cluster.devicePin[Bool]("sdSpi", "cs")
      cluster.devicePin[Bool]("sdSpi", "miso") := io.sd_spi_miso
      cluster.devicePin[Bool]("sdSpi", "cd")   := io.sd_spi_cd
    }

    // ==================================================================
    // Config Flash — Altera (optional)
    // ==================================================================

    if (sys.ioConfig.hasConfigFlash && isAltera && cluster.devicePins.contains("cfgFlash")) {
      io.cf_dclk := cluster.devicePin[Bool]("cfgFlash", "dclk")
      io.cf_ncs  := cluster.devicePin[Bool]("cfgFlash", "ncs")
      io.cf_asdo := cluster.devicePin[Bool]("cfgFlash", "asdo")
      cluster.devicePin[Bool]("cfgFlash", "data0") := io.cf_data0
      cluster.devicePin[Bool]("cfgFlash", "flashReady") := True
    }

    // ==================================================================
    // Config Flash — Xilinx (optional, STARTUPE2 + SPI mux)
    // TODO: Flash boot on Xilinx is complex (SPI reset FSM).
    //       For now, just expose the cluster's SPI signals directly.
    // ==================================================================

    if (sys.ioConfig.hasConfigFlash && isXilinx && cluster.devicePins.contains("cfgFlash")) {
      cluster.devicePin[Bool]("cfgFlash", "data0") := io.cf_miso
      cluster.devicePin[Bool]("cfgFlash", "flashReady") := True

      // Expose cluster SPI for top-level mux wiring
      val cfClusterDclk = cluster.devicePin[Bool]("cfgFlash", "dclk")
      val cfClusterNcs  = cluster.devicePin[Bool]("cfgFlash", "ncs")
      val cfClusterAsdo = cluster.devicePin[Bool]("cfgFlash", "asdo")
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
