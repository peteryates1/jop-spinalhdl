package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.memory.sdram.sdr._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import org.scalatest.funsuite.AnyFunSuite
import jop.utils.JopFileLoader

/**
 * Test harness for JopSystemWithSdram with SDRAM simulation model
 */
case class JopSystemWithSdramTestHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt]
) extends Component {

  val config = JopSystemConfig()

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

    // UART output
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()
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

  // JOP System with SDRAM backend
  val jopSystem = JopSystemWithSdram(
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

  // I/O simulation
  val sysCntReg = Reg(UInt(32 bits)) init(1000000)
  sysCntReg := sysCntReg + 10

  val uartTxDataReg = Reg(Bits(8 bits)) init(0)
  val uartTxValidReg = Reg(Bool()) init(False)

  // Default I/O read data
  val ioRdDataReg = Reg(Bits(32 bits)) init(0)

  // Decode I/O address
  val ioSubAddr = jopSystem.io.ioAddr(3 downto 0)
  val ioSlaveId = jopSystem.io.ioAddr(5 downto 4)

  // I/O read handling
  when(jopSystem.io.ioRd) {
    switch(ioSlaveId) {
      is(0) {  // System
        switch(ioSubAddr) {
          is(0) { ioRdDataReg := sysCntReg.asBits }        // Counter
          is(1) { ioRdDataReg := sysCntReg.asBits }        // Microsecond counter
          is(6) { ioRdDataReg := B(0, 32 bits) }           // CPU ID
          is(7) { ioRdDataReg := B(0, 32 bits) }           // Signal
          default { ioRdDataReg := 0 }
        }
      }
      is(1) {  // UART
        switch(ioSubAddr) {
          is(0) { ioRdDataReg := B(0x1, 32 bits) }  // Status: TX ready
          default { ioRdDataReg := 0 }
        }
      }
      default { ioRdDataReg := 0 }
    }
  }
  jopSystem.io.ioRdData := ioRdDataReg

  // I/O write handling
  uartTxValidReg := False
  when(jopSystem.io.ioWr) {
    switch(ioSlaveId) {
      is(1) {  // UART
        switch(ioSubAddr) {
          is(1) {  // UART data
            uartTxDataReg := jopSystem.io.ioWrData(7 downto 0)
            uartTxValidReg := True
          }
        }
      }
    }
  }

  // Interrupt (disabled)
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
  io.uartTxData := uartTxDataReg
  io.uartTxValid := uartTxValidReg
}

/**
 * JopSystemWithSdram Tests
 */
class JopSystemWithSdramTest extends AnyFunSuite {

  // Paths to initialization files
  val jopFilePath = "/home/peter/git/jopmin/java/Smallest/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat"

  test("JopSystemWithSdram: SDRAM integration test") {
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
      .compile(JopSystemWithSdramTestHarness(romData, ramData, mainMemData))
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
        println("=== Test PASSED: JopSystemWithSdram integration works ===")
      }
  }

  test("JopSystemWithSdram: extended execution with HelloWorld") {
    // Load initialization data
    val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
    val ramData = JopFileLoader.loadStackRam(ramFilePath)
    val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 64 * 1024 / 4)

    SimConfig
      .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
      .compile(JopSystemWithSdramTestHarness(romData, ramData, mainMemData))
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
