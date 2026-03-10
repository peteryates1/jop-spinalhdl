package jop.system

import spinal.core._
import spinal.core.sim._
import jop.config._
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import java.io.PrintWriter

/**
 * Config-driven BRAM simulation.
 *
 * Takes a JopConfig, extracts boot mode paths and core config,
 * loads ROM/RAM/app, and runs the sim.
 */
object JopConfigBramSim {

  def runSim(
    jopConfig: JopConfig,
    jopFilePath: String,
    maxCycles: Int = 2000000,
    logFilePath: String = "spinalhdl/config_bram_simulation.log"
  ): Unit = {
    val sys = jopConfig.system
    val coreConfig = sys.coreConfig.copy(
      memConfig = JopMemoryConfig(mainMemSize = 256 * 1024),
      supersetJumpTable = sys.baseJumpTable
    )

    val romData = JopFileLoader.loadMicrocodeRom(sys.romPath)
    val ramData = JopFileLoader.loadStackRam(sys.ramPath)
    val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 256 * 1024 / 4)

    println(s"=== ${sys.name} (${sys.bootMode}) ===")
    println(s"  ROM: ${sys.romPath} (${romData.length} entries)")
    println(s"  RAM: ${sys.ramPath} (${ramData.length} entries)")
    println(s"  App: $jopFilePath (${mainMemData.length} words)")
    println(s"  imul=${coreConfig.imul}, idiv=${coreConfig.idiv}, irem=${coreConfig.irem}")
    println(s"  needsIntegerCompute=${coreConfig.needsIntegerCompute}, needsFloatCompute=${coreConfig.needsFloatCompute}")

    SimConfig
      .compile(JopCoreTestHarness(romData, ramData, mainMemData, coreConfig = Some(coreConfig)))
      .doSim { dut =>
        val log = new PrintWriter(logFilePath)
        var uartOutput = new StringBuilder
        var lineBuffer = new StringBuilder

        def logLine(msg: String): Unit = {
          log.println(msg)
          log.flush()
        }

        logLine(s"=== ${sys.name} BRAM Simulation ===")
        logLine(s"Config: imul=${coreConfig.imul}, idiv=${coreConfig.idiv}, irem=${coreConfig.irem}")

        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitSampling(5)

        val reportInterval = 100000

        for (cycle <- 0 until maxCycles) {
          dut.clockDomain.waitSampling()

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
            println(f"  [$cycle%7d cycles]")
          }
        }

        if (lineBuffer.nonEmpty) {
          println(lineBuffer.toString)
          logLine(f"[$maxCycles%7d] ${lineBuffer.toString}")
        }

        logLine("")
        logLine(s"=== Simulation Complete ($maxCycles cycles) ===")
        logLine(s"UART Output:\n${uartOutput.toString}")
        log.close()

        println(s"\n=== Simulation Complete ($maxCycles cycles) ===")
        println(s"Full output:\n${uartOutput.toString}")
        println(s"Log: $logFilePath")
      }
  }
}

/** HelloWorld with default simulation config (software imul, no compute units) */
object JopConfigHelloWorldSim extends App {
  JopConfigBramSim.runSim(
    jopConfig = JopConfig.simulation,
    jopFilePath = "java/apps/Smallest/HelloWorld.jop"
  )
}

/** HelloWorld with hardware integer math (IntegerComputeUnit: idiv/irem) */
object JopConfigHwMathHelloWorldSim extends App {
  import Implementation._
  val base = JopConfig.simulation
  val hwMath = base.copy(systems = Seq(base.system.copy(
    name = "hwmath-sim",
    coreConfig = base.system.coreConfig.copy(
      imul = Microcode, idiv = Hardware, irem = Hardware))))
  JopConfigBramSim.runSim(
    jopConfig = hwMath,
    jopFilePath = "java/apps/Smallest/HelloWorld.jop"
  )
}

/** HelloWorld with full hardware float + integer (FloatComputeUnit + IntegerComputeUnit) */
object JopConfigHwFloatHelloWorldSim extends App {
  import Implementation._
  val base = JopConfig.simulation
  val hwFloat = base.copy(systems = Seq(base.system.copy(
    name = "hwfloat-sim",
    coreConfig = base.system.coreConfig.copy(
      imul = Microcode, idiv = Hardware, irem = Hardware,
      fadd = Hardware, fsub = Hardware, fmul = Hardware, fdiv = Hardware,
      fneg = Hardware, i2f = Hardware, f2i = Hardware,
      fcmpl = Hardware, fcmpg = Hardware))))
  JopConfigBramSim.runSim(
    jopConfig = hwFloat,
    jopFilePath = "java/apps/Smallest/HelloWorld.jop"
  )
}

/**
 * JVM test suite runner for any JopConfig preset.
 *
 * Takes a preset name, overrides boot mode to Simulation (so ROM/RAM
 * load from asm/generated/ and jump table uses simulation variant),
 * and runs the full DoAll.jop test suite.
 *
 * Usage:
 *   sbt "Test / runMain jop.system.JopConfigJvmTestsSim wukongFull"
 *   sbt "Test / runMain jop.system.JopConfigJvmTestsSim ep4cgx150HwFloat"
 */
object JopConfigJvmTestsSim {
  def main(args: Array[String]): Unit = {
    val presetName = args.headOption.getOrElse("simulation")
    val preset = JopTopVerilog.resolvePreset(presetName, args)

    // Override to simulation boot mode (loads ROM/RAM from asm/generated/)
    val simConfig = preset.copy(systems = Seq(preset.system.copy(
      bootMode = BootMode.Simulation)))

    println(s"=== JVM Tests with preset: $presetName ===")
    val cc = simConfig.system.coreConfig
    println(s"  imul=${cc.imul}, idiv=${cc.idiv}, irem=${cc.irem}")
    println(s"  fadd=${cc.fadd}, fmul=${cc.fmul}, fdiv=${cc.fdiv}")
    println(s"  fneg=${cc.fneg}, i2f=${cc.i2f}, f2i=${cc.f2i}, fcmpl=${cc.fcmpl}")

    JopConfigBramSim.runSim(
      jopConfig = simConfig,
      jopFilePath = "java/apps/JvmTests/DoAll.jop",
      maxCycles = 60000000,
      logFilePath = s"spinalhdl/jvmtests_${presetName}_simulation.log"
    )
  }
}
