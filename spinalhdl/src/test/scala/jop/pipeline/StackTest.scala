package jop.pipeline

import org.scalatest.funsuite.AnyFunSuite
import io.circe._
import io.circe.parser._
import spinal.core._
import spinal.core.sim._

import scala.io.Source
import java.nio.file.{Path, Paths}

/**
 * Test case data structures for Stack module JSON test vectors
 */
case class StackCycleInputs(cycle: Int, signals: Map[String, String])
case class StackCycleOutputs(cycle: Int, signals: Map[String, String])
case class StackInitialState(
  a_reg: Option[String],
  b_reg: Option[String],
  vp_reg: Option[String]
)

case class StackTestCase(
  name: String,
  testType: String,
  description: Option[String],
  tags: Seq[String],
  initialState: Option[StackInitialState],
  inputs: Seq[StackCycleInputs],
  expectedOutputs: Seq[StackCycleOutputs],
  cycles: Int
)

case class StackTestVectors(
  module: String,
  version: String,
  description: Option[String],
  testCases: Seq[StackTestCase]
)

/**
 * JSON decoders for Stack test vectors using circe
 */
object StackTestVectorLoader {
  import io.circe.generic.semiauto._

  implicit val cycleInputsDecoder: Decoder[StackCycleInputs] = deriveDecoder[StackCycleInputs]
  implicit val cycleOutputsDecoder: Decoder[StackCycleOutputs] = deriveDecoder[StackCycleOutputs]
  implicit val initialStateDecoder: Decoder[StackInitialState] = Decoder.instance { cursor =>
    for {
      a_reg <- cursor.get[Option[String]]("a_reg")
      b_reg <- cursor.get[Option[String]]("b_reg")
      vp_reg <- cursor.getOrElse[Option[String]]("vp_reg")(None)
    } yield StackInitialState(a_reg, b_reg, vp_reg)
  }

  implicit val testCaseDecoder: Decoder[StackTestCase] = Decoder.instance { cursor =>
    for {
      name <- cursor.get[String]("name")
      testType <- cursor.get[String]("type")
      description <- cursor.get[Option[String]]("description")
      tags <- cursor.getOrElse[Seq[String]]("tags")(Seq.empty)
      initialState <- cursor.get[Option[StackInitialState]]("initial_state")
      inputs <- cursor.getOrElse[Seq[StackCycleInputs]]("inputs")(Seq.empty)
      expectedOutputs <- cursor.getOrElse[Seq[StackCycleOutputs]]("expected_outputs")(Seq.empty)
      cycles <- cursor.get[Int]("cycles")
    } yield StackTestCase(name, testType, description, tags, initialState, inputs, expectedOutputs, cycles)
  }

  implicit val testVectorsDecoder: Decoder[StackTestVectors] = Decoder.instance { cursor =>
    for {
      module <- cursor.get[String]("module")
      version <- cursor.get[String]("version")
      description <- cursor.get[Option[String]]("description")
      testCases <- cursor.get[Seq[StackTestCase]]("test_cases")
    } yield StackTestVectors(module, version, description, testCases)
  }

  /**
   * Parse value string to Long (handles 32-bit unsigned values)
   * Supports formats: 0x (hex), 0b (binary), decimal
   */
  def parseValue(valueStr: String): Long = {
    val trimmed = valueStr.trim
    if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
      java.lang.Long.parseUnsignedLong(trimmed.substring(2), 16)
    } else if (trimmed.startsWith("0b") || trimmed.startsWith("0B")) {
      java.lang.Long.parseUnsignedLong(trimmed.substring(2), 2)
    } else {
      trimmed.toLong
    }
  }

  /**
   * Load test vectors from the shared JSON file
   */
  def load(projectRoot: Path): StackTestVectors = {
    val vectorFile = projectRoot.resolve("verification/test-vectors/modules/stack.json")
    val source = Source.fromFile(vectorFile.toFile)
    try {
      val jsonStr = source.mkString
      parse(jsonStr) match {
        case Right(json) =>
          json.as[StackTestVectors] match {
            case Right(vectors) => vectors
            case Left(err) => throw new RuntimeException(s"Failed to decode test vectors: $err")
          }
        case Left(err) => throw new RuntimeException(s"Failed to parse JSON: $err")
      }
    } finally {
      source.close()
    }
  }
}

