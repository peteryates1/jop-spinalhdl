package jop.system
import jop.config._

import spinal.core._
import spinal.core.sim._
import jop.utils.JopFileLoader

/**
 * `jbe.Scale` with the memory latency taken away — the COMPUTE FLOOR.
 *
 * Everything about "what is the ceiling for JbeScale" reduces to one unknown:
 * how many cycles the inner loop costs when it is not waiting for memory. The
 * hardware numbers cannot separate that from queueing —
 *
 *   Wukong SDR  @100 MHz   1 core 161 cyc/access,  8 cores 289 cyc/access/core
 *   Wukong DDR3 @91.68 MHz 1 core 213 cyc/access,  8 cores 390 cyc/access/core
 *
 * — because both numbers contain compute AND memory. Run the same binary
 * against BRAM (single-cycle accept, next-cycle response) and the memory term
 * nearly vanishes, so what is left is C. Then `aggregate <= N/C` is a hard
 * bound and the queueing term falls out by subtraction.
 *
 * It also counts BMB transactions, so the memory operations per access are
 * MEASURED rather than assumed from reading the Java. That matters: the loop
 * body is `buf[idx] = buf[idx] + idx + it; acc += buf[idx];`, which looks like
 * two reads and a write, but iastore is write-through and the second read
 * should hit the array cache — worth confirming instead of believing.
 *
 *   sbt "Test/runMain jop.system.JbeScaleBramSim"
 */
object JbeScaleBramSim extends App {
  // Matches jbe.Scale: WORDS x ITERATIONS accesses on one core.
  val ACCESSES = 16384L * 24

  val jopFilePath = "java/apps/JbeBench/JbeScale.jop"
  val romData = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
  val ramData = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)

  // 2 MB: the 64 KB working set plus heap, with room for the GC to breathe.
  val bramSize = 2 * 1024 * 1024
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, bramSize / 4)

  println(s"jbe.Scale on ${bramSize / 1024} KB BRAM, single core, $ACCESSES accesses")
  println("clkFreq is the JopCoreConfig default 100 MHz, so IO_US_CNT ticks every 100 cycles")

  SimConfig
    .compile(JopCoreLargeBramHarness(romData, ramData, mainMemData, bramSize))
    .doSim { dut =>
      val uart = new StringBuilder
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      val maxCycles = 200000000L
      var cycle = 0L
      var done = false
      // Memory traffic across the measured region, bracketed by the banner and
      // the final line so boot and allocation are excluded.
      var bmbReads = 0L
      var bmbWrites = 0L
      var counting = false
      var startCycle = 0L

      while (!done && cycle < maxCycles) {
        dut.clockDomain.waitSampling()
        cycle += 1

        if (dut.io.bmbCmdValid.toBoolean && dut.io.bmbCmdReady.toBoolean && counting) {
          if (dut.io.bmbCmdOpcode.toInt == 0) bmbReads += 1 else bmbWrites += 1
        }

        if (dut.io.uartTxValid.toBoolean) {
          val c = dut.io.uartTxData.toInt
          uart.append(if (c >= 32 && c < 127) c.toChar else '.')
          print(if (c >= 32 && c < 127) c.toChar else '.')
          val s = uart.toString
          // The banner is printed immediately before the timed region starts.
          if (!counting && s.contains("passes (private memwalk)")) {
            counting = true; startCycle = cycle
          }
          if (s.contains("Scale done")) done = true
        }

        if (cycle % 5000000 == 0) println(f"\n[${cycle}%,d cycles] still running")
      }

      println()
      println("=" * 70)
      if (!done) {
        println(f"TIMEOUT after $cycle%,d cycles — no 'Scale done'")
        simFailure("did not complete")
      }

      // The board's own figure, from IO_US_CNT at 100 MHz.
      val us = "core 0:\\s+(\\d+) us".r.findFirstMatchIn(uart.toString).map(_.group(1).toLong)
      us match {
        case None => println("could not find the per-core timing line"); simFailure("no timing")
        case Some(u) =>
          val cycles = u * 100                       // 100 MHz => 100 cycles per us
          val cPerAccess = cycles.toDouble / ACCESSES
          val kaccs = ACCESSES * 1e6 / u / 1000.0
          println(f"COMPUTE FLOOR (BRAM, 1 core)")
          println(f"  reported          : $u%,d us  ->  $cycles%,d cycles")
          println(f"  per access        : $cPerAccess%.1f cycles   <-- C")
          println(f"  rate              : $kaccs%.0f kacc/s at 100 MHz")
          println()
          println(f"  BMB reads/access  : ${bmbReads.toDouble / ACCESSES}%.2f")
          println(f"  BMB writes/access : ${bmbWrites.toDouble / ACCESSES}%.2f")
          println(f"  BMB total/access  : ${(bmbReads + bmbWrites).toDouble / ACCESSES}%.2f")
          println()
          println(f"  measured elsewhere: SDR 1 core 161, DDR3 1 core 213 cycles/access")
          println(f"  => memory term    : SDR ${161 - cPerAccess}%.0f, DDR3 ${213 - cPerAccess}%.0f cycles/access")
          println(f"  => hard bound at 8 cores if memory were free: " +
                  f"${8.0 / cPerAccess * 100e6 / 1e6}%.1f Macc/s")
      }
      println("=" * 70)
    }
}
