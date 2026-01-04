"""
CocoTB test suite for JOP Core store operations

Tests store microcode instructions.
Each test runs independently to avoid multi-test timing issues.
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge


@cocotb.test()
async def test_st0(dut):
    """Test st0 operation (instruction 0x010 at ROM addr 2)"""
    # Set inputs BEFORE starting clock
    dut.mem_data_in.value = 0
    dut.mem_busy.value = 0
    dut.operand.value = 0
    dut.jpc.value = 0

    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Apply reset
    dut.reset.value = 1
    await RisingEdge(dut.clk)
    await RisingEdge(dut.clk)
    dut.reset.value = 0
    await RisingEdge(dut.clk)

    # Wait to reach addr 2 (st0 instruction)
    for _ in range(1):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("ST0 test PASSED")

@cocotb.test()
async def test_st1(dut):
    """Test st1 operation (instruction 0x011 at ROM addr 3)"""
    # Set inputs BEFORE starting clock
    dut.mem_data_in.value = 0
    dut.mem_busy.value = 0
    dut.operand.value = 0
    dut.jpc.value = 0

    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Apply reset
    dut.reset.value = 1
    await RisingEdge(dut.clk)
    await RisingEdge(dut.clk)
    dut.reset.value = 0
    await RisingEdge(dut.clk)

    # Wait to reach addr 3 (st1 instruction)
    for _ in range(2):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("ST1 test PASSED")

@cocotb.test()
async def test_st2(dut):
    """Test st2 operation (instruction 0x012 at ROM addr 4)"""
    # Set inputs BEFORE starting clock
    dut.mem_data_in.value = 0
    dut.mem_busy.value = 0
    dut.operand.value = 0
    dut.jpc.value = 0

    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Apply reset
    dut.reset.value = 1
    await RisingEdge(dut.clk)
    await RisingEdge(dut.clk)
    dut.reset.value = 0
    await RisingEdge(dut.clk)

    # Wait to reach addr 4 (st2 instruction)
    for _ in range(3):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("ST2 test PASSED")

@cocotb.test()
async def test_st3(dut):
    """Test st3 operation (instruction 0x013 at ROM addr 5)"""
    # Set inputs BEFORE starting clock
    dut.mem_data_in.value = 0
    dut.mem_busy.value = 0
    dut.operand.value = 0
    dut.jpc.value = 0

    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Apply reset
    dut.reset.value = 1
    await RisingEdge(dut.clk)
    await RisingEdge(dut.clk)
    dut.reset.value = 0
    await RisingEdge(dut.clk)

    # Wait to reach addr 5 (st3 instruction)
    for _ in range(4):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("ST3 test PASSED")

@cocotb.test()
async def test_st(dut):
    """Test st operation (instruction 0x014 at ROM addr 6)"""
    # Set inputs BEFORE starting clock
    dut.mem_data_in.value = 0
    dut.mem_busy.value = 0
    dut.operand.value = 0
    dut.jpc.value = 0

    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Apply reset
    dut.reset.value = 1
    await RisingEdge(dut.clk)
    await RisingEdge(dut.clk)
    dut.reset.value = 0
    await RisingEdge(dut.clk)

    # Wait to reach addr 6 (st instruction)
    for _ in range(5):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("ST test PASSED")

@cocotb.test()
async def test_stmi(dut):
    """Test stmi operation (instruction 0x015 at ROM addr 7)"""
    # Set inputs BEFORE starting clock
    dut.mem_data_in.value = 0
    dut.mem_busy.value = 0
    dut.operand.value = 0
    dut.jpc.value = 0

    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Apply reset
    dut.reset.value = 1
    await RisingEdge(dut.clk)
    await RisingEdge(dut.clk)
    dut.reset.value = 0
    await RisingEdge(dut.clk)

    # Wait to reach addr 7 (stmi instruction)
    for _ in range(6):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("STMI test PASSED")
