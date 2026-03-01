package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.collection.mutable

class BmbSdNativeTest extends AnyFunSuite {

  // ==========================================================================
  // CRC helpers (matching hardware polynomial implementations)
  // ==========================================================================

  /** CRC7: polynomial x^7 + x^3 + 1 (0x09) */
  def crc7Update(crc: Int, bit: Int): Int = {
    val inv = ((crc >> 6) & 1) ^ (bit & 1)
    var next = 0
    next |= (inv & 1)
    next |= ((crc >> 0) & 1) << 1
    next |= ((crc >> 1) & 1) << 2
    next |= (((crc >> 2) & 1) ^ inv) << 3
    next |= ((crc >> 3) & 1) << 4
    next |= ((crc >> 4) & 1) << 5
    next |= ((crc >> 5) & 1) << 6
    next & 0x7F
  }

  def computeCrc7(bits: Seq[Int]): Int = {
    var crc = 0
    for (b <- bits) crc = crc7Update(crc, b)
    crc
  }

  /** CRC16-CCITT: polynomial x^16 + x^12 + x^5 + 1 (0x1021) */
  def crc16Update(crc: Int, bit: Int): Int = {
    val inv = ((crc >> 15) & 1) ^ (bit & 1)
    var next = 0
    next |= inv
    next |= ((crc >> 0) & 1) << 1
    next |= ((crc >> 1) & 1) << 2
    next |= ((crc >> 2) & 1) << 3
    next |= ((crc >> 3) & 1) << 4
    next |= (((crc >> 4) & 1) ^ inv) << 5
    next |= ((crc >> 5) & 1) << 6
    next |= ((crc >> 6) & 1) << 7
    next |= ((crc >> 7) & 1) << 8
    next |= ((crc >> 8) & 1) << 9
    next |= ((crc >> 9) & 1) << 10
    next |= ((crc >> 10) & 1) << 11
    next |= (((crc >> 11) & 1) ^ inv) << 12
    next |= ((crc >> 12) & 1) << 13
    next |= ((crc >> 13) & 1) << 14
    next |= ((crc >> 14) & 1) << 15
    next & 0xFFFF
  }

  def computeCrc16(bits: Seq[Int]): Int = {
    var crc = 0
    for (b <- bits) crc = crc16Update(crc, b)
    crc
  }

  // ==========================================================================
  // I/O helpers
  // ==========================================================================

  def ioWrite(dut: BmbSdNative, addr: Int, data: Long)(implicit cd: ClockDomain): Unit = {
    dut.io.addr #= addr
    dut.io.wrData #= data
    dut.io.wr #= true
    dut.io.rd #= false
    cd.waitSampling()
    dut.io.wr #= false
  }

  def ioRead(dut: BmbSdNative, addr: Int)(implicit cd: ClockDomain): Long = {
    dut.io.addr #= addr
    dut.io.rd #= true
    dut.io.wr #= false
    cd.waitSampling()
    val result = dut.io.rdData.toLong
    dut.io.rd #= false
    result
  }

  /** Read from FIFO addr 5 with an extra settling cycle for readSync pipeline */
  def fifoRead(dut: BmbSdNative)(implicit cd: ClockDomain): Long = {
    // Present addr 5 for a cycle so readSync latches the address
    dut.io.addr #= 5
    dut.io.rd #= false
    dut.io.wr #= false
    cd.waitSampling()
    // Now do the actual read — fifoRdData has the correct value
    dut.io.rd #= true
    cd.waitSampling()
    val result = dut.io.rdData.toLong
    dut.io.rd #= false
    // Wait one cycle for the pop to take effect and readSync to latch next addr
    cd.waitSampling()
    result
  }

  def initIo(dut: BmbSdNative): Unit = {
    dut.io.addr #= 0
    dut.io.rd #= false
    dut.io.wr #= false
    dut.io.wrData #= 0
    dut.io.sdCmd.read #= true  // idle high
    dut.io.sdDat.read #= 0xF  // idle high
    dut.io.sdCd #= true        // no card (active low)
  }

  val simConfig = SimConfig
    .workspacePath("simWorkspace")

  def compileDut() = simConfig.compile(BmbSdNative(clkDivInit = 99))

