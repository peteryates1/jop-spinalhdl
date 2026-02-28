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
case class DecodeCycleInputs(cycle: Int, signals: Map[String, String])
case class DecodeCycleOutputs(cycle: Int, signals: Map[String, String])

case class DecodeTestCase(
  name: String,
  testType: String,
  description: Option[String],
  tags: Seq[String],
  inputs: Seq[DecodeCycleInputs],
  expectedOutputs: Seq[DecodeCycleOutputs],
  cycles: Int
)

case class DecodeTestVectors(
  module: String,
  version: String,
  description: Option[String],
  testCases: Seq[DecodeTestCase]
)

/**
 * JSON decoders for Decode test vectors using circe
 */
object DecodeTestVectorLoader {
  implicit val cycleInputsDecoder: Decoder[DecodeCycleInputs] = deriveDecoder[DecodeCycleInputs]
  implicit val cycleOutputsDecoder: Decoder[DecodeCycleOutputs] = deriveDecoder[DecodeCycleOutputs]

  implicit val testCaseDecoder: Decoder[DecodeTestCase] = Decoder.instance { cursor =>
    for {
      name <- cursor.get[String]("name")
      testType <- cursor.get[String]("type")
      description <- cursor.get[Option[String]]("description")
      tags <- cursor.getOrElse[Seq[String]]("tags")(Seq.empty)
      inputs <- cursor.getOrElse[Seq[DecodeCycleInputs]]("inputs")(Seq.empty)
      expectedOutputs <- cursor.getOrElse[Seq[DecodeCycleOutputs]]("expected_outputs")(Seq.empty)
      cycles <- cursor.get[Int]("cycles")
    } yield DecodeTestCase(name, testType, description, tags, inputs, expectedOutputs, cycles)
  }

