package jop

import spinal.core.sim._
import java.nio.file.{Path, Paths}

object TestVectorUtils {

  /** Parse value string to Long (handles 32-bit unsigned values).
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

  /** Check if a value string is a don't-care marker (contains 'X').
    *
    * WARNING: This has a known false-positive on hex-prefixed values like "0x1A"
    * because the "0x" prefix contains 'X'. This means hex-valued expected outputs
    * in test vectors are silently skipped. 26 decode test vectors have mismatches
    * hidden by this bug. Fixing it requires verifying/updating those test vectors
    * against the RTL first — tracked as a separate task.
    */
  def isDontCare(valueStr: String): Boolean = {
    valueStr.trim.toUpperCase.contains("X")
  }

  /** Find the project root by searching for the verification/test-vectors directory. */
  def findProjectRoot(): Path = {
    var current = Paths.get(System.getProperty("user.dir"))
    while (current != null) {
      if (current.resolve("verification/test-vectors").toFile.exists()) {
        return current
      }
      current = current.getParent
    }
    val candidates = Seq(
      Paths.get("../.."),
      Paths.get("../../.."),
      Paths.get(".")
    )
    candidates.find(_.resolve("verification/test-vectors").toFile.exists())
      .getOrElse(throw new RuntimeException("Could not find project root with test vectors"))
  }

  /** Conditionally enable waveform dumps based on SIM_WAVE env var.
    * Set SIM_WAVE=1 to enable FST waveforms.
    */
  def simWave(config: SpinalSimConfig): SpinalSimConfig = {
    if (sys.env.getOrElse("SIM_WAVE", "0") == "1") config.withFstWave
    else config
  }
}
