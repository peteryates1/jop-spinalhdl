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

  // 4-bit op codes
  val LMUL  = 2
  val LDIV  = 3
  val LREM  = 4
  val LSHL  = 6
  val LSHR  = 7
  val LUSHR = 8

  val fullConfig = LongComputeUnitConfig(
    withMul = true, withDiv = true, withRem = true, withShift = true
  )

  val simConfig = SimConfig
    .workspacePath("simWorkspace")

  def compileFull() = simConfig.compile(LongComputeUnit(fullConfig))

  def runOp(dut: LongComputeUnit, opa: BigInt, opb: BigInt, op: Int)
           (implicit cd: ClockDomain): BigInt = {
    dut.io.operands(2) #= opa & BigInt("FFFFFFFF", 16)         // opa_lo (was c)
    dut.io.operands(3) #= (opa >> 32) & BigInt("FFFFFFFF", 16) // opa_hi (was d)
    dut.io.operands(0) #= opb & BigInt("FFFFFFFF", 16)         // opb_lo (was a)
    dut.io.operands(1) #= (opb >> 32) & BigInt("FFFFFFFF", 16) // opb_hi (was b)
    dut.io.op      #= op
    dut.io.start   #= true
    cd.waitSampling()
    dut.io.start   #= false
    cd.waitSampling()

    var cycles = 0
    while (dut.io.busy.toBoolean && cycles < 500) {
      cd.waitSampling()
      cycles += 1
    }
    assert(cycles < 500, s"LCU timed out after 500 cycles (op=$op)")
    (dut.io.resultHi.toBigInt << 32) | dut.io.resultLo.toBigInt
  }

  /** Run shift op: value (64-bit) shifted by amount (int).
    * Shift operand layout matches microcode pop order:
    * operands(0)=amount, operands(1)=val_lo, operands(2)=val_hi */
  def runShift(dut: LongComputeUnit, value: BigInt, amount: BigInt, op: Int)
              (implicit cd: ClockDomain): BigInt = {
    dut.io.operands(0) #= amount & BigInt("FFFFFFFF", 16)          // shift amount
    dut.io.operands(1) #= value & BigInt("FFFFFFFF", 16)           // val_lo
    dut.io.operands(2) #= (value >> 32) & BigInt("FFFFFFFF", 16)   // val_hi
    dut.io.operands(3) #= 0
    dut.io.op      #= op
    dut.io.start   #= true
    cd.waitSampling()
    dut.io.start   #= false
    cd.waitSampling()

    var cycles = 0
    while (dut.io.busy.toBoolean && cycles < 500) {
      cd.waitSampling()
      cycles += 1
    }
    assert(cycles < 500, s"LCU timed out after 500 cycles (op=$op)")
    (dut.io.resultHi.toBigInt << 32) | dut.io.resultLo.toBigInt
  }

  def initIo(dut: LongComputeUnit): Unit = {
    dut.io.operands(0) #= 0
    dut.io.operands(1) #= 0
    dut.io.operands(2) #= 0
    dut.io.operands(3) #= 0
    dut.io.op      #= 0
    dut.io.start   #= false
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

      assertBits64(runShift(dut, long64Bits(1L), long64Bits(0L), LSHL), long64Bits(1L), "1 << 0 = 1")
      assertBits64(runShift(dut, long64Bits(1L), long64Bits(1L), LSHL), long64Bits(2L), "1 << 1 = 2")
      assertBits64(runShift(dut, long64Bits(1L), long64Bits(63L), LSHL), LONG_MIN, "1 << 63 = MIN_VALUE")
    }
  }

  test("lshl_mask") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // JVM masks shift amount to 6 bits: 64 & 0x3F = 0
      assertBits64(runShift(dut, long64Bits(1L), long64Bits(64L), LSHL), long64Bits(1L), "1 << 64 = 1 (masked to 0)")
    }
  }

  // ========================================================================
  // LSHR tests
  // ========================================================================
  test("lshr_basic") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits64(runShift(dut, long64Bits(-1L), long64Bits(0L), LSHR), long64Bits(-1L), "-1 >> 0 = -1")
      assertBits64(runShift(dut, long64Bits(4L), long64Bits(1L), LSHR), long64Bits(2L), "4 >> 1 = 2")
      // Sign extension: -1 >> 32 = -1
      assertBits64(runShift(dut, long64Bits(-1L), long64Bits(32L), LSHR), long64Bits(-1L), "-1 >> 32 = -1 (sign-extends)")
    }
  }

  // ========================================================================
  // LUSHR tests
  // ========================================================================
  test("lushr_basic") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      assertBits64(runShift(dut, long64Bits(-1L), long64Bits(32L), LUSHR),
        long64Bits(0x00000000FFFFFFFFL), "-1 >>> 32 = 0x00000000FFFFFFFF")
    }
  }

  test("lushr_mask") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(10000); initIo(dut); cd.waitSampling(5)

      // 64 & 0x3F = 0
      assertBits64(runShift(dut, long64Bits(-1L), long64Bits(64L), LUSHR),
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
      dut.io.operands(0) #= 0
      dut.io.operands(1) #= 0
      dut.io.operands(2) #= 0
      dut.io.operands(3) #= 0
      dut.io.op      #= 0
      dut.io.start   #= false
      dut.clockDomain.waitSampling(10)
    }
  }
}
