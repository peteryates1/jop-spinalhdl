# JOP Pipeline Implementation Plan

## Overview

Implement JOP pipeline using SpinalHDL Pipeline API while maintaining proven CocoTB-first verification workflow.

**Key Principle:** Golden reference from original VHDL using CocoTB must be established FIRST, then implement SpinalHDL.

---

## Phase 0: Pipeline API Learning (Prototype)

### Goal
Understand SpinalHDL Pipeline API through hands-on prototyping before applying to JOP.

### Tasks

#### 0.1: Simple 2-Stage Prototype
**Create:** `core/spinalhdl/src/main/scala/examples/SimplePipeline.scala`

```scala
/**
 * Simple 2-stage pipeline prototype to learn Pipeline API
 *
 * Stage 1 (Fetch): Load values from memory
 * Stage 2 (Execute): Perform arithmetic operation
 */
class SimplePipeline extends Component {
  val io = new Bundle {
    val addr = in UInt(8 bits)
    val result = out UInt(32 bits)
  }

  val pipeline = new Pipeline {
    // Define stages
    val fetch = new Stage("fetch")
    val execute = new Stage("execute")

    // Define payloads (data flowing through pipeline)
    val ADDR = Payload(UInt(8 bits))
    val DATA_A = Payload(UInt(32 bits))
    val DATA_B = Payload(UInt(32 bits))
    val RESULT = Payload(UInt(32 bits))

    // Connect stages with automatic register insertion
    connect(fetch, execute)(Connection.M2S())

    // Fetch stage: Read from memory
    fetch.area {
      ADDR := io.addr
      val mem = Mem(UInt(32 bits), 256)
      DATA_A := mem.readSync(ADDR)
      DATA_B := mem.readSync(ADDR + 1)
    }

    // Execute stage: Perform operation
    execute.area {
      RESULT := DATA_A + DATA_B
    }

    // Output from final stage
    io.result := execute(RESULT)
  }
}
```

**Verification:** Create simple ScalaTest to verify:
- Payloads propagate between stages
- Registers inserted automatically
- Pipeline produces correct results

**Success Criteria:**
- ✅ Understand Node, Stage, Payload concepts
- ✅ Can connect stages with Connection.M2S()
- ✅ Know how to access payloads: `stage(PAYLOAD)`
- ✅ Understand automatic register inference
- ✅ Can generate and inspect VHDL/Verilog

#### 0.2: 3-Stage Prototype with Control Logic
**Create:** `core/spinalhdl/src/main/scala/examples/ControlPipeline.scala`

Add control signals, branching, and stalls:

```scala
class ControlPipeline extends Component {
  val pipeline = new Pipeline {
    val fetch = new Stage("fetch")
    val decode = new Stage("decode")
    val execute = new Stage("execute")

    // Payloads
    val PC = Payload(UInt(32 bits))
    val INSTRUCTION = Payload(Bits(32 bits))
    val OPCODE = Payload(Bits(8 bits))
    val CONTROL = Payload(new Bundle {
      val aluOp = Bits(3 bits)
      val regWrite = Bool()
    })

    connect(fetch, decode)(Connection.M2S())
    connect(decode, execute)(Connection.M2S())

    fetch.area {
      val pc = Reg(UInt(32 bits)) init(0)
      PC := pc

      // Branch handling
      when(execute(CONTROL).regWrite) {
        pc := pc + 4
      }
    }

    decode.area {
      OPCODE := INSTRUCTION(7 downto 0)
      CONTROL.aluOp := INSTRUCTION(10 downto 8)
      CONTROL.regWrite := INSTRUCTION(11)
    }

    execute.area {
      // ALU operations based on control
      val result = UInt(32 bits)
      switch(CONTROL.aluOp) {
        is(0) { result := operandA + operandB }
        is(1) { result := operandA - operandB }
        is(2) { result := operandA & operandB }
        // ...
      }
    }
  }
}
```

**Success Criteria:**
- ✅ Can handle control signals (bundles of related signals)
- ✅ Understand how to implement branches/jumps
- ✅ Know how to access signals from other stages
- ✅ Ready to apply to JOP pipeline

**Deliverables:**
- Two prototype examples with documentation
- Lessons learned document
- Generated VHDL/Verilog inspection notes

**Estimated Time:** 1-2 days of exploration

---

## Phase 1: CocoTB Golden Standard (JOP Pipeline Stages)

### Goal
Establish cycle-accurate golden reference for each pipeline stage using original VHDL.

