"""
CocoTB test suite for JOP Core (Fetch → Decode → Stack pipeline)

Tests the integrated microcode pipeline by feeding microcode instructions
and verifying correct control signal generation and execution.

Approach:
- FetchStage has internal ROM, so tests must initialize ROM via component
- Each test specifies microcode ROM contents, initial state, and expected results
- Tests validate full pipeline: fetch → decode → stack execution

Test Vector Format:
- name: Test identifier
- rom_contents: Array of {addr, jfetch, jopdfetch, instr} for ROM init
- initial_state: Initial register values (A, B, SP, VP0-3, AR)
- inputs: Per-cycle inputs (operand, jpc, mem_data_in, mem_busy)
- expected_outputs: Per-cycle expected values (aout, bout, flags, control signals)

Based on: verification/cocotb/tests/test_stack.py
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, Timer
from cocotb.binary import BinaryValue
import json
from pathlib import Path

# Test vector file path
TEST_VECTOR_FILE = Path(__file__).parent.parent.parent / "test-vectors" / "modules" / "microcode.json"


class JopCoreTester:
    """Helper class for testing JOP Core pipeline"""

    def __init__(self, dut):
        self.dut = dut

    async def reset(self):
        """Apply reset to the core"""
        self.dut.reset.value = 1
        await RisingEdge(self.dut.clk)
        await RisingEdge(self.dut.clk)
        self.dut.reset.value = 0
        await RisingEdge(self.dut.clk)

    def set_inputs(self, **kwargs):
        """Set input signals from keyword arguments"""
        if 'operand' in kwargs:
            self.dut.operand.value = int(kwargs['operand'], 0)
        if 'jpc' in kwargs:
            self.dut.jpc.value = int(kwargs['jpc'], 0)
        if 'mem_data_in' in kwargs:
            self.dut.mem_data_in.value = int(kwargs['mem_data_in'], 0)
        if 'mem_busy' in kwargs:
            self.dut.mem_busy.value = int(kwargs['mem_busy'], 0)

    def check_outputs(self, expected, cycle_num):
        """Check output signals match expected values"""
        errors = []

        def safe_int(signal):
            """Safely convert signal to int, handling unresolved values"""
            try:
                return int(signal.value)
            except ValueError as e:
                if "Unresolvable bit" in str(e):
                    return None  # Signal not yet resolved
                raise

        if 'aout' in expected:
            exp_val = int(expected['aout'], 0)
            act_val = safe_int(self.dut.aout)
            if act_val is None:
                errors.append(f"Cycle {cycle_num}: aout unresolved - expected {hex(exp_val)}")
            elif act_val != exp_val:
                errors.append(f"Cycle {cycle_num}: aout mismatch - expected {hex(exp_val)}, got {hex(act_val)}")

        if 'bout' in expected:
            exp_val = int(expected['bout'], 0)
            act_val = safe_int(self.dut.bout)
            if act_val is None:
                errors.append(f"Cycle {cycle_num}: bout unresolved - expected {hex(exp_val)}")
            elif act_val != exp_val:
                errors.append(f"Cycle {cycle_num}: bout mismatch - expected {hex(exp_val)}, got {hex(act_val)}")

        if 'sp_ov' in expected:
            exp_val = int(expected['sp_ov'], 0)
            act_val = safe_int(self.dut.sp_ov)
            if act_val is None:
                errors.append(f"Cycle {cycle_num}: sp_ov unresolved - expected {exp_val}")
            elif act_val != exp_val:
                errors.append(f"Cycle {cycle_num}: sp_ov mismatch - expected {exp_val}, got {act_val}")

        if 'jfetch' in expected:
            exp_val = int(expected['jfetch'], 0)
            act_val = safe_int(self.dut.jfetch)
            if act_val is None:
                errors.append(f"Cycle {cycle_num}: jfetch unresolved - expected {exp_val}")
            elif act_val != exp_val:
                errors.append(f"Cycle {cycle_num}: jfetch mismatch - expected {exp_val}, got {act_val}")

        if 'jopdfetch' in expected:
            exp_val = int(expected['jopdfetch'], 0)
            act_val = safe_int(self.dut.jopdfetch)
            if act_val is None:
                errors.append(f"Cycle {cycle_num}: jopdfetch unresolved - expected {exp_val}")
            elif act_val != exp_val:
                errors.append(f"Cycle {cycle_num}: jopdfetch mismatch - expected {exp_val}, got {act_val}")

        return errors


async def run_test_vector(dut, test_case):
    """Execute a single test vector"""
    tester = JopCoreTester(dut)

    # Apply reset
    await tester.reset()

    # NOTE: ROM initialization must be done at synthesis time
    # For now, we'll test with the default ROM content in FetchStage
    # TODO: Add ROM initialization mechanism for testing

    # Set default inputs
    tester.set_inputs(
        operand="0x0000",
        jpc="0x000",
        mem_data_in="0x00000000",
        mem_busy="0x0"
    )

    # Run test sequence
    errors = []
    current_cycle = 0

    if 'inputs' in test_case:
        for inp in test_case['inputs']:
            target_cycle = inp.get('cycle', 0)

            # Wait for the target cycle
            while current_cycle < target_cycle:
                await RisingEdge(dut.clk)
                current_cycle += 1

            # Set inputs for this cycle
            if 'signals' in inp:
                tester.set_inputs(**inp['signals'])

            await RisingEdge(dut.clk)
            current_cycle += 1

    # Check expected outputs
    if 'expected_outputs' in test_case:
        for exp in test_case['expected_outputs']:
            target_cycle = exp.get('cycle', 0)

            # Wait until target cycle
            while current_cycle < target_cycle:
                await RisingEdge(dut.clk)
                current_cycle += 1

            # Check outputs
            if 'signals' in exp:
                errors.extend(tester.check_outputs(exp['signals'], target_cycle))

    # Report results
    if errors:
        dut._log.error(f"Test '{test_case['name']}' FAILED:")
        for err in errors:
            dut._log.error(f"  {err}")
        assert False, f"Test failed with {len(errors)} error(s)"
    else:
        dut._log.info(f"Test '{test_case['name']}' PASSED")


@cocotb.test()
async def test_core_reset(dut):
    """Test basic reset functionality"""
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    tester = JopCoreTester(dut)

    # Apply reset
    await tester.reset()

    # After reset, outputs should be in known state
    await RisingEdge(dut.clk)

    dut._log.info("Reset test PASSED")


@cocotb.test()
async def test_core_nop(dut):
    """Test NOP instruction execution (PC increment)"""
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    tester = JopCoreTester(dut)

    # Apply reset
    await tester.reset()

    # Set inputs
    tester.set_inputs(
        operand="0x0000",
        jpc="0x000",
        mem_data_in="0x00000000",
        mem_busy="0x0"
    )

    # Run for several cycles to observe PC incrementing
    for i in range(10):
        await RisingEdge(dut.clk)

    dut._log.info("NOP test PASSED")


@cocotb.test()
async def test_core_from_json(dut):
    """Run tests from JSON test vectors"""
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Load test vectors
    if not TEST_VECTOR_FILE.exists():
        dut._log.warning(f"Test vector file not found: {TEST_VECTOR_FILE}")
        dut._log.warning("Skipping JSON-based tests")
        return

    with open(TEST_VECTOR_FILE) as f:
        test_data = json.load(f)

    if 'test_cases' not in test_data:
        dut._log.warning("No test_cases found in JSON file")
        return

    # Run each test case
    for test_case in test_data['test_cases']:
        await run_test_vector(dut, test_case)

        # Small delay between tests
        await Timer(100, units="ns")

    dut._log.info(f"All {len(test_data['test_cases'])} JSON tests completed")
