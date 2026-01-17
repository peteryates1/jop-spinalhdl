"""
CocoTB Tests for MemorySubsystem

Tests the integrated memory subsystem with:
- Main memory access (pre-loaded with smallest.jop)
- Method cache lookup
- Method loading from main memory to JBC RAM
- Bytecode reading from JBC RAM
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, Timer


class MemorySubsystemDriver:
    """Driver for MemorySubsystem testbench"""

    def __init__(self, dut):
        self.dut = dut
        self.clock = None

    async def start_clock(self, period_ns=10):
        """Start the clock"""
        self.clock = Clock(self.dut.io_clk, period_ns, unit='ns')
        cocotb.start_soon(self.clock.start())

    async def reset(self):
        """Apply reset"""
        self.dut.io_reset.value = 1
        # Set default values
        self.dut.io_find.value = 0
        self.dut.io_bcAddr.value = 0
        self.dut.io_bcLen.value = 0
        self.dut.io_jpc.value = 0
        self.dut.io_loadStart.value = 0
        self.dut.io_loadAddr.value = 0
        self.dut.io_loadLen.value = 0
        self.dut.io_loadBlock.value = 0

        await RisingEdge(self.dut.io_clk)
        await RisingEdge(self.dut.io_clk)
        self.dut.io_reset.value = 0
        await RisingEdge(self.dut.io_clk)
        await Timer(1, unit='ns')

    async def clock_cycles(self, n):
        """Wait for n clock cycles"""
        for _ in range(n):
            await RisingEdge(self.dut.io_clk)
        await Timer(1, unit='ns')

    async def cache_lookup(self, addr, length):
        """Perform a cache lookup"""
        self.dut.io_bcAddr.value = addr
        self.dut.io_bcLen.value = length
        self.dut.io_find.value = 1
        await self.clock_cycles(1)
        self.dut.io_find.value = 0

        # Wait for ready
        for _ in range(10):
            if int(self.dut.io_rdy.value) == 1:
                break
            await self.clock_cycles(1)

        in_cache = int(self.dut.io_inCache.value)
        bcstart = int(self.dut.io_bcstart.value)
        return in_cache == 1, bcstart

    async def load_method(self, addr, length, block):
        """Load a method from main memory to JBC RAM"""
        self.dut.io_loadAddr.value = addr
        self.dut.io_loadLen.value = length
        self.dut.io_loadBlock.value = block
        self.dut.io_loadStart.value = 1
        await self.clock_cycles(1)

        # Wait for load to complete (2 cycles per word + overhead)
        for _ in range(length * 3 + 20):
            if int(self.dut.io_loadDone.value) == 1:
                break
            await self.clock_cycles(1)

        done = int(self.dut.io_loadDone.value) == 1
        self.dut.io_loadStart.value = 0
        await self.clock_cycles(1)

        return done

    async def read_bytecode(self, jpc):
        """Read a bytecode from JBC RAM"""
        self.dut.io_jpc.value = jpc
        await self.clock_cycles(2)  # 1 cycle for address, 1 for data
        return int(self.dut.io_bytecode.value)


@cocotb.test()
async def test_reset_and_ready(dut):
    """Test that reset initializes correctly and cache is ready"""
    driver = MemorySubsystemDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Cache should be ready (idle state)
    assert int(dut.io_rdy.value) == 1, f"Expected rdy=1 after reset, got {int(dut.io_rdy.value)}"

    cocotb.log.info("test_reset_and_ready PASSED")


@cocotb.test()
async def test_cache_miss_on_empty(dut):
    """Test that cache lookup returns miss when cache is empty"""
    driver = MemorySubsystemDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Look up a method that's not in cache
    in_cache, bcstart = await driver.cache_lookup(addr=0x100, length=64)

    assert not in_cache, "Expected cache miss on empty cache"

    cocotb.log.info("test_cache_miss_on_empty PASSED")


@cocotb.test()
async def test_method_loading(dut):
    """Test loading a method from main memory"""
    driver = MemorySubsystemDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Load 16 words from address 0 into block 0
    success = await driver.load_method(addr=0, length=16, block=0)

    assert success, "Method loading should complete"

    cocotb.log.info("test_method_loading PASSED")


@cocotb.test()
async def test_load_and_read_bytecodes(dut):
    """Test loading method and reading bytecodes"""
    driver = MemorySubsystemDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Load 8 words from address 0 into block 0
    success = await driver.load_method(addr=0, length=8, block=0)
    assert success, "Method loading should complete"

    # Wait for write to complete
    await driver.clock_cycles(5)

    # Debug: Read memData to see what's being read from main memory
    cocotb.log.info(f"memData after loading: {int(dut.io_memData.value):#x}")

    # Read first few bytecodes
    # The main memory is initialized with smallest.jop data
    # First word (address 0) is 2173 (0x87D) = length
    # In little-endian: bytes 0,1,2,3 = 0x7D, 0x08, 0x00, 0x00

    bc0 = await driver.read_bytecode(0)
    bc1 = await driver.read_bytecode(1)
    bc2 = await driver.read_bytecode(2)
    bc3 = await driver.read_bytecode(3)

    cocotb.log.info(f"Bytecodes: bc0={bc0:#x}, bc1={bc1:#x}, bc2={bc2:#x}, bc3={bc3:#x}")

    # First word is 2173 = 0x87D
    # Little-endian: bc0=0x7D, bc1=0x08, bc2=0x00, bc3=0x00
    assert bc0 == 0x7D, f"Expected bc0=0x7D (from 2173), got {bc0:#x}"
    assert bc1 == 0x08, f"Expected bc1=0x08 (from 2173), got {bc1:#x}"
    assert bc2 == 0x00, f"Expected bc2=0x00, got {bc2:#x}"
    assert bc3 == 0x00, f"Expected bc3=0x00, got {bc3:#x}"

    cocotb.log.info("test_load_and_read_bytecodes PASSED")


@cocotb.test()
async def test_load_and_cache_hit(dut):
    """Test that after loading, cache lookup returns hit"""
    driver = MemorySubsystemDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # First lookup should be a miss
    in_cache, _ = await driver.cache_lookup(addr=0x100, length=32)
    assert not in_cache, "Expected cache miss on first lookup"

    # Note: The cache tag is set during the lookup (miss allocates a block)
    # So a second lookup with the same address should hit

    # Do another lookup with the same address
    in_cache, bcstart = await driver.cache_lookup(addr=0x100, length=32)
    assert in_cache, "Expected cache hit on second lookup with same address"

    cocotb.log.info("test_load_and_cache_hit PASSED")


@cocotb.test()
async def test_multiple_methods(dut):
    """Test loading multiple methods into different cache blocks"""
    driver = MemorySubsystemDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Load method 1 into block 0
    success = await driver.load_method(addr=0, length=16, block=0)
    assert success, "Method 1 loading should complete"

    # Load method 2 into block 1
    success = await driver.load_method(addr=100, length=16, block=1)
    assert success, "Method 2 loading should complete"

    # Both methods should be loadable (we're directly controlling the blocks)
    cocotb.log.info("test_multiple_methods PASSED")


@cocotb.test()
async def test_jop_data_integrity(dut):
    """Test that JOP file data is correctly loaded in main memory"""
    driver = MemorySubsystemDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Load first 32 words from main memory
    success = await driver.load_method(addr=0, length=32, block=0)
    assert success, "Loading should complete"

    await driver.clock_cycles(2)

    # Read and verify bytecodes match expected JOP file content
    # smallest.jop starts with: 2173, 1051, 0, 0, ...
    # 2173 = 0x0000087D (little-endian: 0x7D, 0x08, 0x00, 0x00)
    # 1051 = 0x0000041B (little-endian: 0x1B, 0x04, 0x00, 0x00)

    # Word 0: 2173 (0x87D)
    bc0 = await driver.read_bytecode(0)
    bc1 = await driver.read_bytecode(1)
    bc2 = await driver.read_bytecode(2)
    bc3 = await driver.read_bytecode(3)
    word0 = bc0 | (bc1 << 8) | (bc2 << 16) | (bc3 << 24)
    assert word0 == 2173, f"Word 0 should be 2173, got {word0}"

    # Word 1: 1051 (0x41B)
    bc4 = await driver.read_bytecode(4)
    bc5 = await driver.read_bytecode(5)
    bc6 = await driver.read_bytecode(6)
    bc7 = await driver.read_bytecode(7)
    word1 = bc4 | (bc5 << 8) | (bc6 << 16) | (bc7 << 24)
    assert word1 == 1051, f"Word 1 should be 1051, got {word1}"

    cocotb.log.info(f"Word 0: {word0}, Word 1: {word1}")
    cocotb.log.info("test_jop_data_integrity PASSED")
