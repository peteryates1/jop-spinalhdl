package jop.util

import play.api.libs.json._
import scala.io.Source
import java.nio.file.{Path, Paths}

/**
 * Test case loaded from JSON
 */
case class TestCase(
  name: String,
  testType: String,
  description: Option[String],
  tags: Seq[String],
  enabled: Option[Boolean],
  initialState: Map[String, String],
  inputs: Option[Seq[CycleInputs]],
  expectedOutputs: Option[Seq[CycleOutputs]],
  expectedState: Map[String, String],
  cycles: Int,
  assertions: Option[Seq[Assertion]]
)

case class CycleInputs(cycle: Int, signals: Map[String, String])

case class CycleOutputs(cycle: Int, signals: Map[String, String])

case class Assertion(
  cycle: Int,
  signal: String,
  operator: String,
  value: String,
  message: Option[String]
)

case class TestVectorMetadata(
  author: Option[String],
  created: Option[String],
  modified: Option[String],
  tags: Option[Seq[String]],
  reference: Option[String]
)

case class TestVectors(
  module: String,
  version: String,
  description: Option[String],
  metadata: Option[TestVectorMetadata],
  testCases: Seq[TestCase]
)

/**
 * Test vector loader for ScalaTest
 *
 * Loads test vectors from JSON files shared with CocoTB tests.
 */
object TestVectorLoader {

  // JSON readers
  implicit val cycleInputsReads: Reads[CycleInputs] = Json.reads[CycleInputs]
  implicit val cycleOutputsReads: Reads[CycleOutputs] = Json.reads[CycleOutputs]
  implicit val assertionReads: Reads[Assertion] = Json.reads[Assertion]
  implicit val testCaseReads: Reads[TestCase] = Json.reads[TestCase]
  implicit val metadataReads: Reads[TestVectorMetadata] = Json.reads[TestVectorMetadata]
  implicit val testVectorsReads: Reads[TestVectors] = Json.reads[TestVectors]

  /**
   * Load test vectors for a module
   *
   * @param module Module name (e.g., "bytecode-fetch")
   * @param vectorsDir Base directory for test vectors
   * @return TestVectors object
   */
  def load(
    module: String,
    vectorsDir: Path = Paths.get("verification/test-vectors")
  ): TestVectors = {
    val vectorFile = vectorsDir.resolve(s"modules/$module.json")
    val source = Source.fromFile(vectorFile.toFile)
    try {
      val json = Json.parse(source.mkString)
      json.as[TestVectors]
    } finally {
      source.close()
    }
  }

  /**
   * Get filtered test cases
   *
   * @param module Module name
   * @param testType Optional test type filter
   * @param tags Optional tag filters (test must have at least one)
   * @param enabledOnly Only return enabled tests
   * @return Sequence of test cases
   */
  def getTestCases(
    module: String,
    testType: Option[String] = None,
    tags: Seq[String] = Seq.empty,
    enabledOnly: Boolean = true
  ): Seq[TestCase] = {
    val vectors = load(module)
    var filtered = vectors.testCases

    // Filter by enabled
    if (enabledOnly) {
      filtered = filtered.filter(_.enabled.getOrElse(true))
    }

    // Filter by type
    testType.foreach { tt =>
      filtered = filtered.filter(_.testType == tt)
    }

    // Filter by tags
    if (tags.nonEmpty) {
      filtered = filtered.filter { tc =>
        tags.exists(tag => tc.tags.contains(tag))
      }
    }

    filtered
  }

  /**
   * Parse value string to integer
   *
   * @param valueStr Value string (0xHEX, 0bBIN, or decimal)
   * @return Optional integer (None for don't-care)
   */
  def parseValue(valueStr: String): Option[Int] = {
    if (valueStr == null) {
      return None
    }

    val trimmed = valueStr.trim

    // Don't care
    if (trimmed.startsWith("0xX") || trimmed.startsWith("0XX")) {
      return None
    }

    // Hexadecimal
    if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
      return Some(Integer.parseInt(trimmed.substring(2), 16))
    }

    // Binary
    if (trimmed.startsWith("0b") || trimmed.startsWith("0B")) {
      return Some(Integer.parseInt(trimmed.substring(2), 2))
    }

    // Decimal
    Some(trimmed.toInt)
  }

  /**
   * Get module information
   *
   * @param module Module name
   * @return Module metadata
   */
  def getModuleInfo(module: String): (String, String, Option[String]) = {
    val vectors = load(module)
    (vectors.module, vectors.version, vectors.description)
  }
}
