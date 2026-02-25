package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.com.eth._

/**
 * End-to-end test of MacEth RX path at 8-bit (GMII) width.
 * Feeds a complete Ethernet frame (preamble+SFD+data+FCS) through the PHY RX
 * and verifies the frame emerges from the control RX stream.
 */
class MacEthRxTest extends AnyFunSuite {

  /** Software CRC-32 (standard Ethernet, reflected) */
  def ethCrc32(data: Seq[Int]): Long = {
    var crc = 0xFFFFFFFFL
    for (byte <- data) {
      for (bit <- 0 until 8) {
        val b = ((byte >> bit) & 1) ^ ((crc & 1).toInt)
        crc = (crc >>> 1) ^ (if (b == 1) 0xEDB88320L else 0L)
      }
    }
    crc ^ 0xFFFFFFFFL
  }

  /** Build a complete wire-format frame: preamble + SFD + payload + FCS */
  def buildFrame(payload: Seq[Int]): Seq[Int] = {
    val preamble = Seq.fill(7)(0x55) :+ 0xD5  // 7 preamble + 1 SFD
    val crc = ethCrc32(payload)
    val fcs = Seq(
      (crc & 0xFF).toInt,
      ((crc >> 8) & 0xFF).toInt,
      ((crc >> 16) & 0xFF).toInt,
      ((crc >> 24) & 0xFF).toInt
    )
    preamble ++ payload ++ fcs
  }

  // Minimal ARP packet (42 bytes of frame data)
  val arpPayload = Seq(
    0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, // dst MAC
    0x02, 0x00, 0x00, 0x00, 0x00, 0x01, // src MAC
    0x08, 0x06,                           // EtherType
    0x00, 0x01, 0x08, 0x00, 0x06, 0x04, // ARP header
    0x00, 0x01,                           // OPER
    0x02, 0x00, 0x00, 0x00, 0x00, 0x01, // SHA
    0xC0, 0xA8, 0x00, 0x01,             // SPA
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // THA
    0xC0, 0xA8, 0x00, 0x01              // TPA
  )

  // Wrap MacEth in a testable component with explicit clock domains
  case class MacEthTestHarness(dataWidth: Int) extends Component {
    val p = MacEthParameter(
      phy = PhyParameter(txDataWidth = dataWidth, rxDataWidth = dataWidth),
      rxDataWidth = 32,
      txDataWidth = 32,
      rxBufferByteSize = 2048,
      txBufferByteSize = 2048
    )

    val txCd = ClockDomain.external("txCd", withReset = true)
    val rxCd = ClockDomain.external("rxCd", withReset = true)

    val mac = MacEth(p, txCd, rxCd)

    val io = new Bundle {
      // PHY RX interface (directly to mac)
      val rxValid = in Bool()
      val rxData  = in Bits(dataWidth bits)
      val rxError = in Bool()
      val rxLast  = in Bool()

      // Control RX stream out (system clock domain)
      val ctrlRxValid   = out Bool()
      val ctrlRxReady   = in Bool()
      val ctrlRxPayload = out Bits(32 bits)

      // Control signals
      val rxFlush = in Bool()
      val txFlush = in Bool()

      // Stats
      val errors = out UInt(8 bits)
      val drops  = out UInt(8 bits)
      val statsClear = in Bool()
    }

    // Connect PHY RX as Stream
    val phyRxStream = Stream(Fragment(PhyRx(dataWidth)))
    phyRxStream.valid := io.rxValid
    phyRxStream.fragment.data := io.rxData
    phyRxStream.fragment.error := io.rxError
    phyRxStream.last := io.rxLast
    mac.io.phy.rx << phyRxStream

    // TX: tie off
    mac.io.phy.tx.ready := True
    mac.io.phy.colision := False
    mac.io.phy.busy := False

    // Control
    mac.io.ctrl.rx.flush := io.rxFlush
    mac.io.ctrl.tx.flush := io.txFlush
    mac.io.ctrl.tx.alignerEnable := False
    mac.io.ctrl.rx.alignerEnable := False
    mac.io.ctrl.tx.stream.valid := False
    mac.io.ctrl.tx.stream.payload := 0

    // RX output
    io.ctrlRxValid := mac.io.ctrl.rx.stream.valid
    mac.io.ctrl.rx.stream.ready := io.ctrlRxReady
    io.ctrlRxPayload := mac.io.ctrl.rx.stream.payload

    // Stats
    io.errors := mac.io.ctrl.rx.stats.errors
    io.drops := mac.io.ctrl.rx.stats.drops
    mac.io.ctrl.rx.stats.clear := io.statsClear
  }

