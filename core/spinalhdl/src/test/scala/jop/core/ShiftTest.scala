package jop.core

import org.scalatest.funsuite.AnyFunSuite
import io.circe._
import io.circe.parser._
import spinal.core._
import spinal.core.sim._

import scala.io.Source
import java.nio.file.{Path, Paths}

/**
 * Test case data loaded from JSON for Shift module
 */
case class ShiftCycleInputs(cycle: Int, signals: Map[String, String])
case class ShiftCycleOutputs(cycle: Int, signals: Map[String, String])

case class ShiftTestCase(
  name: String,
  testType: String,
  description: Option[String],
  tags: Seq[String],
  inputs: Seq[ShiftCycleInputs],
  expectedOutputs: Seq[ShiftCycleOutputs],
  cycles: Int
)

case class ShiftTestVectors(
  module: String,
  version: String,
  description: Option[String],
  testCases: Seq[ShiftTestCase]
)

/**
 * JSON decoders for Shift test vectors using circe
 */
object ShiftTestVectorLoader {
  import io.circe.generic.semiauto._

  // Custom decoder for ShiftCycleInputs
  implicit val cycleInputsDecoder: Decoder[ShiftCycleInputs] = deriveDecoder[ShiftCycleInputs]

  // Custom decoder for ShiftCycleOutputs
  implicit val cycleOutputsDecoder: Decoder[ShiftCycleOutputs] = deriveDecoder[ShiftCycleOutputs]

  // Custom decoder for ShiftTestCase with field name mapping
  implicit val testCaseDecoder: Decoder[ShiftTestCase] = Decoder.instance { cursor =>
    for {
      name <- cursor.get[String]("name")
      testType <- cursor.get[String]("type")
      description <- cursor.get[Option[String]]("description")
      tags <- cursor.getOrElse[Seq[String]]("tags")(Seq.empty)
      inputs <- cursor.getOrElse[Seq[ShiftCycleInputs]]("inputs")(Seq.empty)
      expectedOutputs <- cursor.getOrElse[Seq[ShiftCycleOutputs]]("expected_outputs")(Seq.empty)
      cycles <- cursor.get[Int]("cycles")
    } yield ShiftTestCase(name, testType, description, tags, inputs, expectedOutputs, cycles)
  }

