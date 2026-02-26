package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.bmb._

class BmbVgaDmaTest extends AnyFunSuite {

  val testBmbParam = BmbParameter(
    access = BmbAccessParameter(
      addressWidth = 26,
      dataWidth = 32
    ).addSources(2, BmbSourceParameter(
      contextWidth = 4,
      lengthWidth = 6,
      canRead = true,
      canWrite = true,
      alignment = BmbParameter.BurstAlignement.WORD
    )),
    invalidation = BmbInvalidationParameter()
  )

  val simConfig = SimConfig
    .workspacePath("simWorkspace")

  def compileDut() = simConfig.compile(
    BmbVgaDma(
      bmbParam = testBmbParam,
      vgaCd = ClockDomain.external("vgaCd"),
      fifoDepth = 256
    )
  )

  /** Write a value to the DUT's I/O register interface */
  def ioWrite(dut: BmbVgaDma, addr: Int, data: Long)(implicit cd: ClockDomain): Unit = {
    dut.io.addr #= addr
    dut.io.wrData #= data
    dut.io.wr #= true
    dut.io.rd #= false
    cd.waitSampling()
    dut.io.wr #= false
  }

  /** Read a value from the DUT's I/O register interface */
  def ioRead(dut: BmbVgaDma, addr: Int)(implicit cd: ClockDomain): Long = {
    dut.io.addr #= addr
    dut.io.rd #= true
    dut.io.wr #= false
    cd.waitSampling()
    val result = dut.io.rdData.toLong
    dut.io.rd #= false
    result
  }

  /** Initialize DUT I/O signals to idle state */
  def initIo(dut: BmbVgaDma): Unit = {
    dut.io.addr #= 0
    dut.io.rd #= false
    dut.io.wr #= false
    dut.io.wrData #= 0
    dut.io.bmb.cmd.ready #= false
    dut.io.bmb.rsp.valid #= false
    dut.io.bmb.rsp.last #= false
    dut.io.bmb.rsp.data #= 0
    dut.io.bmb.rsp.source #= 0
    dut.io.bmb.rsp.context #= 0
    dut.io.bmb.rsp.opcode #= 0
  }

  /**
   * Fork a BMB slave responder that accepts commands and responds with
   * a configurable data pattern. Each burst command gets burstWords
   * response beats.
   */
  def forkBmbResponder(
    dut: BmbVgaDma,
    dataPattern: Long = 0x001F001FL
  )(implicit cd: ClockDomain): Unit = {
    fork {
      dut.io.bmb.cmd.ready #= false
      dut.io.bmb.rsp.valid #= false

      while (true) {
        // Wait for a valid command
        dut.io.bmb.cmd.ready #= true
        cd.waitSamplingWhere(dut.io.bmb.cmd.valid.toBoolean)

        // Capture burst length
        val lengthVal = dut.io.bmb.cmd.length.toInt
        val burstWords = (lengthVal + 1) / 4
        dut.io.bmb.cmd.ready #= false

        // Respond with data beats
        for (i <- 0 until burstWords) {
          dut.io.bmb.rsp.valid #= true
          dut.io.bmb.rsp.data #= dataPattern
          dut.io.bmb.rsp.last #= (i == burstWords - 1)
          dut.io.bmb.rsp.source #= 0
          dut.io.bmb.rsp.context #= 0
          dut.io.bmb.rsp.opcode #= 0 // SUCCESS
          cd.waitSamplingWhere(dut.io.bmb.rsp.ready.toBoolean)
        }
        dut.io.bmb.rsp.valid #= false
      }
    }
  }

  // ========================================================================
  // Test: Control Registers
  // ========================================================================

  test("BmbVgaDma_control_registers") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.vgaCd.forkStimulus(40)
      SimTimeout(100000)

      initIo(dut)
      cd.waitSampling(20)