/**
 * SpinalSim tests for the StackStage (Stack/Execute stage) component
 *
 * These tests use the SHARED test vectors from verification/test-vectors/modules/stack.json
 * to run all 69 JSON-driven per-vector tests via SpinalSim.
 *
 * Stack Stage Characteristics:
 * - 32-bit data path with 33-bit ALU for comparison
 * - Combinational outputs: zf, nf, eq, lt, aout, bout
 * - Registered outputs: A, B, SP, VP0-3, AR, immval, opddly
 * - SP initializes to 128 after reset
 * - RAM has 1-cycle write delay
 *
 * Test Types:
 * - reset: Verify initialization behavior
 * - alu: Test add/subtract operations
 * - logic: Test AND, OR, XOR, pass-through
 * - shift: Test USHR, SHL, SHR operations
 * - sp: Test stack pointer operations
 * - datapath: Test data path muxing
 */
class StackTest extends AnyFunSuite {
  import StackTestVectorLoader._

  // Find project root by looking for verification directory
  def findProjectRoot(): Path = {
    var current = Paths.get(System.getProperty("user.dir"))
    while (current != null) {
      if (current.resolve("verification/test-vectors").toFile.exists()) {
        return current
      }
      current = current.getParent
    }
    // Fallback: try relative paths from likely locations
    val candidates = Seq(
      Paths.get("../.."),
      Paths.get("../../.."),
      Paths.get(".")
    )
    candidates.find(_.resolve("verification/test-vectors").toFile.exists())
      .getOrElse(throw new RuntimeException("Could not find project root with test vectors"))
  }

  // Load test vectors once
  lazy val testVectors: StackTestVectors = load(findProjectRoot())

