package jop.system

import spinal.core._
import spinal.core.sim._
import jop.utils.JopSimDefaults
import org.scalatest.funsuite.AnyFunSuite

/**
 * The safety argument for the UART reset escape is that no DATA can trigger it.
 * That claim is the whole reason a break was chosen over a magic byte sequence,
 * so it is tested here rather than asserted in a comment.
 */
class UartResetEscapeSim extends AnyFunSuite {

  val BAUD = 1000000
  val CLK  = 50000000
  val BIT  = CLK / BAUD          // clock cycles per bit

  // Small window so the timeout case does not need millions of cycles. The
  // shipped default is 100 ms; what is tested here is the mechanism, plus the
  // fact that a window comfortably longer than a frame still admits a byte
  // that arrives late (the real host is milliseconds late -- see the note in
  // UartResetEscape on why a bit-time-sized window is unreachable).
  val WINDOW_MS = 0.2                       // 10000 cycles at 50 MHz
  val WINDOW_CYCLES = (CLK * WINDOW_MS / 1000).toInt
  def compiled = JopSimDefaults.config.compile(
    UartResetEscape(BAUD, HertzNumber(CLK), windowMs = WINDOW_MS))

  /** Drive one 8N1 frame on rxd. */
  def sendByte(dut: UartResetEscape, b: Int): Unit = {
    dut.io.rxd #= false                                  // start
    dut.clockDomain.waitSampling(BIT)
    for (i <- 0 until 8) {
      dut.io.rxd #= ((b >> i) & 1) == 1
      dut.clockDomain.waitSampling(BIT)
    }
    dut.io.rxd #= true                                   // stop
    dut.clockDomain.waitSampling(BIT)
  }

  /** Hold the line low for n bit-times — a break when n >= 13. */
  def sendBreak(dut: UartResetEscape, bits: Int = 20): Unit = {
    dut.io.rxd #= false
    dut.clockDomain.waitSampling(BIT * bits)
    dut.io.rxd #= true
    dut.clockDomain.waitSampling(BIT * 2)
  }

  /** Run body, return true if resetRequest ever pulsed. */
  def watch(body: (UartResetEscape, () => Boolean) => Unit): Boolean = {
    var fired = false
    compiled.doSim { dut =>
      dut.io.rxd #= true
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(10)
      dut.clockDomain.onSamplings { if (dut.io.resetRequest.toBoolean) fired = true }
      body(dut, () => fired)
      dut.clockDomain.waitSampling(BIT * 4)
    }
    fired
  }

  test("break followed by the magic byte triggers a reset") {
    assert(watch { (dut, _) =>
      sendBreak(dut)
      sendByte(dut, 0x52)
      dut.clockDomain.waitSampling(BIT * 4)
    })
  }

  test("NO data pattern can trigger a reset — this is the safety property") {
    // Every byte value, including 0x00 (the longest low run a frame can hold)
    // and the magic byte itself, sent without a preceding break.
    assert(!watch { (dut, _) =>
      for (b <- 0 to 255) sendByte(dut, b)
    })
  }

  test("back-to-back 0x00 bytes do not look like a break") {
    // 9 low bit-times, 1 high, 9 low ... never the 13 consecutive the detector
    // needs. This is the case a naive threshold would get wrong.
    assert(!watch { (dut, _) =>
      for (_ <- 0 until 20) sendByte(dut, 0x00)
      sendByte(dut, 0x52)
    })
  }

  test("break followed by the WRONG byte does not reset") {
    assert(!watch { (dut, _) =>
      sendBreak(dut)
      sendByte(dut, 0x53)
      dut.clockDomain.waitSampling(BIT * 4)
    })
  }

  test("a line stuck low never resets — covers an unplugged or unpowered cable") {
    assert(!watch { (dut, _) =>
      dut.io.rxd #= false
      dut.clockDomain.waitSampling(BIT * 400)
    })
  }

  test("break with no byte behind it times out — covers open/close glitches") {
    assert(!watch { (dut, _) =>
      sendBreak(dut)
      dut.clockDomain.waitSampling(WINDOW_CYCLES + BIT * 4)
      sendByte(dut, 0x52)                      // too late to count
      dut.clockDomain.waitSampling(BIT * 4)
    })
  }

  test("a byte arriving LATE but inside the window still resets") {
    // The case the first version of this component got wrong: the window was
    // sized in bit-times, so the host's few milliseconds of syscall and USB
    // latency always missed it. Simulation passed because the testbench sent
    // the byte immediately; hardware would never have triggered.
    assert(watch { (dut, _) =>
      sendBreak(dut)
      dut.clockDomain.waitSampling(WINDOW_CYCLES / 2)   // long after any frame
      sendByte(dut, 0x52)
      dut.clockDomain.waitSampling(BIT * 4)
    })
  }

  test("the trigger re-arms, so a second reset works") {
    assert(watch { (dut, fired) =>
      sendBreak(dut); sendByte(dut, 0x52)
      dut.clockDomain.waitSampling(BIT * 4)
      assert(fired(), "first reset should have fired")
      sendBreak(dut); sendByte(dut, 0x52)
      dut.clockDomain.waitSampling(BIT * 4)
    })
  }
}
