package jop.memory

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import org.scalatest.funsuite.AnyFunSuite

/**
 * Test harness that connects BmbMemoryController to BmbOnChipRam
 */
case class BmbMemoryTestHarness(
  config: JopMemoryConfig = JopMemoryConfig(mainMemSize = 4096)  // 4KB for test
) extends Component {

  val io = new Bundle {
    // Memory controller interface
    val memIn     = in(MemCtrlInput())
    val memOut    = out(MemCtrlOutput())
    val aout      = in Bits(32 bits)
    val bout      = in Bits(32 bits)
    val bcopd     = in Bits(16 bits)

    // I/O interface (directly exposed)
    val ioRdData  = in Bits(32 bits)

    // Debug
    val debug = new Bundle {
      val state = out UInt(4 bits)
      val busy  = out Bool()
    }
  }

  // Memory controller
  val memCtrl = BmbMemoryController(config)

  // Block RAM with BMB interface
  val ram = BmbOnChipRam(
    p = config.bmbParameter,
    size = config.mainMemSize,
    hexInit = null
  )

  // Connect memory controller to RAM
  ram.io.bus << memCtrl.io.bmb

  // Wire up interfaces
  memCtrl.io.memIn := io.memIn
  io.memOut := memCtrl.io.memOut
  memCtrl.io.aout := io.aout
  memCtrl.io.bout := io.bout
  memCtrl.io.bcopd := io.bcopd
  memCtrl.io.ioRdData := io.ioRdData

  io.debug.state := memCtrl.io.debug.state
  io.debug.busy := memCtrl.io.debug.busy
}

/**
 * BmbMemoryController Tests
 */
class BmbMemoryControllerTest extends AnyFunSuite {

  def defaultMemIn(): Unit = {
    // All signals default to false
  }

  test("BmbMemoryController: simple write and read") {
    SimConfig.withWave.compile(BmbMemoryTestHarness()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Initialize inputs
      dut.io.memIn.rd #= false
      dut.io.memIn.wr #= false
      dut.io.memIn.addrWr #= false
      dut.io.memIn.bcRd #= false
      dut.io.memIn.stidx #= false
      dut.io.memIn.iaload #= false
      dut.io.memIn.iastore #= false
      dut.io.memIn.getfield #= false
      dut.io.memIn.putfield #= false
      dut.io.memIn.getstatic #= false
      dut.io.memIn.putstatic #= false
      dut.io.memIn.copy #= false
      dut.io.memIn.cinval #= false
      dut.io.aout #= 0
      dut.io.bout #= 0
      dut.io.bcopd #= 0
      dut.io.ioRdData #= 0

      dut.clockDomain.waitSampling(5)

      // Write address 0x10
      println("=== Write address 0x10 ===")
      dut.io.aout #= 0x10
      dut.io.memIn.addrWr #= true
      dut.clockDomain.waitSampling()
      dut.io.memIn.addrWr #= false
      dut.clockDomain.waitSampling()

      // Write value 0xDEADBEEF
      println("=== Write value 0xDEADBEEF ===")
      dut.io.aout #= 0xDEADBEEFL
      dut.io.memIn.wr #= true
      dut.clockDomain.waitSampling()
      println(s"After wr pulse: state=${dut.io.debug.state.toInt}, busy=${dut.io.debug.busy.toBoolean}")
      dut.io.memIn.wr #= false

      // Wait for write to complete - need at least one cycle for state machine
      dut.clockDomain.waitSampling()
      println(s"After 1 cycle: state=${dut.io.debug.state.toInt}, busy=${dut.io.debug.busy.toBoolean}")

      var cycles = 0
      while (dut.io.debug.busy.toBoolean && cycles < 20) {
        dut.clockDomain.waitSampling()
        cycles += 1
        println(s"Waiting: state=${dut.io.debug.state.toInt}, busy=${dut.io.debug.busy.toBoolean}")
      }
      println(s"Write completed in $cycles cycles")

      dut.clockDomain.waitSampling(2)

      // Read back
      println("=== Read address 0x10 ===")
      dut.io.memIn.rd #= true
      dut.clockDomain.waitSampling()
      println(s"After rd pulse: state=${dut.io.debug.state.toInt}, busy=${dut.io.debug.busy.toBoolean}")
      dut.io.memIn.rd #= false

      // Wait for read to complete - need at least one cycle for state machine
      dut.clockDomain.waitSampling()
      println(s"After 1 cycle: state=${dut.io.debug.state.toInt}, busy=${dut.io.debug.busy.toBoolean}")

      cycles = 0
      while (dut.io.debug.busy.toBoolean && cycles < 20) {
        dut.clockDomain.waitSampling()
        cycles += 1
        println(s"Waiting: state=${dut.io.debug.state.toInt}, busy=${dut.io.debug.busy.toBoolean}")
      }
      println(s"Read completed in $cycles cycles")

      dut.clockDomain.waitSampling()

      // Check result
      val readValue = dut.io.memOut.rdData.toLong & 0xFFFFFFFFL
      println(f"Read value: 0x$readValue%08X")
      assert(readValue == 0xDEADBEEFL, f"Expected 0xDEADBEEF, got 0x$readValue%08X")

      println("=== Test PASSED ===")
    }
  }

