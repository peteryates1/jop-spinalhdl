package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import jop.io.BmbSdSpi

/**
 * SD SPI Mode Exerciser FPGA Top
 *
 * Exercises BmbSdSpi with a real SD card in SPI mode on the
 * QMTECH EP4CGX150 + DB_FPGA board. Performs card initialization, writes
 * a data block, reads it back, and reports results via UART at 1 Mbaud.
 *
 * Register access is split across clock cycles to avoid combinational loops:
 * each state accesses exactly one register address unconditionally.
 *
 * Tests (loop continuously):
 *   T1: DETECT — check card presence
 *   INIT    — 80 clocks, CMD0, CMD8, CMD55+ACMD41 loop, set fast clock
 *   T2: WRITE — CMD24, data token, 512 bytes, CRC, check response
 *   T3: READ  — CMD17, wait for data token, 512 bytes, compare
 */
case class SdSpiExerciserTop() extends Component {

  val io = new Bundle {
    val clk_in     = in Bool()
    val ser_txd    = out Bool()
    val led        = out Bits(2 bits)
    val sd_spi_clk  = out Bool()
    val sd_spi_mosi = out Bool()
    val sd_spi_miso = in Bool()
    val sd_spi_cs   = out Bool()
    val sd_spi_cd   = in Bool()
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

    // SD SPI controller (clkDiv=199 -> ~200kHz at 80MHz for init)
    val sd = BmbSdSpi(clkDivInit = 199)

    // SPI pin wiring
    io.sd_spi_clk  := sd.io.sclk
    io.sd_spi_mosi := sd.io.mosi
    sd.io.miso     := io.sd_spi_miso
    io.sd_spi_cs   := sd.io.cs
    sd.io.cd       := io.sd_spi_cd

    // Default register interface — each state overrides exactly one access
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
      //  0: "SD SPI TEST\r\n"  (13)
      val m0 = "SD SPI TEST\r\n"
      // 13: "T1:DETECT "       (10)
      val m1 = "T1:DETECT "
      // 23: "INIT      "       (10)
      val m2 = "INIT      "
      // 33: "T2:WRITE  "       (10)
      val m3 = "T2:WRITE  "
      // 43: "T3:READ   "       (10)
      val m4 = "T3:READ   "
      // 53: "PASS\r\n"         (6)
      val m5 = "PASS\r\n"
      // 59: "FAIL\r\n"         (6)
      val m6 = "FAIL\r\n"
      // 65: "\r\n"             (2)
      val m7 = "\r\n"
      // 67: "LOOP "            (5)
      val m8 = "LOOP "
      (m0 + m1 + m2 + m3 + m4 + m5 + m6 + m7 + m8).map(_.toInt)
    }
    val msgRom = Mem(Bits(8 bits), initialContent = msgBytes.map(c => B(c & 0xFF, 8 bits)))

    // ====================================================================
    // State Machine
    // ====================================================================
    object S extends SpinalEnum {
      val INIT_WAIT = newElement()
      val PRINT_MSG, PRINT_HEX = newElement()

      // Shared SPI byte sub-routine: send 0xFF, wait busy, read RX
      val SPI_XFER_SEND, SPI_XFER_POLL, SPI_XFER_READRX = newElement()

      // Shared command send sub-routine: send 6 bytes
      val CMD_SEND_BYTE, CMD_SEND_POLL = newElement()

      // T1: Detect
      val T1_LABEL, T1_READ_STATUS, T1_RESULT = newElement()

      // INIT
      val INIT_LABEL = newElement()
      val INIT_DEASSERT_CS, INIT_CLOCKS_SEND, INIT_CLOCKS_POLL = newElement()
      val INIT_ASSERT_CS = newElement()
      // CMD0
      val INIT_CMD0_PREP, INIT_CMD0_CHECK = newElement()
      // CMD8
      val INIT_CMD8_PREP, INIT_CMD8_CHECK = newElement()
      val INIT_CMD8_R7_NEXT, INIT_CMD8_R7_READRX, INIT_CMD8_R7_DONE = newElement()
      // CMD55+ACMD41
      val INIT_CMD55_PREP, INIT_CMD55_GOT = newElement()
      val INIT_ACMD41_PREP, INIT_ACMD41_CHECK = newElement()
      // Fast clock
      val INIT_SETDIV, INIT_RESULT = newElement()

      // T2: Write
      val T2_LABEL, T2_CMD24_PREP, T2_CMD24_GOT = newElement()
      val T2_TOKEN_SEND, T2_TOKEN_POLL = newElement()
      val T2_DATA_SEND, T2_DATA_POLL = newElement()
      val T2_CRC1_SEND, T2_CRC1_POLL, T2_CRC2_SEND, T2_CRC2_POLL = newElement()
      val T2_DRESP_XFER, T2_DRESP_CHECK = newElement()
      val T2_BUSY_XFER, T2_BUSY_CHECK = newElement()
      val T2_RESULT = newElement()

      // T3: Read
      val T3_LABEL, T3_CMD17_PREP, T3_CMD17_GOT = newElement()
      val T3_TOKEN_POLL, T3_TOKEN_CHECK = newElement()
      val T3_DATA_XFER, T3_DATA_CHECK = newElement()
      val T3_CRC1_SEND, T3_CRC1_POLL, T3_CRC2_SEND, T3_CRC2_POLL = newElement()
      val T3_RESULT = newElement()

      // Loop
      val LOOP_MSG, LOOP_HEX, LOOP_CRLF = newElement()
    }

    val state = RegInit(S.INIT_WAIT)
    val retState = Reg(S())

    // Init counter
    val initCnt = Reg(UInt(24 bits)) init (0)

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

    // SPI command frame (6 bytes)
    val cmdFrame = Vec(Reg(Bits(8 bits)), 6)
    val cmdByteIdx = Reg(UInt(4 bits)) init (0)
    val cmdRetState = Reg(S()) // where to go after all 6 bytes sent

    // Shared SPI xfer result: the received byte
    val respByte = Reg(Bits(8 bits)) init (0xFF)
    val xferRetState = Reg(S()) // where to go after SPI xfer complete

    // Response/token polling
    val respCnt = Reg(UInt(10 bits)) init (0)

    // ACMD41 retry counter
    val acmd41Cnt = Reg(UInt(16 bits)) init (0)

    // CMD8 R7 extra bytes counter
    val r7ByteIdx = Reg(UInt(3 bits)) init (0)
    val r7Data = Reg(Bits(32 bits)) init (0)

    // Data byte counter (0..511)
    val dataIdx = Reg(UInt(10 bits)) init (0)

    // Token poll counter
    val tokenCnt = Reg(UInt(16 bits)) init (0)

    // LED
    val ledReg = Reg(Bits(2 bits)) init (0)
    io.led := ~ledReg

    // SD block number for tests
    def TEST_BLOCK = U(1000, 32 bits)

    // ====================================================================
    // Helper: prepare 6-byte command frame
    // ====================================================================
    def prepareCmd(idx: Int, arg: Long, crc: Int): Unit = {
      cmdFrame(0) := B(0x40 | idx, 8 bits)
      cmdFrame(1) := B(((arg >> 24) & 0xFF).toInt, 8 bits)
      cmdFrame(2) := B(((arg >> 16) & 0xFF).toInt, 8 bits)
      cmdFrame(3) := B(((arg >> 8) & 0xFF).toInt, 8 bits)
      cmdFrame(4) := B((arg & 0xFF).toInt, 8 bits)
      cmdFrame(5) := B(crc, 8 bits)
    }

    def prepareCmdDyn(idx: Bits, arg: Bits, crc: Bits): Unit = {
      cmdFrame(0) := (B"01" ## idx(5 downto 0))
      cmdFrame(1) := arg(31 downto 24)
      cmdFrame(2) := arg(23 downto 16)
      cmdFrame(3) := arg(15 downto 8)
      cmdFrame(4) := arg(7 downto 0)
      cmdFrame(5) := crc
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
          msgBase := 0; msgLen := 13; msgIdx := 0
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
      // Shared SPI transfer sub-routine:
      //   Send 0xFF, wait for busy to clear, read RX byte into respByte.
      //   Caller sets xferRetState before jumping to SPI_XFER_SEND.
      // ================================================================
      is(S.SPI_XFER_SEND) {
        // Send 0xFF byte
        sd.io.addr := 1; sd.io.wr := True; sd.io.wrData := 0xFF
        state := S.SPI_XFER_POLL
      }
      is(S.SPI_XFER_POLL) {
        // Poll status for busy=0 (addr=0 only)
        sd.io.addr := 0; sd.io.rd := True
        when(!sd.io.rdData(0)) {
          state := S.SPI_XFER_READRX
        }
      }
      is(S.SPI_XFER_READRX) {
        // Read RX byte (addr=1 only)
        sd.io.addr := 1; sd.io.rd := True
        respByte := sd.io.rdData(7 downto 0)
        state := xferRetState
      }

      // ================================================================
      // Shared command send sub-routine:
      //   Send 6 bytes from cmdFrame[]. Caller sets cmdByteIdx=0,
      //   cmdRetState, then jumps to CMD_SEND_BYTE.
      // ================================================================
      is(S.CMD_SEND_BYTE) {
        // Send one byte
        sd.io.addr := 1; sd.io.wr := True
        sd.io.wrData := cmdFrame(cmdByteIdx.resized).resized
        state := S.CMD_SEND_POLL
      }
      is(S.CMD_SEND_POLL) {
        // Poll busy
        sd.io.addr := 0; sd.io.rd := True
        when(!sd.io.rdData(0)) {
          cmdByteIdx := cmdByteIdx + 1
          when(cmdByteIdx >= 5) {
            state := cmdRetState
          } otherwise {
            state := S.CMD_SEND_BYTE
          }
        }
      }

      // ================================================================
      // T1: Detect card
      // ================================================================
      is(S.T1_LABEL) {
        msgBase := 13; msgLen := 10; msgIdx := 0
        retState := S.T1_READ_STATUS; state := S.PRINT_MSG
      }
      is(S.T1_READ_STATUS) {
        sd.io.addr := 0; sd.io.rd := True
        testPass := sd.io.rdData(1) // bit 1 = cardPresent
        state := S.T1_RESULT
      }
      is(S.T1_RESULT) {
        when(testPass) {
          msgBase := 53; msgLen := 6; msgIdx := 0
          retState := S.INIT_LABEL; state := S.PRINT_MSG
        } otherwise {
          msgBase := 59; msgLen := 6; msgIdx := 0
          retState := S.INIT_LABEL; state := S.PRINT_MSG
        }
      }

      // ================================================================
      // INIT: Full SD card initialization in SPI mode
      // ================================================================
      is(S.INIT_LABEL) {
        testPass := True
        acmd41Cnt := 0
        msgBase := 23; msgLen := 10; msgIdx := 0
        retState := S.INIT_DEASSERT_CS; state := S.PRINT_MSG
      }

      // --- 80 init clocks: deassert CS, send 10 x 0xFF ---
      is(S.INIT_DEASSERT_CS) {
        sd.io.addr := 0; sd.io.wr := True; sd.io.wrData := 0 // CS high
        cmdByteIdx := 0
        state := S.INIT_CLOCKS_SEND
      }
      is(S.INIT_CLOCKS_SEND) {
        sd.io.addr := 1; sd.io.wr := True; sd.io.wrData := 0xFF
        state := S.INIT_CLOCKS_POLL
      }
      is(S.INIT_CLOCKS_POLL) {
        sd.io.addr := 0; sd.io.rd := True
        when(!sd.io.rdData(0)) {
          cmdByteIdx := cmdByteIdx + 1
          when(cmdByteIdx === 9) {
            state := S.INIT_ASSERT_CS
          } otherwise {
            state := S.INIT_CLOCKS_SEND
          }
        }
      }

      is(S.INIT_ASSERT_CS) {
        sd.io.addr := 0; sd.io.wr := True; sd.io.wrData := 1 // CS low
        state := S.INIT_CMD0_PREP
      }

      // --- CMD0: GO_IDLE_STATE ---
      is(S.INIT_CMD0_PREP) {
        prepareCmd(0, 0x00000000L, 0x95)
        cmdByteIdx := 0
        cmdRetState := S.INIT_CMD0_CHECK
        respCnt := 0
        state := S.CMD_SEND_BYTE
      }
      // After CMD0 sent, poll for R1 response
      is(S.INIT_CMD0_CHECK) {
        // Use shared xfer to get next byte
        xferRetState := S.INIT_CMD0_CHECK // re-enter to check
        // But we need to check respByte — after first xfer, it's available
        // On first entry, start an xfer. On re-entry, check respByte.
        // Use respCnt as a "first time" flag: if 0, start xfer
        when(respCnt === 0) {
          respCnt := 1
          xferRetState := S.INIT_CMD0_CHECK
          state := S.SPI_XFER_SEND
        } otherwise {
          when(respByte(7)) {
            // Still 0xFF
            respCnt := respCnt + 1
            when(respCnt >= 100) {
              testPass := False; state := S.INIT_RESULT
            } otherwise {
              xferRetState := S.INIT_CMD0_CHECK
              state := S.SPI_XFER_SEND
            }
          } otherwise {
            // Got R1; should be 0x01
            when(respByte =/= 0x01) { testPass := False }
            state := S.INIT_CMD8_PREP
          }
        }
      }

      // --- CMD8: SEND_IF_COND ---
      is(S.INIT_CMD8_PREP) {
        prepareCmd(8, 0x000001AAL, 0x87)
        cmdByteIdx := 0
        cmdRetState := S.INIT_CMD8_CHECK
        respCnt := 0
        state := S.CMD_SEND_BYTE
      }
      is(S.INIT_CMD8_CHECK) {
        when(respCnt === 0) {
          respCnt := 1
          xferRetState := S.INIT_CMD8_CHECK
          state := S.SPI_XFER_SEND
        } otherwise {
          when(respByte(7)) {
            respCnt := respCnt + 1
            when(respCnt >= 100) {
              testPass := False; state := S.INIT_RESULT
            } otherwise {
              xferRetState := S.INIT_CMD8_CHECK
              state := S.SPI_XFER_SEND
            }
          } otherwise {
            // Got R1, read 4 more R7 bytes
            r7ByteIdx := 0; r7Data := 0
            xferRetState := S.INIT_CMD8_R7_READRX
            state := S.SPI_XFER_SEND
          }
        }
      }
      is(S.INIT_CMD8_R7_READRX) {
        // respByte has the R7 byte
        r7Data := (r7Data |<< 8) | respByte.resized
        r7ByteIdx := r7ByteIdx + 1
        when(r7ByteIdx === 3) {
          state := S.INIT_CMD8_R7_DONE
        } otherwise {
          xferRetState := S.INIT_CMD8_R7_READRX
          state := S.SPI_XFER_SEND
        }
      }
      is(S.INIT_CMD8_R7_DONE) {
        when(r7Data(11 downto 0) =/= B(0x1AA, 12 bits)) {
          testPass := False
        }
        state := S.INIT_CMD55_PREP
      }

      // --- CMD55+ACMD41 loop ---
      is(S.INIT_CMD55_PREP) {
        prepareCmd(55, 0x00000000L, 0x01)
        cmdByteIdx := 0
        cmdRetState := S.INIT_CMD55_GOT
        respCnt := 0
        state := S.CMD_SEND_BYTE
      }
      // Poll for CMD55 R1 response
      is(S.INIT_CMD55_GOT) {
        when(respCnt === 0) {
          respCnt := 1
          xferRetState := S.INIT_CMD55_GOT
          state := S.SPI_XFER_SEND
        } otherwise {
          when(respByte(7)) {
            respCnt := respCnt + 1
            when(respCnt >= 100) {
              testPass := False; state := S.INIT_RESULT
            } otherwise {
              xferRetState := S.INIT_CMD55_GOT
              state := S.SPI_XFER_SEND
            }
          } otherwise {
            // Got CMD55 R1, now send ACMD41
            state := S.INIT_ACMD41_PREP
          }
        }
      }

      is(S.INIT_ACMD41_PREP) {
        prepareCmd(41, 0x40000000L, 0x01)
        cmdByteIdx := 0
        cmdRetState := S.INIT_ACMD41_CHECK
        respCnt := 0
        state := S.CMD_SEND_BYTE
      }
      is(S.INIT_ACMD41_CHECK) {
        when(respCnt === 0) {
          respCnt := 1
          xferRetState := S.INIT_ACMD41_CHECK
          state := S.SPI_XFER_SEND
        } otherwise {
          when(respByte(7)) {
            respCnt := respCnt + 1
            when(respCnt >= 100) {
              testPass := False; state := S.INIT_RESULT
            } otherwise {
              xferRetState := S.INIT_ACMD41_CHECK
              state := S.SPI_XFER_SEND
            }
          } otherwise {
            when(respByte === 0x00) {
              // Card ready
              state := S.INIT_SETDIV
            } otherwise {
              // Still idle (0x01), retry
              acmd41Cnt := acmd41Cnt + 1
              when(acmd41Cnt >= 1000) {
                testPass := False; state := S.INIT_RESULT
              } otherwise {
                state := S.INIT_CMD55_PREP
              }
            }
          }
        }
      }

      // Set fast SPI clock
      is(S.INIT_SETDIV) {
        sd.io.addr := 2; sd.io.wr := True; sd.io.wrData := 1
        state := S.INIT_RESULT
      }

      is(S.INIT_RESULT) {
        when(testPass) {
          msgBase := 53; msgLen := 6; msgIdx := 0
          retState := S.T2_LABEL; state := S.PRINT_MSG
        } otherwise {
          msgBase := 59; msgLen := 6; msgIdx := 0
          retState := S.T2_LABEL; state := S.PRINT_MSG
        }
      }

      // ================================================================
      // T2: Write block (CMD24)
      // ================================================================
      is(S.T2_LABEL) {
        testPass := True; dataIdx := 0
        msgBase := 33; msgLen := 10; msgIdx := 0
        retState := S.T2_CMD24_PREP; state := S.PRINT_MSG
      }

      is(S.T2_CMD24_PREP) {
        prepareCmdDyn(B(24, 8 bits), TEST_BLOCK.asBits, B(0x01, 8 bits))
        cmdByteIdx := 0
        cmdRetState := S.T2_CMD24_GOT
        respCnt := 0
        state := S.CMD_SEND_BYTE
      }
      // Poll for CMD24 R1 response
      is(S.T2_CMD24_GOT) {
        when(respCnt === 0) {
          respCnt := 1
          xferRetState := S.T2_CMD24_GOT
          state := S.SPI_XFER_SEND
        } otherwise {
          when(respByte(7)) {
            respCnt := respCnt + 1
            when(respCnt >= 100) {
              testPass := False; state := S.T2_RESULT
            } otherwise {
              xferRetState := S.T2_CMD24_GOT
              state := S.SPI_XFER_SEND
            }
          } otherwise {
            // Got R1, send data start token 0xFE
            state := S.T2_TOKEN_SEND
          }
        }
      }

      is(S.T2_TOKEN_SEND) {
        sd.io.addr := 1; sd.io.wr := True; sd.io.wrData := 0xFE
        dataIdx := 0
        state := S.T2_TOKEN_POLL
      }
      is(S.T2_TOKEN_POLL) {
        sd.io.addr := 0; sd.io.rd := True
        when(!sd.io.rdData(0)) { state := S.T2_DATA_SEND }
      }

      // Send 512 data bytes
      is(S.T2_DATA_SEND) {
        sd.io.addr := 1; sd.io.wr := True
        sd.io.wrData := ((dataIdx + 0xA5) & 0xFF).asBits.resized
        state := S.T2_DATA_POLL
      }
      is(S.T2_DATA_POLL) {
        sd.io.addr := 0; sd.io.rd := True
        when(!sd.io.rdData(0)) {
          dataIdx := dataIdx + 1
          when(dataIdx >= 511) {
            state := S.T2_CRC1_SEND
          } otherwise {
            state := S.T2_DATA_SEND
          }
        }
      }

      // Send 2 dummy CRC bytes
      is(S.T2_CRC1_SEND) {
        sd.io.addr := 1; sd.io.wr := True; sd.io.wrData := 0xFF
        state := S.T2_CRC1_POLL
      }
      is(S.T2_CRC1_POLL) {
        sd.io.addr := 0; sd.io.rd := True
        when(!sd.io.rdData(0)) { state := S.T2_CRC2_SEND }
      }
      is(S.T2_CRC2_SEND) {
        sd.io.addr := 1; sd.io.wr := True; sd.io.wrData := 0xFF
        state := S.T2_CRC2_POLL
      }
      is(S.T2_CRC2_POLL) {
        sd.io.addr := 0; sd.io.rd := True
        when(!sd.io.rdData(0)) {
          // Read data response token
          xferRetState := S.T2_DRESP_CHECK
          state := S.SPI_XFER_SEND
        }
      }

      is(S.T2_DRESP_CHECK) {
        // Data response: xxx0sss1, sss=010 means accepted
        when((respByte(4 downto 0) & B"11111") =/= B"00101") {
          testPass := False
        }
        // Now poll busy
        xferRetState := S.T2_BUSY_CHECK
        state := S.SPI_XFER_SEND
      }

      is(S.T2_BUSY_CHECK) {
        when(respByte === 0x00) {
          // Still busy, send another 0xFF
          xferRetState := S.T2_BUSY_CHECK
          state := S.SPI_XFER_SEND
        } otherwise {
          state := S.T2_RESULT
        }
      }

      is(S.T2_RESULT) {
        when(testPass) {
          msgBase := 53; msgLen := 6; msgIdx := 0
          retState := S.T3_LABEL; state := S.PRINT_MSG
        } otherwise {
          msgBase := 59; msgLen := 6; msgIdx := 0
          retState := S.T3_LABEL; state := S.PRINT_MSG
        }
      }

      // ================================================================
      // T3: Read block (CMD17)
      // ================================================================
      is(S.T3_LABEL) {
        testPass := True; dataIdx := 0
        msgBase := 43; msgLen := 10; msgIdx := 0
        retState := S.T3_CMD17_PREP; state := S.PRINT_MSG
      }

      is(S.T3_CMD17_PREP) {
        prepareCmdDyn(B(17, 8 bits), TEST_BLOCK.asBits, B(0x01, 8 bits))
        cmdByteIdx := 0
        cmdRetState := S.T3_CMD17_GOT
        respCnt := 0
        state := S.CMD_SEND_BYTE
      }
      // Poll for CMD17 R1 response
      is(S.T3_CMD17_GOT) {
        when(respCnt === 0) {
          respCnt := 1
          xferRetState := S.T3_CMD17_GOT
          state := S.SPI_XFER_SEND
        } otherwise {
          when(respByte(7)) {
            respCnt := respCnt + 1
            when(respCnt >= 100) {
              testPass := False; state := S.T3_RESULT
            } otherwise {
              xferRetState := S.T3_CMD17_GOT
              state := S.SPI_XFER_SEND
            }
          } otherwise {
            // Got R1, poll for data token 0xFE
            tokenCnt := 0
            xferRetState := S.T3_TOKEN_CHECK
            state := S.SPI_XFER_SEND
          }
        }
      }

      is(S.T3_TOKEN_CHECK) {
        when(respByte === 0xFE) {
          // Data start token received
          dataIdx := 0
          xferRetState := S.T3_DATA_CHECK
          state := S.SPI_XFER_SEND
        } otherwise {
          tokenCnt := tokenCnt + 1
          when(tokenCnt >= 10000) {
            testPass := False; state := S.T3_RESULT
          } otherwise {
            xferRetState := S.T3_TOKEN_CHECK
            state := S.SPI_XFER_SEND
          }
        }
      }

      // Receive and verify 512 data bytes
      is(S.T3_DATA_CHECK) {
        val expected = ((dataIdx + 0xA5) & 0xFF).asBits.resized
        when(respByte =/= expected) {
          testPass := False
        }
        dataIdx := dataIdx + 1
        when(dataIdx >= 511) {
          // Discard 2 CRC bytes
          state := S.T3_CRC1_SEND
        } otherwise {
          xferRetState := S.T3_DATA_CHECK
          state := S.SPI_XFER_SEND
        }
      }

      // Discard 2 CRC bytes
      is(S.T3_CRC1_SEND) {
        sd.io.addr := 1; sd.io.wr := True; sd.io.wrData := 0xFF
        state := S.T3_CRC1_POLL
      }
      is(S.T3_CRC1_POLL) {
        sd.io.addr := 0; sd.io.rd := True
        when(!sd.io.rdData(0)) { state := S.T3_CRC2_SEND }
      }
      is(S.T3_CRC2_SEND) {
        sd.io.addr := 1; sd.io.wr := True; sd.io.wrData := 0xFF
        state := S.T3_CRC2_POLL
      }
      is(S.T3_CRC2_POLL) {
        sd.io.addr := 0; sd.io.rd := True
        when(!sd.io.rdData(0)) { state := S.T3_RESULT }
      }

      is(S.T3_RESULT) {
        when(testPass) {
          msgBase := 53; msgLen := 6; msgIdx := 0
          retState := S.LOOP_MSG; state := S.PRINT_MSG
        } otherwise {
          msgBase := 59; msgLen := 6; msgIdx := 0
          retState := S.LOOP_MSG; state := S.PRINT_MSG
        }
      }

      // ================================================================
      // Loop: print count and restart
      // ================================================================
      is(S.LOOP_MSG) {
        loopCnt := loopCnt + 1
        ledReg := loopCnt.asBits.resized
        msgBase := 67; msgLen := 5; msgIdx := 0
        retState := S.LOOP_HEX
        state := S.PRINT_MSG
      }
      is(S.LOOP_HEX) {
        hexVal := loopCnt.asBits.resized; hexNib := 0
        retState := S.LOOP_CRLF
        state := S.PRINT_HEX
      }
      is(S.LOOP_CRLF) {
        msgBase := 65; msgLen := 2; msgIdx := 0
        retState := S.T1_LABEL
        state := S.PRINT_MSG
      }
    }
  }
}

/**
 * Generate Verilog for SdSpiExerciserTop
 */
object SdSpiExerciserTopVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(80 MHz)
  ).generate(SdSpiExerciserTop())

  println("Generated: spinalhdl/generated/SdSpiExerciserTop.v")
}
