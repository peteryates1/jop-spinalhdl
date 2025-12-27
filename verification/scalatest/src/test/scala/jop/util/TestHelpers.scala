package jop.util

import spinal.core._
import spinal.core.sim._

/**
 * Helper utilities for SpinalHDL tests
 */
object TestHelpers {

  /**
   * Apply reset and wait for stabilization
   *
   * @param reset Reset signal
   * @param clock Clock domain
   * @param cycles Number of cycles to hold reset
   */
  def resetDut(reset: Bool, clock: ClockDomain, cycles: Int = 5): Unit = {
    reset #= true
    clock.waitRisingEdge(cycles)
    reset #= false
    clock.waitRisingEdge(1)
  }

  /**
   * Compare two values and report detailed error
   *
   * @param actual Actual value
   * @param expected Expected value
   * @param name Signal name
   * @param cycle Current cycle number
   */
  def assertEqualWithContext(
    actual: Int,
    expected: Int,
    name: String,
    cycle: Int
  ): Unit = {
    assert(
      actual == expected,
      s"Cycle $cycle: $name mismatch - expected: 0x${expected.toHexString}, " +
      s"actual: 0x${actual.toHexString}"
    )
  }

  /**
   * Wait for a condition with timeout
   *
   * @param clock Clock domain
   * @param condition Condition to wait for
   * @param timeout Maximum cycles to wait
   * @param errorMsg Error message if timeout
   */
  def waitForCondition(
    clock: ClockDomain,
    condition: => Boolean,
    timeout: Int = 100,
    errorMsg: String = "Timeout waiting for condition"
  ): Unit = {
    var cycles = 0
    while (!condition && cycles < timeout) {
      clock.waitRisingEdge(1)
      cycles += 1
    }
    assert(condition, s"$errorMsg (waited $cycles cycles)")
  }

  /**
   * Snapshot DUT state for debugging
   *
   * @param prefix Prefix for output
   * @param signals Map of signal names to value getters
   */
  def snapshotState(prefix: String, signals: Map[String, () => BigInt]): Unit = {
    println(s"=== $prefix ===")
    signals.foreach { case (name, getValue) =>
      println(f"  $name%-15s: 0x${getValue().toString(16)}")
    }
  }

  /**
   * Compare against expected value with operator
   *
   * @param actual Actual value
   * @param operator Comparison operator
   * @param expected Expected value
   * @param message Error message
   */
  def checkAssertion(
    actual: Int,
    operator: String,
    expected: Int,
    message: String
  ): Unit = {
    operator match {
      case "==" => assert(actual == expected, message)
      case "!=" => assert(actual != expected, message)
      case "<"  => assert(actual < expected, message)
      case ">"  => assert(actual > expected, message)
      case "<=" => assert(actual <= expected, message)
      case ">=" => assert(actual >= expected, message)
      case "&"  => assert((actual & expected) == expected, message)
      case "|"  => assert((actual | expected) != 0, message)
      case "^"  => assert((actual ^ expected) != 0, message)
      case _    => throw new IllegalArgumentException(s"Unknown operator: $operator")
    }
  }
}