### Workflow: vhdl-tester-workflow

**CRITICAL:** Before implementing ANY SpinalHDL pipeline code, we must have CocoTB tests validating the original VHDL behavior.

### 1.1: Analyze Pipeline Stages

**Identify Stage Boundaries:**
```
Stage 1: bcfetch.vhd  - Bytecode fetch and branch control
Stage 2: fetch.vhd    - Microcode ROM fetch
Stage 3: decode.vhd   - Microcode decode, control signal generation
Stage 4: stack.vhd    - Stack ALU execution (uses mul, shift)
```

**For Each Stage:**

#### Template: Stage Analysis
```markdown
## Stage: <name>

### Inputs
- Signal: type, source stage, description
- ...

### Outputs
- Signal: type, destination stage, description
- ...

### Internal State
- Register: type, initialization, description
- ...

### Behavior
- What transformation happens this cycle
- Control flow (if/else, case)
- Edge cases

### Timing
- Latency: X cycles
- Critical path: description
- Pipeline registers: list
```

**Create:**
- `docs/verification/modules/bcfetch-analysis.md`
- `docs/verification/modules/fetch-analysis.md`
- `docs/verification/modules/decode-analysis.md`
- `docs/verification/modules/stack-analysis.md`

### 1.2: Create Test Vectors (Per Stage)

**Strategy:** Test each stage in isolation first, then full pipeline integration.

#### Example: bcfetch Stage Test Vectors
**Create:** `verification/test-vectors/modules/bcfetch.json`

```json
{
  "module": "bcfetch",
  "version": "1.0.0",
  "description": "Bytecode fetch and branch control tests",
  "test_cases": [
    {
      "name": "sequential_fetch",
      "description": "Fetch sequential bytecodes without branches",
      "inputs": [
        {"cycle": 0, "signals": {"reset": "0x1"}},
        {"cycle": 1, "signals": {"reset": "0x0"}},
        {"cycle": 2, "signals": {"opd": "0x10"}}
      ],
      "expected_outputs": [
        {"cycle": 3, "signals": {"jpc_out": "0x1"}},
        {"cycle": 4, "signals": {"jpc_out": "0x2"}},
        {"cycle": 5, "signals": {"jpc_out": "0x3"}}
      ]
    },
    {
      "name": "conditional_branch_taken",
      "description": "Branch taken when condition is true",
      "inputs": [
        {"cycle": 0, "signals": {"jbr": "0x1", "stk_eq": "0x1", "jpaddr": "0x100"}}
      ],
      "expected_outputs": [
        {"cycle": 1, "signals": {"jpc_out": "0x100"}}
      ]
    }
  ]
}
```

**Pattern:** Similar JSON test vectors for:
- fetch.json (microcode fetch)
- decode.json (control signal generation)
- stack.json (ALU operations - already partially covered by mul/shift)

### 1.3: Implement CocoTB Tests (Per Stage)

#### Template: Stage Test
**Create:** `verification/cocotb/tests/test_<stage>.py`

```python
"""
CocoTB tests for <stage> module

Tests <stage> behavior in isolation using test vectors from JSON.
Establishes golden reference for SpinalHDL implementation.
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge
from verification.cocotb.util.test_vectors import TestVectorLoader

loader = TestVectorLoader("../test-vectors")

@cocotb.test()
async def test_<stage>_from_vectors(dut):
    """Run all test cases from JSON vectors"""
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    test_cases = loader.get_test_cases("<stage>")

    for tc in test_cases:
        dut._log.info(f"Running test: {tc['name']}")

        # Apply inputs per cycle
        for inp in tc.get('inputs', []):
            await RisingEdge(dut.clk)
            for signal, value in inp['signals'].items():
                val = loader.parse_value(value)
                if val is not None:
                    getattr(dut, signal).value = val

        # Verify expected outputs
        for expected in tc.get('expected_outputs', []):
            # Wait until expected cycle
            while current_cycle < expected['cycle']:
                await RisingEdge(dut.clk)
                current_cycle += 1

            # Check all expected signals
            for signal, expected_val in expected['signals'].items():
                actual = int(getattr(dut, signal).value)
                expected = loader.parse_value(expected_val)
                assert actual == expected, \
                    f"{tc['name']}: {signal} = {actual:#x}, expected {expected:#x}"

        dut._log.info(f"✓ Test '{tc['name']}' PASSED")
```

