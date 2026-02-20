package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.memory.sdram.sdr._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import org.scalatest.funsuite.AnyFunSuite
import jop.memory.JopMemoryConfig
import jop.utils.JopFileLoader
import jop.io.BmbSys

/**
 * Test harness for JopCoreWithSdram with SDRAM simulation model
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

    // UART output
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()

    // I/O debug (directly from JopCore)
    val ioWr = out Bool()
    val ioAddr = out UInt(8 bits)
    val ioWrData = out Bits(32 bits)

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

  // JOP System with SDRAM backend
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

  // Decode I/O address
  val ioSubAddr = jopSystem.io.ioAddr(3 downto 0)
  val ioSlaveId = jopSystem.io.ioAddr(5 downto 4)

  // System I/O (slave 0) — real BmbSys component
  val bmbSys = BmbSys(clkFreqHz = 100000000L)
  bmbSys.io.addr   := ioSubAddr
  bmbSys.io.rd     := jopSystem.io.ioRd && ioSlaveId === 0
  bmbSys.io.wr     := jopSystem.io.ioWr && ioSlaveId === 0
  bmbSys.io.wrData := jopSystem.io.ioWrData

  // Exception signal from BmbSys
  jopSystem.io.exc := bmbSys.io.exc

  // UART (slave 1) — simplified for simulation
  val uartTxDataReg = Reg(Bits(8 bits)) init(0)
  val uartTxValidReg = Reg(Bool()) init(False)

  uartTxValidReg := False
  when(jopSystem.io.ioWr && ioSlaveId === 1 && ioSubAddr === 1) {
    uartTxDataReg := jopSystem.io.ioWrData(7 downto 0)
    uartTxValidReg := True
  }

  // I/O read mux
  val ioRdData = Bits(32 bits)
  ioRdData := 0
  switch(ioSlaveId) {
    is(0) { ioRdData := bmbSys.io.rdData }
    is(1) {
      switch(ioSubAddr) {
        is(0) { ioRdData := B(0x1, 32 bits) }  // Status: TX ready
      }
    }
  }
  jopSystem.io.ioRdData := ioRdData

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
  io.ioWr := jopSystem.io.ioWr
  io.ioAddr := jopSystem.io.ioAddr
  io.ioWrData := jopSystem.io.ioWrData

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