  // ==========================================================================
  // Test 1: Clock divider
  // ==========================================================================

  test("BmbSdNative_clock_divider") {
    simConfig.compile(BmbSdNative(clkDivInit = 1)).doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      SimTimeout(100000)

      initIo(dut)
      cd.waitSampling(5)

      // With divider=1: SD_CLK toggles every (1+1)=2 system clocks
      // Full SD_CLK period = 4 system clocks

      // Find a rising edge
      var lastSdClk = dut.io.sdClk.toBoolean
      var timeout = 100
      while (timeout > 0) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (!lastSdClk && cur) timeout = 0
        else { lastSdClk = cur; timeout -= 1 }
      }

      // Count system clocks until next rising edge
      lastSdClk = true
      var cycleCount = 0
      var foundRise = false
      timeout = 100
      while (!foundRise && timeout > 0) {
        cd.waitSampling()
        cycleCount += 1
        val cur = dut.io.sdClk.toBoolean
        if (!lastSdClk && cur) foundRise = true
        lastSdClk = cur
        timeout -= 1
      }

      assert(foundRise, "Never saw a second rising edge of sdClk")
      assert(cycleCount == 4, s"Expected SD_CLK period of 4 system clocks with divider=1, got $cycleCount")

      // Change divider to 2 via register write and verify
      ioWrite(dut, 6, 2)
      cd.waitSampling(5)

