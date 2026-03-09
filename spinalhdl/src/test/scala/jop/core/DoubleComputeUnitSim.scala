package jop.core

import spinal.core._
import spinal.core.sim._

/**
  * Interactive simulation for DoubleComputeUnit with trace output.
  * Run: sbt "Test / runMain jop.core.DoubleComputeUnitSim"
  */
object DoubleComputeUnitSim extends App {

  val fullConfig = DoubleComputeUnitConfig(
    withAdd  = true,
    withMul  = true,
    withDiv  = true,
    withI2D  = true,
    withD2I  = true,
    withL2D  = true,
    withD2L  = true,
    withF2D  = true,
    withD2F  = true,
    withDcmp = true
  )

  // 4-bit op codes (must match DoubleComputeUnit RTL)
  val DADD  = 0
  val DSUB  = 1
  val DMUL  = 2
  val DDIV  = 3
  val DCMPL = 4
  val DCMPG = 5
  val F2D   = 6
  val D2F   = 7
  val I2D   = 8
  val D2I   = 9
  val L2D   = 10
  val D2L   = 11

  SimConfig
    .withWave
    .workspacePath("simWorkspace")
    .compile(DoubleComputeUnit(fullConfig))
    .doSim { dut =>

    dut.clockDomain.forkStimulus(10)

    def doubleBits(d: Double): BigInt = BigInt(java.lang.Double.doubleToRawLongBits(d)) & BigInt("FFFFFFFFFFFFFFFF", 16)
    def bitsDouble(b: BigInt): Double = java.lang.Double.longBitsToDouble((b & BigInt("FFFFFFFFFFFFFFFF", 16)).toLong)
    def floatBits(f: Float): BigInt   = BigInt(java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL)
    def bitsFloat(b: BigInt): Float   = java.lang.Float.intBitsToFloat((b & BigInt("FFFFFFFF", 16)).toInt)
    def int32Bits(i: Int): BigInt     = BigInt(i.toLong & 0xFFFFFFFFL)
    def long64Bits(l: Long): BigInt   = BigInt(l) & BigInt("FFFFFFFFFFFFFFFF", 16)

    val opNames = Map(
      0 -> "dadd",  1 -> "dsub",  2 -> "dmul",  3 -> "ddiv",
      4 -> "i2d",   5 -> "d2i",   6 -> "l2d",   7 -> "d2l",
      8 -> "f2d",   9 -> "d2f",  10 -> "dcmpl", 11 -> "dcmpg"
    )

    def runOp(opa: BigInt, opb: BigInt, op: Int, desc: String): BigInt = {
      val name = opNames.getOrElse(op, s"op=$op")
      println(f"--- $desc%-50s  op=$name  opa=0x${opa}%016X  opb=0x${opb}%016X ---")

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
      while (dut.io.busy.toBoolean && cycles < 200) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      val result = (dut.io.resultHi.toBigInt << 32) | dut.io.resultLo.toBigInt
      val is64 = dut.io.resultCount.toInt > 1
      if (is64) {
        val resultDouble = bitsDouble(result)
        println(f"  result=0x${result}%016X ($resultDouble%.15g)  is64=$is64  cycles=$cycles")
      } else {
        val low32 = (result & BigInt("FFFFFFFF", 16)).toInt
        println(f"  result=0x${result}%016X (int32=$low32)  is64=$is64  cycles=$cycles")
      }
      dut.clockDomain.waitSampling(2)
      result
    }

    def runDouble(a: Double, b: Double, op: Int, desc: String): BigInt =
      runOp(doubleBits(a), doubleBits(b), op, desc)

    // Initialize
    dut.io.operands(0) #= 0
    dut.io.operands(1) #= 0
    dut.io.operands(2) #= 0
    dut.io.operands(3) #= 0
    dut.io.op      #= 0
    dut.io.start   #= false
    dut.clockDomain.waitSampling(5)

    println("=" * 80)
    println("DoubleComputeUnit Interactive Simulation")
    println("=" * 80)

    // ADD tests
    runDouble(1.5, 2.5, DADD, "ADD: 1.5 + 2.5 = 4.0")
    runDouble(1.0, -1.0, DADD, "ADD: 1.0 + (-1.0) = 0.0")
    runDouble(Double.MaxValue, Double.MaxValue, DADD, "ADD: overflow -> Inf")
    runDouble(Double.PositiveInfinity, 1.0, DADD, "ADD: Inf + 1 = Inf")
    runDouble(Double.PositiveInfinity, Double.NegativeInfinity, DADD, "ADD: Inf + (-Inf) = NaN")

    // SUB tests
    runDouble(5.0, 3.0, DSUB, "SUB: 5.0 - 3.0 = 2.0")
    runDouble(1.0, 1.0, DSUB, "SUB: 1.0 - 1.0 = 0.0")

    // MUL tests
    runDouble(3.0, 4.0, DMUL, "MUL: 3.0 * 4.0 = 12.0")
    runDouble(-2.0, 3.0, DMUL, "MUL: -2.0 * 3.0 = -6.0")
    runDouble(Double.PositiveInfinity, 0.0, DMUL, "MUL: Inf * 0 = NaN")

    // DIV tests
    runDouble(7.0, 2.0, DDIV, "DIV: 7.0 / 2.0 = 3.5")
    runDouble(1.0, 3.0, DDIV, "DIV: 1.0 / 3.0 = 0.333...")
    runDouble(1.0, 0.0, DDIV, "DIV: 1.0 / 0.0 = +Inf")
    runDouble(0.0, 0.0, DDIV, "DIV: 0.0 / 0.0 = NaN")

    // I2D tests
    runOp(int32Bits(1), BigInt(0), I2D, "I2D: 1 -> 1.0")
    runOp(int32Bits(-1), BigInt(0), I2D, "I2D: -1 -> -1.0")
    runOp(int32Bits(0x7FFFFFFF), BigInt(0), I2D, "I2D: INT_MAX -> 2147483647.0")
    runOp(int32Bits(0), BigInt(0), I2D, "I2D: 0 -> 0.0")
    runOp(int32Bits(42), BigInt(0), I2D, "I2D: 42 -> 42.0")

    // D2I tests
    runDouble(3.7, 0.0, D2I, "D2I: 3.7 -> 3")
    runDouble(-2.3, 0.0, D2I, "D2I: -2.3 -> -2")
    runDouble(0.5, 0.0, D2I, "D2I: 0.5 -> 0")
    runDouble(Double.NaN, 0.0, D2I, "D2I: NaN -> 0")
    runDouble(Double.PositiveInfinity, 0.0, D2I, "D2I: +Inf -> INT_MAX")

    // L2D tests
    runOp(long64Bits(1L), BigInt(0), L2D, "L2D: 1L -> 1.0")
    runOp(long64Bits(-1L), BigInt(0), L2D, "L2D: -1L -> -1.0")
    runOp(long64Bits(Long.MaxValue), BigInt(0), L2D, "L2D: LONG_MAX")

    // D2L tests
    runDouble(3.7, 0.0, D2L, "D2L: 3.7 -> 3L")
    runDouble(-2.3, 0.0, D2L, "D2L: -2.3 -> -2L")
    runDouble(Double.NaN, 0.0, D2L, "D2L: NaN -> 0L")
    runDouble(Double.PositiveInfinity, 0.0, D2L, "D2L: +Inf -> LONG_MAX")

    // F2D tests
    runOp(floatBits(1.5f), BigInt(0), F2D, "F2D: 1.5f -> 1.5")
    runOp(floatBits(Float.PositiveInfinity), BigInt(0), F2D, "F2D: +Inf")
    runOp(floatBits(Float.NaN), BigInt(0), F2D, "F2D: NaN")
    runOp(floatBits(0.0f), BigInt(0), F2D, "F2D: 0.0f -> 0.0")

    // D2F tests
    runDouble(1.5, 0.0, D2F, "D2F: 1.5 -> 1.5f")
    runDouble(Double.PositiveInfinity, 0.0, D2F, "D2F: +Inf -> +Inf_f")
    runDouble(Double.NaN, 0.0, D2F, "D2F: NaN -> NaN_f")
    runDouble(1e308, 0.0, D2F, "D2F: 1e308 -> +Inf_f (overflow)")

    // DCMPL tests
    runDouble(1.0, 2.0, DCMPL, "DCMPL: 1.0 < 2.0 -> -1")
    runDouble(2.0, 1.0, DCMPL, "DCMPL: 2.0 > 1.0 -> +1")
    runDouble(1.0, 1.0, DCMPL, "DCMPL: 1.0 == 1.0 -> 0")
    runDouble(Double.NaN, 1.0, DCMPL, "DCMPL: NaN -> -1")

    // DCMPG tests
    runDouble(Double.NaN, 1.0, DCMPG, "DCMPG: NaN -> +1")

    println("=" * 80)
    println("DoubleComputeUnit simulation complete")
    println("=" * 80)
  }
}
