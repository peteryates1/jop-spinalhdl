package jop.core

import spinal.core._
import spinal.core.sim._

/**
  * Interactive simulation for IntegerComputeUnit with trace output.
  * Run: sbt "Test / runMain jop.core.IntegerComputeUnitSim"
  */
object IntegerComputeUnitSim extends App {

  val fullConfig = IntegerComputeUnitConfig(
    withMul = true,
    withDiv = true,
    withRem = true
  )

  // 4-bit op codes
  val IMUL = 0
  val IDIV = 1
  val IREM = 2

  SimConfig
    .withWave
    .workspacePath("simWorkspace")
    .compile(IntegerComputeUnit(fullConfig))
    .doSim { dut =>

    dut.clockDomain.forkStimulus(10)

    def int32Bits(i: Int): BigInt = BigInt(i.toLong & 0xFFFFFFFFL)

    val opNames = Map(
      0 -> "imul", 1 -> "idiv", 2 -> "irem"
    )

    def runOp(opa: BigInt, opb: BigInt, op: Int, desc: String): BigInt = {
      val name = opNames.getOrElse(op, s"op=$op")
      println(f"--- $desc%-55s  op=$name  opa=0x${opa}%08X  opb=0x${opb}%08X ---")

      dut.io.operands(0) #= opa
      dut.io.operands(1) #= opb
      dut.io.op          #= op
      dut.io.start       #= true
      dut.clockDomain.waitSampling()
      dut.io.start       #= false
      dut.clockDomain.waitSampling()

      var cycles = 0
      while (dut.io.busy.toBoolean && cycles < 500) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      val result = (dut.io.resultHi.toBigInt << 32) | dut.io.resultLo.toBigInt
      val result32 = result & BigInt("FFFFFFFF", 16)
      val resultSigned = if (result32 > BigInt("7FFFFFFF", 16))
        result32 - BigInt("100000000", 16) else result32
      println(f"  result=0x${result32}%08X (signed=$resultSigned)  cycles=$cycles")
      dut.clockDomain.waitSampling(2)
      result
    }

    // Initialize
    dut.io.operands(0) #= 0
    dut.io.operands(1) #= 0
    dut.io.op          #= 0
    dut.io.start       #= false
    dut.clockDomain.waitSampling(5)

    println("=" * 80)
    println("IntegerComputeUnit Interactive Simulation")
    println("=" * 80)

    // IMUL tests
    runOp(int32Bits(2), int32Bits(3), IMUL, "IMUL: 2 * 3 = 6")
    runOp(int32Bits(0), int32Bits(12345), IMUL, "IMUL: 0 * 12345 = 0")
    runOp(int32Bits(-1), int32Bits(-1), IMUL, "IMUL: -1 * -1 = 1")
    runOp(int32Bits(50000), int32Bits(50000), IMUL, "IMUL: 50000 * 50000 (overflow)")
    runOp(int32Bits(Int.MinValue), int32Bits(1), IMUL, "IMUL: MIN_VALUE * 1 = MIN_VALUE")
    runOp(int32Bits(Int.MinValue), int32Bits(0), IMUL, "IMUL: MIN_VALUE * 0 = 0")

    // IDIV tests
    runOp(int32Bits(7), int32Bits(2), IDIV, "IDIV: 7 / 2 = 3")
    runOp(int32Bits(-7), int32Bits(2), IDIV, "IDIV: -7 / 2 = -3")
    runOp(int32Bits(1), int32Bits(1), IDIV, "IDIV: 1 / 1 = 1")
    runOp(int32Bits(Int.MinValue), int32Bits(-1), IDIV, "IDIV: MIN_VALUE / -1 = MIN_VALUE")
    runOp(int32Bits(42), int32Bits(0), IDIV, "IDIV: 42 / 0 = 0")

    // IREM tests
    runOp(int32Bits(7), int32Bits(2), IREM, "IREM: 7 % 2 = 1")
    runOp(int32Bits(-7), int32Bits(2), IREM, "IREM: -7 % 2 = -1")
    runOp(int32Bits(Int.MinValue), int32Bits(-1), IREM, "IREM: MIN_VALUE % -1 = 0")
    runOp(int32Bits(42), int32Bits(0), IREM, "IREM: 42 % 0 = 0")

    println("=" * 80)
    println("IntegerComputeUnit simulation complete")
    println("=" * 80)
  }
}
