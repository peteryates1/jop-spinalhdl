"""
CocoTB test suite for MethodCache

Tests the method cache tag lookup, hit/miss detection, and round-robin
block replacement using JSON test vectors.

Test vector file: verification/test-vectors/modules/method_cache.json

Signal names (from MethodCacheTb):
- clk, reset
- find, bc_addr, bc_len
- rdy, in_cache, bcstart, alloc_block
- ext_wr_addr, ext_wr_data, ext_wr_en
- load_done
- jbc_wr_addr, jbc_wr_data, jbc_wr_en

Timing:
- Cache lookup takes 2 cycles for hit (IDLE->S1->IDLE)
- Cache lookup takes 3 cycles for miss (IDLE->S1->S2->IDLE)
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, Timer
import json
from pathlib import Path


# Test vector file path
TEST_VECTOR_FILE = Path(__file__).parent.parent.parent / "test-vectors" / "modules" / "method_cache.json"


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


class MethodCacheTester:
    """Helper class for testing Method Cache"""

    def __init__(self, dut):
        self.dut = dut
        self.loader = TestVectorLoader(TEST_VECTOR_FILE)

    async def reset(self):
        """Apply reset to the cache"""
        self.dut.reset.value = 1
        self.dut.find.value = 0
        self.dut.bc_addr.value = 0
        self.dut.bc_len.value = 0
        self.dut.ext_wr_addr.value = 0
        self.dut.ext_wr_data.value = 0
        self.dut.ext_wr_en.value = 0
        self.dut.load_done.value = 0
        await RisingEdge(self.dut.clk)
        await RisingEdge(self.dut.clk)
        self.dut.reset.value = 0
        await RisingEdge(self.dut.clk)
        # Small delay to let combinational logic settle
        await Timer(1, units='ns')

    async def clock_cycles(self, n):
        """Wait for n clock cycles"""
        for _ in range(n):
            await RisingEdge(self.dut.clk)
        # Small delay to let combinational logic settle
        await Timer(1, units='ns')

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
    tester = MethodCacheTester(dut)
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
    """After reset, cache should be idle and rdy=1"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "reset_state")


@cocotb.test()
async def test_cache_miss_first_lookup(dut):
    """First lookup should be a cache miss (3 cycles)"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "cache_miss_first_lookup")


@cocotb.test()
async def test_cache_hit_after_miss(dut):
    """Second lookup to same address should be a hit (2 cycles)"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "cache_hit_after_miss")


@cocotb.test()
async def test_different_address_miss(dut):
    """Lookup to different address should be a miss"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "different_address_miss")


@cocotb.test()
async def test_round_robin_allocation(dut):
    """Blocks should be allocated in round-robin order"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "round_robin_allocation")


@cocotb.test()
async def test_multiple_addresses_cached(dut):
    """Multiple addresses cached, all should hit"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "multiple_addresses_cached")


@cocotb.test()
async def test_bcstart_block_addressing(dut):
    """Verify bcstart gives correct block start address"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "bcstart_block_addressing")


@cocotb.test()
async def test_external_write_passthrough(dut):
    """External write signals pass through to JBC RAM"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "external_write_passthrough")


@cocotb.test()
async def test_idle_without_find(dut):
    """Cache stays idle when find is not asserted"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "idle_without_find")


@cocotb.test()
async def test_transition_timing(dut):
    """Verify state machine transitions at correct clock edges"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())
    await run_test_case(dut, "transition_timing")


# ============================================================================
# All Vectors Test (runs all test cases from JSON)
# ============================================================================

@cocotb.test()
async def test_all_vectors(dut):
    """Run all test vectors from JSON file"""
    clock = Clock(dut.clk, 10, unit="ns")
    cocotb.start_soon(clock.start())

    tester = MethodCacheTester(dut)

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
