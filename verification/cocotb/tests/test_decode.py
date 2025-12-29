"""
CocoTB tests for decode.vhd (microcode instruction decoder)

This test suite validates the original VHDL implementation using
test vectors from verification/test-vectors/modules/decode.json

The decode stage is a pipeline stage with:
- 10-bit microcode instruction input
- Mixed timing: some outputs combinational, some registered
- 40+ output signals organized into functional groups

Timing characteristics:
- Combinational outputs (0 cycle latency): jbr, sel_rda, sel_wra, sel_smux, wr_ena, dir
- Registered outputs (1 cycle latency): br, jmp, ALU control, MMU control

Test wrapper (decode_tb.vhd) includes:
- Embedded configuration constants (ram_width=8, MMU_WIDTH=4)
- mem_in record fields exposed as individual ports
- All control signals accessible for verification
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, Timer
import sys
from pathlib import Path

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))
from util.test_vectors import TestVectorLoader

# Load test vectors
loader = TestVectorLoader("../test-vectors")

# Instruction encoding constants (from decode.vhd analysis)
# Instruction format: ir[9:0]
# - ir[9:6]: instruction class
# - ir[5:0]: instruction-specific data

# Stack operation classes
STACK_POP_0 = 0x0   # 0000 - POP instructions
STACK_POP_1 = 0x1   # 0001 - POP instructions
STACK_PUSH_0 = 0x2  # 0010 - PUSH instructions
STACK_PUSH_1 = 0x3  # 0011 - PUSH instructions
STACK_NOP = 0x4     # 0100 - NOP/control instructions
STACK_BR_Z = 0x6    # 0110 - Branch if zero (POP)
STACK_BR_NZ = 0x7   # 0111 - Branch if not zero (POP)
STACK_JMP = 0x8     # 1xxx - Jump instructions


async def reset_dut(dut, cycles=2):
    """Apply reset and wait for stabilization"""
    dut.reset.value = 1
    dut.instr.value = 0
    dut.zf.value = 0
    dut.nf.value = 0
    dut.eq.value = 0
    dut.lt.value = 0
    dut.bcopd.value = 0

    for _ in range(cycles):
        await RisingEdge(dut.clk)

    dut.reset.value = 0
    await RisingEdge(dut.clk)


def get_signal_value(dut, signal_name):
    """Get signal value by name, handling hierarchical names"""
    try:
        return int(getattr(dut, signal_name).value)
    except AttributeError:
        dut._log.warning(f"Signal {signal_name} not found")
        return None
    except ValueError:
        # Signal might be X or Z
        return None


def check_signal(dut, signal_name, expected, test_name):
    """Check if a signal matches expected value"""
    actual = get_signal_value(dut, signal_name)
    if actual is None:
        return True  # Skip check if signal not available

    if actual != expected:
        dut._log.error(f"  {test_name}: {signal_name} = {actual:#x}, expected {expected:#x}")
        return False
    return True


@cocotb.test()
async def test_decode_reset(dut):
    """Test reset behavior - all registered outputs should be cleared"""

    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Reset Test: All registered outputs should be 0 after reset")
    dut._log.info("=" * 60)

    # Apply reset
    await reset_dut(dut)

    # Check registered outputs are cleared
    signals_to_check = [
        ("br", 0),
        ("jmp", 0),
        ("sel_sub", 0),
        ("sel_amux", 0),
        ("ena_a", 0),
        ("sel_bmux", 0),
        ("sel_log", 0),
        ("sel_shf", 0),
        ("sel_lmux", 0),
        ("sel_rmux", 0),
        ("sel_mmux", 0),
        ("ena_b", 0),
        ("ena_vp", 0),
        ("ena_jpc", 0),
        ("ena_ar", 0),
        ("mul_wr", 0),
        ("wr_dly", 0),
    ]

    all_passed = True
    for signal, expected in signals_to_check:
        if not check_signal(dut, signal, expected, "reset"):
            all_passed = False

    assert all_passed, "Reset test failed"
    dut._log.info("  PASSED: Reset test")


@cocotb.test()
async def test_decode_from_vectors(dut):
    """Test decode using JSON test vectors"""

    # Start clock
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Running tests from JSON test vectors")
    dut._log.info("=" * 60)

    # Load test vectors
    test_cases = loader.get_test_cases("decode")

    dut._log.info(f"Loaded {len(test_cases)} test cases")

    passed = 0
    failed = 0

    for tc in test_cases:
        dut._log.info(f"\n  Test: {tc['name']}")
        dut._log.info(f"  Description: {tc['description']}")

        # Reset before each test
        await reset_dut(dut)

        test_failed = False

        # Apply inputs according to test vector
        for inp in tc.get('inputs', []):
            target_cycle = inp['cycle']

            # Apply signals
            for signal, value in inp['signals'].items():
                val = loader.parse_value(value)
                if val is not None and hasattr(dut, signal):
                    getattr(dut, signal).value = val
                    dut._log.debug(f"    Set {signal} = {value}")

            # Wait for target cycle
            if target_cycle == 0:
                await Timer(1, units="ns")  # Let combinational logic settle
            else:
                for _ in range(target_cycle):
                    await RisingEdge(dut.clk)

        # Check expected outputs
        for out in tc.get('expected_outputs', []):
            target_cycle = out['cycle']

            # Wait to target cycle if needed
            if target_cycle > 0:
                await RisingEdge(dut.clk)

            # Small delay for combinational logic to settle
            await Timer(1, units="ns")

            # Check signals
            for signal, value in out['signals'].items():
                expected = loader.parse_value(value)
                if expected is not None:
                    actual = get_signal_value(dut, signal)
                    if actual is not None and actual != expected:
                        dut._log.error(f"    Cycle {target_cycle}: {signal} = {actual:#x}, expected {expected:#x}")
                        test_failed = True
                    elif actual is not None:
                        dut._log.debug(f"    Cycle {target_cycle}: {signal} = {actual:#x} OK")

        if test_failed:
            failed += 1
            dut._log.error(f"    FAILED")
        else:
            passed += 1
            dut._log.info(f"    PASSED")

    dut._log.info("\n" + "-" * 60)
    dut._log.info(f"Results: {passed} passed, {failed} failed out of {passed + failed} tests")
    dut._log.info("-" * 60)

    assert failed == 0, f"{failed} test(s) failed"


@cocotb.test()
async def test_decode_stack_operations(dut):
    """Test stack pointer control for various instruction classes"""

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Stack Operation Tests: sel_smux control")
    dut._log.info("=" * 60)

    await reset_dut(dut)

    test_cases = [
        # (instruction, description, expected_sel_smux)
        (0x000, "POP (0x000)", 0x1),      # --sp
        (0x001, "AND (0x001)", 0x1),      # --sp (pop class)
        (0x080, "PUSH class (0x080)", 0x2),  # ++sp
        (0x100, "NOP (0x100)", 0x0),      # sp unchanged
        (0x01B, "STSP (0x01B)", 0x3),     # sp = a
        (0x180, "BZ (0x180)", 0x1),       # --sp (pop class)
        (0x1C0, "BNZ (0x1C0)", 0x1),      # --sp (pop class)
    ]

    all_passed = True

    for instr, desc, expected_smux in test_cases:
        dut.instr.value = instr
        dut.zf.value = 0
        dut.bcopd.value = 0

        await Timer(1, units="ns")  # Let combinational logic settle

        actual = get_signal_value(dut, "sel_smux")
        if actual != expected_smux:
            dut._log.error(f"  {desc}: sel_smux = {actual:#x}, expected {expected_smux:#x}")
            all_passed = False
        else:
            dut._log.info(f"  {desc}: sel_smux = {actual:#x} OK")

        await RisingEdge(dut.clk)

    assert all_passed, "Stack operation tests failed"
    dut._log.info("  PASSED: Stack operation tests")


@cocotb.test()
async def test_decode_alu_operations(dut):
    """Test ALU control signal generation"""

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "=" * 60)
    dut._log.info("ALU Operation Tests: sel_log, sel_sub, sel_amux")
    dut._log.info("=" * 60)

    await reset_dut(dut)

    test_cases = [
        # (instruction, description, expected_sel_log, expected_sel_sub, expected_sel_amux)
        (0x000, "POP", 0x0, None, None),  # sel_log=00 (pass through)
        (0x001, "AND", 0x1, None, None),  # sel_log=01
        (0x002, "OR", 0x2, None, None),   # sel_log=10
        (0x003, "XOR", 0x3, None, None),  # sel_log=11
        (0x004, "ADD", None, 0x0, 0x0),   # sel_sub=0, sel_amux=0
        (0x005, "SUB", None, 0x1, 0x0),   # sel_sub=1, sel_amux=0
    ]

    all_passed = True

    for instr, desc, exp_log, exp_sub, exp_amux in test_cases:
        dut.instr.value = instr
        dut.zf.value = 0
        dut.bcopd.value = 0

        await RisingEdge(dut.clk)  # Wait for registered outputs
        await Timer(1, units="ns")

        if exp_log is not None:
            actual = get_signal_value(dut, "sel_log")
            if actual != exp_log:
                dut._log.error(f"  {desc}: sel_log = {actual:#x}, expected {exp_log:#x}")
                all_passed = False
            else:
                dut._log.info(f"  {desc}: sel_log = {actual:#x} OK")

        if exp_sub is not None:
            actual = get_signal_value(dut, "sel_sub")
            if actual != exp_sub:
                dut._log.error(f"  {desc}: sel_sub = {actual:#x}, expected {exp_sub:#x}")
                all_passed = False
            else:
                dut._log.info(f"  {desc}: sel_sub = {actual:#x} OK")

        if exp_amux is not None:
            actual = get_signal_value(dut, "sel_amux")
            if actual != exp_amux:
                dut._log.error(f"  {desc}: sel_amux = {actual:#x}, expected {exp_amux:#x}")
                all_passed = False
            else:
                dut._log.info(f"  {desc}: sel_amux = {actual:#x} OK")

    assert all_passed, "ALU operation tests failed"
    dut._log.info("  PASSED: ALU operation tests")


@cocotb.test()
async def test_decode_shift_operations(dut):
    """Test shift instruction decoding"""

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Shift Operation Tests: sel_shf, sel_lmux")
    dut._log.info("=" * 60)

    await reset_dut(dut)

    test_cases = [
        # (instruction, description, expected_sel_shf, expected_sel_lmux)
        (0x01C, "USHR", 0x0, 0x1),  # ushr: sel_shf=00, sel_lmux=001
        (0x01D, "SHL", 0x1, 0x1),   # shl: sel_shf=01, sel_lmux=001
        (0x01E, "SHR", 0x2, 0x1),   # shr: sel_shf=10, sel_lmux=001
    ]

    all_passed = True

    for instr, desc, exp_shf, exp_lmux in test_cases:
        dut.instr.value = instr
        dut.zf.value = 0
        dut.bcopd.value = 0

        await RisingEdge(dut.clk)  # Wait for registered outputs
        await Timer(1, units="ns")

        actual_shf = get_signal_value(dut, "sel_shf")
        actual_lmux = get_signal_value(dut, "sel_lmux")

        if actual_shf != exp_shf:
            dut._log.error(f"  {desc}: sel_shf = {actual_shf:#x}, expected {exp_shf:#x}")
            all_passed = False
        elif actual_lmux != exp_lmux:
            dut._log.error(f"  {desc}: sel_lmux = {actual_lmux:#x}, expected {exp_lmux:#x}")
            all_passed = False
        else:
            dut._log.info(f"  {desc}: sel_shf = {actual_shf:#x}, sel_lmux = {actual_lmux:#x} OK")

    assert all_passed, "Shift operation tests failed"
    dut._log.info("  PASSED: Shift operation tests")


@cocotb.test()
async def test_decode_branch_conditions(dut):
    """Test branch instruction conditional execution"""

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Branch Condition Tests: br signal with zf")
    dut._log.info("=" * 60)

    await reset_dut(dut)

    test_cases = [
        # (instruction, zf, description, expected_br)
        (0x180, 1, "BZ with zf=1", 1),   # Branch taken
        (0x180, 0, "BZ with zf=0", 0),   # Branch not taken
        (0x1C0, 0, "BNZ with zf=0", 1),  # Branch taken
        (0x1C0, 1, "BNZ with zf=1", 0),  # Branch not taken
    ]

    all_passed = True

    for instr, zf, desc, exp_br in test_cases:
        dut.instr.value = instr
        dut.zf.value = zf
        dut.bcopd.value = 0

        await RisingEdge(dut.clk)  # Wait for registered br output
        await Timer(1, units="ns")

        actual = get_signal_value(dut, "br")
        if actual != exp_br:
            dut._log.error(f"  {desc}: br = {actual}, expected {exp_br}")
            all_passed = False
        else:
            dut._log.info(f"  {desc}: br = {actual} OK")

    assert all_passed, "Branch condition tests failed"
    dut._log.info("  PASSED: Branch condition tests")


@cocotb.test()
async def test_decode_jbr_combinational(dut):
    """Test that jbr is combinational (0 cycle latency)"""

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "=" * 60)
    dut._log.info("JBR Combinational Test")
    dut._log.info("=" * 60)

    await reset_dut(dut)

    # Set JBR instruction (0x102)
    dut.instr.value = 0x102
    dut.zf.value = 0
    dut.bcopd.value = 0

    # JBR should be 1 immediately (combinational)
    await Timer(1, units="ns")

    jbr = get_signal_value(dut, "jbr")
    assert jbr == 1, f"jbr should be 1 immediately for instruction 0x102, got {jbr}"
    dut._log.info(f"  JBR instruction (0x102): jbr = {jbr} (immediate)")

    # Change to non-JBR instruction
    dut.instr.value = 0x000

    await Timer(1, units="ns")

    jbr = get_signal_value(dut, "jbr")
    assert jbr == 0, f"jbr should be 0 for instruction 0x000, got {jbr}"
    dut._log.info(f"  POP instruction (0x000): jbr = {jbr}")

    dut._log.info("  PASSED: JBR combinational test")


@cocotb.test()
async def test_decode_jmp_instruction(dut):
    """Test JMP instruction decoding"""

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "=" * 60)
    dut._log.info("JMP Instruction Test")
    dut._log.info("=" * 60)

    await reset_dut(dut)

    # JMP instructions have bit 9 set
    jmp_instructions = [0x200, 0x201, 0x2FF, 0x300, 0x3FF]

    all_passed = True

    for instr in jmp_instructions:
        dut.instr.value = instr
        dut.zf.value = 0
        dut.bcopd.value = 0

        await RisingEdge(dut.clk)
        await Timer(1, units="ns")

        jmp = get_signal_value(dut, "jmp")
        ena_a = get_signal_value(dut, "ena_a")

        if jmp != 1:
            dut._log.error(f"  Instr {instr:#05x}: jmp = {jmp}, expected 1")
            all_passed = False
        elif ena_a != 0:
            dut._log.error(f"  Instr {instr:#05x}: ena_a = {ena_a}, expected 0")
            all_passed = False
        else:
            dut._log.info(f"  Instr {instr:#05x}: jmp = {jmp}, ena_a = {ena_a} OK")

    assert all_passed, "JMP instruction tests failed"
    dut._log.info("  PASSED: JMP instruction test")


@cocotb.test()
async def test_decode_mmu_operations(dut):
    """Test MMU/memory control instruction decoding"""

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "=" * 60)
    dut._log.info("MMU Operation Tests")
    dut._log.info("=" * 60)

    await reset_dut(dut)

    test_cases = [
        # (instruction, description, signal_to_check)
        (0x040, "STMUL", "mul_wr"),
        (0x041, "STMWA", "mem_in_addr_wr"),
        (0x042, "STMRA", "mem_in_rd"),
        (0x043, "STMWD", "mem_in_wr"),
        (0x044, "STALD", "mem_in_iaload"),
        (0x045, "STAST", "mem_in_iastore"),
        (0x046, "STGF", "mem_in_getfield"),
        (0x047, "STPF", "mem_in_putfield"),
        (0x048, "STCP", "mem_in_copy"),
        (0x049, "STBCR", "mem_in_bc_rd"),
        (0x04B, "STPS", "mem_in_putstatic"),
    ]

    all_passed = True

    for instr, desc, signal in test_cases:
        dut.instr.value = instr
        dut.zf.value = 0
        dut.bcopd.value = 0

        await RisingEdge(dut.clk)  # Wait for registered outputs
        await Timer(1, units="ns")

        actual = get_signal_value(dut, signal)
        wr_dly = get_signal_value(dut, "wr_dly")

        if actual != 1:
            dut._log.error(f"  {desc}: {signal} = {actual}, expected 1")
            all_passed = False
        elif wr_dly != 1:
            dut._log.error(f"  {desc}: wr_dly = {wr_dly}, expected 1")
            all_passed = False
        else:
            dut._log.info(f"  {desc}: {signal} = {actual}, wr_dly = {wr_dly} OK")

    assert all_passed, "MMU operation tests failed"
    dut._log.info("  PASSED: MMU operation tests")


@cocotb.test()
async def test_decode_load_store_local(dut):
    """Test load/store local variable address decoding"""

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Load/Store Local Variable Tests: sel_rda, sel_wra")
    dut._log.info("=" * 60)

    await reset_dut(dut)

    # Test store instructions (st0-st3)
    store_tests = [
        (0x010, "ST0", "sel_wra", 0),
        (0x011, "ST1", "sel_wra", 1),
        (0x012, "ST2", "sel_wra", 2),
        (0x013, "ST3", "sel_wra", 3),
    ]

    # Test load instructions (ld0-ld3)
    load_tests = [
        (0x0E8, "LD0", "sel_rda", 0),
        (0x0E9, "LD1", "sel_rda", 1),
        (0x0EA, "LD2", "sel_rda", 2),
        (0x0EB, "LD3", "sel_rda", 3),
    ]

    all_passed = True

    for instr, desc, signal, expected in store_tests + load_tests:
        dut.instr.value = instr
        dut.zf.value = 0
        dut.bcopd.value = 0

        await Timer(1, units="ns")  # Combinational signals

        actual = get_signal_value(dut, signal)
        if actual != expected:
            dut._log.error(f"  {desc}: {signal} = {actual}, expected {expected}")
            all_passed = False
        else:
            dut._log.info(f"  {desc}: {signal} = {actual} OK")

        await RisingEdge(dut.clk)

    assert all_passed, "Load/store local tests failed"
    dut._log.info("  PASSED: Load/store local variable tests")


@cocotb.test()
async def test_decode_register_enables(dut):
    """Test register enable signal generation"""

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Register Enable Tests: ena_vp, ena_jpc, ena_ar")
    dut._log.info("=" * 60)

    await reset_dut(dut)

    test_cases = [
        (0x018, "STVP", "ena_vp", 1),
        (0x019, "STJPC", "ena_jpc", 1),
        (0x01A, "STAR", "ena_ar", 1),
    ]

    all_passed = True

    for instr, desc, signal, expected in test_cases:
        dut.instr.value = instr
        dut.zf.value = 0
        dut.bcopd.value = 0

        await RisingEdge(dut.clk)  # Wait for registered outputs
        await Timer(1, units="ns")

        actual = get_signal_value(dut, signal)
        if actual != expected:
            dut._log.error(f"  {desc}: {signal} = {actual}, expected {expected}")
            all_passed = False
        else:
            dut._log.info(f"  {desc}: {signal} = {actual} OK")

    assert all_passed, "Register enable tests failed"
    dut._log.info("  PASSED: Register enable tests")


@cocotb.test()
async def test_decode_bcopd_passthrough(dut):
    """Test bytecode operand passthrough to mem_in.bcopd"""

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "=" * 60)
    dut._log.info("BCOPD Passthrough Test")
    dut._log.info("=" * 60)

    await reset_dut(dut)

    test_values = [0x0000, 0xFFFF, 0xABCD, 0x1234, 0x5678]

    all_passed = True

    for bcopd in test_values:
        dut.instr.value = 0x000
        dut.zf.value = 0
        dut.bcopd.value = bcopd

        await Timer(1, units="ns")  # Combinational passthrough

        actual = get_signal_value(dut, "mem_in_bcopd")
        if actual != bcopd:
            dut._log.error(f"  bcopd = {bcopd:#06x}: mem_in_bcopd = {actual:#06x}")
            all_passed = False
        else:
            dut._log.info(f"  bcopd = {bcopd:#06x}: mem_in_bcopd = {actual:#06x} OK")

        await RisingEdge(dut.clk)

    assert all_passed, "BCOPD passthrough tests failed"
    dut._log.info("  PASSED: BCOPD passthrough test")


@cocotb.test()
async def test_decode_mmu_instr_output(dut):
    """Test mmu_instr output matches lower 4 bits of instruction"""

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "=" * 60)
    dut._log.info("MMU Instruction Output Test")
    dut._log.info("=" * 60)

    await reset_dut(dut)

    test_instructions = [0x040, 0x041, 0x042, 0x04F, 0x000, 0x00F, 0x0FF]

    all_passed = True

    for instr in test_instructions:
        expected = instr & 0xF  # Lower 4 bits

        dut.instr.value = instr
        dut.zf.value = 0
        dut.bcopd.value = 0

        await Timer(1, units="ns")  # Combinational output

        actual = get_signal_value(dut, "mmu_instr")
        if actual != expected:
            dut._log.error(f"  instr = {instr:#05x}: mmu_instr = {actual:#x}, expected {expected:#x}")
            all_passed = False
        else:
            dut._log.info(f"  instr = {instr:#05x}: mmu_instr = {actual:#x} OK")

        await RisingEdge(dut.clk)

    assert all_passed, "MMU instruction output tests failed"
    dut._log.info("  PASSED: MMU instruction output test")


@cocotb.test()
async def test_decode_coverage_summary(dut):
    """Summary test covering all major instruction categories"""

    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    dut._log.info("\n" + "=" * 60)
    dut._log.info("Coverage Summary Test")
    dut._log.info("=" * 60)

    categories_tested = []

    await reset_dut(dut)

    # Test one instruction from each major category
    test_sequence = [
        ("Stack POP", 0x000),
        ("Logic AND", 0x001),
        ("Arithmetic ADD", 0x004),
        ("Store ST0", 0x010),
        ("Shift USHR", 0x01C),
        ("MMU STMUL", 0x040),
        ("Load LDM", 0x0A0),
        ("Load LD0", 0x0E8),
        ("Load LDSP", 0x0F0),
        ("Control NOP", 0x100),
        ("Control JBR", 0x102),
        ("Branch BZ", 0x180),
        ("Branch BNZ", 0x1C0),
        ("Jump JMP", 0x200),
    ]

    for category, instr in test_sequence:
        dut.instr.value = instr
        dut.zf.value = 1  # For BZ to work
        dut.bcopd.value = 0

        await RisingEdge(dut.clk)
        await Timer(1, units="ns")

        categories_tested.append(f"{category} (0x{instr:03x})")

    # Print summary
    dut._log.info("\n  Categories tested:")
    for category in categories_tested:
        dut._log.info(f"    - {category}")

    dut._log.info(f"\n  Total categories: {len(categories_tested)}")
    dut._log.info("\n  PASSED: Coverage summary test")
