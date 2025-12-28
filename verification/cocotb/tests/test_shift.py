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
async def test_shift_basic_ushr(dut):
    """Test basic unsigned shift right operations"""

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Basic Unsigned Shift Right (ushr) Tests")
    dut._log.info("=" * 60)

    test_cases = [
        (0x80000000, 0, 0x80000000, "shift by 0"),
        (0x80000000, 1, 0x40000000, "shift by 1"),
        (0xFFFFFFFF, 16, 0x0000FFFF, "shift by 16"),
        (0x80000000, 31, 0x00000001, "shift by 31"),
        (0x00000000, 16, 0x00000000, "zero shifted"),
    ]

    for din, off, expected, description in test_cases:
        dut.din.value = din
        dut.off.value = off
        dut.shtyp.value = SHTYP_USHR

        # Wait for combinational logic to settle
        await Timer(1, units="ns")

        actual = int(dut.dout.value)

        if actual != expected:
            dut._log.error(f"  FAILED: {description}")
            dut._log.error(f"    din={din:#010x}, off={off}, expected={expected:#010x}, got={actual:#010x}")
            assert False, f"USHR {description}: expected {expected:#010x}, got {actual:#010x}"
        else:
            dut._log.info(f"  PASSED: {description} - {din:#010x} >>> {off} = {actual:#010x}")

    dut._log.info("All basic USHR tests PASSED")


@cocotb.test()
async def test_shift_basic_shl(dut):
    """Test basic shift left operations"""

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Basic Shift Left (shl) Tests")
    dut._log.info("=" * 60)

    test_cases = [
        (0xDEADBEEF, 0, 0xDEADBEEF, "shift by 0"),
        (0x40000000, 1, 0x80000000, "shift by 1"),
        (0x0000FFFF, 16, 0xFFFF0000, "shift by 16"),
        (0x00000001, 31, 0x80000000, "shift by 31"),
        (0x00000000, 16, 0x00000000, "zero shifted"),
    ]

    for din, off, expected, description in test_cases:
        dut.din.value = din
        dut.off.value = off
        dut.shtyp.value = SHTYP_SHL

        # Wait for combinational logic to settle
        await Timer(1, units="ns")

        actual = int(dut.dout.value)

        if actual != expected:
            dut._log.error(f"  FAILED: {description}")
            dut._log.error(f"    din={din:#010x}, off={off}, expected={expected:#010x}, got={actual:#010x}")
            assert False, f"SHL {description}: expected {expected:#010x}, got {actual:#010x}"
        else:
            dut._log.info(f"  PASSED: {description} - {din:#010x} << {off} = {actual:#010x}")

    dut._log.info("All basic SHL tests PASSED")


@cocotb.test()
async def test_shift_basic_shr(dut):
    """Test basic arithmetic shift right operations"""

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Basic Arithmetic Shift Right (shr) Tests")
    dut._log.info("=" * 60)

    test_cases = [
        # Positive numbers (no sign extension)
        (0x7FFFFFFE, 1, 0x3FFFFFFF, "positive shift by 1"),
        (0x7FFFFFFF, 31, 0x00000000, "positive shift by 31"),
        # Negative numbers (sign extension)
        (0x80000000, 1, 0xC0000000, "negative shift by 1"),
        (0x80000000, 31, 0xFFFFFFFF, "negative shift by 31"),
        (0xFFFFFFFF, 16, 0xFFFFFFFF, "-1 shifted stays -1"),
        (0xF0000000, 4, 0xFF000000, "negative shift by 4"),
    ]

    for din, off, expected, description in test_cases:
        dut.din.value = din
        dut.off.value = off
        dut.shtyp.value = SHTYP_SHR

        # Wait for combinational logic to settle
        await Timer(1, units="ns")

        actual = int(dut.dout.value)

        if actual != expected:
            dut._log.error(f"  FAILED: {description}")
            dut._log.error(f"    din={din:#010x}, off={off}, expected={expected:#010x}, got={actual:#010x}")
            assert False, f"SHR {description}: expected {expected:#010x}, got {actual:#010x}"
        else:
            dut._log.info(f"  PASSED: {description} - {din:#010x} >> {off} = {actual:#010x}")

    dut._log.info("All basic SHR tests PASSED")


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


