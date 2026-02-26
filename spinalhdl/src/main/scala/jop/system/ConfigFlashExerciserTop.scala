package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._

/**
 * Configuration Flash Read-Back Exerciser
 *
 * Reads the W25Q128 SPI flash connected to the FPGA's active serial
 * configuration pins (DCLK, DATA0, ASDO, nCSO) as direct I/O. Streams
 * flash contents out via UART at 1 Mbaud.
 *
 * Tests (loop continuously):
 *   T1: JEDEC_ID — read and verify manufacturer/device ID (expect EF4018)
 *   T2: READ     — read first 256 bytes from flash, hex dump
 */
case class ConfigFlashExerciserTop() extends Component {

  val io = new Bundle {
    val clk_in    = in Bool()
    val ser_txd   = out Bool()
    val led       = out Bits(2 bits)
    // Direct SPI pins to config flash (active serial pins)
    val flash_dclk = out Bool()
    val flash_ncs  = out Bool()
    val flash_asdo = out Bool()
    val flash_data0 = in Bool()
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

    // SPI registers
    val spiClk     = Reg(Bool()) init (False)
    val spiCs      = Reg(Bool()) init (True)  // active low, idle high
    val spiClkCnt  = Reg(UInt(2 bits)) init (0)
    val spiShiftOut = Reg(Bits(8 bits)) init (0)
    val spiShiftIn  = Reg(Bits(8 bits)) init (0)
    val spiBitCnt  = Reg(UInt(3 bits)) init (0)
    val spiTxByte  = Reg(Bits(8 bits)) init (0)
    val spiRxByte  = Reg(Bits(8 bits)) init (0)

    // Direct SPI pin wiring (config flash pins as regular I/O)
    io.flash_dclk := spiClk
    io.flash_ncs  := spiCs
    io.flash_asdo := spiShiftOut.msb
    val spiMiso = io.flash_data0

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
      //  0: "CONFIG FLASH TEST\r\n"  (19)
      val m0 = "CONFIG FLASH TEST\r\n"
      // 19: "T1:JEDEC_ID "           (12)
      val m1 = "T1:JEDEC_ID "
      // 31: "T2:READ\r\n"            (9)
      val m2 = "T2:READ\r\n"
      // 40: "PASS\r\n"               (6)
      val m3 = "PASS\r\n"
      // 46: "FAIL\r\n"               (6)
      val m4 = "FAIL\r\n"
      // 52: "\r\n"                    (2)
      val m5 = "\r\n"
      // 54: "LOOP "                   (5)
      val m6 = "LOOP "
      // 59: ": "                      (2)
      val m7 = ": "
      (m0 + m1 + m2 + m3 + m4 + m5 + m6 + m7).map(_.toInt)
    }
    val msgRom = Mem(Bits(8 bits), initialContent = msgBytes.map(c => B(c & 0xFF, 8 bits)))

    // ====================================================================
    // State Machine
    // ====================================================================
    object S extends SpinalEnum {
      val INIT_WAIT = newElement()
      val PRINT_MSG, PRINT_HEX, PRINT_HEX2, PRINT_HEX6 = newElement()

      // SPI byte transfer sub-state
      val SPI_BYTE, SPI_BYTE_RISE, SPI_BYTE_FALL, SPI_BYTE_DONE = newElement()

      // T1: JEDEC ID
      val T1_LABEL, T1_CS_LOW, T1_SEND_CMD = newElement()
      val T1_RECV0, T1_RECV1, T1_RECV2 = newElement()
      val T1_CS_HIGH, T1_PRINT_ID, T1_CHECK, T1_RESULT = newElement()

      // T2: Read 256 bytes
      val T2_LABEL, T2_CS_LOW, T2_SEND_CMD = newElement()
      val T2_SEND_ADDR2, T2_SEND_ADDR1, T2_SEND_ADDR0 = newElement()
      val T2_LINE_ADDR, T2_LINE_COLON = newElement()
      val T2_RECV_BYTE, T2_PRINT_SPACE, T2_PRINT_BYTE = newElement()
      val T2_LINE_END, T2_CS_HIGH = newElement()

      // Loop
      val LOOP_MSG, LOOP_HEX, LOOP_CRLF = newElement()
    }

    val state = RegInit(S.INIT_WAIT)
    val retState = Reg(S())

    // Init counter (28 bits at 80MHz = ~3.4s delay for UART monitor attach)
    val initCnt = Reg(UInt(28 bits)) init (0)

    // Print state
    val msgBase = Reg(UInt(7 bits))
    val msgLen  = Reg(UInt(7 bits))
    val msgIdx  = Reg(UInt(7 bits))

    // Hex print state
    val hexVal = Reg(Bits(32 bits))
    val hexNib = Reg(UInt(4 bits))

    // Test results
    val testPass = Reg(Bool()) init (False)
    val loopCnt  = Reg(UInt(16 bits)) init (0)

    // JEDEC ID bytes
    val jedecId = Reg(Bits(24 bits)) init (0)

    // T2 state
    val readAddr    = Reg(UInt(24 bits)) init (0)
    val bytesInLine = Reg(UInt(4 bits)) init (0)
    val bytesTotal  = Reg(UInt(9 bits)) init (0)

    // LED
    val ledReg = Reg(Bits(2 bits)) init (0)
    io.led := ~ledReg

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
          msgBase := 0; msgLen := 19; msgIdx := 0
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
      // Hex print sub-state: 8 hex digits from hexVal
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

      // Print 2 hex digits (byte) from hexVal[31:24]
      is(S.PRINT_HEX2) {
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
          when(hexNib === 1) {
            state := retState
          }
        }
      }

      // Print 6 hex digits from hexVal[31:8]
      is(S.PRINT_HEX6) {
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
          when(hexNib === 5) {
            state := retState
          }
        }
      }

      // ================================================================
      // SPI byte transfer sub-states
      // Shifts 8 bits out on MOSI / in on MISO using spiClkCnt for timing.
      // On entry: spiTxByte has byte to send, retState has return state.
      // On exit: spiRxByte has received byte.
      // ================================================================
      is(S.SPI_BYTE) {
        // Setup: load shift register, reset counters
        spiShiftOut := spiTxByte
        spiBitCnt := 0
        spiClkCnt := 0
        spiClk := False
        state := S.SPI_BYTE_RISE
      }

      // Count up to rising edge
      is(S.SPI_BYTE_RISE) {
        spiClkCnt := spiClkCnt + 1
        when(spiClkCnt === 3) {
          spiClkCnt := 0
          spiClk := True
          // Sample MISO on rising edge
          spiShiftIn := spiShiftIn(6 downto 0) ## spiMiso
          state := S.SPI_BYTE_FALL
        }
      }

      // Count down to falling edge
      is(S.SPI_BYTE_FALL) {
        spiClkCnt := spiClkCnt + 1
        when(spiClkCnt === 3) {
          spiClkCnt := 0
          spiClk := False
          spiBitCnt := spiBitCnt + 1
          when(spiBitCnt === 7) {
            state := S.SPI_BYTE_DONE
          } otherwise {
            // Shift out next bit
            spiShiftOut := spiShiftOut(6 downto 0) ## B"0"
            state := S.SPI_BYTE_RISE
          }
        }
      }

      // Byte done — latch result
      is(S.SPI_BYTE_DONE) {
        spiRxByte := spiShiftIn
        state := retState
      }

      // ================================================================
      // T1: JEDEC ID
      // ================================================================
      is(S.T1_LABEL) {
        testPass := True
        msgBase := 19; msgLen := 12; msgIdx := 0 // "T1:JEDEC_ID "
        retState := S.T1_CS_LOW; state := S.PRINT_MSG
      }

      is(S.T1_CS_LOW) {
        spiCs := False
        spiTxByte := B"x9F" // READ_JEDEC_ID command
        retState := S.T1_SEND_CMD
        state := S.SPI_BYTE
      }

      // After sending 0x9F, receive 3 bytes
      is(S.T1_SEND_CMD) {
        spiTxByte := 0 // dummy TX while receiving
        retState := S.T1_RECV0
        state := S.SPI_BYTE
      }

      is(S.T1_RECV0) {
        jedecId(23 downto 16) := spiRxByte
        spiTxByte := 0
        retState := S.T1_RECV1
        state := S.SPI_BYTE
      }

      is(S.T1_RECV1) {
        jedecId(15 downto 8) := spiRxByte
        spiTxByte := 0
        retState := S.T1_RECV2
        state := S.SPI_BYTE
      }

      is(S.T1_RECV2) {
        jedecId(7 downto 0) := spiRxByte
        state := S.T1_CS_HIGH
      }

      is(S.T1_CS_HIGH) {
        spiCs := True
        spiClk := False
        // Print the 6-digit JEDEC ID
        hexVal := jedecId.resized ## B"x00"; hexNib := 0
        retState := S.T1_CHECK
        state := S.PRINT_HEX6
      }

      is(S.T1_CHECK) {
        // Space before PASS/FAIL
        when(txFifo.io.push.ready) {
          txFifo.io.push.valid := True
          txFifo.io.push.payload := B(0x20, 8 bits) // ' '
          when(jedecId =/= B"xEF4018") {
            testPass := False
          }
          state := S.T1_RESULT
        }
      }

      is(S.T1_RESULT) {
        when(testPass) {
          msgBase := 40; msgLen := 6; msgIdx := 0 // "PASS\r\n"
          retState := S.T2_LABEL; state := S.PRINT_MSG
        } otherwise {
          msgBase := 46; msgLen := 6; msgIdx := 0 // "FAIL\r\n"
          retState := S.T2_LABEL; state := S.PRINT_MSG
        }
      }

      // ================================================================
      // T2: Read 256 bytes from address 0
      // ================================================================
      is(S.T2_LABEL) {
        readAddr := 0
        bytesTotal := 0
        msgBase := 31; msgLen := 9; msgIdx := 0 // "T2:READ\r\n"
        retState := S.T2_CS_LOW; state := S.PRINT_MSG
      }

      is(S.T2_CS_LOW) {
        spiCs := False
        spiTxByte := B"x03" // READ_DATA command
        retState := S.T2_SEND_CMD
        state := S.SPI_BYTE
      }

      is(S.T2_SEND_CMD) {
        // Send address byte 2 (MSB)
        spiTxByte := B(0, 8 bits)
        retState := S.T2_SEND_ADDR2
        state := S.SPI_BYTE
      }

      is(S.T2_SEND_ADDR2) {
        // Send address byte 1
        spiTxByte := B(0, 8 bits)
        retState := S.T2_SEND_ADDR1
        state := S.SPI_BYTE
      }

      is(S.T2_SEND_ADDR1) {
        // Send address byte 0 (LSB)
        spiTxByte := B(0, 8 bits)
        retState := S.T2_SEND_ADDR0
        state := S.SPI_BYTE
      }

      // Now read 256 bytes, printing 16 per line
      is(S.T2_SEND_ADDR0) {
        bytesInLine := 0
        state := S.T2_LINE_ADDR
      }

      // Print line address (8 hex digits)
      is(S.T2_LINE_ADDR) {
        hexVal := readAddr.asBits.resized; hexNib := 0
        retState := S.T2_LINE_COLON
        state := S.PRINT_HEX
      }

      // Print ": " after address
      is(S.T2_LINE_COLON) {
        msgBase := 59; msgLen := 2; msgIdx := 0 // ": "
        retState := S.T2_RECV_BYTE
        state := S.PRINT_MSG
      }

      // Receive one byte from flash
      is(S.T2_RECV_BYTE) {
        spiTxByte := 0 // dummy
        retState := S.T2_PRINT_SPACE
        state := S.SPI_BYTE
      }

      // Print space before hex byte (except first byte in line handled by ": ")
      is(S.T2_PRINT_SPACE) {
        when(bytesInLine =/= 0) {
          when(txFifo.io.push.ready) {
            txFifo.io.push.valid := True
            txFifo.io.push.payload := B(0x20, 8 bits) // ' '
            state := S.T2_PRINT_BYTE
          }
        } otherwise {
          state := S.T2_PRINT_BYTE
        }
      }

      // Print the byte as 2 hex digits
      is(S.T2_PRINT_BYTE) {
        hexVal := spiRxByte ## B(0, 24 bits); hexNib := 0
        retState := S.T2_LINE_END
        state := S.PRINT_HEX2
      }

      // Check if line is complete
      is(S.T2_LINE_END) {
        readAddr := readAddr + 1
        bytesTotal := bytesTotal + 1
        bytesInLine := bytesInLine + 1
        when(bytesTotal === 255) {
          // Done reading 256 bytes
          state := S.T2_CS_HIGH
        } elsewhen(bytesInLine === 15) {
          // End of line — print CRLF and start new line
          msgBase := 52; msgLen := 2; msgIdx := 0 // "\r\n"
          bytesInLine := 0
          retState := S.T2_LINE_ADDR
          state := S.PRINT_MSG
        } otherwise {
          state := S.T2_RECV_BYTE
        }
      }

      is(S.T2_CS_HIGH) {
        spiCs := True
        spiClk := False
        // Print final CRLF
        msgBase := 52; msgLen := 2; msgIdx := 0 // "\r\n"
        retState := S.LOOP_MSG
        state := S.PRINT_MSG
      }

      // ================================================================
      // Loop: print count and restart
      // ================================================================
      is(S.LOOP_MSG) {
        loopCnt := loopCnt + 1
        ledReg := loopCnt.asBits.resized
        msgBase := 54; msgLen := 5; msgIdx := 0 // "LOOP "
        retState := S.LOOP_HEX
        state := S.PRINT_MSG
      }
      is(S.LOOP_HEX) {
        hexVal := loopCnt.asBits.resized; hexNib := 0
        retState := S.LOOP_CRLF
        state := S.PRINT_HEX
      }
      is(S.LOOP_CRLF) {
        msgBase := 52; msgLen := 2; msgIdx := 0 // "\r\n"
        retState := S.T1_LABEL
        state := S.PRINT_MSG
      }
    }
  }
}

/**
 * Generate Verilog for ConfigFlashExerciserTop
 */
object ConfigFlashExerciserTopVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(80 MHz)
  ).generate(ConfigFlashExerciserTop())

  println("Generated: spinalhdl/generated/ConfigFlashExerciserTop.v")
}
