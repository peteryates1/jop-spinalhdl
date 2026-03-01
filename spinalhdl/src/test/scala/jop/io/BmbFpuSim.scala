package jop.io

import spinal.core._
import spinal.core.sim._

/**
 * Standalone unit test for BmbFpu (the full I/O peripheral wrapper).
 *
 * Tests the registered operand capture and timing as the microcode would use it:
 * - Drive wr=true for one cycle with wrData=TOS and bout=NOS
 * - Wait for busy to clear
 * - Read result
 *
 * Run: sbt "Test / runMain jop.io.BmbFpuSim"
 */
object BmbFpuSim extends App {

  SimConfig.compile(BmbFpu()).doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    def floatBits(f: Float): Long = java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
    def bitsFloat(b: Long): Float = java.lang.Float.intBitsToFloat((b & 0xFFFFFFFFL).toInt)

    // Initialize
    dut.io.addr #= 0
    dut.io.rd #= false
    dut.io.wr #= false
    dut.io.wrData #= 0
    dut.io.bout #= 0
    dut.clockDomain.waitSampling(20)

    // Detailed trace for first operation only
    println("=== Detailed Trace: ADD(1.3, 2.9) ===")
    val a = 1.3f
    val b = 2.9f
    println(f"  Driving: wr=true, addr=0 (ADD), wrData=0x${floatBits(b)}%08X ($b%.4f), bout=0x${floatBits(a)}%08X ($a%.4f)")

    dut.io.addr #= 0
    dut.io.wr #= true
    dut.io.wrData #= floatBits(b)
    dut.io.bout #= floatBits(a)

    // Trace several cycles
    var done = false
    var cycle = 0
    dut.clockDomain.waitSampling()
    cycle += 1
    dut.io.wr #= false
    dut.io.wrData #= 0
    dut.io.bout #= 0

    while (!done && cycle < 50) {
      val busy = dut.io.busy.toBoolean
      dut.io.addr #= 0
      dut.io.rd #= true
      val rdData = dut.io.rdData.toLong & 0xFFFFFFFFL
      val rdFloat = bitsFloat(rdData)
      if (cycle < 5 || !busy) {
        println(f"  cycle $cycle%3d: busy=$busy%-5s rdData=0x${rdData}%08X ($rdFloat%.6f)")
      }
      if (!busy && cycle > 1) {
        println(f"  >>> busy went FALSE at cycle $cycle")
        dut.io.rd #= false

        // Now wait additional cycles to see if result changes later (adapter still computing?)
        println("  >>> Waiting more cycles to check if result changes:")
        val resNow = rdData
        for (j <- 0 until 40) {
          dut.clockDomain.waitSampling()
          dut.io.addr #= 0
          dut.io.rd #= true
          val rdNow = dut.io.rdData.toLong & 0xFFFFFFFFL
          val busyNow = dut.io.busy.toBoolean
          if (busyNow || rdNow != resNow) {
            println(f"    +$j%3d: busy=$busyNow%-5s rdData=0x${rdNow}%08X (${bitsFloat(rdNow)}%.6f)")
          }
        }

        dut.io.addr #= 0
        dut.io.rd #= true
        dut.clockDomain.waitSampling()
        val resAfter = dut.io.rdData.toLong & 0xFFFFFFFFL
        println(f"  >>> Result after extra wait: 0x${resAfter}%08X (${bitsFloat(resAfter)}%.6f), expected ${a + b}%.6f (0x${floatBits(a + b)}%08X)")
        dut.io.rd #= false
        println()
        done = true
      }
      dut.clockDomain.waitSampling()
      cycle += 1
    }
    if (!done) println("  >>> Operation did NOT complete in 50 cycles!")
  }

  // Now run the actual functional test
  SimConfig.compile(BmbFpu()).doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    def floatBits(f: Float): Long = java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
    def bitsFloat(b: Long): Float = java.lang.Float.intBitsToFloat((b & 0xFFFFFFFFL).toInt)

    // Initialize
    dut.io.addr #= 0
    dut.io.rd #= false
    dut.io.wr #= false
    dut.io.wrData #= 0
    dut.io.bout #= 0
    dut.clockDomain.waitSampling(20)

    var testsPassed = 0
    var testsFailed = 0

    def testOp(opName: String, subAddr: Int, nos: Float, tos: Float, expected: Float): Unit = {
      // Simulate stmwd: one-cycle write pulse with wrData=TOS, bout=NOS
      dut.io.addr #= subAddr
      dut.io.wr #= true
      dut.io.wrData #= floatBits(tos)
      dut.io.bout #= floatBits(nos)
      dut.clockDomain.waitSampling()

      // De-assert write
      dut.io.wr #= false

      // Wait for busy to ASSERT (it might take 1 cycle)
      if (!dut.io.busy.toBoolean) {
        dut.clockDomain.waitSampling()
      }

      // Wait for busy to DEASSERT (with timeout)
      var cycles = 0
      val maxCycles = 200
      while (dut.io.busy.toBoolean && cycles < maxCycles) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      if (cycles >= maxCycles) {
        println(f"  TIMEOUT: $opName(nos=$nos%.4f, tos=$tos%.4f) did not complete in $maxCycles cycles")
        testsFailed += 1
        return
      }

      // Wait one more cycle for result register to be stable
      dut.clockDomain.waitSampling()

      // Read result (sub-addr 0)
      dut.io.addr #= 0
      dut.io.rd #= true
      dut.clockDomain.waitSampling()
      val resultBits = dut.io.rdData.toLong & 0xFFFFFFFFL
      dut.io.rd #= false

      val resultFloat = bitsFloat(resultBits)
      val expectedBits = floatBits(expected)
      val ok = resultBits == expectedBits

      if (ok) {
        println(f"  PASS: $opName(v1=$nos%.6f, v2=$tos%.6f) = $resultFloat%.6f [$cycles cycles]")
        testsPassed += 1
      } else {
        println(f"  FAIL: $opName(v1=$nos%.6f, v2=$tos%.6f) = $resultFloat%.6f (0x${resultBits}%08X) expected $expected%.6f (0x${expectedBits}%08X) [$cycles cycles]")
        testsFailed += 1
      }

      dut.clockDomain.waitSampling(3)
    }

    println("=== BmbFpu Functional Test ===")
    println()

    println("--- ADD ---")
    testOp("ADD", 0, 1.3f, 2.9f, 1.3f + 2.9f)
    testOp("ADD", 0, 0.0f, 1.0f, 1.0f)
    testOp("ADD", 0, -0.5f, 1.0f, 0.5f)

    println("--- SUB ---")
    testOp("SUB", 1, 1.3f, 2.9f, 1.3f - 2.9f)
    testOp("SUB", 1, 5.0f, 3.0f, 2.0f)

    println("--- MUL ---")
    testOp("MUL", 2, 2.0f, 3.0f, 6.0f)
    testOp("MUL", 2, -2.0f, 3.0f, -6.0f)

    println("--- DIV ---")
    testOp("DIV", 3, 6.0f, 2.0f, 3.0f)
    testOp("DIV", 3, 7.0f, 2.0f, 3.5f)

    println()
    println(s"=== Results: $testsPassed passed, $testsFailed failed ===")
    if (testsFailed > 0) println("SOME TESTS FAILED") else println("ALL TESTS PASSED")
  }
}
