"""
CocoTB tests for shift.vhd (barrel shifter)

This test suite validates the original VHDL implementation using
test vectors from verification/test-vectors/modules/shift.json

The barrel shifter is a purely combinational module with:
- 0 cycle latency (outputs update immediately with inputs)
- Three shift types: ushr (00), shl (01), shr (10)
- 5-bit shift amount (0-31 bits)
- Multi-stage shifting (16, 8, 4, 2, 1 bit stages)

Note: Since this is combinational logic with no clock, we use
Timer-based delays to allow signal propagation.
"""

import cocotb
from cocotb.triggers import Timer
import sys
from pathlib import Path

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))
from util.test_vectors import TestVectorLoader

# Load test vectors
loader = TestVectorLoader("../test-vectors")

# Shift type constants (matching VHDL)
SHTYP_USHR = 0x0  # Unsigned shift right (logical)
SHTYP_SHL = 0x1   # Shift left
SHTYP_SHR = 0x2   # Arithmetic shift right (signed)


def compute_expected_shift(din: int, off: int, shtyp: int) -> int:
    """
    Compute expected shift result using Python reference implementation.
    This matches the VHDL barrel shifter behavior.

    Args:
        din: 32-bit input value
        off: Shift amount (0-31)
        shtyp: Shift type (0=ushr, 1=shl, 2=shr)

    Returns:
        32-bit result
    """
    # Ensure 32-bit unsigned
    din = din & 0xFFFFFFFF
    off = off & 0x1F  # 5-bit shift amount

    if shtyp == SHTYP_USHR:
        # Unsigned shift right
        result = din >> off
    elif shtyp == SHTYP_SHL:
        # Shift left
        result = (din << off) & 0xFFFFFFFF
    elif shtyp == SHTYP_SHR:
        # Arithmetic shift right (sign extension)
        if din & 0x80000000:
            # Negative: fill with 1s
            if off == 0:
                result = din
            else:
                result = (din >> off) | (0xFFFFFFFF << (32 - off))
                result = result & 0xFFFFFFFF
        else:
            # Positive: fill with 0s (same as ushr)
            result = din >> off
    else:
        # Invalid shtyp - should not occur
        result = din

    return result & 0xFFFFFFFF


@cocotb.test()
async def test_shift_from_vectors(dut):
    """Test barrel shifter using JSON test vectors"""

    # Load test vectors
    test_cases = loader.get_test_cases("shift")

    dut._log.info("\n" + "=" * 60)
    dut._log.info(f"Running {len(test_cases)} test cases from JSON vectors")
    dut._log.info("=" * 60)

    passed = 0
    failed = 0

    for tc in test_cases:
        # Get inputs from first input cycle
        inputs = tc.get('inputs', [])
        if not inputs:
            continue

        signals = inputs[0].get('signals', {})
        din = loader.parse_value(signals.get('din', '0x0'))
        off = loader.parse_value(signals.get('off', '0x0'))
        shtyp = loader.parse_value(signals.get('shtyp', '0x0'))

        # Apply inputs
        dut.din.value = din
        dut.off.value = off
        dut.shtyp.value = shtyp

        # Wait for combinational logic to settle
        await Timer(1, units="ns")

        # Check expected outputs
        expected_outputs = tc.get('expected_outputs', [])
        if not expected_outputs:
            continue

        expected_str = expected_outputs[0].get('signals', {}).get('dout', '0x0')
        expected = loader.parse_value(expected_str)
        actual = int(dut.dout.value)

        if actual != expected:
            failed += 1
            dut._log.error(f"  FAILED: {tc['name']}")
            dut._log.error(f"    {tc['description']}")
            dut._log.error(f"    din={din:#010x}, off={off}, shtyp={shtyp}")
            dut._log.error(f"    expected={expected:#010x}, got={actual:#010x}")
        else:
            passed += 1
            dut._log.info(f"  PASSED: {tc['name']}")

    dut._log.info("\n" + "-" * 60)
    dut._log.info(f"Results: {passed} passed, {failed} failed out of {passed + failed} tests")
    dut._log.info("-" * 60)

    assert failed == 0, f"{failed} test(s) failed"


