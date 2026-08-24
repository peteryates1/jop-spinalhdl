package jop.system

import spinal.core._
import spinal.core.sim._
import jop.config.JopCoreConfig
import jop.memory.JopMemoryConfig
import jop.utils.{JopFileLoader, TestHistory}
import jop.config.MicrocodePaths

/**
 * JVM test suite (DoAll) under generational GC (Stage 2). Requires
 * GC.USE_GENERATIONAL=true and a card-table core. Strong correctness regression:
 * all object/array types + heavy allocation drive many minor (and some major)
 * GCs. Passes on "JVM exit!" with every test "ok" and no exception/hang.
 */
object JopGenJvmTestsBramSim extends App {

  val jopFilePath = "java/apps/JvmTests/DoAll.jop"
  val romData = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
  val ramData = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)

  val memSize = 512 * 1024
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, memSize / 4)

  val cfg = JopCoreConfig(memConfig = JopMemoryConfig(
    mainMemSize = memSize, hasCardTable = true, cardTableBudgetBytes = 4096))

  val run = TestHistory.startRun("JopGenJvmTestsBramSim", "sim-verilator", jopFilePath, "", "")

  SimConfig
    .compile(JopCoreTestHarness(romData, ramData, mainMemData, memSize, Some(cfg)))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val uart = new StringBuilder
      var cycle = 0; var done = false
      val maxCycles = 120000000
      // NOTE: JVM tests deliberately trigger hardware exceptions (NPE, array
      // bounds, ClassCast, ...) which they catch — excFired is normal, NOT a
      // failure. Correctness is judged by per-test ok/fail text + reaching exit.
      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()
        if (dut.io.uartTxValid.toBoolean) {
          val c = dut.io.uartTxData.toInt
          uart.append(if (c >= 32 && c < 127) c.toChar else '.'); print(if (c >= 32 && c < 127) c.toChar else '.')
        }
        if (uart.toString.contains("JVM exit!")) done = true
      }
      val o = uart.toString
      val okCount = " ok".r.findAllIn(o).size
      val failCount = "(?i)fail".r.findAllIn(o).size
      println(s"\n=== Done ($cycle cycles) ok=$okCount fail=$failCount ===")
      def fail(m: String): Unit = { run.finish("FAIL", m); println(s"FAIL: $m"); System.exit(1) }
      if (failCount > 0) fail(s"$failCount 'fail' occurrence(s) in output")
      if (!o.contains("JVM exit!")) fail("did not reach JVM exit! (hang/crash under generational GC)")
      run.finish("PASS", s"$cycle cycles, $okCount ok")
      println(s"PASS: generational GC — $okCount JVM tests ok, JVM exit!")
    }
}
