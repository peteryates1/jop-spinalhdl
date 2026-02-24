package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.{JopFileLoader, TestHistory}
import jop.memory.JopMemoryConfig
import java.io.PrintWriter

/**
 * SMP BRAM simulation running JVM test suite (jvm.DoAll) on 2 cores.
 *
 * Both cores invoke main() via the SMP boot protocol in Startup.java.
 * Core 0 runs the test suite and prints results via UART.
 * Core 1 also runs the test suite (creating bus arbitration pressure,
 * method cache contention, and object/array cache stress) but its UART
 * output is silently dropped (only core 0 has a UART).
 *
 * This tests correctness under multi-core arbitration and exercises:
 * - Method cache under contention (both cores loading same methods)
 * - Object cache snoop invalidation (both cores accessing objects)
 * - Array cache under contention
 * - Memory controller arbitration
 * - Exception handling under SMP
 *
 * Usage: sbt "Test / runMain jop.system.JopJvmTestsSmpBramSim"
 */
object JopJvmTestsSmpBramSim extends App {
  val cpuCnt = 2

  val jopFilePath = "java/apps/JvmTests/DoAll.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/jvmtests_smp_bram_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 256 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"CPU count: $cpuCnt")
  println(s"Log file: $logFilePath")

  val run = TestHistory.startRun("JopJvmTestsSmpBramSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .compile(JopSmpTestHarness(cpuCnt, romData, ramData, mainMemData, memSize = 256 * 1024))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder
      var lineBuffer = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine(s"=== JOP JVM Tests SMP BRAM Simulation ($cpuCnt cores) ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 40000000  // 40M cycles -- SMP is slower due to arbitration
      val reportInterval = 1000000
      var done = false
      var cycle = 0
      var testsPassed = 0
      var testsFailed = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // Check for exception firing (core 0)
        if (dut.io.excFired.toBoolean) {
          val pc0 = dut.io.pc(0).toInt
          val jpc0 = dut.io.jpc(0).toInt
          logLine(f"[$cycle%8d] EXCEPTION PC=$pc0%04x JPC=$jpc0%04x")
        }

        // Check for UART output (core 0 only)
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          if (char == 10) {  // newline
            val line = lineBuffer.toString
            println(line)
            logLine(f"[$cycle%8d] $line")
            if (line.endsWith(" ok")) testsPassed += 1
            if (line.endsWith(" failed!")) testsFailed += 1
            if (line.contains("JVM exit!")) {
              // Run a bit more to flush output
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
          } else if (char >= 32 && char < 127) {
            lineBuffer.append(char.toChar)
          }
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '\n')
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          val pcStr = (0 until cpuCnt).map(i => f"C${i}:PC=${dut.io.pc(i).toInt}%04x").mkString(" ")
          val haltedStr = (0 until cpuCnt).map(i => if (dut.io.halted(i).toBoolean) "H" else ".").mkString
          println(f"  [$cycle%8d] $pcStr halted=$haltedStr passed=$testsPassed failed=$testsFailed")
        }
      }

      if (lineBuffer.nonEmpty) {
        val line = lineBuffer.toString
        println(line)
        logLine(f"[$cycle%8d] $line")
      }

      logLine("")
      logLine("=== Simulation Complete ===")
      logLine(s"UART Output:\n${uartOutput.toString}")
      log.close()

      println(s"\n=== SMP JVM Tests Simulation Complete ($cpuCnt cores, $cycle cycles) ===")
      println(s"Tests passed: $testsPassed, Tests failed: $testsFailed")
      println(s"Log: $logFilePath")

      if (testsFailed > 0) {
        run.finish("FAIL", s"$testsFailed tests failed on $cpuCnt-core SMP")
        println(s"FAIL: $testsFailed tests failed!")
        System.exit(1)
      }
      if (testsPassed == 0) {
        run.finish("FAIL", s"No tests completed on $cpuCnt-core SMP in $cycle cycles")
        println("FAIL: No tests completed!")
        System.exit(1)
      }
      run.finish("PASS", s"$testsPassed tests passed on $cpuCnt-core SMP in $cycle cycles")
      println(s"PASS: $testsPassed tests passed on $cpuCnt-core SMP!")
    }
}
