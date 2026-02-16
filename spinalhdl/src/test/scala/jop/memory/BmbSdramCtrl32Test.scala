package jop.memory

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import spinal.lib.memory.sdram._
import spinal.lib.memory.sdram.sdr._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import org.scalatest.funsuite.AnyFunSuite

/**
 * Test harness for BmbSdramCtrl32 with exposed SDRAM interface
 */
case class BmbSdramCtrl32TestHarness(
  layout: SdramLayout = W9825G6JH6.layout,
  timing: SdramTimings = W9825G6JH6.timingGrade7,
  CAS: Int = 3
) extends Component {

  val bmbParam = BmbParameter(
    access = BmbAccessParameter(
      addressWidth = 25,
      dataWidth = 32
    ).addSources(1, BmbSourceParameter(
      contextWidth = 0,
      lengthWidth = 2,
      canWrite = true
    ))
  )

  val io = new Bundle {
    val bmb = slave(Bmb(bmbParam))
    val sdram = master(SdramInterface(layout))
  }

  val ctrl = BmbSdramCtrl32(
    bmbParameter = bmbParam,
    layout = layout,
    timing = timing,
    CAS = CAS
  )

  io.bmb <> ctrl.io.bmb
  io.sdram <> ctrl.io.sdram
}

/**
 * Unit tests for BmbSdramCtrl32 - the 32-bit BMB to 16-bit SDRAM bridge
 */
class BmbSdramCtrl32Test extends AnyFunSuite {

  val layout = W9825G6JH6.layout
  val timing = W9825G6JH6.timingGrade7

  /** Helper: initialize default BMB command signals */
  def initBmb(dut: BmbSdramCtrl32TestHarness): Unit = {
    dut.io.bmb.cmd.valid #= false
    dut.io.bmb.cmd.last #= true
    dut.io.bmb.cmd.fragment.opcode #= 0 // READ
    dut.io.bmb.cmd.fragment.address #= 0
    dut.io.bmb.cmd.fragment.length #= 3 // 4 bytes
    dut.io.bmb.cmd.fragment.source #= 0
    dut.io.bmb.cmd.fragment.context #= 0
    dut.io.bmb.cmd.fragment.data #= 0
    dut.io.bmb.cmd.fragment.mask #= 0xF
    dut.io.bmb.rsp.ready #= true
  }

  /** Helper: issue a BMB read and return the 32-bit data.
   *
   * BMB protocol: hold cmd.valid until cmd.fire (valid && ready), then deassert.
   * BmbSdramCtrl32 forwards cmd.valid to SdramCtrl, so we MUST deassert valid
   * immediately on the cycle where ready is seen — any extra cycle with
   * valid=True generates a spurious SDRAM low-half command.
   */
  def bmbRead(dut: BmbSdramCtrl32TestHarness, wordAddr: Int): Long = {
    val byteAddr = wordAddr * 4
    dut.io.bmb.cmd.valid #= true
    dut.io.bmb.cmd.fragment.opcode #= 0 // READ
    dut.io.bmb.cmd.fragment.address #= byteAddr
    dut.io.bmb.cmd.fragment.length #= 3
    dut.io.bmb.cmd.fragment.mask #= 0xF

    // Advance one edge to apply signals, then poll for ready
    dut.clockDomain.waitSampling()
    var timeout = 500
    while (!dut.io.bmb.cmd.ready.toBoolean && timeout > 0) {
      dut.clockDomain.waitSampling()
      timeout -= 1
    }
    assert(timeout > 0, s"BMB read command not accepted (addr=$wordAddr)")
    // cmd.fire happened at this edge — deassert valid IMMEDIATELY (takes effect next edge)
    dut.io.bmb.cmd.valid #= false

    // Wait for response
    dut.clockDomain.waitSampling()
    timeout = 500
    while (!dut.io.bmb.rsp.valid.toBoolean && timeout > 0) {
      dut.clockDomain.waitSampling()
      timeout -= 1
    }
    assert(timeout > 0, s"BMB read response not received (addr=$wordAddr)")
    val data = dut.io.bmb.rsp.fragment.data.toLong & 0xFFFFFFFFL
    dut.clockDomain.waitSampling()
    data
  }

