package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.io.InOutWrapper
import spinal.lib.com.uart._
import jop.ddr3._
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
 *     JopCluster.bmb -> BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MIG -> DDR3
 *     JopCore internal: BmbSys (slave 0) + BmbUart (slave 1)
 *
 * When cpuCnt = 1 (single-core):
 *   - One JopCore, BMB goes directly to BmbCacheBridge
 *   - No CmpSync
 *   - LED[0] = WD bit 0, LED[1] = heartbeat
 *
 * When cpuCnt >= 2 (SMP):
 *   - N JopCore instances with BmbArbiter -> shared BmbCacheBridge
 *   - CmpSync for global lock synchronization
 *   - LED[0] = core 0 WD, LED[1] = core 1 WD
 *   - Entity name set to JopDdr3SmpTop for Vivado backward compat
 *
 * The default clock domain (clk/reset ports) is the board 100 MHz clock.
 * Reset is active-high (active-low button inverted in SpinalConfig).
 *
 * Serial-boot: same download protocol as SDRAM FPGA.
 *
 * @param cpuCnt  Number of CPU cores (1 = single-core, 2+ = SMP)
 * @param romInit Microcode ROM initialization data (serial-boot)
 * @param ramInit Stack RAM initialization data (serial-boot)
 */
case class JopDdr3Top(
  cpuCnt: Int = 1,
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  jumpTable: JumpTableInitData = JumpTableInitData.serial,
  ioConfig: IoConfig = IoConfig(),
  fpuMode: FpuMode.FpuMode = FpuMode.Software,
  perCoreConfigs: Option[Seq[JopCoreConfig]] = None
) extends Component {
  require(cpuCnt >= 1, "cpuCnt must be at least 1")

  // Preserve existing Vivado entity names
  if (cpuCnt >= 2) setDefinitionName("JopDdr3SmpTop")

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

    // Config flash SPI pins (active after FPGA configuration)
    // Note: no cf_sck pin — on Xilinx 7 Series, CCLK is driven via STARTUPE2
    val cf_mosi = if (ioConfig.hasConfigFlash) Some(out Bool()) else None
    val cf_miso = if (ioConfig.hasConfigFlash) Some(in Bool()) else None
    val cf_cs   = if (ioConfig.hasConfigFlash) Some(out Bool()) else None
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

  // Board-clock register: set True by diagnostic FSM when SPI mux switches
  // to BmbConfigFlash. Defined here (before mainArea) so it can be
  // BufferCC'd inside the ui_clk domain.
  val spiDiagDone = Reg(Bool()) init(False)

  // ========================================================================
  // Main Design Area (MIG ui_clk, 100 MHz)
  // ========================================================================

  val mainArea = new ClockingArea(uiCd) {

    // ==================================================================
    // JOP Cluster: N cores with arbiter + CmpSync
    // ==================================================================

    // burstLen=0 (pipelined single-word) for single-core; burstLen=4 for SMP
    // (burstLen=0 + SMP has an arbiter interleaving issue with BC_FILL)
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
        ioConfig = ioConfig,
        useStackCache = true,              // Enable 3-bank rotating stack cache
        fpuMode = fpuMode
      ),
      romInit = Some(romInit),
      ramInit = Some(ramInit),
      jbcInit = Some(Seq.fill(2048)(BigInt(0))),
      perCoreConfigs = perCoreConfigs
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

    cluster.io.rxd := io.usb_rx

    // ==================================================================
    // Config Flash (optional, for flash boot)
    // ==================================================================

    // Expose cluster SPI signals for top-level mux (board-clock diagnostic
    // takes priority until done, then cluster's BmbConfigFlash takes over).
    // STARTUPE2 is instantiated in board clock domain outside mainArea.
    val cfClusterDclk = if (ioConfig.hasConfigFlash) Some(cluster.io.cfDclk.get)  else None
    val cfClusterNcs  = if (ioConfig.hasConfigFlash) Some(cluster.io.cfNcs.get)   else None
    val cfClusterAsdo = if (ioConfig.hasConfigFlash) Some(cluster.io.cfAsdo.get)  else None

    if (ioConfig.hasConfigFlash) {
      cluster.io.cfData0.get := io.cf_miso.get

      // Synchronize spiDiagDone (board clock) → ui_clk domain.
      // Microcode polls this via status bit 1 before starting flash I/O,
      // ensuring the SPI mux has switched to BmbConfigFlash outputs.
      val spiDiagDoneSync = BufferCC(spiDiagDone, init = False)
      cluster.io.cfFlashReady.get := spiDiagDoneSync
    }
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

  // ========================================================================
  // STARTUPE2 + Board-Clock SPI Reset (board clock domain)
  // ========================================================================
  //
  // On Xilinx 7 Series, CCLK is driven via STARTUPE2 USRCCLKO.
  //
  // CRITICAL: After JTAG reprogramming, the flash is in QPI mode and
  // BmbConfigFlash (ui_clk domain) cannot reset it via STARTUPE2 alone.
  // A board-clock-domain SPI reset must run first to bring the flash
  // back to SPI mode. Then the mux hands CCLK/CS/MOSI to BmbConfigFlash.

  var spiDiagEos: Option[Bool] = None  // EOS from STARTUPE2
  var diagJedec: Option[Bits] = None   // JEDEC response from diagnostic FSM
  var diagDataWord: Option[Bits] = None // First flash word from diagnostic read

  if (ioConfig.hasConfigFlash) {
    // Board-clock SPI bit-bang registers (for reset sequence only)
    val spiClk      = Reg(Bool()) init(False)
    val spiCs       = Reg(Bool()) init(True)   // idle high
    val spiMosi     = Reg(Bool()) init(True)
    val spiShiftOut = Reg(Bits(8 bits)) init(0)
    val spiClkCnt   = Reg(UInt(5 bits)) init(0)
    val spiBitCnt   = Reg(UInt(3 bits)) init(0)

    // STARTUPE2
    val startup = StartupE2()
    startup.io.CLK       := False
    startup.io.GSR       := False
    startup.io.GTS       := False
    startup.io.KEYCLEARB := True
    startup.io.PACK      := False
    startup.io.USRCCLKTS := False
    startup.io.USRDONEO  := True
    startup.io.USRDONETS := True

    // Mux: board-clock reset FSM drives SPI until done, then BmbConfigFlash takes over
    startup.io.USRCCLKO := Mux(spiDiagDone,
      mainArea.cfClusterDclk.get,
      spiClk)
    io.cf_cs.get   := Mux(spiDiagDone, mainArea.cfClusterNcs.get,  spiCs)
    io.cf_mosi.get := Mux(spiDiagDone, mainArea.cfClusterAsdo.get, spiMosi)

    spiDiagEos = Some(startup.io.EOS)

    // Full reset + JEDEC + data read diagnostic FSM:
    // Phase 1: RSTQIO(0xFF) + RSTEN(0x66) + RST(0x99) + 100μs wait
    // Phase 2: JEDEC_ID(0x9F) read → confirms flash responds
    // Phase 3: READ_DATA(0x03) at 0x240000 → captures first word for debug
    object SpiDiagState extends SpinalEnum {
      val IDLE, CS_LO, SPI_RISE, SPI_FALL, BYTE_DONE, CS_HI,
          GAP, WAIT_RST,
          JEDEC_CS_LO, JEDEC_RISE, JEDEC_FALL, JEDEC_BYTE_DONE, JEDEC_CS_HI,
          DATA_GAP, DATA_CS_LO, DATA_RISE, DATA_FALL, DATA_BYTE_DONE, DATA_CS_HI,
          DONE = newElement()
    }
    val spiState      = RegInit(SpiDiagState.IDLE)
    val spiPhase      = Reg(UInt(2 bits)) init(0)  // 0=RSTQIO, 1=RSTEN, 2=RST
    val spiWaitCnt    = Reg(UInt(14 bits)) init(0)
    val spiGapCnt     = Reg(UInt(8 bits)) init(0)
    val spiRxShift    = Reg(Bits(8 bits)) init(0)
    val jedecResp     = Reg(Bits(24 bits)) init(0)
    val jedecByteCnt  = Reg(UInt(2 bits)) init(0)
    val dataWord      = Reg(Bits(32 bits)) init(0)
    val dataByteCnt   = Reg(UInt(4 bits)) init(0)  // 0=cmd,1=a2,2=a1,3=a0,4-7=data

    diagJedec = Some(jedecResp)
    diagDataWord = Some(dataWord)

    switch(spiState) {
      is(SpiDiagState.IDLE) {
        when(hbCounter === 999999) {  // 10ms after config
          spiCs := False
          spiShiftOut := B"xFF"       // RSTQIO
          spiBitCnt := 0
          spiClkCnt := 0
          spiState := SpiDiagState.CS_LO
        }
      }

      is(SpiDiagState.CS_LO) {
        spiMosi := spiShiftOut.msb
        spiState := SpiDiagState.SPI_RISE
      }

      is(SpiDiagState.SPI_RISE) {
        spiClkCnt := spiClkCnt + 1
        when(spiClkCnt === 15) {
          spiClkCnt := 0
          spiClk := True
          spiState := SpiDiagState.SPI_FALL
        }
      }

      is(SpiDiagState.SPI_FALL) {
        spiClkCnt := spiClkCnt + 1
        when(spiClkCnt === 15) {
          spiClkCnt := 0
          spiClk := False
          spiBitCnt := spiBitCnt + 1
          when(spiBitCnt === 7) {
            spiState := SpiDiagState.BYTE_DONE
          } otherwise {
            spiShiftOut := spiShiftOut(6 downto 0) ## B"0"
            spiMosi := spiShiftOut(6)
            spiState := SpiDiagState.SPI_RISE
          }
        }
      }

      is(SpiDiagState.BYTE_DONE) {
        spiBitCnt := 0
        spiState := SpiDiagState.CS_HI
      }

      is(SpiDiagState.CS_HI) {
        spiCs := True
        spiClk := False
        spiPhase := spiPhase + 1
        when(spiPhase >= 2) {
          spiGapCnt := 0
          spiState := SpiDiagState.WAIT_RST
        } otherwise {
          spiGapCnt := 0
          spiState := SpiDiagState.GAP
        }
      }

      is(SpiDiagState.GAP) {
        spiGapCnt := spiGapCnt + 1
        when(spiGapCnt === 99) {
          spiCs := False
          switch(spiPhase) {
            is(1) { spiShiftOut := B"x66" }  // RSTEN
            is(2) { spiShiftOut := B"x99" }  // RST
            default { spiShiftOut := B"xFF" }
          }
          spiState := SpiDiagState.CS_LO
        }
      }

      is(SpiDiagState.WAIT_RST) {
        spiWaitCnt := spiWaitCnt + 1
        when(spiWaitCnt === 9999) {  // 100μs at 100 MHz
          spiState := SpiDiagState.JEDEC_CS_LO
        }
      }

      // JEDEC ID read: send 0x9F, receive 3 bytes
      is(SpiDiagState.JEDEC_CS_LO) {
        spiCs := False
        spiShiftOut := B"x9F"
        spiBitCnt := 0
        spiClkCnt := 0
        jedecByteCnt := 0
        spiMosi := True  // MSB of 0x9F
        spiState := SpiDiagState.JEDEC_RISE
      }

      is(SpiDiagState.JEDEC_RISE) {
        spiClkCnt := spiClkCnt + 1
        when(spiClkCnt === 15) {
          spiClkCnt := 0
          spiClk := True
          spiRxShift := spiRxShift(6 downto 0) ## io.cf_miso.get.asBits
          spiState := SpiDiagState.JEDEC_FALL
        }
      }

      is(SpiDiagState.JEDEC_FALL) {
        spiClkCnt := spiClkCnt + 1
        when(spiClkCnt === 15) {
          spiClkCnt := 0
          spiClk := False
          spiBitCnt := spiBitCnt + 1
          when(spiBitCnt === 7) {
            spiState := SpiDiagState.JEDEC_BYTE_DONE
          } otherwise {
            spiShiftOut := spiShiftOut(6 downto 0) ## B"0"
            spiMosi := spiShiftOut(6)
            spiState := SpiDiagState.JEDEC_RISE
          }
        }
      }

      is(SpiDiagState.JEDEC_BYTE_DONE) {
        spiBitCnt := 0
        switch(jedecByteCnt) {
          is(1) { jedecResp(23 downto 16) := spiRxShift }
          is(2) { jedecResp(15 downto 8)  := spiRxShift }
          is(3) { jedecResp(7 downto 0)   := spiRxShift }
        }
        jedecByteCnt := jedecByteCnt + 1
        when(jedecByteCnt === 3) {
          spiState := SpiDiagState.JEDEC_CS_HI
        } otherwise {
          spiShiftOut := B"x00"
          spiMosi := False
          spiState := SpiDiagState.JEDEC_RISE
        }
      }

      is(SpiDiagState.JEDEC_CS_HI) {
        spiCs := True
        spiClk := False
        spiGapCnt := 0
        spiState := SpiDiagState.DATA_GAP
      }

      // Data read: READ_DATA (0x03) + 3 addr bytes + 4 data bytes at 0x240000
      is(SpiDiagState.DATA_GAP) {
        spiGapCnt := spiGapCnt + 1
        when(spiGapCnt === 99) {
          spiState := SpiDiagState.DATA_CS_LO
        }
      }

      is(SpiDiagState.DATA_CS_LO) {
        spiCs := False
        spiShiftOut := B"x03"  // READ_DATA command
        spiBitCnt := 0
        spiClkCnt := 0
        dataByteCnt := 0
        spiMosi := False  // MSB of 0x03 = 0
        spiState := SpiDiagState.DATA_RISE
      }

      is(SpiDiagState.DATA_RISE) {
        spiClkCnt := spiClkCnt + 1
        when(spiClkCnt === 15) {
          spiClkCnt := 0
          spiClk := True
          // Sample MISO (useful for data bytes 4-7)
          spiRxShift := spiRxShift(6 downto 0) ## io.cf_miso.get.asBits
          spiState := SpiDiagState.DATA_FALL
        }
      }

      is(SpiDiagState.DATA_FALL) {
        spiClkCnt := spiClkCnt + 1
        when(spiClkCnt === 15) {
          spiClkCnt := 0
          spiClk := False
          spiBitCnt := spiBitCnt + 1
          when(spiBitCnt === 7) {
            spiState := SpiDiagState.DATA_BYTE_DONE
          } otherwise {
            spiShiftOut := spiShiftOut(6 downto 0) ## B"0"
            spiMosi := spiShiftOut(6)
            spiState := SpiDiagState.DATA_RISE
          }
        }
      }

      is(SpiDiagState.DATA_BYTE_DONE) {
        spiBitCnt := 0
        // dataByteCnt: 0=cmd,1=a2,2=a1,3=a0,4=d0,5=d1,6=d2,7=d3
        switch(dataByteCnt) {
          is(4) { dataWord(31 downto 24) := spiRxShift }  // Data byte 0 (MSB)
          is(5) { dataWord(23 downto 16) := spiRxShift }  // Data byte 1
          is(6) { dataWord(15 downto 8)  := spiRxShift }  // Data byte 2
          is(7) { dataWord(7 downto 0)   := spiRxShift }  // Data byte 3 (LSB)
        }
        dataByteCnt := dataByteCnt + 1
        when(dataByteCnt === 7) {
          // All 4 data bytes captured
          spiState := SpiDiagState.DATA_CS_HI
        } otherwise {
          // Set up next byte to transmit
          switch(dataByteCnt) {
            is(0) { spiShiftOut := B"x24"; spiMosi := False }  // addr byte 2 (0x24), MSB=0
            is(1) { spiShiftOut := B"x00"; spiMosi := False }  // addr byte 1
            is(2) { spiShiftOut := B"x00"; spiMosi := False }  // addr byte 0
            default { spiShiftOut := B"x00"; spiMosi := False } // dummy for read
          }
          spiState := SpiDiagState.DATA_RISE
        }
      }

      is(SpiDiagState.DATA_CS_HI) {
        spiCs := True
        spiClk := False
        spiDiagDone := True
        spiState := SpiDiagState.DONE
      }

      is(SpiDiagState.DONE) {
        // SPI pins now controlled by BmbConfigFlash via mux
      }
    }
  }

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
  // After ~167ms (2^24 @ 100MHz board clock), switch LED display and trigger UART dump.
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

  // Latch memState at hang detection (frozen for LED display and UART dump)
  val hangMemState = Reg(UInt(5 bits)) init(0)
  when(!hangDetected) {
    hangMemState := memStateSync
  }

  // ========================================================================
  // Diagnostic UART (board clock domain)
  // ========================================================================
  // On hang, takes over UART TX and sends state dump every ~200ms.

  val diagUart = DiagUart(clockFreqHz = 100000000, baudRate = 1000000)
  diagUart.io.trigger      := hangDetected
  diagUart.io.memState     := memStateSync
  diagUart.io.pc           := pcSync
  diagUart.io.jpc          := jpcSync
  diagUart.io.cacheState   := cacheStateSync
  diagUart.io.adapterState := adapterStateSync

  // Sync config flash debug signals to board clock domain (before bitbang UART)
  val cfDebugRxByteSync = if (ioConfig.hasConfigFlash)
    Some(BufferCC(mainArea.cluster.io.cfDebugRxByte.get, init = B(0, 8 bits)))
  else None
  val cfDebugTxCountSync = if (ioConfig.hasConfigFlash)
    Some(BufferCC(mainArea.cluster.io.cfDebugTxCount.get, init = U(0, 8 bits)))
  else None
  val cfDebugFirstWordSync = if (ioConfig.hasConfigFlash)
    Some(BufferCC(mainArea.cluster.io.cfDebugFirstWord.get, init = B(0, 32 bits)))
  else None

  // ========================================================================
  // Board-domain UART TX: bitbang diagnostic characters.
  //   Phase 0: Send 'B' every ~10ms until MIG calibrates
  //   Phase 1: Send 'M' (MIG calibrated)
  //   Phase 2: Wait ~335ms for flash boot microcode to execute
  //   Phase 3: Send debug info: "Txx/Ryy\r\n" (TX count + RX byte as hex)
  //   Phase 4: Done, hand off to JOP UART
  // ========================================================================

  // Simple 1-Mbaud bitbang TX: 100 cycles per bit, 10 bits (start + 8 data + stop)
  val bbCounter = Reg(UInt(20 bits)) init(0)   // ~10ms inter-character (short!)
  val bbBitCnt  = Reg(UInt(4 bits)) init(0)    // 0=idle, 1=start, 2-9=data, 10=stop
  val bbClkCnt  = Reg(UInt(7 bits)) init(0)    // baud rate divider (100 cycles at 100MHz = 1Mbaud)
  val bbTxReg   = Reg(Bool()) init(True)       // idle high
  val bbDone    = Reg(Bool()) init(False)       // True when all diagnostic output is done

  // Phase tracking
  val bbSentB     = Reg(Bool()) init(False)     // at least one 'B' sent
  val bbSentM     = Reg(Bool()) init(False)     // 'M' sent
  val bbWaitCnt   = Reg(UInt(27 bits)) init(0)  // wait counter (~670ms at 100 MHz)
  val bbWaitDone  = Reg(Bool()) init(False)     // wait period complete
  val bbDebugIdx  = Reg(UInt(5 bits)) init(0)   // debug message char index (0-21)

  // Snapshot debug signals when wait period ends (before sending debug chars)
  val bbTxSnap    = Reg(Bits(8 bits)) init(0)
  val bbRxSnap    = Reg(Bits(8 bits)) init(0)
  val bbPcSnap    = Reg(UInt(11 bits)) init(0)
  val jedecSnap   = Reg(Bits(24 bits)) init(0)
  val firstWordSnap = Reg(Bits(32 bits)) init(0)

  // Wait ~670ms after 'M' for microcode to run (2^26 @ 100 MHz)
  when(bbSentM && !bbWaitDone) {
    bbWaitCnt := bbWaitCnt + 1
    when(bbWaitCnt.msb) {
      bbWaitDone := True
      // Snapshot debug signals
      if (ioConfig.hasConfigFlash) {
        bbTxSnap := cfDebugTxCountSync.get.asBits
        bbRxSnap := cfDebugRxByteSync.get
        jedecSnap := diagJedec.get
        firstWordSnap := cfDebugFirstWordSync.get
      }
      bbPcSnap := pcSync
    }
  }

  // Nibble-to-hex: 0-9 -> '0'-'9' (0x30-0x39), A-F -> 'A'-'F' (0x41-0x46)
  def nibbleToHex(nibble: Bits): Bits = {
    val n = nibble(3 downto 0).asUInt
    Mux(n < 10, (n + 0x30).asBits.resize(8), (n - 10 + 0x41).asBits.resize(8))
  }

  // Debug message: "Jxxxxxx/Wxxxxxxxx/Txx/Ryy/Pxxx\r\n" (32 chars, index 0-31)
  // J = JEDEC ID from board-clock diagnostic (3 bytes = 6 hex chars)
  // W = First word from BmbConfigFlash RX (4 bytes = 8 hex chars)
  // T = BmbConfigFlash TX count (microcode SPI transfers, 8-bit wraps)
  // R = BmbConfigFlash last RX byte
  // P = Program counter (11-bit, 3 hex chars)
  val bbDebugChar = bbDebugIdx.muxListDc(List(
    0  -> B"x4A",                                  // 'J'
    1  -> nibbleToHex(jedecSnap(23 downto 20)),    // JEDEC byte 0 high
    2  -> nibbleToHex(jedecSnap(19 downto 16)),    // JEDEC byte 0 low
    3  -> nibbleToHex(jedecSnap(15 downto 12)),    // JEDEC byte 1 high
    4  -> nibbleToHex(jedecSnap(11 downto 8)),     // JEDEC byte 1 low
    5  -> nibbleToHex(jedecSnap(7 downto 4)),      // JEDEC byte 2 high
    6  -> nibbleToHex(jedecSnap(3 downto 0)),      // JEDEC byte 2 low
    7  -> B"x2F",                                  // '/'
    8  -> B"x57",                                  // 'W'
    9  -> nibbleToHex(firstWordSnap(31 downto 28)), // Word byte 0 high
    10 -> nibbleToHex(firstWordSnap(27 downto 24)), // Word byte 0 low
    11 -> nibbleToHex(firstWordSnap(23 downto 20)), // Word byte 1 high
    12 -> nibbleToHex(firstWordSnap(19 downto 16)), // Word byte 1 low
    13 -> nibbleToHex(firstWordSnap(15 downto 12)), // Word byte 2 high
    14 -> nibbleToHex(firstWordSnap(11 downto 8)),  // Word byte 2 low
    15 -> nibbleToHex(firstWordSnap(7 downto 4)),   // Word byte 3 high
    16 -> nibbleToHex(firstWordSnap(3 downto 0)),   // Word byte 3 low
    17 -> B"x2F",                                  // '/'
    18 -> B"x54",                                  // 'T'
    19 -> nibbleToHex(bbTxSnap(7 downto 4)),       // TX count high
    20 -> nibbleToHex(bbTxSnap(3 downto 0)),       // TX count low
    21 -> B"x2F",                                  // '/'
    22 -> B"x52",                                  // 'R'
    23 -> nibbleToHex(bbRxSnap(7 downto 4)),       // RX byte high
    24 -> nibbleToHex(bbRxSnap(3 downto 0)),       // RX byte low
    25 -> B"x2F",                                  // '/'
    26 -> B"x50",                                  // 'P'
    27 -> nibbleToHex(B"0" ## bbPcSnap(10 downto 8).asBits), // PC high nibble (3 bits)
    28 -> nibbleToHex(bbPcSnap(7 downto 4).asBits),  // PC mid nibble
    29 -> nibbleToHex(bbPcSnap(3 downto 0).asBits),  // PC low nibble
    30 -> B"x0D",                                  // '\r'
    31 -> B"x0A"                                   // '\n'
  ))

  // Character to send depends on phase
  val bbByte = if (ioConfig.hasConfigFlash) {
    Mux(bbWaitDone && !bbDone, bbDebugChar,
      Mux(bbSentB && migCalibSync && !bbSentM, B"x4D", B"x42"))
  } else {
    Mux(bbSentB && migCalibSync && !bbSentM, B"x4D", B"x42")
  }

  when(!bbDone) {
    bbCounter := bbCounter + 1
    when(bbCounter.andR && bbBitCnt === 0) {
      // Start sending a character (skip during wait phase for flash debug)
      val bbCanSend = if (ioConfig.hasConfigFlash) (!bbSentM || bbWaitDone) else True
      when(bbCanSend) {
        bbBitCnt := 1
        bbClkCnt := 0
        bbTxReg := False  // start bit
      }
    }
    when(bbBitCnt =/= 0) {
      bbClkCnt := bbClkCnt + 1
      when(bbClkCnt === 99) {
        bbClkCnt := 0
        bbBitCnt := bbBitCnt + 1
        switch(bbBitCnt) {
          is(1)  { bbTxReg := bbByte(0) }  // LSB first
          is(2)  { bbTxReg := bbByte(1) }
          is(3)  { bbTxReg := bbByte(2) }
          is(4)  { bbTxReg := bbByte(3) }
          is(5)  { bbTxReg := bbByte(4) }
          is(6)  { bbTxReg := bbByte(5) }
          is(7)  { bbTxReg := bbByte(6) }
          is(8)  { bbTxReg := bbByte(7) }
          is(9)  { bbTxReg := True }  // stop bit
          is(10) {
            bbBitCnt := 0
            if (ioConfig.hasConfigFlash) {
              when(bbWaitDone) {
                // Debug phase: advance through message
                bbDebugIdx := bbDebugIdx + 1
                when(bbDebugIdx === 31) {
                  bbDone := True
                }
              } elsewhen(!bbSentB) {
                bbSentB := True
              } elsewhen(migCalibSync && !bbSentM) {
                bbSentM := True
                // Don't set bbDone — enter wait phase, then debug output
              }
            } else {
              when(!bbSentB) {
                bbSentB := True
              } elsewhen(migCalibSync && !bbSentM) {
                bbSentM := True
                bbDone := True
              }
            }
          }
        }
      }
    }
  }

  // UART TX MUX: bitbang during startup, then JOP/DiagUart.
  val jopTxdSync = BufferCC(mainArea.cluster.io.txd, init = True)
  when(!bbDone) {
    io.usb_tx := bbTxReg
  } elsewhen(hangDetected) {
    io.usb_tx := diagUart.io.txd
  } otherwise {
    io.usb_tx := jopTxdSync
  }

  // Config flash debug signals already synced above (before bitbang UART)

  // ========================================================================
  // LED Display
  // ========================================================================

  if (cpuCnt == 1 && ioConfig.hasConfigFlash) {
    // Flash-boot SPI debug:
    //   LED[7]   = EOS (STARTUPE2 end of startup, should be 1)
    //   LED[6:5] = last RX byte bits [7:6] (0xFF = stuck high)
    //   LED[4]   = heartbeat
    //   LED[3:0] = TX count [3:0] (number of SPI bytes sent by microcode)
    io.led(7) := spiDiagEos.get
    io.led(6 downto 5) := cfDebugRxByteSync.get(7 downto 6)
    io.led(4) := heartbeat
    io.led(3 downto 0) := cfDebugTxCountSync.get(3 downto 0).asBits
  } else if (cpuCnt == 1) {
    // Single-core serial boot:
    when(!hangDetected) {
      io.led(7 downto 3) := memStateSync.asBits
      io.led(2) := memBusySync
      io.led(1) := heartbeat
      io.led(0) := wdSync(0)
    } otherwise {
      io.led(7 downto 3) := hangMemState.asBits
      io.led(2) := True  // Solid on = hung
      io.led(1) := heartbeat
      io.led(0) := ~heartbeat  // Both blink = hang indicator
    }
  } else if (cpuCnt >= 2) {
    // SMP: LED[0]=core 0 WD, LED[1]=core 1 WD, LED[2]=memBusy, LED[7:3]=memState or hang
    when(!hangDetected) {
      io.led(7 downto 3) := memStateSync.asBits
      io.led(2) := memBusySync
      io.led(1) := wdSync(1)
      io.led(0) := wdSync(0)
    } otherwise {
      io.led(7 downto 3) := hangMemState.asBits
      io.led(2) := True  // Solid on = hung
      io.led(1) := heartbeat
      io.led(0) := ~heartbeat  // Both blink = hang indicator
    }
  }
}

/**
 * Generate Verilog for JopDdr3Top (single-core)
 */
object JopDdr3TopVerilog extends App {
  val romFilePath = "asm/generated/serial/mem_rom.dat"
  val ramFilePath = "asm/generated/serial/mem_ram.dat"

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
  ).generate(InOutWrapper(JopDdr3Top(
    cpuCnt = 1,
    romInit = romData,
    ramInit = ramData
  )))

  println("Generated: spinalhdl/generated/JopDdr3Top.v")
}

