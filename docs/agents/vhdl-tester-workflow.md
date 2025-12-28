# VHDL Tester Agent Workflow

## Role
Create and maintain the golden reference test suite using CocoTB/GHDL against the original JOP VHDL core.

## Technologies
- Python 3.x
- CocoTB (cocotb framework)
- GHDL (VHDL simulator)
- pytest (test organization)

## Responsibilities

### 1. Analyze Original VHDL
- Study VHDL module structure and interfaces
- Document signals, timing, and state transitions
- Identify critical paths and edge cases
- Map microcode instructions to expected behaviors

### 2. Create Cycle-Accurate Tests
- Write CocoTB testbenches for each module
- Verify cycle-by-cycle execution
- Test all microcode instructions
- Validate state machine transitions

### 3. Document Expected Behavior
- Capture register states (TOS, NOS, SP, PC, JPC)
- Document timing diagrams
- Record expected outputs for given inputs
- Create test vectors and golden outputs

## Workflow Template

### Input Artifacts
```
original/vhdl/core/<module>.vhd
docs/architecture/microcode-spec.md (if exists)
```

### Process Steps

#### Step 0: Debug & Fix Test Infrastructure

**When to use:** Tests exist but are failing due to infrastructure issues rather than design bugs. This step validates and repairs the test environment before proceeding with test development or execution.

**Common Issues:**