  implicit val testVectorsDecoder: Decoder[DecodeTestVectors] = Decoder.instance { cursor =>
    for {
      module <- cursor.get[String]("module")
      version <- cursor.get[String]("version")
      description <- cursor.get[Option[String]]("description")
      testCases <- cursor.get[Seq[DecodeTestCase]]("test_cases")
    } yield DecodeTestVectors(module, version, description, testCases)
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
  def load(projectRoot: Path): DecodeTestVectors = {
    val vectorFile = projectRoot.resolve("verification/test-vectors/modules/decode.json")
    val source = Source.fromFile(vectorFile.toFile)
    try {
      val jsonStr = source.mkString
      parse(jsonStr) match {
        case Right(json) =>
          json.as[DecodeTestVectors] match {
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
 * SpinalSim tests for the DecodeStage (microcode instruction decoder) component
 *
 * These tests use the SHARED test vectors from verification/test-vectors/modules/decode.json
 * using shared JSON test vectors.
 *
 * Timing specification from test vectors:
 * - Some outputs are combinational (0 cycle): jbr, sel_rda, sel_wra, sel_smux, wr_ena, dir, sel_imux, mmu_instr, mem_in_bcopd
 * - Some outputs are registered (1 cycle): br, jmp, ALU control, MMU control, ena_a, ena_vp, ena_jpc, ena_ar
 * - DecodeStage uses SYNC reset with HIGH active level
 */
class DecodeStageTest extends AnyFunSuite {
  import DecodeTestVectorLoader._

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
  lazy val testVectors: DecodeTestVectors = load(findProjectRoot())

  // SpinalSim configuration with SYNC reset, HIGH active level
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
   * Check a Bool output signal against an expected value.
   * Returns None if matched, Some(errorMsg) if mismatched.
   */
  private def checkBool(actual: Boolean, expected: Long, cycle: Int, name: String): Option[String] = {
    val expectedBool = expected != 0
    if (actual != expectedBool) {
      Some(s"Cycle $cycle: $name mismatch - expected $expectedBool, got $actual")
    } else {
      None
    }
  }

  /**
   * Check a Bits output signal against an expected value.
   * Returns None if matched, Some(errorMsg) if mismatched.
   */
  private def checkBits(actual: Long, expected: Long, cycle: Int, name: String): Option[String] = {
    if (actual != expected) {
      Some(s"Cycle $cycle: $name mismatch - expected 0x${expected.toHexString}, got 0x${actual.toHexString}")
    } else {
      None
    }
  }

  /**
   * Run a single test case from the JSON test vectors
   */
  def runTestCase(tc: DecodeTestCase): Unit = {
    simConfig.compile(DecodeStage()).doSim(tc.name) { dut =>
      // Initialize clock domain
      dut.clockDomain.forkStimulus(10)

      // Initialize all inputs to zero/false
      dut.io.instr  #= 0
      dut.io.zf     #= false
      dut.io.nf     #= false
      dut.io.eq     #= false
      dut.io.lt     #= false
      dut.io.bcopd  #= 0
      dut.io.stall  #= false

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
          // Handle reset signal
          signals.get("reset").foreach { v =>
            if (parseValue(v) != 0) {
              dut.clockDomain.assertReset()
            } else {
              dut.clockDomain.deassertReset()
            }
          }

          signals.get("instr").foreach { v => dut.io.instr  #= parseValue(v) }
          signals.get("zf").foreach    { v => dut.io.zf     #= (parseValue(v) != 0) }
          signals.get("nf").foreach    { v => dut.io.nf     #= (parseValue(v) != 0) }
          signals.get("eq").foreach    { v => dut.io.eq     #= (parseValue(v) != 0) }
          signals.get("lt").foreach    { v => dut.io.lt     #= (parseValue(v) != 0) }
          signals.get("bcopd").foreach { v => dut.io.bcopd  #= parseValue(v) }
        }

        // Always keep stall deasserted
        dut.io.stall #= false

        // Advance one clock cycle
        dut.clockDomain.waitRisingEdge()

        // Check outputs at expected cycles (check AFTER the rising edge)
        expectedSchedule.get(cycle).foreach { expectedSignals =>
          expectedSignals.foreach { case (signalName, expectedStr) =>
            // Skip don't-care values
            if (!isDontCare(expectedStr)) {
              val expected = parseValue(expectedStr)

              val error: Option[String] = signalName match {
                // Bool outputs
                case "br"       => checkBool(dut.io.br.toBoolean, expected, cycle, "br")
                case "jmp"      => checkBool(dut.io.jmp.toBoolean, expected, cycle, "jmp")
                case "jbr"      => checkBool(dut.io.jbr.toBoolean, expected, cycle, "jbr")
                case "sel_sub"  => checkBool(dut.io.selSub.toBoolean, expected, cycle, "sel_sub")
                case "sel_amux" => checkBool(dut.io.selAmux.toBoolean, expected, cycle, "sel_amux")
                case "ena_a"    => checkBool(dut.io.enaA.toBoolean, expected, cycle, "ena_a")
                case "sel_bmux" => checkBool(dut.io.selBmux.toBoolean, expected, cycle, "sel_bmux")
                case "sel_mmux" => checkBool(dut.io.selMmux.toBoolean, expected, cycle, "sel_mmux")
                case "wr_ena"   => checkBool(dut.io.wrEna.toBoolean, expected, cycle, "wr_ena")
                case "ena_b"    => checkBool(dut.io.enaB.toBoolean, expected, cycle, "ena_b")
                case "ena_vp"   => checkBool(dut.io.enaVp.toBoolean, expected, cycle, "ena_vp")
                case "ena_jpc"  => checkBool(dut.io.enaJpc.toBoolean, expected, cycle, "ena_jpc")
                case "ena_ar"   => checkBool(dut.io.enaAr.toBoolean, expected, cycle, "ena_ar")
                case "mul_wr"   => checkBool(dut.io.mulWr.toBoolean, expected, cycle, "mul_wr")
                case "wr_dly"   => checkBool(dut.io.wrDly.toBoolean, expected, cycle, "wr_dly")

                // Bits outputs
                case "sel_log"  => checkBits(dut.io.selLog.toLong, expected, cycle, "sel_log")
                case "sel_shf"  => checkBits(dut.io.selShf.toLong, expected, cycle, "sel_shf")
                case "sel_lmux" => checkBits(dut.io.selLmux.toLong, expected, cycle, "sel_lmux")
                case "sel_imux" => checkBits(dut.io.selImux.toLong, expected, cycle, "sel_imux")
                case "sel_rmux" => checkBits(dut.io.selRmux.toLong, expected, cycle, "sel_rmux")
                case "sel_smux" => checkBits(dut.io.selSmux.toLong, expected, cycle, "sel_smux")
                case "sel_rda"  => checkBits(dut.io.selRda.toLong, expected, cycle, "sel_rda")
                case "sel_wra"  => checkBits(dut.io.selWra.toLong, expected, cycle, "sel_wra")
                case "dir"      => checkBits(dut.io.dirAddr.toLong, expected, cycle, "dir")
                case "mmu_instr" => checkBits(dut.io.mmuInstr.toLong, expected, cycle, "mmu_instr")

                // MemoryControl sub-signals (Bools)
                case "mem_in_rd"        => checkBool(dut.io.memIn.rd.toBoolean, expected, cycle, "mem_in_rd")
                case "mem_in_wr"        => checkBool(dut.io.memIn.wr.toBoolean, expected, cycle, "mem_in_wr")
                case "mem_in_addr_wr"   => checkBool(dut.io.memIn.addrWr.toBoolean, expected, cycle, "mem_in_addr_wr")
                case "mem_in_iaload"    => checkBool(dut.io.memIn.iaload.toBoolean, expected, cycle, "mem_in_iaload")
                case "mem_in_iastore"   => checkBool(dut.io.memIn.iastore.toBoolean, expected, cycle, "mem_in_iastore")
                case "mem_in_getfield"  => checkBool(dut.io.memIn.getfield.toBoolean, expected, cycle, "mem_in_getfield")
                case "mem_in_putfield"  => checkBool(dut.io.memIn.putfield.toBoolean, expected, cycle, "mem_in_putfield")
                case "mem_in_putref"    => checkBool(dut.io.memIn.putref.toBoolean, expected, cycle, "mem_in_putref")
                case "mem_in_copy"      => checkBool(dut.io.memIn.copy.toBoolean, expected, cycle, "mem_in_copy")
                case "mem_in_bc_rd"     => checkBool(dut.io.memIn.bcRd.toBoolean, expected, cycle, "mem_in_bc_rd")
                case "mem_in_getstatic" => checkBool(dut.io.memIn.getstatic.toBoolean, expected, cycle, "mem_in_getstatic")
                case "mem_in_putstatic" => checkBool(dut.io.memIn.putstatic.toBoolean, expected, cycle, "mem_in_putstatic")
                case "mem_in_cinval"    => checkBool(dut.io.memIn.cinval.toBoolean, expected, cycle, "mem_in_cinval")

                // MemoryControl sub-signal (Bits)
                case "mem_in_bcopd" => checkBits(dut.io.memIn.bcopd.toLong, expected, cycle, "mem_in_bcopd")

                case other => None  // Unknown signal - skip
              }

              error.foreach { msg =>
                passed = false
                failureMsg = msg
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
    test(s"decode_${tc.name}") {
      println(s"Running test: ${tc.name}")
      tc.description.foreach(d => println(s"  Description: $d"))
      runTestCase(tc)
      println(s"  PASSED")
    }
  }

  // Summary test to verify all test cases loaded
  test("decode_verify_test_vector_count") {
    println(s"Module: ${testVectors.module}")
    println(s"Version: ${testVectors.version}")
    println(s"Total test cases: ${testVectors.testCases.length}")
    assert(testVectors.testCases.length == 59,
      s"Expected 59 test cases, got ${testVectors.testCases.length}")
  }
}
