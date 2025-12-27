# SpinalHDL Developer Agent Workflow

## Role
Create and maintain the SpinalHDL reimplementation of the JOP core, maintaining architectural equivalence with the original VHDL.

## Technologies
- Scala 2.13+
- SpinalHDL
- sbt (build tool)
- Mill (optional alternative)

## Responsibilities

### 1. Port VHDL to SpinalHDL
- Translate VHDL modules to SpinalHDL components
- Maintain signal-level compatibility
- Preserve timing and pipeline structure
- Implement equivalent state machines

### 2. Generate HDL Output
- Generate VHDL for CocoTB validation
- Generate Verilog for synthesis
- Ensure generated code is readable and maintainable

### 3. Create Configurable Architecture
- Parameterize for different FPGA targets
- Support memory configuration options
- Enable/disable features via configuration

## Workflow Template

### Input Artifacts
```
original/vhdl/core/<module>.vhd
docs/verification/modules/<module>-analysis.md
verification/cocotb/fixtures/vectors_<module>.py
```

### Process Steps

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

#### Step 3: Run CocoTB Tests

```bash
# Copy generated VHDL to CocoTB test directory
cp core/spinalhdl/generated/<Module>.vhd verification/cocotb/dut/

# Run CocoTB tests
cd verification/cocotb
make test_<module>_spinalhdl

# Compare results with original VHDL
make compare_<module>
```

**Success Criteria:**
- [ ] All CocoTB tests pass
- [ ] Cycle-accurate match with original
- [ ] Register states match at each cycle
- [ ] No timing violations

**Deliverables:**
- Test results comparison report
- Any necessary bug fixes

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

## Success Criteria

- [ ] All modules ported from VHDL to SpinalHDL
- [ ] Generated VHDL passes all CocoTB tests
- [ ] Configuration system implemented
- [ ] Pipeline integration complete
- [ ] Code is well-documented
- [ ] Build system is reproducible

## Handoff to Next Agent

### To vhdl-tester:
- Generated VHDL files for testing
- Module interface documentation
- Any timing or behavior differences

### To spinalhdl-tester:
- SpinalHDL source code
- Configuration examples
- Build instructions
- Module documentation

### To reviewer:
- Complete source code
- Generated VHDL
- CocoTB test results
- Migration notes

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
