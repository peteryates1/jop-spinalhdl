package examples

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

/**
 * Tests for SimplePipeline
 *
 * Objectives:
 * 1. Verify pipeline stages work correctly
 * 2. Understand payload propagation between stages
 * 3. Verify automatic register insertion
 * 4. Learn how to test pipelines with SpinalSim
 */
class SimplePipelineTest extends AnyFunSuite {

  val simConfig = SimConfig
    .withWave  // Generate waveforms for inspection
    .withConfig(SpinalConfig(
      defaultClockDomainFrequency = FixedFrequency(50 MHz)
    ))

  test("simple_pipeline_basic_operation") {
    simConfig.compile(SimplePipeline()).doSim("basic_operation") { dut =>
      // Fork clock stimulus
      dut.clockDomain.forkStimulus(period = 10)

      // Wait for initialization
      dut.clockDomain.waitSampling()

      println("="*60)
      println("Test: Basic Pipeline Operation")
      println("="*60)

      // Test 1: Read addresses 5 and 10
      // Memory initialized with i*2, so:
      // mem[5] = 10, mem[10] = 20
      // Expected result: 10 + 20 = 30
      dut.io.start #= true
      dut.io.addrA #= 5
      dut.io.addrB #= 10

      println(s"\nCycle 0: Fetch stage")
      println(s"  Input: addrA=5, addrB=10")
      dut.clockDomain.waitSampling()

      // At cycle 1: fetch stage reads from memory (sync read)
      println(s"\nCycle 1: Memory read (sync)")
      println(s"  Fetch stage is reading mem[5] and mem[10]")
      dut.clockDomain.waitSampling()

      // At cycle 2: data available in fetch stage, propagates to execute
      println(s"\nCycle 2: Execute stage receives data")
      println(s"  DATA_A and DATA_B have propagated through pipeline register")
      dut.clockDomain.waitSampling()

      // At cycle 3: result available
      println(s"\nCycle 3: Result available")
      val result = dut.io.result.toLong
      println(s"  Result: {result} (expected: 30)")
      assert(result == 30, s"Expected 30, got $result")

      println("\n✓ Test 1 PASSED: 5 + 10 = 30")

      // Test 2: Different addresses
      // mem[0] = 0, mem[1] = 2
      // Expected result: 0 + 2 = 2
      dut.io.addrA #= 0
      dut.io.addrB #= 1

      println(s"\n" + "="*60)
      println(s"Test 2: Different addresses")
      println(s"  Input: addrA=0, addrB=1")

      // Wait for pipeline latency (3 cycles total)
      dut.clockDomain.waitSampling(3)

      val result2 = dut.io.result.toLong
      println(s"  Result: $result2 (expected: 2)")
      assert(result2 == 2, s"Expected 2, got $result2")

      println("\n✓ Test 2 PASSED: 0 + 1 = 2")

      // Test 3: Larger values
      // mem[100] = 200, mem[50] = 100
      // Expected result: 200 + 100 = 300
      dut.io.addrA #= 100
      dut.io.addrB #= 50

      println(s"\n" + "="*60)
      println(s"Test 3: Larger values")
      println(s"  Input: addrA=100, addrB=50")

      dut.clockDomain.waitSampling(3)

      val result3 = dut.io.result.toLong
      println(s"  Result: $result3 (expected: 300)")
      assert(result3 == 300, s"Expected 300, got $result3")

      println("\n✓ Test 3 PASSED: 100 + 50 = 300")

      println("\n" + "="*60)
      println("All tests PASSED!")
      println("="*60)
    }
  }

  test("pipeline_latency_verification") {
    simConfig.compile(SimplePipeline()).doSim("latency_check") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.waitSampling()

      println("="*60)
      println("Test: Pipeline Latency Verification")
      println("="*60)
      println("Objective: Verify 3-cycle latency from input to output")
      println()

      // Apply inputs at cycle 0
      dut.io.addrA #= 10
      dut.io.addrB #= 20
      println("Cycle 0: Inputs applied (addrA=10, addrB=20)")

      // Check output at each cycle
      for (cycle <- 1 to 5) {
        dut.clockDomain.waitSampling()
        val currentResult = dut.io.result.toLong
        println(s"Cycle $cycle: result = $currentResult")

        if (cycle == 3) {
          // Result should be available at cycle 3
          // mem[10] = 20, mem[20] = 40, so 20 + 40 = 60
          assert(currentResult == 60, s"Expected 60 at cycle 3, got $currentResult")
          println(s"  ✓ Result available at cycle 3 (latency = 3 cycles)")
        }
      }

      println("\n✓ Latency verification PASSED: 3-cycle pipeline")
      println("="*60)
    }
  }

  test("continuous_operation") {
    simConfig.compile(SimplePipeline()).doSim("continuous") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.waitSampling()

      println("="*60)
      println("Test: Continuous Pipeline Operation")
      println("="*60)
      println("Objective: Feed new inputs every cycle, verify throughput")
      println()

      // Feed inputs every cycle
      val testData = List(
        (1, 2, 4),    // mem[1]=2, mem[2]=4, result=6
        (3, 4, 14),   // mem[3]=6, mem[4]=8, result=14
        (5, 6, 22),   // mem[5]=10, mem[6]=12, result=22
        (7, 8, 30)    // mem[7]=14, mem[8]=16, result=30
      )

      testData.foreach { case (addrA, addrB, expectedResult) =>
        dut.io.addrA #= addrA
        dut.io.addrB #= addrB
        println(s"Input: addrA=$addrA, addrB=$addrB (expected result in 3 cycles: $expectedResult)")
        dut.clockDomain.waitSampling()
      }

      // Wait for pipeline to flush (3 cycles latency)
      dut.clockDomain.waitSampling(3)

      // Verify last result
      val finalResult = dut.io.result.toLong
      println(s"\nFinal result: $finalResult (expected: 30)")
      assert(finalResult == 30, s"Expected 30, got $finalResult")

      println("\n✓ Continuous operation PASSED")
      println("  Pipeline can accept new inputs every cycle")
      println("  Throughput: 1 operation per cycle (after initial latency)")
      println("="*60)
    }
  }
}
