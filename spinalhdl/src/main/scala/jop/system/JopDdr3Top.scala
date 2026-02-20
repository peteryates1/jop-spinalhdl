package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.io.InOutWrapper
import jop.ddr3._
import jop.io.{BmbSys, BmbUart}
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import jop.pipeline.JumpTableInitData

/**
 * JOP DDR3 FPGA Top-Level for Alchitry AU V2 (Artix-7 + DDR3)
 *
 * Architecture:
 *   Board 100 MHz -> ClkWiz -> clk_100 (MIG sys) + clk_200 (MIG ref)
 *   MIG -> ui_clk (100 MHz) + ui_clk_sync_rst
 *
 *   All JOP logic runs in MIG ui_clk domain (100 MHz):
 *     JopCore.bmb -> BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MIG -> DDR3
 *     JopCore.io  -> BmbSys (slave 0) + BmbUart (slave 1)
 *
 * The default clock domain (clk/reset ports) is the board 100 MHz clock.
 * Reset is active-high (active-low button inverted in SpinalConfig).
 *
 * Serial-boot: same download protocol as SDRAM FPGA.
 *
 * @param romInit Microcode ROM initialization data (serial-boot)
 * @param ramInit Stack RAM initialization data (serial-boot)
 */
case class JopDdr3Top(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  jumpTable: JumpTableInitData = JumpTableInitData.serial
) extends Component {

  val io = new Bundle {
    val led      = out Bits(8 bits)
    val usb_rx   = in Bool()
    val usb_tx   = out Bool()

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
    val ddr3_cs_n    = out Bits(1 bits)
    val ddr3_dm      = out Bits(2 bits)
    val ddr3_odt     = out Bits(1 bits)
  }

  noIoPrefix()

  // ========================================================================
  // Clock Wizard: Board 100 MHz -> 100 MHz (MIG sys) + 200 MHz (MIG ref)
  // Board clock comes from the default clock domain (clk port).
  // ========================================================================

  val clkWiz = new ClkWizBlackBox
  clkWiz.io.clk_in := ClockDomain.current.readClockWire
  // ClkWiz reset is active-HIGH despite port name "resetn" (Xilinx default polarity).
  // Default CD reset is active-LOW, so invert.
  clkWiz.io.resetn := !ClockDomain.current.readResetWire

  // ========================================================================
  // MIG DDR3 Controller
  // ========================================================================

  val mig = new MigBlackBox

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
  io.ddr3_cs_n    := mig.io.ddr3_cs_n
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

    // JOP core config: addressWidth=26 (28-bit BMB byte address), burstLen=8 for BC fill
    val config = JopCoreConfig(
      memConfig = JopMemoryConfig(addressWidth = 26, burstLen = 0),
      jumpTable = jumpTable
    )

    // JBC init: empty (zeros) — BC_FILL loads bytecodes dynamically from DDR3
    val jbcInit = Seq.fill(2048)(BigInt(0))

    // JOP Core
    val jopCore = JopCore(
      config = config,
      romInit = Some(romInit),
      ramInit = Some(ramInit),
      jbcInit = Some(jbcInit)
    )

    // ==================================================================
    // DDR3 Memory Path: JopCore.bmb -> BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MIG
    // ==================================================================

    val cacheAddrWidth = 28  // BMB byte address width (config.memConfig.addressWidth + 2)
    val cacheDataWidth = 128 // MIG native data width

    val bmbBridge = new BmbCacheBridge(config.memConfig.bmbParameter, cacheAddrWidth, cacheDataWidth)
    val cache = new LruCacheCore(CacheConfig(addrWidth = cacheAddrWidth, dataWidth = cacheDataWidth))
    val adapter = new CacheToMigAdapter

    // JopCore BMB -> BmbCacheBridge
    bmbBridge.io.bmb.cmd.valid := jopCore.io.bmb.cmd.valid
    bmbBridge.io.bmb.cmd.payload := jopCore.io.bmb.cmd.payload
    jopCore.io.bmb.cmd.ready := bmbBridge.io.bmb.cmd.ready
    jopCore.io.bmb.rsp.valid := bmbBridge.io.bmb.rsp.valid
    jopCore.io.bmb.rsp.payload := bmbBridge.io.bmb.rsp.payload
    bmbBridge.io.bmb.rsp.ready := jopCore.io.bmb.rsp.ready

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
    // Interrupts / Exceptions
    // ==================================================================

    jopCore.io.irq    := False
    jopCore.io.irqEna := False

    // ==================================================================
    // I/O Slaves
    // ==================================================================

    val ioSubAddr = jopCore.io.ioAddr(3 downto 0)
    val ioSlaveId = jopCore.io.ioAddr(5 downto 4)

    // System I/O (slave 0)
    val bmbSys = BmbSys(clkFreqHz = 100000000L)
    bmbSys.io.addr   := ioSubAddr
    bmbSys.io.rd     := jopCore.io.ioRd && ioSlaveId === 0
    bmbSys.io.wr     := jopCore.io.ioWr && ioSlaveId === 0
    bmbSys.io.wrData := jopCore.io.ioWrData

    // UART (slave 1)
    val bmbUart = BmbUart()
    bmbUart.io.addr   := ioSubAddr
    bmbUart.io.rd     := jopCore.io.ioRd && ioSlaveId === 1
    bmbUart.io.wr     := jopCore.io.ioWr && ioSlaveId === 1
    bmbUart.io.wrData := jopCore.io.ioWrData
    io.usb_tx         := bmbUart.io.txd
    bmbUart.io.rxd    := io.usb_rx

    // I/O read mux
    val ioRdData = Bits(32 bits)
    ioRdData := 0
    switch(ioSlaveId) {
      is(0) { ioRdData := bmbSys.io.rdData }
      is(1) { ioRdData := bmbUart.io.rdData }
    }
    jopCore.io.ioRdData := ioRdData

    // Exception signal from BmbSys
    jopCore.io.exc := bmbSys.io.exc

    // Debug RAM (tie off)
    jopCore.io.debugRamAddr := 0
  }

  // ========================================================================
  // Board Clock Domain Area (default CD: heartbeat, calib sync, LED driver)
  // ========================================================================

  // Heartbeat: ~1 Hz toggle
  val hbCounter = Reg(UInt(26 bits)) init(0)
  val heartbeat = Reg(Bool()) init(False)
  hbCounter := hbCounter + 1
  when(hbCounter === 49999999) {
    hbCounter := 0
    heartbeat := ~heartbeat
  }

  val migCalibSync = BufferCC(mig.io.init_calib_complete, init = False)

  // Debug: memory controller state, busy, and WD — cross-domain sync from MIG ui_clk
  val memStateSync = BufferCC(mainArea.jopCore.io.debugMemState, init = U(0, 5 bits))
  val memBusySync = BufferCC(mainArea.jopCore.io.memBusy, init = False)
  val wdSync = BufferCC(mainArea.bmbSys.io.wd(0), init = False)

  // Cache/adapter debug: cross-domain sync from MIG ui_clk
  val cacheStateSync = BufferCC(mainArea.cache.io.debugState, init = U(0, 3 bits))
  val adapterStateSync = BufferCC(mainArea.adapter.io.debugState, init = U(0, 3 bits))

  // Hang detector: count cycles while memBusy stays True.
  // After ~167ms (2^24 @ 100MHz board clock), switch LED display to cache/adapter state.
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

  // LED display: normal mode vs hang diagnostic mode
  // Normal:    LED[7:3]=memState, LED[2]=memBusy, LED[1]=heartbeat, LED[0]=wd
  // Hang diag: LED[7:5]=cacheState, LED[4:2]=adapterState, LED[1]=heartbeat, LED[0]=~heartbeat (fast blink = hang)
  when(!hangDetected) {
    io.led(7 downto 3) := memStateSync.asBits
    io.led(2) := memBusySync
    io.led(1) := heartbeat
    io.led(0) := wdSync
  } otherwise {
    io.led(7 downto 5) := cacheStateSync.asBits
    io.led(4 downto 2) := adapterStateSync.asBits
    io.led(1) := heartbeat
    io.led(0) := ~heartbeat  // Both blink = hang indicator
  }
}

/**
 * Generate Verilog for JopDdr3Top
 */
object JopDdr3TopVerilog extends App {
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/serial/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/serial/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = LOW
    )
  ).generate(InOutWrapper(JopDdr3Top(romData, ramData)))

  println("Generated: spinalhdl/generated/JopDdr3Top.v")
}
