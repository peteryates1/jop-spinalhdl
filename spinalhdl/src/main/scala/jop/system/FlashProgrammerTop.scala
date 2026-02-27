package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._

/**
 * UART-Based Flash Programmer
 *
 * Minimal FPGA design that exposes the W25Q128 SPI flash (on config pins)
 * to a host PC via UART. The host sends commands to assert/deassert CS and
 * transfer SPI bytes; all flash command sequencing is done in Python.
 *
 * Protocol (1 Mbaud, 8N1):
 *   0xBB       → CS low,  echo 0xBB
 *   0xCC       → CS high, echo 0xCC
 *   0xDD <b>   → SPI transfer byte b (escape for 0xBB/CC/DD data), echo MISO
 *   other byte → SPI transfer,  echo MISO
 */
case class FlashProgrammerTop() extends Component {

  val io = new Bundle {
    val clk_in     = in Bool()
    val ser_txd    = out Bool()
    val ser_rxd    = in Bool()
    val led        = out Bits(2 bits)
    // Direct SPI pins to config flash (active serial pins)
    val flash_dclk  = out Bool()
    val flash_ncs   = out Bool()
    val flash_asdo  = out Bool()
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
    val spiClk      = Reg(Bool()) init (False)
    val spiCs       = Reg(Bool()) init (True)  // active low, idle high
    val spiClkCnt   = Reg(UInt(2 bits)) init (0)
    val spiShiftOut = Reg(Bits(8 bits)) init (0)
    val spiShiftIn  = Reg(Bits(8 bits)) init (0)
    val spiBitCnt   = Reg(UInt(3 bits)) init (0)
    val spiTxByte   = Reg(Bits(8 bits)) init (0)
    val spiRxByte   = Reg(Bits(8 bits)) init (0)

    // Direct SPI pin wiring (config flash pins as regular I/O)
    io.flash_dclk := spiClk
    io.flash_ncs  := spiCs
    io.flash_asdo := spiShiftOut.msb
    val spiMiso = io.flash_data0

    // UART (1 Mbaud at 80 MHz) — both TX and RX
    val uartCtrl = new UartCtrl(UartCtrlGenerics(
      preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1
    ))
    uartCtrl.io.config.setClockDivider(1000000 Hz)
    uartCtrl.io.config.frame.dataLength := 7
    uartCtrl.io.config.frame.parity := UartParityType.NONE
    uartCtrl.io.config.frame.stop := UartStopType.ONE
    uartCtrl.io.writeBreak := False
    uartCtrl.io.uart.rxd := io.ser_rxd
    io.ser_txd := uartCtrl.io.uart.txd

    // TX FIFO
    val txFifo = StreamFifo(Bits(8 bits), 256)
    uartCtrl.io.write.valid := txFifo.io.pop.valid
    uartCtrl.io.write.payload := txFifo.io.pop.payload
    txFifo.io.pop.ready := uartCtrl.io.write.ready

    txFifo.io.push.valid := False
    txFifo.io.push.payload := 0

    // RX stream
    uartCtrl.io.read.ready := False

    // ====================================================================
    // State Machine
    // ====================================================================
    object S extends SpinalEnum {
      val INIT_WAIT, IDLE = newElement()
      val CS_LO, CS_HI, ESCAPE = newElement()
      val SPI_LOAD, SPI_RISE, SPI_FALL, SPI_DONE = newElement()
      // Banner TX states
      val BANNER_F, BANNER_P, BANNER_CR, BANNER_LF = newElement()
    }

    val state = RegInit(S.INIT_WAIT)

    // Init counter (28 bits at 80MHz = ~3.4s delay for UART monitor attach)
    val initCnt = Reg(UInt(28 bits)) init (0)

    // LED
    val ledReg = Reg(Bits(2 bits)) init (0)
    io.led := ~ledReg

    // ====================================================================
    // State Machine Body
    // ====================================================================
    switch(state) {

      // ================================================================
      // Init: wait for power-up, then print banner "FP\r\n"
      // ================================================================
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

      // ================================================================
      // IDLE: wait for UART RX byte, dispatch
      // ================================================================
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

      // ================================================================
      // CS_LO: assert CS, echo 0xBB
      // ================================================================
      is(S.CS_LO) {
        spiCs := False
        when(txFifo.io.push.ready) {
          txFifo.io.push.valid := True
          txFifo.io.push.payload := B"xBB"
          state := S.IDLE
        }
      }

      // ================================================================
      // CS_HI: deassert CS, echo 0xCC
      // ================================================================
      is(S.CS_HI) {
        spiCs := True
        spiClk := False
        when(txFifo.io.push.ready) {
          txFifo.io.push.valid := True
          txFifo.io.push.payload := B"xCC"
          state := S.IDLE
        }
      }

      // ================================================================
      // ESCAPE: wait for next RX byte, use it as SPI data
      // ================================================================
      is(S.ESCAPE) {
        when(uartCtrl.io.read.valid) {
          uartCtrl.io.read.ready := True
          spiTxByte := uartCtrl.io.read.payload
          state := S.SPI_LOAD
        }
      }

      // ================================================================
      // SPI byte transfer sub-states (identical to exerciser)
      // ================================================================
      is(S.SPI_LOAD) {
        spiShiftOut := spiTxByte
        spiBitCnt := 0
        spiClkCnt := 0
        spiClk := False
        state := S.SPI_RISE
      }

      is(S.SPI_RISE) {
        spiClkCnt := spiClkCnt + 1
        when(spiClkCnt === 3) {
          spiClkCnt := 0
          spiClk := True
          // Sample MISO on rising edge
          spiShiftIn := spiShiftIn(6 downto 0) ## spiMiso
          state := S.SPI_FALL
        }
      }

      is(S.SPI_FALL) {
        spiClkCnt := spiClkCnt + 1
        when(spiClkCnt === 3) {
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
        // Echo received byte
        when(txFifo.io.push.ready) {
          txFifo.io.push.valid := True
          txFifo.io.push.payload := spiShiftIn
          ledReg := ~ledReg
          state := S.IDLE
        }
      }
    }
  }
}

/**
 * Generate Verilog for FlashProgrammerTop
 */
object FlashProgrammerTopVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(80 MHz)
  ).generate(FlashProgrammerTop())

  println("Generated: spinalhdl/generated/FlashProgrammerTop.v")
}
