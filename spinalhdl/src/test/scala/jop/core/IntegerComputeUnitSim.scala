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

  // JVM bytecodes
  val IMUL = 0x68
  val IDIV = 0x6C
  val IREM = 0x70

  SimConfig
    .withWave
    .workspacePath("simWorkspace")
    .compile(IntegerComputeUnit(fullConfig))
    .doSim { dut =>

    dut.clockDomain.forkStimulus(10)

    def int32Bits(i: Int): BigInt = BigInt(i.toLong & 0xFFFFFFFFL)

    val opNames = Map(
      0x68 -> "imul", 0x6C -> "idiv", 0x70 -> "irem"
    )

    def runOp(opa: BigInt, opb: BigInt, bytecode: Int, desc: String): BigInt = {
      val name = opNames.getOrElse(bytecode, f"0x${bytecode}%02X")
      println(f"--- $desc%-55s  opcode=$name  opa=0x${opa}%08X  opb=0x${opb}%08X ---")

      dut.io.a #= opa
      dut.io.b #= opb
      dut.io.opcode   #= bytecode
      dut.io.wr       #= true
      dut.clockDomain.waitSampling()
      dut.io.wr       #= false
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
    dut.io.a #= 0
    dut.io.b #= 0
    dut.io.opcode   #= 0
    dut.io.wr       #= false
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
