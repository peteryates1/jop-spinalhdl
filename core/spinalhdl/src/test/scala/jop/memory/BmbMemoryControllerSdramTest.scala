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
 * Test harness: BmbMemoryController connected to BmbSdramCtrl32
 * Exposes SDRAM interface for simulation model, plus JBC write port for verification.
 */
case class BmbMemCtrlSdramTestHarness(
  config: JopMemoryConfig = JopMemoryConfig(),
  layout: SdramLayout = W9825G6JH6.layout,
  timing: SdramTimings = W9825G6JH6.timingGrade7,
  CAS: Int = 3,
  jpcWidth: Int = 11
) extends Component {

  val io = new Bundle {
    // Memory controller interface
    val memIn     = in(MemCtrlInput())
    val memOut    = out(MemCtrlOutput())
    val aout      = in Bits(32 bits)
    val bout      = in Bits(32 bits)
    val bcopd     = in Bits(16 bits)
    val ioRdData  = in Bits(32 bits)

    // JBC write port (directly from memory controller for verification)
    val jbcWrAddr   = out UInt((jpcWidth - 2) bits)
    val jbcWrData   = out Bits(32 bits)
    val jbcWrEnable = out Bool()

    // SDRAM interface
    val sdram = master(SdramInterface(layout))

    // Debug
    val debugState = out UInt(4 bits)
    val debugBusy  = out Bool()
  }

  val memCtrl = BmbMemoryController(config, jpcWidth)

  val sdramCtrl = BmbSdramCtrl32(
    bmbParameter = config.bmbParameter,
    layout = layout,
    timing = timing,
    CAS = CAS
  )

  // Connect memory controller to SDRAM controller
  sdramCtrl.io.bmb <> memCtrl.io.bmb

  // SDRAM interface
  io.sdram <> sdramCtrl.io.sdram

  // Wire up memory controller signals
  memCtrl.io.memIn := io.memIn
  io.memOut := memCtrl.io.memOut
  memCtrl.io.aout := io.aout
  memCtrl.io.bout := io.bout
  memCtrl.io.bcopd := io.bcopd
  memCtrl.io.ioRdData := io.ioRdData

  // Expose JBC write port
  io.jbcWrAddr := memCtrl.io.jbcWrite.addr
  io.jbcWrData := memCtrl.io.jbcWrite.data
  io.jbcWrEnable := memCtrl.io.jbcWrite.enable

  // Debug
  io.debugState := memCtrl.io.debug.state
  io.debugBusy := memCtrl.io.debug.busy
}

/**
 * Integration tests: BmbMemoryController + BmbSdramCtrl32
 */
class BmbMemoryControllerSdramTest extends AnyFunSuite {

  val layout = W9825G6JH6.layout
  val timing = W9825G6JH6.timingGrade7

  /** Initialize all memory controller inputs to defaults */
  def initMemIn(dut: BmbMemCtrlSdramTestHarness): Unit = {
    dut.io.memIn.rd #= false
    dut.io.memIn.rdc #= false
    dut.io.memIn.rdf #= false
    dut.io.memIn.wr #= false
    dut.io.memIn.wrf #= false
    dut.io.memIn.addrWr #= false
    dut.io.memIn.bcRd #= false
    dut.io.memIn.stidx #= false
    dut.io.memIn.iaload #= false
    dut.io.memIn.iastore #= false
    dut.io.memIn.getfield #= false
    dut.io.memIn.putfield #= false
    dut.io.memIn.putref #= false
    dut.io.memIn.getstatic #= false
    dut.io.memIn.putstatic #= false
    dut.io.memIn.copy #= false
    dut.io.memIn.cinval #= false
    dut.io.memIn.atmstart #= false
    dut.io.memIn.atmend #= false
    dut.io.memIn.bcopd #= 0
    dut.io.aout #= 0
    dut.io.bout #= 0
    dut.io.bcopd #= 0
    dut.io.ioRdData #= 0
  }

