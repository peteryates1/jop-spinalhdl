package jop.core

import org.scalatest.funsuite.AnyFunSuite
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import spinal.core._
import spinal.core.sim._

import scala.io.Source
import java.nio.file.{Path, Paths}

/**
 * Test case data loaded from JSON
 */
case class CycleInputs(cycle: Int, signals: Map[String, String])
case class CycleOutputs(cycle: Int, signals: Map[String, String])

case class MulTestCase(
  name: String,
  testType: String,
  description: Option[String],
  tags: Seq[String],
  inputs: Option[Seq[CycleInputs]],
  expectedOutputs: Option[Seq[CycleOutputs]],
  cycles: Int
)

case class MulTestVectors(
  module: String,
  version: String,
  description: Option[String],
  testCases: Seq[MulTestCase]
)

/**
 * JSON decoders for test vectors using circe
 */
object MulTestVectorLoader {
  // Custom decoder for CycleInputs
  implicit val cycleInputsDecoder: Decoder[CycleInputs] = deriveDecoder[CycleInputs]

  // Custom decoder for CycleOutputs
  implicit val cycleOutputsDecoder: Decoder[CycleOutputs] = deriveDecoder[CycleOutputs]

  // Custom decoder for MulTestCase with field name mapping
  implicit val testCaseDecoder: Decoder[MulTestCase] = Decoder.instance { cursor =>
    for {
      name <- cursor.get[String]("name")
      testType <- cursor.get[String]("type")
      description <- cursor.get[Option[String]]("description")
      tags <- cursor.getOrElse[Seq[String]]("tags")(Seq.empty)
      inputs <- cursor.get[Option[Seq[CycleInputs]]]("inputs")
      expectedOutputs <- cursor.get[Option[Seq[CycleOutputs]]]("expected_outputs")
      cycles <- cursor.get[Int]("cycles")
    } yield MulTestCase(name, testType, description, tags, inputs, expectedOutputs, cycles)
  }

  // Custom decoder for MulTestVectors with field name mapping
  implicit val testVectorsDecoder: Decoder[MulTestVectors] = Decoder.instance { cursor =>
    for {
      module <- cursor.get[String]("module")
      version <- cursor.get[String]("version")
      description <- cursor.get[Option[String]]("description")
      testCases <- cursor.get[Seq[MulTestCase]]("test_cases")
    } yield MulTestVectors(module, version, description, testCases)
  }

  /**
   * Parse value string to Long (handles 32-bit unsigned values)
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
  def load(projectRoot: Path): MulTestVectors = {
    val vectorFile = projectRoot.resolve("verification/test-vectors/modules/mul.json")
    val source = Source.fromFile(vectorFile.toFile)
    try {
      val jsonStr = source.mkString
      parse(jsonStr) match {
        case Right(json) =>
          json.as[MulTestVectors] match {
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
 * SpinalSim tests for the Mul (bit-serial multiplier) component
 *
 * These tests use the SHARED test vectors from verification/test-vectors/modules/mul.json
 * to ensure parity with CocoTB tests.
 *
 * Timing specification from test vectors:
 * - Cycle 0: wr=1, operands latched
 * - Cycles 1-17: Bit-serial multiplication
 * - Cycle 18: Result available on dout
 */
class MulTest extends AnyFunSuite {
  import MulTestVectorLoader._

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
  lazy val testVectors: MulTestVectors = load(findProjectRoot())

  // SpinalSim configuration with BOOT reset (no explicit reset signal)
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
   */
  def runTestCase(tc: MulTestCase): Unit = {
    simConfig.compile(Mul(32)).doSim(tc.name) { dut =>
      // Initialize clock domain
      dut.clockDomain.forkStimulus(10)

      // Initialize inputs to zero
      dut.io.ain #= 0
      dut.io.bin #= 0
      dut.io.wr #= false

      // Build input schedule: cycle -> signals
      val inputSchedule = scala.collection.mutable.Map[Int, Map[String, String]]()
      tc.inputs.foreach { inputs =>
        inputs.foreach { ci =>
          inputSchedule(ci.cycle) = ci.signals
        }
      }

      // Build expected output schedule: cycle -> signals
      val expectedSchedule = scala.collection.mutable.Map[Int, Map[String, String]]()
      tc.expectedOutputs.foreach { outputs =>
        outputs.foreach { co =>
          expectedSchedule(co.cycle) = co.signals
        }
      }

      // Track results
      var passed = true
      var failureMsg = ""

      // Run simulation for the specified number of cycles
      for (cycle <- 0 until tc.cycles) {
        // Apply inputs for this cycle
        inputSchedule.get(cycle).foreach { signals =>
          signals.get("ain").foreach { v => dut.io.ain #= parseValue(v) }
          signals.get("bin").foreach { v => dut.io.bin #= parseValue(v) }
          signals.get("wr").foreach { v => dut.io.wr #= (parseValue(v) != 0) }
        }

        // Advance one clock cycle
        dut.clockDomain.waitRisingEdge()

        // Check outputs at expected cycles (check AFTER the rising edge)
        expectedSchedule.get(cycle).foreach { expectedSignals =>
          expectedSignals.get("dout").foreach { expectedStr =>
            val expected = parseValue(expectedStr)
            val actual = dut.io.dout.toLong
            if (actual != expected) {
              passed = false
              failureMsg = s"Cycle $cycle: dout mismatch - expected 0x${expected.toHexString}, got 0x${actual.toHexString}"
            }
          }
        }
      }

      assert(passed, failureMsg)
    }
  }

  // Generate individual test cases from JSON
  testVectors.testCases.foreach { tc =>
    test(s"mul_${tc.name}") {
      println(s"Running test: ${tc.name}")
      tc.description.foreach(d => println(s"  Description: $d"))
      runTestCase(tc)
      println(s"  PASSED")
    }
  }

  // Summary test to verify all test cases loaded
  test("verify_test_vector_count") {
    println(s"Module: ${testVectors.module}")
    println(s"Version: ${testVectors.version}")
    println(s"Total test cases: ${testVectors.testCases.length}")
    assert(testVectors.testCases.length == 16,
      s"Expected 16 test cases, got ${testVectors.testCases.length}")
  }
}
