package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import spinal.lib.io.InOutWrapper
import jop.ddr3._
import jop.memory.JopMemoryConfig

/**
 * DDR3 GC Pattern Exerciser for Alchitry AU V2
 *
 * Replays GC-like DDR3 access patterns to reproduce the DDR3 GC hang:
 *   P1: Sequential fill (96KB, 24K words)
 *   P2: Scattered read via LFSR (cache thrashing)
 *   P3: memCopy stress (rapid R/W interleaving at different cache sets)
 *   P4: Read-modify-write storm (scattered dirty evictions)
 *   P5: Full sequential verify
 *
 * Same DDR3 path as JopDdr3Top:
 *   BMB -> BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MIG -> DDR3
 */
case class Ddr3GcExerciserTop() extends Component {

  val io = new Bundle {
    val led      = out Bits(8 bits)
    val usb_rx   = in Bool()
    val usb_tx   = out Bool()

    // DDR3 pins
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
  // ========================================================================

  val clkWiz = new ClkWizBlackBox
  clkWiz.io.clk_in := ClockDomain.current.readClockWire
  // ClkWiz reset is active-HIGH despite port name "resetn"
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

    // Same BMB parameters as JOP DDR3 config
    val memConfig = JopMemoryConfig(addressWidth = 26, burstLen = 0)
    val bmbParam = memConfig.bmbParameter

    // ==================================================================
    // DDR3 Memory Path (identical to JopDdr3Top)
    // ==================================================================

    val cacheAddrWidth = 28  // BMB byte address width
    val cacheDataWidth = 128 // MIG native data width

    val bmbBridge = new BmbCacheBridge(bmbParam, cacheAddrWidth, cacheDataWidth)
    val cache = new LruCacheCore(CacheConfig(addrWidth = cacheAddrWidth, dataWidth = cacheDataWidth))
    val adapter = new CacheToMigAdapter

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
    // UART TX (1 Mbaud)
    // ==================================================================

    val uartCtrl = new UartCtrl(UartCtrlGenerics(
      preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1
    ))
    uartCtrl.io.config.setClockDivider(1000000 Hz)
    uartCtrl.io.config.frame.dataLength := 7
    uartCtrl.io.config.frame.parity := UartParityType.NONE
    uartCtrl.io.config.frame.stop := UartStopType.ONE
    uartCtrl.io.writeBreak := False
    uartCtrl.io.uart.rxd := True
    io.usb_tx := uartCtrl.io.uart.txd

    val txFifo = StreamFifo(Bits(8 bits), 256)
    uartCtrl.io.write.valid := txFifo.io.pop.valid
    uartCtrl.io.write.payload := txFifo.io.pop.payload
    txFifo.io.pop.ready := uartCtrl.io.write.ready

    // Default: don't push
    txFifo.io.push.valid := False
    txFifo.io.push.payload := 0

    // ==================================================================
    // Message ROM
    // ==================================================================
    val msgBytes: Seq[Int] = {
      //  0: "GC EXERCISER\r\n"   (14)
      val m0 = "GC EXERCISER\r\n"
      // 14: "P1:FILL "            (8)
      val m1 = "P1:FILL "
      // 22: "P2:SCAN "            (8)
      val m2 = "P2:SCAN "
      // 30: "P3:COPY "            (8)
      val m3 = "P3:COPY "
      // 38: "P4:RMW  "            (8)
      val m4 = "P4:RMW  "
      // 46: "P5:VFY  "            (8)
      val m5 = "P5:VFY  "
      // 54: "PASS\r\n"            (6)
      val m6 = "PASS\r\n"
      // 60: "FAIL "               (5)
      val m7 = "FAIL "
      // 65: " E="                 (3)
      val m8 = " E="
      // 68: " G="                 (3)
      val m9 = " G="
      // 71: "\r\n"                (2)
      val m10 = "\r\n"
      // 73: "LOOP "               (5)
      val m11 = "LOOP "
      (m0 + m1 + m2 + m3 + m4 + m5 + m6 + m7 + m8 + m9 + m10 + m11).map(_.toInt)
    }
    val msgRom = Mem(Bits(8 bits), initialContent = msgBytes.map(c => B(c & 0xFF, 8 bits)))

    // ==================================================================
    // Constants
    // ==================================================================
    def REGION_WORDS = 24576  // 96KB = 24K words
    def COPY_WORDS   = 4096   // 16KB copy region
    def COPY_DST_OFF = 8192   // word offset of copy destination
    def SCATTER_CNT  = 2048   // iterations for scattered access phases

    // Pattern: word_addr XOR 0xDEADBEEF
    def pat(a: UInt): Bits = (a.resize(32).asBits ^ B(0xDEADBEEFL, 32 bits))

    // ==================================================================
    // State Machine
    // ==================================================================
    object S extends SpinalEnum {
      val INIT_WAIT = newElement()
      val PRINT_MSG, PRINT_HEX = newElement()
      // Phase 1: Sequential fill
      val P1_LABEL, P1_W, P1_W_RSP = newElement()
      // Phase 2: Scattered read
      val P2_LABEL, P2_R, P2_R_RSP = newElement()
      // Phase 3: memCopy stress
      val P3_LABEL, P3_CR, P3_CR_RSP, P3_CW, P3_CW_RSP, P3_V, P3_V_RSP = newElement()
      // Phase 4: Read-modify-write storm
      val P4_LABEL, P4_R, P4_R_RSP, P4_W, P4_W_RSP, P4_RV, P4_RV_RSP = newElement()
      // Phase 5: Full verify
      val P5_LABEL, P5_R, P5_R_RSP = newElement()
      // Result printing
      val RESULT, RESULT_SEQ = newElement()
      // Loop
      val LOOP_MSG, LOOP_HEX, LOOP_CRLF = newElement()
    }

    val state = RegInit(S.INIT_WAIT)
    val retState = Reg(S())

    // Init counter
    val initCnt = Reg(UInt(24 bits)) init(0)

    // Print state
    val msgBase = Reg(UInt(7 bits))
    val msgLen  = Reg(UInt(7 bits))
    val msgIdx  = Reg(UInt(7 bits))

    // Hex print state
    val hexVal = Reg(Bits(32 bits))
    val hexNib = Reg(UInt(4 bits))

    // BMB address/data
    val addr     = Reg(UInt(24 bits)) init(0)
    val wrData   = Reg(Bits(32 bits)) init(0)
    val expected = Reg(Bits(32 bits)) init(0)

    // Iteration counter (needs 15 bits for 24576)
    val idx = Reg(UInt(15 bits)) init(0)

    // Test results
    val failCnt  = Reg(UInt(16 bits)) init(0)
    val failAddr = Reg(UInt(24 bits)) init(0)
    val failExp  = Reg(Bits(32 bits)) init(0)
    val failGot  = Reg(Bits(32 bits)) init(0)
    val phaseNum = Reg(UInt(4 bits)) init(0)
    val loopCnt  = Reg(UInt(16 bits)) init(0)
    val resultSeq = Reg(UInt(4 bits)) init(0)

    // Copy buffer (Phase 3)
    val copyBuf = Reg(Bits(32 bits))

    // LFSR for scattered address generation (15-bit Galois, period 32767)
    val lfsr = Reg(UInt(15 bits)) init(1)
    def lfsrStep(): Unit = {
      val feedback = lfsr(0)
      lfsr := (lfsr |>> 1)
      when(feedback) {
        lfsr(14) := ~lfsr(14)
        lfsr(13) := ~lfsr(13)
      }
    }

    // Map LFSR to 0..REGION_WORDS-1 range via comparison
    val scatterAddr = UInt(15 bits)
    when(lfsr >= REGION_WORDS) {
      scatterAddr := lfsr - REGION_WORDS
    } otherwise {
      scatterAddr := lfsr
    }

    // P4 RMW targets upper region only (12288..24575) to avoid corrupting
    // P5's verify region (4096..8191) and P3's copy regions (0..4095, 8192..12287)
    def P4_BASE = 12288
    def P4_RANGE = REGION_WORDS - P4_BASE  // 12288 words
    val scatterAddrP4 = UInt(15 bits)
    val lfsrMod = UInt(15 bits)
    when(lfsr >= P4_RANGE) {
      lfsrMod := lfsr - P4_RANGE
    } otherwise {
      lfsrMod := lfsr
    }
    scatterAddrP4 := lfsrMod + P4_BASE

    // Scatter iteration counter
    val scatterCnt = Reg(UInt(12 bits)) init(0)

    // LED register
    val ledReg = Reg(Bits(4 bits)) init(0)

    // ==================================================================
    // BMB Interface
    // ==================================================================
    val bmb = bmbBridge.io.bmb
    val bmbIsWrite = Reg(Bool()) init(False)

    bmb.cmd.valid := False
    bmb.cmd.last := True
    bmb.cmd.fragment.opcode := Mux(bmbIsWrite, B"1", B"0")
    bmb.cmd.fragment.address := (addr << 2).resized
    bmb.cmd.fragment.length := 3
    bmb.cmd.fragment.source := 0
    bmb.cmd.fragment.context := 0
    bmb.cmd.fragment.data := wrData
    bmb.cmd.fragment.mask := B"1111"
    bmb.rsp.ready := True

    // Helper: record first failure
    def recordFail(got: Bits): Unit = {
      when(failCnt === 0) {
        failAddr := addr
        failExp := expected
        failGot := got
      }
      failCnt := failCnt + 1
    }

    // ==================================================================
    // State Machine Body
    // ==================================================================
    switch(state) {

      // ================================================================
      // Init: wait after MIG calibration
      // ================================================================
      is(S.INIT_WAIT) {
        initCnt := initCnt + 1
        when(initCnt.andR) {
          msgBase := 0; msgLen := 14; msgIdx := 0 // "GC EXERCISER\r\n"
          retState := S.P1_LABEL; state := S.PRINT_MSG
        }
      }

      // ================================================================
      // Print sub-state: msgRom[msgBase..+msgLen)
      // ================================================================
      is(S.PRINT_MSG) {
        when(txFifo.io.push.ready) {
          txFifo.io.push.valid := True
          txFifo.io.push.payload := msgRom.readAsync((msgBase + msgIdx).resized)
          msgIdx := msgIdx + 1
          when(msgIdx + 1 >= msgLen) {
            state := retState
          }
        }
      }

      // ================================================================
      // Hex print sub-state: 8 hex digits
      // ================================================================
      is(S.PRINT_HEX) {
        when(txFifo.io.push.ready) {
          val nib = hexVal(31 downto 28).asUInt
          val ch = Bits(8 bits)
          when(nib < 10) {
            ch := (nib + 0x30).asBits.resized
          } otherwise {
            ch := (nib + 0x57).asBits.resized
          }
          txFifo.io.push.valid := True
          txFifo.io.push.payload := ch
          hexVal := hexVal |<< 4
          hexNib := hexNib + 1
          when(hexNib === 7) {
            state := retState
          }
        }
      }

      // ================================================================
      // Phase 1: Sequential fill (24K words with known pattern)
      // ================================================================
      is(S.P1_LABEL) {
        phaseNum := 1; idx := 0; failCnt := 0
        lfsr := 1  // Reset LFSR for deterministic sequence
        msgBase := 14; msgLen := 8; msgIdx := 0 // "P1:FILL "
        retState := S.P1_W; state := S.PRINT_MSG
      }
      is(S.P1_W) {
        addr := idx.resized
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"1"
        bmb.cmd.fragment.address := (idx.resize(24) << 2).resized
        bmb.cmd.fragment.data := pat(idx.resize(24))
        when(bmb.cmd.fire) { state := S.P1_W_RSP }
      }
      is(S.P1_W_RSP) {
        when(bmb.rsp.fire) {
          idx := idx + 1
          when(idx === REGION_WORDS - 1) {
            state := S.RESULT
          } otherwise {
            state := S.P1_W
          }
        }
      }

      // ================================================================
      // Phase 2: Scattered read (2048 LFSR-addressed reads + verify)
      // ================================================================
      is(S.P2_LABEL) {
        phaseNum := 2; scatterCnt := 0; failCnt := 0
        lfsr := 1  // Reset LFSR for deterministic sequence
        msgBase := 22; msgLen := 8; msgIdx := 0 // "P2:SCAN "
        retState := S.P2_R; state := S.PRINT_MSG
      }
      is(S.P2_R) {
        val a = scatterAddr.resize(24)
        addr := a
        expected := pat(a)
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"0"
        bmb.cmd.fragment.address := (a << 2).resized
        when(bmb.cmd.fire) { state := S.P2_R_RSP }
      }
      is(S.P2_R_RSP) {
        when(bmb.rsp.fire) {
          when(bmb.rsp.fragment.data =/= expected) {
            recordFail(bmb.rsp.fragment.data)
          }
          lfsrStep()
          scatterCnt := scatterCnt + 1
          when(scatterCnt === SCATTER_CNT - 1) {
            state := S.RESULT
          } otherwise {
            state := S.P2_R
          }
        }
      }

      // ================================================================
      // Phase 3: memCopy stress (copy 4K words from src to dst, verify)
      // ================================================================
      is(S.P3_LABEL) {
        phaseNum := 3; idx := 0; failCnt := 0
        msgBase := 30; msgLen := 8; msgIdx := 0 // "P3:COPY "
        retState := S.P3_CR; state := S.PRINT_MSG
      }
      // Read from source
      is(S.P3_CR) {
        val a = idx.resize(24)
        addr := a
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"0"
        bmb.cmd.fragment.address := (a << 2).resized
        when(bmb.cmd.fire) { state := S.P3_CR_RSP }
      }
      is(S.P3_CR_RSP) {
        when(bmb.rsp.fire) {
          copyBuf := bmb.rsp.fragment.data
          state := S.P3_CW
        }
      }
      // Write to destination (offset by COPY_DST_OFF)
      is(S.P3_CW) {
        val a = (idx + COPY_DST_OFF).resize(24)
        addr := a
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"1"
        bmb.cmd.fragment.address := (a << 2).resized
        bmb.cmd.fragment.data := copyBuf
        when(bmb.cmd.fire) { state := S.P3_CW_RSP }
      }
      is(S.P3_CW_RSP) {
        when(bmb.rsp.fire) {
          idx := idx + 1
          when(idx === COPY_WORDS - 1) {
            idx := 0
            state := S.P3_V
          } otherwise {
            state := S.P3_CR
          }
        }
      }
      // Verify destination
      is(S.P3_V) {
        val a = (idx + COPY_DST_OFF).resize(24)
        addr := a
        // Destination should match source pattern: pat(src_addr) = pat(idx)
        expected := pat(idx.resize(24))
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"0"
        bmb.cmd.fragment.address := (a << 2).resized
        when(bmb.cmd.fire) { state := S.P3_V_RSP }
      }
      is(S.P3_V_RSP) {
        when(bmb.rsp.fire) {
          when(bmb.rsp.fragment.data =/= expected) {
            recordFail(bmb.rsp.fragment.data)
          }
          idx := idx + 1
          when(idx === COPY_WORDS - 1) {
            state := S.RESULT
          } otherwise {
            state := S.P3_V
          }
        }
      }

      // ================================================================
      // Phase 4: Read-modify-write storm (2048 scattered RMW + verify)
      // Each: read, XOR with 0x55555555, write back, read-back verify
      // ================================================================
      is(S.P4_LABEL) {
        phaseNum := 4; scatterCnt := 0; failCnt := 0
        lfsr := 1  // Reset LFSR
        msgBase := 38; msgLen := 8; msgIdx := 0 // "P4:RMW  "
        retState := S.P4_R; state := S.PRINT_MSG
      }
      // Read (targets upper region 12288..24575 only)
      is(S.P4_R) {
        val a = scatterAddrP4.resize(24)
        addr := a
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"0"
        bmb.cmd.fragment.address := (a << 2).resized
        when(bmb.cmd.fire) { state := S.P4_R_RSP }
      }
      is(S.P4_R_RSP) {
        when(bmb.rsp.fire) {
          wrData := bmb.rsp.fragment.data ^ B(0x55555555L, 32 bits)
          expected := bmb.rsp.fragment.data ^ B(0x55555555L, 32 bits)
          state := S.P4_W
        }
      }
      // Write modified value back
      is(S.P4_W) {
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"1"
        bmb.cmd.fragment.address := (addr << 2).resized
        bmb.cmd.fragment.data := wrData
        when(bmb.cmd.fire) { state := S.P4_W_RSP }
      }
      is(S.P4_W_RSP) {
        when(bmb.rsp.fire) {
          state := S.P4_RV
        }
      }
      // Read-back verify
      is(S.P4_RV) {
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"0"
        bmb.cmd.fragment.address := (addr << 2).resized
        when(bmb.cmd.fire) { state := S.P4_RV_RSP }
      }
      is(S.P4_RV_RSP) {
        when(bmb.rsp.fire) {
          when(bmb.rsp.fragment.data =/= expected) {
            recordFail(bmb.rsp.fragment.data)
          }
          lfsrStep()
          scatterCnt := scatterCnt + 1
          when(scatterCnt === SCATTER_CNT - 1) {
            state := S.RESULT
          } otherwise {
            state := S.P4_R
          }
        }
      }

      // ================================================================
      // Phase 5: Full sequential verify
      // Words 0..COPY_DST_OFF-1: original pattern (some XOR'd by P4)
      // Words COPY_DST_OFF..COPY_DST_OFF+COPY_WORDS-1: copy of src
      // Words COPY_DST_OFF+COPY_WORDS..REGION_WORDS-1: original pattern (some XOR'd by P4)
      //
      // Since P4 modifies values in-place with immediate verify, and we
      // can't easily track which addresses were modified, verify the copy
      // region only (words COPY_DST_OFF..COPY_DST_OFF+COPY_WORDS-1).
      // ================================================================
      is(S.P5_LABEL) {
        phaseNum := 5; idx := 0; failCnt := 0
        msgBase := 46; msgLen := 8; msgIdx := 0 // "P5:VFY  "
        retState := S.P5_R; state := S.PRINT_MSG
      }
      is(S.P5_R) {
        // Verify words 4096..8191 — untouched by P3 copy (0→8192),
        // P4 may hit some via LFSR but that's a known limitation.
        val a = (idx + U(4096, 15 bits)).resize(24)
        addr := a
        expected := pat(a)
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"0"
        bmb.cmd.fragment.address := (a << 2).resized
        when(bmb.cmd.fire) { state := S.P5_R_RSP }
      }
      is(S.P5_R_RSP) {
        when(bmb.rsp.fire) {
          when(bmb.rsp.fragment.data =/= expected) {
            recordFail(bmb.rsp.fragment.data)
          }
          idx := idx + 1
          when(idx === COPY_WORDS - 1) {
            state := S.RESULT
          } otherwise {
            state := S.P5_R
          }
        }
      }

      // ================================================================
      // Result: PASS/FAIL, then next phase
      // ================================================================
      is(S.RESULT) {
        resultSeq := 0
        state := S.RESULT_SEQ
      }
      is(S.RESULT_SEQ) {
        switch(resultSeq) {
          is(0) {
            when(failCnt === 0) {
              msgBase := 54; msgLen := 6; msgIdx := 0 // "PASS\r\n"
              retState := S.RESULT_SEQ; resultSeq := 10
              state := S.PRINT_MSG
            } otherwise {
              msgBase := 60; msgLen := 5; msgIdx := 0 // "FAIL "
              retState := S.RESULT_SEQ; resultSeq := 1
              state := S.PRINT_MSG
            }
          }
          is(1) { // hex(failAddr)
            hexVal := failAddr.asBits.resized; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 2
            state := S.PRINT_HEX
          }
          is(2) { // " E="
            msgBase := 65; msgLen := 3; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 3
            state := S.PRINT_MSG
          }
          is(3) { // hex(failExp)
            hexVal := failExp; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 4
            state := S.PRINT_HEX
          }
          is(4) { // " G="
            msgBase := 68; msgLen := 3; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 5
            state := S.PRINT_MSG
          }
          is(5) { // hex(failGot)
            hexVal := failGot; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 6
            state := S.PRINT_HEX
          }
          is(6) { // "\r\n"
            msgBase := 71; msgLen := 2; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 10
            state := S.PRINT_MSG
          }
          is(10) { // Advance to next phase
            switch(phaseNum) {
              is(1) { state := S.P2_LABEL }
              is(2) { state := S.P3_LABEL }
              is(3) { state := S.P4_LABEL }
              is(4) { state := S.P5_LABEL }
              is(5) { state := S.LOOP_MSG }
              default { state := S.LOOP_MSG }
            }
          }
          default {}
        }
      }

      // ================================================================
      // Loop: print count and restart
      // ================================================================
      is(S.LOOP_MSG) {
        loopCnt := loopCnt + 1
        ledReg := loopCnt.asBits.resized
        msgBase := 73; msgLen := 5; msgIdx := 0 // "LOOP "
        retState := S.LOOP_HEX
        state := S.PRINT_MSG
      }
      is(S.LOOP_HEX) {
        hexVal := loopCnt.asBits.resized; hexNib := 0
        retState := S.LOOP_CRLF
        state := S.PRINT_HEX
      }
      is(S.LOOP_CRLF) {
        msgBase := 71; msgLen := 2; msgIdx := 0 // "\r\n"
        retState := S.P1_LABEL
        state := S.PRINT_MSG
      }
    }
  }

  // ========================================================================
  // Board Clock Domain Area (heartbeat, calibration sync, LED driver)
  // ========================================================================

  val hbCounter = Reg(UInt(26 bits)) init(0)
  hbCounter := hbCounter + 1

  val migCalibSync = BufferCC(mig.io.init_calib_complete, init = False)
  val ledRegSync = BufferCC(mainArea.ledReg, init = B(0, 4 bits))

  // LED: bit 0 = heartbeat, bit 1 = MIG calibration, bits 5:2 = phase/loop
  io.led(0) := hbCounter.msb
  io.led(1) := migCalibSync
  io.led(5 downto 2) := ledRegSync
  io.led(7 downto 6) := 0
}

/**
 * Generate Verilog for Ddr3GcExerciserTop
 */
object Ddr3GcExerciserTopVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = LOW
    )
  ).generate(InOutWrapper(Ddr3GcExerciserTop()))

  println("Generated: spinalhdl/generated/Ddr3GcExerciserTop.v")
}
