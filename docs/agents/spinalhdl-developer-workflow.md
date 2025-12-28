# SpinalHDL Developer Agent Workflow

## Role
**Implement and verify** the SpinalHDL reimplementation of the JOP core, maintaining cycle-accurate behavioral equivalence with the original VHDL. This agent **executes** the port, **runs** validation tests, and **debugs** behavioral differences - it does not merely advise.

## Technologies
- Scala 2.13+
- SpinalHDL
- sbt (build tool)
- CocoTB (for validation against golden standard)
- GHDL (for testing generated VHDL)
- Python (for test vector integration)

## Responsibilities

### 1. Implement VHDL to SpinalHDL Port
- **Translate** VHDL modules to SpinalHDL components
- **Maintain** signal-level and behavioral compatibility
- **Preserve** timing and pipeline structure
- **Implement** equivalent state machines
- **Verify** cycle-accurate equivalence

### 2. Generate and Validate HDL Output
- **Generate** VHDL for CocoTB validation against golden standard
- **Generate** Verilog for synthesis
- **Test** generated code against original VHDL behavior
- **Debug** and fix behavioral differences

### 3. Validate Against Golden Standard
- **Execute** CocoTB tests created by vhdl-tester-workflow
- **Compare** cycle-by-cycle behavior with original VHDL
- **Debug** test failures and behavioral mismatches
- **Iterate** until all tests pass

### 4. Create Configurable Architecture
- **Implement** parameterization for different FPGA targets
- **Support** memory configuration options
- **Enable/disable** features via configuration

## Workflow Template

### Input Artifacts (from vhdl-tester-workflow)
```
original/vhdl/core/<module>.vhd                    # Original VHDL to port
verification/test-vectors/modules/<module>.json     # Golden test vectors
verification/cocotb/tests/test_<module>.py          # CocoTB test suite
docs/verification/modules/<module>-analysis.md      # Module documentation (if exists)
```

### Process Steps

#### Step 0: Debug Generated VHDL Behavioral Differences

**When to use:** After generating VHDL from SpinalHDL, when CocoTB tests fail or behavior doesn't match the original VHDL. This step focuses on debugging **behavioral** differences, not infrastructure issues (use vhdl-tester-workflow Step 0 for infrastructure).

**Common Issues:**

1. **Signal Timing Differences**
   - SpinalHDL register initialization vs VHDL
   - Clock domain crossing issues
   - Combinational vs registered outputs
   - Reset behavior (sync vs async)

2. **State Machine Translation**
   - State encoding differences
   - Transition timing (same cycle vs next cycle)
   - Default/initial state handling
   - State machine optimization differences

3. **Arithmetic/Logic Differences**
   - Bit width mismatches causing truncation
   - Signed vs unsigned interpretation
   - Overflow/underflow behavior
   - SpinalHDL operator semantics vs VHDL

4. **Generated Code Issues**
   - SpinalHDL generating unexpected VHDL constructs
   - Signal naming making debugging difficult
   - Optimization changing behavior
   - Unreachable states or dead code

**Debugging Process:**

```scala
// Step 0.1: Generate VHDL with debug features
SpinalConfig(
  targetDirectory = "core/spinalhdl/generated",
  defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC),
  // Keep signal names readable
  defaultClockDomainFrequency = FixedFrequency(50 MHz),
  nameWhenByFile = false  // Keep hierarchical names
).generateVhdl(new MyModule(config))

// Step 0.2: Add debug signals
val debug = new Bundle {
  val stateDebug = out(state)
  val counterDebug = out(counter)
}
debug.stateDebug := state
debug.counterDebug := counter
```

```python
# Step 0.3: Compare cycle-by-cycle in CocoTB
@cocotb.test()
async def test_debug_comparison(dut):
    """Compare SpinalHDL vs original VHDL cycle-by-cycle"""

    # Load reference behavior from vhdl-tester results
    reference_file = "golden_outputs/module_cycle_trace.json"

    for cycle in range(100):
        await RisingEdge(dut.clk)

        # Compare each signal
        actual_state = int(dut.stateDebug.value)
        expected_state = reference_data[cycle]['state']

        if actual_state != expected_state:
            dut._log.error(f"Cycle {cycle}: state mismatch")
            dut._log.error(f"  SpinalHDL: {actual_state:#x}")
            dut._log.error(f"  Original:  {expected_state:#x}")

            # Dump last 5 cycles for context
            dump_context(dut, cycle-5, cycle)
            assert False
```

**Common Translation Fixes:**

