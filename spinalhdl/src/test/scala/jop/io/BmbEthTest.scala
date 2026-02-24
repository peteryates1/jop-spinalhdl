package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}

import scala.collection.mutable

class BmbEthTest extends AnyFunSuite {

  /** CRC-32 (IEEE 802.3) — same algorithm as SpinalSimMacTester.calcCrc32 */
  def calcCrc32(data: Seq[Int]): Int = {
    def getBit(id: Int) = (data(id / 8) >> (id % 8)) & 1
    var crc = -1
    for (bitId <- 0 until data.size * 8) {
      val bit = getBit(bitId) ^ ((crc >> 31) & 1)
      crc = (crc << 1) ^ (if (bit == 1) 0x04C11DB7 else 0)
    }
    val crcReversed = (0 until 32).map(i => ((crc >> i) & 1) << (31 - i)).reduce(_ | _)
    ~crcReversed
  }

  /** Build a complete PHY frame: 0x55, 0x55, 0xD5 preamble + payload (padded to 60) + CRC */
  def buildPhyFrame(payload: Seq[Int]): Seq[Int] = {
    val padded = payload ++ List.fill(Math.max(60 - payload.size, 0))(0)
    val crc = calcCrc32(padded)
    val header = List(0x55, 0x55, 0xD5)
    header ++ padded ++ List.tabulate(4)(i => (crc >> i * 8) & 0xFF)
  }

  /** Write a value to the DUT's I/O register interface */
  def ioWrite(dut: BmbEth, addr: Int, data: Long)(implicit cd: ClockDomain): Unit = {
    dut.io.addr #= addr
    dut.io.wrData #= data
    dut.io.wr #= true
    dut.io.rd #= false
    cd.waitSampling()
    dut.io.wr #= false
  }

  /** Read a value from the DUT's I/O register interface */
  def ioRead(dut: BmbEth, addr: Int)(implicit cd: ClockDomain): Long = {
    dut.io.addr #= addr
    dut.io.rd #= true
    dut.io.wr #= false
    cd.waitSampling()
    val result = dut.io.rdData.toLong
    dut.io.rd #= false
    result
  }

  /** Initialize DUT I/O signals to idle state */
  def initIo(dut: BmbEth): Unit = {
    dut.io.addr #= 0
    dut.io.rd #= false
    dut.io.wr #= false
    dut.io.wrData #= 0
    dut.io.phy.colision #= false
    dut.io.phy.busy #= false
  }

  /** Enqueue a frame of bytes as low-nibble-first 4-bit PHY RX transfers */
  def enqueuePhyRx(
    dut: BmbEth,
    phyRxQueue: mutable.Queue[() => Unit],
    frame: Seq[Int]
  ): Unit = {
    val transferCount = frame.size * 2
    for (transferId <- 0 until transferCount) {
      val byteIdx = transferId / 2
      val nibble = if (transferId % 2 == 0) frame(byteIdx) & 0xF
                   else (frame(byteIdx) >> 4) & 0xF
      val isLast = transferId == transferCount - 1
      phyRxQueue += { () =>
        dut.io.phy.rx.data #= nibble
        dut.io.phy.rx.error #= false
        dut.io.phy.rx.last #= isLast
      }
    }
  }

  val simConfig = SimConfig
    .workspacePath("simWorkspace")

  def compileDut() = simConfig.compile(
    BmbEth(
      txCd = ClockDomain.external("txCd", withReset = false),
      rxCd = ClockDomain.external("rxCd", withReset = false),
      rxBufferByteSize = 512,
      txBufferByteSize = 512
    )
  )

  test("BmbEth_status_and_flush") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.txCd.forkStimulus(40)
      dut.rxCd.forkStimulus(40)
      SimTimeout(100000)

      initIo(dut)
      cd.waitSampling(20)

      // Initial state: both flushes should be active (bit 0=TX flush, bit 4=RX flush)
      val status0 = ioRead(dut, 0)
      assert((status0 & 0x01) == 1, s"TX flush should be 1 initially, got ${status0 & 0x01}")
      assert((status0 & 0x10) == 0x10, s"RX flush should be 1 initially, got ${(status0 >> 4) & 1}")

      // Clear both flushes
      ioWrite(dut, 0, 0x00)
      cd.waitSampling(5)

      val status1 = ioRead(dut, 0)
      assert((status1 & 0x01) == 0, s"TX flush should be 0 after clear, got ${status1 & 0x01}")
      assert((status1 & 0x10) == 0, s"RX flush should be 0 after clear, got ${(status1 >> 4) & 1}")

      // Re-enable flushes
      ioWrite(dut, 0, 0x11)
      cd.waitSampling(5)