  test("MacEth 8-bit RX: ARP frame passes CRC and is readable") {
    val frame = buildFrame(arpPayload)
    println(s"Frame size: ${frame.size} bytes (${arpPayload.size} payload + 8 preamble+SFD + 4 FCS)")
    println(f"Expected CRC: 0x${ethCrc32(arpPayload)}%08X")

    SimConfig.withFstWave.compile(MacEthTestHarness(8)).doSim { dut =>
      // Fork all three clock domains
      dut.clockDomain.forkStimulus(10)  // system: 100 MHz
      dut.txCd.forkStimulus(8)          // tx: 125 MHz
      dut.rxCd.forkStimulus(8)          // rx: 125 MHz

      // Initial state
      dut.io.rxValid #= false
      dut.io.rxData #= 0
      dut.io.rxError #= false
      dut.io.rxLast #= false
      dut.io.ctrlRxReady #= false
      dut.io.rxFlush #= true
      dut.io.txFlush #= true
      dut.io.statsClear #= false

      // Wait for reset
      dut.clockDomain.waitSampling(20)
      dut.rxCd.waitSampling(20)

      // Release flush
      dut.io.rxFlush #= false
      dut.io.txFlush #= false
      dut.clockDomain.waitSampling(20)
      dut.rxCd.waitSampling(20)

      // Clear stats
      dut.io.statsClear #= true
      dut.clockDomain.waitSampling()
      dut.io.statsClear #= false
      dut.clockDomain.waitSampling()

      // Feed frame bytes on RX clock domain
      dut.rxCd.waitSampling(5)
      for (i <- frame.indices) {
        dut.io.rxValid #= true
        dut.io.rxData #= frame(i)
        dut.io.rxError #= false
        dut.io.rxLast #= (i == frame.size - 1)
        dut.rxCd.waitSampling()
      }
      dut.io.rxValid #= false
      dut.io.rxData #= 0
      dut.io.rxLast #= false

      // Wait for frame to propagate through CDC
      dut.clockDomain.waitSampling(100)

      // Check stats
      val errors = dut.io.errors.toInt
      val drops = dut.io.drops.toInt
      println(s"Errors: $errors, Drops: $drops")

      // Try to read the frame from control RX stream
      val rxWords = scala.collection.mutable.ArrayBuffer[Long]()
      dut.io.ctrlRxReady #= true
      for (_ <- 0 until 20) {
        dut.clockDomain.waitSampling()
        if (dut.io.ctrlRxValid.toBoolean) {
          rxWords += (dut.io.ctrlRxPayload.toLong & 0xFFFFFFFFL)
        }
      }
      dut.io.ctrlRxReady #= false

      if (rxWords.nonEmpty) {
        println(s"Received ${rxWords.size} words:")
        for ((w, i) <- rxWords.zipWithIndex) {
          println(f"  W$i: 0x${w}%08X")
        }

        // First word should be frame length in bits
        val lenBits = rxWords.head
        println(s"Frame length: $lenBits bits = ${lenBits / 8} bytes")

        // Verify we got data (even if not perfect, getting data means CRC passed)
        assert(rxWords.size > 1, "Expected frame data words")
        assert(errors == 0, s"Expected 0 errors, got $errors")
      } else {
        println(s"NO DATA received! Errors=$errors Drops=$drops")
        assert(false, "No frame data received from MacEth")
      }
    }
  }

  test("MacEth 4-bit RX: ARP frame passes CRC (MII nibbles)") {
    val frame = buildFrame(arpPayload)

    SimConfig.withFstWave.compile(MacEthTestHarness(4)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.txCd.forkStimulus(40)  // tx: 25 MHz
      dut.rxCd.forkStimulus(40)  // rx: 25 MHz

      dut.io.rxValid #= false
      dut.io.rxData #= 0
      dut.io.rxError #= false
      dut.io.rxLast #= false
      dut.io.ctrlRxReady #= false
      dut.io.rxFlush #= true
      dut.io.txFlush #= true
      dut.io.statsClear #= false

      dut.clockDomain.waitSampling(20)
      dut.rxCd.waitSampling(20)

      dut.io.rxFlush #= false
      dut.io.txFlush #= false
      dut.clockDomain.waitSampling(20)
      dut.rxCd.waitSampling(20)

      dut.io.statsClear #= true
      dut.clockDomain.waitSampling()
      dut.io.statsClear #= false
      dut.clockDomain.waitSampling()

      // Feed frame as nibbles (low nibble first per MII)
      dut.rxCd.waitSampling(5)
      for (i <- frame.indices) {
        val byte = frame(i)
        val isLast = (i == frame.size - 1)
        // Low nibble
        dut.io.rxValid #= true
        dut.io.rxData #= (byte & 0x0F)
        dut.io.rxError #= false
        dut.io.rxLast #= false
        dut.rxCd.waitSampling()
        // High nibble
        dut.io.rxData #= ((byte >> 4) & 0x0F)
        dut.io.rxLast #= isLast
        dut.rxCd.waitSampling()
      }
      dut.io.rxValid #= false
      dut.io.rxData #= 0
      dut.io.rxLast #= false

      dut.clockDomain.waitSampling(100)

      val errors = dut.io.errors.toInt
      val drops = dut.io.drops.toInt
      println(s"4-bit: Errors: $errors, Drops: $drops")

      val rxWords = scala.collection.mutable.ArrayBuffer[Long]()
      dut.io.ctrlRxReady #= true
      for (_ <- 0 until 20) {
        dut.clockDomain.waitSampling()
        if (dut.io.ctrlRxValid.toBoolean) {
          rxWords += (dut.io.ctrlRxPayload.toLong & 0xFFFFFFFFL)
        }
      }

      if (rxWords.nonEmpty) {
        println(s"4-bit: Received ${rxWords.size} words")
        assert(errors == 0, s"Expected 0 errors, got $errors")
      } else {
        println(s"4-bit: NO DATA received! Errors=$errors Drops=$drops")
        assert(false, "No frame data received from MacEth 4-bit")
      }
    }
  }
}