```scala
// ISSUE: Different reset behavior
// VHDL: Asynchronous reset
// process(clk, reset)
// begin
//   if reset = '1' then
//     counter <= 0;
//   elsif rising_edge(clk) then
//     counter <= counter + 1;
//   end if;
// end process;

// WRONG SpinalHDL (synchronous reset)
val counter = Reg(UInt(8 bits)) init(0)
counter := counter + 1

// CORRECT SpinalHDL (async reset)
val asyncArea = new ClockingArea(
  ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(resetKind = ASYNC)
  )
) {
  val counter = Reg(UInt(8 bits)) init(0)
  counter := counter + 1
}
```

```scala
// ISSUE: Combinational vs registered output
// VHDL: Immediate combinational output
// dout <= std_logic_vector(p);

// WRONG (introduces 1-cycle delay)
io.dout := RegNext(p)

// CORRECT (combinational)
io.dout := p
```

```scala
// ISSUE: Bit width mismatch
// VHDL: prod := prod + a; (with natural overflow)

// WRONG (may not match VHDL overflow behavior)
val prod = UInt(32 bits)
prod := prod + a  // SpinalHDL might error on overflow

// CORRECT (explicit width handling)
val prod = UInt(32 bits)
prod := (prod.resize(33) + a.resize(33)).resize(32)  // Match VHDL truncation
```

**Deliverables:**
- Debugged SpinalHDL implementation matching VHDL behavior
- Generated VHDL that passes all CocoTB tests
- Documentation of any non-obvious translation decisions
- Cycle-accurate waveform comparison (if needed)

**Tools for Debugging:**
```bash
# Compare generated VHDL structure
diff -u original/vhdl/core/module.vhd core/spinalhdl/generated/Module.vhd

# Generate waveforms for both
ghdl -r module --wave=original.ghw
ghdl -r Module --wave=spinalhdl.ghw
gtkwave original.ghw spinalhdl.ghw  # Visual comparison

# Run CocoTB with detailed logging
COCOTB_LOG_LEVEL=DEBUG make test_module_spinalhdl
```

**When to Move to Step 1:**
Use Step 0 iteratively with Steps 1-3 when debugging. Once behavioral equivalence is established and all CocoTB tests pass, proceed to Step 4 (configuration) and Step 5 (integration).

#### Step 1: Module Translation

```scala
// Template: core/spinalhdl/src/main/scala/jop/pipeline/<Module>.scala

package jop.pipeline

import spinal.core._
import spinal.lib._

/**
 * <Module> - Brief description
 *
 * Ported from: original/vhdl/core/<module>.vhd
 *
 * Pipeline Stage: [fetch|decode|execute]
 * Latency: X cycles
 *
 * Inputs:
 *  - signal_name: description
 *
 * Outputs:
 *  - signal_name: description
 *
 * Registers:
 *  - reg_name: description
 */
case class <Module>Config(
  dataWidth: Int = 32,
  // ... configuration parameters
)

case class <Module>(config: <Module>Config) extends Component {
  val io = new Bundle {
    // Define ports matching VHDL entity
    val clk = in Bool()
    val reset = in Bool()
    // ... other signals
  }

  // No clock domain here - will be applied externally

  // Registers (matching VHDL)
  val stateReg = Reg(UInt(X bits)) init(0)

  // Combinational logic
  val nextState = UInt(X bits)

  // State machine / logic
  switch(stateReg) {
    is(STATE_IDLE) {
      // ...
    }
    is(STATE_ACTIVE) {
      // ...
    }
  }

  // Register updates
  stateReg := nextState
}

object <Module> {
  def main(args: Array[String]): Unit = {
    val config = <Module>Config()
    SpinalConfig(
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC)
    ).generateVhdl(new <Module>(config))
  }
}
```

**Deliverables:**
- `core/spinalhdl/src/main/scala/jop/pipeline/<Module>.scala`
- Inline documentation
- Configuration case class

#### Step 2: Verify Against VHDL

```scala
// Generate VHDL
sbt "runMain jop.pipeline.<Module>"

// Output to: core/spinalhdl/generated/<Module>.vhd
```

**Validation Checklist:**
- [ ] Generated VHDL compiles with GHDL
- [ ] Entity ports match original
- [ ] Signal names are traceable
- [ ] Reset behavior is equivalent
- [ ] Clock domain handling is correct

**Deliverables:**
- `core/spinalhdl/generated/<Module>.vhd`
- `docs/migration/<Module>-comparison.md`

