# SpinalHDL Tester Agent Workflow

## Role
Create and maintain native SpinalHDL/SpinalSim test suite, porting tests from CocoTB and ensuring equivalence.

## Technologies
- Scala 2.13+
- ScalaTest
- SpinalSim (SpinalHDL's simulation framework)
- Verilator backend

## Responsibilities

### 1. Port CocoTB Tests to ScalaTest
- Translate Python CocoTB tests to Scala ScalaTest
- Maintain test case equivalence
- Use SpinalSim for simulation
- Ensure same coverage as CocoTB suite

### 2. Create Native SpinalHDL Tests
- Write idiomatic Scala tests
- Leverage SpinalHDL's simulation features
- Create reusable test utilities
- Performance benchmarking

### 3. Maintain Test Parity
- Verify same test vectors used
- Compare results with CocoTB
- Track coverage equivalence
- CI/CD integration

## Workflow Template

### Input Artifacts
```
verification/cocotb/tests/test_<module>.py
verification/cocotb/fixtures/vectors_<module>.py
core/spinalhdl/src/main/scala/jop/pipeline/<Module>.scala
docs/verification/modules/<module>-analysis.md
```

### Process Steps

#### Step 1: Analyze CocoTB Test

Read and understand the CocoTB test structure:
- Test cases and scenarios
- Setup and teardown
- Clock and reset handling
- Assertions and expectations
- Test vectors used

**Deliverables:**
- Test porting notes
- Identified test cases to port

#### Step 2: Load Test Vectors from JSON

Test vectors are loaded from shared JSON files (see `docs/test-vectors/test-vector-format.md`).

```scala
// Template: verification/scalatest/src/test/scala/jop/util/TestVectorLoader.scala

package jop.util

import play.api.libs.json._
import scala.io.Source

case class TestCase(
  name: String,
  testType: String,
  description: Option[String],
  tags: Seq[String],
  initialState: Map[String, String],
  expectedState: Map[String, String],
  cycles: Int
)

object TestVectorLoader {
  implicit val testCaseReads: Reads[TestCase] = Json.reads[TestCase]

  def load(module: String): Seq[TestCase] = {
    val source = Source.fromFile(s"verification/test-vectors/modules/$module.json")
    try {
      val json = Json.parse(source.mkString)
      (json \ "test_cases").as[Seq[TestCase]]
    } finally {
      source.close()
    }
  }

  def parseValue(valueStr: String): Option[Int] = {
    if (valueStr.startsWith("0x")) {
      Some(Integer.parseInt(valueStr.substring(2), 16))
    } else {
      Some(valueStr.toInt)
    }
  }
}
```

**Deliverables:**
- Test vector loader utility
- No duplicate test vectors (shared with CocoTB)

#### Step 3: Create Test Utilities

```scala
// Template: verification/scalatest/src/test/scala/jop/util/TestHelpers.scala

package jop.util

import spinal.core._
import spinal.core.sim._

object TestHelpers {

  /**
   * Apply reset and wait for stabilization
   */
  def resetDut(reset: Bool, clock: ClockDomain, cycles: Int = 5): Unit = {
    reset #= true
    clock.waitRisingEdge(cycles)
    reset #= false
    clock.waitRisingEdge(1)
  }

  /**
   * Compare two values and report detailed error
   */
  def assertEqualWithContext(actual: Int, expected: Int, name: String, cycle: Int): Unit = {
    assert(
      actual == expected,
      s"Cycle $cycle: $name mismatch - expected: 0x${expected.toHexString}, actual: 0x${actual.toHexString}"
    )
  }

  /**
   * Wait for a condition with timeout
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
   */
  def snapshotState(prefix: String, signals: Map[String, () => BigInt]): Unit = {
    println(s"=== $prefix ===")
    signals.foreach { case (name, getValue) =>
      println(f"  $name%-15s: 0x${getValue().toString(16)}")
    }
  }
}
```

**Deliverables:**
- Reusable test utilities
- Documentation

#### Step 4: Port Tests to ScalaTest

```scala
// Template: verification/scalatest/src/test/scala/jop/pipeline/<Module>Spec.scala

package jop.pipeline

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import jop.fixtures.<Module>Vectors._
import jop.util.TestHelpers._

class <Module>Spec extends AnyFunSuite {

  // SpinalSim configuration
  val simConfig = SimConfig
    .withWave           // Generate waveforms
    .withVerilator      // Use Verilator backend
    .workspacePath("simWorkspace/<Module>")

  test("reset behavior") {
    simConfig.compile(new <Module>(<Module>Config())).doSim { dut =>
      // Fork clock generation
      dut.clockDomain.forkStimulus(period = 10)

      // Apply reset
      resetDut(dut.io.reset, dut.clockDomain)

      // Verify reset state
      dut.clockDomain.waitRisingEdge(1)
      assertEqualWithContext(
        dut.io.tos.toInt,
        0,
        "tos",
        0
      )
      assertEqualWithContext(
        dut.io.nos.toInt,
        0,
        "nos",
        0
      )
    }
  }

  test("reset test vectors") {
    resetVectors.foreach { vector =>
      simConfig.compile(new <Module>(<Module>Config())).doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        // Set initial state
        dut.io.tos #= vector.input.tos
        dut.io.nos #= vector.input.nos
        dut.io.sp #= vector.input.sp
        dut.clockDomain.waitRisingEdge(1)

        // Apply reset
        resetDut(dut.io.reset, dut.clockDomain)

        // Wait expected cycles
        dut.clockDomain.waitRisingEdge(vector.cycles)

        // Verify expected state
        assertEqualWithContext(
          dut.io.tos.toInt,
          vector.expected.tos,
          s"${vector.name}.tos",
          vector.cycles
        )
        assertEqualWithContext(
          dut.io.nos.toInt,
          vector.expected.nos,
          s"${vector.name}.nos",
          vector.cycles
        )
      }
    }
  }

  test("microcode instruction vectors") {
    microcodeVectors.foreach { vector =>
      simConfig.compile(new <Module>(<Module>Config())).doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        resetDut(dut.io.reset, dut.clockDomain)

        // Set initial state
        dut.io.tos #= vector.input.tos
        dut.io.nos #= vector.input.nos
        dut.io.sp #= vector.input.sp

        // Apply microcode (if applicable)
        // dut.io.microcodeIn #= vector.microcode

        // Simulate cycles
        for (cycle <- 1 to vector.cycles) {
          dut.clockDomain.waitRisingEdge(1)

          // Can verify intermediate states here if needed
        }

        // Verify final state
        assertEqualWithContext(
          dut.io.tos.toInt,
          vector.expected.tos,
          s"${vector.name}.tos",
          vector.cycles
        )
      }
    }
  }

  test("edge cases") {
    // Stack overflow
    simConfig.compile(new <Module>(<Module>Config())).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      resetDut(dut.io.reset, dut.clockDomain)

      // Overflow stack
      // ... test implementation
    }

    // Stack underflow
    // ...
  }

  test("cycle-accurate behavior") {
    simConfig.compile(new <Module>(<Module>Config())).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      resetDut(dut.io.reset, dut.clockDomain)

      // Cycle-by-cycle verification
      val cycles = 10
      for (cycle <- 1 to cycles) {
        // Set inputs
        // ...

        dut.clockDomain.waitRisingEdge(1)

        // Verify outputs match expected for this cycle
        // ...
      }
    }
  }
}
```

**Deliverables:**
- Complete ScalaTest suite
- Waveform output configuration
- Test documentation

#### Step 5: Verify Test Parity

```scala
// Template: verification/scalatest/src/test/scala/jop/util/TestParity.scala

package jop.util

import org.scalatest.funsuite.AnyFunSuite
import jop.fixtures._

/**
 * Verify that Scala test vectors match CocoTB test vectors
 */
class TestParitySpec extends AnyFunSuite {

  test("verify vector count matches CocoTB") {
    // Read CocoTB vector counts from JSON export
    val cocotbCounts = Map(
      "reset" -> 5,
      "microcode" -> 120,
      "edge_cases" -> 15
    )

    assert(<Module>Vectors.resetVectors.size == cocotbCounts("reset"))
    assert(<Module>Vectors.microcodeVectors.size == cocotbCounts("microcode"))
  }

  test("verify test coverage matches CocoTB") {
    // Load CocoTB coverage report
    // Compare with ScalaTest coverage
    // Assert equivalence
  }
}
```

**Deliverables:**
- Parity verification tests
- Coverage comparison reports
- Documentation of differences (if any)

#### Step 6: Performance Benchmarking

```scala
// Template: verification/scalatest/src/test/scala/jop/benchmark/<Module>Benchmark.scala

package jop.benchmark

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import jop.pipeline._

class <Module>Benchmark extends AnyFunSuite {

  test("simulation performance") {
    val config = <Module>Config()
    val simConfig = SimConfig.withVerilator

    val startTime = System.currentTimeMillis()

    simConfig.compile(new <Module>(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Run 10000 cycles
      dut.clockDomain.waitRisingEdge(10000)
    }

    val duration = System.currentTimeMillis() - startTime
    val cyclesPerSecond = 10000.0 / (duration / 1000.0)

    println(f"Simulated 10000 cycles in ${duration}ms ($cyclesPerSecond%.0f cycles/sec)")

    // Can assert performance requirements
    assert(cyclesPerSecond > 1000, "Simulation too slow")
  }
}
```

**Deliverables:**
- Performance benchmarks
- Comparison with CocoTB performance
- Performance regression tracking

### Output Artifacts

```
verification/scalatest/
├── src/
│   └── test/
│       └── scala/
│           └── jop/
│               ├── pipeline/
│               │   ├── BytecodeFetchSpec.scala
│               │   ├── MicrocodeFetchSpec.scala
│               │   ├── MicrocodeDecodeSpec.scala
│               │   └── ExecuteSpec.scala
│               ├── fixtures/
│               │   ├── BytecodeFetchVectors.scala
│               │   ├── MicrocodeFetchVectors.scala
│               │   └── ...
│               ├── util/
│               │   ├── TestHelpers.scala
│               │   └── TestParitySpec.scala
│               ├── benchmark/
│               │   └── <Module>Benchmark.scala
│               └── integration/
│                   └── JopCoreSpec.scala
├── simWorkspace/          # Generated simulation files
├── test-results/          # Test reports
├── build.sbt
└── README.md

docs/verification/
├── scalatest-porting.md
├── test-parity-report.md
└── performance-benchmarks.md
```

## Build Configuration

```scala
// Add to build.sbt
libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.2.17" % Test
)

// Test configuration
Test / parallelExecution := false
Test / testOptions += Tests.Argument("-oD")  // Show durations
```

## Success Criteria

- [ ] All CocoTB tests ported to ScalaTest
- [ ] Test vector parity verified
- [ ] Coverage equivalent to CocoTB suite
- [ ] All tests passing
- [ ] Waveform generation working
- [ ] Performance benchmarks established
- [ ] CI/CD integration complete

## Handoff to Next Agent

### To reviewer:
- Complete test suite
- Coverage reports
- Parity verification results
- Performance benchmarks
- Test documentation

### To spinalhdl-developer:
- Test failures or bugs found
- Performance issues
- Suggested improvements

## Test Execution Commands

```bash
# Run all tests
sbt test

# Run specific test suite
sbt "testOnly jop.pipeline.<Module>Spec"

# Run with waveform generation
sbt "testOnly jop.pipeline.<Module>Spec" -Dwave=true

# Run benchmarks
sbt "testOnly jop.benchmark.*"

# Generate coverage report
sbt clean coverage test coverageReport

# Run tests continuously (watch mode)
sbt ~test
```

## Waveform Analysis

```bash
# Generated waveforms are in simWorkspace/<Module>/
# Open with GTKWave
gtkwave simWorkspace/<Module>/test.vcd

# Or use other waveform viewers
# Surfer, ModelSim, etc.
```

## CI/CD Integration

```yaml
# .github/workflows/scalatest.yml
name: ScalaTest Suite

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '11'
      - name: Install Verilator
        run: |
          sudo apt-get update
          sudo apt-get install -y verilator
      - name: Run tests
        run: sbt test
      - name: Upload coverage
        uses: codecov/codecov-action@v3
```

## Notes

- Keep tests deterministic and reproducible
- Use meaningful test names
- Document any deviations from CocoTB tests
- Generate waveforms for debugging
- Monitor simulation performance
- Version control test results and benchmarks
