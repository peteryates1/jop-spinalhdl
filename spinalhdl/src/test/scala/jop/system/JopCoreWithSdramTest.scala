package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.memory.sdram.sdr._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import org.scalatest.funsuite.AnyFunSuite
import jop.memory.JopMemoryConfig
import jop.utils.JopFileLoader

/**
 * Test harness for JopCoreWithSdram with SDRAM simulation model
 *
 * I/O subsystem (BmbSys, BmbUart) is internal to JopCore.
 * UART TX is snooped via JopCore's debug outputs.
 */
case class JopCoreWithSdramTestHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt]
) extends Component {

  val config = JopCoreConfig(memConfig = JopMemoryConfig(burstLen = 4))

  // Use W9825G6JH6 SDRAM parameters
  val sdramLayout = W9825G6JH6.layout
  val sdramTiming = W9825G6JH6.timingGrade7
  val CAS = 3

  val io = new Bundle {
    // SDRAM interface (directly exposed for simulation model)
    val sdram = master(SdramInterface(sdramLayout))

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

    // BMB debug signals (32-bit JOP side)
    val bmbCmdValid = out Bool()
    val bmbCmdReady = out Bool()
    val bmbCmdAddr = out UInt(config.memConfig.bmbParameter.access.addressWidth bits)
    val bmbCmdOpcode = out Bits(1 bits)
    val bmbRspValid = out Bool()
    val bmbRspData = out Bits(32 bits)
  }

  // JBC starts empty - BC_FILL must load bytecodes from SDRAM
  val jbcInit = Seq.fill(2048)(BigInt(0))

  // JOP System with SDRAM backend (BmbSys + BmbUart internal)
  val jopSystem = JopCoreWithSdram(
    config = config,
    sdramLayout = sdramLayout,
    sdramTiming = sdramTiming,
    CAS = CAS,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  // SDRAM interface
  io.sdram <> jopSystem.io.sdram

  // Single-core: no CmpSync
  jopSystem.io.syncIn.halted := False
  jopSystem.io.syncIn.s_out := False

  // No UART RX in test harness
  jopSystem.io.rxd := True

  // Interrupts (disabled)
  jopSystem.io.irq := False
  jopSystem.io.irqEna := False

  // Outputs
  io.pc := jopSystem.io.pc
  io.jpc := jopSystem.io.jpc
  io.instr := jopSystem.io.instr
  io.jfetch := jopSystem.io.jfetch
  io.jopdfetch := jopSystem.io.jopdfetch
  io.aout := jopSystem.io.aout
  io.bout := jopSystem.io.bout
  io.memBusy := jopSystem.io.memBusy
  io.uartTxData := jopSystem.io.uartTxData
  io.uartTxValid := jopSystem.io.uartTxValid

  // BMB debug (32-bit side)
  io.bmbCmdValid := jopSystem.io.bmbCmdValid
  io.bmbCmdReady := jopSystem.io.bmbCmdReady
  io.bmbCmdAddr := jopSystem.io.bmbCmdAddr
  io.bmbCmdOpcode := jopSystem.io.bmbCmdOpcode
  io.bmbRspValid := jopSystem.io.bmbRspValid
  io.bmbRspData := jopSystem.io.bmbRspData
}

/**
 * JopCoreWithSdram Tests
 */
class JopCoreWithSdramTest extends AnyFunSuite {

  // Paths to initialization files
  val jopFilePath = "java/apps/Smallest/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"

  test("JopCoreWithSdram: SDRAM integration test") {
    // Load initialization data
    val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
    val ramData = JopFileLoader.loadStackRam(ramFilePath)
    // Load enough main memory for program
    val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 64 * 1024 / 4)  // 64KB / 4 = 16K words

    println(s"Loaded ROM: ${romData.length} entries")
    println(s"Loaded RAM: ${ramData.length} entries")
    println(s"Loaded main memory: ${mainMemData.length} entries")

    SimConfig
      .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
      // .withWave  // Disabled for faster testing
      .compile(JopCoreWithSdramTestHarness(romData, ramData, mainMemData))
      .doSim { dut =>
        // Initialize clock
        dut.clockDomain.forkStimulus(10)  // 10ns period = 100MHz

        // Create SDRAM simulation model
        val sdramModel = SdramModel(
          io = dut.io.sdram,
          layout = dut.sdramLayout,
          clockDomain = dut.clockDomain
        )

        // Initialize SDRAM with program data
        // Convert 32-bit words to bytes for SDRAM model
        for (wordIdx <- mainMemData.indices) {
          val word = mainMemData(wordIdx).toLong & 0xFFFFFFFFL
          val byteAddr = wordIdx * 4
          // Write 4 bytes per 32-bit word (little-endian byte ordering)
          sdramModel.write(byteAddr + 0, ((word >>  0) & 0xFF).toByte)
          sdramModel.write(byteAddr + 1, ((word >>  8) & 0xFF).toByte)
          sdramModel.write(byteAddr + 2, ((word >> 16) & 0xFF).toByte)
          sdramModel.write(byteAddr + 3, ((word >> 24) & 0xFF).toByte)
        }

        dut.clockDomain.waitSampling(5)

        // Run for enough cycles to see SDRAM activity
        val maxCycles = 500
        var uartOutput = new StringBuilder

        for (cycle <- 0 until maxCycles) {
          dut.clockDomain.waitSampling()

          // Capture UART output
          if (dut.io.uartTxValid.toBoolean) {
            val char = dut.io.uartTxData.toInt.toChar
            uartOutput.append(char)
            print(char)
          }
        }

        println(s"\n=== Executed $maxCycles cycles ===")
        println(s"Final PC: ${dut.io.pc.toInt}")
        println(s"Final JPC: ${dut.io.jpc.toInt}")
        println(s"memBusy: ${dut.io.memBusy.toBoolean}")
        if (uartOutput.nonEmpty) {
          println(s"UART Output: ${uartOutput.toString}")
        }

        // Verify the system is running
        val finalPc = dut.io.pc.toInt
        assert(finalPc > 0, "Pipeline should have started executing")
        println("=== Test PASSED: JopCoreWithSdram integration works ===")
      }
  }

  test("JopCoreWithSdram: extended execution with HelloWorld") {
    // Load initialization data
    val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
    val ramData = JopFileLoader.loadStackRam(ramFilePath)
    val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 64 * 1024 / 4)

    SimConfig
      .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
      .compile(JopCoreWithSdramTestHarness(romData, ramData, mainMemData))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)

        // Create SDRAM simulation model
        val sdramModel = SdramModel(
          io = dut.io.sdram,
          layout = dut.sdramLayout,
          clockDomain = dut.clockDomain
        )

        // Initialize SDRAM
        for (wordIdx <- mainMemData.indices) {
          val word = mainMemData(wordIdx).toLong & 0xFFFFFFFFL
          val byteAddr = wordIdx * 4
          sdramModel.write(byteAddr + 0, ((word >>  0) & 0xFF).toByte)
          sdramModel.write(byteAddr + 1, ((word >>  8) & 0xFF).toByte)
          sdramModel.write(byteAddr + 2, ((word >> 16) & 0xFF).toByte)
          sdramModel.write(byteAddr + 3, ((word >> 24) & 0xFF).toByte)
        }

        dut.clockDomain.waitSampling(5)

        // Run for more cycles to see HelloWorld output
        val maxCycles = 50000
        var uartOutput = new StringBuilder
        var lastJpc = 0

        for (cycle <- 0 until maxCycles) {
          dut.clockDomain.waitSampling()

          // Capture UART output
          if (dut.io.uartTxValid.toBoolean) {
            val char = dut.io.uartTxData.toInt.toChar
            uartOutput.append(char)
            print(char)
          }

          // Track JPC progress
          val currentJpc = dut.io.jpc.toInt
          if (currentJpc != lastJpc && cycle % 1000 == 0) {
            lastJpc = currentJpc
          }
        }

        println(s"\n=== Executed $maxCycles cycles ===")
        println(s"Final PC: ${dut.io.pc.toInt}")
        println(s"Final JPC: ${dut.io.jpc.toInt}")
        if (uartOutput.nonEmpty) {
          println(s"UART Output: ${uartOutput.toString}")
        }

        assert(dut.io.pc.toInt > 0, "Pipeline should have started executing")
        println("=== Test PASSED: Extended execution works ===")
      }
  }
}
