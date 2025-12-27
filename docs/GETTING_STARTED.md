# Getting Started with JOP Migration

## Overview

This guide walks you through starting the JOP SpinalHDL migration using the multi-agent workflow.

## Prerequisites Check

### 1. Verify Reference Files

```bash
# Check original VHDL is accessible
ls /home/peter/git/jop.arch/jop/vhdl/core/

# Check generated files exist
ls /home/peter/git/jop.arch/jop/asm/generated/
```

### 2. Verify Tools

```bash
# Python/CocoTB
python3 --version
ghdl --version

# Scala/SBT
sbt --version
java -version

# Test vector validation works
python tools/scripts/validate_vectors.py
```

## Step-by-Step First Module

### Recommended First Module: `jop_types.vhd`

**Why?**
- Required by everything else
- Just type definitions and constants
- No complex logic
- Perfect warm-up

### Step 1: As vhdl-tester - Analyze jop_types.vhd

```bash
# Read the file
less /home/peter/git/jop.arch/jop/vhdl/core/jop_types.vhd
```

**Task**: Document what's in jop_types.vhd
- Constants (data widths, stack sizes, ROM addresses)
- Type definitions
- Record types (if any)
- Functions

**Create**: `docs/verification/modules/jop_types-analysis.md`

**Example Analysis**:
```markdown
# jop_types.vhd Analysis

## Constants
- jpc_width: Java PC width (bits)
- pc_width: Microcode PC width (bits)
- stack_depth: Stack buffer depth
- ...

## Types
- sc_cpu_out_type: CPU output interface record
- sc_cpu_in_type: CPU input interface record
- ...

## Notes
- No sequential logic (just package)
- All modules depend on this
- Need to translate to Scala constants/types
```

**Note**: For jop_types, you probably won't create test vectors since it's just type definitions. Move directly to SpinalHDL translation.

### Step 2: As spinalhdl-developer - Translate to Scala

Create: `core/spinalhdl/src/main/scala/jop/types/JopTypes.scala`

**Example Translation**:
```scala
package jop.types

import spinal.core._
import spinal.lib._

/**
 * JOP Type Definitions
 *
 * Translated from: jop_types.vhd
 */
object JopTypes {

  // Constants from VHDL
  val JPC_WIDTH = 10  // Example - check actual value
  val PC_WIDTH = 8    // Example - check actual value
  val STACK_DEPTH = 16
  val DATA_WIDTH = 32

  // More constants...
}

/**
 * CPU Interface Bundles
 * Equivalent to VHDL record types
 */
case class CpuOut() extends Bundle {
  val address = UInt(32 bits)
  val writeData = Bits(32 bits)
  val writeEnable = Bool()
  // ... more signals
}

case class CpuIn() extends Bundle {
  val readData = Bits(32 bits)
  val waitState = Bool()
  // ... more signals
}
```

**Compile and verify**:
```bash
cd core/spinalhdl
sbt compile
```

**Deliverables**:
- JopTypes.scala created
- Compiles without errors
- Documented comparison with VHDL

### Step 3: Review (Quick Check)

Since jop_types has no logic to test:
- ✓ All constants translated
- ✓ All types translated
- ✓ Compiles successfully
- ✓ Documented

**ACCEPTED** - Move to next module!

---

## Second Module: `mul.vhd` (Recommended)

Now that types are done, let's do a real module with logic.

### Step 1: As vhdl-tester - Analyze mul.vhd

```bash
# Read the multiplier
less /home/peter/git/jop.arch/jop/vhdl/core/mul.vhd
```

**Create Analysis**: `docs/verification/modules/mul-analysis.md`

**Questions to Answer**:
1. What are the inputs/outputs?
2. How many cycles does multiplication take?
3. Is it pipelined?
4. What's the bit width?
5. Signed or unsigned?
6. Any special cases?

**Example Analysis**:
```markdown
# mul.vhd Analysis

## Entity Ports
- clk: clock input
- a: operand A (32 bits)
- b: operand B (32 bits)
- result: multiplication result (32 bits)

## Behavior
- Combinational or N-cycle pipelined?
- Latency: X cycles
- Throughput: 1 per Y cycles

## Edge Cases
- 0 * anything = 0
- Overflow behavior?
- Signed multiplication?
```

### Step 2: As vhdl-tester - Create Test Vectors

Create: `verification/test-vectors/modules/mul.json`

