package jop.system

import spinal.core.sim._
import jop.utils.JopFileLoader
import jop.config.MicrocodePaths

/**
 * Fast vehicle for the item 136 / TextFormatTest reproducer.
 *
 * 512 KB BRAM so java.text fits, and a short cycle budget: the app prints a
 * marker per step and parks, so it either reaches "Z DONE" or stops at the
 * marker naming the operation that died. Stops early on either.
 */
object StrLitReproSim extends App {
  val jopFilePath = jop.utils.SimApp.jop("Small", "StrLitRepro")
  val romData = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
  val ramData = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)
  val bramSize = 512 * 1024
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, bramSize / 4)

  // Caches are ON, as every board ships. Turning them OFF was tried and changes
  // NOTHING -- the -1 below is identical either way, which is what ruled the
  // object and array caches out.
  //   val noCache = JopCoreConfig(memConfig = JopMemoryConfig(
  //     mainMemSize = bramSize, useOcache = false, useAcache = false))
  SimConfig.compile(JopCoreTestHarness(romData, ramData, mainMemData, memSize = bramSize))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val uart = new StringBuilder
      var cycle = 0
      var done = false
      val maxCycles = 20000000
      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()
        if (dut.io.uartTxValid.toBoolean) {
          val c = dut.io.uartTxData.toInt
          uart.append(if (c >= 32 && c < 127) c.toChar else if (c == 10) '\n' else '.')
          if (uart.endsWith("Z DONE\n")) done = true
        }
      }
      println("=== UART ===")
      println(uart.toString)
      println(s"=== $cycle cycles, reached DONE: $done ===")
    }
}