**Success Criteria (Per Stage):**
- ✅ All test vectors pass (100%)
- ✅ Coverage ≥ 95%
- ✅ Edge cases documented
- ✅ Timing verified (cycle-accurate)

### 1.4: Full Pipeline Integration Tests

**After individual stages verified**, create integration tests:

**Create:** `verification/test-vectors/pipeline/integration.json`

```json
{
  "module": "pipeline_integration",
  "description": "End-to-end pipeline tests executing microcode sequences",
  "test_cases": [
    {
      "name": "simple_add",
      "description": "Execute: PUSH 5, PUSH 3, ADD",
      "microcode_sequence": [
        {"cycle": 0, "bytecode": "0x10", "comment": "BIPUSH"},
        {"cycle": 1, "bytecode": "0x05", "comment": "constant 5"},
        {"cycle": 2, "bytecode": "0x10", "comment": "BIPUSH"},
        {"cycle": 3, "bytecode": "0x03", "comment": "constant 3"},
        {"cycle": 4, "bytecode": "0x60", "comment": "IADD"}
      ],
      "expected_state": {
        "cycle": 10,
        "tos": "0x8",
        "nos": "0x0",
        "sp": "0x1"
      }
    }
  ]
}
```

**Create:** `verification/cocotb/tests/test_pipeline_integration.py`

Tests full pipeline executing bytecode sequences.

### 1.5: Reviewer Validation

**Invoke:** `reviewer-workflow` for each stage

**Acceptance Criteria:**
- ✅ All individual stage tests passing
- ✅ Integration tests passing
- ✅ Documentation complete
- ✅ Quality score ≥ 95/100

**Deliverables (Phase 1):**
- Stage analysis documents (4 files)
- JSON test vectors (4 stage files + 1 integration file)
- CocoTB test suites (4 stage tests + 1 integration test)
- Test results (100% pass rate)
- Reviewer acceptance reports

**Estimated Time:** 2-3 weeks

---

## Phase 2: SpinalHDL Pipeline Implementation

### Goal
Implement JOP pipeline using SpinalHDL Pipeline API, verified against CocoTB golden reference.

### Workflow: spinalhdl-developer-workflow

### 2.1: Create ALU Component (Prerequisite)

**Before pipeline**, complete standalone ALU:

**Create:** `core/spinalhdl/src/main/scala/jop/core/Alu.scala`

```scala
/**
 * Arithmetic Logic Unit for JOP
 *
 * Performs basic arithmetic and logic operations
 * Purely combinational (0-cycle latency)
 */
case class Alu(width: Int = 32) extends Component {
  val io = new Bundle {
    val a = in UInt(width bits)
    val b = in UInt(width bits)
    val op = in Bits(3 bits)  // Operation selector
    val result = out UInt(width bits)
  }

  // Operation encoding
  object AluOp {
    val ADD = 0
    val SUB = 1
    val AND = 2
    val OR  = 3
    val XOR = 4
  }

  switch(io.op) {
    is(AluOp.ADD) { io.result := io.a + io.b }
    is(AluOp.SUB) { io.result := io.a - io.b }
    is(AluOp.AND) { io.result := io.a & io.b }
    is(AluOp.OR)  { io.result := io.a | io.b }
    is(AluOp.XOR) { io.result := io.a ^ io.b }
    default       { io.result := 0 }
  }
}
```

**Verification:**
1. Create test vectors: `verification/test-vectors/modules/alu.json`
2. CocoTB tests (if separate VHDL alu.vhd exists)
3. ScalaTest: `core/spinalhdl/src/test/scala/jop/core/AluTest.scala`

**Status:** Standalone, tested, ready for integration

### 2.2: Define Pipeline Structure

**Create:** `core/spinalhdl/src/main/scala/jop/pipeline/JopPipeline.scala`

