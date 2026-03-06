package jop.core

import spinal.core._
import spinal.core.sim._

/**
  * Interactive simulation for FloatComputeUnit with trace output.
  * Run: sbt "Test / runMain jop.io.FloatComputeUnitSim"
  */
object FloatComputeUnitSim extends App {

  val fullConfig = FloatComputeUnitConfig(
    withAdd  = true,
    withMul  = true,
    withDiv  = true,
    withI2F  = true,
    withF2I  = true,
    withFcmp = true
  )

  // JVM bytecodes
  val FADD  = 0x62
  val FSUB  = 0x66
  val FMUL  = 0x6A
  val FDIV  = 0x6E
  val I2F   = 0x86
  val F2I   = 0x8B
  val FCMPL = 0x95
  val FCMPG = 0x96

  SimConfig
    .withWave
    .workspacePath("simWorkspace")
    .compile(FloatComputeUnit(fullConfig))
    .doSim { dut =>

    dut.clockDomain.forkStimulus(10)

    def floatBits(f: Float): Long = java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
    def bitsFloat(b: Long): Float = java.lang.Float.intBitsToFloat((b & 0xFFFFFFFFL).toInt)
    def int32Bits(i: Int): Long   = i.toLong & 0xFFFFFFFFL
    def bitsInt32(b: Long): Int   = (b & 0xFFFFFFFFL).toInt

    val opNames = Map(
      0x62 -> "fadd",  0x66 -> "fsub",  0x6A -> "fmul",  0x6E -> "fdiv",
      0x86 -> "i2f",   0x8B -> "f2i",   0x95 -> "fcmpl", 0x96 -> "fcmpg"
    )

    def runOp(opa: Long, opb: Long, bytecode: Int, desc: String): Long = {
      val name = opNames.getOrElse(bytecode, f"0x${bytecode}%02X")
      println(f"--- $desc%-40s  opcode=$name  opa=0x${opa}%08X  opb=0x${opb}%08X ---")

      dut.io.a #= (opa & 0xFFFFFFFFL)
      dut.io.b #= (opb & 0xFFFFFFFFL)
      dut.io.opcode   #= bytecode
      dut.io.wr       #= true
      dut.clockDomain.waitSampling()
      dut.io.wr       #= false
      dut.clockDomain.waitSampling()

      var cycles = 0
      while (dut.io.busy.toBoolean && cycles < 100) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      val result = dut.io.resultLo.toBigInt.toLong
      val resultFloat = bitsFloat(result)
      println(f"  result=0x${result}%08X ($resultFloat%.7g)  cycles=$cycles")
      dut.clockDomain.waitSampling(2)
      result
    }

    def runFloat(a: Float, b: Float, bytecode: Int, desc: String): Long =
      runOp(floatBits(a), floatBits(b), bytecode, desc)

    // Initialize
    dut.io.a #= 0
    dut.io.b #= 0
    dut.io.opcode   #= 0
    dut.io.wr       #= false
    dut.clockDomain.waitSampling(5)

    println("=" * 72)
    println("FloatComputeUnit Interactive Simulation")
    println("=" * 72)

    // ADD tests
    runFloat(1.5f, 2.5f, FADD, "ADD: 1.5 + 2.5 = 4.0")
    runFloat(1.0f, -1.0f, FADD, "ADD: 1.0 + (-1.0) = 0.0")
    runFloat(Float.MaxValue, Float.MaxValue, FADD, "ADD: overflow -> Inf")
    runFloat(Float.PositiveInfinity, 1.0f, FADD, "ADD: Inf + 1 = Inf")
    runFloat(Float.PositiveInfinity, Float.NegativeInfinity, FADD, "ADD: Inf + (-Inf) = NaN")

    // SUB tests
    runFloat(5.0f, 3.0f, FSUB, "SUB: 5.0 - 3.0 = 2.0")
    runFloat(1.0f, 1.0f, FSUB, "SUB: 1.0 - 1.0 = 0.0")

    // MUL tests
    runFloat(3.0f, 4.0f, FMUL, "MUL: 3.0 * 4.0 = 12.0")
    runFloat(-2.0f, 3.0f, FMUL, "MUL: -2.0 * 3.0 = -6.0")
    runFloat(Float.PositiveInfinity, 0.0f, FMUL, "MUL: Inf * 0 = NaN")

    // DIV tests
    runFloat(7.0f, 2.0f, FDIV, "DIV: 7.0 / 2.0 = 3.5")
    runFloat(1.0f, 3.0f, FDIV, "DIV: 1.0 / 3.0 = 0.333...")
    runFloat(1.0f, 0.0f, FDIV, "DIV: 1.0 / 0.0 = +Inf")
    runFloat(0.0f, 0.0f, FDIV, "DIV: 0.0 / 0.0 = NaN")

    // I2F tests
    runOp(int32Bits(1), 0, I2F, "I2F: 1 -> 1.0f")
    runOp(int32Bits(-1), 0, I2F, "I2F: -1 -> -1.0f")
    runOp(int32Bits(0x7FFFFFFF), 0, I2F, "I2F: INT_MAX -> 2.14748365E9")
    runOp(int32Bits(0), 0, I2F, "I2F: 0 -> 0.0f")

    // F2I tests
    runFloat(3.7f, 0.0f, F2I, "F2I: 3.7f -> 3")
    runFloat(-2.3f, 0.0f, F2I, "F2I: -2.3f -> -2")
    runFloat(0.5f, 0.0f, F2I, "F2I: 0.5f -> 0")
    runFloat(Float.NaN, 0.0f, F2I, "F2I: NaN -> 0")
    runFloat(Float.PositiveInfinity, 0.0f, F2I, "F2I: +Inf -> INT_MAX")

    // FCMPL tests
    runFloat(1.0f, 2.0f, FCMPL, "FCMPL: 1.0 < 2.0 -> -1")
    runFloat(2.0f, 1.0f, FCMPL, "FCMPL: 2.0 > 1.0 -> +1")
    runFloat(1.0f, 1.0f, FCMPL, "FCMPL: 1.0 == 1.0 -> 0")
    runFloat(Float.NaN, 1.0f, FCMPL, "FCMPL: NaN -> -1")

    // FCMPG tests
    runFloat(Float.NaN, 1.0f, FCMPG, "FCMPG: NaN -> +1")

    println("=" * 72)
    println("FloatComputeUnit simulation complete")
    println("=" * 72)
  }
}
