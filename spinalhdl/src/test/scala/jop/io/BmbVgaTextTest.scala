package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class BmbVgaTextTest extends AnyFunSuite {

  val simConfig = SimConfig
    .workspacePath("simWorkspace")

  def compileDut() = simConfig.compile(
    BmbVgaText(
      vgaCd = ClockDomain.external("vgaCd", withReset = false, config = ClockDomainConfig(resetKind = BOOT))
    )
  )

  /** Write a value to the DUT's I/O register interface */
  def ioWrite(dut: BmbVgaText, addr: Int, data: Long)(implicit cd: ClockDomain): Unit = {
    dut.io.addr #= addr
    dut.io.wrData #= data
    dut.io.wr #= true
    dut.io.rd #= false
    cd.waitSampling()
    dut.io.wr #= false
  }

  /** Read a value from the DUT's I/O register interface */
  def ioRead(dut: BmbVgaText, addr: Int)(implicit cd: ClockDomain): Long = {
    dut.io.addr #= addr
    dut.io.rd #= true
    dut.io.wr #= false
    cd.waitSampling()
    val result = dut.io.rdData.toLong
    dut.io.rd #= false
    result
  }

  /** Initialize DUT I/O signals to idle state */
  def initIo(dut: BmbVgaText): Unit = {
    dut.io.addr #= 0
    dut.io.rd #= false
    dut.io.wr #= false
    dut.io.wrData #= 0
  }

  test("BmbVgaText_control_and_status") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)   // 100 MHz system
      dut.vgaCd.forkStimulus(40) // 25 MHz pixel
      SimTimeout(100000)

      initIo(dut)
      cd.waitSampling(20)

      // Initial state: disabled
      val status0 = ioRead(dut, 0)
      assert((status0 & 0x01) == 0, s"VGA should be disabled initially, got status=$status0")

      // Enable VGA output
      ioWrite(dut, 0, 0x01)
      cd.waitSampling(5)

      val status1 = ioRead(dut, 0)
      assert((status1 & 0x01) == 1, s"VGA should be enabled after write, got status=$status1")

      // Disable VGA output
      ioWrite(dut, 0, 0x00)
      cd.waitSampling(5)

      val status2 = ioRead(dut, 0)
      assert((status2 & 0x01) == 0, s"VGA should be disabled after clear, got status=$status2")
    }
  }

  test("BmbVgaText_cursor_and_write") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.vgaCd.forkStimulus(40)
      SimTimeout(100000)

      initIo(dut)
      cd.waitSampling(20)

      // Set cursor to (0, 0) via addr 1: col=0 [6:0], row=0 [12:8]
      ioWrite(dut, 1, 0x0000)
      cd.waitSampling(2)

      // Verify cursor position
      val cursor0 = ioRead(dut, 1)
      val col0 = cursor0 & 0x7F
      val row0 = (cursor0 >> 8) & 0x1F
      assert(col0 == 0, s"Cursor col should be 0, got $col0")
      assert(row0 == 0, s"Cursor row should be 0, got $row0")

      // Write char 'A' (0x41) + attr 0x0F (white on black) to addr 2
      ioWrite(dut, 2, 0x0F41)
      cd.waitSampling(2)

      // Cursor should have auto-advanced to (1, 0)
      val cursor1 = ioRead(dut, 1)
      val col1 = cursor1 & 0x7F
      val row1 = (cursor1 >> 8) & 0x1F
      assert(col1 == 1, s"Cursor col should be 1 after write, got $col1")
      assert(row1 == 0, s"Cursor row should be 0 after write, got $row1")

      // Write another char 'B' (0x42) + attr 0x0F
      ioWrite(dut, 2, 0x0F42)
      cd.waitSampling(2)

      // Cursor should be at (2, 0)
      val cursor2 = ioRead(dut, 1)
      val col2 = cursor2 & 0x7F
      val row2 = (cursor2 >> 8) & 0x1F
      assert(col2 == 2, s"Cursor col should be 2 after second write, got $col2")
      assert(row2 == 0, s"Cursor row should be 0 after second write, got $row2")
    }
  }

  test("BmbVgaText_cursor_wrap") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.vgaCd.forkStimulus(40)
      SimTimeout(200000)

      initIo(dut)
      cd.waitSampling(20)

      // Set cursor to (79, 0) — end of first row
      val cursorVal = (0 << 8) | 79
      ioWrite(dut, 1, cursorVal)
      cd.waitSampling(2)

      // Write a char — should auto-advance to (0, 1)
      ioWrite(dut, 2, 0x0F41)
      cd.waitSampling(2)

      val cursor = ioRead(dut, 1)
      val col = cursor & 0x7F
      val row = (cursor >> 8) & 0x1F
      assert(col == 0, s"Cursor col should wrap to 0, got $col")
      assert(row == 1, s"Cursor row should advance to 1, got $row")
    }
  }

  test("BmbVgaText_direct_write") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.vgaCd.forkStimulus(40)
      SimTimeout(100000)

      initIo(dut)
      cd.waitSampling(20)

      // Direct write 'B' (0x42) + attr 0x1E at column 10, row 5 via addr 7
      // Format: char[7:0]=0x42, attr[15:8]=0x1E, col[22:16]=10, row[28:24]=5
      val directVal = (5L << 24) | (10L << 16) | (0x1E << 8) | 0x42
      ioWrite(dut, 7, directVal)
      cd.waitSampling(2)

      // Direct write 'A' (0x41) + attr 0x0F at (0, 0)
      val directVal2 = (0L << 24) | (0L << 16) | (0x0F << 8) | 0x41
      ioWrite(dut, 7, directVal2)
      cd.waitSampling(2)

      // Verify cursor was not affected (should still be at initial position 0,0)
      val cursor = ioRead(dut, 1)
      val col = cursor & 0x7F
      val row = (cursor >> 8) & 0x1F
      assert(col == 0, s"Cursor col should be unaffected by direct write, got $col")
      assert(row == 0, s"Cursor row should be unaffected by direct write, got $row")
    }
  }

  test("BmbVgaText_palette_write") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.vgaCd.forkStimulus(40)
      SimTimeout(100000)

      initIo(dut)
      cd.waitSampling(20)

      // Write palette entry 0 = 0xF800 (red) via addr 4
      // Format: index[19:16]=0, rgb565[15:0]=0xF800
      val paletteVal = (0L << 16) | 0xF800
      ioWrite(dut, 4, paletteVal)
      cd.waitSampling(5)

      // Write palette entry 15 = 0x001F (blue)
      val paletteVal2 = (15L << 16) | 0x001F
      ioWrite(dut, 4, paletteVal2)
      cd.waitSampling(5)

      // No crash — just verify we can still read status
      val status = ioRead(dut, 0)
      // Should be valid (enabled bit is 0)
      assert((status & 0x01) == 0, s"VGA should still be disabled, got status=$status")
    }
  }

  test("BmbVgaText_default_attribute") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.vgaCd.forkStimulus(40)
      SimTimeout(100000)

      initIo(dut)
      cd.waitSampling(20)

      // Read default attribute — should be 0x0F (white on black)
      val attr0 = ioRead(dut, 3)
      assert((attr0 & 0xFF) == 0x0F, s"Default attr should be 0x0F, got 0x${(attr0 & 0xFF).toHexString}")

      // Set default attribute to 0x1E (yellow on blue)
      ioWrite(dut, 3, 0x1E)
      cd.waitSampling(2)

      val attr1 = ioRead(dut, 3)
      assert((attr1 & 0xFF) == 0x1E, s"Default attr should be 0x1E, got 0x${(attr1 & 0xFF).toHexString}")
    }
  }

  test("BmbVgaText_dimensions_readback") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.vgaCd.forkStimulus(40)
      SimTimeout(100000)

      initIo(dut)
      cd.waitSampling(20)

      // Read columns (addr 5)
      val cols = ioRead(dut, 5)
      assert(cols == 80, s"Columns should be 80, got $cols")

      // Read rows (addr 6)
      val rows = ioRead(dut, 6)
      assert(rows == 30, s"Rows should be 30, got $rows")
    }
  }

  test("BmbVgaText_vga_timing") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)   // 100 MHz system
      dut.vgaCd.forkStimulus(40) // 25 MHz pixel
      SimTimeout(50000000)  // 50ms — enough for more than one full frame

      initIo(dut)

      // Enable VGA output
      ioWrite(dut, 0, 0x01)

      // Wait for initial stabilization
      dut.vgaCd.waitSampling(10)

      // Measure hsync period: count pixel clocks between falling edges of vgaHsync
      // vgaHsync is active low, so falling edge = start of sync pulse
      var prevHsync = dut.io.vgaHsync.toBoolean
      var hsyncFallingCount = 0
      var pixelClocksBetweenHsync = 0
      var measuredHsyncPeriod = 0

      // First, wait for the first hsync falling edge
      var timeout = 2000
      while (timeout > 0 && !(prevHsync && !dut.io.vgaHsync.toBoolean)) {
        prevHsync = dut.io.vgaHsync.toBoolean
        dut.vgaCd.waitSampling()
        timeout -= 1
      }
      assert(timeout > 0, "Never saw first hsync falling edge")

      // Now count pixel clocks until next hsync falling edge
      prevHsync = dut.io.vgaHsync.toBoolean
      pixelClocksBetweenHsync = 0
      timeout = 1000
      var foundSecondEdge = false
      while (timeout > 0 && !foundSecondEdge) {
        dut.vgaCd.waitSampling()
        pixelClocksBetweenHsync += 1
        val currentHsync = dut.io.vgaHsync.toBoolean
        if (prevHsync && !currentHsync) {
          foundSecondEdge = true
          measuredHsyncPeriod = pixelClocksBetweenHsync
        }
        prevHsync = currentHsync
        timeout -= 1
      }
      assert(foundSecondEdge, "Never saw second hsync falling edge")
      assert(measuredHsyncPeriod == 800,
        s"Hsync period should be 800 pixel clocks, got $measuredHsyncPeriod")

      // Measure vsync period: count hsync falling edges between vsync falling edges
      // First wait for vsync falling edge
      var prevVsync = dut.io.vgaVsync.toBoolean
      timeout = 500000
      while (timeout > 0 && !(prevVsync && !dut.io.vgaVsync.toBoolean)) {
        prevVsync = dut.io.vgaVsync.toBoolean
        dut.vgaCd.waitSampling()
        timeout -= 1
      }
      assert(timeout > 0, "Never saw first vsync falling edge")

      // Count pixel clocks until next vsync falling edge
      prevVsync = dut.io.vgaVsync.toBoolean
      var pixelClocksBetweenVsync = 0
      timeout = 500000
      var foundSecondVsyncEdge = false
      while (timeout > 0 && !foundSecondVsyncEdge) {
        dut.vgaCd.waitSampling()
        pixelClocksBetweenVsync += 1
        val currentVsync = dut.io.vgaVsync.toBoolean
        if (prevVsync && !currentVsync) {
          foundSecondVsyncEdge = true
        }
        prevVsync = currentVsync
        timeout -= 1
      }
      assert(foundSecondVsyncEdge, "Never saw second vsync falling edge")
      val expectedVsyncPeriod = 800 * 525  // 420000 pixel clocks
      assert(pixelClocksBetweenVsync == expectedVsyncPeriod,
        s"Vsync period should be $expectedVsyncPeriod pixel clocks, got $pixelClocksBetweenVsync")
    }
  }

  test("BmbVgaText_clear_screen") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.vgaCd.forkStimulus(40)
      SimTimeout(500000)

      initIo(dut)
      cd.waitSampling(20)

      // Write a character first
      ioWrite(dut, 1, 0x0000) // cursor at (0,0)
      cd.waitSampling(2)
      ioWrite(dut, 2, 0x0F41) // 'A' with white-on-black
      cd.waitSampling(2)

      // Clear screen (write to addr 8)
      ioWrite(dut, 8, 0)
      cd.waitSampling(5)

      // Status bit 2 should indicate busy
      val status = ioRead(dut, 0)
      assert((status & 0x04) != 0, s"Scroll/clear busy bit should be set during clear, got status=$status")

      // Wait for clear to finish (2400 cycles max)
      var timeout = 3000
      var busy = true
      while (busy && timeout > 0) {
        cd.waitSampling()
        val s = dut.io.rdData.toLong
        dut.io.addr #= 0
        dut.io.rd #= true
        cd.waitSampling()
        busy = (dut.io.rdData.toLong & 0x04) != 0
        dut.io.rd #= false
        timeout -= 1
      }
      assert(!busy, "Clear operation did not complete within expected time")
    }
  }

  test("BmbVgaText_scroll_up") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.vgaCd.forkStimulus(40)
      SimTimeout(1000000)

      initIo(dut)
      cd.waitSampling(20)

      // Write to addr 9 to trigger scroll
      ioWrite(dut, 9, 0)
      cd.waitSampling(5)

      // Status bit 2 should be set (scroll busy)
      val status = ioRead(dut, 0)
      assert((status & 0x04) != 0, s"Scroll busy bit should be set, got status=$status")

      // Wait for scroll + clear of last row to complete
      // Scroll: ~2320 * 3 cycles (3 phases) = ~6960 cycles
      // Then clear last row: ~80 cycles
      var timeout = 10000
      var busy = true
      while (busy && timeout > 0) {
        dut.io.addr #= 0
        dut.io.rd #= true
        cd.waitSampling()
        busy = (dut.io.rdData.toLong & 0x04) != 0
        dut.io.rd #= false
        timeout -= 1
      }
      assert(!busy, "Scroll operation did not complete within expected time")
    }
  }
}
