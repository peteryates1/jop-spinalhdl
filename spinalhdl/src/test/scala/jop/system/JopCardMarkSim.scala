package jop.system

import spinal.core._
import spinal.core.sim._
import jop.utils.{JopFileLoader, TestHistory}
import jop.config.MicrocodePaths

/**
 * Stage-1 HW card-marking barrier end-to-end check. Runs CardMarkTest through
 * the cache harness (hasCard = true): the app sets a tenure window, clears the
 * card table, writes a few array elements, and verifies exactly those cards are
 * marked dirty via the IO_CARD_* registers. Requires "CARD OK".
 */
object JopCardMarkSim extends App {

  val jopFilePath = jop.utils.SimApp.jop("Small", "CardMarkTest")
  val romData = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
  val ramData = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 512 * 1024 / 4)

  val run = TestHistory.startRun("JopCardMarkSim", "sim-verilator", jopFilePath, "", "")

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .compile(JopCoreWithCacheTestHarness(romData, ramData, mainMemData, hasCard = true))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val uart = new StringBuilder
      var cycle = 0; var done = false
      while (cycle < 8000000 && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()
        if (dut.io.uartTxValid.toBoolean) {
          val c = dut.io.uartTxData.toInt
          val ch = if (c >= 32 && c < 127) c.toChar else '.'
          uart.append(ch); print(ch)
        }
        val o = uart.toString
        if (o.contains("CARD OK") || o.contains("CARD FAIL")) done = true
      }

      val o = uart.toString
      println(s"\n=== Done ($cycle cycles) ===")
      def fail(m: String): Unit = { run.finish("FAIL", m); println(s"FAIL: $m"); System.exit(1) }
      if (o.contains("CARD FAIL")) fail("card marking mismatch")
      if (!o.contains("CARD OK"))  fail("did not see CARD OK")
      run.finish("PASS", s"$cycle cycles")
      println("PASS: HW card-marking barrier marks the right cards")
    }
}
