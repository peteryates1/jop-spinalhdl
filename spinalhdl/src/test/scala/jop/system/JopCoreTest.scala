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
 */
case class JopCoreTestHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt]
) extends Component {

  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = 32 * 1024)  // 32KB for faster simulation
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

    // UART output
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()

    // Debug
    val debugState = out UInt(4 bits)
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

  // JOP System core
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

  // I/O simulation
  val sysCntReg = Reg(UInt(32 bits)) init(1000000)
  sysCntReg := sysCntReg + 10

  val uartTxDataReg = Reg(Bits(8 bits)) init(0)
  val uartTxValidReg = Reg(Bool()) init(False)

  // I/O read data - COMBINATIONAL (must be available same cycle as ioRd)
  val ioRdData = Bits(32 bits)
  ioRdData := 0  // default

  // Decode I/O address
  val ioSubAddr = jopCore.io.ioAddr(3 downto 0)
  val ioSlaveId = jopCore.io.ioAddr(5 downto 4)

  // I/O read handling - combinational response
  switch(ioSlaveId) {
    is(0) {  // System
      switch(ioSubAddr) {
        is(0) { ioRdData := sysCntReg.asBits }        // Counter
        is(1) { ioRdData := sysCntReg.asBits }        // Microsecond counter
        is(6) { ioRdData := B(0, 32 bits) }           // CPU ID
        is(7) { ioRdData := B(0, 32 bits) }           // Signal
      }
    }
    is(1) {  // UART
      switch(ioSubAddr) {
        is(0) { ioRdData := B(0x1, 32 bits) }  // Status: TX ready
      }
    }
  }
  jopCore.io.ioRdData := ioRdData

  // I/O write handling
  uartTxValidReg := False
  when(jopCore.io.ioWr) {
    switch(ioSlaveId) {
      is(1) {  // UART
        switch(ioSubAddr) {
          is(1) {  // UART data
            uartTxDataReg := jopCore.io.ioWrData(7 downto 0)
            uartTxValidReg := True
          }
        }
      }
    }
  }

  // Interrupt (disabled for now)
  jopCore.io.irq := False
  jopCore.io.irqEna := False

  // Outputs
  io.pc := jopCore.io.pc
  io.jpc := jopCore.io.jpc
  io.instr := jopCore.io.instr
  io.jfetch := jopCore.io.jfetch
  io.jopdfetch := jopCore.io.jopdfetch
  io.aout := jopCore.io.aout
  io.bout := jopCore.io.bout
  io.memBusy := jopCore.io.memBusy
  io.uartTxData := uartTxDataReg
  io.uartTxValid := uartTxValidReg
  io.debugState := 0  // Placeholder - internal signal not accessible
}

/**
 * JopCore Tests
 */
class JopCoreTest extends AnyFunSuite {

  // Paths to initialization files
  val jopFilePath = "/home/peter/git/jopmin/java/Smallest/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat"

  test("JopCore: basic execution with BMB memory") {
    // Load initialization data
    val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
    val ramData = JopFileLoader.loadStackRam(ramFilePath)
    val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 32 * 1024 / 4)  // 32KB / 4 = 8K words

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
