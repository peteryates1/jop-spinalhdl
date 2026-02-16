package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import java.io.PrintWriter

/**
 * Block RAM simulation for comparison with SDRAM
 */
object JopSystemBramSim extends App {

  // Paths to initialization files
  val jopFilePath = "/home/peter/git/jopmin/java/Smallest/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat"
  val logFilePath = "/home/peter/workspaces/ai/jop/core/spinalhdl/bram_simulation.log"

  // Load initialization data
  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 32 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")
  println(s"Log file: $logFilePath")

  SimConfig
    .compile(JopSystemTestHarness(romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder
      var lastPc = -1
      var lastJpc = -1
      var ioWriteCount = 0

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JOP BRAM Simulation Log ===")
      logLine(s"ROM: ${romData.length} entries, RAM: ${ramData.length} entries, Main: ${mainMemData.length} entries")
      logLine("")

      // Initialize clock
      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz

      dut.clockDomain.waitSampling(5)

      // Run simulation
      val maxCycles = 200000
      val reportInterval = 10000

      logLine(s"Starting simulation for $maxCycles cycles...")
      logLine("")
      logLine("Format: [cycle] PC=hex JPC=hex event...")
      logLine("-" * 80)

      for (cycle <- 0 until maxCycles) {
        dut.clockDomain.waitSampling()

        val pc = dut.io.pc.toInt
        val jpc = dut.io.jpc.toInt
        val memBusy = dut.io.memBusy.toBoolean

        // Log PC/JPC changes (first 100 cycles, then every 100 cycles)
        val shouldLogState = (cycle < 100) || (cycle % 100 == 0)
        if (shouldLogState && (pc != lastPc || jpc != lastJpc)) {
          val busyStr = if (memBusy) " BUSY" else ""
          logLine(f"[$cycle%6d] PC=$pc%04x JPC=$jpc%04x$busyStr")
          lastPc = pc
          lastJpc = jpc
        }

        // Check for UART output from harness
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          val charStr = if (char >= 32 && char < 127) char.toChar.toString else f"\\x$char%02x"
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          logLine(f"[$cycle%6d] *** UART TX: '$charStr' (0x$char%02x) ***")
          print(if (char >= 32 && char < 127) char.toChar else '.')
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          println(f"\n[$cycle%6d] PC=$pc%04x JPC=$jpc%04x UART so far: '${uartOutput.toString}'")
        }
      }

      // Final summary
      logLine("")
      logLine("=" * 80)
      logLine("=== Simulation Summary ===")
      logLine(s"Total cycles: $maxCycles")
      logLine(s"Final PC: ${dut.io.pc.toInt}")
      logLine(s"Final JPC: ${dut.io.jpc.toInt}")
      logLine(s"memBusy: ${dut.io.memBusy.toBoolean}")
      if (uartOutput.nonEmpty) {
        logLine(s"UART Output: '${uartOutput.toString}'")
      } else {
        logLine("UART Output: (none)")
      }
      logLine("=" * 80)

      log.close()

      println(s"\n\n=== Simulation Complete ===")
      println(s"Final PC: ${dut.io.pc.toInt}")
      println(s"Final JPC: ${dut.io.jpc.toInt}")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Log written to: $logFilePath")
    }
}