#### Step 3: Validate Against Golden Standard (CocoTB Tests)

**This is the critical validation step.** The CocoTB tests created by vhdl-tester-workflow are your golden standard. Your SpinalHDL implementation must match the original VHDL behavior cycle-for-cycle.

**Process:**

```bash
# Step 3.1: Generate VHDL from SpinalHDL
cd core/spinalhdl
sbt "runMain jop.pipeline.<Module>"

# Step 3.2: Set up test environment
cd ../../verification/cocotb

# Update Makefile to test SpinalHDL-generated VHDL
# Add target for SpinalHDL version
cat >> Makefile << 'EOF'

# Test SpinalHDL-generated version
test_<module>_spinalhdl:
	@echo "Testing SpinalHDL-generated <module>..."
	@mkdir -p waveforms
	@cp ../../core/spinalhdl/generated/<Module>.vhd vhdl/<Module>.vhd
	$(MAKE) VHDL_SOURCES="vhdl/<Module>.vhd" TOPLEVEL=<Module> MODULE=tests.test_<module>
EOF

# Step 3.3: Run tests
make test_<module>_spinalhdl 2>&1 | tee spinalhdl_test_results.log
```

**Interpreting Test Failures:**

```python
# When tests fail, you need to determine WHY:

# 1. INFRASTRUCTURE ISSUE (use vhdl-tester-workflow Step 0)
#    - Path problems
#    - Missing dependencies
#    - Makefile issues
#    Example: "File not found: vhdl/Module.vhd"

# 2. BEHAVIORAL DIFFERENCE (use this workflow Step 0)
#    - Wrong result at specific cycle
#    - State machine behaves differently
#    - Timing is off
#    Example: "Cycle 18: expected 0x1, got 0xC0000001"

# 3. TEST VECTOR ISSUE (rare, check with vhdl-tester agent)
#    - Original VHDL also fails this test
#    - Test vector has wrong expected value
#    Example: Both implementations produce same "wrong" result
```

**Debugging Behavioral Differences:**

```python
# Step 3.3.1: Isolate the failing test
@cocotb.test()
async def test_isolated_failure(dut):
    """Reproduce specific failure in isolation"""
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Use exact inputs from failing test
    dut.ain.value = 0xFFFFFFFF
    dut.bin.value = 0xFFFFFFFF
    dut.wr.value = 1
    await RisingEdge(dut.clk)

    dut.wr.value = 0

    # Check cycle-by-cycle
    for cycle in range(2, 20):
        await RisingEdge(dut.clk)
        dut._log.info(f"Cycle {cycle}: dout = {int(dut.dout.value):#x}")

        # If you have original VHDL results
        if cycle == 18:
            expected = 0x1  # From original VHDL
            actual = int(dut.dout.value)
            if actual != expected:
                dut._log.error(f"MISMATCH at cycle {cycle}")
                dut._log.error(f"  Expected (VHDL):     {expected:#x}")
                dut._log.error(f"  Actual (SpinalHDL):  {actual:#x}")

                # This tells you to fix SpinalHDL implementation
                # Go back to Step 0 or Step 1
                assert False, "Behavioral difference found"
```

**Systematic Validation:**

```bash
# Step 3.4: Run ALL test vectors
# Each test vector from vhdl-tester must pass

cd verification/cocotb
make test_<module>_spinalhdl

# Expected output:
# ** tests.test_<module>.test_startup              PASS **
# ** tests.test_<module>.test_from_vectors         PASS **
# ** tests.test_<module>.test_timing_detailed      PASS **
# ** TESTS=N PASS=N FAIL=0 SKIP=0 **

# If ANY test fails:
# 1. Identify if it's infrastructure (Step 0 of vhdl-tester) or behavioral (Step 0 of this workflow)
# 2. Debug using isolated test case
# 3. Fix SpinalHDL implementation
# 4. Regenerate VHDL
# 5. Re-run tests
# 6. Repeat until all pass
```

**Comparison with Original VHDL:**

```bash
# Step 3.5: Side-by-side validation
# Run BOTH versions and compare

# Test original VHDL
make test_<module>  # Uses original VHDL
mv results.xml results_original.xml

# Test SpinalHDL-generated VHDL
make test_<module>_spinalhdl
mv results.xml results_spinalhdl.xml

# Compare results
python3 << 'EOF'
import xml.etree.ElementTree as ET

orig = ET.parse('results_original.xml')
spin = ET.parse('results_spinalhdl.xml')

# Compare test results
for test_name in ['test_startup', 'test_from_vectors', 'test_timing_detailed']:
    orig_result = orig.find(f".//testcase[@name='{test_name}']").get('status')
    spin_result = spin.find(f".//testcase[@name='{test_name}']").get('status')

    if orig_result != spin_result:
        print(f"❌ {test_name}: VHDL={orig_result}, SpinalHDL={spin_result}")
    else:
        print(f"✅ {test_name}: Both {orig_result}")
EOF
```

