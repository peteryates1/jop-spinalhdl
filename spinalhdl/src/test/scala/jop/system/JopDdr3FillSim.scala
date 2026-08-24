package jop.system

import spinal.core._
import spinal.core.sim._
import jop.utils.{JopFileLoader, TestHistory}
import jop.config.MicrocodePaths

/**
 * Stage-0.5 DDR3 end-to-end validation: the controller's block-fill path drives
 * the DDR3 backend (BmbCacheBridge) to zero SDRAM via full-line 128-bit cache
 * writes (no refill). Runs FillTest through the cache+BRAM harness (hasFill=true)
 * and requires FILL OK + a positive-range io.fillBusy pulse; reports throughput.
 */
object JopDdr3FillSim extends App {

  val jopFilePath = "java/apps/Small/FillTest.jop"
  val romData = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
  val ramData = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 512 * 1024 / 4)

  val run = TestHistory.startRun("JopDdr3FillSim", "sim-verilator", jopFilePath, "", "")

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .compile(JopCoreWithCacheTestHarness(romData, ramData, mainMemData, hasFill = true))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val uart = new StringBuilder
      var fillPulses = 0; var prevFill = false; var rangeOk = false
      var fillCycles = 0; var fillWords = BigInt(0)
      var cycle = 0; var done = false
      val N = 8192   // must match FillTest.N

      while (cycle < 20000000 && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()
        if (dut.io.uartTxValid.toBoolean) {
          val c = dut.io.uartTxData.toInt
          uart.append(if (c >= 32 && c < 127) c.toChar else '.'); print(if (c >= 32 && c < 127) c.toChar else '.')
        }
        val fb = dut.io.fillBusy.toBoolean
        if (fb) fillCycles += 1
        if (fb && !prevFill) {
          fillPulses += 1
          val s = dut.io.fillStart.toBigInt; val e = dut.io.fillEnd.toBigInt
          if (e > s) { rangeOk = true; fillWords = e - s }
          println(f"\n[$cycle%8d] FILL fired: start=0x$s%x end=0x$e%x range=${e - s}")
        }
        prevFill = fb
        val o = uart.toString
        if (o.contains("FILL OK") || o.contains("FILL FAIL")) done = true
        if (cycle % 1000000 == 0) println(f"\n[$cycle%8d] fills=$fillPulses")
      }

      val o = uart.toString
      println(s"\n=== Done ($cycle cycles) fills=$fillPulses rangeOk=$rangeOk ===")
      if (fillWords > 0)
        println(f"THROUGHPUT: $fillWords words in $fillCycles cycles = ${fillCycles.toDouble / fillWords.toDouble}%.2f cyc/word")
      def fail(m: String): Unit = { run.finish("FAIL", m); println(s"FAIL: $m"); System.exit(1) }
      if (o.contains("FILL FAIL")) fail("non-zero words after HW fill")
      if (!o.contains("FILL OK")) fail("did not see FILL OK")
      if (fillPulses < 1) fail("io.fillBusy never pulsed")
      if (!rangeOk) fail("no positive-range fill observed")
      run.finish("PASS", s"$cycle cycles, $fillPulses fills")
      println("PASS: DDR3 backend block-fill zeroed a valid range end-to-end")
    }
}
