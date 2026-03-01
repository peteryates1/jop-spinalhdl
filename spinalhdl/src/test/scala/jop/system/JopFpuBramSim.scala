package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import jop.pipeline.JumpTableInitData
import java.io.PrintWriter

/**
 * Test harness for JopCore with HW FPU + BmbOnChipRam.
 *
 * Same as JopCoreTestHarness but with:
 *   - fpuMode = Hardware (BmbFpu with pure SpinalHDL FPU)
 *   - FPU jump table (simulationFpu — float ops → microcode FPU handlers)
 *   - FPU microcode ROM/RAM (built with FPU_ATTACHED)
 */
case class JopFpuTestHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt],
  memSize: Int = 256 * 1024
) extends Component {

  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = memSize),
    jumpTable = JumpTableInitData.simulationFpu,
    fpuMode = FpuMode.Hardware
  )

  val io = new Bundle {
    val pc = out UInt(config.pcWidth bits)
    val jpc = out UInt((config.jpcWidth + 1) bits)
    val aout = out Bits(config.dataWidth bits)
    val bout = out Bits(config.dataWidth bits)
    val memBusy = out Bool()
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()
    val excFired = out Bool()
    // FPU debug ports
    val fpuOpA = out Bits(32 bits)
    val fpuOpB = out Bits(32 bits)
    val fpuOpCode = out UInt(2 bits)
    val fpuResult = out Bits(32 bits)
    val fpuStart = out Bool()
  }

  // Extract JBC init from main memory
  val mpAddr = if (mainMemInit.length > 1) mainMemInit(1).toInt else 0
  val bootMethodStructAddr = if (mainMemInit.length > mpAddr) mainMemInit(mpAddr).toInt else 0
  val bootMethodStartLen = if (mainMemInit.length > bootMethodStructAddr) mainMemInit(bootMethodStructAddr).toLong else 0
  val bootCodeStart = (bootMethodStartLen >> 10).toInt
  val bytecodeStartWord = if (bootCodeStart > 0) bootCodeStart else 35
  val bytecodeWords = mainMemInit.slice(bytecodeStartWord, bytecodeStartWord + 512)

  val jbcInit = bytecodeWords.flatMap { word =>
    val w = word.toLong & 0xFFFFFFFFL
    Seq(
      BigInt((w >> 24) & 0xFF),
      BigInt((w >> 16) & 0xFF),
      BigInt((w >> 8) & 0xFF),
      BigInt((w >> 0) & 0xFF)
    )
  }.padTo(2048, BigInt(0))

  // JOP core with HW FPU enabled
  val jopCore = JopCore(
    config = config,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  // Block RAM
  val ram = BmbOnChipRam(
    p = config.memConfig.bmbParameter,
    size = config.memConfig.mainMemSize,
    hexInit = null
  )

  val memWords = config.memConfig.mainMemWords.toInt
  val initData = mainMemInit.take(memWords).padTo(memWords, BigInt(0))
  ram.ram.init(initData.map(v => B(v, 32 bits)))

  ram.io.bus << jopCore.io.bmb

  // Tie-offs (single-core, no external connections)
  jopCore.io.syncIn.halted := False
  jopCore.io.syncIn.s_out := False
  jopCore.io.syncIn.status := False
  jopCore.io.rxd := True
  jopCore.io.debugRamAddr := 0
  jopCore.io.debugHalt := False
  jopCore.io.snoopIn.foreach { si =>
    si.valid := False; si.isArray := False; si.handle := 0; si.index := 0
  }

  // Outputs
  io.pc := jopCore.io.pc
  io.jpc := jopCore.io.jpc
  io.aout := jopCore.io.aout
  io.bout := jopCore.io.bout
  io.memBusy := jopCore.io.memBusy
  io.uartTxData := jopCore.io.uartTxData
  io.uartTxValid := jopCore.io.uartTxValid
  io.excFired := jopCore.io.debugExc

  // FPU debug wiring (through proper port hierarchy)
  io.fpuOpA    := jopCore.io.fpuDbgOpA.get
  io.fpuOpB    := jopCore.io.fpuDbgOpB.get
  io.fpuOpCode := jopCore.io.fpuDbgOpCode.get
  io.fpuResult := jopCore.io.fpuDbgResult.get
  io.fpuStart  := jopCore.io.fpuDbgStart.get
}