**Success Criteria (ALL must pass):**
- [ ] All CocoTB test cases pass (PASS=N FAIL=0)
- [ ] Same test results as original VHDL
- [ ] All test vectors from vhdl-tester-workflow validate successfully
- [ ] Cycle-accurate match with original (verify with isolated tests)
- [ ] All register states match at critical cycles
- [ ] No behavioral differences in edge cases

**Failure Recovery:**
```
Test fails → Identify type (infrastructure vs behavioral)
           ↓
Infrastructure → Use vhdl-tester-workflow Step 0
           ↓
Behavioral → Use this workflow Step 0
           ↓
Fix SpinalHDL → Regenerate VHDL → Re-test
           ↓
Repeat until all tests pass
```

**Deliverables:**
- **Passing test results** (all CocoTB tests green)
- **Test comparison report** (SpinalHDL vs original VHDL)
- **Bug fix documentation** (any behavioral issues found and fixed)
- **Validation sign-off** (all tests passing screenshot/log)

#### Step 4: Create Configuration System

```scala
// Template: core/spinalhdl/src/main/scala/jop/JopConfig.scala

package jop

case class JopConfig(
  // Core configuration
  dataWidth: Int = 32,
  stackDepth: Int = 16,

  // Memory configuration
  methodCacheSize: Int = 4096,
  microCodeRomSize: Int = 1024,

  // Pipeline configuration
  fetchStages: Int = 1,

  // FPGA target specific
  fpgaTarget: FpgaTarget = Altera,

  // Feature flags
  enableCache: Boolean = true,
  enableMultiCore: Boolean = false
)

sealed trait FpgaTarget
case object Altera extends FpgaTarget
case object Xilinx extends FpgaTarget
case object Lattice extends FpgaTarget

object JopConfig {
  // Predefined configurations for different boards
  def ep4cgx150df27: JopConfig = JopConfig(
    fpgaTarget = Altera,
    methodCacheSize = 8192
  )

  def cyclone4: JopConfig = JopConfig(
    fpgaTarget = Altera,
    methodCacheSize = 4096
  )

  def alchitryAu: JopConfig = JopConfig(
    fpgaTarget = Xilinx,
    methodCacheSize = 4096
  )
}
```

**Deliverables:**
- Configuration system
- Board-specific presets
- Documentation in `docs/architecture/configuration.md`

#### Step 5: Integrate Pipeline

```scala
// Template: core/spinalhdl/src/main/scala/jop/JopCore.scala

package jop

import spinal.core._
import spinal.lib._
import jop.pipeline._

case class JopCore(config: JopConfig) extends Component {
  val io = new Bundle {
    // Top-level I/O
    val memoryBus = master(MemoryBus())
    val debugPort = slave(DebugPort())
  }

  // Instantiate pipeline stages
  val fetch = new BytecodeFetch(config)
  val decode = new MicrocodeDecode(config)
  val execute = new Execute(config)

  // Connect pipeline
  decode.io.bytecode <> fetch.io.bytecode
  execute.io.microcode <> decode.io.microcode

  // Memory subsystem
  val methodCache = new MethodCache(config)
  val stackBuffer = new StackBuffer(config)

  // Connections
  // ...
}

object JopCore {
  def main(args: Array[String]): Unit = {
    val config = JopConfig.ep4cgx150df27

    SpinalConfig(
      targetDirectory = "core/spinalhdl/generated",
      defaultConfigForClockDomains = ClockDomainConfig(
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    ).generateVhdl(new JopCore(config))
  }
}
```

**Deliverables:**
- Integrated core
- Generated top-level VHDL
- Build scripts

### Output Artifacts

```
core/spinalhdl/
├── src/main/scala/jop/
│   ├── JopCore.scala
│   ├── JopConfig.scala
│   ├── pipeline/
│   │   ├── BytecodeFetch.scala
│   │   ├── MicrocodeFetch.scala
│   │   ├── MicrocodeDecode.scala
│   │   └── Execute.scala
│   ├── memory/
│   │   ├── MethodCache.scala
│   │   ├── StackBuffer.scala
│   │   └── MicrocodeRom.scala
│   └── types/
│       ├── MicrocodeInstruction.scala
│       └── Interfaces.scala
├── generated/
│   ├── JopCore.vhd
│   └── [module].vhd
├── build.sbt
└── project/
    └── plugins.sbt

docs/migration/
├── fetch-comparison.md
├── decode-comparison.md
├── execute-comparison.md
└── integration-notes.md
```

