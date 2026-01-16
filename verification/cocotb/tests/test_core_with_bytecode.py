"""
CocoTB test suite for JopCoreWithBytecode (Bytecode Fetch + Microcode Pipeline)

Tests the integrated system using JSON test vectors for portability between
SpinalHDL-generated VHDL and reference VHDL implementations.

Test vector file: verification/test-vectors/modules/core_bytecode.json

Signal names (from JopCoreWithBytecodeTb):
- clk, reset
- mem_data_in, mem_busy
- jbc_wr_addr, jbc_wr_data, jbc_wr_en
- jpc_wr
- aout, bout, sp_ov
- jpc_out, jfetch, jopdfetch, jpaddr, opd, mul_dout
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, Timer
import json
from pathlib import Path


# Test vector file path
TEST_VECTOR_FILE = Path(__file__).parent.parent.parent / "test-vectors" / "modules" / "core_bytecode.json"


class TestVectorLoader:
    """Load and parse test vectors from JSON file"""

    def __init__(self, filepath):
        with open(filepath, 'r') as f:
            self.data = json.load(f)
        self.test_cases = self.data.get('test_cases', [])

    def get_test_case(self, name):
        """Get a specific test case by name"""
        for tc in self.test_cases:
            if tc['name'] == name:
                return tc
        return None

    @staticmethod
    def parse_value(val_str):
        """Parse a value string (handles hex, binary, decimal)"""
        if isinstance(val_str, int):
            return val_str
        val_str = str(val_str)
        if val_str.startswith('0x') or val_str.startswith('0X'):
            return int(val_str, 16)
        elif val_str.startswith('0b') or val_str.startswith('0B'):
            return int(val_str, 2)
        else:
            return int(val_str)


class JopCoreWithBytecodeTester:
    """Helper class for testing JOP Core with Bytecode"""

    def __init__(self, dut):
        self.dut = dut
        self.loader = TestVectorLoader(TEST_VECTOR_FILE)

    async def reset(self):
        """Apply reset to the core"""
        self.dut.reset.value = 1
        self.dut.mem_busy.value = 0
        self.dut.mem_data_in.value = 0
        self.dut.jbc_wr_en.value = 0
        self.dut.jbc_wr_addr.value = 0
        self.dut.jbc_wr_data.value = 0
        self.dut.jpc_wr.value = 0
        await RisingEdge(self.dut.clk)
        await RisingEdge(self.dut.clk)
        self.dut.reset.value = 0
        await RisingEdge(self.dut.clk)

    async def write_jbc_word(self, word_addr, data):
        """Write a 32-bit word to JBC RAM"""
        self.dut.jbc_wr_addr.value = word_addr
        self.dut.jbc_wr_data.value = data
        self.dut.jbc_wr_en.value = 1
        await RisingEdge(self.dut.clk)
        self.dut.jbc_wr_en.value = 0

    async def clock_cycles(self, n):
        """Wait for n clock cycles"""
        for _ in range(n):
            await RisingEdge(self.dut.clk)

    async def execute_sequence(self, sequence, raise_on_failure=True):
        """Execute a test sequence from JSON"""
        for step in sequence:
            action = step.get('action')

            if action == 'reset':
                await self.reset()

            elif action == 'set':
                for key, val in step.items():
                    if key == 'action':
                        continue
                    signal = getattr(self.dut, key, None)
                    if signal is not None:
                        signal.value = self.loader.parse_value(val)

            elif action == 'clock':
                cycles = step.get('cycles', 1)
                await self.clock_cycles(cycles)

            elif action == 'settle':
                await Timer(1, units='ns')

            elif action == 'write_jbc':
                addr = step.get('address', 0)
                data = self.loader.parse_value(str(step.get('data', '0x0')))
                await self.write_jbc_word(addr, data)

            elif action == 'check':
                await self.check_outputs(step, raise_on_failure=raise_on_failure)

    async def check_outputs(self, expected, raise_on_failure=True):
        """Check output signals against expected values"""
        failures = []

        for key, expected_val in expected.items():
            if key in ('action', 'comment'):
                continue

            signal = getattr(self.dut, key, None)
            if signal is None:
                continue

            try:
                actual_val = int(signal.value)
                exp_val = self.loader.parse_value(expected_val)
                if actual_val != exp_val:
                    failures.append(f"{key}: expected 0x{exp_val:X}, got 0x{actual_val:X}")
            except ValueError:
                # Signal contains X values
                failures.append(f"{key}: contains unresolved values")

        if failures:
            for f in failures:
                self.dut._log.error(f"  CHECK FAILED: {f}")
            if raise_on_failure:
                raise AssertionError("; ".join(failures))
            return False
        return True


async def run_test_case(dut, test_name):
    """Run a single test case by name"""
    tester = JopCoreWithBytecodeTester(dut)
    test_case = tester.loader.get_test_case(test_name)

    if test_case is None:
        raise AssertionError(f"Test case '{test_name}' not found in JSON")

    dut._log.info(f"Running test: {test_case['name']}")
    dut._log.info(f"  Description: {test_case.get('description', 'N/A')}")

    sequence = test_case.get('sequence', [])
    await tester.execute_sequence(sequence)

    dut._log.info(f"  PASSED")


# ============================================================================
# Individual Test Cases (mapped from JSON)
# ============================================================================

@cocotb.test()
async def test_reset_state(dut):
    """After reset, JPC should be 0"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "reset_state")


