"""
CocoTB Tests for MainMemorySim

Tests the simulated main memory component with:
- Read/write operations
- Pre-loaded data verification
- Control signal behavior (ncs, noe, nwr)
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, FallingEdge, Timer
import json
import os


class MainMemorySimDriver:
    """Driver for MainMemorySim testbench"""

    def __init__(self, dut):
        self.dut = dut
        self.clock = None

    async def start_clock(self, period_ns=10):
        """Start the clock"""
        self.clock = Clock(self.dut.io_clk, period_ns, units='ns')
        cocotb.start_soon(self.clock.start())

    async def reset(self):
        """Apply reset"""
        self.dut.io_reset.value = 1
        # Set default values for control signals
        self.dut.io_ncs.value = 1  # Chip not selected
        self.dut.io_noe.value = 1  # Output disabled
        self.dut.io_nwr.value = 1  # Write disabled
        self.dut.io_addr.value = 0
        self.dut.io_din.value = 0

        await RisingEdge(self.dut.io_clk)
        await RisingEdge(self.dut.io_clk)
        self.dut.io_reset.value = 0
        await RisingEdge(self.dut.io_clk)
        # Small delay to let combinational logic settle
        await Timer(1, units='ns')

    async def clock_cycles(self, n):
        """Wait for n clock cycles"""
        for _ in range(n):
            await RisingEdge(self.dut.io_clk)
        # Small delay to let combinational logic settle
        await Timer(1, units='ns')

    async def read(self, addr):
        """Perform a read operation"""
        self.dut.io_addr.value = addr
        self.dut.io_ncs.value = 0  # Select chip
        self.dut.io_noe.value = 0  # Enable output
        self.dut.io_nwr.value = 1  # Disable write

        await self.clock_cycles(1)
        value = int(self.dut.io_dout.value)

        # Deselect after read
        self.dut.io_ncs.value = 1
        return value

    async def write(self, addr, data):
        """Perform a write operation"""
        self.dut.io_addr.value = addr
        self.dut.io_din.value = data
        self.dut.io_ncs.value = 0  # Select chip
        self.dut.io_noe.value = 1  # Disable output
        self.dut.io_nwr.value = 0  # Enable write

        await self.clock_cycles(1)

        # Deselect after write
        self.dut.io_ncs.value = 1
        self.dut.io_nwr.value = 1


def load_test_vectors():
    """Load test vectors from JSON file"""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    json_path = os.path.join(script_dir, '../../test-vectors/modules/main_memory_sim.json')

    try:
        with open(json_path, 'r') as f:
            return json.load(f)
    except Exception as e:
        cocotb.log.warning(f"Could not load test vectors: {e}")
        return None


@cocotb.test()
async def test_reset_clears_outputs(dut):
    """Test that reset initializes outputs correctly"""
    driver = MainMemorySimDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Check ready is high
    assert int(dut.io_ready.value) == 1, f"Expected ready=1, got {int(dut.io_ready.value)}"

    cocotb.log.info("test_reset_clears_outputs PASSED")


@cocotb.test()
async def test_read_preloaded_data(dut):
    """Test reading pre-loaded data (ascending pattern)"""
    driver = MainMemorySimDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Memory is initialized with ascending pattern: mem[i] = i
    test_addresses = [0, 1, 2, 127, 255]

    for addr in test_addresses:
        value = await driver.read(addr)
        expected = addr  # Ascending pattern
        assert value == expected, f"Address {addr}: expected {expected:#x}, got {value:#x}"

    cocotb.log.info("test_read_preloaded_data PASSED")


@cocotb.test()
async def test_write_and_read_back(dut):
    """Test writing data and reading it back"""
    driver = MainMemorySimDriver(dut)
    await driver.start_clock()
    await driver.reset()

    test_cases = [
        (0x10, 0xDEADBEEF),
        (0x20, 0xCAFEBABE),
        (0x30, 0x12345678),
        (0x00, 0xFFFFFFFF),  # Overwrite pre-loaded value
    ]

    for addr, data in test_cases:
        # Write
        await driver.write(addr, data)

        # Read back
        value = await driver.read(addr)
        assert value == data, f"Address {addr:#x}: wrote {data:#x}, read back {value:#x}"

    cocotb.log.info("test_write_and_read_back PASSED")


@cocotb.test()
async def test_sequential_writes(dut):
    """Test writing to multiple sequential addresses"""
    driver = MainMemorySimDriver(dut)
    await driver.start_clock()
    await driver.reset()

    base_addr = 0x40
    test_data = [0x11111111, 0x22222222, 0x33333333, 0x44444444]

    # Write sequence
    for i, data in enumerate(test_data):
        await driver.write(base_addr + i, data)

    # Read back and verify
    for i, expected in enumerate(test_data):
        value = await driver.read(base_addr + i)
        assert value == expected, f"Address {base_addr + i:#x}: expected {expected:#x}, got {value:#x}"

    cocotb.log.info("test_sequential_writes PASSED")


@cocotb.test()
async def test_chip_select_disables_output(dut):
    """Test that ncs=1 disables output"""
    driver = MainMemorySimDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Set up read but with chip deselected
    dut.io_addr.value = 0x01  # Should be 0x01 from pre-loaded pattern
    dut.io_ncs.value = 1  # Chip NOT selected
    dut.io_noe.value = 0  # Output enabled
    dut.io_nwr.value = 1  # Read mode

    await driver.clock_cycles(1)

    # Output should be 0 when chip is not selected
    value = int(dut.io_dout.value)
    assert value == 0, f"Expected dout=0 when ncs=1, got {value:#x}"

    cocotb.log.info("test_chip_select_disables_output PASSED")


@cocotb.test()
async def test_output_enable_controls_output(dut):
    """Test that noe=1 disables output even when ncs=0"""
    driver = MainMemorySimDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Set up read but with output disabled
    dut.io_addr.value = 0x01
    dut.io_ncs.value = 0  # Chip selected
    dut.io_noe.value = 1  # Output disabled
    dut.io_nwr.value = 1  # Read mode

    await driver.clock_cycles(1)

    # Output should be 0 when output is disabled
    value = int(dut.io_dout.value)
    assert value == 0, f"Expected dout=0 when noe=1, got {value:#x}"

    cocotb.log.info("test_output_enable_controls_output PASSED")


@cocotb.test()
async def test_ready_always_high(dut):
    """Test that ready signal is always high for synchronous memory"""
    driver = MainMemorySimDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Check ready in various states
    for i in range(5):
        await driver.clock_cycles(1)
        assert int(dut.io_ready.value) == 1, f"Cycle {i}: expected ready=1, got {int(dut.io_ready.value)}"

    # Check during read
    dut.io_addr.value = 0x00
    dut.io_ncs.value = 0
    dut.io_noe.value = 0
    dut.io_nwr.value = 1
    await driver.clock_cycles(1)
    assert int(dut.io_ready.value) == 1, "Expected ready=1 during read"

    # Check during write
    dut.io_noe.value = 1
    dut.io_nwr.value = 0
    dut.io_din.value = 0x12345678
    await driver.clock_cycles(1)
    assert int(dut.io_ready.value) == 1, "Expected ready=1 during write"

    cocotb.log.info("test_ready_always_high PASSED")


@cocotb.test()
async def test_read_latency(dut):
    """Test that reads have 1 cycle latency"""
    driver = MainMemorySimDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Start a read
    dut.io_addr.value = 0x05  # Pre-loaded with 0x05
    dut.io_ncs.value = 0
    dut.io_noe.value = 0
    dut.io_nwr.value = 1

    # After 1 cycle, data should be available
    await driver.clock_cycles(1)
    value = int(dut.io_dout.value)
    assert value == 0x05, f"Expected 0x05 after 1 cycle, got {value:#x}"

    cocotb.log.info("test_read_latency PASSED")


@cocotb.test()
async def test_write_followed_by_read_same_address(dut):
    """Test writing and immediately reading same address"""
    driver = MainMemorySimDriver(dut)
    await driver.start_clock()
    await driver.reset()

    addr = 0x50
    test_value = 0xABCDABCD

    # Write
    await driver.write(addr, test_value)

    # Immediately read same address
    value = await driver.read(addr)
    assert value == test_value, f"Expected {test_value:#x}, got {value:#x}"

    cocotb.log.info("test_write_followed_by_read_same_address PASSED")
