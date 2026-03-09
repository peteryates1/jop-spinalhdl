package jop.core

import spinal.core._
import spinal.core.sim._

/**
  * Interactive simulation for LongComputeUnit with trace output.
  * Run: sbt "Test / runMain jop.core.LongComputeUnitSim"
  */
object LongComputeUnitSim extends App {

  val fullConfig = LongComputeUnitConfig(
    withMul   = true,
    withDiv   = true,
    withRem   = true,
    withShift = true
  )

  // 4-bit op codes
  val LMUL  = 2
  val LDIV  = 3
  val LREM  = 4
  val LSHL  = 6
  val LSHR  = 7
  val LUSHR = 8

  SimConfig
    .withWave
    .workspacePath("simWorkspace")
    .compile(LongComputeUnit(fullConfig))
    .doSim { dut =>

    dut.clockDomain.forkStimulus(10)

    def long64Bits(l: Long): BigInt = BigInt(l) & BigInt("FFFFFFFFFFFFFFFF", 16)

    val opNames = Map(
      2 -> "lmul",  3 -> "ldiv",  4 -> "lrem",
      6 -> "lshl",  7 -> "lshr",  8 -> "lushr"
    )

    def runOp(opa: BigInt, opb: BigInt, op: Int, desc: String): BigInt = {
      val name = opNames.getOrElse(op, s"op=$op")
      println(f"--- $desc%-55s  op=$name  opa=0x${opa}%016X  opb=0x${opb}%016X ---")

      dut.io.operands(2) #= opa & BigInt("FFFFFFFF", 16)         // opa_lo (was c)
      dut.io.operands(3) #= (opa >> 32) & BigInt("FFFFFFFF", 16) // opa_hi (was d)
      dut.io.operands(0) #= opb & BigInt("FFFFFFFF", 16)         // opb_lo (was a)
      dut.io.operands(1) #= (opb >> 32) & BigInt("FFFFFFFF", 16) // opb_hi (was b)
      dut.io.op      #= op
      dut.io.start   #= true
      dut.clockDomain.waitSampling()
      dut.io.start   #= false
      dut.clockDomain.waitSampling()

      var cycles = 0
      while (dut.io.busy.toBoolean && cycles < 500) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      val result = (dut.io.resultHi.toBigInt << 32) | dut.io.resultLo.toBigInt
      val resultSigned = if (result > BigInt("7FFFFFFFFFFFFFFF", 16))
        result - BigInt("10000000000000000", 16) else result
      println(f"  result=0x${result}%016X (signed=$resultSigned)  cycles=$cycles")
      dut.clockDomain.waitSampling(2)
      result
    }

    // Initialize
    dut.io.operands(0) #= 0
    dut.io.operands(1) #= 0
    dut.io.operands(2) #= 0
    dut.io.operands(3) #= 0
    dut.io.op      #= 0
    dut.io.start   #= false
    dut.clockDomain.waitSampling(5)

    println("=" * 80)
    println("LongComputeUnit Interactive Simulation")
    println("=" * 80)

    // LMUL tests
    runOp(long64Bits(2L), long64Bits(3L), LMUL, "LMUL: 2 * 3 = 6")
    runOp(long64Bits(0L), long64Bits(12345L), LMUL, "LMUL: 0 * 12345 = 0")
    runOp(long64Bits(-1L), long64Bits(-1L), LMUL, "LMUL: -1 * -1 = 1")
    runOp(long64Bits(200000L), long64Bits(200000L), LMUL, "LMUL: 200000 * 200000")
    runOp(long64Bits(Long.MinValue), long64Bits(1L), LMUL, "LMUL: MIN_VALUE * 1 = MIN_VALUE")
    runOp(long64Bits(Long.MinValue), long64Bits(0L), LMUL, "LMUL: MIN_VALUE * 0 = 0")

    // LDIV tests
    runOp(long64Bits(7L), long64Bits(2L), LDIV, "LDIV: 7 / 2 = 3")
    runOp(long64Bits(-7L), long64Bits(2L), LDIV, "LDIV: -7 / 2 = -3")
    runOp(long64Bits(1L), long64Bits(1L), LDIV, "LDIV: 1 / 1 = 1")
    runOp(long64Bits(Long.MinValue), long64Bits(-1L), LDIV, "LDIV: MIN_VALUE / -1 = MIN_VALUE")
    runOp(long64Bits(42L), long64Bits(0L), LDIV, "LDIV: 42 / 0 = 0")

    // LREM tests
    runOp(long64Bits(7L), long64Bits(2L), LREM, "LREM: 7 % 2 = 1")
    runOp(long64Bits(-7L), long64Bits(2L), LREM, "LREM: -7 % 2 = -1")
    runOp(long64Bits(Long.MinValue), long64Bits(-1L), LREM, "LREM: MIN_VALUE % -1 = 0")
    runOp(long64Bits(42L), long64Bits(0L), LREM, "LREM: 42 % 0 = 0")

    // LSHL tests
    runOp(long64Bits(1L), long64Bits(0L), LSHL, "LSHL: 1 << 0 = 1")
    runOp(long64Bits(1L), long64Bits(1L), LSHL, "LSHL: 1 << 1 = 2")
    runOp(long64Bits(1L), long64Bits(63L), LSHL, "LSHL: 1 << 63 = MIN_VALUE")
    runOp(long64Bits(1L), long64Bits(64L), LSHL, "LSHL: 1 << 64 = 1 (6-bit mask)")

    // LSHR tests
    runOp(long64Bits(-1L), long64Bits(0L), LSHR, "LSHR: -1 >> 0 = -1")
    runOp(long64Bits(4L), long64Bits(1L), LSHR, "LSHR: 4 >> 1 = 2")
    runOp(long64Bits(-1L), long64Bits(32L), LSHR, "LSHR: -1 >> 32 = -1 (sign-extends)")

    // LUSHR tests
    runOp(long64Bits(-1L), long64Bits(32L), LUSHR, "LUSHR: -1 >>> 32 = 0x00000000FFFFFFFF")
    runOp(long64Bits(-1L), long64Bits(64L), LUSHR, "LUSHR: -1 >>> 64 = -1 (6-bit mask)")

    println("=" * 80)
    println("LongComputeUnit simulation complete")
    println("=" * 80)
  }
}