/**
 * Generate Verilog for JopDdr3Top in SMP mode (entity: JopDdr3SmpTop)
 */
object JopDdr3SmpTopVerilog extends App {
  val cpuCnt = if (args.length > 0) args(0).toInt else 2

  val romFilePath = "asm/generated/serial/mem_rom.dat"
  val ramFilePath = "asm/generated/serial/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Generating $cpuCnt-core SMP DDR3 Verilog...")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = LOW
    )
  ).generate(InOutWrapper(JopDdr3Top(
    cpuCnt = cpuCnt,
    romInit = romData,
    ramInit = ramData
  )))

  println(s"Generated: spinalhdl/generated/JopDdr3SmpTop.v ($cpuCnt cores)")
}

/**
 * Generate Verilog for JopDdr3Top with config flash (for flash boot)
 */
object JopDdr3FlashTopVerilog extends App {
  val romFilePath = "asm/generated/flash/mem_rom.dat"
  val ramFilePath = "asm/generated/flash/mem_ram.dat"

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
  ).generate(InOutWrapper(JopDdr3Top(
    cpuCnt = 1,
    romInit = romData,
    ramInit = ramData,
    ioConfig = IoConfig(hasConfigFlash = true),
    jumpTable = JumpTableInitData.flash
  )))

  println("Generated: spinalhdl/generated/JopDdr3Top.v (config flash boot)")
}
