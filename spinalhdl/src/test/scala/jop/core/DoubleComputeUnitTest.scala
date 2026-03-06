package jop.core

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class DoubleComputeUnitTest extends AnyFunSuite {

  // ========================================================================
  // Helpers
  // ========================================================================
  def doubleBits(d: Double): BigInt = {
    val raw = java.lang.Double.doubleToRawLongBits(d)
    BigInt(raw) & BigInt("FFFFFFFFFFFFFFFF", 16)
  }
  def bitsDouble(b: BigInt): Double =
    java.lang.Double.longBitsToDouble((b & BigInt("FFFFFFFFFFFFFFFF", 16)).toLong)
  def floatBits(f: Float): BigInt =
    BigInt(java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL)
  def int32Bits(i: Int): BigInt = BigInt(i.toLong & 0xFFFFFFFFL)
  def long64Bits(l: Long): BigInt = BigInt(l) & BigInt("FFFFFFFFFFFFFFFF", 16)

  val CANONICAL_NAN = BigInt("7FF8000000000000", 16)
  val POS_INF       = BigInt("7FF0000000000000", 16)
  val NEG_INF       = BigInt("FFF0000000000000", 16)
  val POS_ZERO      = BigInt(0)

  // Single-precision constants for D2F results
  val SP_CANONICAL_NAN = BigInt("7FC00000", 16)
  val SP_POS_INF       = BigInt("7F800000", 16)

  // JVM bytecodes
  val DADD  = 0x63
  val DSUB  = 0x67
  val DMUL  = 0x6B
  val DDIV  = 0x6F
  val I2D   = 0x87
  val D2I   = 0x8E
  val L2D   = 0x8A
  val D2L   = 0x8F
  val F2D   = 0x8D
  val D2F   = 0x90
  val DCMPL = 0x97
  val DCMPG = 0x98

  val fullConfig = DoubleComputeUnitConfig(
    withAdd = true, withMul = true, withDiv = true,
    withI2D = true, withD2I = true, withL2D = true, withD2L = true,
    withF2D = true, withD2F = true, withDcmp = true
  )

  val simConfig = SimConfig
    .workspacePath("simWorkspace")

  def compileFull() = simConfig.compile(DoubleComputeUnit(fullConfig))

  def runOp(dut: DoubleComputeUnit, opa: BigInt, opb: BigInt, bytecode: Int)
           (implicit cd: ClockDomain): BigInt = {
    dut.io.c #= opa & BigInt("FFFFFFFF", 16)
    dut.io.d #= (opa >> 32) & BigInt("FFFFFFFF", 16)
    dut.io.a #= opb & BigInt("FFFFFFFF", 16)
    dut.io.b #= (opb >> 32) & BigInt("FFFFFFFF", 16)
    dut.io.opcode   #= bytecode
    dut.io.wr       #= true
    cd.waitSampling()
    dut.io.wr       #= false
    cd.waitSampling()

    var cycles = 0
    while (dut.io.busy.toBoolean && cycles < 200) {
      cd.waitSampling()
      cycles += 1
    }
    assert(cycles < 200, s"DPU timed out after 200 cycles (bytecode=0x${bytecode.toHexString})")
    (dut.io.resultHi.toBigInt << 32) | dut.io.resultLo.toBigInt
  }

  def runDouble(dut: DoubleComputeUnit, a: Double, b: Double, bytecode: Int)
               (implicit cd: ClockDomain): BigInt =
    runOp(dut, doubleBits(a), doubleBits(b), bytecode)

  def initIo(dut: DoubleComputeUnit): Unit = {
    dut.io.a #= 0
    dut.io.b #= 0
    dut.io.c #= 0
    dut.io.d #= 0
    dut.io.opcode   #= 0
    dut.io.wr       #= false
  }

  def assertDouble(actual: BigInt, expected: Double, msg: String): Unit = {
    val expBits = doubleBits(expected)
    assert(actual == expBits,
      f"$msg: expected $expected%.15g (0x${expBits}%016X), got ${bitsDouble(actual)}%.15g (0x${actual}%016X)")
  }

  def assertBits64(actual: BigInt, expected: BigInt, msg: String): Unit = {
    assert(actual == expected,
      f"$msg: expected 0x${expected}%016X, got 0x${actual}%016X")
  }

  def assertBits32(actual: BigInt, expected: BigInt, msg: String): Unit = {
    val a32 = actual & BigInt("FFFFFFFF", 16)
    val e32 = expected & BigInt("FFFFFFFF", 16)
    assert(a32 == e32,
      f"$msg: expected 0x${e32}%08X, got 0x${a32}%08X")
  }

  def assertNaN64(actual: BigInt, msg: String): Unit = {
    val exp = (actual >> 52) & BigInt("7FF", 16)
    val frac = actual & BigInt("FFFFFFFFFFFFF", 16)
    assert(exp == BigInt("7FF", 16) && frac != 0, f"$msg: expected NaN, got 0x${actual}%016X")
  }

  def assertNaN32(actual: BigInt, msg: String): Unit = {
    val low32 = actual & BigInt("FFFFFFFF", 16)
    val exp = (low32 >> 23) & 0xFF
    val frac = low32 & BigInt("7FFFFF", 16)
    assert(exp == 0xFF && frac != 0, f"$msg: expected SP NaN, got 0x${low32}%08X")
  }

  // ========================================================================
  // ADD tests
  // ========================================================================
  test("add_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertDouble(runDouble(dut, 1.5, 2.5, DADD), 4.0, "1.5 + 2.5")
      assertDouble(runDouble(dut, 100.0, 0.5, DADD), 100.5, "100.0 + 0.5")
      assertDouble(runDouble(dut, -1.5, -2.5, DADD), -4.0, "-1.5 + -2.5")
    }
  }

  test("add_cancellation") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      val r = runDouble(dut, 1.0, -1.0, DADD)
      assertBits64(r, POS_ZERO, "1.0 + (-1.0) = +0")
    }
  }

  test("add_inf_handling") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits64(runDouble(dut, Double.PositiveInfinity, 1.0, DADD), POS_INF, "Inf + 1 = Inf")
      assertBits64(runDouble(dut, Double.NegativeInfinity, 1.0, DADD), NEG_INF, "-Inf + 1 = -Inf")
      assertNaN64(runDouble(dut, Double.PositiveInfinity, Double.NegativeInfinity, DADD), "Inf + (-Inf) = NaN")
    }
  }

  test("add_nan_propagation") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertNaN64(runDouble(dut, Double.NaN, 1.0, DADD), "NaN + 1 = NaN")
      assertNaN64(runDouble(dut, 1.0, Double.NaN, DADD), "1 + NaN = NaN")
    }
  }

  test("add_zero_handling") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertDouble(runDouble(dut, 0.0, 5.0, DADD), 5.0, "0 + 5 = 5")
      assertDouble(runDouble(dut, 5.0, 0.0, DADD), 5.0, "5 + 0 = 5")
    }
  }

  // ========================================================================
  // SUB tests
  // ========================================================================
  test("sub_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertDouble(runDouble(dut, 5.0, 3.0, DSUB), 2.0, "5.0 - 3.0")
      assertDouble(runDouble(dut, 3.0, 5.0, DSUB), -2.0, "3.0 - 5.0")
    }
  }

  test("sub_cancellation") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      val r = runDouble(dut, 1.0, 1.0, DSUB)
      assertBits64(r, POS_ZERO, "1.0 - 1.0 = +0")
    }
  }

  // ========================================================================
  // MUL tests
  // ========================================================================
  test("mul_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertDouble(runDouble(dut, 3.0, 4.0, DMUL), 12.0, "3.0 * 4.0")
      assertDouble(runDouble(dut, -2.0, 3.0, DMUL), -6.0, "-2.0 * 3.0")
      assertDouble(runDouble(dut, 0.5, 0.5, DMUL), 0.25, "0.5 * 0.5")
    }
  }

  test("mul_special_values") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertNaN64(runDouble(dut, Double.PositiveInfinity, 0.0, DMUL), "Inf * 0 = NaN")
      assertBits64(runDouble(dut, Double.PositiveInfinity, 2.0, DMUL), POS_INF, "Inf * 2 = Inf")
      assertBits64(runDouble(dut, 0.0, 0.0, DMUL), POS_ZERO, "0 * 0 = +0")
      assertNaN64(runDouble(dut, Double.NaN, 1.0, DMUL), "NaN * 1 = NaN")
    }
  }

  test("mul_sign") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertDouble(runDouble(dut, -3.0, -4.0, DMUL), 12.0, "(-3) * (-4) = +12")
      val r = runDouble(dut, -0.0, 1.0, DMUL)
      assert((r >> 63) == 1, s"(-0) * 1 should be negative zero, got 0x${r.toString(16)}")
    }
  }

  test("mul_overflow_to_inf") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits64(runDouble(dut, Double.MaxValue, 2.0, DMUL), POS_INF, "MaxValue * 2 = Inf")
    }
  }

  test("mul_underflow_to_zero") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertDouble(runDouble(dut, 1e-200, 1e-200, DMUL), 0.0, "1e-200 * 1e-200 -> 0 (FTZ)")
    }
  }

  // ========================================================================
  // DIV tests
  // ========================================================================
  test("div_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(20000); initIo(dut); cd.waitSampling(5)

      assertDouble(runDouble(dut, 7.0, 2.0, DDIV), 3.5, "7.0 / 2.0")
      assertDouble(runDouble(dut, 12.0, 4.0, DDIV), 3.0, "12.0 / 4.0")
    }
  }

  test("div_special_values") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(20000); initIo(dut); cd.waitSampling(5)

      assertBits64(runDouble(dut, 1.0, 0.0, DDIV), POS_INF, "1 / 0 = +Inf")
      assertBits64(runDouble(dut, -1.0, 0.0, DDIV), NEG_INF, "-1 / 0 = -Inf")
      assertNaN64(runDouble(dut, 0.0, 0.0, DDIV), "0 / 0 = NaN")
      assertNaN64(runDouble(dut, Double.PositiveInfinity, Double.PositiveInfinity, DDIV), "Inf / Inf = NaN")
      assertBits64(runDouble(dut, 1.0, Double.PositiveInfinity, DDIV), POS_ZERO, "1 / Inf = +0")
    }
  }

  // ========================================================================
  // I2D tests
  // ========================================================================
  test("i2d_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertDouble(runOp(dut, int32Bits(1), BigInt(0), I2D), 1.0, "i2d(1)")
      assertDouble(runOp(dut, int32Bits(0), BigInt(0), I2D), 0.0, "i2d(0)")
      assertDouble(runOp(dut, int32Bits(-1), BigInt(0), I2D), -1.0, "i2d(-1)")
      assertDouble(runOp(dut, int32Bits(42), BigInt(0), I2D), 42.0, "i2d(42)")
      assertDouble(runOp(dut, int32Bits(256), BigInt(0), I2D), 256.0, "i2d(256)")
    }
  }

  test("i2d_large") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // INT_MAX: 2147483647 -> exact in double
      assertDouble(runOp(dut, int32Bits(0x7FFFFFFF), BigInt(0), I2D), 2147483647.0, "i2d(INT_MAX)")
    }
  }

  // ========================================================================
  // D2I tests
  // ========================================================================
  test("d2i_truncate") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits32(runDouble(dut, 3.7, 0, D2I), int32Bits(3), "d2i(3.7)")
      assertBits32(runDouble(dut, -2.3, 0, D2I), int32Bits(-2), "d2i(-2.3)")
      assertBits32(runDouble(dut, 0.5, 0, D2I), int32Bits(0), "d2i(0.5)")
      assertBits32(runDouble(dut, -0.9, 0, D2I), int32Bits(0), "d2i(-0.9)")
      assertBits32(runDouble(dut, 100.0, 0, D2I), int32Bits(100), "d2i(100.0)")
    }
  }

  test("d2i_special") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits32(runDouble(dut, Double.NaN, 0, D2I), BigInt(0), "d2i(NaN) = 0")
      assertBits32(runDouble(dut, Double.PositiveInfinity, 0, D2I), int32Bits(0x7FFFFFFF), "d2i(+Inf) = INT_MAX")
      assertBits32(runDouble(dut, Double.NegativeInfinity, 0, D2I), int32Bits(0x80000000.toInt), "d2i(-Inf) = INT_MIN")
      assertBits32(runDouble(dut, 0.0, 0, D2I), BigInt(0), "d2i(0.0) = 0")
    }
  }

  // ========================================================================
  // L2D tests
  // ========================================================================
  test("l2d_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertDouble(runOp(dut, long64Bits(1L), BigInt(0), L2D), 1.0, "l2d(1)")
      assertDouble(runOp(dut, long64Bits(0L), BigInt(0), L2D), 0.0, "l2d(0)")
      assertDouble(runOp(dut, long64Bits(-1L), BigInt(0), L2D), -1.0, "l2d(-1)")
      assertDouble(runOp(dut, long64Bits(1000000L), BigInt(0), L2D), 1000000.0, "l2d(1000000)")
    }
  }

  test("l2d_large") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // LONG_MAX: 9223372036854775807 -> rounds in double
      val r = runOp(dut, long64Bits(Long.MaxValue), BigInt(0), L2D)
      assertDouble(r, Long.MaxValue.toDouble, "l2d(LONG_MAX)")
    }
  }

  // ========================================================================
  // D2L tests
  // ========================================================================
  test("d2l_truncate") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits64(runDouble(dut, 3.7, 0, D2L), long64Bits(3L), "d2l(3.7)")
      assertBits64(runDouble(dut, -2.3, 0, D2L), long64Bits(-2L), "d2l(-2.3)")
      assertBits64(runDouble(dut, 0.5, 0, D2L), long64Bits(0L), "d2l(0.5)")
    }
  }

  test("d2l_special") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits64(runDouble(dut, Double.NaN, 0, D2L), BigInt(0), "d2l(NaN) = 0")
      assertBits64(runDouble(dut, Double.PositiveInfinity, 0, D2L), long64Bits(Long.MaxValue), "d2l(+Inf) = LONG_MAX")
      assertBits64(runDouble(dut, Double.NegativeInfinity, 0, D2L), long64Bits(Long.MinValue), "d2l(-Inf) = LONG_MIN")
    }
  }

  // ========================================================================
  // F2D tests
  // ========================================================================
  test("f2d_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertDouble(runOp(dut, floatBits(1.5f), BigInt(0), F2D), 1.5, "f2d(1.5f)")
      assertDouble(runOp(dut, floatBits(-3.25f), BigInt(0), F2D), -3.25, "f2d(-3.25f)")
      assertDouble(runOp(dut, floatBits(42.0f), BigInt(0), F2D), 42.0, "f2d(42.0f)")
    }
  }

  test("f2d_special") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits64(runOp(dut, floatBits(0.0f), BigInt(0), F2D), POS_ZERO, "f2d(0) = 0")
      assertBits64(runOp(dut, floatBits(Float.PositiveInfinity), BigInt(0), F2D), POS_INF, "f2d(+Inf)")
      assertBits64(runOp(dut, floatBits(Float.NegativeInfinity), BigInt(0), F2D), NEG_INF, "f2d(-Inf)")
      assertNaN64(runOp(dut, floatBits(Float.NaN), BigInt(0), F2D), "f2d(NaN)")
    }
  }

  // ========================================================================
  // D2F tests
  // ========================================================================
  test("d2f_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits32(runDouble(dut, 1.5, 0, D2F), floatBits(1.5f), "d2f(1.5)")
      assertBits32(runDouble(dut, -3.25, 0, D2F), floatBits(-3.25f), "d2f(-3.25)")
      assertBits32(runDouble(dut, 42.0, 0, D2F), floatBits(42.0f), "d2f(42.0)")
    }
  }

  test("d2f_special") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits32(runDouble(dut, Double.PositiveInfinity, 0, D2F), SP_POS_INF, "d2f(+Inf)")
      assertNaN32(runDouble(dut, Double.NaN, 0, D2F), "d2f(NaN)")
      assertBits32(runDouble(dut, 0.0, 0, D2F), BigInt(0), "d2f(0)")
    }
  }

  test("d2f_overflow") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // Double value too large for float -> Inf
      assertBits32(runDouble(dut, 1e308, 0, D2F), SP_POS_INF, "d2f(1e308) = +Inf_f")
    }
  }

  test("d2f_underflow") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // Double value too small for float -> zero
      assertBits32(runDouble(dut, 1e-200, 0, D2F), BigInt(0), "d2f(1e-200) = 0")
    }
  }

  // ========================================================================
  // DCMPL / DCMPG tests
  // ========================================================================
  test("dcmpl_normal") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits32(runDouble(dut, 1.0, 2.0, DCMPL), int32Bits(-1), "dcmpl: 1 < 2 -> -1")
      assertBits32(runDouble(dut, 2.0, 1.0, DCMPL), int32Bits(1), "dcmpl: 2 > 1 -> +1")
      assertBits32(runDouble(dut, 1.0, 1.0, DCMPL), int32Bits(0), "dcmpl: 1 == 1 -> 0")
      assertBits32(runDouble(dut, -1.0, 1.0, DCMPL), int32Bits(-1), "dcmpl: -1 < 1 -> -1")
      assertBits32(runDouble(dut, -2.0, -1.0, DCMPL), int32Bits(-1), "dcmpl: -2 < -1 -> -1")
    }
  }

  test("dcmpl_nan") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits32(runDouble(dut, Double.NaN, 1.0, DCMPL), int32Bits(-1), "dcmpl: NaN,1 -> -1")
      assertBits32(runDouble(dut, 1.0, Double.NaN, DCMPL), int32Bits(-1), "dcmpl: 1,NaN -> -1")
    }
  }

  test("dcmpg_nan") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits32(runDouble(dut, Double.NaN, 1.0, DCMPG), int32Bits(1), "dcmpg: NaN,1 -> +1")
      assertBits32(runDouble(dut, 1.0, Double.NaN, DCMPG), int32Bits(1), "dcmpg: 1,NaN -> +1")
    }
  }

  test("dcmp_zeros") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits32(runDouble(dut, 0.0, -0.0, DCMPL), int32Bits(0), "dcmpl: +0 == -0")
      assertBits32(runDouble(dut, -0.0, 0.0, DCMPG), int32Bits(0), "dcmpg: -0 == +0")
    }
  }

  // ========================================================================
  // Overflow / underflow
  // ========================================================================
  test("add_overflow_to_inf") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits64(runDouble(dut, Double.MaxValue, Double.MaxValue, DADD), POS_INF, "MaxValue + MaxValue = Inf")
    }
  }

  // ========================================================================
  // Config test: disabled ops don't block FSM
  // ========================================================================
  test("minimal_config_compiles") {
    val minConfig = DoubleComputeUnitConfig(
      withAdd = false, withMul = false, withDiv = false,
      withI2D = false, withD2I = false, withL2D = false, withD2L = false,
      withF2D = false, withD2F = false, withDcmp = false
    )
    simConfig.compile(DoubleComputeUnit(minConfig)).doSim(seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      SimTimeout(1000)
      dut.io.a #= 0
      dut.io.b #= 0
      dut.io.c #= 0
      dut.io.d #= 0
      dut.io.opcode   #= 0
      dut.io.wr       #= false
      dut.clockDomain.waitSampling(10)
    }
  }
}
