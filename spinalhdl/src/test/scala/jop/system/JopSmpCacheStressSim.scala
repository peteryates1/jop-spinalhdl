package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.{JopFileLoader, TestHistory}
import jop.memory.JopMemoryConfig
import java.io.PrintWriter

/**
 * SMP cache coherency stress test simulation.
 *
 * Runs the SmpCacheTest app on 2 cores:
 * - Core 0 writes to shared arrays and object fields
 * - Core 1 reads and verifies the values
 * - Tests A$ and O$ snoop invalidation under real cross-core traffic
 *
 * This is the highest-priority missing test (Priority 1 CRITICAL)
 * from the test coverage audit. It directly tests the snoop invalidation
 * mechanism that fixed bugs #13 and #14.
 *
 * Usage: sbt "Test / runMain jop.system.JopSmpCacheStressSim"
 */
object JopSmpCacheStressSim extends App {
  val cpuCnt = 2

  val jopFilePath = "java/apps/SmpCacheTest/SmpCacheTest.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/smp_cache_stress_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"CPU count: $cpuCnt")
  println(s"Log file: $logFilePath")

  val run = TestHistory.startRun("JopSmpCacheStressSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .compile(JopSmpTestHarness(cpuCnt, romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder
      var lineBuffer = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine(s"=== JOP SMP Cache Coherency Stress Test ($cpuCnt cores) ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 20000000  // 20M cycles
      val reportInterval = 1000000
      var done = false
      var cycle = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // Check for exception firing (core 0)
        if (dut.io.excFired.toBoolean) {
          val pc0 = dut.io.pc(0).toInt
          val jpc0 = dut.io.jpc(0).toInt
          println(f"\n[$cycle%8d] *** EXCEPTION PC=$pc0%04x JPC=$jpc0%04x ***")
          logLine(f"[$cycle%8d] EXCEPTION PC=$pc0%04x JPC=$jpc0%04x")
        }

        // Check for UART output (core 0)
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          if (char == 10 || char == 13) {  // newline or CR
            if (lineBuffer.nonEmpty) {
              val line = lineBuffer.toString
              println(line)
              logLine(f"[$cycle%8d] $line")
              // Check for completion
              if (line.contains("SmpCacheTest PASS") || line.contains("SmpCacheTest FAIL")) {
                // Run a bit more to flush
                for (_ <- 0 until 5000) {
                  dut.clockDomain.waitSampling()
                  if (dut.io.uartTxValid.toBoolean) {
                    val c = dut.io.uartTxData.toInt
                    uartOutput.append(if (c >= 32 && c < 127) c.toChar else '\n')
                  }
                }
                done = true
              }
              lineBuffer.clear()
            }
          } else if (char >= 32 && char < 127) {
            lineBuffer.append(char.toChar)
          }
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '\n')
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          val pcStr = (0 until cpuCnt).map(i => f"C${i}:PC=${dut.io.pc(i).toInt}%04x").mkString(" ")
          val haltedStr = (0 until cpuCnt).map(i => if (dut.io.halted(i).toBoolean) "H" else ".").mkString
          println(f"\n[$cycle%8d] $pcStr halted=$haltedStr")
        }
      }

      if (lineBuffer.nonEmpty) {
        val line = lineBuffer.toString
        println(line)
        logLine(f"[$cycle%8d] $line")
      }

      log.close()

      println(s"\n=== SMP Cache Stress Test Complete ($cpuCnt cores, $cycle cycles) ===")
      println(s"UART Output:\n${uartOutput.toString}")
      println(s"Log: $logFilePath")

      val output = uartOutput.toString
      if (output.contains("SmpCacheTest PASS")) {
        run.finish("PASS", s"$cpuCnt cores, $cycle cycles, cache coherency verified")
        println("PASS: SMP cache coherency test passed!")
      } else if (output.contains("SmpCacheTest FAIL")) {
        run.finish("FAIL", "Cache coherency verification failed")
        println("FAIL: Cache coherency verification failed!")
        System.exit(1)
      } else {
        run.finish("FAIL", s"Test did not complete in $cycle cycles")
        println("FAIL: Test did not complete in time!")
        System.exit(1)
      }
    }
}