  // Custom decoder for ShiftTestVectors with field name mapping
  implicit val testVectorsDecoder: Decoder[ShiftTestVectors] = Decoder.instance { cursor =>
    for {
      module <- cursor.get[String]("module")
      version <- cursor.get[String]("version")
      description <- cursor.get[Option[String]]("description")
      testCases <- cursor.get[Seq[ShiftTestCase]]("test_cases")
    } yield ShiftTestVectors(module, version, description, testCases)
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
  def load(projectRoot: Path): ShiftTestVectors = {
    val vectorFile = projectRoot.resolve("verification/test-vectors/modules/shift.json")
    val source = Source.fromFile(vectorFile.toFile)
    try {
      val jsonStr = source.mkString
      parse(jsonStr) match {
        case Right(json) =>
          json.as[ShiftTestVectors] match {
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
 * SpinalSim tests for the Shift (barrel shifter) component
 *
 * These tests use the SHARED test vectors from verification/test-vectors/modules/shift.json
 * to ensure parity with CocoTB tests.
 *
 * IMPORTANT: The Shift module is PURELY COMBINATIONAL
 * - No clock input required by the module
 * - Output changes immediately with input changes (0-cycle latency)
 * - Tests verify immediate output after applying inputs
 *
 * Shift Types:
 * - 0x0 (00): ushr - Unsigned shift right (zero fill)
 * - 0x1 (01): shl  - Shift left (zero fill)
 * - 0x2 (10): shr  - Arithmetic shift right (sign extension)
 */
class ShiftTest extends AnyFunSuite {
  import ShiftTestVectorLoader._

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
      Paths.get("/home/peter/workspaces/ai/jop")
    )
    candidates.find(_.resolve("verification/test-vectors").toFile.exists())
      .getOrElse(throw new RuntimeException("Could not find project root with test vectors"))
  }

  // Load test vectors once
  lazy val testVectors: ShiftTestVectors = load(findProjectRoot())

  // SpinalSim configuration - note: Shift is combinational but SpinalSim
  // requires a clock domain for simulation infrastructure
  val simConfig = SimConfig
    .withConfig(SpinalConfig(
      defaultConfigForClockDomains = ClockDomainConfig(
        resetKind = BOOT
      )
    ))
    .withWave
    .workspacePath("simWorkspace")

  /**
   * Run a single test case from the JSON test vectors
   *
   * Since Shift is combinational:
   * - Apply inputs directly (no clock cycles needed for latency)
   * - Check outputs immediately after input application
   * - Use sleep(1) to allow combinational logic to settle in simulation
   */
  def runTestCase(tc: ShiftTestCase): Unit = {
    simConfig.compile(Shift(32)).doSim(tc.name) { dut =>
      // Initialize clock domain for simulation infrastructure
      // (Shift itself is combinational, but SpinalSim needs this)
      dut.clockDomain.forkStimulus(10)

      // Initialize inputs to zero
      dut.io.din #= 0
      dut.io.off #= 0
      dut.io.shtyp #= 0

      // Wait for simulation to stabilize
      sleep(1)

      // Track results
      var passed = true
      var failureMsg = ""

      // Process each input cycle (for combinational, typically just cycle 0)
      tc.inputs.foreach { input =>
        // Apply inputs
        input.signals.get("din").foreach { v => dut.io.din #= parseValue(v) }
        input.signals.get("off").foreach { v => dut.io.off #= parseValue(v) }
        input.signals.get("shtyp").foreach { v => dut.io.shtyp #= parseValue(v) }

        // Allow combinational logic to propagate
        sleep(1)

        // Check expected outputs for this input cycle
        tc.expectedOutputs.filter(_.cycle == input.cycle).foreach { expectedOutput =>
          expectedOutput.signals.get("dout").foreach { expectedStr =>
            val expected = parseValue(expectedStr)
            val actual = dut.io.dout.toLong
            if (actual != expected) {
              passed = false
              // Capture input context for better debugging
              val dinVal = input.signals.getOrElse("din", "?")
              val offVal = input.signals.getOrElse("off", "?")
              val shtypVal = input.signals.getOrElse("shtyp", "?")
              val shtypName = parseValue(shtypVal) match {
                case 0 => "ushr"
                case 1 => "shl"
                case 2 => "shr"
                case _ => "unknown"
              }
              failureMsg = s"Test '${tc.name}': dout mismatch\n" +
                s"  Input:    din=$dinVal, off=$offVal, shtyp=$shtypVal ($shtypName)\n" +
                s"  Expected: 0x${expected.toHexString.toUpperCase}\n" +
                s"  Actual:   0x${actual.toHexString.toUpperCase}"
            }
          }
        }
      }

      assert(passed, failureMsg)
    }
  }

  // Generate individual test cases from JSON
  testVectors.testCases.foreach { tc =>
    test(s"shift_${tc.name}") {
      println(s"Running test: ${tc.name}")
      tc.description.foreach(d => println(s"  Description: $d"))
      runTestCase(tc)
      println(s"  PASSED")
    }
  }

  // Summary test to verify all test cases loaded
  test("shift_verify_test_vector_count") {
    println(s"Module: ${testVectors.module}")
    println(s"Version: ${testVectors.version}")
    println(s"Total test cases: ${testVectors.testCases.length}")

    // Verify we have 54 test cases to match VHDL tests
    assert(testVectors.testCases.length == 54,
      s"Expected 54 test cases to match VHDL test parity, got ${testVectors.testCases.length}")

    // Print test case categories
    val byType = testVectors.testCases.groupBy(_.testType)
    println("Test categories:")
    byType.foreach { case (typ, cases) =>
      println(s"  $typ: ${cases.length} tests")
    }
  }

  // Additional parity check with CocoTB expectations
  test("shift_cocotb_parity_check") {
    println("Verifying test parity with CocoTB tests...")

    // All shift types should be covered
    val allTags = testVectors.testCases.flatMap(_.tags).toSet
    assert(allTags.contains("ushr"), "Missing ushr tests")
    assert(allTags.contains("shl"), "Missing shl tests")
    assert(allTags.contains("shr"), "Missing shr tests")

    // Count by shift type
    val ushrTests = testVectors.testCases.filter(_.tags.contains("ushr"))
    val shlTests = testVectors.testCases.filter(_.tags.contains("shl"))
    val shrTests = testVectors.testCases.filter(_.tags.contains("shr"))

    println(s"  ushr (unsigned shift right): ${ushrTests.length} tests")
    println(s"  shl (shift left): ${shlTests.length} tests")
    println(s"  shr (arithmetic shift right): ${shrTests.length} tests")

    // Verify edge cases are covered
    val edgeCases = testVectors.testCases.filter(_.testType == "edge_case")
    println(s"  Edge cases: ${edgeCases.length} tests")
    assert(edgeCases.nonEmpty, "Expected edge case tests")

    // Verify zero shift tests exist
    val zeroShiftTests = testVectors.testCases.filter(_.tags.contains("zero"))
    println(s"  Zero shift tests: ${zeroShiftTests.length} tests")
    assert(zeroShiftTests.nonEmpty, "Expected zero shift tests")

    // Verify max shift (31 bits) tests exist
    val maxShiftTests = testVectors.testCases.filter(_.tags.contains("max"))
    println(s"  Max shift (31-bit) tests: ${maxShiftTests.length} tests")
    assert(maxShiftTests.nonEmpty, "Expected max shift tests")

    println("  CocoTB parity check: PASSED")
  }
}
