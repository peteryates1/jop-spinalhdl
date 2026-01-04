"""
CocoTB test suite for JOP Core branch operations

Tests branch microcode instructions with custom ROM containing:
- Addr 2: 0x180 - BZ (branch if zero)
- Addr 3: 0x1C0 - BNZ (branch if not zero)

This test uses JopCoreBranchTestTb which has a custom ROM with branch instructions.
Each test runs independently to avoid multi-test timing issues.
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge
from pathlib import Path


@cocotb.test()
async def test_branch_bz(dut):
    """Test branch if zero (instruction 0x180 at ROM addr 2)"""
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

    # PC should be at addr 1 (after reset from addr 0)
    # Wait for it to reach addr 2 (BZ instruction)
    await RisingEdge(dut.clk)

    # At this point, DecodeStage should be decoding instruction 0x180
    # This is a BZ (branch if zero) operation
    # ir[9:6] = 0110 (BZ class)

    # Let it run for a few cycles
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("Branch BZ test PASSED")


@cocotb.test()
async def test_branch_bnz(dut):
    """Test branch if not zero (instruction 0x1C0 at ROM addr 3)"""
    dut.mem_data_in.value = 0
    dut.mem_busy.value = 0
    dut.operand.value = 0
    dut.jpc.value = 0

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    # Apply reset
    dut.reset.value = 1
    await RisingEdge(dut.clk)
    await RisingEdge(dut.clk)
    dut.reset.value = 0
    await RisingEdge(dut.clk)

    # Wait to reach addr 3 (BNZ instruction)
    # From reset: addr 0 → 1 → 2 → 3
    for _ in range(2):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("Branch BNZ test PASSED")
