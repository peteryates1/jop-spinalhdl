package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.com.eth._

/**
 * Verify SpinalHDL CRC-32 at 8-bit width against known Ethernet CRC values.
 */
class CrcVerifyTest extends AnyFunSuite {

  /** Software CRC-32 (standard Ethernet, reflected) for comparison */
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

  /** The magic residue when feeding data+FCS through CRC (reflected style) */
  val MAGIC_RESIDUE = 0x2144DF1CL

  test("CRC-32 software reference: known ARP packet") {
    // Minimal ARP packet (42 bytes) — broadcast ARP request
    val arpPacket = Seq(
      0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, // dst MAC (broadcast)
      0x02, 0x00, 0x00, 0x00, 0x00, 0x01, // src MAC
      0x08, 0x06,                           // EtherType (ARP)
      0x00, 0x01,                           // HTYPE (Ethernet)
      0x08, 0x00,                           // PTYPE (IPv4)
      0x06,                                 // HLEN
      0x04,                                 // PLEN
      0x00, 0x01,                           // OPER (request)
      0x02, 0x00, 0x00, 0x00, 0x00, 0x01, // SHA
      0xC0, 0xA8, 0x00, 0x01,             // SPA (192.168.0.1)
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // THA
      0xC0, 0xA8, 0x00, 0x01,             // TPA (192.168.0.1)
    )
    val crc = ethCrc32(arpPacket)
    println(f"ARP CRC-32 = 0x${crc}%08X")

    // Append FCS and verify residue
    val fcsBytes = Seq(
      (crc & 0xFF).toInt,
      ((crc >> 8) & 0xFF).toInt,
      ((crc >> 16) & 0xFF).toInt,
      ((crc >> 24) & 0xFF).toInt
    )
    val fullFrame = arpPacket ++ fcsBytes

    // Feed full frame through CRC — should get magic residue
    var state = 0xFFFFFFFFL
    for (byte <- fullFrame) {
      for (bit <- 0 until 8) {
        val b = ((byte >> bit) & 1) ^ ((state & 1).toInt)
        state = (state >>> 1) ^ (if (b == 1) 0xEDB88320L else 0L)
      }
    }
    println(f"Residue after full frame = 0x${state}%08X (expect 0xDEBB20E3)")
    // Note: 0xDEBB20E3 is the raw CRC state residue for the reflected (LSB-first) algorithm
    // (0xC704DD7B is the non-reflected residue, not applicable here)
    assert(state == 0xDEBB20E3L, f"Expected 0xDEBB20E3, got 0x${state}%08X")
  }

  test("SpinalHDL CRC-32 at 8-bit: matches software reference") {
    // Test with a known packet
    val testData = Seq(
      0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
      0x02, 0x00, 0x00, 0x00, 0x00, 0x01,
      0x08, 0x06,
      0x00, 0x01, 0x08, 0x00, 0x06, 0x04,
      0x00, 0x01,
      0x02, 0x00, 0x00, 0x00, 0x00, 0x01,
      0xC0, 0xA8, 0x00, 0x01,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0xC0, 0xA8, 0x00, 0x01,
    )

    val swCrc = ethCrc32(testData)
    println(f"Software CRC = 0x${swCrc}%08X")

    // Simulate the SpinalHDL CRC module
    SimConfig.withFstWave.compile(Crc(CrcKind.Crc32, 8)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Flush
      dut.io.flush #= true
      dut.io.input.valid #= false
      dut.io.input.payload #= 0
      dut.clockDomain.waitSampling()
      dut.io.flush #= false
      dut.clockDomain.waitSampling()

      // Feed data bytes
      for (byte <- testData) {
        dut.io.input.valid #= true
        dut.io.input.payload #= byte
        dut.clockDomain.waitSampling()
      }
      dut.io.input.valid #= false
      dut.clockDomain.waitSampling()

      // Read result (with output reflection and final XOR already applied by Crc module)
      val hwResult = dut.io.result.toLong & 0xFFFFFFFFL
      println(f"SpinalHDL CRC result = 0x${hwResult}%08X")
      println(f"Software CRC         = 0x${swCrc}%08X")
      assert(hwResult == swCrc, f"Mismatch! HW=0x${hwResult}%08X SW=0x${swCrc}%08X")

      // Now feed the FCS bytes and check resultNext == magic residue
      val fcsBytes = Seq(
        (swCrc & 0xFF).toInt,
        ((swCrc >> 8) & 0xFF).toInt,
        ((swCrc >> 16) & 0xFF).toInt,
        ((swCrc >> 24) & 0xFF).toInt
      )
      for (byte <- fcsBytes) {
        dut.io.input.valid #= true
        dut.io.input.payload #= byte
        dut.clockDomain.waitSampling()
      }

      val residue = dut.io.resultNext.toLong & 0xFFFFFFFFL
      println(f"SpinalHDL residue after FCS = 0x${residue}%08X (expect 0x2144DF1C)")
      assert(residue == MAGIC_RESIDUE, f"Residue mismatch! Got 0x${residue}%08X, expected 0x2144DF1C")
    }
  }

