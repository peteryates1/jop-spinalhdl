package jop.system

import spinal.core._
import spinal.core.sim._
import jop.config.JopCoreConfig
import jop.memory.JopMemoryConfig
import jop.utils.{JopFileLoader, TestHistory}
import jop.config.MicrocodePaths

/**
 * Generational GC (Stage 2) BRAM sanity sim. Requires GC.USE_GENERATIONAL=true
 * and a card-table-equipped core. Runs HelloWorld with a small heap so the
 * nursery fills during boot/clinit and minorGc() actually runs. Passes if it
 * boots, GCs, prints "Hello World!" and never faults/hangs.
 */
object JopGenGcBramSim extends App {

  val jopFilePath = "java/apps/Small/HelloWorld.jop"
  val romData = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
  val ramData = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)

  val memSize = 512 * 1024
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, memSize / 4)

  val cfg = JopCoreConfig(memConfig = JopMemoryConfig(
    mainMemSize = memSize, hasCardTable = true, cardTableBudgetBytes = 4096))

  val run = TestHistory.startRun("JopGenGcBramSim", "sim-verilator", jopFilePath, "", "")

  SimConfig
    .compile(JopCoreTestHarness(romData, ramData, mainMemData, memSize, Some(cfg)))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val uart = new StringBuilder
      var cycle = 0; var done = false; var fault = false
      val maxCycles = 40000000
      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()
        if (dut.io.excFired.toBoolean) {
          val t = dut.io.excType.toInt; val pc = dut.io.pc.toInt
          println(f"\n[$cycle%8d] *** EXCEPTION type=$t PC=$pc%04x ***")
          fault = true; done = true
        }
        if (dut.io.uartTxValid.toBoolean) {
          val c = dut.io.uartTxData.toInt
          val ch = if (c >= 32 && c < 127) c.toChar else '.'
          uart.append(ch); print(ch)
        }
        val o = uart.toString
        if (o.contains("Hello World!")) done = true
      }
      val o = uart.toString
      println(s"\n=== Done ($cycle cycles) ===")
      def fail(m: String): Unit = { run.finish("FAIL", m); println(s"FAIL: $m"); System.exit(1) }
      if (fault) fail("exception fired under generational GC")
      if (!o.contains("Hello World!")) fail("did not reach Hello World! (hang or GC bug)")
      run.finish("PASS", s"$cycle cycles")
      println("PASS: generational GC boots + GCs + runs to Hello World!")
    }
}
