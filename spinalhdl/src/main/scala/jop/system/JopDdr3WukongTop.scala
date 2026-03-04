package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.io.InOutWrapper
import jop.ddr3._
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import jop.pipeline.JumpTableInitData

/**
 * MIG BlackBox for Wukong board (no ddr3_cs_n pin).
 * Wukong MIG config has emrCSSelection=Disable, so the MIG IP
 * does not expose a cs_n port. Otherwise identical to MigBlackBox.
 */
class WukongMigBlackBox extends BlackBox {
  val io = new Bundle {
    val ddr3_dq      = inout(Analog(Bits(16 bits)))
    val ddr3_dqs_n   = inout(Analog(Bits(2 bits)))
    val ddr3_dqs_p   = inout(Analog(Bits(2 bits)))
    val ddr3_addr    = out Bits(14 bits)
    val ddr3_ba      = out Bits(3 bits)
    val ddr3_ras_n   = out Bool()
    val ddr3_cas_n   = out Bool()
    val ddr3_we_n    = out Bool()
    val ddr3_reset_n = out Bool()
    val ddr3_ck_p    = out Bits(1 bits)
    val ddr3_ck_n    = out Bits(1 bits)
    val ddr3_cke     = out Bits(1 bits)
    // No ddr3_cs_n — Wukong has no CS pin routed
    val ddr3_dm      = out Bits(2 bits)
    val ddr3_odt     = out Bits(1 bits)

    val sys_clk_i = in Bool()
    val clk_ref_i = in Bool()

    val app_addr         = in Bits(28 bits)
    val app_cmd          = in Bits(3 bits)
    val app_en           = in Bool()
    val app_wdf_data     = in Bits(128 bits)
    val app_wdf_end      = in Bool()
    val app_wdf_mask     = in Bits(16 bits)
    val app_wdf_wren     = in Bool()
    val app_rd_data      = out Bits(128 bits)
    val app_rd_data_end  = out Bool()
    val app_rd_data_valid = out Bool()
    val app_rdy          = out Bool()
    val app_wdf_rdy      = out Bool()
    val app_sr_req       = in Bool()
    val app_ref_req      = in Bool()
    val app_zq_req       = in Bool()
    val app_sr_active    = out Bool()
    val app_ref_ack      = out Bool()
    val app_zq_ack       = out Bool()

    val ui_clk             = out Bool()
    val ui_clk_sync_rst    = out Bool()
    val init_calib_complete = out Bool()
    val device_temp        = out Bits(12 bits)

    val sys_rst = in Bool()
  }

  setBlackBoxName("mig_7series_0")
  noIoPrefix()
}

/**
 * JOP DDR3 FPGA Top-Level for QMTECH XC7A100T Wukong V3
 *
 * Architecture:
 *   Board 50 MHz -> ClkWiz -> clk_100 (MIG sys) + clk_200 (MIG ref)
 *   MIG -> ui_clk (100 MHz) + ui_clk_sync_rst
 *
 *   All JOP logic runs in MIG ui_clk domain (100 MHz):
 *     JopCluster.bmb -> BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MIG -> DDR3
 *
 * Board differences from Alchitry AU:
 *   - 50 MHz board clock (vs 100 MHz)
 *   - No ddr3_cs_n pin (Wukong MIG disables CS)
 *   - 2 LEDs (vs 8)
 *   - UART: ser_txd/ser_rxd (CH340N, vs usb_rx/usb_tx)
 *
 * Serial-boot: same download protocol as AU DDR3 FPGA.
 */
