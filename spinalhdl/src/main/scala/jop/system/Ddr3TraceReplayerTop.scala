package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import spinal.lib.io.InOutWrapper
import jop.ddr3._
import jop.memory.JopMemoryConfig
import jop.utils.TraceFileLoader

/**
 * DDR3 Trace Replayer for Alchitry AU V2
 *
 * Replays exact BMB transactions captured from a GC simulation through real DDR3
 * hardware and verifies every read response against expected values.
 *
 * Flow:
 *   1. Wait for MIG calibration
 *   2. Fill DDR3 with .jop memory image (from initMem BRAM)
 *   3. Replay every BMB transaction from trace (from traceMem BRAM)
 *   4. For reads: compare response against expected data
 *   5. Report PASS/FAIL with mismatch details via UART
 *
 * Same DDR3 path as JopDdr3Top:
 *   BMB -> BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MIG -> DDR3
 *
 * @param initData   Memory init words (32-bit, from gc_mem_init.hex) — just the .jop content
 * @param traceData  Flat trace words (32-bit pairs, from gc_bmb_trace.hex)
 * @param fillSize   Total words to fill (including zeros beyond initData), default 32768 = 128KB
 */
case class Ddr3TraceReplayerTop(
  initData: Seq[BigInt],
  traceData: Seq[BigInt],
  fillSize: Int = 32768
) extends Component {

  val initSize = initData.length
  val traceSize = traceData.length / 2  // number of trace entries (2 words each)

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
  clkWiz.io.resetn := !ClockDomain.current.readResetWire

  // ========================================================================
  // MIG DDR3 Controller
  // ========================================================================

  val mig = new MigBlackBox

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

  mig.io.sys_clk_i := clkWiz.io.clk_100
  mig.io.clk_ref_i := clkWiz.io.clk_200
  mig.io.sys_rst   := !clkWiz.io.locked

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

    val cacheAddrWidth = 28
    val cacheDataWidth = 128

    val bmbBridge = new BmbCacheBridge(bmbParam, cacheAddrWidth, cacheDataWidth)
    val cache = new LruCacheCore(CacheConfig(addrWidth = cacheAddrWidth, dataWidth = cacheDataWidth))
    val adapter = new CacheToMigAdapter

    cache.io.frontend.req << bmbBridge.io.cache.req
    bmbBridge.io.cache.rsp << cache.io.frontend.rsp

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
    // usb_tx driven from board clock domain MUX (see below)

    val txFifo = StreamFifo(Bits(8 bits), 256)
    uartCtrl.io.write.valid := txFifo.io.pop.valid
    uartCtrl.io.write.payload := txFifo.io.pop.payload
    txFifo.io.pop.ready := uartCtrl.io.write.ready

    txFifo.io.push.valid := False
    txFifo.io.push.payload := 0

    // ==================================================================
    // BRAMs (initialized at Verilog generation time)
    // ==================================================================

    val initMem = Mem(Bits(32 bits), initialContent = initData.map(v => B(v, 32 bits)))
    val traceMem = Mem(Bits(32 bits), initialContent = traceData.map(v => B(v, 32 bits)))

    // ==================================================================
    // Message ROM
    // ==================================================================
    val msgBytes: Seq[Int] = {
      //  0: "TRACE REPLAY\r\n"  (14)
      val m0 = "TRACE REPLAY\r\n"
      // 14: "F:"                (2)
      val m1 = "F:"
      // 16: "T:"                (2)
      val m2 = "T:"
      // 18: "\r\n"              (2)
      val m3 = "\r\n"
      // 20: "PASS N="           (7)
      val m4 = "PASS N="
      // 27: "FAIL @"            (6)
      val m5 = "FAIL @"
      // 33: " A="               (3)
      val m6 = " A="
      // 36: " E="               (3)
      val m7 = " E="
      // 39: " G="               (3)
      val m8 = " G="
      // 42: "HANG @"            (6)
      val m9 = "HANG @"
      (m0 + m1 + m2 + m3 + m4 + m5 + m6 + m7 + m8 + m9).map(_.toInt)
    }
    val msgRom = Mem(Bits(8 bits), initialContent = msgBytes.map(c => B(c & 0xFF, 8 bits)))

    // ==================================================================
    // State Machine
    // ==================================================================
    object S extends SpinalEnum {
      val INIT_WAIT = newElement()
      val PRINT_MSG, PRINT_HEX, PRINT_HEX4 = newElement()
      val PROGRESS_HEX, PROGRESS_CRLF = newElement()
      val BANNER = newElement()
      val FILL_RD, FILL_CMD, FILL_RSP = newElement()  // FILL_RD: readSync pipeline stage
      val REPLAY_RD0, REPLAY_RD0W, REPLAY_RD1W = newElement()  // readSync pipeline
      val REPLAY_CMD, REPLAY_RSP = newElement()
      val RESULT_PASS, RESULT_FAIL, RESULT_HANG = newElement()
      val RESULT_SEQ = newElement()
      val DELAY, DONE = newElement()
    }

    val state = RegInit(S.INIT_WAIT)
    val retState = Reg(S())

    // Init counter (wait for MIG calibration)
    val initCnt = Reg(UInt(24 bits)) init(0)

    // Print state
    val msgBase = Reg(UInt(7 bits))
    val msgLen  = Reg(UInt(7 bits))
    val msgIdx  = Reg(UInt(7 bits))

    // Hex print state
    val hexVal = Reg(Bits(32 bits))
    val hexNib = Reg(UInt(4 bits))

    // Fill index (covers full fillSize, not just initSize)
    val fillBits = log2Up(fillSize) + 1
    val fillIdx = Reg(UInt(fillBits bits)) init(0)

    // Trace index
    val traceBits = log2Up(traceSize) + 1
    val traceIdx = Reg(UInt(traceBits bits)) init(0)

    // Synchronous read ports (readSync maps to BRAM; readAsync maps to LUT-ROM).
    // initMem: address defaults to fillIdx, data valid one cycle later.
    val initReadAddr = UInt(log2Up(initData.length) bits)
    initReadAddr := fillIdx.resized
    val initReadData = initMem.readSync(initReadAddr)

    // traceMem: single shared read port for both word0 and word1.
    // Address defaults to even word (traceIdx*2), overridden for odd word reads.
    val traceReadAddr = UInt(log2Up(traceData.length) bits)
    traceReadAddr := (traceIdx << 1).resized
    val traceReadData = traceMem.readSync(traceReadAddr)

    // Trace entry latched from BRAM reads
    val traceWord0 = Reg(Bits(32 bits))  // [31]=isWrite, [27:0]=byte address
    val traceWord1 = Reg(Bits(32 bits))  // data (write data or expected read data)

    // Mismatch recording (first failure only)
    val failEntry = Reg(UInt(traceBits bits))
    val failAddr  = Reg(Bits(28 bits))
    val failExp   = Reg(Bits(32 bits))
    val failGot   = Reg(Bits(32 bits))
    val hasFail   = Reg(Bool()) init(False)

    // Hang watchdog
    val wdCounter = Reg(UInt(20 bits)) init(0)  // 1M cycles timeout

    // Result sequence counter (needs 5 bits for HANG sequence values 20-23)
    val resultSeq = Reg(UInt(5 bits)) init(0)

    // Progress print: where to go after hex4+CRLF chain
    val progressRetState = Reg(S())

    // Result repeat: 0=PASS, 1=FAIL, 2=HANG
    val resultType = Reg(UInt(2 bits)) init(0)
    val delayCnt = Reg(UInt(26 bits)) init(0)  // ~670ms at 100 MHz

    // LED register
    val ledReg = Reg(Bits(6 bits)) init(0)

    // ==================================================================
    // BMB Interface
    // ==================================================================
    val bmb = bmbBridge.io.bmb

    bmb.cmd.valid := False
    bmb.cmd.last := True
    bmb.cmd.fragment.opcode := B"0"
    bmb.cmd.fragment.address := 0
    bmb.cmd.fragment.length := 3
    bmb.cmd.fragment.source := 0
    bmb.cmd.fragment.context := 0
    bmb.cmd.fragment.data := 0
    bmb.cmd.fragment.mask := B"1111"
    bmb.rsp.ready := True

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
          state := S.BANNER
        }
      }

      // ================================================================
      // Banner
      // ================================================================
      is(S.BANNER) {
        msgBase := 0; msgLen := 14; msgIdx := 0
        retState := S.FILL_RD; state := S.PRINT_MSG
        fillIdx := 0
        ledReg(2) := True  // Fill phase active
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
      // Hex print sub-state: 8 hex digits (32-bit value)
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
      // Hex print sub-state: 4 hex digits (16-bit value)
      // ================================================================
      is(S.PRINT_HEX4) {
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
          when(hexNib === 3) {
            state := retState
          }
        }
      }

      // ================================================================
      // Progress print chain: label already printed → hex4 → CRLF → progressRetState
      // ================================================================
      is(S.PROGRESS_HEX) {
        // hexVal already loaded by caller, print 4-digit hex
        hexNib := 0
        retState := S.PROGRESS_CRLF
        state := S.PRINT_HEX4
      }
      is(S.PROGRESS_CRLF) {
        msgBase := 18; msgLen := 2; msgIdx := 0  // "\r\n"
        retState := progressRetState
        state := S.PRINT_MSG
      }

      // ================================================================
      // Fill DDR3: write .jop memory image
      // readSync pipeline: FILL_RD presents address, FILL_CMD uses data
      // ================================================================
      is(S.FILL_RD) {
        // initReadAddr defaults to fillIdx.resized — BRAM samples on this edge
        state := S.FILL_CMD
      }
      is(S.FILL_CMD) {
        val byteAddr = (fillIdx << 2).resize(28)
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"1"  // write
        bmb.cmd.fragment.address := byteAddr.resized
        // Use BRAM data for addresses < initSize, zeros for the rest (heap area)
        bmb.cmd.fragment.data := Mux(fillIdx < initSize, initReadData, B(0, 32 bits))
        when(bmb.cmd.fire) {
          state := S.FILL_RSP
        }
      }
      is(S.FILL_RSP) {
        when(bmb.rsp.fire) {
          fillIdx := fillIdx + 1
          when(fillIdx === fillSize - 1) {
            // Fill complete -> start replay
            traceIdx := 0
            ledReg(2) := False
            ledReg(3) := True  // Replay phase active
            state := S.REPLAY_RD0
          } otherwise {
            // Print progress every 2048 words: "F:XXXX\r\n"
            when(fillIdx(10 downto 0) === 0 && fillIdx =/= 0) {
              hexVal := (fillIdx.asBits << 16).resized
              progressRetState := S.FILL_RD
              msgBase := 14; msgLen := 2; msgIdx := 0  // "F:"
              retState := S.PROGRESS_HEX
              state := S.PRINT_MSG
            } otherwise {
              state := S.FILL_RD  // go through read pipeline again
            }
          }
        }
      }

      // ================================================================
      // Replay: read trace entries from BRAM via readSync pipeline
      // REPLAY_RD0: present even addr (default) → REPLAY_RD0W: latch word0, present odd addr
      // → REPLAY_RD1W: latch word1 → REPLAY_CMD
      // ================================================================
      is(S.REPLAY_RD0) {
        // traceReadAddr defaults to traceIdx*2 — BRAM samples on this edge
        state := S.REPLAY_RD0W
      }
      is(S.REPLAY_RD0W) {
        // readSync data valid: traceMem[traceIdx*2]
        traceWord0 := traceReadData
        // Present odd address for next read
        traceReadAddr := ((traceIdx << 1) | 1).resized
        state := S.REPLAY_RD1W
      }
      is(S.REPLAY_RD1W) {
        // readSync data valid: traceMem[traceIdx*2+1]
        traceWord1 := traceReadData
        state := S.REPLAY_CMD
      }
      is(S.REPLAY_CMD) {
        // Decode trace entry
        val isWrite = traceWord0(31)
        val byteAddr = traceWord0(27 downto 0)
        val data = traceWord1

        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := Mux(isWrite, B"1", B"0")
        bmb.cmd.fragment.address := byteAddr.asUInt.resized
        bmb.cmd.fragment.data := data  // write data (ignored for reads)
        when(bmb.cmd.fire) {
          wdCounter := 0  // Reset watchdog
          state := S.REPLAY_RSP
        }
      }
      is(S.REPLAY_RSP) {
        wdCounter := wdCounter + 1

        when(bmb.rsp.fire) {
          // For reads: compare response data against expected
          val isWrite = traceWord0(31)
          when(!isWrite) {
            when(bmb.rsp.fragment.data =/= traceWord1 && !hasFail) {
              hasFail := True
              failEntry := traceIdx
              failAddr := traceWord0(27 downto 0)
              failExp := traceWord1
              failGot := bmb.rsp.fragment.data
            }
          }

          traceIdx := traceIdx + 1
          when(traceIdx === traceSize - 1) {
            // All entries replayed
            ledReg(3) := False
            when(hasFail) {
              state := S.RESULT_FAIL
            } otherwise {
              state := S.RESULT_PASS
            }
          } otherwise {
            // Print progress every 1024 entries: "T:XXXX\r\n"
            when(traceIdx(9 downto 0) === 0 && traceIdx =/= 0) {
              hexVal := (traceIdx.asBits << 16).resized
              progressRetState := S.REPLAY_RD0
              msgBase := 16; msgLen := 2; msgIdx := 0  // "T:"
              retState := S.PROGRESS_HEX
              state := S.PRINT_MSG
            } otherwise {
              state := S.REPLAY_RD0
            }
          }
        }

        // Hang watchdog: 1M cycles without response
        when(wdCounter.andR) {
          ledReg(3) := False
          state := S.RESULT_HANG
        }
      }

      // ================================================================
      // Results
      // ================================================================

      // PASS N=XXXX\r\n
      is(S.RESULT_PASS) {
        ledReg(4) := True
        resultType := 0
        resultSeq := 0
        state := S.RESULT_SEQ
      }

      // FAIL @XXXX A=XXXXXXX E=XXXXXXXX G=XXXXXXXX\r\n
      is(S.RESULT_FAIL) {
        ledReg(5) := True
        resultType := 1
        resultSeq := 10
        state := S.RESULT_SEQ
      }

      // HANG @XXXX A=XXXXXXX\r\n
      is(S.RESULT_HANG) {
        ledReg(5) := True
        resultType := 2
        resultSeq := 20
        state := S.RESULT_SEQ
      }

      is(S.RESULT_SEQ) {
        switch(resultSeq) {
          // --- PASS sequence ---
          is(0) {  // "PASS N="
            msgBase := 20; msgLen := 7; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 1
            state := S.PRINT_MSG
          }
          is(1) {  // hex4(traceSize)
            hexVal := (U(traceSize, 16 bits) ## U(0, 16 bits)).asBits; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 2
            state := S.PRINT_HEX4
          }
          is(2) {  // "\r\n" → delay then repeat
            msgBase := 18; msgLen := 2; msgIdx := 0
            retState := S.DELAY; state := S.PRINT_MSG
          }

          // --- FAIL sequence ---
          is(10) {  // "FAIL @"
            msgBase := 27; msgLen := 6; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 11
            state := S.PRINT_MSG
          }
          is(11) {  // hex4(failEntry)
            hexVal := (failEntry.asBits << 16).resized; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 12
            state := S.PRINT_HEX4
          }
          is(12) {  // " A="
            msgBase := 33; msgLen := 3; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 13
            state := S.PRINT_MSG
          }
          is(13) {  // hex(failAddr)
            hexVal := failAddr.resized; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 14
            state := S.PRINT_HEX
          }
          is(14) {  // " E="
            msgBase := 36; msgLen := 3; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 15
            state := S.PRINT_MSG
          }
          is(15) {  // hex(failExp)
            hexVal := failExp; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 8
            state := S.PRINT_HEX
          }
          is(8) {  // " G=" (reuse seq number to stay within 4-bit range)
            msgBase := 39; msgLen := 3; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 9
            state := S.PRINT_MSG
          }
          is(9) {  // hex(failGot) + CRLF
            hexVal := failGot; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 2  // reuse CRLF → DONE
            state := S.PRINT_HEX
          }

          // --- HANG sequence ---
          // "HANG @XXXX A=XXXXXXX\r\n"
          is(20) {  // use high bits: remap via offset
            msgBase := 42; msgLen := 6; msgIdx := 0  // "HANG @"
            retState := S.RESULT_SEQ; resultSeq := 21
            state := S.PRINT_MSG
          }
          is(21) {  // hex4(traceIdx) — entry that hung
            hexVal := (traceIdx.asBits << 16).resized; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 22
            state := S.PRINT_HEX4
          }
          is(22) {  // " A="
            msgBase := 33; msgLen := 3; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 23
            state := S.PRINT_MSG
          }
          is(23) {  // hex(address from current trace word0)
            hexVal := traceWord0(27 downto 0).resized; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 2  // CRLF → DONE
            state := S.PRINT_HEX
          }

          default {}
        }
      }

      // ================================================================
      // Delay: wait ~670ms then repeat result message
      // ================================================================
      is(S.DELAY) {
        delayCnt := delayCnt + 1
        when(delayCnt.andR) {
          delayCnt := 0
          switch(resultType) {
            is(0) { state := S.RESULT_PASS }
            is(1) { state := S.RESULT_FAIL }
            is(2) { state := S.RESULT_HANG }
          }
        }
      }

      // ================================================================
      // Done (unused — kept for completeness)
      // ================================================================
      is(S.DONE) {
      }
    }

  }

  // ========================================================================
  // Board Clock Domain Area (boot UART, calibration sync, LED driver)
  // ========================================================================

  val hbCounter = Reg(UInt(26 bits)) init(0)
  hbCounter := hbCounter + 1

  val migCalibSync = BufferCC(mig.io.init_calib_complete, init = False)
  val ledRegSync = BufferCC(mainArea.ledReg, init = B(0, 6 bits))

  io.led(0) := hbCounter.msb               // Heartbeat
  io.led(1) := migCalibSync                 // MIG calibration
  io.led(5 downto 2) := ledRegSync(5 downto 2)  // Fill/Replay/Pass/Fail
  io.led(7 downto 6) := 0

  // ========================================================================
  // Boot UART (board clock domain, bit-bang, diagnostic messages)
  //
  // Sends "BOOT\r\n" immediately at startup, then "CAL OK\r\n" when MIG
  // calibration completes. After that, hands off usb_tx to the main UART
  // in the MIG clock domain.
  //
  // Diagnostic flow:
  //   - See "BOOT" only           → MIG calibration failed
  //   - See "BOOT" + "CAL OK"     → MIG OK, mainArea state machine stuck
  //   - See "BOOT" + "CAL OK" + "TRACE REPLAY" → Everything working
  // ========================================================================

  val bootBaudDiv = 100  // 100 MHz / 1 Mbaud
  val bootBaudCnt = Reg(UInt(7 bits)) init(0)
  val bootBaudTick = bootBaudCnt === (bootBaudDiv - 1)
  when(bootBaudTick) { bootBaudCnt := 0 } otherwise { bootBaudCnt := bootBaudCnt + 1 }

  val bootShift = Reg(Bits(10 bits)) init(B"1111111111")
  val bootBitCnt = Reg(UInt(4 bits)) init(0)
  val bootByteIdx = Reg(UInt(4 bits)) init(0)

  // ROM: "BOOT\r\nCAL OK\r\n" (14 bytes)
  val bootMsgStr = "BOOT\r\nCAL OK\r\n"
  val bootMsgRom = Vec(bootMsgStr.map(c => B(c.toInt & 0xFF, 8 bits)))

  object BootS extends SpinalEnum {
    val LOAD_BYTE, SHIFTING, WAIT_CAL, DONE = newElement()
  }

  val bootState = RegInit(BootS.LOAD_BYTE)
  val bootDone = Reg(Bool()) init(False)

  switch(bootState) {
    is(BootS.LOAD_BYTE) {
      bootShift := B"1" ## bootMsgRom(bootByteIdx) ## B"0"
      bootBitCnt := 0
      bootBaudCnt := 0
      bootState := BootS.SHIFTING
    }
    is(BootS.SHIFTING) {
      when(bootBaudTick) {
        when(bootBitCnt === 9) {
          // Byte complete
          bootByteIdx := bootByteIdx + 1
          when(bootByteIdx === 5) {
            // "BOOT\r\n" sent (bytes 0-5), wait for MIG calibration
            bootState := BootS.WAIT_CAL
          } elsewhen(bootByteIdx === 13) {
            // "CAL OK\r\n" sent (bytes 6-13), hand off to main UART
            bootDone := True
            bootState := BootS.DONE
          } otherwise {
            bootState := BootS.LOAD_BYTE
          }
        } otherwise {
          bootShift := B"1" ## bootShift(9 downto 1)
          bootBitCnt := bootBitCnt + 1
        }
      }
    }
    is(BootS.WAIT_CAL) {
      when(migCalibSync) {
        bootState := BootS.LOAD_BYTE
      }
    }
    is(BootS.DONE) {
      // idle — txd stays HIGH (idle)
    }
  }

  val bootTxd = bootShift(0)

  // UART MUX: boot UART until done, then main UART (synced to board domain)
  val mainTxdSync = BufferCC(mainArea.uartCtrl.io.uart.txd, init = True)
  io.usb_tx := Mux(bootDone, mainTxdSync, bootTxd)
}

