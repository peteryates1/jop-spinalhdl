package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.{JopFileLoader, JopSimDefaults, TestHistory}
import jop.memory.JopMemoryConfig
import jop.sim.SimVerdict
import java.io.PrintWriter
import jop.config.MicrocodePaths

/**
 * BRAM simulation running JVM test suite (jvm.DoAll)
 */
object JopJvmTestsBramSim extends App {

  // App selectable, so the non-cache path can be pointed at jvm.DeepAll — the
  // case that HANGS here (192 usable stack words against ~250 needed) because
  // spOv is not wired to EXC_SPOV. Status item 133.
  val jopFilePath = jop.utils.SimApp.jop(
    if (args.length > 0) args(0) else "JvmTests",
    if (args.length > 1) args(1) else "DoAll")
  val romFilePath = MicrocodePaths.simulationRom
  val ramFilePath = MicrocodePaths.simulationRam
  val logFilePath = "build/sim-logs/jvmtests_bram_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val bramSize = 512 * 1024  // 512KB — DoAll.jop code is ~280KB, leaving ~230KB heap
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, bramSize / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")

  // Seed handling and the --x-initial 0 X-state defence both live in
  // JopSimDefaults; see the long note there for why this suite was flaky.
  private val simSeed: Int = JopSimDefaults.seed()

  JopSimDefaults.config
    .compile(JopCoreTestHarness(romData, ramData, mainMemData, memSize = bramSize))
    .doSim(seed = simSeed) { dut =>
      val log = { new java.io.File(logFilePath).getParentFile.mkdirs(); new PrintWriter(logFilePath) }
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

      // Ends at DoAll's "JVM exit!", not at maxCycles: the suite is over by
      // then, and running out the rest -- 22M cycles here after DoAll's 38M on
      // the baseline, 40M after 20M on the fallbacks -- made these CI's two
      // longest jobs, ~17.5 minutes of simulation each. maxCycles still bounds
      // a hang.
      var cycle = 0
      var done = false
      while (cycle < maxCycles && !done) {
        dut.clockDomain.waitSampling()

        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          if (char == 10) {  // newline
            val line = lineBuffer.toString
            println(line)
            logLine(f"[$cycle%7d] $line")
            lineBuffer.clear()
            if (line.contains("JVM exit!")) done = true
          } else if (char >= 32 && char < 127) {
            lineBuffer.append(char.toChar)
          }
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '\n')
        }

        if (cycle > 0 && cycle % reportInterval == 0) {
          println(f"  [$cycle%7d cycles]")
        }
        cycle += 1
      }

      if (lineBuffer.nonEmpty) {
        println(lineBuffer.toString)
        logLine(f"[$cycle%7d] ${lineBuffer.toString}")
      }

      logLine("")
      logLine("=== Simulation Complete ===")
      logLine(s"UART Output:\n${uartOutput.toString}")
      log.close()

      println(s"\n=== Simulation Complete ($cycle cycles) ===")
      println(s"Full output:\n${uartOutput.toString}")
      println(s"Log: $logFilePath")

      // DoAll's verdict, by its own count (items 176, 177). This printed the
      // suite and exited 0 whatever it said.
      SimVerdict.doAll(uartOutput.toString) match {
        case Right(what) => println(s"PASS: $what")
        case Left(why) =>
          println(s"FAIL: $why")
          System.exit(1)
      }
    }
}
