package jop.system

import spinal.core._
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig

/**
 * Generate Verilog for the BRAM GC test harness to run in Questa/ModelSim.
 * Produces a self-contained Verilog module with all memory pre-initialized.
 */
object JopSmallGcQuestaGen extends App {

  val jopFilePath = "/home/peter/workspaces/ai/jop/java/apps/Small/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "verification/questa",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generate(JopCoreTestHarness(romData, ramData, mainMemData))

  println("Generated: verification/questa/JopCoreTestHarness.v")
  println("Run with: cd verification/questa && make sim")
}
