"""
CocoTB Tests for Interrupt/Exception Handling

Tests the interrupt and exception handling in BytecodeFetchStage:
- Interrupt pending latch (int_pend)
- Exception pending latch (exc_pend)
- Priority muxing: Exception > Interrupt > Normal bytecode
- Acknowledge signals (ack_irq, ack_exc)
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, Timer


class InterruptTestDriver:
    """Driver for InterruptTestTb testbench"""

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
        self.dut.io_jpc_wr.value = 0
        self.dut.io_din.value = 0
        self.dut.io_jfetch.value = 0
        self.dut.io_jopdfetch.value = 0
        self.dut.io_jbr.value = 0
        self.dut.io_zf.value = 0
        self.dut.io_nf.value = 0
        self.dut.io_eq.value = 0
        self.dut.io_lt.value = 0
        self.dut.io_irq.value = 0
        self.dut.io_exc.value = 0
        self.dut.io_ena.value = 0

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

    def get_jpaddr(self):
        """Get current jump address (microcode address from jump table)"""
        return int(self.dut.io_jpaddr.value)

    def get_sys_int_addr(self):
        """Get expected interrupt handler address"""
        return int(self.dut.io_sysIntAddr.value)

    def get_sys_exc_addr(self):
        """Get expected exception handler address"""
        return int(self.dut.io_sysExcAddr.value)

    def get_ack_irq(self):
        """Get interrupt acknowledge signal"""
        return int(self.dut.io_ack_irq.value)

    def get_ack_exc(self):
        """Get exception acknowledge signal"""
        return int(self.dut.io_ack_exc.value)

    async def fetch(self):
        """Perform a bytecode fetch (jfetch pulse)

        Returns the jpaddr value captured DURING the jfetch cycle.
        The key is to sample jpaddr AFTER setting jfetch but BEFORE
        the clock edge updates registers (which clears pending flags).
        """
        self.dut.io_jfetch.value = 1
        # Wait a tiny bit for combinational logic to settle
        await Timer(1, unit='ns')
        # Capture jpaddr while pending flags are still set
        jpaddr_during = int(self.dut.io_jpaddr.value)
        # Now wait for clock edge (this will clear pending flags)
        await RisingEdge(self.dut.io_clk)
        await Timer(1, unit='ns')
        self.dut.io_jfetch.value = 0
        await self.clock_cycles(1)
        return jpaddr_during

    async def assert_irq(self):
        """Assert interrupt request"""
        self.dut.io_irq.value = 1
        await self.clock_cycles(1)
        self.dut.io_irq.value = 0

    async def assert_exc(self):
        """Assert exception request"""
        self.dut.io_exc.value = 1
        await self.clock_cycles(1)
        self.dut.io_exc.value = 0


@cocotb.test()
async def test_reset_and_initial_state(dut):
    """Test that reset initializes correctly"""
    driver = InterruptTestDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # After reset, no interrupts or exceptions should be pending
    # jpaddr should show the normal bytecode lookup result
    assert driver.get_ack_irq() == 0, "Expected ack_irq=0 after reset"
    assert driver.get_ack_exc() == 0, "Expected ack_exc=0 after reset"

    cocotb.log.info("test_reset_and_initial_state PASSED")


@cocotb.test()
async def test_irq_without_enable(dut):
    """Test that IRQ is ignored when ena=0"""
    driver = InterruptTestDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Get normal bytecode address
    normal_addr = await driver.fetch()
    cocotb.log.info(f"Normal jpaddr: 0x{normal_addr:03x}")

    # Assert IRQ without enabling interrupts
    driver.dut.io_ena.value = 0
    await driver.assert_irq()

    # Fetch should use normal bytecode
    jpaddr = await driver.fetch()

    sys_int = driver.get_sys_int_addr()

    cocotb.log.info(f"After IRQ (ena=0): jpaddr=0x{jpaddr:03x}, sys_int=0x{sys_int:03x}")

    # Should NOT be at interrupt handler
    assert jpaddr != sys_int, f"Expected normal address, got sys_int=0x{sys_int:03x}"
    assert driver.get_ack_irq() == 0, "Expected no ack_irq when ena=0"

    cocotb.log.info("test_irq_without_enable PASSED")


@cocotb.test()
async def test_irq_with_enable(dut):
    """Test that IRQ triggers interrupt handler when ena=1"""
    driver = InterruptTestDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Enable interrupts
    driver.dut.io_ena.value = 1

    # Assert IRQ
    await driver.assert_irq()

    # Wait for pending latch to be set (register update)
    await driver.clock_cycles(1)

    # Fetch should trigger interrupt handler
    jpaddr = await driver.fetch()

    sys_int = driver.get_sys_int_addr()

    cocotb.log.info(f"After IRQ (ena=1): jpaddr=0x{jpaddr:03x}, sys_int=0x{sys_int:03x}")

    # Should be at interrupt handler
    assert jpaddr == sys_int, f"Expected sys_int=0x{sys_int:03x}, got 0x{jpaddr:03x}"

    cocotb.log.info("test_irq_with_enable PASSED")


@cocotb.test()
async def test_irq_acknowledge(dut):
    """Test that ack_irq is asserted during interrupt handling"""
    driver = InterruptTestDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Enable interrupts
    driver.dut.io_ena.value = 1

    # Assert IRQ
    await driver.assert_irq()
    await driver.clock_cycles(1)

    # Set jfetch and sample ack immediately (combinational)
    driver.dut.io_jfetch.value = 1
    await Timer(1, unit='ns')

    # ack_irq should be high during jfetch when int_pend is set
    ack_irq = driver.get_ack_irq()
    cocotb.log.info(f"ack_irq during jfetch: {ack_irq}")

    # Now complete the clock cycle (this clears int_pend)
    await RisingEdge(driver.dut.io_clk)
    await Timer(1, unit='ns')
    driver.dut.io_jfetch.value = 0
    await driver.clock_cycles(1)

    # After fetch completes, ack should go low
    ack_after = driver.get_ack_irq()
    cocotb.log.info(f"ack_irq after jfetch: {ack_after}")

    assert ack_irq == 1, "Expected ack_irq=1 during interrupt jfetch"
    assert ack_after == 0, "Expected ack_irq=0 after jfetch"

    cocotb.log.info("test_irq_acknowledge PASSED")


@cocotb.test()
async def test_exception_handler(dut):
    """Test that exception triggers exception handler"""
    driver = InterruptTestDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Assert exception
    await driver.assert_exc()
    await driver.clock_cycles(1)

    # Fetch should trigger exception handler
    jpaddr = await driver.fetch()

    sys_exc = driver.get_sys_exc_addr()

    cocotb.log.info(f"After exception: jpaddr=0x{jpaddr:03x}, sys_exc=0x{sys_exc:03x}")

    # Should be at exception handler
    assert jpaddr == sys_exc, f"Expected sys_exc=0x{sys_exc:03x}, got 0x{jpaddr:03x}"

    cocotb.log.info("test_exception_handler PASSED")


@cocotb.test()
async def test_exception_acknowledge(dut):
    """Test that ack_exc is asserted during exception handling"""
    driver = InterruptTestDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Assert exception
    await driver.assert_exc()
    await driver.clock_cycles(1)

    # Set jfetch and sample ack immediately (combinational)
    driver.dut.io_jfetch.value = 1
    await Timer(1, unit='ns')

    # ack_exc should be high during jfetch when exc_pend is set
    ack_exc = driver.get_ack_exc()
    cocotb.log.info(f"ack_exc during jfetch: {ack_exc}")

    # Now complete the clock cycle (this clears exc_pend)
    await RisingEdge(driver.dut.io_clk)
    await Timer(1, unit='ns')
    driver.dut.io_jfetch.value = 0
    await driver.clock_cycles(1)

    # After fetch completes, ack should go low
    ack_after = driver.get_ack_exc()
    cocotb.log.info(f"ack_exc after jfetch: {ack_after}")

    assert ack_exc == 1, "Expected ack_exc=1 during exception jfetch"
    assert ack_after == 0, "Expected ack_exc=0 after jfetch"

    cocotb.log.info("test_exception_acknowledge PASSED")


@cocotb.test()
async def test_exception_priority_over_interrupt(dut):
    """Test that exception has priority over interrupt"""
    driver = InterruptTestDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Enable interrupts
    driver.dut.io_ena.value = 1

    # Assert both IRQ and exception
    await driver.assert_irq()
    await driver.assert_exc()
    await driver.clock_cycles(1)

    # Fetch should trigger exception handler (higher priority)
    jpaddr = await driver.fetch()

    sys_int = driver.get_sys_int_addr()
    sys_exc = driver.get_sys_exc_addr()

    cocotb.log.info(f"After IRQ+exception: jpaddr=0x{jpaddr:03x}")
    cocotb.log.info(f"  sys_int=0x{sys_int:03x}, sys_exc=0x{sys_exc:03x}")

    # Should be at exception handler, not interrupt
    assert jpaddr == sys_exc, f"Expected sys_exc=0x{sys_exc:03x} (priority), got 0x{jpaddr:03x}"

    cocotb.log.info("test_exception_priority_over_interrupt PASSED")


@cocotb.test()
async def test_interrupt_after_exception_cleared(dut):
    """Test that interrupt is handled after exception is cleared"""
    driver = InterruptTestDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Enable interrupts
    driver.dut.io_ena.value = 1

    # Assert both IRQ and exception
    await driver.assert_irq()
    await driver.assert_exc()
    await driver.clock_cycles(1)

    # First fetch handles exception
    jpaddr1 = await driver.fetch()
    sys_exc = driver.get_sys_exc_addr()
    cocotb.log.info(f"First fetch: jpaddr=0x{jpaddr1:03x} (expected exception=0x{sys_exc:03x})")
    assert jpaddr1 == sys_exc, "First fetch should handle exception"

    # Second fetch should handle interrupt (exception now cleared)
    jpaddr2 = await driver.fetch()
    sys_int = driver.get_sys_int_addr()
    cocotb.log.info(f"Second fetch: jpaddr=0x{jpaddr2:03x} (expected interrupt=0x{sys_int:03x})")
    assert jpaddr2 == sys_int, "Second fetch should handle interrupt"

    cocotb.log.info("test_interrupt_after_exception_cleared PASSED")


@cocotb.test()
async def test_exception_without_interrupt_enable(dut):
    """Test that exception works even when interrupts are disabled"""
    driver = InterruptTestDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Disable interrupts (ena=0)
    driver.dut.io_ena.value = 0

    # Assert exception
    await driver.assert_exc()
    await driver.clock_cycles(1)

    # Fetch should still trigger exception handler
    jpaddr = await driver.fetch()

    sys_exc = driver.get_sys_exc_addr()

    cocotb.log.info(f"After exception (ena=0): jpaddr=0x{jpaddr:03x}, sys_exc=0x{sys_exc:03x}")

    # Exception should still work (not affected by ena)
    assert jpaddr == sys_exc, f"Expected sys_exc=0x{sys_exc:03x}, got 0x{jpaddr:03x}"

    cocotb.log.info("test_exception_without_interrupt_enable PASSED")


@cocotb.test()
async def test_normal_fetch_after_clearing(dut):
    """Test that normal bytecode fetch works after interrupt/exception cleared"""
    driver = InterruptTestDriver(dut)
    await driver.start_clock()
    await driver.reset()

    # Enable interrupts
    driver.dut.io_ena.value = 1

    # Get initial normal address
    normal_addr = await driver.fetch()
    cocotb.log.info(f"Initial normal jpaddr: 0x{normal_addr:03x}")

    # Trigger and handle interrupt
    await driver.assert_irq()
    await driver.clock_cycles(1)
    int_addr = await driver.fetch()
    sys_int = driver.get_sys_int_addr()
    cocotb.log.info(f"Interrupt jpaddr: 0x{int_addr:03x}")
    assert int_addr == sys_int, "Should be at interrupt handler"

    # Now fetch should return to normal bytecode
    final_addr = await driver.fetch()
    cocotb.log.info(f"After interrupt cleared: jpaddr=0x{final_addr:03x}")

    # Should not be at any handler address
    assert final_addr != sys_int, "Should not still be at interrupt handler"
    assert final_addr != driver.get_sys_exc_addr(), "Should not be at exception handler"

    cocotb.log.info("test_normal_fetch_after_clearing PASSED")
