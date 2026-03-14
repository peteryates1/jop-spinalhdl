package jop.system

import spinal.core._
import spinal.core.sim._
import jop.config._
import jop.sim.SimDisplay
import jop.utils.JopFileLoader
import java.io.PrintWriter

/**
 * JopTop VGA BRAM simulation — exercises VGA text output in simulation mode.
 *
 * Uses JopTop(simulation=true) with BRAM memory and VGA text enabled.
 * A SimDisplay window shows the VGA output. UART output is also decoded.
 *
 * Usage:
 *   sbt "Test / runMain jop.system.JopVgaBramSim"
 */
object JopVgaBramSim extends App {

  val jopFilePath = "java/apps/Small/VgaTest.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/joptop_vga_bram_simulation.log"
  val bramSize = 256 * 1024  // 256KB

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, bramSize / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")

  // Use wukongBram config but with VGA text enabled
  val config = JopConfig(
    assembly = SystemAssembly.wukong,
    systems = Seq(JopSystem(
      name = "main",
      memory = "bram",
      bootMode = BootMode.Simulation,
      clkFreq = 100 MHz,
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart), "vga" -> DeviceInstance(DeviceType.VgaText)),
      coreConfig = JopCoreConfig(bytecodes = Map("idiv" -> "hw", "irem" -> "hw")))))

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

      logLine("=== JopTop VGA BRAM Simulation Log ===")

      // Initialize clocks
      dut.clockDomain.forkStimulus(10)    // 100 MHz system clock
      dut.deviceClockDomains.vgaCd.get.forkStimulus(40)          // 25 MHz pixel clock

      // Drive UART RX idle (HIGH)
      dut.io.ser_rxd #= true

      dut.clockDomain.waitSampling(5)

      // VGA display window
      val display = new SimDisplay(scale = 2)

      // VGA pin references (auto-generated outside io bundle)
      val vgaHs = dut.ioPinMap("vga_hs").asInstanceOf[Bool]
      val vgaVs = dut.ioPinMap("vga_vs").asInstanceOf[Bool]
      val vgaR  = dut.ioPinMap("vga_r").asInstanceOf[Bits]
      val vgaG  = dut.ioPinMap("vga_g").asInstanceOf[Bits]
      val vgaB  = dut.ioPinMap("vga_b").asInstanceOf[Bits]

      // VGA capture fork (runs on pixel clock)
      fork {
        while (true) {
          dut.deviceClockDomains.vgaCd.get.waitSampling()
          display.tick(
            vgaHs.toBoolean,
            vgaVs.toBoolean,
            vgaR.toInt,
            vgaG.toInt,
            vgaB.toInt)
        }
      }

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
          val charStr = if (char >= 32 && char < 127) char.toChar.toString else f"\\x$char%02x"
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          logLine(s"*** UART TX: '$charStr' (0x${f"$char%02x"}) ***")
          print(if (char >= 32 && char < 127) char.toChar else '.')

          for (_ <- 0 until cyclesPerBit) dut.clockDomain.waitSampling()
        }
      }

      // Main simulation loop — run long enough for VGA frames to appear
      val maxCycles = 10000000
      val reportInterval = 1000000

      logLine(s"Starting simulation for $maxCycles cycles...")
      logLine(s"VGA frames at ~60Hz = ~1.67M pixel clocks/frame = ~6.67M sys clocks/frame")

      for (cycle <- 0 until maxCycles) {
        dut.clockDomain.waitSampling()

        if (cycle > 0 && cycle % reportInterval == 0) {
          println(f"\n[$cycle%7d] frames=${display.frames} UART: '${uartOutput.toString}'")
        }
      }

      logLine("")
      logLine("=== Simulation Summary ===")
      logLine(s"Total cycles: $maxCycles")
      logLine(s"VGA frames: ${display.frames}")
      logLine(s"UART Output: '${uartOutput.toString}'")

      log.close()
      display.close()

      println(s"\n\n=== JopTop VGA BRAM Simulation Complete ===")
      println(s"VGA frames rendered: ${display.frames}")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Log: $logFilePath")
    }
}
