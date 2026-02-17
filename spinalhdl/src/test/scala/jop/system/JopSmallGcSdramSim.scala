package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.memory.sdram.sdr._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import jop.utils.JopFileLoader
import java.io.PrintWriter

/**
 * SDRAM simulation for the Small GC test app
 */
object JopSmallGcSdramSim extends App {

  val jopFilePath = "/home/peter/workspaces/ai/jop/java/apps/Small/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat"
  val logFilePath = "/home/peter/workspaces/ai/jop/spinalhdl/small_gc_sdram_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"Log file: $logFilePath")

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .compile(JopCoreWithSdramTestHarness(romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JOP Small GC SDRAM Simulation Log ===")

      dut.clockDomain.forkStimulus(10)

      val sdramModel = SdramModel(
        io = dut.io.sdram,
        layout = dut.sdramLayout,
        clockDomain = dut.clockDomain
      )

      // Initialize SDRAM
      for (wordIdx <- mainMemData.indices) {
        val word = mainMemData(wordIdx).toLong & 0xFFFFFFFFL
        val byteAddr = wordIdx * 4
        sdramModel.write(byteAddr + 0, ((word >>  0) & 0xFF).toByte)
        sdramModel.write(byteAddr + 1, ((word >>  8) & 0xFF).toByte)
        sdramModel.write(byteAddr + 2, ((word >> 16) & 0xFF).toByte)
        sdramModel.write(byteAddr + 3, ((word >> 24) & 0xFF).toByte)
      }

      dut.clockDomain.waitSampling(5)

      val maxCycles = 20000000  // 20M cycles — need enough for GC cycle
      val reportInterval = 100000
      var startTime = System.currentTimeMillis()
      var done = false
      var cycle = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // Check for UART output
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          print(if (char >= 32 && char < 127) char.toChar else '.')
          logLine(f"[$cycle%8d] UART: '${if (char >= 32 && char < 127) char.toChar.toString else f"\\x$char%02x"}' (0x$char%02x)")
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
          val rate = cycle / elapsed
          println(f"\n[$cycle%8d] PC=$pc%04x JPC=$jpc%04x rate=${rate.toInt} cycles/sec UART: '${uartOutput.toString}'")
        }

        // Exit after a full GC cycle (R12 triggers GC, R14+ confirms it completed)
        val output = uartOutput.toString
        if (output.contains("R14 f=") || output.contains("Uncaught exception")) {
          // Capture more output
          for (_ <- 0 until 100000) {
            dut.clockDomain.waitSampling()
            if (dut.io.uartTxValid.toBoolean) {
              val char = dut.io.uartTxData.toInt
              uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
              print(if (char >= 32 && char < 127) char.toChar else '.')
            }
          }
          done = true
        }
      }

      log.close()

      println(s"\n\n=== Simulation Complete (${cycle} cycles) ===")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Log written to: $logFilePath")

      val output = uartOutput.toString
      if (output.contains("Uncaught exception")) {
        println("FAIL: Uncaught exception detected")
        System.exit(1)
      }
      if (!output.contains("GC test start")) {
        println("FAIL: Did not see 'GC test start'")
        System.exit(1)
      }
      if (!output.contains("R0 f=")) {
        println("FAIL: Did not see allocation rounds")
        System.exit(1)
      }
      println("PASS: GC allocation test working on SDRAM")
    }
}