      // Initial state: disabled, no vsync pending, no underrun
      val status0 = ioRead(dut, 0)
      assert((status0 & 0x01) == 0, s"Enabled should be 0 initially, got ${status0 & 0x01}")
      assert((status0 & 0x02) == 0, s"VsyncPending should be 0 initially, got ${(status0 >> 1) & 1}")

      // Write base address to addr 1
      ioWrite(dut, 1, 0x100000L)
      cd.waitSampling(5)
      val baseAddr = ioRead(dut, 1)
      assert(baseAddr == 0x100000L,
        f"Base address should be 0x100000, got 0x$baseAddr%X")

      // Write framebuffer size to addr 2
      ioWrite(dut, 2, 614400L)
      cd.waitSampling(5)
      val fbSize = ioRead(dut, 2)
      assert(fbSize == 614400L,
        s"Framebuffer size should be 614400, got $fbSize")

      // Enable VGA via addr 0
      ioWrite(dut, 0, 0x01)
      cd.waitSampling(5)
      val status1 = ioRead(dut, 0)
      assert((status1 & 0x01) == 1, s"Enabled should be 1 after enable, got ${status1 & 0x01}")

      // Disable VGA
      ioWrite(dut, 0, 0x00)
      cd.waitSampling(5)
      val status2 = ioRead(dut, 0)
      assert((status2 & 0x01) == 0, s"Enabled should be 0 after disable, got ${status2 & 0x01}")
    }
  }

  // ========================================================================
  // Test: DMA Read Requests
  // ========================================================================

  test("BmbVgaDma_dma_read_requests") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.vgaCd.forkStimulus(40)
      SimTimeout(200000)

      initIo(dut)
      cd.waitSampling(20)

      // Set base address and framebuffer size
      ioWrite(dut, 1, 0x1000L)
      ioWrite(dut, 2, 4096L) // Small framebuffer for testing

      // Track addresses of issued commands
      var cmdAddresses = List.empty[Long]
      val targetCmdCount = 8

      // Fork a BMB responder that also records addresses
      fork {
        dut.io.bmb.cmd.ready #= false
        dut.io.bmb.rsp.valid #= false

        while (cmdAddresses.size < targetCmdCount) {
          dut.io.bmb.cmd.ready #= true
          cd.waitSamplingWhere(dut.io.bmb.cmd.valid.toBoolean)

          val addr = dut.io.bmb.cmd.address.toLong
          val lengthVal = dut.io.bmb.cmd.length.toInt
          val burstWords = (lengthVal + 1) / 4
          cmdAddresses = cmdAddresses :+ addr
          dut.io.bmb.cmd.ready #= false

          // Respond
          for (i <- 0 until burstWords) {
            dut.io.bmb.rsp.valid #= true
            dut.io.bmb.rsp.data #= 0xDEADBEEFL
            dut.io.bmb.rsp.last #= (i == burstWords - 1)
            dut.io.bmb.rsp.source #= 0
            dut.io.bmb.rsp.context #= 0
            dut.io.bmb.rsp.opcode #= 0
            cd.waitSamplingWhere(dut.io.bmb.rsp.ready.toBoolean)
          }
          dut.io.bmb.rsp.valid #= false
        }
      }

      // Enable DMA
      ioWrite(dut, 0, 0x01)

      // Wait for commands to be issued
      var timeout = 15000
      while (cmdAddresses.size < targetCmdCount && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
      }
      assert(cmdAddresses.size >= targetCmdCount,
        s"Expected at least $targetCmdCount commands, got ${cmdAddresses.size}")

      // Verify addresses: should start at 0x1000 and increment by burstBytes
      val burstBytes = 1 << testBmbParam.access.lengthWidth
      for (i <- cmdAddresses.indices) {
        val expected = 0x1000L + (i.toLong * burstBytes) % 4096L
        assert(cmdAddresses(i) == expected,
          f"Command $i: expected address 0x$expected%X, got 0x${cmdAddresses(i)}%X")
      }
    }
  }

  // ========================================================================
  // Test: VGA Timing
  // ========================================================================

  test("BmbVgaDma_vga_timing") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)    // 100 MHz system clock
      dut.vgaCd.forkStimulus(40)  // 25 MHz pixel clock
      SimTimeout(500000)

      initIo(dut)

      // Start BMB responder to keep FIFO fed
      forkBmbResponder(dut, 0x00000000L)

      // Enable VGA
      cd.waitSampling(20)
      ioWrite(dut, 0, 0x01)

      // Measure hsync period in pixel clock cycles.
      // hsync is active-low: low during sync pulse, high otherwise.
      // Count pixel clocks between two consecutive falling edges of hsync.

      // Synchronize: wait for hsync high (inactive), then falling edge
      dut.vgaCd.waitSamplingWhere(dut.io.vgaHsync.toBoolean)
      dut.vgaCd.waitSamplingWhere(!dut.io.vgaHsync.toBoolean)

      // Now count until next falling edge
      var pixelCount = 0
      // First wait for hsync to go high (end of current pulse)
      while (!dut.io.vgaHsync.toBoolean) {
        dut.vgaCd.waitSampling()
        pixelCount += 1
      }
      // Then wait for it to go low again (start of next pulse)
      while (dut.io.vgaHsync.toBoolean) {
        dut.vgaCd.waitSampling()
        pixelCount += 1
      }

      assert(pixelCount == 800,
        s"Horizontal line period should be 800 pixel clocks, got $pixelCount")

      // Measure hsync pulse width: should be hSyncEnd - hSyncStart = 96 pixel clocks
      var syncWidth = 0
      while (!dut.io.vgaHsync.toBoolean) {
        dut.vgaCd.waitSampling()
        syncWidth += 1
      }
      assert(syncWidth == 96,
        s"Hsync pulse width should be 96 pixel clocks, got $syncWidth")
    }
  }

  // ========================================================================
  // Test: Pixel Output
  // ========================================================================

  test("BmbVgaDma_pixel_output") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.vgaCd.forkStimulus(40)
      SimTimeout(20000000)

      initIo(dut)

      // BMB responder returns 0x001F001F = two blue pixels (R=0, G=0, B=0x1F)
      forkBmbResponder(dut, 0x001F001FL)

      cd.waitSampling(20)
      ioWrite(dut, 0, 0x01) // Enable

      // Wait for active display area.
      // Navigate to a point where both hCounter < 640 and vCounter < 480.
      // Wait for vsync to go low (active) then high again to synchronize to
      // the start of the visible frame.
      dut.vgaCd.waitSamplingWhere(!dut.io.vgaVsync.toBoolean)
      dut.vgaCd.waitSamplingWhere(dut.io.vgaVsync.toBoolean)

      // Now we are at the end of vsync. Wait for a few lines until we enter
      // the active area. vSyncEnd=492, vTotal=525, then vCounter resets to 0.
      // From vsync end (line 492) we need to reach line 525 (overflow) + line 0.
      // That is 525-492 = 33 lines of vblank, then line 0 starts.
      // Each line is 800 pixel clocks. Wait through remaining vblank.
      // We need approximately (525-492)*800 = 26400 pixel clocks to reach line 0.
      // Then allow some margin for the FIFO to fill.

      // Wait for enough time to be in active area and FIFO to be filled
      for (_ <- 0 until 28000) dut.vgaCd.waitSampling()

      // Now sample during active display. Look for a pixel clock where
      // we see the expected blue pixel data.
      var bluePixelSeen = false
      var sampleCount = 0
      val maxSamples = 2000

      while (!bluePixelSeen && sampleCount < maxSamples) {
        dut.vgaCd.waitSampling()
        sampleCount += 1
        val r = dut.io.vgaR.toInt
        val g = dut.io.vgaG.toInt
        val b = dut.io.vgaB.toInt
        if (b == 0x1F && r == 0 && g == 0) {
          bluePixelSeen = true
        }
      }

      assert(bluePixelSeen,
        s"Expected to see blue pixels (R=0,G=0,B=0x1F) during active display within $maxSamples pixel clocks")
    }
  }
}
