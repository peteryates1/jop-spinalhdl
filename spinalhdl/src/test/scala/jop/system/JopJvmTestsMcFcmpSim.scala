package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.{JopFileLoader, TestHistory}
import jop.memory.JopMemoryConfig
import jop.config.JopCoreConfig
import java.io.PrintWriter
import jop.config.MicrocodePaths

/**
 * JVM test suite (jvm.DoAll) with fcmpl/fcmpg forced to MICROCODE.
 *
 * The default configuration implements both in Java, so JopJvmTestsBramSim
 * never executes fcmpl_sw/fcmpg_sw at all — passing there says nothing about
 * them. This is the run that actually exercises the handlers added for item 19
 * tier 1, and is the done-condition that item records.
 *
 * `bytecodes` sets only these two: everything else stays at its default, so a
 * failure here is attributable to the new microcode rather than to a wholesale
 * change of implementation strategy.
 */
object JopJvmTestsMcFcmpSim extends App {

  val jopFilePath = "java/apps/JvmTests/DoAll.jop"
  val romFilePath = MicrocodePaths.simulationRom
  val ramFilePath = MicrocodePaths.simulationRam
  val logFilePath = "build/sim-logs/jvmtests_mcfcmp_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val bramSize = 512 * 1024  // 512KB — DoAll.jop code is ~280KB, leaving ~230KB heap
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, bramSize / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")

  SimConfig
    .compile(JopCoreTestHarness(romData, ramData, mainMemData, memSize = bramSize,
      coreConfig = Some(JopCoreConfig(
        memConfig = JopMemoryConfig(mainMemSize = bramSize),
        bytecodes = Map("fcmpl" -> "mc", "fcmpg" -> "mc")))))
    .doSim { dut =>
      val log = { new java.io.File(logFilePath).getParentFile.mkdirs(); new PrintWriter(logFilePath) }
      var uartOutput = new StringBuilder
      var lineBuffer = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JOP JVM Tests, fcmpl/fcmpg = microcode ===")

      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      val maxCycles = 60000000
      val reportInterval = 100000

      for (cycle <- 0 until maxCycles) {
        dut.clockDomain.waitSampling()

        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          if (char == 10) {  // newline
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
      logLine("=== Simulation Complete ===")
      logLine(s"UART Output:\n${uartOutput.toString}")
      log.close()

      println(s"\n=== Simulation Complete ($maxCycles cycles) ===")
      println(s"Full output:\n${uartOutput.toString}")
      println(s"Log: $logFilePath")
    }
}