case class JopDdr3WukongTop(
  cpuCnt: Int = 1,
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  jumpTable: JumpTableInitData = JumpTableInitData.serial
) extends Component {
  require(cpuCnt >= 1, "cpuCnt must be at least 1")

  val io = new Bundle {
    val led      = out Bits(2 bits)
    val ser_txd  = out Bool()
    val ser_rxd  = in Bool()

    // DDR3 pins (directly connected to MIG BlackBox)
    val ddr3_dq      = inout(Analog(Bits(16 bits)))
    val ddr3_dqs_n   = inout(Analog(Bits(2 bits)))
    val ddr3_dqs_p   = inout(Analog(Bits(2 bits)))
    val ddr3_addr    = out Bits(14 bits)
    val ddr3_ba      = out Bits(3 bits)
    val ddr3_ras_n   = out Bool()
    val ddr3_cas_n   = out Bool()
    val ddr3_we_n    = out Bool()
    val ddr3_reset_n = out Bool()
    val ddr3_ck_p    = out Bits(1 bits)
    val ddr3_ck_n    = out Bits(1 bits)
    val ddr3_cke     = out Bits(1 bits)
    // No ddr3_cs_n
    val ddr3_dm      = out Bits(2 bits)
    val ddr3_odt     = out Bits(1 bits)
  }

  noIoPrefix()

  // ========================================================================
  // Clock Wizard: Board 50 MHz -> 100 MHz (MIG sys) + 200 MHz (MIG ref)
  // Reuses ClkWizBlackBox from jop.ddr3 — same port interface, just
  // configured for 50 MHz input instead of 100 MHz.
  // ========================================================================

  val clkWiz = new ClkWizBlackBox
  clkWiz.io.clk_in := ClockDomain.current.readClockWire
  // ClkWiz reset is active-HIGH despite port name "resetn" (Xilinx default polarity).
  // Default CD reset is active-LOW, so invert.
  clkWiz.io.resetn := !ClockDomain.current.readResetWire

  // ========================================================================
  // MIG DDR3 Controller (no CS pin variant)
  // ========================================================================

  val mig = new WukongMigBlackBox

  // DDR3 pin connections
  io.ddr3_dq    <> mig.io.ddr3_dq
  io.ddr3_dqs_n <> mig.io.ddr3_dqs_n
  io.ddr3_dqs_p <> mig.io.ddr3_dqs_p
  io.ddr3_addr    := mig.io.ddr3_addr
  io.ddr3_ba      := mig.io.ddr3_ba
  io.ddr3_ras_n   := mig.io.ddr3_ras_n
  io.ddr3_cas_n   := mig.io.ddr3_cas_n
  io.ddr3_we_n    := mig.io.ddr3_we_n
  io.ddr3_reset_n := mig.io.ddr3_reset_n
  io.ddr3_ck_p    := mig.io.ddr3_ck_p
  io.ddr3_ck_n    := mig.io.ddr3_ck_n
  io.ddr3_cke     := mig.io.ddr3_cke
  io.ddr3_dm      := mig.io.ddr3_dm
  io.ddr3_odt     := mig.io.ddr3_odt

  // MIG clock inputs
  mig.io.sys_clk_i := clkWiz.io.clk_100
  mig.io.clk_ref_i := clkWiz.io.clk_200
  mig.io.sys_rst   := !clkWiz.io.locked

  // Disable optional maintenance requests
  mig.io.app_sr_req  := False
  mig.io.app_ref_req := False
  mig.io.app_zq_req  := False

  // ========================================================================
  // MIG UI Clock Domain (100 MHz)
  // ========================================================================

  val uiCd = ClockDomain(
    clock = mig.io.ui_clk,
    reset = mig.io.ui_clk_sync_rst,
    frequency = FixedFrequency(100 MHz),
    config = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  // ========================================================================
  // Main Design Area (MIG ui_clk, 100 MHz)
  // ========================================================================

  val mainArea = new ClockingArea(uiCd) {

    // ==================================================================
    // JOP Cluster: N cores with arbiter + CmpSync
    // ==================================================================

    val burstLen = if (cpuCnt > 1) 4 else 0

    val cluster = JopCluster(
      cpuCnt = cpuCnt,
      baseConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(
          addressWidth = 28,
          mainMemSize = 256L * 1024 * 1024,  // 256MB DDR3 (MT41K128M16JT)
          burstLen = burstLen,
          stackRegionWordsPerCore = 8192   // 32KB per core for stack spill
        ),
        jumpTable = jumpTable,
        clkFreqHz = 100000000L,
        useStackCache = true
      ),
      romInit = Some(romInit),
      ramInit = Some(ramInit),
      jbcInit = Some(Seq.fill(2048)(BigInt(0)))
    )

    // ==================================================================
    // DDR3 Memory Path: JopCluster.bmb -> BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MIG
    // ==================================================================

    val cacheAddrWidth = 28  // BMB byte address width (addressWidth + 2)
    val cacheDataWidth = 128 // MIG native data width

    val bmbBridge = new BmbCacheBridge(cluster.bmbParameter, cacheAddrWidth, cacheDataWidth)
    val cache = new LruCacheCore(CacheConfig(addrWidth = cacheAddrWidth, dataWidth = cacheDataWidth, setCount = 512))
    val adapter = new CacheToMigAdapter

    // JopCluster BMB -> BmbCacheBridge
    bmbBridge.io.bmb <> cluster.io.bmb

    // BmbCacheBridge -> LruCacheCore
    cache.io.frontend.req << bmbBridge.io.cache.req
    bmbBridge.io.cache.rsp << cache.io.frontend.rsp

    // LruCacheCore -> CacheToMigAdapter
    adapter.io.cmd.valid         := cache.io.memCmd.valid
    adapter.io.cmd.payload.addr  := cache.io.memCmd.payload.addr
    adapter.io.cmd.payload.write := cache.io.memCmd.payload.write
    adapter.io.cmd.payload.wdata := cache.io.memCmd.payload.data
    adapter.io.cmd.payload.wmask := cache.io.memCmd.payload.mask
    cache.io.memCmd.ready        := adapter.io.cmd.ready

    cache.io.memRsp.valid         := adapter.io.rsp.valid
    cache.io.memRsp.payload.data  := adapter.io.rsp.payload.rdata
    cache.io.memRsp.payload.error := adapter.io.rsp.payload.error
    adapter.io.rsp.ready          := cache.io.memRsp.ready

    // CacheToMigAdapter -> MIG
    adapter.io.app_rdy           := mig.io.app_rdy
    adapter.io.app_wdf_rdy       := mig.io.app_wdf_rdy
    adapter.io.app_rd_data       := mig.io.app_rd_data
    adapter.io.app_rd_data_valid := mig.io.app_rd_data_valid

    mig.io.app_addr     := adapter.io.app_addr
    mig.io.app_cmd      := adapter.io.app_cmd
    mig.io.app_en       := adapter.io.app_en
    mig.io.app_wdf_data := adapter.io.app_wdf_data
    mig.io.app_wdf_end  := adapter.io.app_wdf_end
    mig.io.app_wdf_mask := adapter.io.app_wdf_mask
    mig.io.app_wdf_wren := adapter.io.app_wdf_wren

    // ==================================================================
    // UART
    // ==================================================================

    cluster.io.rxd := io.ser_rxd
  }

  // ========================================================================
  // Board Clock Domain Area (default CD: heartbeat, calib sync, LED driver)
  // ========================================================================

  // Heartbeat: ~1 Hz toggle (25M cycles at 50 MHz board clock)
  val hbCounter = Reg(UInt(25 bits)) init(0)
  val heartbeat = Reg(Bool()) init(False)
  hbCounter := hbCounter + 1
  when(hbCounter === 24999999) {
    hbCounter := 0
    heartbeat := ~heartbeat
  }

  val migCalibSync = BufferCC(mig.io.init_calib_complete, init = False)

  // Debug: memory controller state, busy, and WD — cross-domain sync from MIG ui_clk
  val memStateSync = BufferCC(mainArea.cluster.io.debugMemState, init = U(0, 5 bits))
  val memBusySync = BufferCC(mainArea.cluster.io.memBusy(0), init = False)
  val wdSync = (0 until cpuCnt).map(i => BufferCC(mainArea.cluster.io.wd(i)(0), init = False))

  // Cache/adapter debug: cross-domain sync from MIG ui_clk
  val cacheStateSync = BufferCC(mainArea.cache.io.debugState, init = U(0, 3 bits))
  val adapterStateSync = BufferCC(mainArea.adapter.io.debugState, init = U(0, 3 bits))

  // Pipeline debug: pc and jpc — cross-domain sync from MIG ui_clk
  val pcSync = BufferCC(mainArea.cluster.io.pc(0), init = U(0, 11 bits))
  val jpcSync = BufferCC(mainArea.cluster.io.jpc(0), init = U(0, 12 bits))

  // Hang detector: count cycles while memBusy stays True.
  // After ~335ms (2^24 @ 50MHz board clock), switch LED display and trigger UART dump.
  val hangCounter = Reg(UInt(25 bits)) init(0)
  val hangDetected = Reg(Bool()) init(False)
  when(memBusySync) {
    when(!hangCounter.msb) {
      hangCounter := hangCounter + 1
    } otherwise {
      hangDetected := True
    }
  } otherwise {
    hangCounter := 0
  }

  // Latch memState at hang detection
  val hangMemState = Reg(UInt(5 bits)) init(0)
  when(!hangDetected) {
    hangMemState := memStateSync
  }

  // ========================================================================
  // Diagnostic UART (board clock domain, 50 MHz)
  // ========================================================================

  val diagUart = DiagUart(clockFreqHz = 50000000, baudRate = 1000000)
  diagUart.io.trigger      := hangDetected
  diagUart.io.memState     := memStateSync
  diagUart.io.pc           := pcSync
  diagUart.io.jpc          := jpcSync
  diagUart.io.cacheState   := cacheStateSync
  diagUart.io.adapterState := adapterStateSync

  // UART TX MUX: JOP's UART during normal operation, DiagUart when hung.
  val jopTxdSync = BufferCC(mainArea.cluster.io.txd, init = True)
  io.ser_txd := Mux(hangDetected, diagUart.io.txd, jopTxdSync)

  // ========================================================================
  // LED Display (2 LEDs only)
  // ========================================================================

  // LED[0] = WD (proves Java running), LED[1] = heartbeat (proves clock running)
  // On hang: both LEDs alternate blink
  when(!hangDetected) {
    io.led(1) := heartbeat
    io.led(0) := wdSync(0)
  } otherwise {
    io.led(1) := heartbeat
    io.led(0) := ~heartbeat
  }
}

/**
 * Generate Verilog for JopDdr3WukongTop (single-core)
 */
object JopDdr3WukongTopVerilog extends App {
  val romFilePath = "asm/generated/serial/mem_rom.dat"
  val ramFilePath = "asm/generated/serial/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = LOW
    )
  ).generate(InOutWrapper(JopDdr3WukongTop(
    cpuCnt = 1,
    romInit = romData,
    ramInit = ramData
  )))

  println("Generated: spinalhdl/generated/JopDdr3WukongTop.v")
}
