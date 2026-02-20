package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import org.scalatest.funsuite.AnyFunSuite
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import jop.io.BmbSys

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

    // UART output
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

  // Decode I/O address
  val ioSubAddr = jopCore.io.ioAddr(3 downto 0)
  val ioSlaveId = jopCore.io.ioAddr(5 downto 4)

  // System I/O (slave 0) — real BmbSys component
  val bmbSys = BmbSys(clkFreqHz = 100000000L)
  bmbSys.io.addr   := ioSubAddr
  bmbSys.io.rd     := jopCore.io.ioRd && ioSlaveId === 0
  bmbSys.io.wr     := jopCore.io.ioWr && ioSlaveId === 0
  bmbSys.io.wrData := jopCore.io.ioWrData

  // Exception signal from BmbSys
  jopCore.io.exc := bmbSys.io.exc

  // Snoop exception writes for simulation debug output
  val excTypeSnoop = Reg(Bits(8 bits)) init(0)
  when(jopCore.io.ioWr && ioSlaveId === 0 && ioSubAddr === 4) {
    excTypeSnoop := jopCore.io.ioWrData(7 downto 0)
  }

  // UART (slave 1) — simplified for simulation (no bit-serial timing)
  val uartTxDataReg = Reg(Bits(8 bits)) init(0)
  val uartTxValidReg = Reg(Bool()) init(False)

  uartTxValidReg := False
  when(jopCore.io.ioWr && ioSlaveId === 1 && ioSubAddr === 1) {
    uartTxDataReg := jopCore.io.ioWrData(7 downto 0)
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
  jopCore.io.ioRdData := ioRdData

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
  io.excFired := bmbSys.io.exc
  io.excType := excTypeSnoop
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
