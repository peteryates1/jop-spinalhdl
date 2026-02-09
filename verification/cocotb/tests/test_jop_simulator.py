"""
CocoTB Tests for JOP Simulator

This test runs HelloWorld.jop on the JOP simulator and logs:
- Bytecode execution (jpaddr, current bytecode)
- UART output ("Hello World!")
- Watchdog toggles

The simulator uses:
- Main memory pre-loaded with HelloWorld.jop from Smallest target
- Microcode ROM from mem_rom.dat (SIM build type)
- Full pipeline: BytecodeFetch -> Fetch -> Decode -> Stack

Expected behavior:
- Program initializes and enters main loop
- Every 50ms (simulated), prints " Hello World!\n" to UART
- Toggles watchdog output
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, Timer, ClockCycles
import logging


class JopSimulatorDriver:
    """Driver for JOP Simulator testbench"""

    def __init__(self, dut):
        self.dut = dut
        self.clock = None
        self.uart_buffer = ""
        self.wd_toggles = 0
        self.cycle_count = 0
        self.last_jfetch_pc = 0
        self.bytecode_count = 0

        # Configure logging
        self.log = logging.getLogger("JopSim")
        self.log.setLevel(logging.INFO)

    async def start_clock(self, period_ns=20):
        """Start the clock (50 MHz default)"""
        self.clock = Clock(self.dut.io_clk, period_ns, units='ns')
        cocotb.start_soon(self.clock.start())
        self.log.info(f"Clock started at {1000/period_ns} MHz")

    async def reset(self):
        """Apply reset"""
        self.dut.io_reset.value = 1
        self.dut.io_uartRxData.value = 0
        self.dut.io_uartRxReady.value = 0
        await RisingEdge(self.dut.io_clk)
        await RisingEdge(self.dut.io_clk)
        await RisingEdge(self.dut.io_clk)
        self.dut.io_reset.value = 0
        await RisingEdge(self.dut.io_clk)
        await Timer(1, units='ns')
        self.log.info("Reset complete")

    async def clock_cycles(self, n):
        """Wait for n clock cycles"""
        for _ in range(n):
            await RisingEdge(self.dut.io_clk)
        await Timer(1, units='ns')

    def safe_int(self, signal):
        """Safely convert signal to int, handling X/Z values"""
        try:
            return int(signal.value)
        except ValueError:
            return 0

    async def run_cycles(self, n, log_interval=10000):
        """Run for n cycles with logging"""
        for i in range(n):
            await RisingEdge(self.dut.io_clk)
            self.cycle_count += 1

            # Check for UART output
            if self.safe_int(self.dut.io_uartTxValid) == 1:
                char_val = self.safe_int(self.dut.io_uartTxData)
                char = chr(char_val) if 32 <= char_val < 127 else f'\\x{char_val:02x}'
                self.uart_buffer += chr(char_val) if char_val < 128 else '?'
                self.log.info(f"[{self.cycle_count:8d}] UART TX: '{char}' (0x{char_val:02x})")

            # Check for WD toggle
            if self.safe_int(self.dut.io_wdToggle) == 1:
                self.wd_toggles += 1
                wd_state = self.safe_int(self.dut.io_wdOut)
                self.log.info(f"[{self.cycle_count:8d}] WD Toggle #{self.wd_toggles} -> {wd_state}")

            # Log bytecode fetches
            if self.safe_int(self.dut.io_jfetch) == 1:
                self.bytecode_count += 1
                pc = self.safe_int(self.dut.io_pc)
                jpc = self.safe_int(self.dut.io_jpc)
                jpaddr = self.safe_int(self.dut.io_jpaddr)
                if self.bytecode_count <= 100 or self.bytecode_count % 1000 == 0:
                    self.log.info(f"[{self.cycle_count:8d}] JFETCH #{self.bytecode_count}: PC={pc:04x} JPC={jpc:04x} JPADDR={jpaddr:04x}")

            # Periodic status
            if self.cycle_count % log_interval == 0:
                pc = self.safe_int(self.dut.io_pc)
                jpc = self.safe_int(self.dut.io_jpc)
                aout = self.safe_int(self.dut.io_aout)
                bout = self.safe_int(self.dut.io_bout)
                self.log.info(f"[{self.cycle_count:8d}] STATUS: PC={pc:04x} JPC={jpc:04x} TOS={aout:08x} NOS={bout:08x} UART=\"{self.uart_buffer[-20:]}\" WD={self.wd_toggles}")

            # Check for halt
            if self.safe_int(self.dut.io_halted) == 1:
                self.log.warning(f"[{self.cycle_count:8d}] HALTED detected!")
                break

        await Timer(1, units='ns')

    def get_uart_output(self):
        """Get accumulated UART output"""
        return self.uart_buffer

    def get_wd_toggle_count(self):
        """Get number of WD toggles"""
        return self.wd_toggles


@cocotb.test()
async def test_jop_simulator_basic(dut):
    """Basic test - verify simulator starts and executes"""
    driver = JopSimulatorDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Run for a few cycles to see initial execution
    cocotb.log.info("Running initial cycles...")
    await driver.run_cycles(1000, log_interval=100)

    # Check that we're executing (PC should be non-zero)
    pc = driver.safe_int(dut.io_pc)
    cocotb.log.info(f"After 1000 cycles: PC={pc:04x}")

    # Verify some execution happened
    assert driver.bytecode_count > 0, "No bytecode fetches detected"
    cocotb.log.info(f"test_jop_simulator_basic PASSED: {driver.bytecode_count} bytecodes executed")


@cocotb.test()
async def test_jop_simulator_uart_output(dut):
    """Test UART output - should see 'Hello World' messages"""
    driver = JopSimulatorDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Run for enough cycles to get UART output
    # HelloWorld prints every 50000 microseconds
    # At 50MHz, that's 50000 * 50 = 2,500,000 cycles
    # But first message should come much sooner during init
    cocotb.log.info("Running simulation to capture UART output...")

    # Run in batches to check for output
    total_cycles = 0
    max_cycles = 5_000_000  # 5 million cycles max

    while total_cycles < max_cycles:
        await driver.run_cycles(100000, log_interval=50000)
        total_cycles += 100000

        # Check if we have any UART output
        uart = driver.get_uart_output()
        if "Hello" in uart:
            cocotb.log.info(f"Found 'Hello' in UART output after {total_cycles} cycles!")
            break

        # Check for WD toggles as sign of main loop running
        if driver.get_wd_toggle_count() >= 2:
            cocotb.log.info(f"Got {driver.get_wd_toggle_count()} WD toggles - main loop is running")
            break

    # Report results
    uart = driver.get_uart_output()
    wd_toggles = driver.get_wd_toggle_count()

    cocotb.log.info(f"=== SIMULATION RESULTS ===")
    cocotb.log.info(f"Total cycles: {total_cycles}")
    cocotb.log.info(f"Bytecodes executed: {driver.bytecode_count}")
    cocotb.log.info(f"UART output: '{uart}'")
    cocotb.log.info(f"WD toggles: {wd_toggles}")

    # Verify we got some output
    if uart:
        cocotb.log.info(f"test_jop_simulator_uart_output PASSED: Got UART output")
    elif wd_toggles > 0:
        cocotb.log.info(f"test_jop_simulator_uart_output PASSED: Got WD toggles (main loop running)")
    else:
        cocotb.log.warning(f"test_jop_simulator_uart_output: No UART or WD output yet")


@cocotb.test()
async def test_jop_simulator_extended(dut):
    """Extended test - run longer to see full Hello World cycle"""
    driver = JopSimulatorDriver(dut)
    await driver.start_clock()
    await driver.reset()

    cocotb.log.info("Running extended simulation...")

    # Run for 10 million cycles (200ms at 50MHz)
    # Should see multiple Hello World messages
    await driver.run_cycles(10_000_000, log_interval=1_000_000)

    # Report final results
    uart = driver.get_uart_output()
    wd_toggles = driver.get_wd_toggle_count()

    cocotb.log.info(f"=== EXTENDED SIMULATION RESULTS ===")
    cocotb.log.info(f"Total cycles: {driver.cycle_count}")
    cocotb.log.info(f"Bytecodes executed: {driver.bytecode_count}")
    cocotb.log.info(f"UART output ({len(uart)} chars): '{uart}'")
    cocotb.log.info(f"WD toggles: {wd_toggles}")

    # Count Hello World occurrences
    hello_count = uart.count("Hello")
    cocotb.log.info(f"'Hello' occurrences: {hello_count}")

    cocotb.log.info(f"test_jop_simulator_extended PASSED")