```json
{
  "module": "mul",
  "version": "1.0.0",
  "description": "Test vectors for multiplier unit",
  "metadata": {
    "author": "vhdl-tester",
    "created": "2025-01-15T12:00:00Z",
    "tags": ["alu", "multiply"]
  },
  "test_cases": [
    {
      "name": "reset_clears_output",
      "type": "reset",
      "description": "Verify reset clears result",
      "tags": ["reset", "basic"],
      "initial_state": {},
      "inputs": [
        {"cycle": 0, "signals": {"reset": "0x1"}}
      ],
      "expected_state": {
        "result": "0x0"
      },
      "cycles": 1
    },
    {
      "name": "multiply_simple",
      "type": "microcode",
      "description": "Simple multiplication: 5 * 3 = 15",
      "tags": ["basic", "multiply"],
      "initial_state": {},
      "inputs": [
        {"cycle": 1, "signals": {"a": "0x5", "b": "0x3"}}
      ],
      "expected_outputs": [
        {"cycle": 2, "signals": {"result": "0xF"}}
      ],
      "cycles": 2
    },
    {
      "name": "multiply_zero",
      "type": "edge_case",
      "description": "Multiply by zero",
      "tags": ["edge_case", "zero"],
      "inputs": [
        {"cycle": 1, "signals": {"a": "0x5", "b": "0x0"}}
      ],
      "expected_outputs": [
        {"cycle": 2, "signals": {"result": "0x0"}}
      ],
      "cycles": 2
    },
    {
      "name": "multiply_overflow",
      "type": "edge_case",
      "description": "Multiplication overflow",
      "tags": ["edge_case", "overflow"],
      "inputs": [
        {"cycle": 1, "signals": {"a": "0xFFFFFFFF", "b": "0x2"}}
      ],
      "expected_outputs": [
        {"cycle": 2, "signals": {"result": "0xFFFFFFFE"}}
      ],
      "cycles": 2
    }
  ]
}
```

**Validate**:
```bash
python tools/scripts/validate_vectors.py verification/test-vectors/modules/mul.json
```

### Step 3: As vhdl-tester - Create CocoTB Test

Create: `verification/cocotb/tests/test_mul.py`

```python
"""
CocoTB tests for mul.vhd (multiplier)
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))
from util.test_vectors import TestVectorLoader

loader = TestVectorLoader("../../test-vectors")


@cocotb.test()
async def test_mul_from_vectors(dut):
    """Test multiplier using JSON test vectors"""

    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Load test vectors
    test_cases = loader.get_test_cases("mul")

    for tc in test_cases:
        dut._log.info(f"Running: {tc['name']}")

        # Reset if needed
        if tc['type'] == 'reset':
            dut.reset.value = 1
            await RisingEdge(dut.clk)
            dut.reset.value = 0
            await RisingEdge(dut.clk)
        else:
            # Apply reset at start
            dut.reset.value = 1
            await RisingEdge(dut.clk)
            dut.reset.value = 0
            await RisingEdge(dut.clk)

        # Apply inputs
        for inp in tc.get('inputs', []):
            for signal, value in inp['signals'].items():
                val = loader.parse_value(value)
                if val is not None and hasattr(dut, signal):
                    getattr(dut, signal).value = val

            await RisingEdge(dut.clk)

        # Check expected outputs
        for out in tc.get('expected_outputs', []):
            # May need to wait for specific cycle
            for signal, expected_str in out['signals'].items():
                expected = loader.parse_value(expected_str)
                if expected is not None and hasattr(dut, signal):
                    actual = int(getattr(dut, signal).value)
                    assert actual == expected, \
                        f"{tc['name']}: {signal} = {hex(actual)}, expected {hex(expected)}"

        dut._log.info(f"  ✓ PASSED")
```

**Run against original VHDL**:
```bash
cd verification/cocotb
make TOPLEVEL=mul MODULE=tests.test_mul
```

### Step 4: As spinalhdl-developer - Port to SpinalHDL

Create: `core/spinalhdl/src/main/scala/jop/pipeline/Mul.scala`

```scala
package jop.pipeline

import spinal.core._
import spinal.lib._
import jop.types.JopTypes

/**
 * Multiplier Unit
 *
 * Ported from: mul.vhd
 *
 * Performs 32-bit multiplication
 * Latency: X cycles (check original VHDL)
 */
case class MulConfig(
  dataWidth: Int = 32
)

class Mul(config: MulConfig) extends Component {
  val io = new Bundle {
    val a = in UInt(config.dataWidth bits)
    val b = in UInt(config.dataWidth bits)
    val result = out UInt(config.dataWidth bits)
  }

  // Translation from VHDL
  // (Implement based on analysis of mul.vhd)

  // Example - check actual VHDL for correct implementation
  io.result := io.a * io.b
}

object Mul {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      targetDirectory = "generated",
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC)
    ).generateVhdl(new Mul(MulConfig()))
  }
}
```