  /** Wait for busy to clear */
  def waitNotBusy(dut: BmbMemCtrlSdramTestHarness, maxCycles: Int = 2000): Int = {
    var cycles = 0
    while (dut.io.debugBusy.toBoolean && cycles < maxCycles) {
      dut.clockDomain.waitSampling()
      cycles += 1
    }
    assert(cycles < maxCycles, s"Timed out waiting for not-busy after $maxCycles cycles")
    cycles
  }

  test("MemCtrl+SDRAM: simple write and read") {
    SimConfig
      .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
      .compile(BmbMemCtrlSdramTestHarness())
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        initMemIn(dut)

        val sdramModel = SdramModel(
          io = dut.io.sdram,
          layout = layout,
          clockDomain = dut.clockDomain
        )

        // Wait for SDRAM initialization
        dut.clockDomain.waitSampling(40000)

        // Write address 0x10
        dut.io.aout #= 0x10
        dut.io.memIn.addrWr #= true
        dut.clockDomain.waitSampling()
        dut.io.memIn.addrWr #= false
        dut.clockDomain.waitSampling()

        // Write value 0xDEADBEEF
        dut.io.aout #= 0xDEADBEEFL
        dut.io.memIn.wr #= true
        dut.clockDomain.waitSampling()
        dut.io.memIn.wr #= false

        val wrCycles = waitNotBusy(dut)
        println(s"Write completed in $wrCycles cycles")
        dut.clockDomain.waitSampling(2)

        // Read back from same address
        dut.io.aout #= 0x10
        dut.io.memIn.rd #= true
        dut.clockDomain.waitSampling()
        dut.io.memIn.rd #= false

        val rdCycles = waitNotBusy(dut)
        println(s"Read completed in $rdCycles cycles")
        dut.clockDomain.waitSampling()

        val data = dut.io.memOut.rdData.toLong & 0xFFFFFFFFL
        println(f"Read back: 0x$data%08X")
        assert(data == 0xDEADBEEFL, f"Expected 0xDEADBEEF, got 0x$data%08X")

        println("=== Simple write/read through SDRAM: PASSED ===")
      }
  }

  test("MemCtrl+SDRAM: BC_FILL loads correct data") {
    SimConfig
      .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
      .compile(BmbMemCtrlSdramTestHarness())
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        initMemIn(dut)

        val sdramModel = SdramModel(
          io = dut.io.sdram,
          layout = layout,
          clockDomain = dut.clockDomain
        )

        // Initialize SDRAM with known bytecode data at word address 100
        val bcStartAddr = 100  // Word address in main memory
        val bcLen = 16  // 16 words = 64 bytes of bytecodes
        val testBytecodes = (0 until bcLen).map(i =>
          (0xAA000000L | (i << 16) | (i << 8) | i) & 0xFFFFFFFFL
        )

        for ((word, i) <- testBytecodes.zipWithIndex) {
          val byteAddr = (bcStartAddr + i) * 4
          sdramModel.write(byteAddr + 0, ((word >> 0) & 0xFF).toByte)
          sdramModel.write(byteAddr + 1, ((word >> 8) & 0xFF).toByte)
          sdramModel.write(byteAddr + 2, ((word >> 16) & 0xFF).toByte)
          sdramModel.write(byteAddr + 3, ((word >> 24) & 0xFF).toByte)
        }

        dut.clockDomain.waitSampling(40000)

        // Trigger BC_FILL: TOS = packed(startAddr, length)
        // Upper bits = start word address, lower 10 bits = length in words
        val packedVal = (bcStartAddr.toLong << 10) | bcLen.toLong
        dut.io.aout #= packedVal
        dut.io.memIn.bcRd #= true
        dut.clockDomain.waitSampling()
        dut.io.memIn.bcRd #= false

        // Capture JBC writes during the fill
        val jbcWrites = scala.collection.mutable.ArrayBuffer[(Int, Long)]()

        var cycles = 0
        val maxCycles = 2000
        while (dut.io.debugBusy.toBoolean && cycles < maxCycles) {
          dut.clockDomain.waitSampling()
          if (dut.io.jbcWrEnable.toBoolean) {
            val addr = dut.io.jbcWrAddr.toInt
            val data = dut.io.jbcWrData.toLong & 0xFFFFFFFFL
            jbcWrites += ((addr, data))
          }
          cycles += 1
        }

        println(s"BC_FILL completed in $cycles cycles, ${jbcWrites.length} JBC writes")

        // Verify JBC writes
        assert(jbcWrites.length == bcLen, s"Expected $bcLen JBC writes, got ${jbcWrites.length}")

        var failures = 0
        for (i <- 0 until bcLen) {
          val (addr, data) = jbcWrites(i)
          val srcWord = testBytecodes(i)
          // BC_FILL byte-swaps: big-endian to little-endian
          val expectedSwapped = ((srcWord & 0xFF) << 24) |
            (((srcWord >> 8) & 0xFF) << 16) |
            (((srcWord >> 16) & 0xFF) << 8) |
            ((srcWord >> 24) & 0xFF)

          if (addr != i) {
            println(f"FAIL JBC write $i: addr=$addr expected=$i")
            failures += 1
          }
          if (data != expectedSwapped) {
            println(f"FAIL JBC write $i: data=0x$data%08X expected=0x$expectedSwapped%08X (src=0x$srcWord%08X)")
            failures += 1
          } else {
            println(f"OK   JBC write $i: addr=$addr data=0x$data%08X (src=0x$srcWord%08X)")
          }
        }

        assert(failures == 0, s"$failures JBC write mismatches")
        println("=== BC_FILL through SDRAM: PASSED ===")
      }
  }

  test("MemCtrl+SDRAM: read pre-initialized SDRAM data") {
    SimConfig
      .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
      .compile(BmbMemCtrlSdramTestHarness())
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        initMemIn(dut)

        val sdramModel = SdramModel(
          io = dut.io.sdram,
          layout = layout,
          clockDomain = dut.clockDomain
        )

        // Pre-initialize SDRAM with known data
        val testData = Map(
          0 -> 0x00000848L,
          1 -> 0x00000400L,
          6 -> 0x2AB70001L,
          7 -> 0xB1000000L,
          9 -> 0x2ADD3604L
        )

        for ((addr, word) <- testData) {
          val base = addr * 4
          sdramModel.write(base + 0, ((word >> 0) & 0xFF).toByte)
          sdramModel.write(base + 1, ((word >> 8) & 0xFF).toByte)
          sdramModel.write(base + 2, ((word >> 16) & 0xFF).toByte)
          sdramModel.write(base + 3, ((word >> 24) & 0xFF).toByte)
        }

        dut.clockDomain.waitSampling(40000)

        // Read each address and verify
        for ((addr, expected) <- testData.toSeq.sortBy(_._1)) {
          dut.io.aout #= addr
          dut.io.memIn.rd #= true
          dut.clockDomain.waitSampling()
          dut.io.memIn.rd #= false

          waitNotBusy(dut)
          dut.clockDomain.waitSampling()

          val data = dut.io.memOut.rdData.toLong & 0xFFFFFFFFL
          println(f"Addr $addr: read=0x$data%08X expected=0x$expected%08X ${if (data == expected) "OK" else "FAIL"}")
          assert(data == expected, f"Addr $addr: expected 0x$expected%08X, got 0x$data%08X")
        }

        println("=== Read pre-initialized SDRAM: PASSED ===")
      }
  }

  test("MemCtrl+SDRAM: getfield through SDRAM") {
    SimConfig
      .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
      .compile(BmbMemCtrlSdramTestHarness())
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        initMemIn(dut)

        val sdramModel = SdramModel(
          io = dut.io.sdram,
          layout = layout,
          clockDomain = dut.clockDomain
        )

        // Set up a handle at address 50: handle[0] = data_ptr = 100
        // Set up field data at address 100+3 = 103: value = 0xCAFEBABE
        val handleAddr = 50
        val dataPtr = 100L
        val fieldIndex = 3
        val fieldValue = 0xCAFEBABEL

        // Write handle[0] = data_ptr
        val handleByteAddr = handleAddr * 4
        sdramModel.write(handleByteAddr + 0, ((dataPtr >> 0) & 0xFF).toByte)
        sdramModel.write(handleByteAddr + 1, ((dataPtr >> 8) & 0xFF).toByte)
        sdramModel.write(handleByteAddr + 2, ((dataPtr >> 16) & 0xFF).toByte)
        sdramModel.write(handleByteAddr + 3, ((dataPtr >> 24) & 0xFF).toByte)

        // Write field data at data_ptr + fieldIndex
        val fieldByteAddr = (dataPtr.toInt + fieldIndex) * 4
        sdramModel.write(fieldByteAddr + 0, ((fieldValue >> 0) & 0xFF).toByte)
        sdramModel.write(fieldByteAddr + 1, ((fieldValue >> 8) & 0xFF).toByte)
        sdramModel.write(fieldByteAddr + 2, ((fieldValue >> 16) & 0xFF).toByte)
        sdramModel.write(fieldByteAddr + 3, ((fieldValue >> 24) & 0xFF).toByte)

        dut.clockDomain.waitSampling(40000)

        // Issue getfield: TOS = object ref (handle address), bcopd = field index
        dut.io.aout #= handleAddr
        dut.io.bcopd #= fieldIndex
        dut.io.memIn.getfield #= true
        dut.clockDomain.waitSampling()
        dut.io.memIn.getfield #= false

        val cycles = waitNotBusy(dut)
        dut.clockDomain.waitSampling()

        val data = dut.io.memOut.rdData.toLong & 0xFFFFFFFFL
        println(f"getfield result: 0x$data%08X expected 0x$fieldValue%08X ($cycles cycles)")
        assert(data == fieldValue, f"getfield: expected 0x$fieldValue%08X, got 0x$data%08X")

        println("=== getfield through SDRAM: PASSED ===")
      }
  }

  test("MemCtrl+SDRAM: multiple sequential operations") {
    SimConfig
      .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
      .compile(BmbMemCtrlSdramTestHarness())
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        initMemIn(dut)

        val sdramModel = SdramModel(
          io = dut.io.sdram,
          layout = layout,
          clockDomain = dut.clockDomain
        )

        dut.clockDomain.waitSampling(40000)

        // Test: write 10 values, then read them all back
        val testValues = (0 until 10).map(i => (0x10000000L + i * 0x11111111L) & 0xFFFFFFFFL)

        for ((value, addr) <- testValues.zipWithIndex) {
          // Set write address
          dut.io.aout #= addr
          dut.io.memIn.addrWr #= true
          dut.clockDomain.waitSampling()
          dut.io.memIn.addrWr #= false
          dut.clockDomain.waitSampling()

          // Write data
          dut.io.aout #= value
          dut.io.memIn.wr #= true
          dut.clockDomain.waitSampling()
          dut.io.memIn.wr #= false

          waitNotBusy(dut)
          dut.clockDomain.waitSampling(2)
        }

        println("All writes complete, reading back...")

        // Read back
        var failures = 0
        for ((expected, addr) <- testValues.zipWithIndex) {
          dut.io.aout #= addr
          dut.io.memIn.rd #= true
          dut.clockDomain.waitSampling()
          dut.io.memIn.rd #= false

          waitNotBusy(dut)
          dut.clockDomain.waitSampling()

          val data = dut.io.memOut.rdData.toLong & 0xFFFFFFFFL
          if (data != expected) {
            println(f"FAIL Addr $addr: read=0x$data%08X expected=0x$expected%08X")
            failures += 1
          }
        }

        println(s"Sequential operations: $failures failures")
        assert(failures == 0)
        println("=== Multiple sequential operations: PASSED ===")
      }
  }
}