      val status2 = ioRead(dut, 0)
      assert((status2 & 0x01) == 1, s"TX flush should be 1 after re-set, got ${status2 & 0x01}")
      assert((status2 & 0x10) == 0x10, s"RX flush should be 1 after re-set, got ${(status2 >> 4) & 1}")
    }
  }

  test("BmbEth_rx_path") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.txCd.forkStimulus(40)
      dut.rxCd.forkStimulus(40)
      SimTimeout(1000000)

      initIo(dut)
      cd.waitSampling(20)

      // Clear both flushes
      ioWrite(dut, 0, 0x00)
      cd.waitSampling(100)

      // Payload: 16 bytes (0x00 to 0x0F)
      val payload = (0 to 15).toList
      val phyFrame = buildPhyFrame(payload)

      // Drive frame into PHY RX using StreamDriver with a queue of lambdas
      val phyRxQueue = mutable.Queue[() => Unit]()
      StreamDriver(dut.io.phy.rx, dut.rxCd) { p =>
        if (phyRxQueue.nonEmpty) {
          phyRxQueue.dequeue().apply()
          true
        } else {
          false
        }
      }

      enqueuePhyRx(dut, phyRxQueue, phyFrame)

      // Wait for frame to be received — poll status bit 5 (RX valid)
      var timeout = 5000
      var rxValid = false
      while (!rxValid && timeout > 0) {
        val status = ioRead(dut, 0)
        rxValid = (status & 0x20) != 0
        timeout -= 1
      }
      assert(rxValid, "RX valid never asserted after frame injection")

      // First read from addr 3 = bit count
      val bitCount = ioRead(dut, 3)
      // MAC includes CRC in buffered output: 60 padded + 4 CRC = 64 bytes = 512 bits
      val padded = payload ++ List.fill(60 - payload.size)(0)
      val crc = calcCrc32(padded)
      val fullFrame = padded ++ List.tabulate(4)(i => (crc >> i * 8) & 0xFF)
      val expectedBits = fullFrame.size * 8
      assert(bitCount == expectedBits, s"Expected bit count $expectedBits, got $bitCount")

      // Read data words: 64 bytes = 16 words of 32 bits (little-endian)
      val expectedWords = (0 until fullFrame.size / 4).map { i =>
        var word = 0L
        for (b <- 0 until 4) {
          word |= (fullFrame(i * 4 + b).toLong & 0xFF) << (b * 8)
        }
        word
      }

      for (i <- expectedWords.indices) {
        val word = ioRead(dut, 3)
        assert(word == expectedWords(i),
          f"RX word $i: expected 0x${expectedWords(i)}%08X, got 0x$word%08X")
      }
    }
  }

  test("BmbEth_tx_path") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.txCd.forkStimulus(40)
      dut.rxCd.forkStimulus(40)
      SimTimeout(1000000)

      initIo(dut)

      // Also need to drive phy.rx idle so StreamDriver doesn't get stuck
      StreamDriver(dut.io.phy.rx, dut.rxCd) { _ => false }

      cd.waitSampling(20)

      // Flush then clear
      ioWrite(dut, 0, 0x11)
      cd.waitSampling(50)
      ioWrite(dut, 0, 0x00)
      cd.waitSampling(100)

      // Check TX availability
      val avail = ioRead(dut, 1)
      assert(avail > 0, s"TX availability should be > 0, got $avail")

      // Payload: 16 bytes
      val payload = (0 to 15).toList

      // Build expected PHY output
      val padded = payload ++ List.fill(Math.max(60 - payload.size, 0))(0)
      val crc = calcCrc32(padded)
      val expectedBytes = List.fill(7)(0x55) :+ 0xD5
      val expectedPayloadAndCrc = padded ++ List.tabulate(4)(i => (crc >> i * 8) & 0xFF)
      val expectedNibbles = (expectedBytes ++ expectedPayloadAndCrc).flatMap { b =>
        List(b & 0xF, (b >> 4) & 0xF)
      }

      // Monitor PHY TX output
      val receivedNibbles = mutable.ArrayBuffer[Int]()
      StreamReadyRandomizer(dut.io.phy.tx, dut.txCd)
      StreamMonitor(dut.io.phy.tx, dut.txCd) { _ =>
        receivedNibbles += dut.io.phy.tx.data.toInt
      }

      // Write frame via CPU: first word = bit count, then data words
      // BmbEth does NOT use aligner, so no alignment prefix
      val bitCount = payload.size * 8
      ioWrite(dut, 2, bitCount)

      // Pack payload into 32-bit words (little-endian), padded to word boundary
      val wordPayload = payload ++ List.fill((4 - payload.size % 4) % 4)(0)
      for (i <- 0 until wordPayload.size / 4) {
        var word = 0L
        for (b <- 0 until 4) {
          word |= (wordPayload(i * 4 + b).toLong & 0xFF) << (b * 8)
        }
        ioWrite(dut, 2, word)
      }

      // Wait for TX to complete — all nibbles received
      var timeout = 5000
      while (receivedNibbles.size < expectedNibbles.size && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
      }
      // Give a bit more time for any stragglers
      cd.waitSampling(200)

      assert(receivedNibbles.size == expectedNibbles.size,
        s"Expected ${expectedNibbles.size} nibbles, got ${receivedNibbles.size}")

      for (i <- expectedNibbles.indices) {
        assert(receivedNibbles(i) == expectedNibbles(i),
          f"TX nibble $i: expected 0x${expectedNibbles(i)}%X, got 0x${receivedNibbles(i)}%X")
      }
    }
  }

  test("BmbEth_rx_stats") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.txCd.forkStimulus(40)
      dut.rxCd.forkStimulus(40)
      SimTimeout(1000000)

      initIo(dut)
      cd.waitSampling(20)

      // Clear flushes
      ioWrite(dut, 0, 0x00)
      cd.waitSampling(100)

      // Build a frame with a bad CRC (corrupt the CRC)
      val payload = (0 to 15).toList
      val padded = payload ++ List.fill(60 - payload.size)(0)
      val goodCrc = calcCrc32(padded)
      val badCrc = goodCrc ^ 0xFFFFFFFF // Invert all CRC bits
      val header = List(0x55, 0x55, 0xD5)
      val badFrame = header ++ padded ++ List.tabulate(4)(i => (badCrc >> i * 8) & 0xFF)

      // Drive the bad frame into PHY RX
      val phyRxQueue = mutable.Queue[() => Unit]()
      StreamDriver(dut.io.phy.rx, dut.rxCd) { p =>
        if (phyRxQueue.nonEmpty) {
          phyRxQueue.dequeue().apply()
          true
        } else {
          false
        }
      }

      enqueuePhyRx(dut, phyRxQueue, badFrame)

      // Wait for MAC to process the frame (go through CRC check)
      waitUntil(phyRxQueue.isEmpty)
      dut.rxCd.waitSampling(500)
      cd.waitSampling(100)

      // Read stats (addr 4): errors should be > 0
      val stats = ioRead(dut, 4)
      val errors = stats & 0xFF
      assert(errors > 0, s"Expected errors > 0 after bad CRC frame, got $errors")

      // Read again — should be auto-cleared
      cd.waitSampling(5)
      val stats2 = ioRead(dut, 4)
      val errors2 = stats2 & 0xFF
      assert(errors2 == 0, s"Expected errors == 0 after auto-clear, got $errors2")
    }
  }

  test("BmbEth_interrupts") {
    compileDut().doSim(seed = 42) { dut =>
      implicit val cd: ClockDomain = dut.clockDomain
      cd.forkStimulus(10)
      dut.txCd.forkStimulus(40)
      dut.rxCd.forkStimulus(40)
      SimTimeout(2000000)

      initIo(dut)

      // Track interrupt pulses — start monitoring from cycle 0
      var rxInterruptSeen = false
      var txInterruptSeen = false

      fork {
        while (true) {
          cd.waitSampling()
          if (dut.io.rxInterrupt.toBoolean) rxInterruptSeen = true
          if (dut.io.txInterrupt.toBoolean) txInterruptSeen = true
        }
      }

      // Accept TX PHY output so MAC internals can operate
      StreamReadyRandomizer(dut.io.phy.tx, dut.txCd)

      // Let system stabilize with flushes active, then clear
      cd.waitSampling(20)
      ioWrite(dut, 0, 0x00)

      // Wait for CDC to propagate — TX ready rising edge fires txInterrupt
      // at some point during init or after flush clear
      cd.waitSampling(2000)

      // TX interrupt should have fired when ready rose (during init or flush clear)
      assert(txInterruptSeen,
        "TX interrupt should fire on ready rising edge (during init or after flush clear)")

      // Now test RX interrupt: inject a frame and verify rxInterrupt fires
      rxInterruptSeen = false

      val payload = (0 to 15).toList
      val phyFrame = buildPhyFrame(payload)

      val phyRxQueue = mutable.Queue[() => Unit]()
      StreamDriver(dut.io.phy.rx, dut.rxCd) { p =>
        if (phyRxQueue.nonEmpty) {
          phyRxQueue.dequeue().apply()
          true
        } else {
          false
        }
      }

      enqueuePhyRx(dut, phyRxQueue, phyFrame)

      // Wait for frame to arrive and cross CDC
      waitUntil(phyRxQueue.isEmpty)
      dut.rxCd.waitSampling(500)
      cd.waitSampling(200)

      assert(rxInterruptSeen, "RX interrupt should fire when frame becomes available")
    }
  }
}
