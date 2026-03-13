package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.{JopFileLoader, TestHistory}
import jop.memory.JopMemoryConfig
import java.io.PrintWriter

/**
 * Block RAM simulation for comparison with SDRAM
 */
object JopCoreBramSim extends App {

  // Paths to initialization files
  val jopFilePath = "java/apps/Small/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/bram_simulation.log"

  // Load initialization data
  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 256 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")
  println(s"Log file: $logFilePath")

  val run = TestHistory.startRun("JopCoreBramSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .compile(JopCoreTestHarness(romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder
      var lastPc = -1
      var lastJpc = -1
      var ioWriteCount = 0

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JOP BRAM Simulation Log ===")
      logLine(s"ROM: ${romData.length} entries, RAM: ${ramData.length} entries, Main: ${mainMemData.length} entries")
      logLine("")

      // Initialize clock
      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz

      dut.clockDomain.waitSampling(5)

      // Run simulation
      val maxCycles = 2000000
      val reportInterval = 10000

      logLine(s"Starting simulation for $maxCycles cycles...")
      logLine("")
      logLine("Format: [cycle] PC=hex JPC=hex event...")
      logLine("-" * 80)

      var wrCount = 0
      var addrWrCount = 0
      var rdCount = 0

      for (cycle <- 0 until maxCycles) {
        dut.clockDomain.waitSampling()

        val pc = dut.io.pc.toInt
        val jpc = dut.io.jpc.toInt
        val memBusy = dut.io.memBusy.toBoolean

        // Trace memory controller internals (first 500 events)
        val memRd = dut.jopCore.memCtrl.io.memIn.rd.toBoolean
        val memWr = dut.jopCore.memCtrl.io.memIn.wr.toBoolean
        val memWrf = dut.jopCore.memCtrl.io.memIn.wrf.toBoolean
        val memAddrWr = dut.jopCore.memCtrl.io.memIn.addrWr.toBoolean
        val addrIsIo = dut.jopCore.memCtrl.addrIsIo.toBoolean
        val aoutIsIo = dut.jopCore.memCtrl.aoutIsIo.toBoolean
        val addrRegVal = dut.jopCore.memCtrl.addrReg.toLong
        val aoutVal = dut.io.aout.toLong
        val memReadReq = dut.jopCore.memCtrl.memReadRequested.toBoolean

        if (memAddrWr) {
          addrWrCount += 1
          if (addrWrCount <= 20) {
            println(f"[$cycle%6d] ADDRWR aout=0x$aoutVal%06x aoutIsIo=$aoutIsIo addrReg=0x$addrRegVal%06x addrIsIo=$addrIsIo PC=$pc%04x")
          }
        }
        if (memWr || memWrf) {
          wrCount += 1
          if (wrCount <= 20) {
            println(f"[$cycle%6d] WRITE  aout=0x$aoutVal%06x addrReg=0x$addrRegVal%06x addrIsIo=$addrIsIo PC=$pc%04x wrCnt=$wrCount")
          }
        }
        if (memRd) {
          rdCount += 1
          if (rdCount <= 20) {
            println(f"[$cycle%6d] READ   aout=0x$aoutVal%06x aoutIsIo=$aoutIsIo PC=$pc%04x rdCnt=$rdCount")
          }
        }

        // Log PC/JPC changes (first 100 cycles, then every 100 cycles)
        val shouldLogState = (cycle < 100) || (cycle % 100 == 0)
        if (shouldLogState && (pc != lastPc || jpc != lastJpc)) {
          val busyStr = if (memBusy) " BUSY" else ""
          logLine(f"[$cycle%6d] PC=$pc%04x JPC=$jpc%04x$busyStr")
          lastPc = pc
          lastJpc = jpc
        }

        // Check for UART output from harness
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          val charStr = if (char >= 32 && char < 127) char.toChar.toString else f"\\x$char%02x"
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          logLine(f"[$cycle%6d] *** UART TX: '$charStr' (0x$char%02x) ***")
          print(if (char >= 32 && char < 127) char.toChar else '.')
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          val ioRdCnt = dut.jopCore.io.debugIoRdCount.toInt
          val ioWrCnt = dut.jopCore.io.debugIoWrCount.toInt
          println(f"\n[$cycle%6d] PC=$pc%04x JPC=$jpc%04x ioRd=$ioRdCnt ioWr=$ioWrCnt UART so far: '${uartOutput.toString}'")
        }
      }

      // Final summary
      logLine("")
      logLine("=" * 80)
      logLine("=== Simulation Summary ===")
      logLine(s"Total cycles: $maxCycles")
      logLine(s"Final PC: ${dut.io.pc.toInt}")
      logLine(s"Final JPC: ${dut.io.jpc.toInt}")
      logLine(s"memBusy: ${dut.io.memBusy.toBoolean}")
      if (uartOutput.nonEmpty) {
        logLine(s"UART Output: '${uartOutput.toString}'")
      } else {
        logLine("UART Output: (none)")
      }
      logLine("=" * 80)

      log.close()

      println(s"\n\n=== Simulation Complete ===")
      println(s"Final PC: ${dut.io.pc.toInt}")
      println(s"Final JPC: ${dut.io.jpc.toInt}")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Log written to: $logFilePath")

      run.finish("PASS", s"$maxCycles cycles")
    }
}