@cocotb.test()
async def test_shift_java_bytecode_operations(dut):
    """Test shift operations matching Java bytecode semantics"""

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Java Bytecode Shift Operation Tests")
    dut._log.info("=" * 60)
    dut._log.info("Testing operations matching JVM ishl, ishr, iushr")

    # Java-style test cases
    # ishl (shift left): value << (amount & 0x1F)
    # ishr (arithmetic shift right): value >> (amount & 0x1F)
    # iushr (logical shift right): value >>> (amount & 0x1F)

    test_cases = [
        # (din, off, shtyp, expected, description)
        # ishl examples
        (1, 5, SHTYP_SHL, 32, "1 << 5 = 32"),
        (0xFF, 8, SHTYP_SHL, 0xFF00, "255 << 8 = 65280"),
        (0x7FFFFFFF, 1, SHTYP_SHL, 0xFFFFFFFE, "MAX_INT << 1 (overflow)"),

        # ishr examples
        (32, 5, SHTYP_SHR, 1, "32 >> 5 = 1"),
        (0xFFFFFF00, 4, SHTYP_SHR, 0xFFFFFFF0, "-256 >> 4 = -16"),
        (0x80000000, 31, SHTYP_SHR, 0xFFFFFFFF, "MIN_INT >> 31 = -1"),

        # iushr examples
        (0xFFFFFFFF, 24, SHTYP_USHR, 0xFF, "-1 >>> 24 = 255"),
        (0x80000000, 1, SHTYP_USHR, 0x40000000, "MIN_INT >>> 1"),
        (0x80000000, 31, SHTYP_USHR, 1, "MIN_INT >>> 31 = 1"),
    ]

    failed = 0

    for din, off, shtyp, expected, description in test_cases:
        shtyp_name = ["ushr", "shl", "shr"][shtyp]

        dut.din.value = din & 0xFFFFFFFF
        dut.off.value = off
        dut.shtyp.value = shtyp

        await Timer(1, units="ns")

        actual = int(dut.dout.value)

        if actual != expected:
            failed += 1
            dut._log.error(f"  FAILED: {description}")
            dut._log.error(f"    din={din:#010x}, off={off}, {shtyp_name}")
            dut._log.error(f"    expected={expected:#010x}, got={actual:#010x}")
        else:
            dut._log.info(f"  PASSED: {description}")

    if failed == 0:
        dut._log.info("All Java bytecode operation tests PASSED")
    else:
        assert False, f"{failed} Java bytecode test(s) failed"


@cocotb.test()
async def test_shift_edge_cases_summary(dut):
    """Summary test of critical edge cases"""

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Edge Cases Summary Test")
    dut._log.info("=" * 60)

    edge_cases = [
        # (din, off, shtyp, expected, description)
        # Zero shift
        (0xDEADBEEF, 0, SHTYP_USHR, 0xDEADBEEF, "ushr by 0"),
        (0xDEADBEEF, 0, SHTYP_SHL, 0xDEADBEEF, "shl by 0"),
        (0xDEADBEEF, 0, SHTYP_SHR, 0xDEADBEEF, "shr by 0"),

        # Maximum shift
        (0x80000000, 31, SHTYP_USHR, 0x00000001, "ushr by 31"),
        (0x00000001, 31, SHTYP_SHL, 0x80000000, "shl by 31"),
        (0x80000000, 31, SHTYP_SHR, 0xFFFFFFFF, "shr negative by 31"),
        (0x7FFFFFFF, 31, SHTYP_SHR, 0x00000000, "shr positive by 31"),

        # Zero value
        (0x00000000, 16, SHTYP_USHR, 0x00000000, "zero ushr"),
        (0x00000000, 16, SHTYP_SHL, 0x00000000, "zero shl"),
        (0x00000000, 16, SHTYP_SHR, 0x00000000, "zero shr"),

        # All ones
        (0xFFFFFFFF, 16, SHTYP_USHR, 0x0000FFFF, "all 1s ushr by 16"),
        (0xFFFFFFFF, 16, SHTYP_SHL, 0xFFFF0000, "all 1s shl by 16"),
        (0xFFFFFFFF, 16, SHTYP_SHR, 0xFFFFFFFF, "all 1s shr by 16 (stays -1)"),
    ]

    failed = 0

    for din, off, shtyp, expected, description in edge_cases:
        dut.din.value = din
        dut.off.value = off
        dut.shtyp.value = shtyp

        await Timer(1, units="ns")

        actual = int(dut.dout.value)

        if actual != expected:
            failed += 1
            dut._log.error(f"  FAILED: {description}")
            dut._log.error(f"    expected={expected:#010x}, got={actual:#010x}")
        else:
            dut._log.info(f"  PASSED: {description} = {actual:#010x}")

    if failed == 0:
        dut._log.info("\nAll edge case tests PASSED")
    else:
        assert False, f"{failed} edge case test(s) failed"
