package jop.io

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import jop.utils.JopSimDefaults
import org.scalatest.funsuite.AnyFunSuite
import scala.math.BigDecimal.RoundingMode

/**
 * The claim is that the fractional generator hits the requested baud on clocks
 * where an integer divisor cannot. That is measured off the running hardware
 * rather than re-derived from the increment arithmetic -- a correct increment
 * driving a broken accumulator would otherwise look fine.
 *
 * Everything is measured in CLOCK CYCLES, never in simulated seconds. The
 * declared `clkFreq` is an elaboration parameter and the testbench clock is
 * whatever `forkStimulus` says; comparing the two would measure the testbench.
 * Cycles per bit is the invariant: it must come out at `clkFreq / baud`.
 */
class UartBaudTickSim extends AnyFunSuite {

  val SAMPLES = 5 // preSampling 1 + sampling 3 + postSampling 1, as jop.io.Uart uses

  /** What `UartCtrl.setClockDivider` would give: an integer divisor. */
  def integerDividerBaud(clk: BigDecimal, baud: Int): BigDecimal = {
    val n = (clk / BigDecimal(baud) / SAMPLES).setScale(0, RoundingMode.HALF_DOWN)
    clk / n / SAMPLES
  }

  // Real board clock/baud pairs, including the two that are broken today.
  val boards = Seq(
    ("EP4CGX150 SDR",        BigDecimal(80000000),  2000000),
    ("XC7A100T DB V5 DDR3",  BigDecimal(100000000), 2000000),
    ("Colorlight i5 SDR",    BigDecimal(40000000),  1000000),
    ("A-E115FB DDR2 @1M",    BigDecimal(75000000),  1000000),
    ("A-E115FB DDR2 @2M",    BigDecimal(75000000),  2000000),
    ("Wukong DDR3 Ddr3_366", BigDecimal(91676000),  2000000))

  test("the fractional average is exact where an integer divisor is not") {
    var improved = 0
    for ((name, clk, baud) <- boards) {
      val frac = UartBaudTick.actualRate(HertzNumber(clk), baud, SAMPLES)
      val int_ = integerDividerBaud(clk, baud)
      val fErr = ((frac - baud) / baud * 100).toDouble
      val iErr = ((int_ - baud) / baud * 100).toDouble
      info(f"$name%-22s integer ${int_.toDouble}%10.0f (${iErr}%+.2f%%)   fractional ${frac.toDouble}%12.2f (${fErr}%+.5f%%)")
      assert(scala.math.abs(fErr) < 0.01, s"$name: fractional baud off by $fErr%")
      if (scala.math.abs(iErr) > 0.1) improved += 1
    }
    // Guards the premise. If every board became exact by integer division this
    // component would be dead weight and should be reconsidered, not kept.
    assert(improved >= 2,
      "no board needs fractional division any more — is UartBaudTick still earning its keep?")
  }

  /** Bare generator, so the measurement is of the accumulator and nothing else. */
  class TickDut(clkFreq: BigDecimal, baud: Int) extends Component {
    val io = new Bundle { val tick = out Bool() }
    io.tick := UartBaudTick(HertzNumber(clkFreq), baud, SAMPLES)
  }

  /** Cycles per bit, averaged over `cycles` clocks of the real hardware. */
  def measureCyclesPerBit(clkFreq: BigDecimal, baud: Int, cycles: Int = 400000): Double = {
    var ticks = 0L
    JopSimDefaults.config.compile(new TickDut(clkFreq, baud)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)
      dut.clockDomain.onSamplings { if (dut.io.tick.toBoolean) ticks += 1 }
      dut.clockDomain.waitSampling(cycles)
    }
    assert(ticks > 0, "generator never ticked")
    cycles.toDouble * SAMPLES / ticks.toDouble
  }

  def checkRate(name: String, clk: BigDecimal, baud: Int): Unit = {
    val ideal = (clk / baud).toDouble
    val got = measureCyclesPerBit(clk, baud)
    val err = (got - ideal) / ideal * 100
    val oldErr = ((integerDividerBaud(clk, baud) - baud) / baud * 100).toDouble
    info(f"$name: measured $got%.4f cycles/bit vs ideal $ideal%.4f (${err}%+.4f%%); " +
         f"integer divisor would be ${oldErr}%+.2f%%")
    assert(scala.math.abs(err) < 0.02, f"$name: measured rate is $err%+.4f%% off ideal")
  }

  test("measured rate on the Wukong's 91.676 MHz is 2 Mbaud, not 2.037") {
    checkRate("Wukong Ddr3_366", BigDecimal(91676000), 2000000)
  }

  test("measured rate on the A-E115FB's 75 MHz reaches 2 Mbaud — today it cannot") {
    checkRate("A-E115FB @2M", BigDecimal(75000000), 2000000)
  }

  test("a board whose divisor was already exact is unchanged") {
    checkRate("EP4CGX150 80 MHz", BigDecimal(80000000), 2000000)
  }

  /** Loopback proves the controller carries bytes. It shares one tick between
    * TX and RX, so it cannot detect a systematic rate error -- that is what the
    * measurements above are for. The two together cover both failure modes. */
  class LoopDut(clkFreq: BigDecimal, baud: Int) extends Component {
    val io = new Bundle {
      val push = slave Stream (Bits(8 bits))
      val pop = master Stream (Bits(8 bits))
    }
    val ctrl = JopUartCtrl(baud, HertzNumber(clkFreq))
    ctrl.io.write << io.push
    io.pop << ctrl.io.read
    ctrl.io.rxd := ctrl.io.txd // loop the wire
    ctrl.io.writeBreak := False
  }

  test("loopback carries every byte at 91.676 MHz / 2 Mbaud") {
    JopSimDefaults.config.compile(new LoopDut(BigDecimal(91676000), 2000000)).doSim { dut =>
      dut.io.push.valid #= false
      dut.io.pop.ready #= true
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(10)

      val sent = (0 until 48).map(i => (i * 7 + 3) & 0xFF)
      val got = scala.collection.mutable.ArrayBuffer[Int]()
      dut.clockDomain.onSamplings {
        if (dut.io.pop.valid.toBoolean) got += dut.io.pop.payload.toInt
      }
      for (b <- sent) {
        dut.io.push.valid #= true
        dut.io.push.payload #= b
        dut.clockDomain.waitSamplingWhere(dut.io.push.ready.toBoolean)
        dut.io.push.valid #= false
      }
      dut.clockDomain.waitSampling(48 * 11 * 50) // frames x bits x ~46 clocks, plus slack
      assert(got.toSeq == sent,
        s"loopback mismatch: sent ${sent.take(10)}... got ${got.take(10)}... (${got.size}/${sent.size} bytes)")
    }
  }
}
