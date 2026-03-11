package jop.system

import spinal.core._
import spinal.core.sim._
import jop.config._
import jop.utils.{JopFileLoader, TestHistory}
import java.io.PrintWriter

/**
 * JopTop BRAM simulation — exercises the unified JopTop in simulation mode.
 *
 * Uses JopTop(simulation=true) with BRAM memory, verifying that the unified
 * top-level works end-to-end. UART output is decoded from the bit-level
 * io.ser_txd signal (2 Mbaud at 100 MHz = 50 cycles/bit).
 *
 * Usage:
 *   sbt "Test / runMain jop.system.JopTopBramSim"
 */
object JopTopBramSim extends App {

  val jopFilePath = "java/apps/Smallest/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/joptop_bram_simulation.log"
  val bramSize = 256 * 1024  // 256KB

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, bramSize / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")

  val config = JopConfig.wukongBram

  val run = TestHistory.startRun("JopTopBramSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .compile(JopTop(
      config = config,
      romInit = romData,
      ramInit = ramData,
      mainMemInit = Some(mainMemData),
      mainMemSize = bramSize,
      simulation = true
    ))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      val uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JopTop BRAM Simulation Log ===")
      logLine(s"Config: ${config.entityName}")
      logLine(s"ROM: ${romData.length}, RAM: ${ramData.length}, Main: ${mainMemData.length}")
      logLine("")

      // Initialize clock (100 MHz = 10ns period)
      dut.clockDomain.forkStimulus(10)

      // Drive UART RX idle (HIGH)
      dut.io.ser_rxd #= true

      dut.clockDomain.waitSampling(5)

      // UART bit decoder: 2 Mbaud at 100 MHz = 50 cycles/bit
      val cyclesPerBit = 50

      fork {
        while (true) {
          // Wait for start bit (falling edge: ser_txd goes LOW)
          dut.clockDomain.waitSamplingWhere(!dut.io.ser_txd.toBoolean)

          // Wait to middle of first data bit (1.5 bit periods from start edge)
          for (_ <- 0 until cyclesPerBit + cyclesPerBit / 2) {
            dut.clockDomain.waitSampling()
          }

          // Sample 8 data bits (LSB first)
          var byte = 0
          for (bit <- 0 until 8) {
            if (dut.io.ser_txd.toBoolean) byte |= (1 << bit)
            if (bit < 7) {
              for (_ <- 0 until cyclesPerBit) dut.clockDomain.waitSampling()
            }
          }

          val char = byte & 0xFF
          val charStr = if (char >= 32 && char < 127) char.toChar.toString else f"\\x$char%02x"
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          logLine(s"*** UART TX: '$charStr' (0x${f"$char%02x"}) ***")
          print(if (char >= 32 && char < 127) char.toChar else '.')

          // Wait through stop bit
          for (_ <- 0 until cyclesPerBit) dut.clockDomain.waitSampling()
        }
      }

      // Main simulation loop
      val maxCycles = 2000000
      val reportInterval = 100000

      logLine(s"Starting simulation for $maxCycles cycles...")

      for (cycle <- 0 until maxCycles) {
        dut.clockDomain.waitSampling()

        if (cycle > 0 && cycle % reportInterval == 0) {
          println(f"\n[$cycle%7d] UART so far: '${uartOutput.toString}'")
        }
      }

      // Final summary
      logLine("")
      logLine("=" * 60)
      logLine("=== Simulation Summary ===")
      logLine(s"Total cycles: $maxCycles")
      logLine(s"UART Output: '${uartOutput.toString}'")
      logLine("=" * 60)

      log.close()

      println(s"\n\n=== JopTop BRAM Simulation Complete ===")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Log: $logFilePath")

      run.finish(
        if (uartOutput.toString.contains("Hello World")) "PASS" else "CHECK",
        s"$maxCycles cycles, UART: '${uartOutput.toString}'"
      )
    }
}