```scala
/**
 * JOP Pipeline using SpinalHDL Pipeline API
 *
 * Implements 4-stage pipeline:
 * - Bcfetch: Bytecode fetch and branch control
 * - Fetch: Microcode ROM access
 * - Decode: Microcode decode and control signal generation
 * - Execute: Stack-based ALU execution
 */
class JopPipeline(config: JopConfig) extends Component {
  val io = new Bundle {
    // Memory interface
    val mem = master(MemBus(config))
    // I/O interface
    val io_bus = master(IOBus(config))
    // Interrupts
    val irq = in(IrqSignals())
  }

  val pipeline = new Pipeline {
    // ========================================
    // Stage Definitions
    // ========================================

    val bcfetch = new Stage("bcfetch")
    val fetch = new Stage("fetch")
    val decode = new Stage("decode")
    val execute = new Stage("execute")

    // ========================================
    // Payload Definitions
    // ========================================

    // Program counter and instruction flow
    val JPC = Payload(UInt(config.jpcWidth bits))
    val BYTECODE = Payload(Bits(8 bits))
    val MICROCODE = Payload(Bits(config.instrWidth bits))

    // Branch control
    val BRANCH_CTRL = Payload(new Bundle {
      val br = Bool()      // Unconditional branch
      val jmp = Bool()     // Jump
      val jbr = Bool()     // Conditional branch
    })

    // ALU control signals (from decode)
    val ALU_CTRL = Payload(new Bundle {
      val sel_sub = Bool()
      val sel_amux = Bits(2 bits)
      val ena_a = Bool()
      val sel_bmux = Bits(2 bits)
      val sel_log = Bits(2 bits)
      val sel_shf = Bits(2 bits)
      val sel_lmux = Bool()
      val sel_imux = Bool()
      val sel_rmux = Bool()
      val sel_smux = Bool()
      val sel_mmux = Bool()
    })

    // Stack data
    val STACK_DATA = Payload(new Bundle {
      val tos = UInt(32 bits)
      val nos = UInt(32 bits)
      val sp = UInt(config.spWidth bits)
    })

    // Memory control
    val MEM_CTRL = Payload(new Bundle {
      val wr_ena = Bool()
      val rd = Bool()
      val wr = Bool()
    })

    // ========================================
    // Stage Connections
    // ========================================

    connect(bcfetch, fetch)(Connection.M2S())
    connect(fetch, decode)(Connection.M2S())
    connect(decode, execute)(Connection.M2S())

    // ========================================
    // Stage Implementations
    // ========================================

    // Import stage logic from separate files
    val bcfetchLogic = new BcfetchStage(this, bcfetch, config)
    val fetchLogic = new FetchStage(this, fetch, config)
    val decodeLogic = new DecodeStage(this, decode, config)
    val executeLogic = new ExecuteStage(this, execute, config)
  }
}
```

### 2.3: Implement Individual Stages

**Create stage files:** `core/spinalhdl/src/main/scala/jop/pipeline/`

#### BcfetchStage.scala
```scala
class BcfetchStage(
  pipeline: Pipeline,
  stage: Stage,
  config: JopConfig
) extends Area {
  import pipeline._

  stage.area {
    // Java bytecode PC register
    val jpc = Reg(UInt(config.jpcWidth bits)) init(0)

    // Branch target address
    val branchTarget = UInt(config.jpcWidth bits)

    // Update JPC based on branch conditions
    when(BRANCH_CTRL.br) {
      jpc := branchTarget
    }.elsewhen(BRANCH_CTRL.jbr && conditionMet) {
      jpc := branchTarget
    }.otherwise {
      jpc := jpc + 1  // Sequential
    }

    // Output current JPC
    JPC := jpc

    // Fetch bytecode from cache
    BYTECODE := bytecodeCache.read(JPC)
  }
}
```

#### FetchStage.scala
```scala
class FetchStage(
  pipeline: Pipeline,
  stage: Stage,
  config: JopConfig
) extends Area {
  import pipeline._

  stage.area {
    // Microcode ROM
    val microcodeRom = Mem(Bits(config.instrWidth bits), config.romSize)

    // Compute microcode address from bytecode
    val mcAddr = computeMicrocodeAddress(BYTECODE)

    // Fetch microcode instruction
    MICROCODE := microcodeRom.readSync(mcAddr)
  }
}
```

#### DecodeStage.scala
```scala
class DecodeStage(
  pipeline: Pipeline,
  stage: Stage,
  config: JopConfig
) extends Area {
  import pipeline._

  stage.area {
    // Decode microcode to control signals
    ALU_CTRL.sel_sub := MICROCODE(bit_sel_sub)
    ALU_CTRL.sel_amux := MICROCODE(bits_sel_amux)
    ALU_CTRL.ena_a := MICROCODE(bit_ena_a)
    // ... decode all control bits

    MEM_CTRL.wr_ena := MICROCODE(bit_wr_ena)
    MEM_CTRL.rd := MICROCODE(bit_rd)

    BRANCH_CTRL.br := MICROCODE(bit_br)
    BRANCH_CTRL.jmp := MICROCODE(bit_jmp)
  }
}
```

