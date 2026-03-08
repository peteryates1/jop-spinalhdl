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
 * Parameterized test harness for min/max config comparison.
 */
case class JopConfigTestHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt],
  coreConfig: JopCoreConfig,
  memSize: Int = 256 * 1024
) extends Component {

  val io = new Bundle {
    val pc = out UInt(coreConfig.pcWidth bits)
    val jpc = out UInt((coreConfig.jpcWidth + 1) bits)
    val aout = out Bits(coreConfig.dataWidth bits)
    val bout = out Bits(coreConfig.dataWidth bits)
    val memBusy = out Bool()
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()
    val excFired = out Bool()
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

  val jopCore = JopCore(
    config = coreConfig,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  val ram = BmbOnChipRam(
    p = coreConfig.memConfig.bmbParameter,
    size = coreConfig.memConfig.mainMemSize,
    hexInit = null
  )

  val memWords = coreConfig.memConfig.mainMemWords.toInt
  val initData = mainMemInit.take(memWords).padTo(memWords, BigInt(0))
  ram.ram.init(initData.map(v => B(v, 32 bits)))

  ram.io.bus << jopCore.io.bmb

  jopCore.io.syncIn.halted := False
  jopCore.io.syncIn.s_out := False
  jopCore.io.syncIn.status := False
  jopCore.io.rxd := True
  jopCore.io.debugRamAddr := 0
  jopCore.io.debugHalt := False
  jopCore.io.snoopIn.foreach { si =>
    si.valid := False; si.isArray := False; si.handle := 0; si.index := 0
  }

  io.pc := jopCore.io.pc
  io.jpc := jopCore.io.jpc
  io.aout := jopCore.io.aout
  io.bout := jopCore.io.bout
  io.memBusy := jopCore.io.memBusy
  io.uartTxData := jopCore.io.uartTxData
  io.uartTxValid := jopCore.io.uartTxValid
  io.excFired := jopCore.io.debugExc
}

/**
 * Run a JVM test suite sim and report per-test cycle counts.
 */
object JopMinMaxSim {
  def runSim(label: String, coreConfig: JopCoreConfig, maxCycles: Int = 60000000): Unit = {
    val jopFilePath = "java/apps/JvmTests/DoAll.jop"
    val romFilePath = "asm/generated/mem_rom.dat"
    val ramFilePath = "asm/generated/mem_ram.dat"
    val logFilePath = s"spinalhdl/${label}_comparison.log"

    val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
    val ramData = JopFileLoader.loadStackRam(ramFilePath)
    val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 256 * 1024 / 4)

    println(s"=== $label ===")
    println(s"  needsIntegerCompute: ${coreConfig.needsIntegerCompute}")
    println(s"  needsFloatCompute: ${coreConfig.needsFloatCompute}")

    SimConfig
      .compile(JopConfigTestHarness(romData, ramData, mainMemData, coreConfig))
      .doSim { dut =>
        val log = new PrintWriter(logFilePath)
        var uartOutput = new StringBuilder
        var lineBuffer = new StringBuilder
        var testResults = new scala.collection.mutable.ArrayBuffer[(String, Int)]()
        var lastTestCycle = 0

        def logLine(msg: String): Unit = {
          log.println(msg)
          log.flush()
        }

        logLine(s"=== $label Simulation ===")

        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitSampling(5)

        val reportInterval = 1000000

        for (cycle <- 0 until maxCycles) {
          dut.clockDomain.waitSampling()

          if (dut.io.uartTxValid.toBoolean) {
            val char = dut.io.uartTxData.toInt
            if (char == 10) {
              val line = lineBuffer.toString
              println(line)
              logLine(f"[$cycle%8d] $line")

              // Track per-test timing
              if (line.startsWith("T") && (line.endsWith("Ok") || line.endsWith("FAIL"))) {
                testResults += ((line, cycle - lastTestCycle))
                lastTestCycle = cycle
              } else if (line.startsWith("GC") || line.startsWith("CI") || line.startsWith("OK") || line.startsWith("M0")) {
                lastTestCycle = cycle
              }

              lineBuffer.clear()
            } else if (char >= 32 && char < 127) {
              lineBuffer.append(char.toChar)
            }
            uartOutput.append(if (char >= 32 && char < 127) char.toChar else '\n')
          }

          if (cycle > 0 && cycle % reportInterval == 0) {
            println(f"  [$cycle%8d cycles]")
          }
        }

        if (lineBuffer.nonEmpty) {
          println(lineBuffer.toString)
          logLine(f"[$maxCycles%8d] ${lineBuffer.toString}")
        }

        logLine("")
        logLine(s"=== $label Complete ($maxCycles cycles) ===")
        logLine("")
        logLine("Per-test cycle counts:")
        testResults.foreach { case (name, cycles) =>
          logLine(f"  $name%-40s $cycles%8d cycles")
        }
        log.close()

        println(s"\n=== $label Complete ===")
        println("Per-test cycle counts:")
        testResults.foreach { case (name, cycles) =>
          println(f"  $name%-40s $cycles%8d cycles")
        }
      }
  }
}

/**
 * MINIMUM config: all bytecodes fall through to Java software.
 * No IntegerComputeUnit, no FloatComputeUnit.
 */
object JopMinBramSim extends App {
  // TRUE minimum: no IntCU, no FloatCU. Uses bare microcode ROM with
  // pure-microcode shift-and-add imul (no HW_MUL flag).
  // All float/div/rem ops handled via JOPizer→SoftFloat (IMP_JAVA).
  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = 256 * 1024),
    supersetJumpTable = JumpTableInitData.bareSimulation,
    imul  = Implementation.Java,
    idiv  = Implementation.Java,
    irem  = Implementation.Java,
    fadd  = Implementation.Java,
    fsub  = Implementation.Java,
    fmul  = Implementation.Java,
    fdiv  = Implementation.Java,
    fneg  = Implementation.Java,
    i2f   = Implementation.Java,
    f2i   = Implementation.Java,
    fcmpl = Implementation.Java,
    fcmpg = Implementation.Java
  )
  JopMinMaxSim.runSim("MINIMUM", config)
}

/**
 * MAXIMUM config: all bytecodes use hardware acceleration.
 * IntegerComputeUnit (imul/idiv/irem) + FloatComputeUnit (fadd..fcmpg).
 */
object JopMaxBramSim extends App {
  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = 256 * 1024),
    supersetJumpTable = JumpTableInitData.simulation,
    imul  = Implementation.Microcode,  // sthw → IntegerComputeUnit radix-4 multiply
    idiv  = Implementation.Hardware,   // sthw → IntegerComputeUnit binary restoring div
    irem  = Implementation.Hardware,   // sthw → IntegerComputeUnit binary restoring rem
    fadd  = Implementation.Hardware,
    fsub  = Implementation.Hardware,
    fmul  = Implementation.Hardware,
    fdiv  = Implementation.Hardware,
    fneg  = Implementation.Hardware,   // microcode XOR sign bit
    i2f   = Implementation.Hardware,
    f2i   = Implementation.Hardware,
    fcmpl = Implementation.Hardware,
    fcmpg = Implementation.Hardware
  )
  JopMinMaxSim.runSim("MAXIMUM", config)
}

