package jop.system

import spinal.core._
import spinal.core.sim._
import jop.config._
import jop.utils.JopFileLoader
import java.io.PrintWriter

/**
 * JopTop BRAM simulation with ALL compute units (including DCU).
 *
 * Uses JopTop(simulation=true) with bit-level UART decoding.
 * Purpose: reproduce DCU hang that occurs on DDR3 FPGA (DoubleField test 48).
 * Uses DoAll.jop with 4MB BRAM — enough for all tests including heap.
 *
 * 2 Mbaud at 100 MHz = 50 cycles/bit.
 */
object JopTopDcuBramSim extends App {

  val jopFilePath = "java/apps/JvmTests/DoAll.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/joptop_dcu_bram_simulation.log"
  val bramSize = 4 * 1024 * 1024  // 4MB

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, bramSize / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")

  // wukongBramFull: BRAM + all CUs (ICU+FCU+LCU+DCU) including DSP mul
  val config = JopConfig.wukongBramFull

  println(s"Config: ${config.entityName}")
  println(s"Core config: imul=${config.system.coreConfig.impl("imul")}, " +
    s"fadd=${config.system.coreConfig.impl("fadd")}, " +
    s"ladd=${config.system.coreConfig.impl("ladd")}, " +
    s"dadd=${config.system.coreConfig.impl("dadd")}")

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
      var lineBuffer = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JopTop DCU BRAM Simulation ===")
      logLine(s"Config: ${config.entityName}")
      logLine(s"All CUs enabled (ICU+FCU+LCU+DCU)")
      logLine("")

      dut.clockDomain.forkStimulus(10)
      dut.io.ser_rxd #= true
      dut.clockDomain.waitSampling(5)

      // UART bit decoder: 2 Mbaud at 100 MHz = 50 cycles/bit
      val cyclesPerBit = 50

      fork {
        while (true) {
          dut.clockDomain.waitSamplingWhere(!dut.io.ser_txd.toBoolean)

          for (_ <- 0 until cyclesPerBit + cyclesPerBit / 2) {
            dut.clockDomain.waitSampling()
          }

          var byte = 0
          for (bit <- 0 until 8) {
            if (dut.io.ser_txd.toBoolean) byte |= (1 << bit)
            if (bit < 7) {
              for (_ <- 0 until cyclesPerBit) dut.clockDomain.waitSampling()
            }
          }

          val char = byte & 0xFF
          if (char == 10) {  // newline
            val line = lineBuffer.toString
            println(line)
            logLine(line)
            lineBuffer.clear()
          } else if (char == 13) {
            // ignore CR
          } else if (char >= 32 && char < 127) {
            lineBuffer.append(char.toChar)
          }
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '\n')

          for (_ <- 0 until cyclesPerBit) dut.clockDomain.waitSampling()
        }
      }

      val maxCycles = 120000000  // 120M cycles
      val reportInterval = 1000000

      logLine(s"Starting simulation for $maxCycles cycles...")

      for (cycle <- 0 until maxCycles) {
        dut.clockDomain.waitSampling()

        if (cycle > 0 && cycle % reportInterval == 0) {
          println(f"  [$cycle%9d cycles]")
        }
      }

      if (lineBuffer.nonEmpty) {
        println(lineBuffer.toString)
        logLine(lineBuffer.toString)
      }

      logLine("")
      logLine("=== Simulation Complete ===")
      logLine(s"UART Output:\n${uartOutput.toString}")
      log.close()

      println(s"\n=== Simulation Complete ($maxCycles cycles) ===")
      println(s"Log: $logFilePath")
    }
}
