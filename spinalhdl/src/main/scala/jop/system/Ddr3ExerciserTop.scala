package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import spinal.lib.io.InOutWrapper
import jop.ddr3._
import jop.memory.JopMemoryConfig

/**
 * DDR3 Exerciser FPGA Top for Alchitry AU V2
 *
 * Directly exercises the full DDR3 memory path:
 *   BMB -> BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MIG -> DDR3
 *
 * Same test suite as SdramExerciserTop:
 *   T1: Sequential fill + readback (1024 words)
 *   T2: memCopy pattern -- fill src, copy to dst, verify (512 words)
 *   T3: Write-then-read same address back-to-back (256 words)
 *   All tests loop continuously, reporting pass/fail via UART at 1 Mbaud.
 *
 * No JOP processor involved -- isolates the memory subsystem.
 */
case class Ddr3ExerciserTop() extends Component {

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
  // All design logic runs here. Held in reset until MIG calibration completes.
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
      //  0: "DDR3 TEST\r\n"   (11)
      val m0 = "DDR3 TEST\r\n"
      // 11: "T1:SEQWR "        (9)
      val m1 = "T1:SEQWR "
      // 20: "T2:COPY  "        (9)
      val m2 = "T2:COPY  "
      // 29: "T3:WRTRD "        (9)
      val m3 = "T3:WRTRD "
      // 38: "PASS\r\n"         (6)
      val m4 = "PASS\r\n"
      // 44: "FAIL "             (5)
      val m5 = "FAIL "
      // 49: " E="               (3)
      val m6 = " E="
      // 52: " G="               (3)
      val m7 = " G="
      // 55: "\r\n"              (2)
      val m8 = "\r\n"
      // 57: "LOOP "             (5)
      val m9 = "LOOP "
      (m0 + m1 + m2 + m3 + m4 + m5 + m6 + m7 + m8 + m9).map(_.toInt)
    }
    val msgRom = Mem(Bits(8 bits), initialContent = msgBytes.map(c => B(c & 0xFF, 8 bits)))

    // ==================================================================
    // State Machine
    // ==================================================================
    object S extends SpinalEnum {
      val INIT_WAIT = newElement()
      // Generic print sub-states
      val PRINT_MSG, PRINT_HEX = newElement()
      // Test 1: Sequential write/read
      val T1_LABEL, T1_W, T1_W_RSP, T1_R, T1_R_RSP = newElement()
      // Test 2: memCopy
      val T2_LABEL, T2_F, T2_F_RSP, T2_CR, T2_CR_RSP, T2_CW, T2_CW_RSP, T2_V, T2_V_RSP = newElement()
      // Test 3: Write-then-read
      val T3_LABEL, T3_W, T3_W_RSP, T3_R, T3_R_RSP = newElement()
      // Result printing
      val RESULT, RESULT_SEQ = newElement()
      // Loop
      val LOOP_MSG, LOOP_HEX, LOOP_CRLF = newElement()
    }

    val state = RegInit(S.INIT_WAIT)
    val retState = Reg(S())

    // Init counter (wait after MIG calibration)
    val initCnt = Reg(UInt(24 bits)) init (0)

    // Print state
    val msgBase = Reg(UInt(7 bits))
    val msgLen = Reg(UInt(7 bits))
    val msgIdx = Reg(UInt(7 bits))

    // Hex print state
    val hexVal = Reg(Bits(32 bits))
    val hexNib = Reg(UInt(4 bits))

    // BMB address/data
    val addr = Reg(UInt(24 bits)) init (0)
    val wrData = Reg(Bits(32 bits)) init (0)
    val rdData = Reg(Bits(32 bits)) init (0)
    val expected = Reg(Bits(32 bits)) init (0)

    // Test iteration
    val idx = Reg(UInt(12 bits)) init (0)
    val cnt = Reg(UInt(12 bits)) init (0)

    // Test results
    val failCnt = Reg(UInt(16 bits)) init (0)
    val failAddr = Reg(UInt(24 bits)) init (0)
    val failExp = Reg(Bits(32 bits)) init (0)
    val failGot = Reg(Bits(32 bits)) init (0)
    val testNum = Reg(UInt(4 bits)) init (0)
    val loopCnt = Reg(UInt(16 bits)) init (0)
    val resultSeq = Reg(UInt(4 bits)) init (0)

    // Copy buffer
    val copyBuf = Reg(Bits(32 bits))

    // LED register (active low on board)
    val ledReg = Reg(Bits(2 bits)) init (0)

    // Test regions (word addresses)
    def T1_BASE = U(0x000, 24 bits)
    def T1_CNT  = U(1024, 12 bits)
    def T2_SRC  = U(0x1000, 24 bits)
    def T2_DST  = U(0x2000, 24 bits)
    def T2_CNT  = U(512, 12 bits)
    def T3_BASE = U(0x3000, 24 bits)
    def T3_CNT  = U(256, 12 bits)

    // Pattern: addr XOR 0xA5A5A5A5
    def pat(a: UInt): Bits = (a.resize(32).asBits ^ B(0xA5A5A5A5L, 32 bits))

    // ==================================================================
    // BMB Interface
    // ==================================================================
    val bmb = bmbBridge.io.bmb
    val bmbIsWrite = Reg(Bool()) init (False)

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
      // Init: wait after MIG calibration (reset released by ui_clk_sync_rst)
      // ================================================================
      is(S.INIT_WAIT) {
        initCnt := initCnt + 1
        when(initCnt.andR) {
          msgBase := 0; msgLen := 11; msgIdx := 0 // "DDR3 TEST\r\n"
          retState := S.T1_LABEL; state := S.PRINT_MSG
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
      // Test 1: Sequential write + readback (1024 words)
      // ================================================================
      is(S.T1_LABEL) {
        testNum := 1; idx := 0; cnt := T1_CNT; failCnt := 0
        msgBase := 11; msgLen := 9; msgIdx := 0 // "T1:SEQWR "
        retState := S.T1_W; state := S.PRINT_MSG
      }
      is(S.T1_W) {
        val a = T1_BASE + idx.resized
        addr := a
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"1"
        bmb.cmd.fragment.address := (a << 2).resized
        bmb.cmd.fragment.data := pat(a)
        when(bmb.cmd.fire) { state := S.T1_W_RSP }
      }
      is(S.T1_W_RSP) {
        when(bmb.rsp.fire) {
          idx := idx + 1
          when(idx + 1 >= cnt) {
            idx := 0
            state := S.T1_R
          } otherwise {
            state := S.T1_W
          }
        }
      }
      is(S.T1_R) {
        val a = T1_BASE + idx.resized
        addr := a
        expected := pat(a)
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"0"
        bmb.cmd.fragment.address := (a << 2).resized
        when(bmb.cmd.fire) { state := S.T1_R_RSP }
      }
      is(S.T1_R_RSP) {
        when(bmb.rsp.fire) {
          when(bmb.rsp.fragment.data =/= expected) {
            recordFail(bmb.rsp.fragment.data)
          }
          idx := idx + 1
          when(idx + 1 >= cnt) {
            state := S.RESULT
          } otherwise {
            state := S.T1_R
          }
        }
      }

      // ================================================================
      // Test 2: memCopy (fill src -> copy -> verify dst)
      // ================================================================
      is(S.T2_LABEL) {
        testNum := 2; idx := 0; cnt := T2_CNT; failCnt := 0
        msgBase := 20; msgLen := 9; msgIdx := 0 // "T2:COPY  "
        retState := S.T2_F; state := S.PRINT_MSG
      }
      // Fill source region
      is(S.T2_F) {
        val a = T2_SRC + idx.resized
        addr := a
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"1"
        bmb.cmd.fragment.address := (a << 2).resized
        bmb.cmd.fragment.data := pat(a)
        when(bmb.cmd.fire) { state := S.T2_F_RSP }
      }
      is(S.T2_F_RSP) {
        when(bmb.rsp.fire) {
          idx := idx + 1
          when(idx + 1 >= cnt) {
            idx := 0
            state := S.T2_CR
          } otherwise {
            state := S.T2_F
          }
        }
      }
      // Copy: read from src
      is(S.T2_CR) {
        val a = T2_SRC + idx.resized
        addr := a
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"0"
        bmb.cmd.fragment.address := (a << 2).resized
        when(bmb.cmd.fire) { state := S.T2_CR_RSP }
      }
      is(S.T2_CR_RSP) {
        when(bmb.rsp.fire) {
          copyBuf := bmb.rsp.fragment.data
          state := S.T2_CW
        }
      }
      // Copy: write to dst
      is(S.T2_CW) {
        val a = T2_DST + idx.resized
        addr := a
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"1"
        bmb.cmd.fragment.address := (a << 2).resized
        bmb.cmd.fragment.data := copyBuf
        when(bmb.cmd.fire) { state := S.T2_CW_RSP }
      }
      is(S.T2_CW_RSP) {
        when(bmb.rsp.fire) {
          idx := idx + 1
          when(idx + 1 >= cnt) {
            idx := 0
            state := S.T2_V
          } otherwise {
            state := S.T2_CR
          }
        }
      }
      // Verify destination
      is(S.T2_V) {
        val a = T2_DST + idx.resized
        addr := a
        expected := pat(T2_SRC + idx.resized)
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"0"
        bmb.cmd.fragment.address := (a << 2).resized
        when(bmb.cmd.fire) { state := S.T2_V_RSP }
      }
      is(S.T2_V_RSP) {
        when(bmb.rsp.fire) {
          when(bmb.rsp.fragment.data =/= expected) {
            recordFail(bmb.rsp.fragment.data)
          }
          idx := idx + 1
          when(idx + 1 >= cnt) {
            state := S.RESULT
          } otherwise {
            state := S.T2_V
          }
        }
      }

      // ================================================================
      // Test 3: Write-then-read same address (256 addrs)
      // ================================================================
      is(S.T3_LABEL) {
        testNum := 3; idx := 0; cnt := T3_CNT; failCnt := 0
        msgBase := 29; msgLen := 9; msgIdx := 0 // "T3:WRTRD "
        retState := S.T3_W; state := S.PRINT_MSG
      }
      is(S.T3_W) {
        val a = T3_BASE + idx.resized
        addr := a
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"1"
        bmb.cmd.fragment.address := (a << 2).resized
        bmb.cmd.fragment.data := pat(a)
        when(bmb.cmd.fire) { state := S.T3_W_RSP }
      }
      is(S.T3_W_RSP) {
        when(bmb.rsp.fire) {
          state := S.T3_R
        }
      }
      is(S.T3_R) {
        val a = T3_BASE + idx.resized
        addr := a
        expected := pat(a)
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"0"
        bmb.cmd.fragment.address := (a << 2).resized
        when(bmb.cmd.fire) { state := S.T3_R_RSP }
      }
      is(S.T3_R_RSP) {
        when(bmb.rsp.fire) {
          when(bmb.rsp.fragment.data =/= expected) {
            recordFail(bmb.rsp.fragment.data)
          }
          idx := idx + 1
          when(idx + 1 >= cnt) {
            state := S.RESULT
          } otherwise {
            state := S.T3_W
          }
        }
      }

      // ================================================================
      // Result: print PASS or FAIL with details, then next test
      // ================================================================
      is(S.RESULT) {
        resultSeq := 0
        state := S.RESULT_SEQ
      }
      is(S.RESULT_SEQ) {
        switch(resultSeq) {
          is(0) {
            when(failCnt === 0) {
              msgBase := 38; msgLen := 6; msgIdx := 0 // "PASS\r\n"
              retState := S.RESULT_SEQ; resultSeq := 10
              state := S.PRINT_MSG
            } otherwise {
              msgBase := 44; msgLen := 5; msgIdx := 0 // "FAIL "
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
            msgBase := 49; msgLen := 3; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 3
            state := S.PRINT_MSG
          }
          is(3) { // hex(failExp)
            hexVal := failExp; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 4
            state := S.PRINT_HEX
          }
          is(4) { // " G="
            msgBase := 52; msgLen := 3; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 5
            state := S.PRINT_MSG
          }
          is(5) { // hex(failGot)
            hexVal := failGot; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 6
            state := S.PRINT_HEX
          }
          is(6) { // "\r\n"
            msgBase := 55; msgLen := 2; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 10
            state := S.PRINT_MSG
          }
          is(10) { // Advance to next test
            switch(testNum) {
              is(1) { state := S.T2_LABEL }
              is(2) { state := S.T3_LABEL }
              is(3) { state := S.LOOP_MSG }
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
        msgBase := 57; msgLen := 5; msgIdx := 0 // "LOOP "
        retState := S.LOOP_HEX
        state := S.PRINT_MSG
      }
      is(S.LOOP_HEX) {
        hexVal := loopCnt.asBits.resized; hexNib := 0
        retState := S.LOOP_CRLF
        state := S.PRINT_HEX
      }
      is(S.LOOP_CRLF) {
        msgBase := 55; msgLen := 2; msgIdx := 0 // "\r\n"
        retState := S.T1_LABEL
        state := S.PRINT_MSG
      }
    }
  }

  // ========================================================================
  // Board Clock Domain Area (heartbeat, calibration sync, LED driver)
  // ========================================================================

  val hbCounter = Reg(UInt(26 bits)) init (0)
  hbCounter := hbCounter + 1

  val migCalibSync = BufferCC(mig.io.init_calib_complete, init = False)
  val ledRegSync = BufferCC(mainArea.ledReg, init = B(0, 2 bits))

  // LED: bit 0 = heartbeat, bit 1 = MIG calibration, bits 3:2 = loop counter
  io.led(0) := hbCounter.msb
  io.led(1) := migCalibSync
  io.led(3 downto 2) := ledRegSync
  io.led(7 downto 4) := 0
}

/**
 * Generate Verilog for Ddr3ExerciserTop
 */
object Ddr3ExerciserTopVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = LOW
    )
  ).generate(InOutWrapper(Ddr3ExerciserTop()))

  println("Generated: spinalhdl/generated/Ddr3ExerciserTop.v")
}
