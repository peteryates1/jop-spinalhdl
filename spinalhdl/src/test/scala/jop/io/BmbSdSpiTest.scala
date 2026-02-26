package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.collection.mutable

class BmbSdSpiTest extends AnyFunSuite {

  val simConfig = SimConfig
    .workspacePath("simWorkspace")

  def compileDut() = simConfig.compile(BmbSdSpi(clkDivInit = 199))

  /** Write a value to the DUT's I/O register interface */
  def ioWrite(dut: BmbSdSpi, addr: Int, data: Long)(implicit cd: ClockDomain): Unit = {
    dut.io.addr #= addr
    dut.io.wrData #= data
    dut.io.wr #= true
    dut.io.rd #= false
    cd.waitSampling()
    dut.io.wr #= false
  }

  /** Read a value from the DUT's I/O register interface */
  def ioRead(dut: BmbSdSpi, addr: Int)(implicit cd: ClockDomain): Long = {
    dut.io.addr #= addr
    dut.io.rd #= true
    dut.io.wr #= false
    cd.waitSampling()
    val result = dut.io.rdData.toLong
    dut.io.rd #= false
    result
  }

  /** Initialize DUT I/O signals to idle state */
  def initIo(dut: BmbSdSpi): Unit = {
    dut.io.addr #= 0
    dut.io.rd #= false
    dut.io.wr #= false
    dut.io.wrData #= 0
    dut.io.miso #= true   // MISO idles high (SD card default)
    dut.io.cd #= true      // No card present (active low)
  }

  test("BmbSdSpi_cs_control") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      SimTimeout(100000)

      initIo(dut)
      cd.waitSampling(10)

      // Initially CS should be high (deasserted, csAssertReg=0 -> cs=1)
      assert(dut.io.cs.toBoolean == true, "CS should be high (deasserted) initially")

      // Write 0x01 to addr 0: csAssert=1 -> CS goes low
      ioWrite(dut, 0, 0x01)
      cd.waitSampling()
      assert(dut.io.cs.toBoolean == false, "CS should be low (asserted) after writing 0x01")

