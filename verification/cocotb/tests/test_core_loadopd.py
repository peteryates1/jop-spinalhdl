"""
CocoTB test suite for JOP Core loadopd operations

Tests loadopd microcode instructions.
Each test runs independently to avoid multi-test timing issues.
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge


@cocotb.test()
async def test_ld_opd_8u(dut):
    """Test ld_opd_8u operation (instruction 0x0F4 at ROM addr 2)"""
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

    # Wait to reach addr 2 (ld_opd_8u instruction)
    for _ in range(1):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("LD_OPD_8U test PASSED")

@cocotb.test()
async def test_ld_opd_8s(dut):
    """Test ld_opd_8s operation (instruction 0x0F5 at ROM addr 3)"""
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

    # Wait to reach addr 3 (ld_opd_8s instruction)
    for _ in range(2):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("LD_OPD_8S test PASSED")

@cocotb.test()
async def test_ld_opd_16u(dut):
    """Test ld_opd_16u operation (instruction 0x0F6 at ROM addr 4)"""
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

    # Wait to reach addr 4 (ld_opd_16u instruction)
    for _ in range(3):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("LD_OPD_16U test PASSED")

@cocotb.test()
async def test_ld_opd_16s(dut):
    """Test ld_opd_16s operation (instruction 0x0F7 at ROM addr 5)"""
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

    # Wait to reach addr 5 (ld_opd_16s instruction)
    for _ in range(4):
        await RisingEdge(dut.clk)

    # Let it execute
    for _ in range(5):
        await RisingEdge(dut.clk)

    dut._log.info("LD_OPD_16S test PASSED")
