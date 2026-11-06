package jop.system

import spinal.core._
import spinal.core.sim._
import jop.config.JopCoreConfig
import jop.memory.JopMemoryConfig
import jop.sim.SimVerdict
import jop.utils.{JopFileLoader, TestHistory, JopSimDefaults}
import jop.config.MicrocodePaths

/**
 * JVM test suite (DoAll) under generational GC (Stage 2). Requires
 * GC.USE_GENERATIONAL=true and a card-table core. Strong correctness regression:
 * all object/array types + heavy allocation drive many minor (and some major)
 * GCs. Passes on "JVM exit!" with every test "ok" and no exception/hang.
 */
object JopGenJvmTestsBramSim extends App {

  val jopFilePath = jop.utils.SimApp.jop("JvmTests", "DoAll")
  val romData = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
  val ramData = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)

  val memSize = 512 * 1024
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, memSize / 4)

  val cfg = JopCoreConfig(memConfig = JopMemoryConfig(
    mainMemSize = memSize, hasCardTable = true, cardTableBudgetBytes = 4096))

  val run = TestHistory.startRun("JopGenJvmTestsBramSim", "sim-verilator", jopFilePath, "", "")

  JopSimDefaults.config
    .compile(JopCoreTestHarness(romData, ramData, mainMemData, memSize, Some(cfg)))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val uart = new StringBuilder
      var cycle = 0; var done = false
      val maxCycles = 120000000
      // NOTE: JVM tests deliberately trigger hardware exceptions (NPE, array
      // bounds, ClassCast, ...) which they catch — excFired is normal, NOT a
      // failure. Correctness is judged by per-test ok/fail text + reaching exit.
      // The UART as lines -- the verdict counts DoAll's `<name> ok` LINES, and
      // this kept a newline as '.' -- each printed whole when it ends.
      val line = new StringBuilder
      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()
        if (dut.io.uartTxValid.toBoolean) {
          val c = dut.io.uartTxData.toInt
          if (c == 10) {
            println(line.toString)
            // Checked once a line ends, not every cycle: this rebuilt the whole
            // output to search it 120M times.
            if (line.toString.contains("JVM exit!")) done = true
            line.clear()
          } else if (c >= 32 && c < 127) line.append(c.toChar)
          uart.append(if (c == 10) '\n' else if (c >= 32 && c < 127) c.toChar else ' ')
        }
      }
      if (line.nonEmpty) println(line.toString)
      val o = uart.toString
      println(s"\n=== Done ($cycle cycles) ===")
      def fail(m: String): Unit = { run.finish("FAIL", m); println(s"FAIL: $m"); System.exit(1) }
      // DoAll's verdict, by its own count (item 177): this counted " ok"
      // anywhere and had no idea how many tests there were.
      SimVerdict.doAll(o) match {
        case Left(why) => fail(why)
        case Right(what) =>
          if (!o.contains("JVM exit!")) fail(s"$what, but no JVM exit! (hang/crash under generational GC)")
          run.finish("PASS", s"$cycle cycles, $what")
          println(s"PASS: generational GC -- $what, JVM exit!")
      }
    }
}