  // SpinalSim configuration
  val simConfig = SimConfig
    .withConfig(SpinalConfig(
      defaultConfigForClockDomains = ClockDomainConfig(
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    ))
    .withWave
    .workspacePath("simWorkspace")

  /**
   * Run a single test case from the JSON test vectors
   *
   * The Stack stage has both combinational and registered outputs:
   * - Combinational: zf, nf, eq, lt (flags)
   * - Registered: A (aout), B (bout), SP, etc.
   *
   * Test vectors with initial_state require pre-loading registers.
   */
  /** Reset all DUT inputs to their default values */
  private def resetInputsToDefaults(dut: StackStage): Unit = {
    dut.io.din #= 0
    dut.io.dirAddr #= 0
    dut.io.opd #= 0
    dut.io.jpc #= 0
    dut.io.selSub #= false
    dut.io.selAmux #= false
    dut.io.enaA #= false
    dut.io.selBmux #= false
    dut.io.selLog #= 0
    dut.io.selShf #= 0
    dut.io.selLmux #= 0
    dut.io.selImux #= 0
    dut.io.selRmux #= 0
    dut.io.selSmux #= 0
    dut.io.selMmux #= false
    dut.io.selRda #= 0
    dut.io.selWra #= 0
    dut.io.wrEna #= false
    dut.io.enaB #= false
    dut.io.enaVp #= false
    dut.io.enaAr #= false
  }

  def runTestCase(tc: StackTestCase): Unit = {
    simConfig.compile(StackStage()).doSim(tc.name) { dut =>
      // Initialize clock domain
      dut.clockDomain.forkStimulus(10)

      // Initialize all inputs to default values
      resetInputsToDefaults(dut)

      // Deassert reset and wait for simulation to stabilize.
      // Need 3 edges: forkStimulus holds reset for ~2 edges, then we need
      // one more for registers to exit reset cleanly.
      dut.clockDomain.waitRisingEdge(3)
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitRisingEdge()

      // Handle initial state by loading registers if specified.
      // Order: VP first (uses A as intermediate), then B, then A.
      tc.initialState.foreach { state =>
        state.vp_reg.foreach { vpVal =>
          // Load vpVal into A, then transfer to VP via enaVp
          dut.io.din #= parseValue(vpVal)
          dut.io.selAmux #= true
          dut.io.selLmux #= 4  // din path
          dut.io.enaA #= true
          dut.io.enaVp #= false
          dut.clockDomain.waitRisingEdge()
          // Now A has vpVal, transfer to VP
          dut.io.enaA #= false
          dut.io.enaVp #= true
          dut.clockDomain.waitRisingEdge()
          dut.io.enaVp #= false
        }
        state.b_reg.foreach { bVal =>
          // Load bVal into A via din path
          dut.io.din #= parseValue(bVal)
          dut.io.selAmux #= true
          dut.io.selLmux #= 4  // din path
          dut.io.enaA #= true
          dut.io.enaB #= false
          dut.clockDomain.waitRisingEdge()
          // Transfer A to B
          dut.io.enaA #= false
          dut.io.enaB #= true
          dut.io.selBmux #= false  // B from A
          dut.clockDomain.waitRisingEdge()
        }
        state.a_reg.foreach { aVal =>
          // Load aVal into A via din path
          dut.io.din #= parseValue(aVal)
          dut.io.selAmux #= true
          dut.io.selLmux #= 4  // din path
          dut.io.enaA #= true
          dut.io.enaB #= false
          dut.clockDomain.waitRisingEdge()
        }
        // Reset all enables and muxes after loading
        resetInputsToDefaults(dut)
      }

      // Build input schedule
      val inputSchedule = scala.collection.mutable.Map[Int, Map[String, String]]()
      tc.inputs.foreach { ci =>
        inputSchedule(ci.cycle) = ci.signals
      }

      // Build expected output schedule
      val expectedSchedule = scala.collection.mutable.Map[Int, Map[String, String]]()
      tc.expectedOutputs.foreach { co =>
        expectedSchedule(co.cycle) = co.signals
      }

      // Track results
      var passed = true
      val failureMsgs = scala.collection.mutable.ArrayBuffer[String]()

      // Run simulation for the specified number of cycles
      for (cycle <- 0 until tc.cycles) {
        // Apply inputs for this cycle
        inputSchedule.get(cycle).foreach { signals =>
          signals.get("reset").foreach { v =>
            if (parseValue(v) != 0) {
              dut.clockDomain.assertReset()
            } else {
              dut.clockDomain.deassertReset()
            }
          }
          signals.get("din").foreach { v => dut.io.din #= parseValue(v) }
          signals.get("opd").foreach { v => dut.io.opd #= parseValue(v) }
          signals.get("jpc").foreach { v => dut.io.jpc #= parseValue(v) }
          signals.get("sel_sub").foreach { v => dut.io.selSub #= (parseValue(v) != 0) }
          signals.get("sel_amux").foreach { v => dut.io.selAmux #= (parseValue(v) != 0) }
          signals.get("ena_a").foreach { v => dut.io.enaA #= (parseValue(v) != 0) }
          signals.get("sel_bmux").foreach { v => dut.io.selBmux #= (parseValue(v) != 0) }
          signals.get("sel_log").foreach { v => dut.io.selLog #= parseValue(v) }
          signals.get("sel_shf").foreach { v => dut.io.selShf #= parseValue(v) }
          signals.get("sel_lmux").foreach { v => dut.io.selLmux #= parseValue(v) }
          signals.get("sel_imux").foreach { v => dut.io.selImux #= parseValue(v) }
          signals.get("sel_rmux").foreach { v => dut.io.selRmux #= parseValue(v) }
          signals.get("sel_smux").foreach { v => dut.io.selSmux #= parseValue(v) }
          signals.get("sel_mmux").foreach { v => dut.io.selMmux #= (parseValue(v) != 0) }
          signals.get("sel_rda").foreach { v => dut.io.selRda #= parseValue(v) }
          signals.get("sel_wra").foreach { v => dut.io.selWra #= parseValue(v) }
          signals.get("wr_ena").foreach { v => dut.io.wrEna #= (parseValue(v) != 0) }
          signals.get("ena_b").foreach { v => dut.io.enaB #= (parseValue(v) != 0) }
          signals.get("ena_vp").foreach { v => dut.io.enaVp #= (parseValue(v) != 0) }
          signals.get("ena_ar").foreach { v => dut.io.enaAr #= (parseValue(v) != 0) }
        }

        // Advance one clock cycle
        dut.clockDomain.waitRisingEdge()

        // Check outputs at expected cycles
        expectedSchedule.get(cycle).foreach { expectedSignals =>
          def checkSignal(name: String, getter: => BigInt, bits: Int = 32): Unit = {
            expectedSignals.get(name).foreach { expectedStr =>
              val expected = parseValue(expectedStr)
              val actual = getter.toLong & ((1L << bits) - 1)  // Mask to expected bits
              if (actual != expected) {
                passed = false
                failureMsgs += s"Cycle $cycle: $name mismatch - expected 0x${expected.toHexString}, got 0x${actual.toHexString}"
              }
            }
          }

          checkSignal("aout", dut.io.aout.toBigInt)
          checkSignal("bout", dut.io.bout.toBigInt)
          checkSignal("zf", dut.io.zf.toBigInt, 1)
          checkSignal("nf", dut.io.nf.toBigInt, 1)
          checkSignal("eq", dut.io.eq.toBigInt, 1)
          checkSignal("lt", dut.io.lt.toBigInt, 1)
          checkSignal("sp_ov", dut.io.spOv.toBigInt, 1)
        }
      }

      assert(passed, failureMsgs.mkString("\n"))
    }
  }

  // Generate individual test cases from JSON
  testVectors.testCases.foreach { tc =>
    test(s"stack_${tc.name}") {
      println(s"Running test: ${tc.name}")
      tc.description.foreach(d => println(s"  Description: $d"))
      runTestCase(tc)
      println(s"  PASSED")
    }
  }

  // Test basic reset behavior
  test("stack_reset_behavior") {
    simConfig.compile(StackStage()).doSim("reset_behavior") { dut =>
      dut.clockDomain.forkStimulus(10)

      // Initialize all inputs to zero/false
      dut.io.din #= 0
      dut.io.dirAddr #= 0
      dut.io.opd #= 0
      dut.io.jpc #= 0
      dut.io.selSub #= false
      dut.io.selAmux #= false
      dut.io.enaA #= false
      dut.io.selBmux #= false
      dut.io.selLog #= 0
      dut.io.selShf #= 0
      dut.io.selLmux #= 0
      dut.io.selImux #= 0
      dut.io.selRmux #= 0
      dut.io.selSmux #= 0
      dut.io.selMmux #= false
      dut.io.selRda #= 0
      dut.io.selWra #= 0
      dut.io.wrEna #= false
      dut.io.enaB #= false
      dut.io.enaVp #= false
      dut.io.enaAr #= false

      // Assert reset for a few cycles
      dut.clockDomain.assertReset()
      dut.clockDomain.waitRisingEdge(2)
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitRisingEdge()

      // After reset, A and B should be 0
      assert(dut.io.aout.toBigInt == 0, s"A should be 0, got ${dut.io.aout.toBigInt}")
      assert(dut.io.bout.toBigInt == 0, s"B should be 0, got ${dut.io.bout.toBigInt}")
      assert(dut.io.zf.toBoolean, "Zero flag should be set")
      assert(dut.io.eq.toBoolean, "Equal flag should be set (A == B == 0)")
      println("  Reset behavior test PASSED")
    }
  }

  /** Helper to initialize DUT and deassert reset */
  def initializeDut(dut: StackStage): Unit = {
    dut.io.din #= 0
    dut.io.dirAddr #= 0
    dut.io.opd #= 0
    dut.io.jpc #= 0
    dut.io.selSub #= false
    dut.io.selAmux #= false
    dut.io.enaA #= false
    dut.io.selBmux #= false
    dut.io.selLog #= 0
    dut.io.selShf #= 0
    dut.io.selLmux #= 0
    dut.io.selImux #= 0
    dut.io.selRmux #= 0
    dut.io.selSmux #= 0
    dut.io.selMmux #= false
    dut.io.selRda #= 0
    dut.io.selWra #= 0
    dut.io.wrEna #= false
    dut.io.enaB #= false
    dut.io.enaVp #= false
    dut.io.enaAr #= false

    // Assert reset for a few cycles
    dut.clockDomain.assertReset()
    dut.clockDomain.waitRisingEdge(2)
    dut.clockDomain.deassertReset()
    dut.clockDomain.waitRisingEdge()
  }

  // Test component elaborates with no errors
  test("stack_elaboration") {
    println("  Testing StackStage elaboration...")
    simConfig.compile(StackStage()).doSim("elaboration") { dut =>
      dut.clockDomain.forkStimulus(10)
      initializeDut(dut)
      dut.clockDomain.waitRisingEdge(5)
      println("  Elaboration test PASSED")
    }
  }

  // Summary test to verify all test cases loaded
  test("stack_verify_test_vector_count") {
    println(s"Module: ${testVectors.module}")
    println(s"Version: ${testVectors.version}")
    println(s"Total test cases in JSON: ${testVectors.testCases.length}")

    assert(testVectors.testCases.length == 69,
      s"Expected 69 test cases, got ${testVectors.testCases.length}")

    // Print test case categories
    val byType = testVectors.testCases.groupBy(_.testType)
    println("Test categories:")
    byType.foreach { case (typ, cases) =>
      println(s"  $typ: ${cases.length} tests")
    }
  }

  // Verify JSON test vectors can be loaded and parsed
  test("stack_json_test_vector_structure") {
    val aluTests = testVectors.testCases.filter(_.testType == "alu")
    val logicTests = testVectors.testCases.filter(_.testType == "logic")
    val shiftTests = testVectors.testCases.filter(_.testType == "shift")
    val spOvTest = testVectors.testCases.find(_.name == "sp_overflow_flag")

    assert(aluTests.nonEmpty, "Should have ALU tests")
    assert(logicTests.nonEmpty, "Should have logic tests")
    assert(shiftTests.nonEmpty, "Should have shift tests")
    assert(spOvTest.isDefined, "Should have sp_overflow_flag test (Section 10.1)")

    println(s"  ALU tests found: ${aluTests.length}")
    println(s"  Logic tests found: ${logicTests.length}")
    println(s"  Shift tests found: ${shiftTests.length}")
    println(s"  sp_overflow_flag test: ${if (spOvTest.isDefined) "FOUND ✓" else "MISSING"}")
    println("  JSON test vector structure: VALID")
  }

}
