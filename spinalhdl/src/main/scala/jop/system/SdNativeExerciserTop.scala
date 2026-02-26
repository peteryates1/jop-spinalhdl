package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import spinal.lib.io.{InOutWrapper, TriState}
import jop.io.BmbSdNative

/**
 * SD Native Mode Exerciser FPGA Top
 *
 * Exercises BmbSdNative with a real SD card in native 1-bit mode on the
 * QMTECH EP4CGX150 + DB_FPGA board. Performs card initialization, writes
 * a data block, reads it back, and reports results via UART at 1 Mbaud.
 *
 * Tests (loop continuously):
 *   T1: DETECT — check card presence
 *   INIT    — CMD0/CMD8/ACMD41/CMD2/CMD3/CMD7 initialization sequence
 *   T2: WRITE — fill FIFO, CMD24 write block 1000
 *   T3: READ  — CMD17 read block 1000, compare data
 */
case class SdNativeExerciserTop() extends Component {

  val io = new Bundle {
    val clk_in  = in Bool()
    val ser_txd = out Bool()
    val led     = out Bits(2 bits)
    val sd_clk  = out Bool()
    val sd_cmd  = master(TriState(Bool))
    val sd_dat_0 = master(TriState(Bool))
    val sd_dat_1 = master(TriState(Bool))
    val sd_dat_2 = master(TriState(Bool))
    val sd_dat_3 = master(TriState(Bool))
    val sd_cd   = in Bool()
  }

  noIoPrefix()

  // PLL: 50 MHz -> 80 MHz system clock
  val pll = DramPll()
  pll.io.inclk0 := io.clk_in
  pll.io.areset := False

  // Reset generator
  val rawClockDomain = ClockDomain(
    clock = pll.io.c1,
    config = ClockDomainConfig(resetKind = BOOT)
  )
  val resetGen = new ClockingArea(rawClockDomain) {
    val res_cnt = Reg(UInt(3 bits)) init (0)
    when(pll.io.locked && res_cnt =/= 7) {
      res_cnt := res_cnt + 1
    }
    val int_res = !pll.io.locked || !res_cnt(0) || !res_cnt(1) || !res_cnt(2)
  }
  val mainClockDomain = ClockDomain(
    clock = pll.io.c1,
    reset = resetGen.int_res,
    frequency = FixedFrequency(80 MHz),
    config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
  )

  // ========================================================================
  // Main Design
  // ========================================================================

  val mainArea = new ClockingArea(mainClockDomain) {

    // SD Native controller (clkDiv=99 -> 400kHz at 80MHz for init)
    val sd = BmbSdNative(clkDivInit = 99)

    // SD pin wiring
    io.sd_clk := sd.io.sdClk

    io.sd_cmd.write       := sd.io.sdCmd.write
    io.sd_cmd.writeEnable := sd.io.sdCmd.writeEnable
    sd.io.sdCmd.read      := io.sd_cmd.read

    io.sd_dat_0.write       := sd.io.sdDat.write(0)
    io.sd_dat_0.writeEnable := sd.io.sdDat.writeEnable(0)
    io.sd_dat_1.write       := sd.io.sdDat.write(1)
    io.sd_dat_1.writeEnable := sd.io.sdDat.writeEnable(1)
    io.sd_dat_2.write       := sd.io.sdDat.write(2)
    io.sd_dat_2.writeEnable := sd.io.sdDat.writeEnable(2)
    io.sd_dat_3.write       := sd.io.sdDat.write(3)
    io.sd_dat_3.writeEnable := sd.io.sdDat.writeEnable(3)
    sd.io.sdDat.read := io.sd_dat_3.read ## io.sd_dat_2.read ## io.sd_dat_1.read ## io.sd_dat_0.read

    sd.io.sdCd := io.sd_cd

    // Default register interface
    sd.io.addr   := 0
    sd.io.rd     := False
    sd.io.wr     := False
    sd.io.wrData := 0

    // UART TX (1 Mbaud at 80 MHz)
    val uartCtrl = new UartCtrl(UartCtrlGenerics(
      preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1
    ))
    uartCtrl.io.config.setClockDivider(1000000 Hz)
    uartCtrl.io.config.frame.dataLength := 7
    uartCtrl.io.config.frame.parity := UartParityType.NONE
    uartCtrl.io.config.frame.stop := UartStopType.ONE
    uartCtrl.io.writeBreak := False
    uartCtrl.io.uart.rxd := True
    io.ser_txd := uartCtrl.io.uart.txd

    val txFifo = StreamFifo(Bits(8 bits), 256)
    uartCtrl.io.write.valid := txFifo.io.pop.valid
    uartCtrl.io.write.payload := txFifo.io.pop.payload
    txFifo.io.pop.ready := uartCtrl.io.write.ready

    txFifo.io.push.valid := False
    txFifo.io.push.payload := 0

    // ====================================================================
    // Message ROM
    // ====================================================================
    val msgBytes: Seq[Int] = {
      //  0: "SD NATIVE TEST\r\n"  (16)
      val m0 = "SD NATIVE TEST\r\n"
      // 16: "T1:DETECT "          (10)
      val m1 = "T1:DETECT "
      // 26: "INIT      "          (10)
      val m2 = "INIT      "
      // 36: "T2:WRITE  "          (10)
      val m3 = "T2:WRITE  "
      // 46: "T3:READ   "          (10)
      val m4 = "T3:READ   "
      // 56: "PASS\r\n"            (6)
      val m5 = "PASS\r\n"
      // 62: "FAIL\r\n"            (6)
      val m6 = "FAIL\r\n"
      // 68: "\r\n"                (2)
      val m7 = "\r\n"
      // 70: "LOOP "               (5)
      val m8 = "LOOP "
      // 75: "FAIL "               (5) - for debug with hex
      val m9 = "FAIL "
      (m0 + m1 + m2 + m3 + m4 + m5 + m6 + m7 + m8 + m9).map(_.toInt)
    }
    val msgRom = Mem(Bits(8 bits), initialContent = msgBytes.map(c => B(c & 0xFF, 8 bits)))

    // ====================================================================
    // State Machine
    // ====================================================================
    object S extends SpinalEnum {
      val INIT_WAIT = newElement()
      val PRINT_MSG, PRINT_HEX = newElement()

      // T1: Detect
      val T1_LABEL, T1_READ_STATUS, T1_RESULT = newElement()

      // INIT: CMD0, CMD8, ACMD41, CMD2, CMD3, CMD7
      val INIT_LABEL, INIT_SLOW_CLK, INIT_CMD0_DELAY, INIT_CMD_DELAY = newElement()
      val INIT_CMD0_ARG, INIT_CMD0_IDX, INIT_CMD0_SEND, INIT_CMD0_WAIT = newElement()
      val INIT_CMD8_ARG, INIT_CMD8_IDX, INIT_CMD8_SEND, INIT_CMD8_WAIT, INIT_CMD8_STATUS, INIT_CMD8_CHECK = newElement()
      val INIT_CMD55_ARG, INIT_CMD55_IDX, INIT_CMD55_SEND, INIT_CMD55_WAIT = newElement()
      val INIT_ACMD41_ARG, INIT_ACMD41_IDX, INIT_ACMD41_SEND, INIT_ACMD41_WAIT, INIT_ACMD41_STATUS, INIT_ACMD41_READOCR = newElement()
      val INIT_CMD2_ARG, INIT_CMD2_IDX, INIT_CMD2_SEND, INIT_CMD2_WAIT = newElement()
      val INIT_CMD3_ARG, INIT_CMD3_IDX, INIT_CMD3_SEND, INIT_CMD3_WAIT, INIT_CMD3_GETRCA = newElement()
      val INIT_CMD7_ARG, INIT_CMD7_IDX, INIT_CMD7_SEND, INIT_CMD7_WAIT = newElement()
      val INIT_SETDIV = newElement()
      val INIT_RESULT, INIT_FAIL_HEX1, INIT_FAIL_HEX2, INIT_FAIL_CRLF = newElement()

      // T2: Write block
      val T2_LABEL, T2_FILL_FIFO = newElement()
      val T2_CMD24_ARG, T2_CMD24_IDX, T2_CMD24_SEND, T2_CMD24_WAIT = newElement()
      val T2_START_WRITE, T2_WRITE_WAIT, T2_RESULT = newElement()

      // T3: Read block
      val T3_LABEL = newElement()
      val T3_CMD17_ARG, T3_CMD17_IDX, T3_CMD17_SEND, T3_CMD17_WAIT = newElement()
      val T3_START_READ, T3_READ_WAIT, T3_VERIFY, T3_VERIFY_SETTLE, T3_RESULT = newElement()

      // Loop
      val LOOP_MSG, LOOP_HEX, LOOP_CRLF = newElement()
    }

    val state = RegInit(S.INIT_WAIT)
    val retState = Reg(S())

    // Init counter (28 bits at 80MHz = ~3.4s delay for UART monitor attach)
    val initCnt = Reg(UInt(28 bits)) init (0)

    // Print state
    val msgBase = Reg(UInt(7 bits))
    val msgLen = Reg(UInt(7 bits))
    val msgIdx = Reg(UInt(7 bits))

    // Hex print state
    val hexVal = Reg(Bits(32 bits))
    val hexNib = Reg(UInt(4 bits))

    // Test results
    val testPass = Reg(Bool()) init (False)
    val loopCnt = Reg(UInt(16 bits)) init (0)

    // SD init state
    val rca = Reg(Bits(16 bits)) init (0)
    val sdhcMode = Reg(Bool()) init (False) // CCS bit from ACMD41: True=SDHC(block addr), False=SDSC(byte addr)
    val acmd41Cnt = Reg(UInt(16 bits)) init (0)
    val cmd0Cnt = Reg(UInt(3 bits)) init (0)  // CMD0 repeat counter
    val delayCnt = Reg(UInt(20 bits)) init (0) // Delay counter (~13ms at 80MHz)

    // Data verification
    val fifoIdx = Reg(UInt(8 bits)) init (0)
    val verifyFail = Reg(Bool()) init (False)

    // Debug: track init step and status on failure
    val dbgStep = Reg(Bits(8 bits)) init (0)
    val dbgStatus = Reg(Bits(32 bits)) init (0)

    // SD block number for tests (SDHC: block #, SDSC: byte address = block# * 512)
    val testBlockAddr = Mux(sdhcMode, U(1000, 32 bits), U(1000 * 512, 32 bits))

    // LED
    val ledReg = Reg(Bits(2 bits)) init (0)
    io.led := ~ledReg

    // ====================================================================
    // Helper: write a register
    // ====================================================================
    def sdWrite(address: UInt, data: Bits): Unit = {
      sd.io.addr   := address
      sd.io.wr     := True
      sd.io.wrData := data
    }

    def sdRead(address: UInt): Unit = {
      sd.io.addr := address
      sd.io.rd   := True
    }

    // ====================================================================
    // State Machine Body
    // ====================================================================
    switch(state) {

      // ================================================================
      // Init: wait for power-up
      // ================================================================
      is(S.INIT_WAIT) {
        initCnt := initCnt + 1
        when(initCnt.andR) {
          msgBase := 0; msgLen := 16; msgIdx := 0
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
      // T1: Detect card
      // ================================================================
      is(S.T1_LABEL) {
        msgBase := 16; msgLen := 10; msgIdx := 0
        retState := S.T1_READ_STATUS; state := S.PRINT_MSG
      }
      is(S.T1_READ_STATUS) {
        // Read status register (addr 0), check bit 7 (cardPresent)
        sdRead(U(0, 4 bits))
        testPass := sd.io.rdData(7)
        state := S.T1_RESULT
      }
      is(S.T1_RESULT) {
        when(testPass) {
          msgBase := 56; msgLen := 6; msgIdx := 0 // "PASS\r\n"
          retState := S.INIT_LABEL; state := S.PRINT_MSG
        } otherwise {
          msgBase := 62; msgLen := 6; msgIdx := 0 // "FAIL\r\n"
          retState := S.INIT_LABEL; state := S.PRINT_MSG
        }
      }

      // ================================================================
      // INIT: Full SD card initialization sequence
      // ================================================================
      is(S.INIT_LABEL) {
        testPass := True
        acmd41Cnt := 0
        msgBase := 26; msgLen := 10; msgIdx := 0 // "INIT      "
        retState := S.INIT_SLOW_CLK; state := S.PRINT_MSG
      }

      // Reset clock divider to slow speed for init (99 -> 400kHz)
      is(S.INIT_SLOW_CLK) {
        sdWrite(U(6, 4 bits), B(99, 32 bits))
        cmd0Cnt := 0
        delayCnt := 0
        state := S.INIT_CMD0_DELAY
      }

      // Wait ~1ms (80,000 cycles) for clock to stabilize and card power-up clocks
      is(S.INIT_CMD0_DELAY) {
        delayCnt := delayCnt + 1
        when(delayCnt === 80000) {
          delayCnt := 0
          state := S.INIT_CMD0_ARG
        }
      }

      // Inter-command delay: 2000 cycles (~25μs) to meet Ncc (8 SD clocks at 400kHz)
      is(S.INIT_CMD_DELAY) {
        delayCnt := delayCnt + 1
        when(delayCnt === 2000) {
          delayCnt := 0
          state := retState
        }
      }

      // CMD0: GO_IDLE_STATE (no response expected) - send 3 times for reliability
      is(S.INIT_CMD0_ARG) {
        sdWrite(U(1, 4 bits), B(0, 32 bits))  // argument = 0
        state := S.INIT_CMD0_IDX
      }
      is(S.INIT_CMD0_IDX) {
        // index=0, no response expected (bit6=0, bit7=0)
        sdWrite(U(2, 4 bits), B(0, 32 bits))
        state := S.INIT_CMD0_SEND
      }
      is(S.INIT_CMD0_SEND) {
        // Trigger sendCmd (bit 0 of control reg)
        sdWrite(U(0, 4 bits), B(1, 32 bits))
        state := S.INIT_CMD0_WAIT
      }
      is(S.INIT_CMD0_WAIT) {
        sdRead(U(0, 4 bits))
        when(!sd.io.rdData(0)) { // cmdBusy cleared
          cmd0Cnt := cmd0Cnt + 1
          when(cmd0Cnt < 2) {
            // Send CMD0 again (3 times total)
            delayCnt := 0
            state := S.INIT_CMD0_DELAY
          } otherwise {
            // After 3 CMD0s, delay then proceed to CMD8
            delayCnt := 0
            retState := S.INIT_CMD8_ARG
            state := S.INIT_CMD_DELAY
          }
        }
      }

      // CMD8: SEND_IF_COND (arg=0x1AA, expect short response)
      is(S.INIT_CMD8_ARG) {
        sdWrite(U(1, 4 bits), B(0x000001AA, 32 bits))
        state := S.INIT_CMD8_IDX
      }
      is(S.INIT_CMD8_IDX) {
        // index=8, expect response (bit6=1)
        sdWrite(U(2, 4 bits), B((8 | 0x40), 32 bits))
        state := S.INIT_CMD8_SEND
      }
      is(S.INIT_CMD8_SEND) {
        sdWrite(U(0, 4 bits), B(1, 32 bits))
        state := S.INIT_CMD8_WAIT
      }
      is(S.INIT_CMD8_WAIT) {
        sdRead(U(0, 4 bits))
        when(!sd.io.rdData(0)) { // cmdBusy cleared
          state := S.INIT_CMD8_STATUS
        }
      }
      is(S.INIT_CMD8_STATUS) {
        // Check status for cmdTimeout (bit 3) — if CMD8 timed out, skip check
        sdRead(U(0, 4 bits))
        when(sd.io.rdData(3)) {
          // CMD8 timed out — old MMC card or issue; set debug and continue
          dbgStep := 0x08
          dbgStatus := sd.io.rdData
          delayCnt := 0; retState := S.INIT_CMD55_ARG; state := S.INIT_CMD_DELAY
        } otherwise {
          state := S.INIT_CMD8_CHECK
        }
      }
      is(S.INIT_CMD8_CHECK) {
        // Read response register 1 (addr 1), check lower 12 bits = 0x1AA
        sdRead(U(1, 4 bits))
        when((sd.io.rdData(11 downto 0)) =/= B(0x1AA, 12 bits)) {
          testPass := False
          dbgStep := 0x18 // CMD8 response mismatch
          dbgStatus := sd.io.rdData
        }
        delayCnt := 0; retState := S.INIT_CMD55_ARG; state := S.INIT_CMD_DELAY
      }

      // CMD55+ACMD41 loop: APP_CMD + SD_SEND_OP_COND
      is(S.INIT_CMD55_ARG) {
        sdWrite(U(1, 4 bits), B(0, 32 bits))  // argument = 0 (RCA=0 during init)
        state := S.INIT_CMD55_IDX
      }
      is(S.INIT_CMD55_IDX) {
        // index=55, expect response (bit6=1)
        sdWrite(U(2, 4 bits), B((55 | 0x40), 32 bits))
        state := S.INIT_CMD55_SEND
      }
      is(S.INIT_CMD55_SEND) {
        sdWrite(U(0, 4 bits), B(1, 32 bits))
        state := S.INIT_CMD55_WAIT
      }
      is(S.INIT_CMD55_WAIT) {
        sdRead(U(0, 4 bits))
        when(!sd.io.rdData(0)) {
          delayCnt := 0; retState := S.INIT_ACMD41_ARG; state := S.INIT_CMD_DELAY
        }
      }

      is(S.INIT_ACMD41_ARG) {
        // HCS bit (bit 30) + full voltage window (2.7-3.6V)
        sdWrite(U(1, 4 bits), B(0x40FF8000L, 32 bits))
        state := S.INIT_ACMD41_IDX
      }
      is(S.INIT_ACMD41_IDX) {
        // index=41, expect response (bit6=1)
        sdWrite(U(2, 4 bits), B((41 | 0x40), 32 bits))
        state := S.INIT_ACMD41_SEND
      }
      is(S.INIT_ACMD41_SEND) {
        sdWrite(U(0, 4 bits), B(1, 32 bits))
        state := S.INIT_ACMD41_WAIT
      }
      is(S.INIT_ACMD41_WAIT) {
        sdRead(U(0, 4 bits))
        when(!sd.io.rdData(0)) {
          state := S.INIT_ACMD41_STATUS
        }
      }
      // First check status register for cmdTimeout
      is(S.INIT_ACMD41_STATUS) {
        sdRead(U(0, 4 bits))
        when(sd.io.rdData(3)) {
          // cmdTimeout: card didn't respond to ACMD41
          acmd41Cnt := acmd41Cnt + 1
          when(acmd41Cnt >= 500) {
            testPass := False
            dbgStep := 0x41 // ACMD41 timeout (no response)
            dbgStatus := sd.io.rdData // status register
            state := S.INIT_RESULT
          } otherwise {
            delayCnt := 0; retState := S.INIT_CMD55_ARG; state := S.INIT_CMD_DELAY
          }
        } otherwise {
          state := S.INIT_ACMD41_READOCR
        }
      }
      // Then read OCR from response register
      is(S.INIT_ACMD41_READOCR) {
        sdRead(U(1, 4 bits))
        when(sd.io.rdData(31)) {
          // Card ready (busy bit set) — save CCS (bit 30) for addressing mode
          sdhcMode := sd.io.rdData(30) // CCS=1: SDHC (block addr), CCS=0: SDSC (byte addr)
          delayCnt := 0; retState := S.INIT_CMD2_ARG; state := S.INIT_CMD_DELAY
        } otherwise {
          acmd41Cnt := acmd41Cnt + 1
          when(acmd41Cnt >= 500) {
            testPass := False
            dbgStep := 0x42 // ACMD41 OCR not ready
            dbgStatus := sd.io.rdData // OCR value
            state := S.INIT_RESULT
          } otherwise {
            delayCnt := 0; retState := S.INIT_CMD55_ARG; state := S.INIT_CMD_DELAY
          }
        }
      }

      // CMD2: ALL_SEND_CID (long response)
      is(S.INIT_CMD2_ARG) {
        sdWrite(U(1, 4 bits), B(0, 32 bits))
        state := S.INIT_CMD2_IDX
      }
      is(S.INIT_CMD2_IDX) {
        // index=2, expect long response (bit6=1, bit7=1)
        sdWrite(U(2, 4 bits), B((2 | 0x40 | 0x80), 32 bits))
        state := S.INIT_CMD2_SEND
      }
      is(S.INIT_CMD2_SEND) {
        sdWrite(U(0, 4 bits), B(1, 32 bits))
        state := S.INIT_CMD2_WAIT
      }
      is(S.INIT_CMD2_WAIT) {
        sdRead(U(0, 4 bits))
        when(!sd.io.rdData(0)) {
          delayCnt := 0; retState := S.INIT_CMD3_ARG; state := S.INIT_CMD_DELAY
        }
      }

      // CMD3: SEND_RELATIVE_ADDR
      is(S.INIT_CMD3_ARG) {
        sdWrite(U(1, 4 bits), B(0, 32 bits))
        state := S.INIT_CMD3_IDX
      }
      is(S.INIT_CMD3_IDX) {
        // index=3, expect response (bit6=1)
        sdWrite(U(2, 4 bits), B((3 | 0x40), 32 bits))
        state := S.INIT_CMD3_SEND
      }
      is(S.INIT_CMD3_SEND) {
        sdWrite(U(0, 4 bits), B(1, 32 bits))
        state := S.INIT_CMD3_WAIT
      }
      is(S.INIT_CMD3_WAIT) {
        sdRead(U(0, 4 bits))
        when(!sd.io.rdData(0)) {
          state := S.INIT_CMD3_GETRCA
        }
      }
      is(S.INIT_CMD3_GETRCA) {
        // Response contains RCA in bits [31:16]
        sdRead(U(1, 4 bits))
        rca := sd.io.rdData(31 downto 16)
        delayCnt := 0; retState := S.INIT_CMD7_ARG; state := S.INIT_CMD_DELAY
      }

      // CMD7: SELECT_CARD (with RCA)
      is(S.INIT_CMD7_ARG) {
        sdWrite(U(1, 4 bits), (rca ## B(0, 16 bits)))
        state := S.INIT_CMD7_IDX
      }
      is(S.INIT_CMD7_IDX) {
        // index=7, expect response (bit6=1)
        sdWrite(U(2, 4 bits), B((7 | 0x40), 32 bits))
        state := S.INIT_CMD7_SEND
      }
      is(S.INIT_CMD7_SEND) {
        sdWrite(U(0, 4 bits), B(1, 32 bits))
        state := S.INIT_CMD7_WAIT
      }
      is(S.INIT_CMD7_WAIT) {
        sdRead(U(0, 4 bits))
        when(!sd.io.rdData(0)) {
          state := S.INIT_SETDIV
        }
      }

      // Set faster clock after init (divider=3 -> 10MHz at 80MHz)
      // Note: divider=1 (20MHz) causes CMD24 timeouts on this board
      is(S.INIT_SETDIV) {
        sdWrite(U(6, 4 bits), B(3, 32 bits))
        state := S.INIT_RESULT
      }

      is(S.INIT_RESULT) {
        when(testPass) {
          msgBase := 56; msgLen := 6; msgIdx := 0 // "PASS\r\n"
          retState := S.T2_LABEL; state := S.PRINT_MSG
        } otherwise {
          // Print "FAIL " then step hex, then status hex, then CRLF
          msgBase := 75; msgLen := 5; msgIdx := 0 // "FAIL "
          retState := S.INIT_FAIL_HEX1; state := S.PRINT_MSG
        }
      }
      is(S.INIT_FAIL_HEX1) {
        // Print dbgStep as hex
        hexVal := dbgStep.resized; hexNib := 0
        retState := S.INIT_FAIL_HEX2
        state := S.PRINT_HEX
      }
      is(S.INIT_FAIL_HEX2) {
        // Print dbgStatus as hex
        hexVal := dbgStatus; hexNib := 0
        retState := S.INIT_FAIL_CRLF
        state := S.PRINT_HEX
      }
      is(S.INIT_FAIL_CRLF) {
        msgBase := 68; msgLen := 2; msgIdx := 0 // "\r\n"
        retState := S.T2_LABEL; state := S.PRINT_MSG
      }

      // ================================================================
      // T2: Write block
      // ================================================================
      is(S.T2_LABEL) {
        testPass := True; fifoIdx := 0; verifyFail := False
        // Set block length to 512 bytes
        sdWrite(U(9, 4 bits), B(512, 32 bits))
        msgBase := 36; msgLen := 10; msgIdx := 0 // "T2:WRITE  "
        retState := S.T2_FILL_FIFO; state := S.PRINT_MSG
      }

      // Fill FIFO with 128 words of test pattern
      is(S.T2_FILL_FIFO) {
        val pattern = (fifoIdx.resize(32) * U(0x01010101L, 32 bits)).resize(32).asBits ^ B(0xDEADBEEFL, 32 bits)
        sdWrite(U(5, 4 bits), pattern)
        fifoIdx := fifoIdx + 1
        when(fifoIdx === 127) {
          state := S.T2_CMD24_ARG
        }
      }

      // CMD24: WRITE_SINGLE_BLOCK (block 1000)
      is(S.T2_CMD24_ARG) {
        // For SDHC/SDXC, argument is block number; for SDSC, byte address
        sdWrite(U(1, 4 bits), testBlockAddr.asBits)
        state := S.T2_CMD24_IDX
      }
      is(S.T2_CMD24_IDX) {
        sdWrite(U(2, 4 bits), B((24 | 0x40), 32 bits))
        state := S.T2_CMD24_SEND
      }
      is(S.T2_CMD24_SEND) {
        sdWrite(U(0, 4 bits), B(1, 32 bits))
        state := S.T2_CMD24_WAIT
      }
      is(S.T2_CMD24_WAIT) {
        sdRead(U(0, 4 bits))
        when(!sd.io.rdData(0)) { // cmdBusy cleared
          state := S.T2_START_WRITE
        }
      }

      // Start data write
      is(S.T2_START_WRITE) {
        // Write reg 7 bit 1 = startWrite
        sdWrite(U(7, 4 bits), B(2, 32 bits))
        state := S.T2_WRITE_WAIT
      }
      is(S.T2_WRITE_WAIT) {
        // Poll status for dataBusy (bit 4) to clear
        sdRead(U(0, 4 bits))
        when(!sd.io.rdData(4)) {
          // Check for data CRC error (bit 5) when dataBusy clears
          when(sd.io.rdData(5)) { testPass := False }
          state := S.T2_RESULT
        }
      }
      is(S.T2_RESULT) {
        // testPass updated from T2_WRITE_WAIT on previous cycle
        when(testPass) {
          msgBase := 56; msgLen := 6; msgIdx := 0 // "PASS\r\n"
          retState := S.T3_LABEL; state := S.PRINT_MSG
        } otherwise {
          msgBase := 62; msgLen := 6; msgIdx := 0 // "FAIL\r\n"
          retState := S.T3_LABEL; state := S.PRINT_MSG
        }
      }

      // ================================================================
      // T3: Read block
      // ================================================================
      is(S.T3_LABEL) {
        testPass := True; fifoIdx := 0; verifyFail := False
        msgBase := 46; msgLen := 10; msgIdx := 0 // "T3:READ   "
        retState := S.T3_CMD17_ARG; state := S.PRINT_MSG
      }

      // CMD17: READ_SINGLE_BLOCK (block 1000)
      is(S.T3_CMD17_ARG) {
        sdWrite(U(1, 4 bits), testBlockAddr.asBits)
        state := S.T3_CMD17_IDX
      }
      is(S.T3_CMD17_IDX) {
        sdWrite(U(2, 4 bits), B((17 | 0x40), 32 bits))
        state := S.T3_CMD17_SEND
      }
      is(S.T3_CMD17_SEND) {
        sdWrite(U(0, 4 bits), B(1, 32 bits))
        state := S.T3_CMD17_WAIT
      }
      is(S.T3_CMD17_WAIT) {
        sdRead(U(0, 4 bits))
        when(!sd.io.rdData(0)) { // cmdBusy cleared
          state := S.T3_START_READ
        }
      }

      // Start data read
      is(S.T3_START_READ) {
        // Write reg 7 bit 0 = startRead
        sdWrite(U(7, 4 bits), B(1, 32 bits))
        state := S.T3_READ_WAIT
      }
      is(S.T3_READ_WAIT) {
        // Poll status for dataBusy (bit 4) to clear
        sdRead(U(0, 4 bits))
        when(!sd.io.rdData(4)) {
          // Check for data CRC error (bit 5)
          when(sd.io.rdData(5)) {
            testPass := False
          }
          fifoIdx := 0
          state := S.T3_VERIFY
        }
      }

      // Read and verify 128 words from FIFO
      // readSync has 1-cycle latency, so we alternate: read+pop, then settle
      is(S.T3_VERIFY) {
        sdRead(U(5, 4 bits)) // Read from data FIFO (addr 5) — pops FIFO
        val expected = (fifoIdx.resize(32) * U(0x01010101L, 32 bits)).resize(32).asBits ^ B(0xDEADBEEFL, 32 bits)
        when(sd.io.rdData =/= expected) {
          testPass := False
        }
        fifoIdx := fifoIdx + 1
        when(fifoIdx === 127) {
          state := S.T3_RESULT
        } otherwise {
          state := S.T3_VERIFY_SETTLE
        }
      }
      // Settle cycle: let readSync latch new fifoRdPtr before next read
      is(S.T3_VERIFY_SETTLE) {
        // Present addr 5 without rd (no pop), just let readSync update
        sd.io.addr := U(5, 4 bits)
        state := S.T3_VERIFY
      }

      is(S.T3_RESULT) {
        when(testPass) {
          msgBase := 56; msgLen := 6; msgIdx := 0 // "PASS\r\n"
          retState := S.LOOP_MSG; state := S.PRINT_MSG
        } otherwise {
          msgBase := 62; msgLen := 6; msgIdx := 0 // "FAIL\r\n"
          retState := S.LOOP_MSG; state := S.PRINT_MSG
        }
      }

      // ================================================================
      // Loop: print count and restart
      // ================================================================
      is(S.LOOP_MSG) {
        loopCnt := loopCnt + 1
        ledReg := loopCnt.asBits.resized
        msgBase := 70; msgLen := 5; msgIdx := 0 // "LOOP "
        retState := S.LOOP_HEX
        state := S.PRINT_MSG
      }
      is(S.LOOP_HEX) {
        hexVal := loopCnt.asBits.resized; hexNib := 0
        retState := S.LOOP_CRLF
        state := S.PRINT_HEX
      }
      is(S.LOOP_CRLF) {
        msgBase := 68; msgLen := 2; msgIdx := 0 // "\r\n"
        retState := S.T1_LABEL
        state := S.PRINT_MSG
      }
    }
  }
}

/**
 * Generate Verilog for SdNativeExerciserTop
 */
object SdNativeExerciserTopVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(80 MHz)
  ).generate(InOutWrapper(SdNativeExerciserTop()))

  println("Generated: spinalhdl/generated/SdNativeExerciserTop.v")
}
