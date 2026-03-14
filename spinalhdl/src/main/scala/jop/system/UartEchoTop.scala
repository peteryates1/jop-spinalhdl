package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._

/**
 * UART echo test for QMTECH XC7A100T + DB_FPGA V5.
 *
 * Sends startup banner, then echoes all received characters.
 * Uses 50 MHz input clock directly (no PLL).
 *
 * RP2040 UART0: GPIO0 (TX) → B5 (FPGA rxd), GPIO1 (RX) → A5 (FPGA txd)
 */
case class UartEchoTop() extends Component {

  val io = new Bundle {
    val clk_in = in Bool()
    val rxd    = in Bool()
    val txd    = out Bool()
    val led    = out Bits(2 bits)
  }

  noIoPrefix()

  // Use raw 50 MHz clock with BOOT reset (no PLL)
  val rawClockDomain = ClockDomain(
    clock = io.clk_in,
    frequency = FixedFrequency(50 MHz),
    config = ClockDomainConfig(resetKind = BOOT)
  )

  val mainArea = new ClockingArea(rawClockDomain) {

    val uartCtrl = new UartCtrl(UartCtrlGenerics(
      preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1
    ))
    uartCtrl.io.config.setClockDivider(115200 Hz)
    uartCtrl.io.config.frame.dataLength := 7
    uartCtrl.io.config.frame.parity := UartParityType.NONE
    uartCtrl.io.config.frame.stop := UartStopType.ONE
    uartCtrl.io.writeBreak := False
    uartCtrl.io.uart.rxd := io.rxd
    io.txd := uartCtrl.io.uart.txd

    // Startup banner: "JOP XC7A100T Echo\r\n"
    val bannerStr = "JOP XC7A100T Echo\r\n"
    val banner = Vec(bannerStr.map(c => B(c.toInt, 8 bits)))
    val bannerLen = bannerStr.length

    val bannerIdx   = Reg(UInt(log2Up(bannerLen + 1) bits)) init (0)
    val bannerDone  = Reg(Bool()) init (False)
    val startDelay  = Reg(UInt(24 bits)) init (0)  // ~335ms at 50 MHz
    val startReady  = Reg(Bool()) init (False)

    // RX byte counter for LED
    val rxCount = Reg(UInt(8 bits)) init (0)
    io.led := ~rxCount(1 downto 0).asBits  // active-low LEDs

    // RX FIFO: buffer received bytes for echo (prevents drops at full baud)
    val rxFifo = StreamFifo(Bits(8 bits), 64)
    rxFifo.io.push << uartCtrl.io.read

    when(uartCtrl.io.read.fire) {
      rxCount := rxCount + 1
    }

    // TX state machine
    uartCtrl.io.write.valid := False
    uartCtrl.io.write.payload := B(0, 8 bits)
    rxFifo.io.pop.ready := False

    // Startup delay before sending banner
    when(!startReady) {
      startDelay := startDelay + 1
      when(startDelay.andR) {
        startReady := True
      }
    }

    // Send banner first, then echo from FIFO
    when(startReady && !bannerDone) {
      uartCtrl.io.write.valid := True
      uartCtrl.io.write.payload := banner(bannerIdx.resized)
      when(uartCtrl.io.write.ready) {
        when(bannerIdx === (bannerLen - 1)) {
          bannerDone := True
        } otherwise {
          bannerIdx := bannerIdx + 1
        }
      }
    } elsewhen (bannerDone && rxFifo.io.pop.valid) {
      uartCtrl.io.write.valid := True
      uartCtrl.io.write.payload := rxFifo.io.pop.payload
      rxFifo.io.pop.ready := uartCtrl.io.write.ready
    }
  }
}

object UartEchoTopVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  ).generate(UartEchoTop())
  println("Generated: spinalhdl/generated/UartEchoTop.v")
}
