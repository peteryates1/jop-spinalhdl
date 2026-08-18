package jop.system
import jop.config._

import spinal.core.sim._
import jop.utils.JopFileLoader

/**
 * `jbe.DoApp` with memory latency removed — how much of REAL application time
 * is spent waiting for memory at all?
 *
 * This is the measurement that decides where memory optimisation effort should
 * go, and it was missing. `DoAppAcacheSweepSim` showed 62 % of DoApp's memory
 * TRANSACTIONS are bytecode fill, but transactions are not time: a method-cache
 * fill is a burst, and a burst of many cheap transactions can cost less than a
 * few expensive ones. Until the stall fraction is known, "62 % of traffic"
 * cannot be turned into "62 % of the win".
 *
 * Run the same binary against BRAM (single-cycle accept, next-cycle response)
 * and compare with the same binary on SDRAM hardware:
 *
 *   EP4CGX150, SDR, 80 MHz, single core:  Kfl 7742, UdpIp 3521, Lift 12690 1/s
 *
 * The BRAM harness runs at 100 MHz, so normalise per MHz before comparing. What
 * comes out is the fraction of application time that memory latency costs —
 * which bounds EVERYTHING a memory-system change can buy on real code.
 *
 *   sbt "Test/runMain jop.system.DoAppBramSim"
 */
object DoAppBramSim extends App {
  val jopFilePath = "java/apps/JbeBench/JbeBench.jop"
  val romData = JopFileLoader.loadMicrocodeRom("asm/generated/mem_rom.dat")
  val ramData = JopFileLoader.loadStackRam("asm/generated/mem_ram.dat")
  val bramSize = 2 * 1024 * 1024
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, bramSize / 4)

  // DoApp calibrates each benchmark to ~1 simulated second = 100 M cycles here,
  // three of them plus startup.
  val CLK_MHZ = 5      // see JopCoreLargeBramHarness.clkMhz -- shrinks the
                       // calibration target; per-MHz results are unchanged
  val maxCycles = 120000000L

  println(s"jbe.DoApp on BRAM, single core, declared $CLK_MHZ MHz — the zero-latency reference")
  println("hardware for comparison (EP4CGX150 SDR 80 MHz): Kfl 7742, UdpIp 3521, Lift 12690\n")

  SimConfig
    .compile(JopCoreLargeBramHarness(romData, ramData, mainMemData, bramSize, clkMhz = CLK_MHZ))
    .doSim { dut =>
      val uart = new StringBuilder
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)
      var cycle = 0L
      var done = false
      while (!done && cycle < maxCycles) {
        dut.clockDomain.waitSampling()
        cycle += 1
        if (dut.io.uartTxValid.toBoolean) {
          val c = dut.io.uartTxData.toInt
          uart.append(if (c >= 32 && c < 127) c.toChar else '.')
          print(if (c >= 32 && c < 127) c.toChar else '.')
          if (uart.toString.contains("Lift") && uart.toString.trim.endsWith("1/s")) done = true
        }
        if (cycle % 25000000 == 0) println(f"\n[${cycle}%,d cycles]")
      }
      println()
      println("=" * 68)
      val hw = Map("Kfl" -> 7742.0, "UdpIp" -> 3521.0, "Lift" -> 12690.0)
      val re = raw"(Kfl|UdpIp|Lift)\s+(\d+) 1/s".r
      val got = re.findAllMatchIn(uart.toString).map(m => m.group(1) -> m.group(2).toDouble).toMap
      if (got.isEmpty) println(f"no results parsed after $cycle%,d cycles")
      else {
        println("bench".padTo(9,' ') + "BRAM@100".reverse.padTo(10,' ').reverse +
                "SDR@80".reverse.padTo(10,' ').reverse + "BRAM/MHz".reverse.padTo(11,' ').reverse +
                "SDR/MHz".reverse.padTo(10,' ').reverse + "stall share".reverse.padTo(13,' ').reverse)
        for (b <- Seq("Kfl", "UdpIp", "Lift"); g <- got.get(b)) {
          val bp = g / CLK_MHZ.toDouble; val sp = hw(b) / 80.0
          println(f"$b%-8s $g%10.0f ${hw(b)}%9.0f $bp%10.2f $sp%9.2f ${(1 - sp / bp) * 100}%11.1f%%")
        }
        println()
        println("stall share = fraction of per-MHz throughput lost to memory latency.")
        println("It is the ceiling on what ANY memory-system change can buy on real code.")
      }
      println("=" * 68)
    }
}