@cocotb.test()
async def test_jbc_write_single_word(dut):
    """Write a single word to JBC RAM"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "jbc_write_single_word")


@cocotb.test()
async def test_bytecode_nop_lookup(dut):
    """NOP bytecode (0x00) jump table lookup"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_nop_lookup")


@cocotb.test()
async def test_bytecode_iconst_0_lookup(dut):
    """iconst_0 bytecode (0x03) jump table lookup"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_iconst_0_lookup")


@cocotb.test()
async def test_bytecode_iconst_1_lookup(dut):
    """iconst_1 bytecode (0x04) jump table lookup"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_iconst_1_lookup")


@cocotb.test()
async def test_bytecode_iconst_2_lookup(dut):
    """iconst_2 bytecode (0x05) jump table lookup"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_iconst_2_lookup")


@cocotb.test()
async def test_bytecode_bipush_lookup(dut):
    """bipush bytecode (0x10) jump table lookup"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_bipush_lookup")


@cocotb.test()
async def test_bytecode_iadd_lookup(dut):
    """iadd bytecode (0x60) jump table lookup"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_iadd_lookup")


@cocotb.test()
async def test_bytecode_isub_lookup(dut):
    """isub bytecode (0x64) jump table lookup"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_isub_lookup")


@cocotb.test()
async def test_bytecode_iand_lookup(dut):
    """iand bytecode (0x7E) jump table lookup"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_iand_lookup")


@cocotb.test()
async def test_bytecode_ior_lookup(dut):
    """ior bytecode (0x80) jump table lookup"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_ior_lookup")


@cocotb.test()
async def test_bytecode_ixor_lookup(dut):
    """ixor bytecode (0x82) jump table lookup"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_ixor_lookup")


@cocotb.test()
async def test_bytecode_iload_0_lookup(dut):
    """iload_0 bytecode (0x1A) jump table lookup"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_iload_0_lookup")


@cocotb.test()
async def test_bytecode_istore_0_lookup(dut):
    """istore_0 bytecode (0x3B) jump table lookup"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_istore_0_lookup")


@cocotb.test()
async def test_bytecode_dup_lookup(dut):
    """dup bytecode (0x59) jump table lookup"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_dup_lookup")


@cocotb.test()
async def test_bytecode_pop_lookup(dut):
    """pop bytecode (0x57) jump table lookup"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_pop_lookup")


@cocotb.test()
async def test_bytecode_sequence_in_ram(dut):
    """Multiple bytecodes stored in consecutive RAM locations"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bytecode_sequence_in_ram")


@cocotb.test()
async def test_unknown_bytecode_to_sys_noim(dut):
    """Unknown bytecode (0xFF) maps to sys_noim handler"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "unknown_bytecode_to_sys_noim")


# ============================================================================
# All Vectors Test (runs all test cases from JSON)
# ============================================================================

@cocotb.test()
async def test_all_vectors(dut):
    """Run all test vectors from JSON file"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())

    tester = JopCoreWithBytecodeTester(dut)

    passed = 0
    failed = 0
    results = []

    for test_case in tester.loader.test_cases:
        test_name = test_case['name']
        description = test_case.get('description', 'N/A')

        dut._log.info(f"\nTest: {test_name}")
        dut._log.info(f"  Description: {description}")

        try:
            sequence = test_case.get('sequence', [])
            # Use raise_on_failure=True for test_all_vectors to properly fail
            await tester.execute_sequence(sequence, raise_on_failure=True)
            dut._log.info(f"  PASSED")
            passed += 1
            results.append((test_name, True, None))
        except Exception as e:
            dut._log.error(f"  FAILED: {e}")
            failed += 1
            results.append((test_name, False, str(e)))

    dut._log.info(f"\n" + "=" * 60)
    dut._log.info(f"Results: {passed} passed, {failed} failed out of {len(tester.loader.test_cases)} tests")

    if failed > 0:
        dut._log.info("\nFailed tests:")
        for name, success, error in results:
            if not success:
                dut._log.info(f"  - {name}: {error}")

    assert failed == 0, f"{failed} test(s) failed"
