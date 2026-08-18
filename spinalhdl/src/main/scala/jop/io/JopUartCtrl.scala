package jop.io

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._

/**
 * UART controller with an EXACT average baud rate.
 *
 * Same wiring as `spinal.lib.com.uart.UartCtrl` -- it instantiates the stock
 * `UartCtrlTx` and `UartCtrlRx` unchanged -- but the sampling tick comes from
 * `UartBaudTick`, a fractional (NCO) generator, instead of `UartCtrl`'s
 * integer `clockDivider`. See `UartBaudTick` for why that matters: an integer
 * divisor cannot produce 2 Mbaud from 91.676 MHz or 75 MHz, and both of those
 * are real boards here.
 *
 * The frame is fixed at 8N1, which is what every JOP board uses and what the
 * boot loader's download protocol assumes. Making it configurable would mean
 * carrying a config bundle for something nothing varies.
 *
 * Baud is an ELABORATION-TIME parameter, not a runtime register. `UartCtrl`
 * exposes `config.clockDivider` on its bus so software can retune; nothing in
 * JOP does, and fixing it lets the increment be a constant rather than a
 * multiplier.
 *
 * @param baudRate wanted line rate; default matches jop.io.Uart
 * @param clkFreq  clock feeding this controller
 * @param generics oversampling; JOP uses 5 samples per bit
 * @param accWidth phase accumulator width
 */
case class JopUartCtrl(baudRate: Int = 1000000,
                       clkFreq: HertzNumber = HertzNumber(100000000),
                       generics: UartCtrlGenerics = UartCtrlGenerics(
                         preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1),
                       accWidth: Int = 24) extends Component {

  val io = new Bundle {
    val write      = slave Stream (Bits(8 bits))
    val read       = master Stream (Bits(8 bits))
    val txd        = out Bool()
    val rxd        = in Bool()
    val writeBreak = in Bool()
    val readBreak  = out Bool()
    val readError  = out Bool()
  }

  val tx = new UartCtrlTx(generics)
  val rx = new UartCtrlRx(generics)

  // Already a register output (the accumulator's carry bit), so this matches
  // UartCtrl's registered `tickReg` without an extra stage.
  val samplingTick = UartBaudTick(clkFreq, baudRate, generics.rxSamplePerBit, accWidth)
  tx.io.samplingTick := samplingTick
  rx.io.samplingTick := samplingTick

  val frame = UartCtrlFrameConfig(generics)
  frame.dataLength := 7 // 8 bits, 0-indexed
  frame.parity     := UartParityType.NONE
  frame.stop       := UartStopType.ONE
  tx.io.configFrame := frame
  rx.io.configFrame := frame

  // throwWhen(break) is UartCtrl's own behaviour, kept deliberately: it is what
  // makes a host break visible as suppressed TX, which is how the CP2102N's
  // missing break support was diagnosed (see download.py send_reset).
  tx.io.write << io.write.throwWhen(rx.io.break)
  rx.io.read >> io.read

  io.txd := tx.io.txd
  rx.io.rxd := io.rxd
  tx.io.cts := False
  tx.io.break := io.writeBreak
  io.readBreak := rx.io.break
  io.readError := rx.io.error

  /** Average line rate this instance actually produces. */
  def effectiveBaud: BigDecimal =
    UartBaudTick.actualRate(clkFreq, baudRate, generics.rxSamplePerBit, accWidth)
}
