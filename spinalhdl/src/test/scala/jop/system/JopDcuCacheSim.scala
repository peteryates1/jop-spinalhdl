package jop.system
import jop.config._

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import jop.utils.{JopFileLoader, JopSimDefaults, TestHistory}
import java.io.PrintWriter

/**
 * DoAll.jop test through LruCacheCore to verify the pipelined cache is
 * functionally correct. This exercises the same memory path as DDR3 FPGA
 * but in simulation where timing doesn't matter.
 */
object JopDcuCacheSim extends App {

  // Optional MSHR count, e.g. `Test/runMain jop.system.JopDcuCacheSim 4`.
  // Running the whole JVM suite through a non-blocking cache is the broadest
  // correctness check there is for the MSHR file: 66 tests' worth of real
  // access patterns, with hits served under outstanding misses and refills
  // completing out of order. Default 1 keeps the CI job on the blocking path.
  val mshrCount = args.headOption.map(_.toInt).getOrElse(1)

  val jopFilePath = "java/apps/JvmTests/DoAll.jop"
  val romFilePath = MicrocodePaths.simulationRom
  val ramFilePath = MicrocodePaths.simulationRam
  val logFilePath = "build/sim-logs/dcu_cache_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 512 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"Log file: $logFilePath")

  // Match wukongFull core config (all CUs enabled)
  val fullCuConfig = JopCoreConfig(
    useDspMul = true,
    bytecodes = Map("*" -> "hw"))

  // Zero latency: tests pure cache logic (hit/miss/evict/writeback) without DDR3 timing overhead
  // This sim ran with NO seed until 2026-08-18 -- `doSim` drew a fresh one
  // every run and never printed it, so a CI failure here could not be replayed
  // at all. It is one of the three jvm-suite jobs, and it failed CI five times.
  private val simSeed: Int = JopSimDefaults.seed()

  JopSimDefaults.config
    .compile(JopCoreWithCacheTestHarness(romData, ramData, mainMemData,
      readLatency = 0, writeLatency = 0, coreConfigOverride = Some(fullCuConfig),
      mshrCount = mshrCount))
    .doSim(seed = simSeed) { dut =>
      val log = { new java.io.File(logFilePath).getParentFile.mkdirs(); new PrintWriter(logFilePath) }
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JOP DoAll Cache Simulation Log ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 60000000  // 60M cycles
      val reportInterval = 500000
      var done = false
      var cycle = 0
      var testCount = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // Check for UART output
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          val ch = if (char >= 32 && char < 127) char.toChar else '.'
          uartOutput.append(ch)
          print(ch)
          if (char == 0x0A || char == 0x0D) print('\n')
          logLine(f"[$cycle%8d] UART: '$ch' (0x$char%02x)")

          // Count " ok" occurrences
          val output = uartOutput.toString
          if (output.endsWith(" ok")) {
            testCount += 1
          }
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          val memState = dut.io.debugMemState.toInt
          println(f"\n[$cycle%8d] PC=$pc%04x JPC=$jpc%04x memState=$memState tests=$testCount")
        }

        // Success: all 59 tests pass
        if (testCount >= 59) {
          println(s"\n*** All $testCount tests passed! ***")
          done = true
        }
      }

      log.close()

      println(s"\n\n=== Simulation Complete (${cycle} cycles) ===")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Tests passed: $testCount/59")

      if (testCount >= 59) {
        println("PASS: All 59 tests pass through cache path")
      } else {
        println(s"FAIL: Only $testCount/59 tests passed (possible hang)")
        System.exit(1)
      }
    }
}