  test("BmbMemoryController: multiple writes and reads") {
    SimConfig.withWave.compile(BmbMemoryTestHarness()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Initialize inputs
      dut.io.memIn.rd #= false
      dut.io.memIn.wr #= false
      dut.io.memIn.addrWr #= false
      dut.io.memIn.bcRd #= false
      dut.io.memIn.stidx #= false
      dut.io.memIn.iaload #= false
      dut.io.memIn.iastore #= false
      dut.io.memIn.getfield #= false
      dut.io.memIn.putfield #= false
      dut.io.memIn.getstatic #= false
      dut.io.memIn.putstatic #= false
      dut.io.memIn.copy #= false
      dut.io.memIn.cinval #= false
      dut.io.aout #= 0
      dut.io.bout #= 0
      dut.io.bcopd #= 0
      dut.io.ioRdData #= 0

      dut.clockDomain.waitSampling(5)

      // Write test pattern to addresses 0-3
      val testData = Seq(0x11111111L, 0x22222222L, 0x33333333L, 0x44444444L)

      for ((data, addr) <- testData.zipWithIndex) {
        // Set address
        dut.io.aout #= addr
        dut.io.memIn.addrWr #= true
        dut.clockDomain.waitSampling()
        dut.io.memIn.addrWr #= false
        dut.clockDomain.waitSampling()

        // Write data
        dut.io.aout #= data
        dut.io.memIn.wr #= true
        dut.clockDomain.waitSampling()
        dut.io.memIn.wr #= false

        // Wait for state machine to start
        dut.clockDomain.waitSampling()

        // Wait for completion
        while (dut.io.debug.busy.toBoolean) {
          dut.clockDomain.waitSampling()
        }
        dut.clockDomain.waitSampling()
      }

      println("=== All writes complete ===")

      // Read back and verify
      for ((expected, addr) <- testData.zipWithIndex) {
        // Set address
        dut.io.aout #= addr
        dut.io.memIn.addrWr #= true
        dut.clockDomain.waitSampling()
        dut.io.memIn.addrWr #= false
        dut.clockDomain.waitSampling()

        // Read
        dut.io.memIn.rd #= true
        dut.clockDomain.waitSampling()
        dut.io.memIn.rd #= false

        // Wait for state machine to start
        dut.clockDomain.waitSampling()

        // Wait for completion
        while (dut.io.debug.busy.toBoolean) {
          dut.clockDomain.waitSampling()
        }
        dut.clockDomain.waitSampling()

        val readValue = dut.io.memOut.rdData.toLong & 0xFFFFFFFFL
        println(f"Address $addr: read 0x$readValue%08X, expected 0x$expected%08X")
        assert(readValue == expected, f"Mismatch at address $addr")
      }

      println("=== Test PASSED ===")
    }
  }
}
