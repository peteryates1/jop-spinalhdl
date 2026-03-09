package jop.core

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class IntegerComputeUnitTest extends AnyFunSuite {

  // ========================================================================
  // Helpers
  // ========================================================================
  def int32Bits(i: Int): BigInt = BigInt(i.toLong & 0xFFFFFFFFL)

  val INT_MIN = int32Bits(Int.MinValue)  // 0x80000000

  // 4-bit op codes
  val IMUL      = 0
  val IDIV      = 1
  val IREM      = 2
  val IMUL_WIDE = 3

  val fullConfig = IntegerComputeUnitConfig(
    withMul = true, withDiv = true, withRem = true
  )

  val simConfig = SimConfig
    .workspacePath("simWorkspace")

  def compileFull() = simConfig.compile(IntegerComputeUnit(fullConfig))

  def runOp(dut: IntegerComputeUnit, opa: BigInt, opb: BigInt, op: Int)
           (implicit cd: ClockDomain): BigInt = {
    dut.io.operands(0) #= opa
    dut.io.operands(1) #= opb
    dut.io.op          #= op
    dut.io.start       #= true
    cd.waitSampling()
    dut.io.start       #= false
    cd.waitSampling()

    var cycles = 0
    while (dut.io.busy.toBoolean && cycles < 500) {
      cd.waitSampling()
      cycles += 1
    }
    assert(cycles < 500, s"ICU timed out after 500 cycles (op=$op)")
    (dut.io.resultHi.toBigInt << 32) | dut.io.resultLo.toBigInt
  }

  def initIo(dut: IntegerComputeUnit): Unit = {
    dut.io.operands(0) #= 0
    dut.io.operands(1) #= 0
    dut.io.op          #= 0
    dut.io.start       #= false
  }

  def assertBits32(actual: BigInt, expected: BigInt, msg: String): Unit = {
    val a = actual & BigInt("FFFFFFFF", 16)
    val e = expected & BigInt("FFFFFFFF", 16)
    assert(a == e,
      f"$msg: expected 0x${e}%08X, got 0x${a}%08X")
  }

  def assertBits64(actual: BigInt, expected: BigInt, msg: String): Unit = {
    val a = actual & BigInt("FFFFFFFFFFFFFFFF", 16)
    val e = expected & BigInt("FFFFFFFFFFFFFFFF", 16)
    assert(a == e,
      f"$msg: expected 0x${e}%016X, got 0x${a}%016X")
  }

  // ========================================================================
  // IMUL tests
  // ========================================================================
  test("imul_basic") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits32(runOp(dut, int32Bits(2), int32Bits(3), IMUL), int32Bits(6), "2 * 3 = 6")
      assertBits32(runOp(dut, int32Bits(0), int32Bits(12345), IMUL), int32Bits(0), "0 * x = 0")
      assertBits32(runOp(dut, int32Bits(-1), int32Bits(-1), IMUL), int32Bits(1), "-1 * -1 = 1")
    }
  }

  test("imul_large") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // 50000 * 50000 = 2500000000 = 0x9502F900 (overflows to negative in signed 32-bit)
      assertBits32(runOp(dut, int32Bits(50000), int32Bits(50000), IMUL),
        int32Bits(50000 * 50000), "50000 * 50000 (overflow wrapping)")
    }
  }

  test("imul_minvalue") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits32(runOp(dut, INT_MIN, int32Bits(1), IMUL), INT_MIN, "MIN_VALUE * 1 = MIN_VALUE")
      assertBits32(runOp(dut, INT_MIN, int32Bits(0), IMUL), int32Bits(0), "MIN_VALUE * 0 = 0")
    }
  }

  // ========================================================================
  // IDIV tests
  // ========================================================================
  test("idiv_basic") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      // runOp(operands(0)=TOS=divisor, operands(1)=NOS=dividend): idiv = NOS / TOS
      assertBits32(runOp(dut, int32Bits(2), int32Bits(7), IDIV), int32Bits(3), "7 / 2 = 3")
      assertBits32(runOp(dut, int32Bits(2), int32Bits(-7), IDIV), int32Bits(-3), "-7 / 2 = -3")
      assertBits32(runOp(dut, int32Bits(1), int32Bits(1), IDIV), int32Bits(1), "1 / 1 = 1")
    }
  }

  test("idiv_minvalue_neg1") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      // operands(0)=TOS=divisor=-1, operands(1)=NOS=dividend=MIN_VALUE
      assertBits32(runOp(dut, int32Bits(-1), INT_MIN, IDIV), INT_MIN, "MIN_VALUE / -1 = MIN_VALUE")
    }
  }

  test("idiv_by_zero") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      // operands(0)=TOS=divisor=0, operands(1)=NOS=dividend=42
      assertBits32(runOp(dut, int32Bits(0), int32Bits(42), IDIV), int32Bits(0), "42 / 0 = 0")
    }
  }

  // ========================================================================
  // IREM tests
  // ========================================================================
  test("irem_basic") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      // runOp(operands(0)=TOS=divisor, operands(1)=NOS=dividend): irem = NOS % TOS
      assertBits32(runOp(dut, int32Bits(2), int32Bits(7), IREM), int32Bits(1), "7 % 2 = 1")
      assertBits32(runOp(dut, int32Bits(2), int32Bits(-7), IREM), int32Bits(-1), "-7 % 2 = -1")
    }
  }

  test("irem_minvalue") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      // operands(0)=TOS=divisor=-1, operands(1)=NOS=dividend=MIN_VALUE
      assertBits32(runOp(dut, int32Bits(-1), INT_MIN, IREM), int32Bits(0), "MIN_VALUE % -1 = 0")
    }
  }

  test("irem_by_zero") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      // operands(0)=TOS=divisor=0, operands(1)=NOS=dividend=42
      assertBits32(runOp(dut, int32Bits(0), int32Bits(42), IREM), int32Bits(0), "42 % 0 = 0")
    }
  }

  // ========================================================================
  // IMUL_WIDE tests (op 3: 32×32→64 unsigned)
  // ========================================================================
  test("imul_wide_basic") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // 2 * 3 = 6 (fits in 32 bits, hi = 0)
      assertBits64(runOp(dut, int32Bits(2), int32Bits(3), IMUL_WIDE),
        BigInt(6), "2 * 3 = 6")
      // 0 * anything = 0
      assertBits64(runOp(dut, int32Bits(0), int32Bits(12345), IMUL_WIDE),
        BigInt(0), "0 * x = 0")
      // 1 * 1 = 1
      assertBits64(runOp(dut, int32Bits(1), int32Bits(1), IMUL_WIDE),
        BigInt(1), "1 * 1 = 1")
    }
  }

  test("imul_wide_large") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // 0x80000000 * 2 = 0x1_00000000 (needs hi word)
      assertBits64(runOp(dut, int32Bits(0x80000000.toInt), int32Bits(2), IMUL_WIDE),
        BigInt("100000000", 16), "0x80000000 * 2 = 0x100000000")

      // 0xFFFFFFFF * 0xFFFFFFFF = 0xFFFFFFFE00000001 (max unsigned 32-bit product)
      assertBits64(runOp(dut, int32Bits(-1), int32Bits(-1), IMUL_WIDE),
        BigInt("FFFFFFFE00000001", 16), "0xFFFFFFFF * 0xFFFFFFFF")

      // 50000 * 50000 = 2500000000 = 0x9502F900
      assertBits64(runOp(dut, int32Bits(50000), int32Bits(50000), IMUL_WIDE),
        BigInt(50000L * 50000L), "50000 * 50000")
    }
  }

  test("imul_wide_resultCount") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // imul_wide: resultCount should be 2 after completion
      val r1 = runOp(dut, int32Bits(3), int32Bits(4), IMUL_WIDE)
      assert(dut.io.resultCount.toInt == 2,
        s"imul_wide resultCount should be 2 but got ${dut.io.resultCount.toInt}")

      // imul: resultCount should be 1 after completion
      val r2 = runOp(dut, int32Bits(3), int32Bits(4), IMUL)
      assert(dut.io.resultCount.toInt == 1,
        s"imul resultCount should be 1 but got ${dut.io.resultCount.toInt}")
    }
  }

  // ========================================================================
  // Minimal config: all flags false, sim doesn't crash
  // ========================================================================
  test("minimal_config_compiles") {
    val minConfig = IntegerComputeUnitConfig(
      withMul = false, withDiv = false, withRem = false
    )
    simConfig.compile(IntegerComputeUnit(minConfig)).doSim(seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      SimTimeout(1000)
      dut.io.operands(0) #= 0
      dut.io.operands(1) #= 0
      dut.io.op          #= 0
      dut.io.start       #= false
      dut.clockDomain.waitSampling(10)
    }
  }
}