      // Write 0x00 to addr 0: csAssert=0 -> CS goes high
      ioWrite(dut, 0, 0x00)
      cd.waitSampling()
      assert(dut.io.cs.toBoolean == true, "CS should be high (deasserted) after writing 0x00")
    }
  }

  test("BmbSdSpi_spi_transfer") {
    // Use a separate compile with divider=1 for fast simulation
    val fastDut = simConfig.compile(BmbSdSpi(clkDivInit = 1))
    fastDut.doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      SimTimeout(500000)

      initIo(dut)
      cd.waitSampling(10)

      // Set clock divider to 1 (fastest: SPI_CLK = sys_clk / 4)
      ioWrite(dut, 2, 1)
      cd.waitSampling()

      // Assert CS
      ioWrite(dut, 0, 0x01)
      cd.waitSampling()

      // MISO pattern to drive: 0xB4 = 10110100 (MSB first)
      // We will drive bits MSB first as the master clocks them in
      val misoPattern = 0xB4
      val misoBits = (0 until 8).map(i => ((misoPattern >> (7 - i)) & 1) == 1)

      // Monitor MOSI output to capture the 8 bits shifted out
      val mosiBits = mutable.ArrayBuffer[Boolean]()
      var misoBitIndex = 0

      // Fork a thread to drive MISO and capture MOSI on SCLK edges.
      //
      // SPI Mode 0 timing: MOSI is valid while SCLK is low (set up before
      // rising edge). The DUT updates shiftReg on the system clock edge
      // where sclkReg transitions 0->1, so after waitSampling we see the
      // *post-shift* MOSI. We must capture MOSI on the cycle *before* the
      // rising edge (i.e., when SCLK is still low), saving it each cycle.
      val monitorThread = fork {
        var prevSclk = false
        var prevMosi = false
        // Pre-drive first MISO bit
        dut.io.miso #= misoBits(0)
        while (mosiBits.size < 8) {
          // Save MOSI value before advancing the clock.
          // This is the value the slave would see during the low phase.
          prevMosi = dut.io.mosi.toBoolean
          cd.waitSampling()
          val curSclk = dut.io.sclk.toBoolean

          if (curSclk && !prevSclk) {
            // Rising edge detected: the MOSI bit that was present *before*
            // this clock edge is the one the master intended for this bit slot.
            mosiBits += prevMosi
            misoBitIndex += 1
            // Drive next MISO bit (it will be sampled on the next rising edge)
            if (misoBitIndex < 8) {
              dut.io.miso #= misoBits(misoBitIndex)
            }
          }
          prevSclk = curSclk
        }
      }

      // Write TX byte 0xA5 to addr 1 to start transfer
      ioWrite(dut, 1, 0xA5)

      // Poll for busy=0
      var timeout = 5000
      var stillBusy = true
      while (stillBusy && timeout > 0) {
        val status = ioRead(dut, 0)
        stillBusy = (status & 0x01) != 0
        timeout -= 1
      }
      assert(!stillBusy, "Transfer should complete (busy=0)")

      // Wait for monitor to finish
      cd.waitSampling(10)

      // Read RX byte from addr 1
      val rxByte = ioRead(dut, 1) & 0xFF
      assert(rxByte == misoPattern,
        f"RX byte should be 0x$misoPattern%02X, got 0x$rxByte%02X")

      // Verify MOSI bits match 0xA5 = 10100101 MSB first
      assert(mosiBits.size == 8, s"Expected 8 MOSI bits, got ${mosiBits.size}")
      val txByte = 0xA5
      for (i <- 0 until 8) {
        val expectedBit = ((txByte >> (7 - i)) & 1) == 1
        assert(mosiBits(i) == expectedBit,
          s"MOSI bit $i: expected $expectedBit, got ${mosiBits(i)}")
      }
    }
  }

  test("BmbSdSpi_card_detect") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      SimTimeout(100000)

      initIo(dut)
      cd.waitSampling(10)

      // Card inserted: cd=false (active low) -> cardPresent=1
      dut.io.cd #= false
      // Wait for BufferCC to propagate (2 cycles + margin)
      cd.waitSampling(5)

      val status1 = ioRead(dut, 0)
      assert((status1 & 0x02) != 0,
        s"cardPresent (bit 1) should be 1 when cd=false, got status=0x${status1.toHexString}")

      // Card removed: cd=true (active low) -> cardPresent=0
      dut.io.cd #= true
      cd.waitSampling(5)

      val status2 = ioRead(dut, 0)
      assert((status2 & 0x02) == 0,
        s"cardPresent (bit 1) should be 0 when cd=true, got status=0x${status2.toHexString}")
    }
  }

  test("BmbSdSpi_interrupt") {
    val fastDut = simConfig.compile(BmbSdSpi(clkDivInit = 1))
    fastDut.doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      SimTimeout(500000)

      initIo(dut)
      cd.waitSampling(10)

      // Set fast clock divider
      ioWrite(dut, 2, 1)
      cd.waitSampling()

      // Enable interrupt: write 0x02 to addr 0 (bit1=intEnable, bit0=csAssert=0)
      ioWrite(dut, 0, 0x02)
      cd.waitSampling()

      // Monitor interrupt pulses in a fork
      var intCount = 0
      val monitorThread = fork {
        while (true) {
          cd.waitSampling()
          if (dut.io.interrupt.toBoolean) intCount += 1
        }
      }

      // Drive MISO high (idle) during transfer
      dut.io.miso #= true

      // Start transfer 1 (with intEnable=1)
      ioWrite(dut, 1, 0xFF)

      // Wait for busy=0
      var timeout = 5000
      var stillBusy = true
      while (stillBusy && timeout > 0) {
        val status = ioRead(dut, 0)
        stillBusy = (status & 0x01) != 0
        timeout -= 1
      }
      assert(!stillBusy, "Transfer 1 should complete")

      // Let interrupt propagate
      cd.waitSampling(5)

      assert(intCount == 1, s"Expected exactly 1 interrupt pulse with intEnable=1, got $intCount")

      // Now disable interrupt: write 0x00 (intEnable=0, csAssert=0)
      ioWrite(dut, 0, 0x00)
      cd.waitSampling()

      val countBefore = intCount

      // Start transfer 2 (with intEnable=0)
      ioWrite(dut, 1, 0xAA)

      timeout = 5000
      stillBusy = true
      while (stillBusy && timeout > 0) {
        val status = ioRead(dut, 0)
        stillBusy = (status & 0x01) != 0
        timeout -= 1
      }
      assert(!stillBusy, "Transfer 2 should complete")

      cd.waitSampling(5)

      assert(intCount == countBefore,
        s"No interrupt should fire with intEnable=0, count went from $countBefore to $intCount")
    }
  }

  test("BmbSdSpi_back_to_back_transfers") {
    val fastDut = simConfig.compile(BmbSdSpi(clkDivInit = 1))
    fastDut.doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      SimTimeout(500000)

      initIo(dut)
      cd.waitSampling(10)

      // Set fast divider, assert CS
      ioWrite(dut, 2, 1)
      ioWrite(dut, 0, 0x01)
      cd.waitSampling()

      // --- Transfer 1: TX=0xA5, MISO drives 0x3C ---
      val tx1 = 0xA5
      val miso1 = 0x3C
      val miso1Bits = (0 until 8).map(i => ((miso1 >> (7 - i)) & 1) == 1)
      val mosi1Bits = mutable.ArrayBuffer[Boolean]()
      var miso1BitIdx = 0

      val mon1 = fork {
        var prevSclk = false
        var prevMosi = false
        dut.io.miso #= miso1Bits(0)
        while (mosi1Bits.size < 8) {
          prevMosi = dut.io.mosi.toBoolean
          cd.waitSampling()
          val curSclk = dut.io.sclk.toBoolean
          if (curSclk && !prevSclk) {
            mosi1Bits += prevMosi
            miso1BitIdx += 1
            if (miso1BitIdx < 8) dut.io.miso #= miso1Bits(miso1BitIdx)
          }
          prevSclk = curSclk
        }
      }

      ioWrite(dut, 1, tx1)

      var timeout = 5000
      var stillBusy = true
      while (stillBusy && timeout > 0) {
        val status = ioRead(dut, 0)
        stillBusy = (status & 0x01) != 0
        timeout -= 1
      }
      assert(!stillBusy, "Transfer 1 should complete")
      cd.waitSampling(5)

      val rx1 = ioRead(dut, 1) & 0xFF
      assert(rx1 == miso1, f"Transfer 1 RX: expected 0x$miso1%02X, got 0x$rx1%02X")

      // Verify MOSI for transfer 1
      assert(mosi1Bits.size == 8, s"Expected 8 MOSI bits for transfer 1, got ${mosi1Bits.size}")
      for (i <- 0 until 8) {
        val expected = ((tx1 >> (7 - i)) & 1) == 1
        assert(mosi1Bits(i) == expected,
          s"Transfer 1 MOSI bit $i: expected $expected, got ${mosi1Bits(i)}")
      }

      // --- Transfer 2: TX=0x5A, MISO drives 0xC3 ---
      val tx2 = 0x5A
      val miso2 = 0xC3
      val miso2Bits = (0 until 8).map(i => ((miso2 >> (7 - i)) & 1) == 1)
      val mosi2Bits = mutable.ArrayBuffer[Boolean]()
      var miso2BitIdx = 0

      val mon2 = fork {
        var prevSclk = false
        var prevMosi = false
        dut.io.miso #= miso2Bits(0)
        while (mosi2Bits.size < 8) {
          prevMosi = dut.io.mosi.toBoolean
          cd.waitSampling()
          val curSclk = dut.io.sclk.toBoolean
          if (curSclk && !prevSclk) {
            mosi2Bits += prevMosi
            miso2BitIdx += 1
            if (miso2BitIdx < 8) dut.io.miso #= miso2Bits(miso2BitIdx)
          }
          prevSclk = curSclk
        }
      }

      ioWrite(dut, 1, tx2)

      timeout = 5000
      stillBusy = true
      while (stillBusy && timeout > 0) {
        val status = ioRead(dut, 0)
        stillBusy = (status & 0x01) != 0
        timeout -= 1
      }
      assert(!stillBusy, "Transfer 2 should complete")
      cd.waitSampling(5)

      val rx2 = ioRead(dut, 1) & 0xFF
      assert(rx2 == miso2, f"Transfer 2 RX: expected 0x$miso2%02X, got 0x$rx2%02X")

      // Verify MOSI for transfer 2
      assert(mosi2Bits.size == 8, s"Expected 8 MOSI bits for transfer 2, got ${mosi2Bits.size}")
      for (i <- 0 until 8) {
        val expected = ((tx2 >> (7 - i)) & 1) == 1
        assert(mosi2Bits(i) == expected,
          s"Transfer 2 MOSI bit $i: expected $expected, got ${mosi2Bits(i)}")
      }
    }
  }

  test("BmbSdSpi_clock_divider") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      SimTimeout(100000)

      initIo(dut)
      cd.waitSampling(10)

      // Default divider should be 199
      val div0 = ioRead(dut, 2) & 0xFFFF
      assert(div0 == 199, s"Default divider should be 199, got $div0")

      // Write a new divider value
      ioWrite(dut, 2, 42)
      cd.waitSampling()

      val div1 = ioRead(dut, 2) & 0xFFFF
      assert(div1 == 42, s"Divider should be 42 after write, got $div1")

      // Write another value
      ioWrite(dut, 2, 0xBEEF)
      cd.waitSampling()

      val div2 = ioRead(dut, 2) & 0xFFFF
      assert(div2 == 0xBEEF, s"Divider should be 0xBEEF after write, got 0x${div2.toHexString}")
    }
  }
}