1. **Build/Configuration Problems**
   - Makefile variable conflicts (CocoTB's `VHDL_SOURCES_*` pattern matching)
   - Path resolution issues (relative paths, working directory assumptions)
   - Missing or incorrect simulator flags
   - Tool version incompatibilities

2. **Timing/Cycle Issues**
   - Off-by-one errors in cycle counting
   - Results appearing at unexpected cycles due to tool behavior
   - Misunderstanding of when signals are captured vs when they're set
   - Pipelined vs combinational signal timing

3. **Test Vector Errors**
   - Wrong expected values (e.g., upper vs lower 32 bits in multiplication)
   - Incorrect cycle timing in multi-cycle operations
   - Missing initialization requirements for stateful modules
   - Assumptions about reset behavior in modules without reset signals

4. **Integration Issues**
   - CocoTB/GHDL specific quirks and limitations
   - Signal initialization for modules without reset signals
   - State contamination between sequential tests
   - VPI interface timing considerations

**Debugging Process:**

```python
# Step 0.1: Identify the failure mode
# Run tests and capture full output
make test_<module> 2>&1 | tee test_output.log

# Step 0.2: Create isolated test case
# Strip down to minimal reproduction
@cocotb.test()
async def test_isolated_case(dut):
    """Reproduce specific failure in isolation"""
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Minimal setup
    dut.ain.value = 0xFFFFFFFF
    dut.bin.value = 0xFFFFFFFF
    dut.wr.value = 1
    await RisingEdge(dut.clk)

    # Check at multiple cycles to find actual timing
    for cycle in range(15, 20):
        await RisingEdge(dut.clk)
        dut._log.info(f"Cycle {cycle}: result = {int(dut.dout.value):#x}")

# Step 0.3: Verify against golden behavior
# Use Python or other reference to validate expected values
a = 0xFFFFFFFF
b = 0xFFFFFFFF
expected_64bit = a * b
expected_32bit = expected_64bit & 0xFFFFFFFF
print(f"Reference calculation: {expected_32bit:#x}")

# Step 0.4: Update test vectors
# Correct cycle timing and expected values based on actual VHDL behavior
```

**Resolution Checklist:**

- [ ] Makefile variables don't conflict with tool patterns
- [ ] All paths resolve correctly from test execution directory
- [ ] Signal initialization handles modules without reset
- [ ] Test vectors use correct cycle timing (validate with isolated tests)
- [ ] Expected values verified against VHDL behavior (not assumptions)
- [ ] Tests properly isolated (no state leakage between cases)
- [ ] Clean between tests (run full operation cycles, not partial)

**Example Fixes:**

```makefile
# WRONG: CocoTB interprets VHDL_SOURCES_DIR as a source list for library "DIR"
VHDL_SOURCES_DIR = ../../original/vhdl/core

# CORRECT: Use different naming pattern
ORIG_VHDL_DIR = ../../original/vhdl/core
```

```python
# WRONG: Assumes signals initialize to 0
@cocotb.test()
async def test_startup(dut):
    await RisingEdge(dut.clk)
    assert int(dut.dout.value) == 0  # May be 'U' (uninitialized)

# CORRECT: Initialize via write operation (if no reset signal)
@cocotb.test()
async def test_startup(dut):
    dut.ain.value = 0
    dut.bin.value = 0
    dut.wr.value = 1
    await RisingEdge(dut.clk)
    dut.wr.value = 0
    await RisingEdge(dut.clk)
    assert int(dut.dout.value) == 0  # Now properly initialized
```

```python
# WRONG: Checking at cycle 17 based on documentation
{"cycle": 17, "signals": {"dout": "0x1"}}

# CORRECT: Checking at cycle 18 based on actual VHDL timing
# (Result appears 18 cycles after wr='1' due to tool behavior)
{"cycle": 18, "signals": {"dout": "0x1"}}
```

**Deliverables:**
- Fixed Makefile and test infrastructure
- Corrected test vectors matching actual VHDL behavior
- Documentation of VHDL timing quirks and tool-specific behavior
- Isolated test cases demonstrating correct behavior
- Updated test execution procedures if needed

**Tools for Debugging:**
```bash
# Check simulator version
ghdl --version

# Verify CocoTB installation
cocotb-config --version
cocotb-config --lib-name-path vpi ghdl

# Test VHDL compilation directly
ghdl -a --std=08 --ieee=synopsys vhdl/module.vhd
ghdl -e --std=08 --ieee=synopsys module

# Enable verbose CocoTB logging
export COCOTB_LOG_LEVEL=DEBUG
make test_<module>
```

**When to Move to Step 1:**
Once all tests execute without infrastructure errors (even if they fail on assertions), and you've validated the test infrastructure is correctly configured, proceed to Step 1 for new module development or continue with test execution for existing modules.

#### Step 1: Module Analysis
```python
# Template: analyze_module.py
"""
Analyze VHDL module and extract:
- Entity ports
- Architecture signals
- State machines
- Timing requirements
"""
```

**Deliverables:**
- `docs/verification/modules/<module>-analysis.md`
- Signal timing diagrams
- State transition tables

#### Step 2: Test Development
```python
# Template: verification/cocotb/tests/test_<module>.py

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge
from verification.cocotb.util.test_vectors import TestVectorLoader

# Load test vectors from JSON
loader = TestVectorLoader("../../test-vectors")

@cocotb.test()
async def test_from_vectors(dut):
    """Run all test cases from JSON vectors"""
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    test_cases = loader.get_test_cases("<module-name>")

    for tc in test_cases:
        # Setup initial state
        for signal, value in tc['initial_state'].items():
            val = loader.parse_value(value)
            if val is not None:
                getattr(dut, signal).value = val

        # Apply inputs per cycle
        for inp in tc.get('inputs', []):
            await RisingEdge(dut.clk)
            for signal, value in inp['signals'].items():
                val = loader.parse_value(value)
                if val is not None:
                    getattr(dut, signal).value = val

        # Verify expected state
        await RisingEdge(dut.clk)
        for signal, value in tc['expected_state'].items():
            expected = loader.parse_value(value)
            if expected is not None:
                actual = int(getattr(dut, signal).value)
                assert actual == expected, \
                    f"{tc['name']}: {signal} = {hex(actual)}, expected {hex(expected)}"
```

**Deliverables:**
- `verification/cocotb/tests/test_<module>.py`
- Test fixtures in `verification/cocotb/fixtures/`

#### Step 3: Create Test Vectors

Test vectors are maintained in JSON format (see `docs/test-vectors/test-vector-format.md`).

```json
{
  "module": "<module-name>",
  "version": "1.0.0",
  "description": "Test vectors for <module>",
  "metadata": {
    "author": "vhdl-tester",
    "created": "2025-01-15T10:00:00Z",
    "tags": ["pipeline", "core"]
  },
  "test_cases": [
    {
      "name": "reset_clears_registers",
      "type": "reset",
      "description": "Verify reset clears all registers",
      "tags": ["reset", "basic"],
      "initial_state": {
        "tos": "0xDEAD",
        "nos": "0xBEEF",
        "sp": "0x5"
      },
      "inputs": [
        {
          "cycle": 0,
          "signals": {"reset": "0x1"}
        }
      ],
      "expected_state": {
        "tos": "0x0",
        "nos": "0x0",
        "sp": "0x0"
      },
      "cycles": 1
    }
  ]
}
```

**Deliverables:**
- JSON test vector files in `verification/test-vectors/modules/`
- Test vectors for all microcode instructions
- Edge case vectors (overflow, underflow, etc.)
- Validated against schema

#### Step 4: Validate Coverage
```bash
# Run tests with coverage
pytest verification/cocotb/tests/ --cov=verification --cov-report=html

# Verify microcode coverage
python tools/scripts/verify_microcode_coverage.py
```

**Deliverables:**
- Coverage reports (HTML)
- `docs/verification/<module>-coverage.md`
- Microcode instruction coverage matrix

### Output Artifacts
```
verification/cocotb/
├── tests/
│   ├── test_fetch.py
│   ├── test_decode.py
│   ├── test_execute.py
│   └── test_stack.py
├── fixtures/
│   ├── vectors_fetch.py
│   ├── vectors_decode.py
│   └── golden_outputs/
├── testbenches/
│   └── tb_<module>.vhd (if needed)
└── Makefile

docs/verification/
├── modules/
│   ├── fetch-analysis.md
│   ├── decode-analysis.md
│   └── execute-analysis.md
├── coverage/
│   ├── overall-coverage.md
│   └── microcode-coverage-matrix.md
└── test-results/
    └── <timestamp>-results.json
```

## Success Criteria

- [ ] All microcode instructions have tests
- [ ] Cycle-accurate verification passing
- [ ] 100% microcode coverage
- [ ] All register states verified
- [ ] Edge cases documented and tested
- [ ] Tests pass consistently on original VHDL
- [ ] Golden outputs captured for SpinalHDL validation

## Handoff to Next Agent

### To spinalhdl-developer:
- Test vectors and expected behaviors
- Timing requirements
- Module interface specifications
- Critical path documentation

### To spinalhdl-tester:
- CocoTB test structure
- Test vectors for porting
- Expected behaviors
- Coverage requirements

## Example Test Session

```bash
# Setup environment
cd verification/cocotb
python -m venv venv
source venv/bin/activate
pip install cocotb pytest ghdl

# Run tests for specific module
make test_fetch

# Run full suite
make test_all

# Generate coverage report
make coverage

# Validate against golden outputs
make validate_golden
```

## Notes

- Each test should be independent and deterministic
- Use meaningful test names that describe the behavior
- Document any deviations from expected VHDL behavior
- Keep test execution time reasonable (< 5 min for full suite)
- Version control all test vectors and golden outputs
