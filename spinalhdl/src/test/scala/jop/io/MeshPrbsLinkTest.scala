package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import jop.TestVectorUtils

/**
 * The cluster carrier's mesh link, TX into RX as on the board: the receiver
 * runs on the clock the transmitter forwards through its ODDR.
 *
 * THE LAUNCH EDGE IS CHECKED ON THE LINES, NOT BY THE CHECKER. In a zero-delay
 * simulation, data launched on the wrong (rising) edge only shifts the stream
 * by a cycle, and the PRBS checker locks onto it as happily: proved, the four
 * checker tests below passed with the data launched from the rising edge. So
 * the first test watches the TX lines themselves.
 *
 * `invert` XORs into the line (D0..D3, then FR) for as long as it is held, and
 * `stuck0` holds every data line at 0 -- a peer that is not driving.
 */
class MeshPrbsLinkTest extends AnyFunSuite {

  case class Harness(windowLog2: Int) extends Component {
    val io = new Bundle {
      val invert = in Bits(5 bits)
      val stuck0 = in Bool()
      val status = out(MeshRxStatus())
      val valid  = out Bool()
      val txLine = out Bits(5 bits)  // D0..D3, FR as they leave the ODDRs
    }
    val tx = MeshTxPrbs(seed = 5)
    val rxCd = ClockDomain(tx.io.clk, config = ClockDomainConfig(resetKind = BOOT))
    val rxArea = new ClockingArea(rxCd) {
      val rx = MeshRxPrbs(windowLog2)
      val line = (tx.io.fr ## tx.io.d) ^ io.invert
      rx.io.d  := Mux(io.stuck0, B(0, 4 bits), line(3 downto 0))
      rx.io.fr := !io.stuck0 && line(4)
    }
    io.status := rxArea.rx.io.status.payload
    io.valid  := rxArea.rx.io.status.valid
    io.txLine := tx.io.fr ## tx.io.d
  }

  val windowLog2 = 8
  lazy val compiled = TestVectorUtils.simWave(SimConfig)
    .workspaceName("MeshPrbsLinkTest")
    .addRtl("spinalhdl/src/test/resources/xilinx_oddr_model.v")
    .compile(Harness(windowLog2))

  /** Runs `body` with a clock and a recorder of each window's status. */
  def run(stuck0: Boolean = false)(body: (Harness, () => Map[String, BigInt], Int => Unit) => Unit): Unit =
    compiled.doSim { dut =>
      dut.io.invert #= 0
      dut.io.stuck0 #= stuck0
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(4)
      dut.clockDomain.deassertReset()
      var last = Map[String, BigInt]("lock" -> 0, "everLocked" -> 0, "errors" -> 0, "windows" -> 0)
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.io.valid.toBoolean) last = Map(
            "lock" -> (if (dut.io.status.lock.toBoolean) 1 else 0),
            "everLocked" -> (if (dut.io.status.everLocked.toBoolean) 1 else 0),
            "errors" -> dut.io.status.errors.toBigInt,
            "windows" -> dut.io.status.windows.toBigInt)
        }
      }
      body(dut, () => last, n => dut.clockDomain.waitSampling(n))
    }

  val window = 1 << windowLog2

  test("the data changes on the falling edge, never on the forwarded clock's rising edge") {
    run() { (dut, _, cycles) =>
      cycles(16)
      // Read mid-way through each half cycle (the clock's period is 10): a read
      // AT an edge gives the value from just before it, which is how the first
      // version of this test measured the falling edge and called it the rising.
      var atRise, atFall = 0
      var prevLow = -1
      for (_ <- 0 until 1000) {
        dut.clockDomain.waitRisingEdge(); sleep(2)
        val high = dut.io.txLine.toInt
        if (prevLow >= 0 && high != prevLow) atRise += 1
        dut.clockDomain.waitFallingEdge(); sleep(2)
        val low = dut.io.txLine.toInt
        if (low != high) atFall += 1
        prevLow = low
      }
      assert(atRise == 0, s"$atRise of 1000 rising edges changed a data line")
      assert(atFall > 400, s"the lines changed on only $atFall of 1000 falling edges")
    }
  }

  test("a clean link locks, and counts no errors") {
    run() { (_, status, cycles) =>
      cycles(4 * window)
      val s = status()
      assert(s("lock") == 1 && s("everLocked") == 1, s)
      assert(s("errors") == 0, s)
      assert(s("windows") >= 2, s)
    }
  }

  test("one flipped data bit costs exactly three errors, and the next window is clean") {
    run() { (dut, status, cycles) =>
      cycles(3 * window)
      assert(status()("everLocked") == 1)
      dut.io.invert #= 0x4  // D2, for one cycle
      cycles(1)
      dut.io.invert #= 0
      cycles(3 * window)
      val s = status()
      assert(s("errors") == 3, s)
      assert(s("lock") == 1, s)
    }
  }

  test("a flipped FR is caught as well") {
    run() { (dut, status, cycles) =>
      cycles(3 * window)
      dut.io.invert #= 0x10
      cycles(1)
      dut.io.invert #= 0
      cycles(3 * window)
      assert(status()("errors") == 3, status())
    }
  }

  test("a link held at 0 never locks, though it never errs either") {
    run(stuck0 = true) { (_, status, cycles) =>
      cycles(6 * window)
      val s = status()
      assert(s("everLocked") == 0 && s("lock") == 0, s)
      assert(s("windows") == 0, s)
    }
  }
}