## Build Configuration

```scala
// build.sbt
name := "jop-spinalhdl"
version := "0.1.0"
scalaVersion := "2.13.12"

libraryDependencies ++= Seq(
  "com.github.spinalhdl" %% "spinalhdl-core" % "1.10.1",
  "com.github.spinalhdl" %% "spinalhdl-lib" % "1.10.1",
  compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % "1.10.1")
)

fork := true
```

## Success Criteria (Module Completion)

For each module, ALL criteria must be met before considering it complete:

- [ ] SpinalHDL implementation complete and compiles
- [ ] Generated VHDL compiles with GHDL
- [ ] **ALL CocoTB tests pass (FAIL=0)**
- [ ] **Test results match original VHDL exactly**
- [ ] **All test vectors from vhdl-tester-workflow validate**
- [ ] Cycle-accurate behavioral equivalence verified
- [ ] Code is well-documented with VHDL equivalents noted
- [ ] Configuration parameters implemented
- [ ] No known behavioral differences

## Integration with vhdl-tester-workflow

**Critical:** This agent **depends on** vhdl-tester-workflow outputs:

```
vhdl-tester-workflow → Creates golden standard
                    ↓
                  (provides)
                    ↓
  - Test vectors (JSON)
  - CocoTB test suite
  - Expected behavior documentation
                    ↓
spinalhdl-developer → Implements port
                    ↓
                  (validates against)
                    ↓
  - Runs CocoTB tests
  - Compares with golden standard
  - Debugs until match
                    ↓
                  (produces)
                    ↓
  - Verified SpinalHDL implementation
  - Generated VHDL matching original
```

**Workflow:**
1. vhdl-tester-workflow establishes golden standard (tests passing on original VHDL)
2. spinalhdl-developer implements SpinalHDL version
3. spinalhdl-developer validates against same tests
4. **Tests must produce identical results**

## Handoff to Next Agent

### From vhdl-tester-workflow (inputs):
- Test vectors (`verification/test-vectors/modules/<module>.json`)
- CocoTB test suite (`verification/cocotb/tests/test_<module>.py`)
- Module analysis documentation
- Golden standard test results

### To spinalhdl-tester (outputs):
- Verified SpinalHDL source code
- Configuration examples
- Build instructions
- Generated VHDL for reference

### To reviewer (outputs):
- Complete SpinalHDL implementation
- Generated VHDL matching original
- **CocoTB test results (all passing)**
- Comparison report with original VHDL
- Documentation of translation decisions

## Development Commands

```bash
# Compile Scala code
sbt compile

# Generate VHDL for specific module
sbt "runMain jop.pipeline.<Module>"

# Generate complete core
sbt "runMain jop.JopCore"

# Run Scala tests (once spinalhdl-tester creates them)
sbt test

# Package
sbt package
```

## Code Style Guidelines

- Use meaningful names that match VHDL when possible
- Document all registers and their reset values
- Use `Reg() init(value)` for synchronous resets
- Group related signals in Bundles
- Comment non-obvious translations from VHDL
- Use SpinalHDL idioms (Stream, Flow) where appropriate
- Keep generated VHDL readable

## Common Translation Patterns

### VHDL Process → SpinalHDL
```scala
// VHDL:
// process(clk)
// begin
//   if rising_edge(clk) then
//     if reset = '1' then
//       counter <= 0;
//     else
//       counter <= counter + 1;
//     end if;
//   end if;
// end process;

// SpinalHDL:
val counter = Reg(UInt(8 bits)) init(0)
counter := counter + 1
```

### VHDL State Machine → SpinalHDL
```scala
// Use SpinalHDL StateMachine or switch/is
val state = Reg(StateEnum()) init(StateEnum.IDLE)

switch(state) {
  is(StateEnum.IDLE) {
    when(startSignal) {
      state := StateEnum.ACTIVE
    }
  }
  is(StateEnum.ACTIVE) {
    // ...
  }
}
```

## Notes

- Maintain cycle-accurate equivalence with original
- Document any intentional deviations
- Keep configuration backward compatible
- Generate readable VHDL for debugging
- Test incrementally (module by module)
