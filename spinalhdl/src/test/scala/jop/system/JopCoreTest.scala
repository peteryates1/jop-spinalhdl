package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import org.scalatest.funsuite.AnyFunSuite
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig

/**
 * Test harness for JopCore with BmbOnChipRam
 * Uses default SpinalHDL clock domain for simpler simulation.
 *
 * I/O subsystem (BmbSys, BmbUart) is internal to JopCore.
 * UART TX is snooped via JopCore's debug outputs.
 */
case class JopCoreTestHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt]
) extends Component {

  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = 128 * 1024)  // 128KB: room for program + GC heap
  )

  val io = new Bundle {
    // Pipeline outputs
    val pc = out UInt(config.pcWidth bits)
    val jpc = out UInt((config.jpcWidth + 1) bits)
    val instr = out Bits(config.instrWidth bits)
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    // Stack outputs
    val aout = out Bits(config.dataWidth bits)
    val bout = out Bits(config.dataWidth bits)

    // Memory status
    val memBusy = out Bool()

    // UART output (from JopCore debug snoop)
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()

    // Debug
    val debugState = out UInt(4 bits)

    // Exception debug
    val excFired = out Bool()
    val excType = out Bits(8 bits)
  }

  // Extract JBC init from main memory
  val mpAddr = if (mainMemInit.length > 1) mainMemInit(1).toInt else 0
  val bootMethodStructAddr = if (mainMemInit.length > mpAddr) mainMemInit(mpAddr).toInt else 0
  val bootMethodStartLen = if (mainMemInit.length > bootMethodStructAddr) mainMemInit(bootMethodStructAddr).toLong else 0
  val bootCodeStart = (bootMethodStartLen >> 10).toInt
  val bytecodeStartWord = if (bootCodeStart > 0) bootCodeStart else 35
  val bytecodeWords = mainMemInit.slice(bytecodeStartWord, bytecodeStartWord + 512)

  // Convert words to bytes (big-endian)
  val jbcInit = bytecodeWords.flatMap { word =>
    val w = word.toLong & 0xFFFFFFFFL
    Seq(
      BigInt((w >> 24) & 0xFF),
      BigInt((w >> 16) & 0xFF),
      BigInt((w >> 8) & 0xFF),
      BigInt((w >> 0) & 0xFF)
    )
  }.padTo(2048, BigInt(0))

  // JOP System core (BmbSys + BmbUart internal)
  val jopCore = JopCore(
    config = config,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  // Block RAM with BMB interface
  val ram = BmbOnChipRam(
    p = config.memConfig.bmbParameter,
    size = config.memConfig.mainMemSize,
    hexInit = null
  )

  // Initialize RAM (limit to actual memory size)
  val memWords = config.memConfig.mainMemWords.toInt
  val initData = mainMemInit.take(memWords).padTo(memWords, BigInt(0))
  ram.ram.init(initData.map(v => B(v, 32 bits)))

  // Connect BMB
  ram.io.bus << jopCore.io.bmb

  // Single-core: no CmpSync
  jopCore.io.syncIn.halted := False
  jopCore.io.syncIn.s_out := False

  // No UART RX in test harness
  jopCore.io.rxd := True

  // Tie unused debug inputs
  jopCore.io.debugRamAddr := 0
  jopCore.io.debugHalt := False

  // SimPublic for I/O tracing
  jopCore.memCtrl.io.ioRd.simPublic()
  jopCore.memCtrl.io.ioWr.simPublic()
  jopCore.memCtrl.io.ioAddr.simPublic()
  jopCore.memCtrl.io.ioWrData.simPublic()
  jopCore.memCtrl.io.ioRdData.simPublic()
  jopCore.io.debugIoRdCount.simPublic()
  jopCore.io.debugIoWrCount.simPublic()

  // Outputs
  io.pc := jopCore.io.pc
  io.jpc := jopCore.io.jpc
  io.instr := jopCore.io.instr
  io.jfetch := jopCore.io.jfetch
  io.jopdfetch := jopCore.io.jopdfetch
  io.aout := jopCore.io.aout
  io.bout := jopCore.io.bout
  io.memBusy := jopCore.io.memBusy
  io.uartTxData := jopCore.io.uartTxData
  io.uartTxValid := jopCore.io.uartTxValid
  io.debugState := 0  // Placeholder - internal signal not accessible
  io.excFired := jopCore.io.debugExc
  io.excType := 0  // Exception type not easily snooped with internal I/O
}

/**
 * JopCore Tests
 */
class JopCoreTest extends AnyFunSuite {

  // Paths to initialization files
  val jopFilePath = "java/apps/Smallest/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"

  test("JopCore: basic execution with BMB memory") {
    // Load initialization data
    val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
    val ramData = JopFileLoader.loadStackRam(ramFilePath)
    val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)  // 128KB / 4 = 32K words

    println(s"Loaded ROM: ${romData.length} entries")
    println(s"Loaded RAM: ${ramData.length} entries")
    println(s"Loaded main memory: ${mainMemData.length} entries")

    SimConfig
      // .withWave  // Disabled for faster testing
      .compile(JopCoreTestHarness(romData, ramData, mainMemData))
      .doSim { dut =>
        // Initialize clock
        dut.clockDomain.forkStimulus(10)  // 10ns period

        dut.clockDomain.waitSampling(5)

        // Run for a small number of cycles to verify integration
        val maxCycles = 100

        for (cycle <- 0 until maxCycles) {
          dut.clockDomain.waitSampling()
        }

        println(s"=== Executed $maxCycles cycles ===")
        println(s"Final PC: ${dut.io.pc.toInt}")
        println(s"Final JPC: ${dut.io.jpc.toInt}")
        println(s"memBusy: ${dut.io.memBusy.toBoolean}")
        println(s"debugState: ${dut.io.debugState.toInt}")

        // Verify the system is running (PC should have changed from initial value)
        val finalPc = dut.io.pc.toInt
        assert(finalPc > 0, "Pipeline should have started executing")
        println("=== Test PASSED: JopCore integration works ===")
      }
  }
}