  /** Helper: issue a BMB write */
  def bmbWrite(dut: BmbSdramCtrl32TestHarness, wordAddr: Int, data: Long): Unit = {
    val byteAddr = wordAddr * 4
    dut.io.bmb.cmd.valid #= true
    dut.io.bmb.cmd.fragment.opcode #= 1 // WRITE
    dut.io.bmb.cmd.fragment.address #= byteAddr
    dut.io.bmb.cmd.fragment.length #= 3
    dut.io.bmb.cmd.fragment.data #= data
    dut.io.bmb.cmd.fragment.mask #= 0xF

    // Advance one edge to apply signals, then poll for ready
    dut.clockDomain.waitSampling()
    var timeout = 500
    while (!dut.io.bmb.cmd.ready.toBoolean && timeout > 0) {
      dut.clockDomain.waitSampling()
      timeout -= 1
    }
    assert(timeout > 0, s"BMB write command not accepted (addr=$wordAddr)")
    // cmd.fire happened — deassert valid immediately
    dut.io.bmb.cmd.valid #= false

    // Wait for response
    dut.clockDomain.waitSampling()
    timeout = 500
    while (!dut.io.bmb.rsp.valid.toBoolean && timeout > 0) {
      dut.clockDomain.waitSampling()
      timeout -= 1
    }
    assert(timeout > 0, s"BMB write response not received (addr=$wordAddr)")
    dut.clockDomain.waitSampling()
  }

