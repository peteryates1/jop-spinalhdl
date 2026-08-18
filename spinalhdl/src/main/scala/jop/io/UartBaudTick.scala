package jop.io

import spinal.core._
import scala.math.BigDecimal.RoundingMode

/**
 * Fractional baud-rate tick generator.
 *
 * WHY NOT A PLAIN DIVIDER. `UartCtrl` divides by an integer:
 * `clockDivider = round(clkFreq / baud / samplesPerBit) - 1`. That is exact
 * only when the clock happens to be a whole multiple of `baud x samplesPerBit`,
 * and on this project's boards it often is not:
 *
 *   80.000 MHz -> 2 Mbaud   divisor  8   exact
 *  100.000 MHz -> 2 Mbaud   divisor 10   exact
 *   75.000 MHz -> 1 Mbaud   divisor 15   exact
 *   75.000 MHz -> 2 Mbaud   divisor  7   2,142,857 baud, +7.14 %   <-- broken
 *   91.676 MHz -> 2 Mbaud   divisor  9   2,037,244 baud, +1.86 %
 *
 * Those last two are not hypothetical. They are the A-E115FB's documented
 * "baud must divide 75 MHz / 5 exactly, 2 M does NOT", and the Wukong's
 * DDR3_UART_BAUD of 2037000 -- a host-side constant that exists purely to
 * chase a hardware rounding error. No integer divisor can fix either: 2 Mbaud
 * from 91.676 MHz needs 45.838 clocks per bit.
 *
 * HOW THIS WORKS. A numerically controlled oscillator. Add a fixed increment
 * to a phase accumulator every clock and tick on the carry out:
 *
 *   inc  = round(2^accWidth x baud x samplesPerBit / clkFreq)
 *   rate = clkFreq x inc / 2^accWidth  ~= baud x samplesPerBit
 *
 * At `accWidth = 24` the average rate is within about one part in 10^7 of the
 * request -- for the Wukong, 2,000,000.2 baud against a wanted 2,000,000.
 *
 * WHAT YOU TRADE. The average is right but individual ticks land on clock
 * edges, so each is up to one clock period early or late: 10.9 ns at
 * 91.676 MHz, against a 100 ns sample interval and a 500 ns bit. Crucially
 * that jitter does NOT ACCUMULATE, because the accumulator carries the
 * remainder forward. The integer divider's error does accumulate: +1.86 % is
 * ~18 % of a bit by the stop bit of an 8N1 frame, and it is 68 % at the
 * A-E115FB's +7.14 %, which is why that board simply cannot run 2 Mbaud today.
 *
 * Cost is an adder and `accWidth` flip-flops.
 *
 * @param clkFreq       clock feeding the accumulator
 * @param baudRate      wanted line rate
 * @param samplesPerBit receiver oversampling (`UartCtrlGenerics.rxSamplePerBit`)
 * @param accWidth      phase accumulator width; 24 is far more than enough
 */
object UartBaudTick {

  /** Phase increment, exposed so tests and elaboration checks can see it. */
  def increment(clkFreq: HertzNumber, baudRate: Int,
                samplesPerBit: Int, accWidth: Int = 24): BigInt = {
    val exact = (BigDecimal(2).pow(accWidth) * BigDecimal(baudRate) *
                 BigDecimal(samplesPerBit)) / clkFreq.toBigDecimal
    exact.setScale(0, RoundingMode.HALF_UP).toBigInt
  }

  /** Average line rate actually produced, for reporting and tests. */
  def actualRate(clkFreq: HertzNumber, baudRate: Int,
                 samplesPerBit: Int, accWidth: Int = 24): BigDecimal =
    clkFreq.toBigDecimal * BigDecimal(increment(clkFreq, baudRate, samplesPerBit, accWidth)) /
      BigDecimal(2).pow(accWidth) / BigDecimal(samplesPerBit)

  /** One-cycle tick at `baudRate * samplesPerBit`, on average. */
  def apply(clkFreq: HertzNumber, baudRate: Int,
            samplesPerBit: Int, accWidth: Int = 24): Bool = {
    val inc = increment(clkFreq, baudRate, samplesPerBit, accWidth)
    require(inc > 0,
      s"baud $baudRate x $samplesPerBit is too slow to represent in $accWidth bits at $clkFreq")
    require(inc < (BigInt(1) << accWidth),
      s"baud $baudRate x $samplesPerBit exceeds the $clkFreq clock; the UART cannot run this fast")

    val acc = Reg(UInt(accWidth + 1 bits)) init (0)
    // Drop last cycle's carry, then add with the carry out landing in the MSB.
    acc := acc(accWidth - 1 downto 0) +^ U(inc, accWidth bits)
    acc.msb
  }
}
