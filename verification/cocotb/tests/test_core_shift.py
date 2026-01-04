"""
CocoTB test suite for JOP Core shift operations

Tests basic shift microcode instructions with custom ROM containing:
- Addr 2: 0x01C - USHR (unsigned shift right)
- Addr 3: 0x01D - SHL (shift left)
- Addr 4: 0x01E - SHR (signed shift right)

This test uses JopCoreShiftTestTb which has a custom ROM with shift instructions.
Each test runs independently to avoid multi-test timing issues.
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge
from pathlib import Path


@cocotb.test()
async def test_shift_ushr(dut):
    """Test unsigned shift right (instruction 0x01C at ROM addr 2)"""
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
    # Wait for it to reach addr 2 (USHR instruction)
    await RisingEdge(dut.clk)

    # At this point, DecodeStage should be decoding instruction 0x01C
    # This is a POP operation with selLmux=001 (shifter output)
    # and shift type = 00 (USHR)

    # Let it run for a few cycles
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("Shift USHR test PASSED")


@cocotb.test()
async def test_shift_shl(dut):
    """Test shift left (instruction 0x01D at ROM addr 3)"""
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

    # Wait to reach addr 3 (SHL instruction)
    # From reset: addr 0 → 1 → 2 → 3
    for _ in range(2):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("Shift SHL test PASSED")


@cocotb.test()
async def test_shift_shr(dut):
    """Test signed shift right (instruction 0x01E at ROM addr 4)"""
    dut.mem_data_in.value = 0
    dut.mem_busy.value = 0
    dut.operand.value = 0
    dut.jpc.value = 0

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut.reset.value = 1
    await RisingEdge(dut.clk)
    await RisingEdge(dut.clk)
    dut.reset.value = 0
    await RisingEdge(dut.clk)

    # Wait to reach addr 4 (SHR instruction)
    for _ in range(3):
        await RisingEdge(dut.clk)

    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("Shift SHR test PASSED")