/**
 * BRAM simulation running JVM test suite with HW FPU enabled.
 *
 * Uses JvmTests/DoAll.jop which includes FloatTest, FloatArray, and DoubleArithmetic.
 * Float operations (fadd/fsub/fmul/fdiv) go through the HW FPU via microcode;
 * double operations still use SoftFloat64 (software path).
 *
 * This verifies that the HW FPU produces correct IEEE 754 results
 * for all float operations exercised by the test suite.
 *
 * Uses FPU simulation microcode ROM/RAM (built with SIMULATION + FPU_ATTACHED).
 *
 * Run: sbt "Test / runMain jop.system.JopFpuBramSim"
 */
object JopFpuBramSim extends App {

  val jopFilePath = "java/apps/JvmTests/DoAll.jop"
  val romFilePath = "asm/generated/fpu/mem_rom.dat"
  val ramFilePath = "asm/generated/fpu/mem_ram.dat"
  val logFilePath = "spinalhdl/fpu_bram_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 256 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries (FPU microcode)")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")
  println("FPU mode: Hardware (SpinalHDL FPU)")

  def bitsFloat(b: Long): Float = java.lang.Float.intBitsToFloat((b & 0xFFFFFFFFL).toInt)
  val opNames = Array("ADD", "SUB", "MUL", "DIV")

  SimConfig
    .compile(JopFpuTestHarness(romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder
      var lineBuffer = new StringBuilder
      var fpuOpCount = 0

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JOP FPU BRAM Simulation (HW FPU) ===")

      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      val maxCycles = 27000000
      val reportInterval = 500000

      for (cycle <- 0 until maxCycles) {
        dut.clockDomain.waitSampling()

        // Count FPU operations via debug start pulse
        if (dut.io.fpuStart.toBoolean) {
          val opA = dut.io.fpuOpA.toLong & 0xFFFFFFFFL
          val opB = dut.io.fpuOpB.toLong & 0xFFFFFFFFL
          val opCode = dut.io.fpuOpCode.toInt
          val opName = if (opCode < 4) opNames(opCode) else s"?$opCode"
          fpuOpCount += 1
          val msg = f"FPU #$fpuOpCount%3d [$cycle%8d]: $opName opA=0x${opA}%08X(${bitsFloat(opA)}%.6f) opB=0x${opB}%08X(${bitsFloat(opB)}%.6f)"
          logLine(msg)
        }

        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          if (char == 10) {
            val line = lineBuffer.toString
            println(line)
            logLine(f"[$cycle%7d] $line")
            lineBuffer.clear()
          } else if (char >= 32 && char < 127) {
            lineBuffer.append(char.toChar)
          }
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '\n')
        }

        if (cycle > 0 && cycle % reportInterval == 0) {
          println(f"  [$cycle%7d cycles] (FPU ops: $fpuOpCount)")
        }
      }

      if (lineBuffer.nonEmpty) {
        println(lineBuffer.toString)
        logLine(f"[$maxCycles%7d] ${lineBuffer.toString}")
      }

      logLine("")
      logLine("=== Simulation Complete ===")
      logLine(s"UART Output:\n${uartOutput.toString}")
      log.close()

      println(s"\n=== FPU Simulation Complete ($maxCycles cycles) ===")
      println(s"Total FPU operations: $fpuOpCount")
      println(s"Full output:\n${uartOutput.toString}")
      println(s"Log: $logFilePath")
    }
}
