package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.{JopFileLoader, TestHistory}
import jop.memory.JopMemoryConfig
import java.io.PrintWriter
import jop.config.MicrocodePaths

/**
 * BRAM simulation for the Small GC test app
 */
object JopSmallGcBramSim extends App {

  // GcStressTest.jop, not HelloWorld.jop. This sim asserts on "GC test start",
  // which ONLY java/apps/Small/src/test/GcStressTest.java prints —
  // src/test/HelloWorld.java just prints "Hello World!". It was passing on a
  // stale HelloWorld.jop built from some other source at some point in the
  // past: the file's provenance no longer matched the tree, so the sim was
  // testing a binary nobody could rebuild. Rebuild it with
  //   make -C java/apps/Small APP_NAME=GcStressTest
  val jopFilePath = "java/apps/Small/GcStressTest.jop"
  val romFilePath = MicrocodePaths.simulationRom
  val ramFilePath = MicrocodePaths.simulationRam
  val logFilePath = "spinalhdl/small_gc_bram_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"Log file: $logFilePath")

  val run = TestHistory.startRun("JopSmallGcBramSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .compile(JopCoreTestHarness(romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JOP Small GC BRAM Simulation Log ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 100000000  // 100M cycles — need enough for multiple GC cycles (mark-compact heap fills ~R24)
      val reportInterval = 100000
      var done = false
      var cycle = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // Check for exception firing
        if (dut.io.excFired.toBoolean) {
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          val excType = dut.io.excType.toInt
          val aout = dut.io.aout.toLong & 0xFFFFFFFFL
          val bout = dut.io.bout.toLong & 0xFFFFFFFFL
          println(f"\n[$cycle%8d] *** EXCEPTION type=$excType PC=$pc%04x JPC=$jpc%04x aout=0x$aout%08x bout=0x$bout%08x ***")
          logLine(f"[$cycle%8d] EXCEPTION type=$excType PC=$pc%04x JPC=$jpc%04x aout=0x$aout%08x bout=0x$bout%08x")
        }

        // Check for UART output
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          print(if (char >= 32 && char < 127) char.toChar else '.')
          logLine(f"[$cycle%8d] UART: '${if (char >= 32 && char < 127) char.toChar.toString else f"\\x$char%02x"}' (0x$char%02x)")
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          println(f"\n[$cycle%8d] PC=$pc%04x JPC=$jpc%04x UART: '${uartOutput.toString}'")
        }

        // Stop on the EVIDENCE (free memory jumped back up), not on a fixed
        // Watch a WINDOW that comfortably contains the first collection rather
        // than ending exactly on it. Stopping at "R80 f=" made the pass depend
        // on the GC firing at exactly R80: any change to runtime code size
        // shifts the heap start, which shifts free-per-round, which moves the
        // trigger a round either way. A GC.java edit that fires the collector
        // at R81 instead of R80 failed this sim while collecting perfectly well
        // (HEAD: R79 f=1180 -> GC at R80; slightly larger runtime: R79 f=1308
        // -> GC at R81, one round outside the window).
        val output = uartOutput.toString
        if (output.contains("R95 f=")) {
          println("\n*** Multiple GC cycles completed! ***")
          // Capture a bit more
          for (_ <- 0 until 50000) {
            dut.clockDomain.waitSampling()
            if (dut.io.uartTxValid.toBoolean) {
              val char = dut.io.uartTxData.toInt
              uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
              print(if (char >= 32 && char < 127) char.toChar else '.')
            }
          }
          done = true
        }
      }

      log.close()

      println(s"\n\n=== Simulation Complete (${cycle} cycles) ===")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Log written to: $logFilePath")

      if (!uartOutput.toString.contains("GC test start")) {
        run.finish("FAIL", "Did not see 'GC test start'")
        println("FAIL: Did not see 'GC test start'")
        System.exit(1)
      }
      if (!uartOutput.toString.contains("R0 f=")) {
        run.finish("FAIL", "Did not see allocation rounds")
        println("FAIL: Did not see allocation rounds")
        System.exit(1)
      }
      // Verify GC actually reclaimed memory (free went up at some point)
      val freePattern = """R\d+ f=(\d+)""".r
      val freeVals = freePattern.findAllMatchIn(uartOutput.toString).map(_.group(1).toInt).toList
      val gcOccurred = freeVals.length >= 2 && freeVals.sliding(2).exists { case List(a, b) => b > a case _ => false }
      if (!gcOccurred) {
        run.finish("FAIL", "GC never triggered (free memory never increased)")
        println("FAIL: GC never triggered (free memory never increased)")
        System.exit(1)
      }
      val gcCycles = freeVals.sliding(2).count { case List(a, b) => b > a case _ => false }
      run.finish("PASS", s"$cycle cycles, $gcCycles GC cycles observed")
      println(s"PASS: $gcCycles GC cycles observed in $cycle cycles")
    }
}
