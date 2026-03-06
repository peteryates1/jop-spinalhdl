package jop.core

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class LongComputeUnitTest extends AnyFunSuite {

  // ========================================================================
  // Helpers
  // ========================================================================
  def long64Bits(l: Long): BigInt = BigInt(l) & BigInt("FFFFFFFFFFFFFFFF", 16)
  def int32Bits(i: Int): BigInt = BigInt(i.toLong & 0xFFFFFFFFL)

  val LONG_MIN = long64Bits(Long.MinValue)
  val LONG_MAX = long64Bits(Long.MaxValue)

  // JVM bytecodes
  val LMUL  = 0x69
  val LDIV  = 0x6D
  val LREM  = 0x71
  val LSHL  = 0x79
  val LSHR  = 0x7B
  val LUSHR = 0x7D

  val fullConfig = LongComputeUnitConfig(
    withMul = true, withDiv = true, withRem = true, withShift = true
  )

  val simConfig = SimConfig
    .workspacePath("simWorkspace")

  def compileFull() = simConfig.compile(LongComputeUnit(fullConfig))

  def runOp(dut: LongComputeUnit, opa: BigInt, opb: BigInt, bytecode: Int)
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
    while (dut.io.busy.toBoolean && cycles < 500) {
      cd.waitSampling()
      cycles += 1
    }
    assert(cycles < 500, s"LCU timed out after 500 cycles (bytecode=0x${bytecode.toHexString})")
    (dut.io.resultHi.toBigInt << 32) | dut.io.resultLo.toBigInt
  }

  def initIo(dut: LongComputeUnit): Unit = {
    dut.io.a #= 0
    dut.io.b #= 0
    dut.io.c #= 0
    dut.io.d #= 0
    dut.io.opcode   #= 0
    dut.io.wr       #= false
  }

  def assertBits64(actual: BigInt, expected: BigInt, msg: String): Unit = {
    val a = actual & BigInt("FFFFFFFFFFFFFFFF", 16)
    val e = expected & BigInt("FFFFFFFFFFFFFFFF", 16)
    assert(a == e,
      f"$msg: expected 0x${e}%016X, got 0x${a}%016X")
  }

  // ========================================================================
  // LMUL tests
  // ========================================================================
  test("lmul_basic") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits64(runOp(dut, long64Bits(2L), long64Bits(3L), LMUL), long64Bits(6L), "2 * 3 = 6")
      assertBits64(runOp(dut, long64Bits(0L), long64Bits(12345L), LMUL), long64Bits(0L), "0 * x = 0")
      assertBits64(runOp(dut, long64Bits(-1L), long64Bits(-1L), LMUL), long64Bits(1L), "-1 * -1 = 1")
    }
  }

  test("lmul_large") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits64(runOp(dut, long64Bits(200000L), long64Bits(200000L), LMUL),
        long64Bits(200000L * 200000L), "200000 * 200000")
      // Overflow wrapping
      assertBits64(runOp(dut, long64Bits(0x100000000L), long64Bits(0x100000000L), LMUL),
        long64Bits(0L), "2^32 * 2^32 wraps to 0")
    }
  }

  test("lmul_minvalue") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits64(runOp(dut, LONG_MIN, long64Bits(1L), LMUL), LONG_MIN, "MIN_VALUE * 1 = MIN_VALUE")
      assertBits64(runOp(dut, LONG_MIN, long64Bits(0L), LMUL), long64Bits(0L), "MIN_VALUE * 0 = 0")
    }
  }

  // ========================================================================
  // LDIV tests
  // ========================================================================
  test("ldiv_basic") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      assertBits64(runOp(dut, long64Bits(7L), long64Bits(2L), LDIV), long64Bits(3L), "7 / 2 = 3")
      assertBits64(runOp(dut, long64Bits(-7L), long64Bits(2L), LDIV), long64Bits(-3L), "-7 / 2 = -3")
      assertBits64(runOp(dut, long64Bits(1L), long64Bits(1L), LDIV), long64Bits(1L), "1 / 1 = 1")
    }
  }

  test("ldiv_minvalue_neg1") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      assertBits64(runOp(dut, LONG_MIN, long64Bits(-1L), LDIV), LONG_MIN, "MIN_VALUE / -1 = MIN_VALUE")
    }
  }

  test("ldiv_by_zero") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      assertBits64(runOp(dut, long64Bits(42L), long64Bits(0L), LDIV), long64Bits(0L), "42 / 0 = 0")
    }
  }

  // ========================================================================
  // LREM tests
  // ========================================================================
  test("lrem_basic") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      assertBits64(runOp(dut, long64Bits(7L), long64Bits(2L), LREM), long64Bits(1L), "7 % 2 = 1")
      assertBits64(runOp(dut, long64Bits(-7L), long64Bits(2L), LREM), long64Bits(-1L), "-7 % 2 = -1")
    }
  }

  test("lrem_minvalue") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      assertBits64(runOp(dut, LONG_MIN, long64Bits(-1L), LREM), long64Bits(0L), "MIN_VALUE % -1 = 0")
    }
  }

  test("lrem_by_zero") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      assertBits64(runOp(dut, long64Bits(42L), long64Bits(0L), LREM), long64Bits(0L), "42 % 0 = 0")
    }
  }

  // ========================================================================
  // LSHL tests
  // ========================================================================
  test("lshl_basic") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits64(runOp(dut, long64Bits(1L), long64Bits(0L), LSHL), long64Bits(1L), "1 << 0 = 1")
      assertBits64(runOp(dut, long64Bits(1L), long64Bits(1L), LSHL), long64Bits(2L), "1 << 1 = 2")
      assertBits64(runOp(dut, long64Bits(1L), long64Bits(63L), LSHL), LONG_MIN, "1 << 63 = MIN_VALUE")
    }
  }

  test("lshl_mask") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // JVM masks shift amount to 6 bits: 64 & 0x3F = 0
      assertBits64(runOp(dut, long64Bits(1L), long64Bits(64L), LSHL), long64Bits(1L), "1 << 64 = 1 (masked to 0)")
    }
  }

  // ========================================================================
  // LSHR tests
  // ========================================================================
  test("lshr_basic") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits64(runOp(dut, long64Bits(-1L), long64Bits(0L), LSHR), long64Bits(-1L), "-1 >> 0 = -1")
      assertBits64(runOp(dut, long64Bits(4L), long64Bits(1L), LSHR), long64Bits(2L), "4 >> 1 = 2")
      // Sign extension: -1 >> 32 = -1
      assertBits64(runOp(dut, long64Bits(-1L), long64Bits(32L), LSHR), long64Bits(-1L), "-1 >> 32 = -1 (sign-extends)")
    }
  }

  // ========================================================================
  // LUSHR tests
  // ========================================================================
  test("lushr_basic") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits64(runOp(dut, long64Bits(-1L), long64Bits(32L), LUSHR),
        long64Bits(0x00000000FFFFFFFFL), "-1 >>> 32 = 0x00000000FFFFFFFF")
    }
  }

  test("lushr_mask") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // 64 & 0x3F = 0
      assertBits64(runOp(dut, long64Bits(-1L), long64Bits(64L), LUSHR),
        long64Bits(-1L), "-1 >>> 64 = -1 (masked to 0)")
    }
  }

  // ========================================================================
  // Minimal config: all flags false, sim doesn't crash
  // ========================================================================
  test("minimal_config_compiles") {
    val minConfig = LongComputeUnitConfig(
      withMul = false, withDiv = false, withRem = false, withShift = false
    )
    simConfig.compile(LongComputeUnit(minConfig)).doSim(seed = 42) { dut =>
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
