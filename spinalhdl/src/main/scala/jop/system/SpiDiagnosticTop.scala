package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import jop.ddr3.StartupE2

/**
 * Minimal standalone SPI diagnostic for Artix-7.
 *
 * Tests the STARTUPE2 + SPI flash path using ONLY the board clock domain.
 * No MIG, no JOP, no BmbConfigFlash — just a simple FSM that reads the
 * JEDEC ID (0x9F) from the SPI flash and displays it on LEDs.
 *
 * LED display after test:
 *   LED[7]   = testDone (1 when complete)
 *   LED[6:0] = manufacturer ID [6:0]  (SST26: 0xBF = 1011111)
 *
 * Expected for SST26VF032B: LED[7]=ON, LED[6:0]=0111111 (0xBF lower 7 bits)
 *
 * SPI: Mode 0 (CPOL=0, CPHA=0), ~3 MHz (divider=15 at 100 MHz).
 * Pins: MOSI=J13, MISO=J14, CS=L12, CCLK via STARTUPE2.
 */
case class SpiDiagnosticTop() extends Component {
  val io = new Bundle {
    val led     = out Bits(8 bits)
    val usb_rx  = in Bool()
    val usb_tx  = out Bool()
    val cf_mosi = out Bool()
    val cf_miso = in Bool()
    val cf_cs   = out Bool()
  }

  noIoPrefix()

  // STARTUPE2: drive CCLK from SPI clock
  val startup = StartupE2()
  startup.io.CLK       := False
  startup.io.GSR       := False
  startup.io.GTS       := False
  startup.io.KEYCLEARB := True
  startup.io.PACK      := False
  startup.io.USRCCLKTS := False  // Enable CCLK output
  startup.io.USRDONEO  := True
  startup.io.USRDONETS := True

  // SPI registers
  val spiClk   = Reg(Bool()) init(False)
  val spiCs    = Reg(Bool()) init(True)   // idle high
  val spiShift = Reg(Bits(8 bits)) init(0)
  val spiRxBuf = Reg(Bits(8 bits)) init(0)

  startup.io.USRCCLKO := spiClk
  io.cf_cs   := spiCs
  io.cf_mosi := spiShift(7)  // MSB first

  val miso = io.cf_miso

  // Result registers
  val jedecMfr  = Reg(Bits(8 bits)) init(0xFF)
  val jedecType = Reg(Bits(8 bits)) init(0xFF)
  val jedecCap  = Reg(Bits(8 bits)) init(0xFF)
  val testDone  = Reg(Bool()) init(False)

  // Heartbeat
  val hbCounter = Reg(UInt(26 bits)) init(0)
  val heartbeat = Reg(Bool()) init(False)
  hbCounter := hbCounter + 1
  when(hbCounter === 49999999) {
    hbCounter := 0
    heartbeat := ~heartbeat
  }

  // FSM
  object S extends SpinalEnum {
    val WAIT_EOS, SETTLE,
        RST_EN_CS, RST_EN_LOAD, RST_EN_RISE, RST_EN_FALL, RST_EN_DONE, RST_EN_CS_HI,
        RST_CS, RST_LOAD, RST_RISE, RST_FALL, RST_DONE, RST_CS_HI,
        RST_WAIT,
        CS_LOW, LOAD_BYTE,
        SPI_RISE, SPI_FALL, BYTE_DONE,
        CS_HIGH, DONE = newElement()
  }

  val state   = RegInit(S.WAIT_EOS)
  val byteCnt = Reg(UInt(2 bits)) init(0)   // 0=cmd, 1-3=response
  val bitCnt  = Reg(UInt(3 bits)) init(0)
  val divCnt  = Reg(UInt(5 bits)) init(0)
  val waitCnt = Reg(UInt(28 bits)) init(0)
  val rstBitCnt = Reg(UInt(3 bits)) init(0)

  switch(state) {

    is(S.WAIT_EOS) {
      when(startup.io.EOS) {
        state := S.SETTLE
        waitCnt := 0
      }
    }

    is(S.SETTLE) {
      waitCnt := waitCnt + 1
      when(waitCnt(23 downto 0).andR) {  // ~167ms at 100 MHz
        state := S.RST_EN_CS
      }
    }

    // -- Software reset: RSTEN (0x66) --
    is(S.RST_EN_CS) {
      spiCs := False
      spiShift := B"x66"   // RSTEN command
      rstBitCnt := 0
      divCnt := 0
      state := S.RST_EN_RISE
    }

    is(S.RST_EN_RISE) {
      divCnt := divCnt + 1
      when(divCnt === 15) {
        divCnt := 0
        spiClk := True
        state := S.RST_EN_FALL
      }
    }

    is(S.RST_EN_FALL) {
      divCnt := divCnt + 1
      when(divCnt === 15) {
        divCnt := 0
        spiClk := False
        rstBitCnt := rstBitCnt + 1
        when(rstBitCnt === 7) {
          state := S.RST_EN_CS_HI
        } otherwise {
          spiShift := spiShift(6 downto 0) ## False
          state := S.RST_EN_RISE
        }
      }
    }

    is(S.RST_EN_CS_HI) {
      spiCs := True
      spiClk := False
      state := S.RST_CS
    }

    // -- Software reset: RST (0x99) --
    is(S.RST_CS) {
      spiCs := False
      spiShift := B"x99"   // RST command
      rstBitCnt := 0
      divCnt := 0
      state := S.RST_RISE
    }

    is(S.RST_RISE) {
      divCnt := divCnt + 1
      when(divCnt === 15) {
        divCnt := 0
        spiClk := True
        state := S.RST_FALL
      }
    }

    is(S.RST_FALL) {
      divCnt := divCnt + 1
      when(divCnt === 15) {
        divCnt := 0
        spiClk := False
        rstBitCnt := rstBitCnt + 1
        when(rstBitCnt === 7) {
          state := S.RST_CS_HI
        } otherwise {
          spiShift := spiShift(6 downto 0) ## False
          state := S.RST_RISE
        }
      }
    }

    is(S.RST_CS_HI) {
      spiCs := True
      spiClk := False
      waitCnt := 0
      state := S.RST_WAIT
    }

    // Wait ~1ms for reset to complete (tRST = 30µs for SST26)
    is(S.RST_WAIT) {
      waitCnt := waitCnt + 1
      when(waitCnt(16 downto 0).andR) {  // ~1.3ms at 100 MHz
        state := S.CS_LOW
      }
    }

    // -- JEDEC ID read --
    is(S.CS_LOW) {
      spiCs := False
      byteCnt := 0
      spiShift := B"x9F"   // JEDEC_ID command
      state := S.LOAD_BYTE
    }

    is(S.LOAD_BYTE) {
      // MOSI is already spiShift(7) via continuous assign
      bitCnt := 0
      divCnt := 0
      state := S.SPI_RISE
    }

    is(S.SPI_RISE) {
      divCnt := divCnt + 1
      when(divCnt === 15) {
        divCnt := 0
        spiClk := True
        // Sample MISO on rising edge
        spiRxBuf := spiRxBuf(6 downto 0) ## miso
        state := S.SPI_FALL
      }
    }

    is(S.SPI_FALL) {
      divCnt := divCnt + 1
      when(divCnt === 15) {
        divCnt := 0
        spiClk := False
        bitCnt := bitCnt + 1
        when(bitCnt === 7) {
          state := S.BYTE_DONE
        } otherwise {
          spiShift := spiShift(6 downto 0) ## False
          state := S.SPI_RISE
        }
      }
    }

    is(S.BYTE_DONE) {
      // Save JEDEC response bytes (byteCnt 1=mfr, 2=type, 3=cap)
      switch(byteCnt) {
        is(1) { jedecMfr  := spiRxBuf }
        is(2) { jedecType := spiRxBuf }
        is(3) { jedecCap  := spiRxBuf }
      }
      when(byteCnt === 3) {
        state := S.CS_HIGH
      } otherwise {
        byteCnt := byteCnt + 1
        spiShift := B"x00"   // dummy for response bytes
        state := S.LOAD_BYTE
      }
    }

    is(S.CS_HIGH) {
      spiCs := True
      spiClk := False
      testDone := True
      state := S.DONE
    }

    is(S.DONE) {
      // Stay here
    }
  }

  // ========================================================================
  // UART (1 Mbaud at 100 MHz) — send JEDEC ID as hex string after test
  // ========================================================================

  val uartCtrl = new UartCtrl(UartCtrlGenerics(
    preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1
  ))
  uartCtrl.io.config.setClockDivider(1000000 Hz)
  uartCtrl.io.config.frame.dataLength := 7
  uartCtrl.io.config.frame.parity := UartParityType.NONE
  uartCtrl.io.config.frame.stop := UartStopType.ONE
  uartCtrl.io.writeBreak := False
  uartCtrl.io.uart.rxd := io.usb_rx
  io.usb_tx := uartCtrl.io.uart.txd
  uartCtrl.io.read.ready := False

  // Simple TX: send string "JEDEC:xxyyzz\r\n" then repeat every ~2s
  val txState = Reg(UInt(5 bits)) init(0)
  val txDelay = Reg(UInt(28 bits)) init(0)
  val txReady = Reg(Bool()) init(False)

  uartCtrl.io.write.valid := False
  uartCtrl.io.write.payload := 0

  // Hex nibble to ASCII
  def hexChar(nibble: Bits): Bits = {
    val n = nibble(3 downto 0).asUInt
    Mux(n < 10, (n + 0x30).asBits.resize(8), (n + 0x57).asBits.resize(8))
  }

  // Build message bytes: "JID:xxyyzz\r\n" (12 bytes)
  val msgBytes = Vec(Bits(8 bits), 12)
  msgBytes(0) := B"x4A"  // 'J'
  msgBytes(1) := B"x49"  // 'I'
  msgBytes(2) := B"x44"  // 'D'
  msgBytes(3) := B"x3A"  // ':'
  msgBytes(4) := hexChar(jedecMfr(7 downto 4))
  msgBytes(5) := hexChar(jedecMfr(3 downto 0))
  msgBytes(6) := hexChar(jedecType(7 downto 4))
  msgBytes(7) := hexChar(jedecType(3 downto 0))
  msgBytes(8) := hexChar(jedecCap(7 downto 4))
  msgBytes(9) := hexChar(jedecCap(3 downto 0))
  msgBytes(10) := B"x0D" // '\r'
  msgBytes(11) := B"x0A" // '\n'

  when(testDone) {
    when(txState < 12) {
      uartCtrl.io.write.valid := True
      uartCtrl.io.write.payload := msgBytes(txState.resized)
      when(uartCtrl.io.write.ready) {
        txState := txState + 1
      }
    } otherwise {
      // Wait ~2s then repeat
      txDelay := txDelay + 1
      when(txDelay.andR) {
        txState := 0
      }
    }
  }

  // LED display
  when(testDone) {
    // Show manufacturer ID
    io.led(7) := True           // test complete indicator
    io.led(6 downto 0) := jedecMfr(6 downto 0)
  } otherwise {
    // Show progress
    io.led(7) := startup.io.EOS  // EOS indicator
    io.led(6) := heartbeat
    io.led(5 downto 0) := state.asBits.resize(6)
  }
}

object SpiDiagnosticTopVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = LOW
    )
  ).generate(SpiDiagnosticTop())
  println("Generated: spinalhdl/generated/SpiDiagnosticTop.v")
}
