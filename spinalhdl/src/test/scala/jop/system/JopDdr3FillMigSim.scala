package jop.system

import spinal.core._
import spinal.core.sim._
import jop.utils.{JopFileLoader, TestHistory}
import jop.config.MicrocodePaths

/**
 * Stage-0.5 DDR3 fill THROUGHPUT validation against the realistic MIG model.
 *
 * Unlike JopDdr3FillSim (which uses the one-at-a-time CacheToBramAdapter and so
 * hides the pipelined-write benefit), this runs FillTest through the REAL
 * CacheToMigAdapter + MigBehavioralModel (immediate write-accept into a FIFO,
 * periodic refresh) — the same datapath as the FPGA. It measures cyc/line for
 * the direct-to-MIG streaming fill.
 */
object JopDdr3FillMigSim extends App {

  val jopFilePath = "java/apps/Small/FillTest.jop"
  val romData = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
  val ramData = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)
  val memSizeBytes = 512 * 1024
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, memSizeBytes / 4)

  val run = TestHistory.startRun("JopDdr3FillMigSim", "sim-verilator", jopFilePath, "", "")

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .compile(JopCoreWithMigTestHarness(romData, ramData, mainMemData, hasFill = true, memSizeBytes = memSizeBytes))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val uart = new StringBuilder
      var fillPulses = 0; var prevFill = false; var rangeOk = false
      var fillCycles = 0; var fillWords = BigInt(0)
      var cycle = 0; var done = false

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
      if (fillWords > 0) {
        val cycPerWord = fillCycles.toDouble / fillWords.toDouble
        println(f"THROUGHPUT: $fillWords words in $fillCycles cycles = $cycPerWord%.2f cyc/word = ${cycPerWord * 4}%.2f cyc/line")
      }
      def fail(m: String): Unit = { run.finish("FAIL", m); println(s"FAIL: $m"); System.exit(1) }
      if (o.contains("FILL FAIL")) fail("non-zero words after HW fill")
      if (!o.contains("FILL OK")) fail("did not see FILL OK")
      if (fillPulses < 1) fail("io.fillBusy never pulsed")
      if (!rangeOk) fail("no positive-range fill observed")
      run.finish("PASS", s"$cycle cycles, $fillPulses fills")
      println("PASS: DDR3 direct-to-MIG fill zeroed a valid range end-to-end")
    }
}
