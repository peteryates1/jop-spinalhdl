package jop.system

import spinal.core._
import spinal.lib.io.InOutWrapper
import jop.utils.JopFileLoader
import jop.pipeline.JumpTableInitData
import java.io.PrintWriter

/**
 * Generate Verilog for the DDR3 GC test to run in Vivado xsim.
 *
 * Produces:
 *   1. JopDdr3Top.v — full design with DDR3 interface (simulation jump table)
 *   2. ddr3_init.hex — memory initialization file for the DDR3 behavioral model
 *
 * The external DDR3 model (ddr3_model.sv from Xilinx MIG IP) connects to
 * JopDdr3Top's DDR3 pins. The real MIG RTL handles PHY, controller, and
 * calibration — matching the exact FPGA logic path.
 */
object JopDdr3SimGen extends App {

  val jopFilePath = "java/apps/Small/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val targetDir   = "verification/vivado-ddr3"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")

  // Generate Verilog with simulation jump table (skip serial download)
  SpinalConfig(
    mode = Verilog,
    targetDirectory = targetDir,
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = LOW
    )
  ).generate(InOutWrapper(JopDdr3Top(romData, ramData, JumpTableInitData.simulation)))

  // Write memory hex file for DDR3 model initialization
  // Format: one 32-bit word per line in hex
  val hexFile = new PrintWriter(s"$targetDir/ddr3_init.hex")
  for (wordIdx <- mainMemData.indices) {
    hexFile.println(f"${mainMemData(wordIdx).toLong & 0xFFFFFFFFL}%08x")
  }
  hexFile.close()

  println(s"Generated: $targetDir/JopDdr3Top.v")
  println(s"Generated: $targetDir/ddr3_init.hex (${mainMemData.length} words)")
  println(s"Run with: cd $targetDir && make sim")
}
