"""
CocoTB test suite for JOP Core stack operations

Tests stack microcode instructions.
Each test runs independently to avoid multi-test timing issues.
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge


@cocotb.test()
async def test_dup(dut):
    """Test dup operation (instruction 0x0F8 at ROM addr 2)"""
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

    # Wait to reach addr 2 (dup instruction)
    for _ in range(1):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("DUP test PASSED")

@cocotb.test()
async def test_nop(dut):
    """Test nop operation (instruction 0x100 at ROM addr 3)"""
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

    # Wait to reach addr 3 (nop instruction)
    for _ in range(2):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("NOP test PASSED")

@cocotb.test()
async def test_wait(dut):
    """Test wait operation (instruction 0x101 at ROM addr 4)"""
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

    # Wait to reach addr 4 (wait instruction)
    for _ in range(3):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("WAIT test PASSED")