  test("BmbSdramCtrl32: read pre-initialized SDRAM data") {
    SimConfig
      .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
      .compile(BmbSdramCtrl32TestHarness())
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        initBmb(dut)

        // Create SDRAM model
        val sdramModel = SdramModel(
          io = dut.io.sdram,
          layout = layout,
          clockDomain = dut.clockDomain
        )

        // Initialize SDRAM with known 32-bit words (little-endian byte order)
        val testWords = Seq(
          0xDEADBEEFL, 0x12345678L, 0xCAFEBABEL, 0x00000001L,
          0xFFFFFFFFL, 0x00000000L, 0x80000000L, 0x7FFFFFFFL
        )
        for ((word, idx) <- testWords.zipWithIndex) {
          val base = idx * 4
          sdramModel.write(base + 0, ((word >> 0) & 0xFF).toByte)
          sdramModel.write(base + 1, ((word >> 8) & 0xFF).toByte)
          sdramModel.write(base + 2, ((word >> 16) & 0xFF).toByte)
          sdramModel.write(base + 3, ((word >> 24) & 0xFF).toByte)
        }

        // Wait for SDRAM init sequence
        dut.clockDomain.waitSampling(40000)

        // Read back each word and verify
        for ((expected, idx) <- testWords.zipWithIndex) {
          val data = bmbRead(dut, idx)
          println(f"Word $idx: read=0x$data%08X expected=0x$expected%08X ${if (data == expected) "OK" else "FAIL"}")
          assert(data == expected, f"Word $idx: expected 0x$expected%08X, got 0x$data%08X")
        }

        println("=== Read pre-initialized SDRAM: PASSED ===")
      }
  }

  test("BmbSdramCtrl32: write then read back") {
    SimConfig
      .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
      .compile(BmbSdramCtrl32TestHarness())
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        initBmb(dut)

        val sdramModel = SdramModel(
          io = dut.io.sdram,
          layout = layout,
          clockDomain = dut.clockDomain
        )

        dut.clockDomain.waitSampling(40000)

        // Write patterns to addresses 0-7
        val testPatterns = Seq(
          0xDEADBEEFL, 0x12345678L, 0xCAFEBABEL, 0xA5A5A5A5L,
          0x5A5A5A5AL, 0x01020304L, 0xF0F0F0F0L, 0x0F0F0F0FL
        )

        for ((pattern, idx) <- testPatterns.zipWithIndex) {
          bmbWrite(dut, idx, pattern)
        }
        println("All writes complete")

        // Read back and verify
        for ((expected, idx) <- testPatterns.zipWithIndex) {
          val data = bmbRead(dut, idx)
          println(f"Word $idx: read=0x$data%08X expected=0x$expected%08X ${if (data == expected) "OK" else "FAIL"}")
          assert(data == expected, f"Word $idx: expected 0x$expected%08X, got 0x$data%08X")
        }

        println("=== Write then read: PASSED ===")
      }
  }

  test("BmbSdramCtrl32: sequential reads (BC_FILL pattern)") {
    SimConfig
      .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
      .compile(BmbSdramCtrl32TestHarness())
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        initBmb(dut)

        val sdramModel = SdramModel(
          io = dut.io.sdram,
          layout = layout,
          clockDomain = dut.clockDomain
        )

        // Initialize 128 consecutive words (simulates a method's bytecodes)
        val numWords = 128
        val baseAddr = 100  // Start at word address 100 (not aligned to 0)
        val testData = (0 until numWords).map(i => ((i * 0x01010101L + 0xABCD0000L) & 0xFFFFFFFFL))

        for ((word, i) <- testData.zipWithIndex) {
          val byteAddr = (baseAddr + i) * 4
          sdramModel.write(byteAddr + 0, ((word >> 0) & 0xFF).toByte)
          sdramModel.write(byteAddr + 1, ((word >> 8) & 0xFF).toByte)
          sdramModel.write(byteAddr + 2, ((word >> 16) & 0xFF).toByte)
          sdramModel.write(byteAddr + 3, ((word >> 24) & 0xFF).toByte)
        }

        dut.clockDomain.waitSampling(40000)

        // Read all words sequentially (like BC_FILL does)
        var failures = 0
        for (i <- 0 until numWords) {
          val data = bmbRead(dut, baseAddr + i)
          val expected = testData(i)
          if (data != expected) {
            println(f"FAIL Word ${baseAddr + i}: read=0x$data%08X expected=0x$expected%08X")
            failures += 1
          }
        }

        println(s"Sequential read: $numWords words, $failures failures")
        assert(failures == 0, s"$failures words read back incorrectly")
        println("=== Sequential reads (BC_FILL pattern): PASSED ===")
      }
  }

  test("BmbSdramCtrl32: verify actual JOP memory data") {
    // Test with the actual first few words from HelloWorld.jop
    // to ensure the real program data survives the SDRAM roundtrip
    SimConfig
      .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
      .compile(BmbSdramCtrl32TestHarness())
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        initBmb(dut)

        val sdramModel = SdramModel(
          io = dut.io.sdram,
          layout = layout,
          clockDomain = dut.clockDomain
        )

        // First words of HelloWorld.jop (from sim log verification)
        val jopData = Seq(
          0x00000848L, 0x00000400L, 0x00000000L, 0x00000000L,
          0x00000000L, 0x00000000L, 0x2AB70001L, 0xB1000000L,
          0x00000000L, 0x2ADD3604L
        )

        for ((word, idx) <- jopData.zipWithIndex) {
          val base = idx * 4
          sdramModel.write(base + 0, ((word >> 0) & 0xFF).toByte)
          sdramModel.write(base + 1, ((word >> 8) & 0xFF).toByte)
          sdramModel.write(base + 2, ((word >> 16) & 0xFF).toByte)
          sdramModel.write(base + 3, ((word >> 24) & 0xFF).toByte)
        }

        dut.clockDomain.waitSampling(40000)

        for ((expected, idx) <- jopData.zipWithIndex) {
          val data = bmbRead(dut, idx)
          println(f"JOP word $idx: read=0x$data%08X expected=0x$expected%08X ${if (data == expected) "OK" else "FAIL"}")
          assert(data == expected, f"JOP word $idx: expected 0x$expected%08X, got 0x$data%08X")
        }

        println("=== JOP memory data roundtrip: PASSED ===")
      }
  }
}
