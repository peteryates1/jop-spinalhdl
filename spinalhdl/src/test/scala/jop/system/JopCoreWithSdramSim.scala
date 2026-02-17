package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.memory.sdram.sdr._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import jop.utils.JopFileLoader
import java.io.PrintWriter

/**
 * Extended simulation for JopCoreWithSdram with full logging
 */
object JopCoreWithSdramSim extends App {

  // Paths to initialization files
  val jopFilePath = "/home/peter/workspaces/ai/jop/java/apps/Smallest/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat"
  val logFilePath = "/home/peter/workspaces/ai/jop/spinalhdl/sdram_simulation.log"

  // Load initialization data
  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)  // 128KB

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")
  println(s"Log file: $logFilePath")

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    // .withWave  // Uncomment for waveform
    .compile(JopCoreWithSdramTestHarness(romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder
      var wdState = false
      var wdToggles = 0
      var lastPc = -1
      var lastJpc = -1
      var ioWriteCount = 0
      var ioReadCount = 0

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JOP SDRAM Simulation Log ===")
      logLine(s"ROM: ${romData.length} entries, RAM: ${ramData.length} entries, Main: ${mainMemData.length} entries")
      logLine("")

      // Initialize clock
      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz

      // Create SDRAM simulation model
      val sdramModel = SdramModel(
        io = dut.io.sdram,
        layout = dut.sdramLayout,
        clockDomain = dut.clockDomain
      )

      // Initialize SDRAM with program data
      logLine("Initializing SDRAM...")
      for (wordIdx <- mainMemData.indices) {
        val word = mainMemData(wordIdx).toLong & 0xFFFFFFFFL
        val byteAddr = wordIdx * 4
        sdramModel.write(byteAddr + 0, ((word >>  0) & 0xFF).toByte)
        sdramModel.write(byteAddr + 1, ((word >>  8) & 0xFF).toByte)
        sdramModel.write(byteAddr + 2, ((word >> 16) & 0xFF).toByte)
        sdramModel.write(byteAddr + 3, ((word >> 24) & 0xFF).toByte)
      }
      logLine(s"SDRAM initialized with ${mainMemData.length} words")
      logLine("")

      dut.clockDomain.waitSampling(5)

      // Verify SDRAM contents
      logLine("Verifying first 10 words of SDRAM:")
      for (i <- 0 until 10) {
        val b0 = sdramModel.banks(0).data(i * 4 + 0) & 0xFF
        val b1 = sdramModel.banks(0).data(i * 4 + 1) & 0xFF
        val b2 = sdramModel.banks(0).data(i * 4 + 2) & 0xFF
        val b3 = sdramModel.banks(0).data(i * 4 + 3) & 0xFF
        val word = (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
        logLine(f"  Word $i%3d: 0x$word%08x (expected 0x${mainMemData(i).toLong & 0xFFFFFFFFL}%08x)")
      }
      logLine("")

      // Run simulation
      val maxCycles = 200000
      val reportInterval = 10000
      var startTime = System.currentTimeMillis()

      logLine(s"Starting simulation for $maxCycles cycles...")
      logLine("")
      logLine("Format: [cycle] PC=hex JPC=hex event...")
      logLine("-" * 80)

      var bmbTxnCount = 0
      var lastBusy = false
      var busyStartCycle = 0
      var bmbCmdCount = 0
      var bmbRspCount = 0
      var done = false

      var cycle = 0
      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        val pc = dut.io.pc.toInt
        val jpc = dut.io.jpc.toInt
        val memBusy = dut.io.memBusy.toBoolean

        // Log BMB transactions (all of them after boot, sampled)
        val cmdFire = dut.io.bmbCmdValid.toBoolean && dut.io.bmbCmdReady.toBoolean
        val rspFire = dut.io.bmbRspValid.toBoolean
        if (cmdFire) bmbCmdCount += 1
        if (rspFire) bmbRspCount += 1

        // Log BMB transactions in critical window (after JOP start, during BC_FILLs)
        val shouldLogBmb = cycle >= 33000 && cycle <= 200000
        if (cmdFire && shouldLogBmb) {
          val addr = dut.io.bmbCmdAddr.toLong
          val op = if (dut.io.bmbCmdOpcode.toInt == 0) "RD" else "WR"
          logLine(f"[$cycle%6d] BMB CMD $op addr=0x$addr%08x (word=${addr/4}%d)")
        }
        if (rspFire) {
          if (shouldLogBmb) {
            val data = dut.io.bmbRspData.toLong & 0xFFFFFFFFL
            logLine(f"[$cycle%6d] BMB RSP data=0x$data%08x")
          }
          bmbTxnCount += 1
        }

        // Track busy transitions
        if (memBusy != lastBusy) {
          if (memBusy) {
            busyStartCycle = cycle
            if (cycle > 33000) {
              logLine(f"[$cycle%6d] BUSY START PC=$pc%04x JPC=$jpc%04x cmds=$bmbCmdCount rsps=$bmbRspCount")
            }
          } else {
            val duration = cycle - busyStartCycle
            if (cycle > 33000) {
              logLine(f"[$cycle%6d] BUSY END   PC=$pc%04x JPC=$jpc%04x duration=$duration cmds=$bmbCmdCount rsps=$bmbRspCount")
            }
          }
          lastBusy = memBusy
        }

        // Log PC/JPC changes (first 100 cycles, then every 1000 cycles)
        val shouldLogState = (cycle < 100) || (cycle % 1000 == 0)
        if (shouldLogState && (pc != lastPc || jpc != lastJpc)) {
          val busyStr = if (memBusy) " BUSY" else ""
          logLine(f"[$cycle%6d] PC=$pc%04x JPC=$jpc%04x$busyStr")
          lastPc = pc
          lastJpc = jpc
        }

        // Check for I/O writes
        if (dut.io.ioWr.toBoolean) {
          val addr = dut.io.ioAddr.toInt
          val data = dut.io.ioWrData.toLong
          logLine(f"[$cycle%6d] IO WRITE: addr=0x$addr%02x data=0x$data%08x")
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
          val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
          val rate = cycle / elapsed
          println(f"\n[$cycle%6d] PC=$pc%04x JPC=$jpc%04x rate=${rate.toInt} cycles/sec UART so far: '${uartOutput.toString}'")
        }

        // Early exit if we see HelloWorld output
        if (uartOutput.toString.contains("Hello World")) {
          logLine("")
          logLine("*** HelloWorld output detected! ***")
          println("\n*** HelloWorld output detected! ***")
          // Continue a bit more to capture any remaining output
          for (_ <- 0 until 10000) {
            dut.clockDomain.waitSampling()
            if (dut.io.uartTxValid.toBoolean) {
              val char = dut.io.uartTxData.toInt
              uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
              print(if (char >= 32 && char < 127) char.toChar else '.')
            }
          }
          logLine(s"Captured additional output, total: '${uartOutput.toString}'")
          done = true
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
      logLine(s"WD toggles: $wdToggles")
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
    }
}
