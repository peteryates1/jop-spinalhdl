package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.{JopFileLoader, TestHistory}
import jop.memory.JopMemoryConfig
import java.io.PrintWriter

/**
 * BRAM simulation running JVM test suite (jvm.DoAll)
 */
object JopJvmTestsBramSim extends App {

  val jopFilePath = "java/apps/JvmTests/DoAll.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/jvmtests_bram_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val bramSize = 512 * 1024  // 512KB — DoAll.jop code is ~280KB, leaving ~230KB heap
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, bramSize / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")

  // Seed is settable so a CI failure can be replayed exactly. This suite has
  // failed intermittently (item 30) with the failure not reproducible locally,
  // and the seed is printed by every run — being able to feed it back is the
  // difference between a reproducer and a shrug. Unset means random, i.e.
  // unchanged behaviour.
  //   JOP_SIM_SEED=405669157 sbt "Test/runMain jop.system.JopJvmTestsBramSim"
  private val simSeed: Int =
    sys.env.get("JOP_SIM_SEED").map(_.trim.toInt).getOrElse(scala.util.Random.nextInt())
  println(s"Simulation seed: $simSeed  (set JOP_SIM_SEED to replay)")

  SimConfig
    .compile(JopCoreTestHarness(romData, ramData, mainMemData, memSize = bramSize))
    .doSim(seed = simSeed) { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder
      var lineBuffer = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JOP JVM Tests BRAM Simulation ===")

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
