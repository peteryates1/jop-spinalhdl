package jop.io

import spinal.core._
import spinal.core.sim._

/**
  * Interactive simulation for SimpleFpu with trace output.
  * Run: sbt "Test / runMain jop.io.SimpleFpuSim"
  */
object SimpleFpuSim extends App {

  val fullConfig = SimpleFpuConfig(
    withAdd  = true,
    withMul  = true,
    withDiv  = true,
    withI2F  = true,
    withF2I  = true,
    withFcmp = true
  )

  SimConfig
    .withWave
    .workspacePath("simWorkspace")
    .compile(SimpleFpu(fullConfig))
    .doSim { dut =>

    dut.clockDomain.forkStimulus(10)

    def floatBits(f: Float): Long = java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
    def bitsFloat(b: Long): Float = java.lang.Float.intBitsToFloat((b & 0xFFFFFFFFL).toInt)
    def int32Bits(i: Int): Long   = i.toLong & 0xFFFFFFFFL
    def bitsInt32(b: Long): Int   = (b & 0xFFFFFFFFL).toInt

    val opNames = Array("ADD", "SUB", "MUL", "DIV", "I2F", "F2I", "FCMPL", "FCMPG")

    def runOp(opa: Long, opb: Long, opcode: Int, desc: String): Long = {
      println(f"--- $desc%-40s  opcode=${opNames(opcode)}  opa=0x${opa}%08X  opb=0x${opb}%08X ---")

      dut.io.opa    #= opa
      dut.io.opb    #= opb
      dut.io.opcode #= opcode
      dut.io.start  #= true
      dut.clockDomain.waitSampling()
      dut.io.start  #= false

      var cycles = 0
      while (!dut.io.ready.toBoolean && cycles < 100) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      val result = dut.io.result.toLong & 0xFFFFFFFFL
      val resultFloat = bitsFloat(result)
      println(f"  result=0x${result}%08X ($resultFloat%.7g)  cycles=$cycles")
      dut.clockDomain.waitSampling(2)
      result
    }

    def runFloat(a: Float, b: Float, opcode: Int, desc: String): Long =
      runOp(floatBits(a), floatBits(b), opcode, desc)

    // Initialize
    dut.io.opa    #= 0
    dut.io.opb    #= 0
    dut.io.opcode #= 0
    dut.io.start  #= false
    dut.clockDomain.waitSampling(5)

    println("=" * 72)
    println("SimpleFpu Interactive Simulation")
    println("=" * 72)

    // ADD tests
    runFloat(1.5f, 2.5f, 0, "ADD: 1.5 + 2.5 = 4.0")
    runFloat(1.0f, -1.0f, 0, "ADD: 1.0 + (-1.0) = 0.0")
    runFloat(Float.MaxValue, Float.MaxValue, 0, "ADD: overflow → Inf")
    runFloat(Float.PositiveInfinity, 1.0f, 0, "ADD: Inf + 1 = Inf")
    runFloat(Float.PositiveInfinity, Float.NegativeInfinity, 0, "ADD: Inf + (-Inf) = NaN")

    // SUB tests
    runFloat(5.0f, 3.0f, 1, "SUB: 5.0 - 3.0 = 2.0")
    runFloat(1.0f, 1.0f, 1, "SUB: 1.0 - 1.0 = 0.0")

    // MUL tests
    runFloat(3.0f, 4.0f, 2, "MUL: 3.0 * 4.0 = 12.0")
    runFloat(-2.0f, 3.0f, 2, "MUL: -2.0 * 3.0 = -6.0")
    runFloat(Float.PositiveInfinity, 0.0f, 2, "MUL: Inf * 0 = NaN")

    // DIV tests
    runFloat(7.0f, 2.0f, 3, "DIV: 7.0 / 2.0 = 3.5")
    runFloat(1.0f, 3.0f, 3, "DIV: 1.0 / 3.0 = 0.333...")
    runFloat(1.0f, 0.0f, 3, "DIV: 1.0 / 0.0 = +Inf")
    runFloat(0.0f, 0.0f, 3, "DIV: 0.0 / 0.0 = NaN")

    // I2F tests
    runOp(int32Bits(1), 0, 4, "I2F: 1 → 1.0f")
    runOp(int32Bits(-1), 0, 4, "I2F: -1 → -1.0f")
    runOp(int32Bits(0x7FFFFFFF), 0, 4, "I2F: INT_MAX → 2.14748365E9")
    runOp(int32Bits(0), 0, 4, "I2F: 0 → 0.0f")

    // F2I tests
    runFloat(3.7f, 0.0f, 5, "F2I: 3.7f → 3")
    runFloat(-2.3f, 0.0f, 5, "F2I: -2.3f → -2")
    runFloat(0.5f, 0.0f, 5, "F2I: 0.5f → 0")
    runFloat(Float.NaN, 0.0f, 5, "F2I: NaN → 0")
    runFloat(Float.PositiveInfinity, 0.0f, 5, "F2I: +Inf → INT_MAX")

    // FCMPL tests
    runFloat(1.0f, 2.0f, 6, "FCMPL: 1.0 < 2.0 → -1")
    runFloat(2.0f, 1.0f, 6, "FCMPL: 2.0 > 1.0 → +1")
    runFloat(1.0f, 1.0f, 6, "FCMPL: 1.0 == 1.0 → 0")
    runFloat(Float.NaN, 1.0f, 6, "FCMPL: NaN → -1")

    // FCMPG tests
    runFloat(Float.NaN, 1.0f, 7, "FCMPG: NaN → +1")

    println("=" * 72)
    println("SimpleFpu simulation complete")
    println("=" * 72)
  }
}
