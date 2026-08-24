package jop.system

import spinal.core._
import spinal.core.sim._
import jop.config.JopCoreConfig
import jop.memory.JopMemoryConfig
import jop.utils.{JopFileLoader, TestHistory}
import jop.config.MicrocodePaths

/**
 * Generational GC (Stage 2) churn stress. Requires GC.USE_GENERATIONAL=true and
 * a card-table core. GcStressTest allocates int[32] arrays in a tight loop (all
 * garbage but the last), forcing continuous minor GCs, and prints "R<round>
 * f=<freeMem>" each round forever. Passes if rounds keep advancing past a
 * threshold (no leak/hang) with no exception — i.e. minorGc reclaims the churn.
 */
object JopGenGcStressSim extends App {

  val jopFilePath = "java/apps/Small/GcStressTest.jop"
  val romData = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
  val ramData = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)

  val memSize = 512 * 1024
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, memSize / 4)

  val cfg = JopCoreConfig(memConfig = JopMemoryConfig(
    mainMemSize = memSize, hasCardTable = true, cardTableBudgetBytes = 4096))

  val run = TestHistory.startRun("JopGenGcStressSim", "sim-verilator", jopFilePath, "", "")
  val ROUND_TARGET = 300

  SimConfig
    .compile(JopCoreTestHarness(romData, ramData, mainMemData, memSize, Some(cfg)))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val uart = new StringBuilder
      var cycle = 0; var done = false; var fault = false; var maxRound = -1
      val maxCycles = 60000000
      val roundRe = "R(\\d+) f=(\\d+)".r
      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()
        if (dut.io.excFired.toBoolean) {
          println(f"\n[$cycle%8d] *** EXCEPTION type=${dut.io.excType.toInt} PC=${dut.io.pc.toInt}%04x ***")
          fault = true; done = true
        }
        if (dut.io.uartTxValid.toBoolean) {
          val c = dut.io.uartTxData.toInt
          val ch = if (c >= 32 && c < 127) c.toChar else '\n'
          uart.append(ch); print(ch)
          if (ch == '\n') {
            val line = uart.toString.reverse.drop(1).takeWhile(_ != '\n').reverse
            roundRe.findFirstMatchIn(line).foreach { m => maxRound = math.max(maxRound, m.group(1).toInt) }
            if (maxRound >= ROUND_TARGET) done = true
          }
        }
      }
      println(s"\n=== Done ($cycle cycles) maxRound=$maxRound ===")
      def fail(m: String): Unit = { run.finish("FAIL", m); println(s"FAIL: $m"); System.exit(1) }
      if (fault) fail("exception during GC churn")
      if (maxRound < ROUND_TARGET) fail(s"only reached round $maxRound (< $ROUND_TARGET) — leak/hang?")
      run.finish("PASS", s"$cycle cycles, $maxRound rounds")
      println(s"PASS: generational GC sustained $maxRound churn rounds, no leak/fault")
    }
}