/**
 * Generate Verilog for Ddr3TraceReplayerTop
 */
object Ddr3TraceReplayerTopVerilog extends App {
  val initHexPath  = "/home/peter/workspaces/ai/jop/spinalhdl/generated/gc_mem_init.hex"
  val traceHexPath = "/home/peter/workspaces/ai/jop/spinalhdl/generated/gc_bmb_trace.hex"

  // BRAM budget: XC7A35T has 50 × 36Kbit = 225KB total.
  // Cache + FIFOs + overhead ≈ 30KB, leaving ~195KB for init + trace.
  val maxTraceEntries = 16384  // 16K entries × 8B = 128KB

  println(s"Loading init hex: $initHexPath")
  val initData = TraceFileLoader.loadInitHex(initHexPath)
  println(s"  ${initData.length} words (${initData.length * 4 / 1024} KB)")

  println(s"Loading trace hex: $traceHexPath")
  val traceDataFull = TraceFileLoader.loadTraceHex(traceHexPath)
  val fullEntryCount = traceDataFull.length / 2
  println(s"  ${fullEntryCount} entries (${traceDataFull.length * 4 / 1024} KB)")

  // Truncate trace if needed to fit BRAM
  val traceDataFlat = if (fullEntryCount > maxTraceEntries) {
    println(s"  Truncating to ${maxTraceEntries} entries to fit BRAM budget")
    traceDataFull.take(maxTraceEntries * 2)
  } else {
    traceDataFull
  }
  val traceEntryCount = traceDataFlat.length / 2

  val initKB = initData.length * 4 / 1024
  val traceKB = traceDataFlat.length * 4 / 1024
  val totalKB = initKB + traceKB
  println(s"\nBRAM usage:")
  println(s"  Init memory:  ${initData.length} words = ${initKB} KB")
  println(s"  Trace memory: ${traceEntryCount} entries = ${traceKB} KB")
  println(s"  Total data:   ${totalKB} KB (budget ~195KB)")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = LOW
    )
  ).generate(InOutWrapper(Ddr3TraceReplayerTop(initData, traceDataFlat)))

  println("Generated: spinalhdl/generated/Ddr3TraceReplayerTop.v")
}