  test("SpinalHDL CRC-32 at 4-bit: matches software reference") {
    val testData = Seq(
      0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
      0x02, 0x00, 0x00, 0x00, 0x00, 0x01,
      0x08, 0x06,
      0x00, 0x01, 0x08, 0x00, 0x06, 0x04,
      0x00, 0x01,
      0x02, 0x00, 0x00, 0x00, 0x00, 0x01,
      0xC0, 0xA8, 0x00, 0x01,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0xC0, 0xA8, 0x00, 0x01,
    )

    val swCrc = ethCrc32(testData)

    // Simulate the SpinalHDL CRC module at 4-bit width (MII nibbles)
    SimConfig.withFstWave.compile(Crc(CrcKind.Crc32, 4)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Flush
      dut.io.flush #= true
      dut.io.input.valid #= false
      dut.io.input.payload #= 0
      dut.clockDomain.waitSampling()
      dut.io.flush #= false
      dut.clockDomain.waitSampling()

      // Feed data as nibbles (low nibble first, then high nibble — MII order)
      for (byte <- testData) {
        dut.io.input.valid #= true
        dut.io.input.payload #= (byte & 0x0F)  // low nibble first
        dut.clockDomain.waitSampling()
        dut.io.input.payload #= ((byte >> 4) & 0x0F) // high nibble second
        dut.clockDomain.waitSampling()
      }
      dut.io.input.valid #= false
      dut.clockDomain.waitSampling()

      val hwResult = dut.io.result.toLong & 0xFFFFFFFFL
      println(f"SpinalHDL 4-bit CRC result = 0x${hwResult}%08X")
      println(f"Software CRC               = 0x${swCrc}%08X")
      assert(hwResult == swCrc, f"Mismatch! HW=0x${hwResult}%08X SW=0x${swCrc}%08X")

      // Feed FCS nibbles
      val fcsBytes = Seq(
        (swCrc & 0xFF).toInt,
        ((swCrc >> 8) & 0xFF).toInt,
        ((swCrc >> 16) & 0xFF).toInt,
        ((swCrc >> 24) & 0xFF).toInt
      )
      for (byte <- fcsBytes) {
        dut.io.input.valid #= true
        dut.io.input.payload #= (byte & 0x0F)
        dut.clockDomain.waitSampling()
        dut.io.input.payload #= ((byte >> 4) & 0x0F)
        dut.clockDomain.waitSampling()
      }

      val residue = dut.io.resultNext.toLong & 0xFFFFFFFFL
      println(f"SpinalHDL 4-bit residue after FCS = 0x${residue}%08X (expect 0x2144DF1C)")
      assert(residue == MAGIC_RESIDUE, f"Residue mismatch! Got 0x${residue}%08X, expected 0x2144DF1C")
    }
  }
}
