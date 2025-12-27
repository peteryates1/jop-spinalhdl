"""
CocoTB tests for mul.vhd (bit-serial multiplier)

This test suite validates the original VHDL implementation using
test vectors from verification/test-vectors/modules/mul.json

The multiplier is a radix-4 bit-serial implementation with:
- 17 cycle latency from wr='1' to final result
- Unsigned multiplication only
- 32-bit operands and result (lower 32 bits of full product)
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, Timer
import sys
from pathlib import Path

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))
from util.test_vectors import TestVectorLoader

# Load test vectors
loader = TestVectorLoader("../../test-vectors")


@cocotb.test()
async def test_mul_startup(dut):
    """Test initial state before any multiplication"""

    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Wait a few cycles
    await RisingEdge(dut.clk)
    await RisingEdge(dut.clk)

    # Output should be zero at startup
    assert int(dut.dout.value) == 0, \
        f"Startup output should be 0, got {int(dut.dout.value):#x}"

    dut._log.info("✓ Startup state verified")


@cocotb.test()
async def test_mul_from_vectors(dut):
    """Test multiplier using JSON test vectors"""

    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Load test vectors
    test_cases = loader.get_test_cases("mul")

    # Skip startup test (already tested separately)
    test_cases = [tc for tc in test_cases if tc['name'] != 'startup_output_zero']

    for tc in test_cases:
        dut._log.info(f"\n{'='*60}")
        dut._log.info(f"Running: {tc['name']}")
        dut._log.info(f"Description: {tc['description']}")
        dut._log.info(f"{'='*60}")

        # Initialize inputs to known state
        dut.wr.value = 0
        dut.ain.value = 0
        dut.bin.value = 0

        # Wait a cycle
        await RisingEdge(dut.clk)

        # Track cycle count
        current_cycle = 0

        # Apply inputs according to test vector
        for inp in tc.get('inputs', []):
            target_cycle = inp['cycle']

            # Wait until target cycle
            while current_cycle < target_cycle:
                await RisingEdge(dut.clk)
                current_cycle += 1

            # Apply signals
            for signal, value in inp['signals'].items():
                val = loader.parse_value(value)
                if val is not None and hasattr(dut, signal):
                    getattr(dut, signal).value = val
                    dut._log.debug(f"  Cycle {current_cycle}: {signal} = {value}")

        # Continue simulation until all expected outputs can be checked
        max_cycle = tc.get('cycles', 20)

        # Check expected outputs
        for out in tc.get('expected_outputs', []):
            target_cycle = out['cycle']

            # Wait until target cycle
            while current_cycle < target_cycle:
                await RisingEdge(dut.clk)
                current_cycle += 1

            # Check signals
            for signal, expected_str in out['signals'].items():
                expected = loader.parse_value(expected_str)
                if expected is not None and hasattr(dut, signal):
                    actual = int(getattr(dut, signal).value)

                    if actual != expected:
                        # Get input values for error message
                        ain_val = "?"
                        bin_val = "?"
                        for inp in tc.get('inputs', []):
                            if 'ain' in inp['signals']:
                                ain_val = inp['signals']['ain']
                            if 'bin' in inp['signals']:
                                bin_val = inp['signals']['bin']

                        dut._log.error(f"  ✗ FAILED at cycle {current_cycle}")
                        dut._log.error(f"    Input: {ain_val} × {bin_val}")
                        dut._log.error(f"    Expected: {signal} = {expected_str} ({expected:#x})")
                        dut._log.error(f"    Actual:   {signal} = {actual:#x}")

                        assert actual == expected, \
                            f"{tc['name']}: {signal} = {actual:#x}, expected {expected:#x}"
                    else:
                        dut._log.info(f"  ✓ Cycle {current_cycle}: {signal} = {expected_str} ({expected:#x})")

        # Run to completion
        while current_cycle < max_cycle:
            await RisingEdge(dut.clk)
            current_cycle += 1

        dut._log.info(f"✓ Test '{tc['name']}' PASSED")


@cocotb.test()
async def test_mul_timing_detailed(dut):
    """Detailed timing test - verify result appears exactly at cycle 17"""

    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "="*60)
    dut._log.info("Detailed Timing Test: 7 × 8 = 56")
    dut._log.info("="*60)

    # Initialize
    dut.wr.value = 0
    dut.ain.value = 0
    dut.bin.value = 0
    await RisingEdge(dut.clk)

    # Cycle 0: Start multiplication
    dut._log.info("Cycle 0: Starting multiplication (wr='1', ain=7, bin=8)")
    dut.ain.value = 7
    dut.bin.value = 8
    dut.wr.value = 1
    await RisingEdge(dut.clk)

    # Cycle 1: Deassert wr
    dut._log.info("Cycle 1: Deassert wr, multiplication in progress")
    dut.wr.value = 0
    await RisingEdge(dut.clk)

    # Cycles 2-16: Multiplication in progress
    for i in range(2, 17):
        dut._log.debug(f"Cycle {i}: Computing... (dout={int(dut.dout.value):#x})")
        await RisingEdge(dut.clk)

    # Cycle 17: Result should be ready
    dut._log.info(f"Cycle 17: Result should be ready")
    actual = int(dut.dout.value)
    expected = 56

    assert actual == expected, \
        f"At cycle 17: dout = {actual:#x}, expected {expected:#x}"

    dut._log.info(f"✓ Cycle 17: dout = {actual:#x} (correct!)")
    dut._log.info("✓ Timing test PASSED")


@cocotb.test()
async def test_mul_pipelining(dut):
    """Test that new multiplication can overwrite in-progress multiplication"""

    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "="*60)
    dut._log.info("Pipelining Test: Start second mult before first completes")
    dut._log.info("="*60)

    # Initialize
    dut.wr.value = 0
    dut.ain.value = 0
    dut.bin.value = 0
    await RisingEdge(dut.clk)

    # Cycle 0: Start first multiplication (10 × 11 = 110)
    dut._log.info("Cycle 0: Start first multiplication (10 × 11 = 110)")
    dut.ain.value = 10
    dut.bin.value = 11
    dut.wr.value = 1
    await RisingEdge(dut.clk)

    # Cycle 1: Start second multiplication IMMEDIATELY (5 × 6 = 30)
    # This should overwrite the first multiplication
    dut._log.info("Cycle 1: Start second multiplication (5 × 6 = 30) - overwrites first!")
    dut.ain.value = 5
    dut.bin.value = 6
    dut.wr.value = 1
    await RisingEdge(dut.clk)

    # Cycle 2: Deassert wr
    dut.wr.value = 0
    await RisingEdge(dut.clk)

    # Wait for result (cycle 18 = cycle 1 + 17)
    for i in range(3, 19):
        await RisingEdge(dut.clk)

    # Check result - should be 30 (second multiplication), not 110
    actual = int(dut.dout.value)
    expected = 30

    dut._log.info(f"Cycle 18: Result = {actual:#x}")

    assert actual == expected, \
        f"Result should be {expected:#x} (second mult), got {actual:#x}"

    dut._log.info(f"✓ Pipelining works correctly - second mult overwrote first")
    dut._log.info("✓ Pipelining test PASSED")


@cocotb.test()
async def test_mul_edge_cases_summary(dut):
    """Summary test of critical edge cases"""

    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "="*60)
    dut._log.info("Edge Cases Summary Test")
    dut._log.info("="*60)

    edge_cases = [
        (0, 123, 0, "multiply by zero (a)"),
        (456, 0, 0, "multiply by zero (b)"),
        (1, 789, 789, "multiply by one (a)"),
        (999, 1, 999, "multiply by one (b)"),
        (0xFFFFFFFF, 0x2, 0xFFFFFFFE, "overflow test"),
        (0xFFFFFFFF, 0xFFFFFFFF, 0x1, "max overflow"),
    ]

    for ain, bin_val, expected, description in edge_cases:
        dut._log.info(f"\n  Testing: {description}")
        dut._log.info(f"    {ain:#x} × {bin_val:#x} = {expected:#x}")

        # Initialize
        dut.wr.value = 0
        await RisingEdge(dut.clk)

        # Start multiplication
        dut.ain.value = ain
        dut.bin.value = bin_val
        dut.wr.value = 1
        await RisingEdge(dut.clk)

        # Deassert wr
        dut.wr.value = 0
        await RisingEdge(dut.clk)

        # Wait 16 more cycles (total 17 from wr='1')
        for _ in range(16):
            await RisingEdge(dut.clk)

        # Check result
        actual = int(dut.dout.value)

        if actual != expected:
            dut._log.error(f"    ✗ FAILED: got {actual:#x}")
            assert False, f"{description}: got {actual:#x}, expected {expected:#x}"
        else:
            dut._log.info(f"    ✓ PASSED: {actual:#x}")

    dut._log.info("\n✓ All edge cases PASSED")
