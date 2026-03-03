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

  // JVM bytecodes
  val IMUL = 0x68
  val IDIV = 0x6C
  val IREM = 0x70

  val fullConfig = IntegerComputeUnitConfig(
    withMul = true, withDiv = true, withRem = true
  )

  val simConfig = SimConfig
    .workspacePath("simWorkspace")

  def compileFull() = simConfig.compile(IntegerComputeUnit(fullConfig))

  def runOp(dut: IntegerComputeUnit, opa: BigInt, opb: BigInt, bytecode: Int)
           (implicit cd: ClockDomain): BigInt = {
    dut.io.operand0 #= opa
    dut.io.operand1 #= opb
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
    assert(cycles < 500, s"ICU timed out after 500 cycles (bytecode=0x${bytecode.toHexString})")
    dut.io.result.toBigInt & BigInt("FFFFFFFFFFFFFFFF", 16)
  }

  def initIo(dut: IntegerComputeUnit): Unit = {
    dut.io.operand0 #= 0
    dut.io.operand1 #= 0
    dut.io.opcode   #= 0
    dut.io.wr       #= false
  }

  def assertBits32(actual: BigInt, expected: BigInt, msg: String): Unit = {
    val a = actual & BigInt("FFFFFFFF", 16)
    val e = expected & BigInt("FFFFFFFF", 16)
    assert(a == e,
      f"$msg: expected 0x${e}%08X, got 0x${a}%08X")
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

      assertBits32(runOp(dut, int32Bits(7), int32Bits(2), IDIV), int32Bits(3), "7 / 2 = 3")
      assertBits32(runOp(dut, int32Bits(-7), int32Bits(2), IDIV), int32Bits(-3), "-7 / 2 = -3")
      assertBits32(runOp(dut, int32Bits(1), int32Bits(1), IDIV), int32Bits(1), "1 / 1 = 1")
    }
  }

  test("idiv_minvalue_neg1") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      assertBits32(runOp(dut, INT_MIN, int32Bits(-1), IDIV), INT_MIN, "MIN_VALUE / -1 = MIN_VALUE")
    }
  }

  test("idiv_by_zero") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      assertBits32(runOp(dut, int32Bits(42), int32Bits(0), IDIV), int32Bits(0), "42 / 0 = 0")
    }
  }

  // ========================================================================
  // IREM tests
  // ========================================================================
  test("irem_basic") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      assertBits32(runOp(dut, int32Bits(7), int32Bits(2), IREM), int32Bits(1), "7 % 2 = 1")
      assertBits32(runOp(dut, int32Bits(-7), int32Bits(2), IREM), int32Bits(-1), "-7 % 2 = -1")
    }
  }

  test("irem_minvalue") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      assertBits32(runOp(dut, INT_MIN, int32Bits(-1), IREM), int32Bits(0), "MIN_VALUE % -1 = 0")
    }
  }

  test("irem_by_zero") {
    compileFull().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10); SimTimeout(50000); initIo(dut); cd.waitSampling(5)

      assertBits32(runOp(dut, int32Bits(42), int32Bits(0), IREM), int32Bits(0), "42 % 0 = 0")
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
      dut.io.operand0 #= 0
      dut.io.operand1 #= 0
      dut.io.opcode   #= 0
      dut.io.wr       #= false
      dut.clockDomain.waitSampling(10)
    }
  }
}
