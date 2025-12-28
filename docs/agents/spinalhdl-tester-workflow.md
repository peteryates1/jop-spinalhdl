# SpinalHDL Tester Agent Workflow

## Role
**Implement and maintain** native SpinalHDL/SpinalSim test suite using the **same JSON test vectors** established by vhdl-tester-workflow. This agent **executes** the test porting, **validates** behavioral equivalence, and **debugs** test failures - ensuring SpinalHDL implementation matches the golden standard.

## Technologies
- Scala 2.13+
- ScalaTest
- SpinalSim (SpinalHDL's simulation framework)
- Verilator backend
- Play JSON (for test vector loading)

## Responsibilities

### 1. Implement ScalaTest Suite Using Golden Test Vectors
- **Port** Python CocoTB tests to Scala ScalaTest
- **Use** the same JSON test vectors from vhdl-tester-workflow (no duplication)
- **Maintain** exact test case equivalence
- **Execute** tests via SpinalSim
- **Validate** same coverage as CocoTB suite

### 2. Create Native SpinalHDL Test Infrastructure
- **Implement** idiomatic Scala tests
- **Leverage** SpinalHDL's simulation features (waveforms, assertions)
- **Build** reusable test utilities and helpers
- **Execute** performance benchmarking

### 3. Validate Test Parity and Debug Failures
- **Verify** same test vectors used (no duplication)
- **Compare** results with CocoTB golden standard
- **Track** coverage equivalence
- **Debug** test failures (implementation vs test issues)
- **Integrate** with CI/CD

## Workflow Template

### Input Artifacts (from vhdl-tester-workflow)
```
verification/test-vectors/modules/<module>.json    # Golden test vectors (SHARED with CocoTB)
verification/cocotb/tests/test_<module>.py         # CocoTB test structure to port
core/spinalhdl/src/main/scala/jop/pipeline/<Module>.scala  # SpinalHDL implementation to test
docs/verification/modules/<module>-analysis.md     # Module documentation (if exists)
```

**Critical:** Test vectors are **SHARED** between CocoTB and ScalaTest - both use the same JSON files from vhdl-tester-workflow. Do NOT duplicate test vectors.

### Process Steps

#### Step 0: Debug ScalaTest/SpinalSim Infrastructure

**When to use:** When ScalaTest suite won't compile, tests won't run, or SpinalSim has issues. This step focuses on test infrastructure problems, not behavioral test failures (use Step 5 for test failures).

**Common Issues:**

1. **Build/Dependency Problems**
   - sbt configuration errors
   - Missing Verilator or wrong version
   - ScalaTest or SpinalHDL version conflicts
   - Play JSON library issues for test vector loading

2. **SpinalSim Configuration Issues**
   - Verilator compilation failures
   - Waveform generation not working
   - Clock domain configuration errors
   - Workspace directory permissions

3. **Test Vector Loading Problems**
   - JSON file path resolution issues
   - Parsing errors (malformed JSON)
   - Type conversion issues (String to Int, hex parsing)
   - Missing test vector files

4. **Runtime Issues**
   - Simulation doesn't start
   - Tests hang indefinitely
   - Memory issues with large simulations
   - Waveform files not generated

**Debugging Process:**

```bash
# Step 0.1: Verify build environment
sbt --version  # Should be 1.9+
verilator --version  # Should be 4.0+
scala --version  # Should be 2.13+

# Step 0.2: Clean build
sbt clean
rm -rf simWorkspace/
rm -rf target/

# Step 0.3: Test basic compilation
sbt compile
sbt test:compile

# Step 0.4: Run single simple test
sbt "testOnly jop.util.TestVectorLoaderSpec"
```

**Common Fixes:**

```scala
// ISSUE: Can't find test vectors
// WRONG: Relative path from test file
val source = Source.fromFile("../../test-vectors/modules/mul.json")

// CORRECT: Relative path from project root (where sbt runs)
val source = Source.fromFile("verification/test-vectors/modules/mul.json")
```

```scala
// ISSUE: Verilator compilation fails
// Add to build.sbt:
fork := true
javaOptions += "-Xmx4G"  // Increase memory for large designs
```

```scala
// ISSUE: Tests hang
// Add timeout to SpinalSim config
val simConfig = SimConfig
  .withWave
  .withVerilator
  .workspacePath("simWorkspace/Module")
  .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(50 MHz)))

// In test, add watchdog
dut.clockDomain.forkStimulus(period = 10)
fork {
  dut.clockDomain.waitRisingEdge(10000)
  simFailure("Test timeout after 10000 cycles")
}
```

```scala
// ISSUE: Waveform not generating
// Ensure wave file path is accessible
val simConfig = SimConfig
  .withWave
  .withConfig(SpinalConfig(
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  ))
  .workspacePath("simWorkspace/Module")

simConfig.compile(new Module(config)).doSim { dut =>
  // Test code
}
// Waveform will be at: simWorkspace/Module/test.vcd
```

**Deliverables:**
- Working ScalaTest infrastructure
- Test vectors loading successfully
- SpinalSim compiling and running
- Waveforms generating (if enabled)

**When to Move to Step 1:**
Once basic test infrastructure works (tests compile and run, even if they fail assertions), proceed to Step 1 to port actual tests.

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

#### Step 2: Load Test Vectors from Golden Standard (JSON)

**Critical:** These are the **SAME** test vectors used by CocoTB from vhdl-tester-workflow. Load from the shared JSON files - do NOT duplicate or recreate test vectors.

```scala
// Template: verification/scalatest/src/test/scala/jop/util/TestVectorLoader.scala

package jop.util

import play.api.libs.json._
import scala.io.Source

case class Signal(name: String, value: String)

case class InputCycle(
  cycle: Int,
  signals: Map[String, String]
)

case class ExpectedOutput(
  cycle: Int,
  signals: Map[String, String]
)

case class TestCase(
  name: String,
  @JsonProperty("type") testType: String,
  description: String,
  tags: Seq[String],
  inputs: Seq[InputCycle],
  @JsonProperty("expected_outputs") expectedOutputs: Seq[ExpectedOutput],
  cycles: Int,
  enabled: Option[Boolean] = Some(true)
)

case class TestVectorFile(
  module: String,
  version: String,
  description: String,
  @JsonProperty("test_cases") testCases: Seq[TestCase]
)

object TestVectorLoader {
  implicit val inputCycleReads: Reads[InputCycle] = Json.reads[InputCycle]
  implicit val expectedOutputReads: Reads[ExpectedOutput] = Json.reads[ExpectedOutput]
  implicit val testCaseReads: Reads[TestCase] = Json.reads[TestCase]
  implicit val testVectorFileReads: Reads[TestVectorFile] = Json.reads[TestVectorFile]

  /**
   * Load test vectors from SHARED JSON file
   * Path is relative to project root where sbt runs
   */
  def load(module: String): Seq[TestCase] = {
    val vectorFile = s"verification/test-vectors/modules/$module.json"
    val source = Source.fromFile(vectorFile)
    try {
      val json = Json.parse(source.mkString)
      val file = json.as[TestVectorFile]

      // Filter to enabled tests only
      file.testCases.filter(_.enabled.getOrElse(true))
    } catch {
      case e: Exception =>
        throw new RuntimeException(s"Failed to load test vectors from $vectorFile", e)
    } finally {
      source.close()
    }
  }

  /**
   * Parse value string (0x prefix for hex, otherwise decimal)
   * Returns None for don't-care values
   */
  def parseValue(valueStr: String): Option[BigInt] = {
    val trimmed = valueStr.trim

    // Don't-care value
    if (trimmed.startsWith("0xX") || trimmed.startsWith("0XX")) {
      None
    }
    // Hexadecimal
    else if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
      Some(BigInt(trimmed.substring(2), 16))
    }
    // Binary
    else if (trimmed.startsWith("0b") || trimmed.startsWith("0B")) {
      Some(BigInt(trimmed.substring(2), 2))
    }
    // Decimal
    else {
      Some(BigInt(trimmed))
    }
  }
}
```

**Validation:**

```scala
// Verify we're using the SAME vectors as CocoTB
test("verify using shared test vectors") {
  val vectors = TestVectorLoader.load("mul")

  // Should load from verification/test-vectors/modules/mul.json
  // This is the SAME file CocoTB uses
  assert(vectors.nonEmpty, "Test vectors should load")

  // Verify against known test from vhdl-tester-workflow
  val maxOverflow = vectors.find(_.name == "multiply_max_overflow")
  assert(maxOverflow.isDefined, "Should have multiply_max_overflow test")

  // Verify structure matches CocoTB expectations
  maxOverflow.foreach { tc =>
    assert(tc.expectedOutputs.nonEmpty)
    assert(tc.cycles > 0)
  }
}
```

**Deliverables:**
- Test vector loader utility loading from **SHARED JSON files**
- Validation that vectors match CocoTB
- **NO duplicate test vectors** (both use same files)

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

#### Step 5: Validate Test Parity and Debug Failures

**This is critical validation.** ScalaTest must produce **identical** results to CocoTB since both use the same test vectors and test the same implementation.

**Process:**

```bash
# Step 5.1: Run CocoTB tests (golden standard)
cd verification/cocotb
make test_mul 2>&1 | tee cocotb_results.log

# Note the results:
# ** TESTS=5 PASS=5 FAIL=0 SKIP=0 **

# Step 5.2: Run ScalaTest tests
cd ../../verification/scalatest
sbt "testOnly jop.pipeline.MulSpec" 2>&1 | tee scalatest_results.log

# Step 5.3: Compare results
# Both should have same number of passing/failing tests
```

**Test Parity Validation:**

```scala
// Template: verification/scalatest/src/test/scala/jop/util/TestParitySpec.scala

package jop.util

import org.scalatest.funsuite.AnyFunSuite

/**
 * Verify that ScalaTest uses SAME test vectors as CocoTB
 * and produces SAME results
 */
class TestParitySpec extends AnyFunSuite {

  test("verify using shared JSON test vectors") {
    // Load test vectors (same file CocoTB uses)
    val mulVectors = TestVectorLoader.load("mul")

    // Verify we loaded vectors successfully
    assert(mulVectors.nonEmpty, "Should load test vectors from shared JSON")

    // Verify specific test cases exist (same as CocoTB)
    val testNames = mulVectors.map(_.name).toSet
    assert(testNames.contains("multiply_5_times_3"))
    assert(testNames.contains("multiply_max_overflow"))
    assert(testNames.contains("multiply_alternating_bits"))

    println(s"Loaded ${mulVectors.size} test vectors from shared JSON")
  }

  test("verify test count matches CocoTB") {
    val mulVectors = TestVectorLoader.load("mul")

    // Should match CocoTB test count
    // CocoTB has 16 test vectors for mul
    assert(mulVectors.size == 16,
      s"Expected 16 test vectors (matching CocoTB), got ${mulVectors.size}")
  }

  test("verify no duplicate test vectors") {
    // Ensure we're not creating our own vectors
    // Should ONLY load from verification/test-vectors/
    import java.io.File

    val scalaTestDir = new File("verification/scalatest/src/test/scala")
    val jsonFiles = findFiles(scalaTestDir, ".json")

    assert(jsonFiles.isEmpty,
      "ScalaTest should NOT have its own JSON test vectors - use shared ones!")
  }

  private def findFiles(dir: File, extension: String): Seq[File] = {
    if (!dir.exists()) Seq.empty
    else {
      val files = dir.listFiles().toSeq
      val matching = files.filter(_.getName.endsWith(extension))
      val fromSubdirs = files.filter(_.isDirectory).flatMap(findFiles(_, extension))
      matching ++ fromSubdirs
    }
  }
}
```

**When Tests Fail - Debugging Decision Tree:**

```
ScalaTest fails → What type of failure?
                ↓
                ├─ Infrastructure failure (won't compile, won't run)
                │  → Use Step 0 (Debug Infrastructure)
                │
                ├─ Test vector loading failure
                │  → Check JSON file exists and is valid
                │  → Use Step 0 or check with vhdl-tester-workflow
                │
                ├─ Behavioral failure (wrong result at specific cycle)
                │  → Compare with CocoTB result for same test
                │  │
                │  ├─ CocoTB PASSES, ScalaTest FAILS
                │  │  → SpinalSim issue or test porting bug
                │  │  → Debug SpinalHDL test implementation (this workflow)
                │  │
                │  ├─ CocoTB FAILS, ScalaTest FAILS (same way)
                │  │  → SpinalHDL implementation bug
                │  │  → Send to spinalhdl-developer-workflow
                │  │
                │  └─ CocoTB FAILS, ScalaTest PASSES
                │     → Test vector issue or CocoTB setup problem
                │     → Check with vhdl-tester-workflow
                │
                └─ All tests fail
                   → Likely SpinalHDL implementation is broken
                   → Send to spinalhdl-developer-workflow
```

**Debugging Behavioral Differences:**

```scala
test("debug specific failure - isolated") {
  // When a test fails, isolate it
  val simConfig = SimConfig.withWave.withVerilator

  simConfig.compile(new Mul(MulConfig())).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Reproduce exact test case that failed
    dut.io.ain #= 0xFFFFFFFF
    dut.io.bin #= 0xFFFFFFFF
    dut.io.wr #= true
    dut.clockDomain.waitRisingEdge(1)

    dut.io.wr #= false

    // Check cycle-by-cycle and compare with CocoTB log
    for (cycle <- 2 to 20) {
      dut.clockDomain.waitRisingEdge(1)
      val dout = dut.io.dout.toBigInt
      println(f"Cycle $cycle: dout = 0x${dout.toString(16)}")

      // At cycle 18, CocoTB expects 0x1
      if (cycle == 18) {
        val expected = BigInt(0x1)
        if (dout != expected) {
          println(s"MISMATCH at cycle $cycle!")
          println(s"  CocoTB (expected): 0x${expected.toString(16)}")
          println(s"  ScalaTest (actual): 0x${dout.toString(16)}")
          println("Check waveform: simWorkspace/Mul/test.vcd")
          fail("Behavioral difference - check SpinalHDL implementation")
        }
      }
    }
  }
}
```

**Deliverables:**
- Parity verification tests passing
- Test count matches CocoTB exactly
- Results match CocoTB exactly (same pass/fail)
- Documentation of any differences (with justification)
- Debug logs for any failures

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

## Success Criteria (Module Completion)

For each module, ALL criteria must be met:

- [ ] All CocoTB tests ported to ScalaTest
- [ ] **Using SAME JSON test vectors** (no duplication)
- [ ] **Test count matches CocoTB exactly**
- [ ] **All tests passing (FAIL=0)**
- [ ] **Results match CocoTB exactly** (same pass/fail for each test)
- [ ] Coverage equivalent to CocoTB suite
- [ ] Waveform generation working
- [ ] Performance benchmarks established
- [ ] Test parity validation passing
- [ ] CI/CD integration complete

## Integration with vhdl-tester-workflow

**Critical:** This agent **depends on** vhdl-tester-workflow outputs and **validates** spinalhdl-developer-workflow outputs:

```
vhdl-tester-workflow → Creates test vectors + CocoTB tests
                    ↓
                (provides)
                    ↓
        JSON test vectors (SHARED)
                    ↓
                (used by)
                    ↓
    ┌───────────────┴───────────────┐
    ↓                               ↓
CocoTB (tests VHDL)          ScalaTest (tests SpinalHDL)
    ↓                               ↓
Golden standard             Must match golden standard
```

**Workflow:**
1. vhdl-tester-workflow creates JSON test vectors
2. vhdl-tester-workflow validates vectors against original VHDL (golden standard)
3. spinalhdl-developer implements SpinalHDL port
4. spinalhdl-tester (THIS) creates ScalaTest using SAME vectors
5. ScalaTest validates SpinalHDL matches golden standard
6. **Both CocoTB and ScalaTest must produce identical results**

## Handoff to Next Agent

### From vhdl-tester-workflow (inputs):
- **Test vectors** (`verification/test-vectors/modules/<module>.json`) - SHARED
- CocoTB test structure (for porting reference)
- Golden standard test results
- Expected behavior documentation

### From spinalhdl-developer (inputs):
- SpinalHDL implementation to test
- Generated VHDL (for reference)
- Module documentation

### To spinalhdl-developer (outputs - when tests fail):
- **Test failures** indicating SpinalHDL bugs
- Behavioral differences from golden standard
- Cycle-by-cycle comparison logs
- Waveforms showing differences

### To reviewer (outputs):
- Complete ScalaTest suite
- **Test parity verification** (matching CocoTB)
- Coverage reports
- Performance benchmarks
- Test documentation

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
