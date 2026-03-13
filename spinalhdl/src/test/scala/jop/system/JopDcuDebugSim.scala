// Quick debug to find where wukongFull BRAM sim gets stuck
package jop.system

import spinal.core._
import spinal.core.sim._
import jop.config._
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig

object JopDcuDebugSim extends App {
  // Test WITHOUT DCU — same config approach, to verify the sim works
  val baseCoreConfig = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = 256 * 1024)
  )
  val coreConfig = baseCoreConfig

  val romData = JopFileLoader.loadMicrocodeRom("asm/generated/mem_rom.dat")
  val ramData = JopFileLoader.loadStackRam("asm/generated/mem_ram.dat")
  val mainMemData = JopFileLoader.jopFileToMemoryInit(
    "java/apps/Smallest/HelloWorld.jop", 256 * 1024 / 4)

  println(s"ROM: ${romData.length}, RAM: ${ramData.length}, Mem: ${mainMemData.length}")
  println(s"Config: imul=${coreConfig.impl("imul")}, dadd=${coreConfig.impl("dadd")}, dcmpl=${coreConfig.impl("dcmpl")}")
  println(s"needsDoubleCompute=${coreConfig.needsDoubleCompute}")

  SimConfig
    .compile(JopCoreTestHarness(romData, ramData, mainMemData))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      var lastPc = -1
      var stuckCount = 0
      var uartCount = 0
      val maxCycles = 5000000

      for (cycle <- 0 until maxCycles) {
        dut.clockDomain.waitSampling()
        val pc = dut.io.pc.toInt
        val memBusy = dut.io.memBusy.toBoolean

        if (pc == lastPc) stuckCount += 1 else stuckCount = 0

        if (stuckCount == 100) {
          val a = dut.io.aout.toLong & 0xFFFFFFFFL
          val b = dut.io.bout.toLong & 0xFFFFFFFFL
          val sp = dut.io.debugSp.toInt
          val vp = dut.io.debugVp.toInt
          val jpc = dut.io.jpc.toInt
          val instr = dut.io.instr.toInt
          println(f"STUCK at cycle $cycle: PC=$pc%04x JPC=$jpc%04x INSTR=$instr%03x SP=$sp%04x VP=$vp%04x A=$a%08x B=$b%08x memBusy=$memBusy")
        }

        if (stuckCount == 200) {
          println(s"HUNG at cycle $cycle — aborting")
          simFailure(s"Hung at PC=0x${pc.toHexString}")
        }

        if (dut.io.uartTxValid.toBoolean) {
          val ch = dut.io.uartTxData.toInt
          uartCount += 1
          if (uartCount <= 500) {
            val repr = if (ch >= 32 && ch < 127) s"'${ch.toChar}'" else f"0x$ch%02x"
            println(f"[$cycle%7d] UART #$uartCount: $repr")
          }
          print(if (ch >= 32 && ch < 127) ch.toChar.toString else if (ch == 10) "\n" else "")
        }

        if (cycle > 0 && cycle % 500000 == 0) {
          println(f"  [$cycle%7d cycles] uartChars=$uartCount")
        }

        lastPc = pc
      }
      println(s"\nCompleted $maxCycles cycles without hang")
    }
}
