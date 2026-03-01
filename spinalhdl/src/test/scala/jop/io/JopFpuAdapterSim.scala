package jop.io

import spinal.core._
import spinal.core.sim._

/**
 * Standalone unit test for JopFpuAdapter.
 *
 * Tests all four operations (ADD, SUB, MUL, DIV) with known IEEE 754 values
 * and verifies the results match expected floating-point outputs.
 *
 * Run: sbt "Test / runMain jop.io.JopFpuAdapterSim"
 */
object JopFpuAdapterSim extends App {

  SimConfig.compile(JopFpuAdapter()).doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    // Helper: float to IEEE 754 bits
    def floatBits(f: Float): Long = java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
    def bitsFloat(b: Long): Float = java.lang.Float.intBitsToFloat((b & 0xFFFFFFFFL).toInt)

    // Initialize inputs
    dut.io.opa #= 0
    dut.io.opb #= 0
    dut.io.opcode #= 0
    dut.io.start #= false
    dut.clockDomain.waitSampling(20)  // Let FPU initialize

    var testsPassed = 0
    var testsFailed = 0

    def testOp(opName: String, opCode: Int, a: Float, b: Float, expected: Float): Unit = {
      // Drive operands and start
      dut.io.opa #= floatBits(a)
      dut.io.opb #= floatBits(b)
      dut.io.opcode #= opCode
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false

      // Wait for ready pulse (with timeout)
      var cycles = 0
      val maxCycles = 200
      while (!dut.io.ready.toBoolean && cycles < maxCycles) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      if (cycles >= maxCycles) {
        println(f"  TIMEOUT: $opName ($a%.4f op $b%.4f) did not complete in $maxCycles cycles")
        testsFailed += 1
        return
      }

      val resultBits = dut.io.result.toLong & 0xFFFFFFFFL
      val resultFloat = bitsFloat(resultBits)

      val aBits = floatBits(a)
      val bBits = floatBits(b)
      val expectedBits = floatBits(expected)

      val ok = resultBits == expectedBits
      if (ok) {
        println(f"  PASS: $opName($a%.6f, $b%.6f) = $resultFloat%.6f (expected $expected%.6f) [$cycles cycles]")
        testsPassed += 1
      } else {
        println(f"  FAIL: $opName($a%.6f, $b%.6f) = $resultFloat%.6f (0x${resultBits}%08X) expected $expected%.6f (0x${expectedBits}%08X) [$cycles cycles]")
        println(f"        opA=0x${aBits}%08X opB=0x${bBits}%08X")
        testsFailed += 1
      }

      // Wait a few cycles between operations
      dut.clockDomain.waitSampling(5)
    }

    println("=== JopFpuAdapter Standalone Test ===")
    println()

    // ADD tests (opcode 0)
    println("--- ADD ---")
    testOp("ADD", 0, 1.3f, 2.9f, 1.3f + 2.9f)
    testOp("ADD", 0, 0.0f, 0.0f, 0.0f)
    testOp("ADD", 0, 1.0f, -1.0f, 0.0f)
    testOp("ADD", 0, 100.5f, 200.25f, 100.5f + 200.25f)
    testOp("ADD", 0, -3.14f, 3.14f, 0.0f)

    // SUB tests (opcode 1)
    println("--- SUB ---")
    testOp("SUB", 1, 1.3f, 2.9f, 1.3f - 2.9f)
    testOp("SUB", 1, 5.0f, 3.0f, 2.0f)
    testOp("SUB", 1, 0.0f, 1.0f, -1.0f)
    testOp("SUB", 1, 100.0f, 100.0f, 0.0f)

    // MUL tests (opcode 2)
    println("--- MUL ---")
    testOp("MUL", 2, 2.0f, 3.0f, 6.0f)
    testOp("MUL", 2, 0.0f, 100.0f, 0.0f)
    testOp("MUL", 2, -2.0f, 3.0f, -6.0f)
    testOp("MUL", 2, 1.5f, 4.0f, 6.0f)
    testOp("MUL", 2, 10.0f, 10.0f, 100.0f)

    // DIV tests (opcode 3)
    println("--- DIV ---")
    testOp("DIV", 3, 6.0f, 2.0f, 3.0f)
    testOp("DIV", 3, 0.0f, 1.0f, 0.0f)
    testOp("DIV", 3, -6.0f, 2.0f, -3.0f)
    testOp("DIV", 3, 100.0f, 10.0f, 10.0f)
    testOp("DIV", 3, 7.0f, 2.0f, 3.5f)

    // FloatTest specific cases (these are the ones that might fail)
    println("--- FloatTest specific ---")
    testOp("ADD", 0, 0.0f, 1.0f, 1.0f)
    testOp("ADD", 0, 1.0f, 2.0f, 3.0f)
    testOp("ADD", 0, -0.5f, 1.0f, 0.5f)   // FloatField: -0.5 + 1.0 = 0.5

    println()
    println(s"=== Results: $testsPassed passed, $testsFailed failed ===")

    if (testsFailed > 0) {
      println("SOME TESTS FAILED")
    } else {
      println("ALL TESTS PASSED")
    }
  }
}
