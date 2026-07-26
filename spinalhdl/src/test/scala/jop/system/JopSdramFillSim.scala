package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import jop.memory.SdramDeviceInfo
import jop.utils.{JopFileLoader, TestHistory}

/**
 * Stage-0.5 end-to-end validation for SDR: the controller's block-fill path
 * (FILL_REQ -> MemFill -> BmbSdramCtrl32 fill) actually zeroes SDRAM.
 *
 * Runs FillTest, which HW-zeros a 512-word buffer over a known VALID range and
 * prints "FILL OK" iff every word reads back zero. We also require io.fillBusy
 * to pulse (a real fill fired) with a positive range.
 */
object JopSdramFillSim extends App {

  val jopFilePath = "java/apps/Small/FillTest.jop"
  val romData = JopFileLoader.loadMicrocodeRom("asm/generated/mem_rom.dat")
  val ramData = JopFileLoader.loadStackRam("asm/generated/mem_ram.dat")
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 256 * 1024 / 4)

  val run = TestHistory.startRun("JopSdramFillSim", "sim-verilator", jopFilePath, "", "")

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .compile(JopCoreWithSdramTestHarness(romData, ramData, mainMemData, memBytes = 256 * 1024))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val sdramModel = SdramModel(io = dut.io.sdram,
        layout = SdramDeviceInfo.layoutFor(dut.md), clockDomain = dut.clockDomain)
      for (i <- mainMemData.indices) {
        val w = mainMemData(i).toLong & 0xFFFFFFFFL; val b = i * 4
        sdramModel.write(b + 0, ((w >>  0) & 0xFF).toByte)
        sdramModel.write(b + 1, ((w >>  8) & 0xFF).toByte)
        sdramModel.write(b + 2, ((w >> 16) & 0xFF).toByte)
        sdramModel.write(b + 3, ((w >> 24) & 0xFF).toByte)
      }
      dut.clockDomain.waitSampling(5)

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
      if (fillWords > 0) {
        val writes16 = fillWords * 2   // two 16-bit SDRAM writes per 32-bit word
        println(f"THROUGHPUT: filled $fillWords words in $fillCycles cycles = " +
          f"${fillCycles.toDouble / fillWords.toDouble}%.2f cyc/word (${fillCycles.toDouble / writes16.toDouble}%.2f cyc/16b-write)")
      }
      def fail(m: String): Unit = { run.finish("FAIL", m); println(s"FAIL: $m"); System.exit(1) }
      if (o.contains("FILL FAIL")) fail("FillTest reported non-zero words after HW fill")
      if (!o.contains("FILL OK")) fail("did not see FILL OK")
      if (fillPulses < 1) fail("io.fillBusy never pulsed")
      if (!rangeOk) fail("no positive-range fill observed")
      run.finish("PASS", s"$cycle cycles, $fillPulses fills")
      println("PASS: controller-driven SDR block-fill zeroed a valid range end-to-end")
    }
}
