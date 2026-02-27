package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._

/**
 * Minimal UART TX test — just outputs "HELLO\r\n" repeatedly.
 * Used to debug FlashWriterTop UART issue.
 */
case class UartTestTop() extends Component {

  val io = new Bundle {
    val clk_in  = in Bool()
    val ser_txd = out Bool()
    val ser_rxd = in Bool()
    val led     = out Bits(2 bits)
  }

  noIoPrefix()

  val pll = DramPll()
  pll.io.inclk0 := io.clk_in
  pll.io.areset := False

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

  val mainArea = new ClockingArea(mainClockDomain) {

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

    // Always consume RX
    uartCtrl.io.read.ready := True

    // Simple message: "HELLO\r\n"
    val msg = Vec(
      B(0x48, 8 bits), B(0x45, 8 bits), B(0x4C, 8 bits), B(0x4C, 8 bits),
      B(0x4F, 8 bits), B(0x0D, 8 bits), B(0x0A, 8 bits)
    )

    val initCnt = Reg(UInt(28 bits)) init (0)
    val msgIdx  = Reg(UInt(3 bits)) init (0)
    val sending = Reg(Bool()) init (False)
    val loopCnt = Reg(UInt(16 bits)) init (0)

    uartCtrl.io.write.valid := False
    uartCtrl.io.write.payload := msg(msgIdx)

    io.led := ~loopCnt(1 downto 0).asBits

    when(!sending) {
      initCnt := initCnt + 1
      when(initCnt.andR) {
        sending := True
        msgIdx := 0
      }
    } otherwise {
      uartCtrl.io.write.valid := True
      when(uartCtrl.io.write.ready) {
        msgIdx := msgIdx + 1
        when(msgIdx === 6) {
          msgIdx := 0
          loopCnt := loopCnt + 1
        }
      }
    }
  }
}

object UartTestTopVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(80 MHz)
  ).generate(UartTestTop())
  println("Generated: spinalhdl/generated/UartTestTop.v")
}
