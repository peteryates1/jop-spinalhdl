"""
Example CocoTB test using JSON test vectors

This serves as a template for testing JOP modules.
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, FallingEdge, Timer
from pathlib import Path
import sys

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

from util.test_vectors import TestVectorLoader


# Initialize test vector loader
loader = TestVectorLoader("../test-vectors")


@cocotb.test()
async def test_example_from_vectors(dut):
    """
    Example test that loads test cases from JSON vectors

    This template shows how to:
    - Load test vectors
    - Setup clock and reset
    - Apply initial state
    - Run through test cycles
    - Verify expected outputs
    """
    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Load test vectors (replace 'example' with actual module name)
    try:
        test_cases = loader.get_test_cases("bytecode-fetch")
    except FileNotFoundError:
        dut._log.warning("No test vectors found - skipping test")
        return

    # Run each test case
    for tc in test_cases:
        dut._log.info(f"Running test case: {tc['name']}")
        dut._log.info(f"  Description: {tc.get('description', 'N/A')}")

        # Apply reset (common for most tests)
        dut.reset.value = 1
        await RisingEdge(dut.clk)
        dut.reset.value = 0
        await RisingEdge(dut.clk)

        # Setup initial state
        for signal, value in tc.get('initial_state', {}).items():
            val = loader.parse_value(value)
            if val is not None and hasattr(dut, signal):
                getattr(dut, signal).value = val
                dut._log.debug(f"  Init {signal} = {hex(val)}")

        # Apply inputs per cycle
        for inp in tc.get('inputs', []):
            cycle = inp['cycle']
            dut._log.debug(f"  Cycle {cycle}: applying inputs")

            for signal, value in inp['signals'].items():
                val = loader.parse_value(value)
                if val is not None and hasattr(dut, signal):
                    getattr(dut, signal).value = val

            await RisingEdge(dut.clk)

        # Check expected outputs (cycle-by-cycle)
        for out in tc.get('expected_outputs', []):
            cycle = out['cycle']
            # Note: May need to adjust timing based on when outputs appear

            for signal, expected_str in out['signals'].items():
                expected = loader.parse_value(expected_str)
                if expected is not None and hasattr(dut, signal):
                    actual = int(getattr(dut, signal).value)
                    assert actual == expected, \
                        f"Cycle {cycle}: {signal} = {hex(actual)}, expected {hex(expected)}"

        # Wait remaining cycles
        total_cycles = tc['cycles']
        await RisingEdge(dut.clk)

        # Verify final expected state
        for signal, expected_str in tc.get('expected_state', {}).items():
            expected = loader.parse_value(expected_str)
            if expected is not None and hasattr(dut, signal):
                actual = int(getattr(dut, signal).value)
                assert actual == expected, \
                    f"{tc['name']}: {signal} = {hex(actual)}, expected {hex(expected)}"

        # Check custom assertions
        for assertion in tc.get('assertions', []):
            cycle = assertion['cycle']
            signal = assertion['signal']
            operator = assertion['operator']
            value_str = assertion['value']
            message = assertion.get('message', f"Assertion failed on {signal}")

            value = loader.parse_value(value_str)
            if hasattr(dut, signal):
                actual = int(getattr(dut, signal).value)

                if operator == '==':
                    assert actual == value, message
                elif operator == '!=':
                    assert actual != value, message
                elif operator == '<':
                    assert actual < value, message
                elif operator == '>':
                    assert actual > value, message
                elif operator == '<=':
                    assert actual <= value, message
                elif operator == '>=':
                    assert actual >= value, message

        dut._log.info(f"  ✓ {tc['name']} PASSED")


@cocotb.test()
async def test_example_manual(dut):
    """
    Example manual test (not using test vectors)

    Sometimes you need custom test logic that doesn't fit
    the test vector format.
    """
    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Apply reset
    dut.reset.value = 1
    await RisingEdge(dut.clk)
    dut.reset.value = 0
    await RisingEdge(dut.clk)

    # Custom test logic here
    # ...

    dut._log.info("Manual test passed")
