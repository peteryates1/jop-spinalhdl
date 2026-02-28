package jop.pipeline

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
case class FetchCycleInputs(cycle: Int, signals: Map[String, String])
case class FetchCycleOutputs(cycle: Int, signals: Map[String, String])

case class FetchTestCase(
  name: String,
  testType: String,
  description: Option[String],
  tags: Seq[String],
  inputs: Seq[FetchCycleInputs],
  expectedOutputs: Seq[FetchCycleOutputs],
  cycles: Int
)

case class FetchTestVectors(
  module: String,
  version: String,
  description: Option[String],
  testCases: Seq[FetchTestCase]
)

/**
 * JSON decoders for Fetch test vectors using circe
 */
object FetchTestVectorLoader {
  implicit val cycleInputsDecoder: Decoder[FetchCycleInputs] = deriveDecoder[FetchCycleInputs]
  implicit val cycleOutputsDecoder: Decoder[FetchCycleOutputs] = deriveDecoder[FetchCycleOutputs]

  implicit val testCaseDecoder: Decoder[FetchTestCase] = Decoder.instance { cursor =>
    for {
      name <- cursor.get[String]("name")
      testType <- cursor.get[String]("type")
      description <- cursor.get[Option[String]]("description")
      tags <- cursor.getOrElse[Seq[String]]("tags")(Seq.empty)
      inputs <- cursor.getOrElse[Seq[FetchCycleInputs]]("inputs")(Seq.empty)
      expectedOutputs <- cursor.getOrElse[Seq[FetchCycleOutputs]]("expected_outputs")(Seq.empty)
      cycles <- cursor.get[Int]("cycles")
    } yield FetchTestCase(name, testType, description, tags, inputs, expectedOutputs, cycles)
  }

  implicit val testVectorsDecoder: Decoder[FetchTestVectors] = Decoder.instance { cursor =>
    for {
      module <- cursor.get[String]("module")
      version <- cursor.get[String]("version")
      description <- cursor.get[Option[String]]("description")
      testCases <- cursor.get[Seq[FetchTestCase]]("test_cases")
    } yield FetchTestVectors(module, version, description, testCases)
  }

  /**
   * Parse value string to Long (handles 32-bit unsigned values).
   * Supports formats: 0x (hex), 0b (binary), decimal.
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
   * Check if a value string is a don't-care marker (contains 'X').
   */
  def isDontCare(valueStr: String): Boolean = {
    valueStr.trim.toUpperCase.contains("X")
  }

