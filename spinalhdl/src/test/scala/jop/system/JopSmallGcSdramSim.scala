package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.memory.sdram.sdr._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import jop.utils.{JopFileLoader, TestHistory}
import java.io.PrintWriter

/**
 * SDRAM simulation for the Small GC test app
 */
object JopSmallGcSdramSim extends App {

  val jopFilePath = "java/apps/Small/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/small_gc_sdram_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"Log file: $logFilePath")

  val run = TestHistory.startRun("JopSmallGcSdramSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

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

      val maxCycles = 50000000  // 50M cycles — need enough for multiple GC cycles with mark-compact
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

        // Exit after multiple GC cycles (mark-compact: heap fills ~R24, so R80 = ~3 GC cycles)
        val output = uartOutput.toString
        if (output.contains("R80 f=") || output.contains("Uncaught exception")) {
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
        run.finish("FAIL", "Uncaught exception detected")
        println("FAIL: Uncaught exception detected")
        System.exit(1)
      }
      if (!output.contains("GC test start")) {
        run.finish("FAIL", "Did not see 'GC test start'")
        println("FAIL: Did not see 'GC test start'")
        System.exit(1)
      }
      if (!output.contains("R0 f=")) {
        run.finish("FAIL", "Did not see allocation rounds")
        println("FAIL: Did not see allocation rounds")
        System.exit(1)
      }
      // Verify GC actually reclaimed memory (free went up at some point)
      val freePattern = """R\d+ f=(\d+)""".r
      val freeVals = freePattern.findAllMatchIn(output).map(_.group(1).toInt).toList
      val gcOccurred = freeVals.length >= 2 && freeVals.sliding(2).exists { case List(a, b) => b > a case _ => false }
      if (!gcOccurred) {
        run.finish("FAIL", "GC never triggered (free memory never increased)")
        println("FAIL: GC never triggered (free memory never increased)")
        System.exit(1)
      }
      val gcCycles = freeVals.sliding(2).count { case List(a, b) => b > a case _ => false }
      run.finish("PASS", s"$cycle cycles, $gcCycles GC cycles observed on SDRAM")
      println("PASS: GC allocation test working on SDRAM")
    }
}
