package jop.core

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class FloatComputeUnitTest extends AnyFunSuite {

  // ========================================================================
  // Helpers
  // ========================================================================
  def floatBits(f: Float): Long = java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
  def bitsFloat(b: Long): Float = java.lang.Float.intBitsToFloat((b & 0xFFFFFFFFL).toInt)
  def int32Bits(i: Int): Long   = i.toLong & 0xFFFFFFFFL

  val CANONICAL_NAN = 0x7FC00000L
  val POS_INF       = 0x7F800000L
  val NEG_INF       = 0xFF800000L

  // JVM bytecodes
  val FADD  = 0x62
  val FSUB  = 0x66
  val FMUL  = 0x6A
  val FDIV  = 0x6E
  val I2F   = 0x86
  val F2I   = 0x8B
  val FCMPL = 0x95
  val FCMPG = 0x96

  val fullConfig = FloatComputeUnitConfig(
    withAdd = true, withMul = true, withDiv = true,
    withI2F = true, withF2I = true, withFcmp = true
  )

  val simConfig = SimConfig
    .workspacePath("simWorkspace")

  def compileFull() = simConfig.compile(FloatComputeUnit(fullConfig))

  /** Run one FPU operation, return lower-32-bit result. */
  def runOp(dut: FloatComputeUnit, opa: Long, opb: Long, bytecode: Int)
           (implicit cd: ClockDomain): Long = {
    dut.io.a #= (opa & 0xFFFFFFFFL)
    dut.io.b #= (opb & 0xFFFFFFFFL)
    dut.io.opcode   #= bytecode
    dut.io.wr       #= true
    cd.waitSampling()
    dut.io.wr       #= false
    cd.waitSampling()

    var cycles = 0
    while (dut.io.busy.toBoolean && cycles < 100) {
      cd.waitSampling()
      cycles += 1
    }
    assert(cycles < 100, s"FPU timed out after 100 cycles (bytecode=0x${bytecode.toHexString})")
    dut.io.resultLo.toBigInt.toLong
  }

  /** Run float op. For pipeline-order consistency:
    * a=io.a (TOS in pipeline), b=io.b (NOS in pipeline).
    * Non-commutative ops (fsub/fdiv/fcmp) swap internally so
    * opaReg=value1(NOS), opbReg=value2(TOS). */
  def runFloat(dut: FloatComputeUnit, a: Float, b: Float, bytecode: Int)
              (implicit cd: ClockDomain): Long =
    runOp(dut, floatBits(a), floatBits(b), bytecode)

  def initIo(dut: FloatComputeUnit): Unit = {
    dut.io.a #= 0
    dut.io.b #= 0
    dut.io.opcode   #= 0
    dut.io.wr       #= false
  }

  def assertFloat(actual: Long, expected: Float, msg: String): Unit = {
    val expBits = floatBits(expected)
    assert(actual == expBits,
      f"$msg: expected ${expected}%.7g (0x${expBits}%08X), got ${bitsFloat(actual)}%.7g (0x${actual}%08X)")
  }

  def assertBits(actual: Long, expected: Long, msg: String): Unit = {
    assert(actual == expected,
      f"$msg: expected 0x${expected}%08X, got 0x${actual}%08X")
  }

  def assertNaN(actual: Long, msg: String): Unit = {
    val exp = (actual >> 23) & 0xFF
    val frac = actual & 0x7FFFFF
    assert(exp == 0xFF && frac != 0, f"$msg: expected NaN, got 0x${actual}%08X")
  }

  // ========================================================================
  // ADD tests
  // ========================================================================
  test("add_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertFloat(runFloat(dut, 1.5f, 2.5f, FADD), 4.0f, "1.5 + 2.5")
      assertFloat(runFloat(dut, 100.0f, 0.5f, FADD), 100.5f, "100.0 + 0.5")
      assertFloat(runFloat(dut, -1.5f, -2.5f, FADD), -4.0f, "-1.5 + -2.5")
    }
  }

  test("add_cancellation") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      val r = runFloat(dut, 1.0f, -1.0f, FADD)
      assertBits(r, 0x00000000L, "1.0 + (-1.0) = +0")
    }
  }

  test("add_inf_handling") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits(runFloat(dut, Float.PositiveInfinity, 1.0f, FADD), POS_INF, "Inf + 1 = Inf")
      assertBits(runFloat(dut, Float.NegativeInfinity, 1.0f, FADD), NEG_INF, "-Inf + 1 = -Inf")
      assertNaN(runFloat(dut, Float.PositiveInfinity, Float.NegativeInfinity, FADD), "Inf + (-Inf) = NaN")
    }
  }

  test("add_nan_propagation") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertNaN(runFloat(dut, Float.NaN, 1.0f, FADD), "NaN + 1 = NaN")
      assertNaN(runFloat(dut, 1.0f, Float.NaN, FADD), "1 + NaN = NaN")
    }
  }

  test("add_zero_handling") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertFloat(runFloat(dut, 0.0f, 5.0f, FADD), 5.0f, "0 + 5 = 5")
      assertFloat(runFloat(dut, 5.0f, 0.0f, FADD), 5.0f, "5 + 0 = 5")
    }
  }

  // ========================================================================
  // SUB tests
  // ========================================================================
  test("sub_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // Pipeline order: a=TOS=value2, b=NOS=value1. CU computes value1-value2.
      assertFloat(runFloat(dut, 3.0f, 5.0f, FSUB), 2.0f, "5.0 - 3.0")
      assertFloat(runFloat(dut, 5.0f, 3.0f, FSUB), -2.0f, "3.0 - 5.0")
    }
  }

  test("sub_cancellation") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      val r = runFloat(dut, 1.0f, 1.0f, FSUB)
      assertBits(r, 0x00000000L, "1.0 - 1.0 = +0")
    }
  }

  // ========================================================================
  // MUL tests
  // ========================================================================
  test("mul_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertFloat(runFloat(dut, 3.0f, 4.0f, FMUL), 12.0f, "3.0 * 4.0")
      assertFloat(runFloat(dut, -2.0f, 3.0f, FMUL), -6.0f, "-2.0 * 3.0")
      assertFloat(runFloat(dut, 0.5f, 0.5f, FMUL), 0.25f, "0.5 * 0.5")
    }
  }

  test("mul_special_values") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertNaN(runFloat(dut, Float.PositiveInfinity, 0.0f, FMUL), "Inf * 0 = NaN")
      assertBits(runFloat(dut, Float.PositiveInfinity, 2.0f, FMUL), POS_INF, "Inf * 2 = Inf")
      assertBits(runFloat(dut, 0.0f, 0.0f, FMUL), 0x00000000L, "0 * 0 = +0")
      assertNaN(runFloat(dut, Float.NaN, 1.0f, FMUL), "NaN * 1 = NaN")
    }
  }

  test("mul_sign") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertFloat(runFloat(dut, -3.0f, -4.0f, FMUL), 12.0f, "(-3) * (-4) = +12")
      val r = runFloat(dut, -0.0f, 1.0f, FMUL)
      assert((r >> 31) == 1, s"(-0) * 1 should be negative zero, got 0x${r.toHexString}")
    }
  }

  // ========================================================================
  // DIV tests
  // ========================================================================
  test("div_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // Pipeline order: a=TOS=value2, b=NOS=value1. CU computes value1/value2.
      assertFloat(runFloat(dut, 2.0f, 7.0f, FDIV), 3.5f, "7.0 / 2.0")
      assertFloat(runFloat(dut, 4.0f, 12.0f, FDIV), 3.0f, "12.0 / 4.0")
    }
  }

  test("div_special_values") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // Pipeline order: a=TOS=value2(divisor), b=NOS=value1(dividend)
      assertBits(runFloat(dut, 0.0f, 1.0f, FDIV), POS_INF, "1 / 0 = +Inf")
      assertBits(runFloat(dut, 0.0f, -1.0f, FDIV), NEG_INF, "-1 / 0 = -Inf")
      assertNaN(runFloat(dut, 0.0f, 0.0f, FDIV), "0 / 0 = NaN")
      assertNaN(runFloat(dut, Float.PositiveInfinity, Float.PositiveInfinity, FDIV), "Inf / Inf = NaN")
      assertBits(runFloat(dut, Float.PositiveInfinity, 1.0f, FDIV), 0x00000000L, "1 / Inf = +0")
    }
  }

  // ========================================================================
  // I2F tests
  // ========================================================================
  test("i2f_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertFloat(runOp(dut, int32Bits(1), 0, I2F), 1.0f, "i2f(1)")
      assertFloat(runOp(dut, int32Bits(0), 0, I2F), 0.0f, "i2f(0)")
      assertFloat(runOp(dut, int32Bits(-1), 0, I2F), -1.0f, "i2f(-1)")
      assertFloat(runOp(dut, int32Bits(42), 0, I2F), 42.0f, "i2f(42)")
      assertFloat(runOp(dut, int32Bits(256), 0, I2F), 256.0f, "i2f(256)")
    }
  }

  test("i2f_large") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // INT_MAX: 2147483647 -> rounds to 2.14748365E9 in float
      val r = runOp(dut, int32Bits(0x7FFFFFFF), 0, I2F)
      assertFloat(r, 2147483647.toFloat, "i2f(INT_MAX)")
    }
  }

  // ========================================================================
  // F2I tests
  // ========================================================================
  test("f2i_truncate") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits(runFloat(dut, 3.7f, 0, F2I), int32Bits(3), "f2i(3.7)")
      assertBits(runFloat(dut, -2.3f, 0, F2I), int32Bits(-2), "f2i(-2.3)")
      assertBits(runFloat(dut, 0.5f, 0, F2I), int32Bits(0), "f2i(0.5)")
      assertBits(runFloat(dut, -0.9f, 0, F2I), int32Bits(0), "f2i(-0.9)")
      assertBits(runFloat(dut, 100.0f, 0, F2I), int32Bits(100), "f2i(100.0)")
    }
  }

  test("f2i_special") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits(runFloat(dut, Float.NaN, 0, F2I), 0, "f2i(NaN) = 0")
      assertBits(runFloat(dut, Float.PositiveInfinity, 0, F2I), int32Bits(0x7FFFFFFF), "f2i(+Inf) = INT_MAX")
      assertBits(runFloat(dut, Float.NegativeInfinity, 0, F2I), int32Bits(0x80000000.toInt), "f2i(-Inf) = INT_MIN")
      assertBits(runFloat(dut, 0.0f, 0, F2I), 0, "f2i(0.0) = 0")
    }
  }

  // ========================================================================
  // FCMPL / FCMPG tests
  // ========================================================================
  test("fcmpl_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // Pipeline order: a=TOS=value2, b=NOS=value1. CU compares value1 vs value2.
      assertBits(runFloat(dut, 2.0f, 1.0f, FCMPL), int32Bits(-1), "fcmpl: 1 < 2 -> -1")
      assertBits(runFloat(dut, 1.0f, 2.0f, FCMPL), int32Bits(1), "fcmpl: 2 > 1 -> +1")
      assertBits(runFloat(dut, 1.0f, 1.0f, FCMPL), int32Bits(0), "fcmpl: 1 == 1 -> 0")
      assertBits(runFloat(dut, 1.0f, -1.0f, FCMPL), int32Bits(-1), "fcmpl: -1 < 1 -> -1")
      assertBits(runFloat(dut, -1.0f, -2.0f, FCMPL), int32Bits(-1), "fcmpl: -2 < -1 -> -1")
    }
  }

  test("fcmpl_nan") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits(runFloat(dut, Float.NaN, 1.0f, FCMPL), int32Bits(-1), "fcmpl: NaN,1 -> -1")
      assertBits(runFloat(dut, 1.0f, Float.NaN, FCMPL), int32Bits(-1), "fcmpl: 1,NaN -> -1")
    }
  }

  test("fcmpg_nan") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits(runFloat(dut, Float.NaN, 1.0f, FCMPG), int32Bits(1), "fcmpg: NaN,1 -> +1")
      assertBits(runFloat(dut, 1.0f, Float.NaN, FCMPG), int32Bits(1), "fcmpg: 1,NaN -> +1")
    }
  }

  test("fcmp_zeros") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits(runFloat(dut, 0.0f, -0.0f, FCMPL), int32Bits(0), "fcmpl: +0 == -0")
      assertBits(runFloat(dut, -0.0f, 0.0f, FCMPG), int32Bits(0), "fcmpg: -0 == +0")
    }
  }

  // ========================================================================
  // Overflow / underflow
  // ========================================================================
  test("add_overflow_to_inf") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits(runFloat(dut, Float.MaxValue, Float.MaxValue, FADD), POS_INF, "MaxValue + MaxValue = Inf")
    }
  }

  test("mul_overflow_to_inf") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits(runFloat(dut, Float.MaxValue, 2.0f, FMUL), POS_INF, "MaxValue * 2 = Inf")
    }
  }

  test("mul_underflow_to_zero") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // Small normalized floats whose product underflows (below min normal ~1.18e-38)
      assertFloat(runFloat(dut, 1e-20f, 1e-20f, FMUL), 0.0f, "1e-20 * 1e-20 -> 0 (FTZ)")
    }
  }

  // ========================================================================
  // Config test: disabled ops don't block FSM
  // ========================================================================
  test("minimal_config_compiles") {
    val minConfig = FloatComputeUnitConfig(
      withAdd = false, withMul = false, withDiv = false,
      withI2F = false, withF2I = false, withFcmp = false
    )
    // Just verify it compiles and simulates without crashing
    simConfig.compile(FloatComputeUnit(minConfig)).doSim(seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      SimTimeout(1000)
      dut.io.a #= 0
      dut.io.b #= 0
      dut.io.opcode   #= 0
      dut.io.wr       #= false
      dut.clockDomain.waitSampling(10)
    }
  }
}