#### ExecuteStage.scala
```scala
class ExecuteStage(
  pipeline: Pipeline,
  stage: Stage,
  config: JopConfig
) extends Area {
  import pipeline._

  stage.area {
    // Instantiate execution units
    val alu = Alu()
    val mul = Mul()
    val shift = Shift()

    // Stack registers
    val tos = Reg(UInt(32 bits)) init(0)
    val nos = Reg(UInt(32 bits)) init(0)
    val sp = Reg(UInt(config.spWidth bits)) init(0)

    // A-mux (TOS source selection)
    val aInput = UInt(32 bits)
    switch(ALU_CTRL.sel_amux) {
      is(0) { aInput := tos }
      is(1) { aInput := memoryData }
      is(2) { aInput := ioData }
      is(3) { aInput := jpcExtended }
    }

    // B-mux (NOS source selection)
    val bInput = UInt(32 bits)
    switch(ALU_CTRL.sel_bmux) {
      is(0) { bInput := nos }
      is(1) { bInput := operandData }
      // ...
    }

    // ALU operation
    alu.io.a := aInput
    alu.io.b := bInput
    alu.io.op := ALU_CTRL.sel_sub ## ALU_CTRL.sel_log

    // Shifter
    shift.io.din := aInput
    shift.io.off := bInput(4 downto 0)
    shift.io.shtyp := ALU_CTRL.sel_shf

    // Multiplier
    mul.io.ain := aInput
    mul.io.bin := bInput
    mul.io.wr := MEM_CTRL.mul_wr

    // Result mux
    val result = UInt(32 bits)
    switch(ALU_CTRL.sel_rmux) {
      is(0) { result := alu.io.result }
      is(1) { result := shift.io.dout }
      is(2) { result := mul.io.dout }
      is(3) { result := memoryData }
    }

    // Update TOS
    when(ALU_CTRL.ena_a) {
      tos := result
    }

    // Update stack data payload
    STACK_DATA.tos := tos
    STACK_DATA.nos := nos
    STACK_DATA.sp := sp
  }
}
```

### 2.4: Generate VHDL for Verification

```bash
cd core/spinalhdl
sbt "runMain jop.pipeline.JopPipelineVhdl"
```

**Output:** `core/spinalhdl/generated/vhdl/JopPipeline.vhd`

### 2.5: Verify Against CocoTB Golden Reference

**Update Makefile:** `verification/cocotb/Makefile`

```makefile
# Test SpinalHDL-generated pipeline
test_pipeline_spinalhdl:
	@echo "Testing SpinalHDL-generated JOP pipeline..."
	@mkdir -p waveforms
	@cp ../../core/spinalhdl/generated/vhdl/JopPipeline.vhd vhdl/
	$(MAKE) VHDL_SOURCES="vhdl/JopPipeline.vhd" TOPLEVEL=JopPipeline \
		MODULE=tests.test_pipeline_integration
```

**Run tests:**
```bash
cd verification/cocotb
make test_pipeline_spinalhdl
```

**Success Criteria:**
- ✅ All integration tests pass
- ✅ Cycle-accurate match with original VHDL
- ✅ All stage tests pass individually

**Deliverables (Phase 2):**
- Alu.scala (standalone component)
- JopPipeline.scala (main pipeline)
- Stage files (4 files)
- Generated VHDL passing CocoTB tests
- Documentation of any translation differences

**Estimated Time:** 3-4 weeks

---

## Phase 3: ScalaTest Verification

### Goal
Verify SpinalHDL pipeline using SpinalSim with same test vectors.

### Workflow: spinalhdl-tester-workflow

### 3.1: Create Pipeline Tests

**Create:** `core/spinalhdl/src/test/scala/jop/pipeline/JopPipelineTest.scala`

```scala
class JopPipelineTest extends AnyFunSuite {
  val simConfig = SimConfig
    .withWave
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(50 MHz)))

  test("pipeline_integration_from_vectors") {
    val testVectors = PipelineTestVectorLoader.load(
      projectRoot.resolve("verification/test-vectors/pipeline/integration.json")
    )

    simConfig.compile(JopPipeline(JopConfig.default)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      testVectors.testCases.foreach { tc =>
        println(s"Running test: ${tc.name}")

        // Execute microcode sequence
        tc.microcodeSequence.foreach { step =>
          dut.clockDomain.waitSampling(step.cycle)
          // Inject bytecode
          forceBytecode(dut, step.bytecode)
          dut.clockDomain.waitSampling(1)
        }

        // Verify final state
        dut.clockDomain.waitSampling(tc.expectedState.cycle)
        assert(dut.io.stack.tos.toLong == tc.expectedState.tos)
        assert(dut.io.stack.nos.toLong == tc.expectedState.nos)
        assert(dut.io.stack.sp.toLong == tc.expectedState.sp)

        println(s"✓ Test '${tc.name}' PASSED")
      }
    }
  }
}
```

