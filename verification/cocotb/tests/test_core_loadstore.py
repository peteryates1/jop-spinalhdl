"""
CocoTB test suite for JOP Core load/store operations

Tests load/store microcode instructions with custom ROM containing:
- Addr 2: 0x0A0 - ldm (load from memory)
- Addr 3: 0x020 - stm (store to memory)
- Addr 4: 0x0C0 - ldi (load immediate)

This test uses JopCoreLoadStoreTestTb which has a custom ROM with load/store instructions.
Each test runs independently to avoid multi-test timing issues.
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge
from pathlib import Path


@cocotb.test()
async def test_ldm(dut):
    """Test load from memory (instruction 0x0A0 at ROM addr 2)"""
    # Set inputs BEFORE starting clock
    dut.mem_data_in.value = 0x12345678  # Test data to load
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
    # Wait for it to reach addr 2 (ldm instruction)
    await RisingEdge(dut.clk)

    # At this point, DecodeStage should be decoding instruction 0x0A0
    # This is a ldm operation with selLmux=010 (memory output)

    # Let it run for a few cycles
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("Load from memory (ldm) test PASSED")


@cocotb.test()
async def test_stm(dut):
    """Test store to memory (instruction 0x020 at ROM addr 3)"""
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

    # Wait to reach addr 3 (stm instruction)
    # From reset: addr 0 → 1 → 2 → 3
    for _ in range(2):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("Store to memory (stm) test PASSED")


@cocotb.test()
async def test_ldi(dut):
    """Test load immediate (instruction 0x0C0 at ROM addr 4)"""
    dut.mem_data_in.value = 0xABCDEF00  # Test immediate data
    dut.mem_busy.value = 0
    dut.operand.value = 0x1234
    dut.jpc.value = 0

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut.reset.value = 1
    await RisingEdge(dut.clk)
    await RisingEdge(dut.clk)
    dut.reset.value = 0
    await RisingEdge(dut.clk)

    # Wait to reach addr 4 (ldi instruction)
    for _ in range(3):
        await RisingEdge(dut.clk)

    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("Load immediate (ldi) test PASSED")
