package jop.system

import spinal.core._
import spinal.lib.io.InOutWrapper
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import java.io.PrintWriter

/**
 * Generate Verilog for the SDRAM GC test harness to run in Questa/ModelSim.
 *
 * Produces:
 *   1. JopCoreWithSdramTestHarness.v — full design with SDRAM interface
 *   2. sdram_init.hex — memory initialization file for the SDRAM model
 *
 * The external SDRAM model (sdram_model.v) connects to the SDRAM pins
 * and checks for SDRAM protocol timing violations that SpinalHDL's
 * SdramModel doesn't enforce.
 */
object JopSmallGcSdramQuestaGen extends App {

  val jopFilePath = "/home/peter/workspaces/ai/jop/java/apps/Small/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat"
  val targetDir   = "verification/questa-sdram"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")

  // Generate Verilog
  SpinalConfig(
    mode = Verilog,
    targetDirectory = targetDir,
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generate(InOutWrapper(JopCoreWithSdramTestHarness(romData, ramData, mainMemData)))

  // Write memory hex file for SDRAM model initialization
  // Format: one 32-bit word per line in hex, little-endian byte order
  // (matches SDRAM byte addressing: word N at byte address 4*N)
  val hexFile = new PrintWriter(s"$targetDir/sdram_init.hex")
  for (wordIdx <- mainMemData.indices) {
    hexFile.println(f"${mainMemData(wordIdx).toLong & 0xFFFFFFFFL}%08x")
  }
  hexFile.close()

  println(s"Generated: $targetDir/JopCoreWithSdramTestHarness.v")
  println(s"Generated: $targetDir/sdram_init.hex (${mainMemData.length} words)")
  println(s"Run with: cd $targetDir && make sim")
}