### 3.2: Test Parity Validation

Verify same test vectors used:
- ✅ CocoTB uses: `verification/test-vectors/pipeline/integration.json`
- ✅ ScalaTest uses: `verification/test-vectors/pipeline/integration.json`
- ✅ Results match exactly

**Deliverables (Phase 3):**
- JopPipelineTest.scala
- All tests passing (100%)
- Test parity confirmed
- Waveforms for debugging

**Estimated Time:** 1-2 weeks

---

## Phase 4: Final Review & Integration

### Workflow: reviewer-workflow

### 4.1: Comprehensive Review

**Review Items:**
1. Pipeline structure quality
2. SpinalHDL idiom usage
3. Test coverage (unit + integration)
4. Code quality and documentation
5. Behavioral equivalence verification
6. Performance (resource usage, timing)

### 4.2: Integration Testing

**Test with:**
- Real Java bytecode sequences
- Exception handling
- Interrupt handling
- Memory/IO operations

### 4.3: Documentation

**Update:**
- Migration status (all modules complete)
- Architecture documentation
- User guide for running tests
- Performance benchmarks

**Deliverables (Phase 4):**
- Final review report
- Complete documentation
- Acceptance decision
- Commit to repository

**Estimated Time:** 1 week

---

## Timeline Summary

| Phase | Duration | Deliverable |
|-------|----------|-------------|
| **0: Prototype** | 1-2 days | Pipeline API understanding |
| **1: CocoTB Golden** | 2-3 weeks | Stage tests + integration tests (VHDL) |
| **2: SpinalHDL Impl** | 3-4 weeks | Pipeline using Pipeline API |
| **3: ScalaTest** | 1-2 weeks | SpinalHDL verification |
| **4: Review** | 1 week | Final acceptance |
| **Total** | **8-11 weeks** | Complete JOP pipeline |

---

## Success Criteria

### Phase 0
- ✅ 2 working prototypes
- ✅ Understand Pipeline API concepts
- ✅ Documented lessons learned

### Phase 1 (Golden Reference)
- ✅ CocoTB tests for all 4 stages (100% pass)
- ✅ Integration tests (100% pass)
- ✅ Coverage ≥ 95% per stage
- ✅ Cycle-accurate documentation

### Phase 2 (Implementation)
- ✅ SpinalHDL pipeline compiles
- ✅ Generated VHDL passes all CocoTB tests
- ✅ Code quality ≥ 95/100
- ✅ Behavioral equivalence confirmed

### Phase 3 (ScalaTest)
- ✅ All ScalaTest tests pass (100%)
- ✅ Test parity with CocoTB verified
- ✅ Waveforms match expected behavior

### Phase 4 (Final)
- ✅ Reviewer acceptance
- ✅ Documentation complete
- ✅ Ready for production use

---

## Key Principles

1. **CocoTB First** - Always establish golden reference before SpinalHDL implementation
2. **Incremental** - Verify each stage individually before integration
3. **Test Parity** - Same test vectors for CocoTB and ScalaTest
4. **Documentation** - Document behavior, timing, and edge cases
5. **Quality** - Maintain ≥ 95/100 quality scores

---

## Risk Mitigation

### Risk: Pipeline API learning curve
**Mitigation:** Phase 0 prototyping before real implementation

### Risk: Complex stage interactions
**Mitigation:** Test stages individually before integration

### Risk: Timing differences between VHDL and SpinalHDL
**Mitigation:** Cycle-accurate CocoTB tests catch discrepancies early

### Risk: Test coverage gaps
**Mitigation:** Require ≥95% coverage, comprehensive edge case testing

---

## Next Immediate Action

**Start Phase 0:** Create simple Pipeline API prototype to learn the framework.

**Estimated Start:** Now
**Estimated Complete:** 1-2 days
**Deliverable:** Working prototype + understanding of Pipeline API

Would you like to proceed with Phase 0 (prototype)?