      // Find a rising edge
      lastSdClk = dut.io.sdClk.toBoolean
      timeout = 100
      foundRise = false
      while (!foundRise && timeout > 0) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (!lastSdClk && cur) foundRise = true
        lastSdClk = cur
        timeout -= 1
      }

      // Count until next rising edge
      lastSdClk = true
      cycleCount = 0
      foundRise = false
      timeout = 100
      while (!foundRise && timeout > 0) {
        cd.waitSampling()
        cycleCount += 1
        val cur = dut.io.sdClk.toBoolean
        if (!lastSdClk && cur) foundRise = true
        lastSdClk = cur
        timeout -= 1
      }

      assert(foundRise, "Never saw a second rising edge after divider change")
      assert(cycleCount == 6, s"Expected SD_CLK period of 6 system clocks with divider=2, got $cycleCount")
    }
  }

  // ==========================================================================
  // Test 2: CMD send and response
  // ==========================================================================

  test("BmbSdNative_cmd_send_and_response") {
    simConfig.compile(BmbSdNative(clkDivInit = 1)).doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      SimTimeout(500000)

      initIo(dut)
      cd.waitSampling(10)

      // CMD8 (SEND_IF_COND): index=8, argument=0x000001AA
      val cmdIdx = 8
      val cmdArg = 0x000001AAL

      // Build expected 48-bit command frame:
      // [47]=0(start), [46]=1(host), [45:40]=index, [39:8]=arg, [7:1]=CRC7, [0]=1(stop)
      val contentBits = mutable.ArrayBuffer[Int]()
      contentBits += 1 // direction: host->card
      for (i <- 5 to 0 by -1) contentBits += ((cmdIdx >> i) & 1)
      for (i <- 31 to 0 by -1) contentBits += ((cmdArg.toInt >> i) & 1)
      assert(contentBits.size == 39)
      val expectedCrc7 = computeCrc7(contentBits.toSeq)

      val expectedBits = mutable.ArrayBuffer[Int]()
      expectedBits += 0 // start bit
      expectedBits ++= contentBits
      for (i <- 6 to 0 by -1) expectedBits += ((expectedCrc7 >> i) & 1)
      expectedBits += 1 // stop bit
      assert(expectedBits.size == 48)

      // Set up CMD
      ioWrite(dut, 1, cmdArg)            // argument
      ioWrite(dut, 2, cmdIdx | (1 << 6)) // index + expectResponse
      ioWrite(dut, 0, 1)                 // sendCmd

      // Capture CMD bits on rising edge of sdClk (when writeEnable is active)
      val capturedBits = mutable.ArrayBuffer[Int]()
      var lastSdClk = dut.io.sdClk.toBoolean
      var bitsCapd = 0

      while (bitsCapd < 48) {
        cd.waitSampling()
        val curSdClk = dut.io.sdClk.toBoolean
        if (!lastSdClk && curSdClk) {
          // Rising edge — capture if host is driving
          if (dut.io.sdCmd.writeEnable.toBoolean) {
            val bit = if (dut.io.sdCmd.write.toBoolean) 1 else 0
            capturedBits += bit
            bitsCapd += 1
          }
        }
        lastSdClk = curSdClk
      }

      // Verify captured command bits
      for (i <- capturedBits.indices) {
        assert(capturedBits(i) == expectedBits(i),
          s"CMD bit $i: expected ${expectedBits(i)}, got ${capturedBits(i)}")
      }

      // Build card response for CMD8 (R7 format):
      // start(0), dir(0), index(8), echo(0x000001AA), CRC7, stop(1)
      val rspContentBits = mutable.ArrayBuffer[Int]()
      rspContentBits += 0 // direction: card->host
      for (i <- 5 to 0 by -1) rspContentBits += ((cmdIdx >> i) & 1)
      for (i <- 31 to 0 by -1) rspContentBits += ((cmdArg.toInt >> i) & 1)
      val rspCrc7 = computeCrc7(rspContentBits.toSeq)

      val responseBits = mutable.ArrayBuffer[Int]()
      responseBits += 0 // start
      responseBits ++= rspContentBits
      for (i <- 6 to 0 by -1) responseBits += ((rspCrc7 >> i) & 1)
      responseBits += 1 // stop
      assert(responseBits.size == 48)

      // Wait a couple SD clocks for bus turnaround
      lastSdClk = dut.io.sdClk.toBoolean
      var turnaroundClocks = 0
      while (turnaroundClocks < 2) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) turnaroundClocks += 1
        lastSdClk = cur
      }

      // Drive response bits: set data on falling edge so it's stable at rising edge
      var rspBitIdx = 0
      while (rspBitIdx < 48) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) {
          dut.io.sdCmd.read #= (responseBits(rspBitIdx) == 1)
          rspBitIdx += 1
        }
        lastSdClk = cur
      }

      // Release CMD line after a couple more clocks
      turnaroundClocks = 0
      while (turnaroundClocks < 2) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) turnaroundClocks += 1
        lastSdClk = cur
      }
      dut.io.sdCmd.read #= true

      // Wait for cmdBusy=0
      var timeout = 5000
      var busy = true
      while (busy && timeout > 0) {
        val status = ioRead(dut, 0)
        busy = (status & 1) != 0
        timeout -= 1
      }
      assert(!busy, "cmdBusy never cleared")

      // Check status flags
      val finalStatus = ioRead(dut, 0)
      assert((finalStatus & 0x02) != 0, s"cmdResponseValid should be set, status=0x${finalStatus.toHexString}")
      assert((finalStatus & 0x04) == 0, s"cmdCrcError should be clear, status=0x${finalStatus.toHexString}")
      assert((finalStatus & 0x08) == 0, s"cmdTimeout should be clear, status=0x${finalStatus.toHexString}")

      // Read response: cmdResponse(0) = shiftReg[39:8] = 32-bit argument field
      val rsp0 = ioRead(dut, 1)
      assert(rsp0 == cmdArg,
        f"Response[0] expected 0x${cmdArg}%08X, got 0x${rsp0}%08X")
    }
  }

  // ==========================================================================
  // Test 3: Data read (1-bit mode)
  // ==========================================================================

  test("BmbSdNative_data_read_1bit") {
    simConfig.compile(BmbSdNative(clkDivInit = 1)).doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      SimTimeout(2000000)

      initIo(dut)
      cd.waitSampling(10)

      // Set 1-bit mode
      ioWrite(dut, 8, 0)
      // Set block length to 16 bytes
      ioWrite(dut, 9, 16)

      // Test data: 16 bytes
      val testData = (0 until 16).map(i => (i * 0x11 + 0xA5) & 0xFF)

      // Compute CRC16 for the serial bit stream (MSB-first per byte)
      val dataBitsSeq = testData.flatMap { byte =>
        (7 to 0 by -1).map(i => (byte >> i) & 1)
      }
      val crc16 = computeCrc16(dataBitsSeq)

      // Build drive sequence: start(0), data bits, CRC16 (MSB-first), stop(1)
      val driveBits = mutable.ArrayBuffer[Int]()
      driveBits += 0
      driveBits ++= dataBitsSeq
      for (i <- 15 to 0 by -1) driveBits += ((crc16 >> i) & 1)
      driveBits += 1

      // Start data read
      ioWrite(dut, 7, 1)

      // Wait a few SD clocks before start bit (card latency)
      var lastSdClk = dut.io.sdClk.toBoolean
      var waitClocks = 0
      while (waitClocks < 4) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) waitClocks += 1
        lastSdClk = cur
      }

      // Drive data bits on falling edge (stable for rising edge sample)
      var bitIdx = 0
      while (bitIdx < driveBits.size) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) {
          // DAT0 = bit value, DAT[3:1] = 1 (idle)
          val datVal = if (driveBits(bitIdx) == 1) 0xF else 0xE
          dut.io.sdDat.read #= datVal
          bitIdx += 1
        }
        lastSdClk = cur
      }

      // Release DAT lines
      var releaseClocks = 0
      while (releaseClocks < 2) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) releaseClocks += 1
        lastSdClk = cur
      }
      dut.io.sdDat.read #= 0xF

      // Wait for dataBusy=0
      var timeout = 10000
      var busy = true
      while (busy && timeout > 0) {
        val status = ioRead(dut, 0)
        busy = (status & 0x10) != 0
        timeout -= 1
      }
      assert(!busy, "dataBusy never cleared")

      // Check no CRC error
      val status = ioRead(dut, 0)
      assert((status & 0x20) == 0, s"dataCrcError should be clear, status=0x${status.toHexString}")

      // Check FIFO occupancy: 16 bytes = 4 words
      val occ = ioRead(dut, 6)
      assert(occ == 4, s"Expected FIFO occupancy 4, got $occ")

      // Expected words: data packed MSB-first (big-endian byte order in each word)
      val expectedWords = (0 until 4).map { w =>
        var word = 0L
        for (b <- 0 until 4) {
          word = (word << 8) | (testData(w * 4 + b) & 0xFF)
        }
        word
      }

      // Read words from FIFO. readSync has 1-cycle latency, so we need to
      // present the address first, then read on the next cycle.
      for (i <- expectedWords.indices) {
        val word = fifoRead(dut)
        assert(word == expectedWords(i),
          f"FIFO word $i: expected 0x${expectedWords(i)}%08X, got 0x${word}%08X")
      }
    }
  }

  // ==========================================================================
  // Test 4: Data read (4-bit mode)
  // ==========================================================================

  test("BmbSdNative_data_read_4bit") {
    simConfig.compile(BmbSdNative(clkDivInit = 1)).doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      SimTimeout(2000000)

      initIo(dut)
      cd.waitSampling(10)

      // Set 4-bit mode and block length = 16 bytes
      ioWrite(dut, 8, 1)
      ioWrite(dut, 9, 16)

      // Test data: 16 bytes
      val testData = (0 until 16).map(i => (i * 0x11 + 0xA5) & 0xFF)

      // Build nibble sequence (high nibble first per byte, 32 nibbles total)
      val nibbles = testData.flatMap { byte =>
        Seq((byte >> 4) & 0xF, byte & 0xF)
      }

      // Compute per-line CRC16: for line i, the bit stream is bit i of each nibble
      val lineCrc = (0 until 4).map { line =>
        val bits = nibbles.map(n => (n >> line) & 1)
        computeCrc16(bits)
      }

      // Start data read
      ioWrite(dut, 7, 1)

      // Wait a few SD clocks before driving start nibble
      var lastSdClk = dut.io.sdClk.toBoolean
      var waitClocks = 0
      while (waitClocks < 4) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) waitClocks += 1
        lastSdClk = cur
      }

      // Drive start nibble (0000) on falling edge
      var driven = false
      while (!driven) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) {
          dut.io.sdDat.read #= 0x0
          driven = true
        }
        lastSdClk = cur
      }

      // Drive 32 data nibbles on falling edges
      var nibIdx = 0
      while (nibIdx < nibbles.size) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) {
          dut.io.sdDat.read #= nibbles(nibIdx)
          nibIdx += 1
        }
        lastSdClk = cur
      }

      // Drive 16 CRC clocks: DAT[i] = CRC bit for line i
      var crcIdx = 0
      while (crcIdx < 16) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) {
          var nibVal = 0
          for (line <- 0 until 4) {
            nibVal |= (((lineCrc(line) >> (15 - crcIdx)) & 1) << line)
          }
          dut.io.sdDat.read #= nibVal
          crcIdx += 1
        }
        lastSdClk = cur
      }

      // Drive stop nibble (1111)
      driven = false
      while (!driven) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) {
          dut.io.sdDat.read #= 0xF
          driven = true
        }
        lastSdClk = cur
      }

      // Release DAT lines
      var releaseClocks = 0
      while (releaseClocks < 2) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) releaseClocks += 1
        lastSdClk = cur
      }
      dut.io.sdDat.read #= 0xF

      // Wait for dataBusy=0
      var timeout = 10000
      var busy = true
      while (busy && timeout > 0) {
        val status = ioRead(dut, 0)
        busy = (status & 0x10) != 0
        timeout -= 1
      }
      assert(!busy, "dataBusy never cleared")

      // Check no CRC error
      val status = ioRead(dut, 0)
      assert((status & 0x20) == 0, s"dataCrcError should be clear, status=0x${status.toHexString}")

      // Check FIFO occupancy: 16 bytes = 4 words
      val occ = ioRead(dut, 6)
      assert(occ == 4, s"Expected FIFO occupancy 4, got $occ")

      // Expected words: nibbles packed via (acc << 4) | nibble, 8 nibbles per word
      val expectedWords = (0 until 4).map { w =>
        var word = 0L
        for (b <- 0 until 4) {
          word = (word << 8) | (testData(w * 4 + b) & 0xFF)
        }
        word
      }

      for (i <- expectedWords.indices) {
        val word = fifoRead(dut)
        assert(word == expectedWords(i),
          f"FIFO word $i: expected 0x${expectedWords(i)}%08X, got 0x${word}%08X")
      }
    }
  }

  // ==========================================================================
  // Test 5: Data write (1-bit mode)
  // ==========================================================================

  test("BmbSdNative_data_write_1bit") {
    simConfig.compile(BmbSdNative(clkDivInit = 1)).doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      SimTimeout(2000000)

      initIo(dut)
      cd.waitSampling(10)

      // Set 1-bit mode, block length = 16 bytes
      ioWrite(dut, 8, 0)
      ioWrite(dut, 9, 16)

      // Test data: 16 bytes -> 4 words
      val testData = (0 until 16).map(i => (i * 0x11 + 0xA5) & 0xFF)
      val testWords = (0 until 4).map { w =>
        var word = 0L
        for (b <- 0 until 4) {
          word = (word << 8) | (testData(w * 4 + b) & 0xFF)
        }
        word
      }

      // Push 4 words into FIFO via addr 5
      for (w <- testWords) {
        ioWrite(dut, 5, w)
      }

      // Expected serial bit stream (MSB-first per byte)
      val expectedBits = testData.flatMap { byte =>
        (7 to 0 by -1).map(i => (byte >> i) & 1)
      }

      // Compute CRC16 for the serial bit stream
      val expectedCrc16 = computeCrc16(expectedBits)

      // Start write (bit 1 of addr 7)
      ioWrite(dut, 7, 2)

      // Capture bits from sdDat.write(0) on falling edges of sdClk
      // when writeEnable(0) is set.
      // Expected sequence: start(0), 128 data bits, 16 CRC bits, stop(1)
      val totalOutputBits = 1 + 128 + 16 + 1 // = 146
      val capturedBits = mutable.ArrayBuffer[Int]()
      var lastSdClk = dut.io.sdClk.toBoolean

      while (capturedBits.size < totalOutputBits) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) {
          // Falling edge — check if host is driving DAT0
          if ((dut.io.sdDat.writeEnable.toLong & 1) != 0) {
            val bit = (dut.io.sdDat.write.toLong & 1).toInt
            capturedBits += bit
          }
        }
        lastSdClk = cur
      }

      // Verify start bit
      assert(capturedBits(0) == 0, s"Start bit should be 0, got ${capturedBits(0)}")

      // Verify 128 data bits
      for (i <- expectedBits.indices) {
        assert(capturedBits(1 + i) == expectedBits(i),
          s"Data bit $i: expected ${expectedBits(i)}, got ${capturedBits(1 + i)}")
      }

      // Verify CRC16 bits
      for (i <- 0 until 16) {
        val expected = (expectedCrc16 >> (15 - i)) & 1
        assert(capturedBits(129 + i) == expected,
          s"CRC bit $i: expected $expected, got ${capturedBits(129 + i)}")
      }

      // Verify stop bit
      assert(capturedBits(145) == 1, s"Stop bit should be 1, got ${capturedBits(145)}")

      // Drive CRC status response "010" (positive ACK) on DAT0.
      // HW samples DAT0 on rising edges in WAIT_CRC_STATUS state:
      //   cnt=0: detects start bit (DAT0=0), advances to cnt=1
      //   cnt=1,2,3: shifts DAT0 into datCrcStatus (3 status bits)
      // After the capture loop (which ends on a falling edge), the HW
      // has just entered WAIT_CRC_STATUS.  Set DAT0=0 immediately so
      // it is stable before the next rising edge (start bit).
      dut.io.sdDat.read #= 0xE  // DAT0=0, DAT[3:1]=1  (start bit)

      // Drive the 3 status bits on successive falling edges so they
      // are stable when sampled on the following rising edges.
      // Status "010": bit2=0, bit1=1, bit0=0.
      val crcStatusBits = Seq(0, 1, 0)
      var statusIdx = 0
      lastSdClk = dut.io.sdClk.toBoolean
      while (statusIdx < crcStatusBits.size) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) {
          dut.io.sdDat.read #= (if (crcStatusBits(statusIdx) == 1) 0xF else 0xE)
          statusIdx += 1
        }
        lastSdClk = cur
      }

      // After CRC status, HW transitions to WAIT_BUSY (checks DAT0 on rise).
      // Drive DAT0 low (busy) for a few clocks, then release high.
      var busyClocks = 0
      while (busyClocks < 4) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) {
          dut.io.sdDat.read #= 0xE // DAT0=0 (busy)
          busyClocks += 1
        }
        lastSdClk = cur
      }

      // Release DAT0 (not busy)
      var released = false
      while (!released) {
        cd.waitSampling()
        val cur = dut.io.sdClk.toBoolean
        if (lastSdClk && !cur) {
          dut.io.sdDat.read #= 0xF
          released = true
        }
        lastSdClk = cur
      }

      // Wait for dataBusy=0
      var timeout = 10000
      var busy = true
      while (busy && timeout > 0) {
        val status = ioRead(dut, 0)
        busy = (status & 0x10) != 0
        timeout -= 1
      }
      assert(!busy, "dataBusy never cleared")

      // Check no CRC error
      val finalStatus = ioRead(dut, 0)
      assert((finalStatus & 0x20) == 0, s"dataCrcError should be clear, status=0x${finalStatus.toHexString}")
    }
  }

  // ==========================================================================
  // Test 6: Card detect
  // ==========================================================================

  test("BmbSdNative_card_detect") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      SimTimeout(100000)

      initIo(dut)
      cd.waitSampling(10)

      // sdCd active low: high = no card
      dut.io.sdCd #= true
      cd.waitSampling(10)

      val status0 = ioRead(dut, 0)
      assert((status0 & 0x80) == 0, s"cardPresent should be 0 when sdCd=high, status=0x${status0.toHexString}")

      val reg8_0 = ioRead(dut, 8)
      assert((reg8_0 & 0x01) == 0, s"addr8.cardPresent should be 0 when sdCd=high")

      // Insert card
      dut.io.sdCd #= false
      cd.waitSampling(10)

      val status1 = ioRead(dut, 0)
      assert((status1 & 0x80) != 0, s"cardPresent should be 1 when sdCd=low, status=0x${status1.toHexString}")

      val reg8_1 = ioRead(dut, 8)
      assert((reg8_1 & 0x01) != 0, s"addr8.cardPresent should be 1 when sdCd=low")

      // Remove card
      dut.io.sdCd #= true
      cd.waitSampling(10)

      val status2 = ioRead(dut, 0)
      assert((status2 & 0x80) == 0, s"cardPresent should be 0 after removal, status=0x${status2.toHexString}")
    }
  }
}