@cocotb.test()
async def test_shift_exhaustive_amounts(dut):
    """Exhaustively test all shift amounts (0-31) for each shift type"""

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Exhaustive Shift Amount Tests (0-31)")
    dut._log.info("=" * 60)

    # Test value
    test_value = 0x80000001

    failed = 0

    # Test USHR
    dut._log.info("Testing USHR for all shift amounts...")
    for off in range(32):
        dut.din.value = test_value
        dut.off.value = off
        dut.shtyp.value = SHTYP_USHR

        await Timer(1, units="ns")

        expected = compute_expected_shift(test_value, off, SHTYP_USHR)
        actual = int(dut.dout.value)

        if actual != expected:
            failed += 1
            dut._log.error(f"  USHR off={off}: expected {expected:#010x}, got {actual:#010x}")

    # Test SHL
    dut._log.info("Testing SHL for all shift amounts...")
    for off in range(32):
        dut.din.value = test_value
        dut.off.value = off
        dut.shtyp.value = SHTYP_SHL

        await Timer(1, units="ns")

        expected = compute_expected_shift(test_value, off, SHTYP_SHL)
        actual = int(dut.dout.value)

        if actual != expected:
            failed += 1
            dut._log.error(f"  SHL off={off}: expected {expected:#010x}, got {actual:#010x}")

    # Test SHR
    dut._log.info("Testing SHR for all shift amounts...")
    for off in range(32):
        dut.din.value = test_value
        dut.off.value = off
        dut.shtyp.value = SHTYP_SHR

        await Timer(1, units="ns")

        expected = compute_expected_shift(test_value, off, SHTYP_SHR)
        actual = int(dut.dout.value)

        if actual != expected:
            failed += 1
            dut._log.error(f"  SHR off={off}: expected {expected:#010x}, got {actual:#010x}")

    if failed == 0:
        dut._log.info("All exhaustive shift amount tests PASSED")
    else:
        assert False, f"{failed} exhaustive test(s) failed"


@cocotb.test()
async def test_shift_sign_extension(dut):
    """Test arithmetic shift right sign extension in detail"""

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Sign Extension Tests for Arithmetic Shift Right")
    dut._log.info("=" * 60)

    # Various negative numbers
    negative_values = [
        0x80000000,  # -2147483648 (minimum 32-bit signed)
        0xFFFFFFFF,  # -1
        0xFFFFFFFE,  # -2
        0xF0000000,  # Large negative
        0xC0000000,  # Another negative
        0x80000001,  # Near minimum
    ]

    failed = 0

    for din in negative_values:
        dut._log.info(f"  Testing din={din:#010x}")
        for off in [1, 4, 8, 16, 24, 31]:
            dut.din.value = din
            dut.off.value = off
            dut.shtyp.value = SHTYP_SHR

            await Timer(1, units="ns")

            expected = compute_expected_shift(din, off, SHTYP_SHR)
            actual = int(dut.dout.value)

            if actual != expected:
                failed += 1
                dut._log.error(f"    off={off}: expected {expected:#010x}, got {actual:#010x}")
            else:
                # Verify high bits are all 1s for negative numbers
                high_bits = actual >> (32 - off) if off > 0 else 0
                expected_high = (1 << off) - 1 if off > 0 else 0
                dut._log.debug(f"    off={off}: {actual:#010x} (high {off} bits = {high_bits:#x})")

    if failed == 0:
        dut._log.info("All sign extension tests PASSED")
    else:
        assert False, f"{failed} sign extension test(s) failed"


@cocotb.test()
async def test_shift_patterns(dut):
    """Test various bit patterns to catch any shifting errors"""

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Bit Pattern Tests")
    dut._log.info("=" * 60)

    patterns = [
        (0xAAAAAAAA, "alternating 10"),
        (0x55555555, "alternating 01"),
        (0x0F0F0F0F, "nibble pattern 0F"),
        (0xF0F0F0F0, "nibble pattern F0"),
        (0x00FF00FF, "byte pattern 00FF"),
        (0xFF00FF00, "byte pattern FF00"),
        (0x0000FFFF, "half word low"),
        (0xFFFF0000, "half word high"),
        (0x12345678, "sequential nibbles"),
        (0xFEDCBA98, "reverse sequential"),
    ]

    failed = 0

    for pattern, description in patterns:
        dut._log.info(f"  Testing {description} ({pattern:#010x})")

        for shtyp, shtyp_name in [(SHTYP_USHR, "ushr"), (SHTYP_SHL, "shl"), (SHTYP_SHR, "shr")]:
            for off in [1, 4, 8, 16]:
                dut.din.value = pattern
                dut.off.value = off
                dut.shtyp.value = shtyp

                await Timer(1, units="ns")

                expected = compute_expected_shift(pattern, off, shtyp)
                actual = int(dut.dout.value)

                if actual != expected:
                    failed += 1
                    dut._log.error(f"    {shtyp_name} off={off}: expected {expected:#010x}, got {actual:#010x}")

    if failed == 0:
        dut._log.info("All bit pattern tests PASSED")
    else:
        assert False, f"{failed} bit pattern test(s) failed"