**Generate VHDL**:
```bash
cd core/spinalhdl
sbt "runMain jop.pipeline.Mul"
```

**Test generated VHDL with CocoTB**:
```bash
# Copy generated VHDL
cp core/spinalhdl/generated/Mul.vhd verification/cocotb/dut/

# Update Makefile to point to generated version
# Run tests
cd verification/cocotb
make TOPLEVEL=mul MODULE=tests.test_mul VHDL_SOURCES=dut/Mul.vhd
```

### Step 5: As spinalhdl-tester - Create ScalaTest

Create: `verification/scalatest/src/test/scala/jop/pipeline/MulSpec.scala`

```scala
package jop.pipeline

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import jop.util.{TestVectorLoader, TestHelpers}

class MulSpec extends AnyFunSuite {

  val simConfig = SimConfig
    .withWave
    .withVerilator

  val testCases = TestVectorLoader.getTestCases("mul")

  testCases.foreach { tc =>
    test(tc.name) {
      simConfig.compile(new Mul(MulConfig())).doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        // Apply reset if needed
        if (tc.testType == "reset") {
          dut.io.reset #= true
          dut.clockDomain.waitRisingEdge(1)
          dut.io.reset #= false
          dut.clockDomain.waitRisingEdge(1)
        }

        // Apply inputs
        tc.inputs.foreach { inp =>
          inp.signals.get("a").flatMap(TestVectorLoader.parseValue).foreach { v =>
            dut.io.a #= v
          }
          inp.signals.get("b").flatMap(TestVectorLoader.parseValue).foreach { v =>
            dut.io.b #= v
          }
          dut.clockDomain.waitRisingEdge(1)
        }

        // Check outputs
        tc.expectedOutputs.foreach { out =>
          out.signals.get("result").flatMap(TestVectorLoader.parseValue).foreach { expected =>
            val actual = dut.io.result.toInt
            assert(actual == expected,
              s"${tc.name}: result = $actual, expected $expected")
          }
        }
      }
    }
  }
}
```

**Run tests**:
```bash
cd core/spinalhdl
sbt test
```

### Step 6: As reviewer - Verify Equivalence

**Checklist**:
- [ ] CocoTB tests pass on original VHDL
- [ ] CocoTB tests pass on generated VHDL from SpinalHDL
- [ ] ScalaTest tests pass
- [ ] Same test vectors used in both
- [ ] Results match exactly
- [ ] Code reviewed and approved

**Create**: `docs/reviews/modules/mul-review.md`

**If all pass**: **ACCEPTED** ✓

---

## After First Two Modules

You now have:
1. ✓ jop_types - Foundation types
2. ✓ mul - First real module with logic

### Next Modules (in order):

3. **shift.vhd** - Similar to mul, good practice
4. **stack.vhd** - Uses mul and shift
5. **decode.vhd** - Microcode decode
6. **fetch.vhd** - Microcode fetch (uses rom.vhd)
7. **bcfetch.vhd** - Bytecode fetch (uses jtbl.vhd)
8. **cache.vhd** - Method cache

## Iteration Workflow

For each module:
```
1. vhdl-tester: Analyze → Create vectors → Create CocoTB test → Verify
2. spinalhdl-developer: Port → Generate VHDL → Test with CocoTB
3. spinalhdl-tester: Create ScalaTest → Verify
4. reviewer: Cross-validate → Accept/Reject
```

## Tips

- **Start simple** - jop_types, then mul
- **One module at a time** - Don't rush
- **Test frequently** - Verify at each step
- **Document everything** - Analysis, decisions, issues
- **Ask questions** - Use AskUserQuestion tool
- **Track progress** - Use TodoWrite tool

## Common Issues

### CocoTB can't find module
- Check VHDL compiles with ghdl
- Check entity name matches TOPLEVEL
- Check VHDL_SOURCES path

### SpinalHDL won't compile
- Check imports
- Check signal types match
- Check clock domain handling

### Tests don't match
- Check test vector values (hex vs decimal)
- Check timing (cycle counts)
- Check reset behavior

## Ready to Start!

```bash
# Verify setup one more time
python tools/scripts/validate_vectors.py

# Start with jop_types analysis
less /home/peter/git/jop.arch/jop/vhdl/core/jop_types.vhd

# Let's go! 🚀
```
