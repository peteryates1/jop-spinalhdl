package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import java.io.PrintWriter

/**
 * GHDL simulation for the Small GC test app.
 * GHDL is event-driven (like Questa/ModelSim) — propagates X/U values
 * unlike Verilator which converts X to 0. If GHDL shows different
 * behavior from Verilator, it indicates uninitialized signal issues
 * that could explain FPGA-vs-simulation divergence.
 */
object JopSmallGcGhdlSim extends App {

  val jopFilePath = "java/apps/Small/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/small_gc_ghdl_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"Log file: $logFilePath")
  println("Using GHDL backend (event-driven simulator)")

  SimConfig
    .withGhdl
    .compile(JopCoreTestHarness(romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JOP Small GC GHDL Simulation Log ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 20000000  // 20M cycles
      val reportInterval = 100000
      var startTime = System.currentTimeMillis()
      var done = false
      var cycle = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // Check for exception firing
        if (dut.io.excFired.toBoolean) {
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          val excType = dut.io.excType.toInt
          val aout = dut.io.aout.toLong & 0xFFFFFFFFL
          val bout = dut.io.bout.toLong & 0xFFFFFFFFL
          println(f"\n[$cycle%8d] *** EXCEPTION type=$excType PC=$pc%04x JPC=$jpc%04x aout=0x$aout%08x bout=0x$bout%08x ***")
          logLine(f"[$cycle%8d] EXCEPTION type=$excType PC=$pc%04x JPC=$jpc%04x aout=0x$aout%08x bout=0x$bout%08x")
        }

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
          val rate = if (elapsed > 0) (cycle / elapsed).toInt else 0
          println(f"\n[$cycle%8d] PC=$pc%04x JPC=$jpc%04x rate=$rate cycles/sec UART: '${uartOutput.toString}'")
        }

        // Exit after a full GC cycle
        val output = uartOutput.toString
        if (output.contains("R14 f=") || output.contains("Uncaught exception")) {
          // Capture more output
          for (_ <- 0 until 50000) {
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

      println(s"\n\n=== GHDL Simulation Complete (${cycle} cycles) ===")
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
      println("PASS: GC allocation test working on GHDL")
    }
}