  /**
   * Load test vectors from the shared JSON file
   */
  def load(projectRoot: Path): FetchTestVectors = {
    val vectorFile = projectRoot.resolve("verification/test-vectors/modules/fetch.json")
    val source = Source.fromFile(vectorFile.toFile)
    try {
      val jsonStr = source.mkString
      parse(jsonStr) match {
        case Right(json) =>
          json.as[FetchTestVectors] match {
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
 * SpinalSim tests for the FetchStage (microcode ROM fetch) component
 *
 * These tests use the SHARED test vectors from verification/test-vectors/modules/fetch.json
 * using shared JSON test vectors.
 *
 * Timing specification from test vectors:
 * - ROM has registered address, unregistered output
 * - IR captures instruction on rising edge (1-cycle latency from PC change)
 * - PC MUX priority: jfetch > br > jmp > (pcwait AND bsy) > pc_inc
 */
class FetchStageTest extends AnyFunSuite {
  import FetchTestVectorLoader._

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
  lazy val testVectors: FetchTestVectors = load(findProjectRoot())

  // SpinalSim configuration with BOOT reset (no explicit reset signal)
  val simConfig = SimConfig
    .withConfig(SpinalConfig(
      defaultConfigForClockDomains = ClockDomainConfig(
        resetKind = BOOT
      ),
      defaultClockDomainFrequency = FixedFrequency(100 MHz)
    ))
    .withWave
    .workspacePath("simWorkspace")

  /**
   * Run a single test case from the JSON test vectors
   */
  def runTestCase(tc: FetchTestCase): Unit = {
    simConfig.compile(FetchStage()).doSim(tc.name) { dut =>
      // Initialize clock domain
      dut.clockDomain.forkStimulus(10)

      // Initialize inputs to zero
      dut.io.br #= false
      dut.io.jmp #= false
      dut.io.bsy #= false
      dut.io.jpaddr #= 0
      dut.io.extStall #= false

      // For "reset" type tests: FetchStage uses BOOT reset (registers
      // initialized via init values at elaboration time). No explicit reset
      // signal exists, so we just let the simulation start -- the BOOT
      // initial values are already applied at time 0.

      // Build input schedule: cycle -> signals
      val inputSchedule = scala.collection.mutable.Map[Int, Map[String, String]]()
      tc.inputs.foreach { ci =>
        inputSchedule(ci.cycle) = ci.signals
      }

      // Build expected output schedule: cycle -> signals
      val expectedSchedule = scala.collection.mutable.Map[Int, Map[String, String]]()
      tc.expectedOutputs.foreach { co =>
        expectedSchedule(co.cycle) = co.signals
      }

      // Track results
      var passed = true
      var failureMsg = ""

      // Run simulation for the specified number of cycles
      for (cycle <- 0 until tc.cycles) {
        // Apply inputs for this cycle
        inputSchedule.get(cycle).foreach { signals =>
          signals.get("br").foreach { v => dut.io.br #= (parseValue(v) != 0) }
          signals.get("jmp").foreach { v => dut.io.jmp #= (parseValue(v) != 0) }
          signals.get("bsy").foreach { v => dut.io.bsy #= (parseValue(v) != 0) }
          signals.get("jpaddr").foreach { v => dut.io.jpaddr #= parseValue(v) }
        }

        // Always keep extStall deasserted
        dut.io.extStall #= false

        // Advance one clock cycle
        dut.clockDomain.waitRisingEdge()

        // Check outputs at expected cycles (check AFTER the rising edge)
        expectedSchedule.get(cycle).foreach { expectedSignals =>
          expectedSignals.foreach { case (signalName, expectedStr) =>
            // Skip don't-care values
            if (!isDontCare(expectedStr)) {
              val expected = parseValue(expectedStr)

              signalName match {
                case "pc_out" =>
                  val actual = dut.io.pc_out.toLong
                  if (actual != expected) {
                    passed = false
                    failureMsg = s"Cycle $cycle: pc_out mismatch - expected 0x${expected.toHexString}, got 0x${actual.toHexString}"
                  }
                case "ir_out" =>
                  val actual = dut.io.ir_out.toLong
                  if (actual != expected) {
                    passed = false
                    failureMsg = s"Cycle $cycle: ir_out mismatch - expected 0x${expected.toHexString}, got 0x${actual.toHexString}"
                  }
                case "nxt" =>
                  val actual = dut.io.nxt.toBoolean
                  val expectedBool = expected != 0
                  if (actual != expectedBool) {
                    passed = false
                    failureMsg = s"Cycle $cycle: nxt mismatch - expected $expectedBool, got $actual"
                  }
                case "opd" =>
                  val actual = dut.io.opd.toBoolean
                  val expectedBool = expected != 0
                  if (actual != expectedBool) {
                    passed = false
                    failureMsg = s"Cycle $cycle: opd mismatch - expected $expectedBool, got $actual"
                  }
                case "dout" =>
                  val actual = dut.io.dout.toLong
                  if (actual != expected) {
                    passed = false
                    failureMsg = s"Cycle $cycle: dout mismatch - expected 0x${expected.toHexString}, got 0x${actual.toHexString}"
                  }
                case other =>
                  // Unknown signal - skip
              }
            }
          }
        }
      }

      assert(passed, failureMsg)
    }
  }

  // Generate individual test cases from JSON
  testVectors.testCases.foreach { tc =>
    test(s"fetch_${tc.name}") {
      println(s"Running test: ${tc.name}")
      tc.description.foreach(d => println(s"  Description: $d"))
      runTestCase(tc)
      println(s"  PASSED")
    }
  }

  // Summary test to verify all test cases loaded
  test("fetch_verify_test_vector_count") {
    println(s"Module: ${testVectors.module}")
    println(s"Version: ${testVectors.version}")
    println(s"Total test cases: ${testVectors.testCases.length}")
    assert(testVectors.testCases.length == 24,
      s"Expected 24 test cases, got ${testVectors.testCases.length}")
  }
}
