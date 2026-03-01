package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import jop.ddr3._

/**
 * UART-Based Flash Programmer for Artix-7 (Alchitry Au V2)
 *
 * Same protocol as FlashProgrammerTop (Cyclone IV) but adapted for:
 *   - 100 MHz board clock (no PLL needed, runs directly on board clock)
 *   - STARTUPE2 primitive for CCLK (SPI clock to flash)
 *   - Different pin names (cf_mosi/cf_miso/cf_cs instead of flash_asdo/flash_data0/flash_ncs)
 *
 * The SST26VF032B on the Au V2 requires a Global Block Protection Unlock
 * (ULBPR, 0x98) before erase/write.  This is handled by the Python host
 * script (flash_program.py --sst26).
 *
 * Protocol (1 Mbaud, 8N1):
 *   0xBB       -> CS low,  echo 0xBB
 *   0xCC       -> CS high, echo 0xCC
 *   0xDD <b>   -> SPI transfer byte b (escape for 0xBB/CC/DD data), echo MISO
 *   other byte -> SPI transfer,  echo MISO
 */
case class FlashProgrammerDdr3Top() extends Component {

  val io = new Bundle {
    val led      = out Bits(8 bits)
    val usb_rx   = in Bool()
    val usb_tx   = out Bool()
    // Config flash SPI (MOSI, MISO, CS — CCLK via STARTUPE2)
    val cf_mosi  = out Bool()
    val cf_miso  = in Bool()
    val cf_cs    = out Bool()
  }

  noIoPrefix()

  // ========================================================================
  // SPI registers
  // ========================================================================

  val spiClk      = Reg(Bool()) init(False)
  val spiCs       = Reg(Bool()) init(True)   // active low, idle high
  val spiClkCnt   = Reg(UInt(4 bits)) init(0)
  val spiShiftOut = Reg(Bits(8 bits)) init(0)
  val spiShiftIn  = Reg(Bits(8 bits)) init(0)
  val spiBitCnt   = Reg(UInt(3 bits)) init(0)
  val spiTxByte   = Reg(Bits(8 bits)) init(0)
  val spiRxByte   = Reg(Bits(8 bits)) init(0)

  // STARTUPE2: drive CCLK from our SPI clock
  val startup = StartupE2()
  startup.io.CLK       := False
  startup.io.GSR       := False
  startup.io.GTS       := False
  startup.io.KEYCLEARB := True
  startup.io.PACK      := False
  startup.io.USRCCLKTS := False  // Enable CCLK output
  startup.io.USRDONEO  := True
  startup.io.USRDONETS := True
  startup.io.USRCCLKO  := spiClk

  // SPI data + CS pins
  io.cf_cs   := spiCs
  io.cf_mosi := spiShiftOut.msb
  val spiMiso = io.cf_miso

  // ========================================================================
  // UART (1 Mbaud at 100 MHz)
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

  // TX FIFO
  val txFifo = StreamFifo(Bits(8 bits), 256)
  uartCtrl.io.write.valid := txFifo.io.pop.valid
  uartCtrl.io.write.payload := txFifo.io.pop.payload
  txFifo.io.pop.ready := uartCtrl.io.write.ready

  txFifo.io.push.valid := False
  txFifo.io.push.payload := 0

  // RX stream
  uartCtrl.io.read.ready := False

  // ========================================================================
  // State Machine
  // ========================================================================

  object S extends SpinalEnum {
    val INIT_WAIT, IDLE = newElement()
    val CS_LO, CS_HI, ESCAPE = newElement()
    val SPI_LOAD, SPI_RISE, SPI_FALL, SPI_DONE = newElement()
    val BANNER_F, BANNER_P, BANNER_CR, BANNER_LF = newElement()
  }

  val state = RegInit(S.INIT_WAIT)

  // Init counter (28 bits at 100MHz = ~2.7s delay for UART monitor attach)
  val initCnt = Reg(UInt(28 bits)) init(0)

  // LED
  val ledReg = Reg(Bits(2 bits)) init(0)

  // Heartbeat
  val hbCounter = Reg(UInt(26 bits)) init(0)
  val heartbeat = Reg(Bool()) init(False)
  hbCounter := hbCounter + 1
  when(hbCounter === 49999999) {
    hbCounter := 0
    heartbeat := ~heartbeat
  }

  // LED[1:0] = FSM activity, LED[2] = EOS (end of startup), LED[3] = heartbeat
  io.led := B(0, 4 bits) ## heartbeat ## startup.io.EOS ## ledReg

  // ========================================================================
  // State Machine Body
  // ========================================================================

  switch(state) {

    is(S.INIT_WAIT) {
      initCnt := initCnt + 1
      when(initCnt.andR) {
        state := S.BANNER_F
      }
    }

    is(S.BANNER_F) {
      when(txFifo.io.push.ready) {
        txFifo.io.push.valid := True
        txFifo.io.push.payload := B(0x46, 8 bits) // 'F'
        state := S.BANNER_P
      }
    }

    is(S.BANNER_P) {
      when(txFifo.io.push.ready) {
        txFifo.io.push.valid := True
        txFifo.io.push.payload := B(0x50, 8 bits) // 'P'
        state := S.BANNER_CR
      }
    }

    is(S.BANNER_CR) {
      when(txFifo.io.push.ready) {
        txFifo.io.push.valid := True
        txFifo.io.push.payload := B(0x0D, 8 bits) // '\r'
        state := S.BANNER_LF
      }
    }

    is(S.BANNER_LF) {
      when(txFifo.io.push.ready) {
        txFifo.io.push.valid := True
        txFifo.io.push.payload := B(0x0A, 8 bits) // '\n'
        ledReg := B"01"
        state := S.IDLE
      }
    }

    is(S.IDLE) {
      when(uartCtrl.io.read.valid) {
        uartCtrl.io.read.ready := True
        val rxByte = uartCtrl.io.read.payload
        when(rxByte === B"xBB") {
          state := S.CS_LO
        } elsewhen(rxByte === B"xCC") {
          state := S.CS_HI
        } elsewhen(rxByte === B"xDD") {
          state := S.ESCAPE
        } otherwise {
          spiTxByte := rxByte
          state := S.SPI_LOAD
        }
      }
    }

    is(S.CS_LO) {
      spiCs := False
      when(txFifo.io.push.ready) {
        txFifo.io.push.valid := True
        txFifo.io.push.payload := B"xBB"
        state := S.IDLE
      }
    }

    is(S.CS_HI) {
      spiCs := True
      spiClk := False
      when(txFifo.io.push.ready) {
        txFifo.io.push.valid := True
        txFifo.io.push.payload := B"xCC"
        state := S.IDLE
      }
    }

    is(S.ESCAPE) {
      when(uartCtrl.io.read.valid) {
        uartCtrl.io.read.ready := True
        spiTxByte := uartCtrl.io.read.payload
        state := S.SPI_LOAD
      }
    }

    // SPI byte transfer sub-states
    is(S.SPI_LOAD) {
      spiShiftOut := spiTxByte
      spiBitCnt := 0
      spiClkCnt := 0
      spiClk := False
      state := S.SPI_RISE
    }

    is(S.SPI_RISE) {
      spiClkCnt := spiClkCnt + 1
      when(spiClkCnt === 15) {
        spiClkCnt := 0
        spiClk := True
        // Sample MISO on rising edge
        spiShiftIn := spiShiftIn(6 downto 0) ## spiMiso
        state := S.SPI_FALL
      }
    }

    is(S.SPI_FALL) {
      spiClkCnt := spiClkCnt + 1
      when(spiClkCnt === 15) {
        spiClkCnt := 0
        spiClk := False
        spiBitCnt := spiBitCnt + 1
        when(spiBitCnt === 7) {
          state := S.SPI_DONE
        } otherwise {
          spiShiftOut := spiShiftOut(6 downto 0) ## B"0"
          state := S.SPI_RISE
        }
      }
    }

    is(S.SPI_DONE) {
      spiRxByte := spiShiftIn
      when(txFifo.io.push.ready) {
        txFifo.io.push.valid := True
        txFifo.io.push.payload := spiShiftIn
        ledReg := ~ledReg
        state := S.IDLE
      }
    }
  }
}

/**
 * Generate Verilog for FlashProgrammerDdr3Top
 */
object FlashProgrammerDdr3TopVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = LOW
    )
  ).generate(FlashProgrammerDdr3Top())

  println("Generated: spinalhdl/generated/FlashProgrammerDdr3Top.v")
}
