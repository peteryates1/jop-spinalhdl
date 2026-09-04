package jop.system

import spinal.core._
import spinal.core.sim._
import jop.utils.{JopFileLoader, TestHistory}
import jop.config.MicrocodePaths

/**
 * End-to-end timer interrupt simulation test.
 *
 * Boots the InterruptTest.jop app and verifies the full interrupt chain:
 *   timer compare → Sys intstate → priority encoder → irq pulse →
 *   BytecodeFetch pending latch → JumpTable dispatch → Java handler
 *
 * Expected UART output: "I:TTTTTOK"
 *
 * Usage: sbt "Test / runMain jop.system.JopInterruptSim"
 */
object JopInterruptSim extends App {

  val jopFilePath = jop.utils.SimApp.jop("InterruptTest", "InterruptTest")
  val romFilePath = MicrocodePaths.simulationRom
  val ramFilePath = MicrocodePaths.simulationRam

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 2 * 1024 * 1024 / 4)

  val bramSize = 2 * 1024 * 1024  // 2MB

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (for ${bramSize / 1024}KB BRAM)")

  val run = TestHistory.startRun("JopInterruptSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .compile(JopCoreLargeBramHarness(romData, ramData, mainMemData, bramSize))
    .doSim { dut =>
      var uartOutput = new StringBuilder
      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      // Budget: MEASURED, not estimated. The run completes at 5,431,155 cycles,
      // so 8M gives ~47 % margin.
      //
      // It was 4,000,000 on the estimate "boot (~150k) + 5 interrupts x 500k
      // each". The interrupts actually take ~1.05M cycles apiece, so the budget
      // ran out after the second one and the test failed with
      //   FAIL: Expected 'I:TTTTTOK' in output, got: '...I:TT'
      // deterministically -- which reads exactly like an interrupt controller
      // dropping events, and was reported as one (status item 98). Nothing was
      // wrong with the hardware; the estimate was 4x optimistic and outlived
      // whatever made it true.
      val maxCycles = 8000000
      val reportInterval = 100000
      var done = false
      var cycle = 0

      while (!done && cycle < maxCycles) {
        dut.clockDomain.waitSampling()
        cycle += 1

        // Capture UART output
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          print(if (char >= 32 && char < 127) char.toChar else '.')
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          println(f"\n[$cycle%8d] PC=$pc%04x JPC=$jpc%04x UART='${uartOutput.toString}'")
        }

        // Early exit when we see the specific test success pattern
        // (Boot also prints "OK" during startup, so match our unique marker)
        if (uartOutput.toString.contains("TTTTTOK")) {
          // Drain a few more cycles for any trailing output
          for (_ <- 0 until 1000) {
            dut.clockDomain.waitSampling()
            if (dut.io.uartTxValid.toBoolean) {
              val c = dut.io.uartTxData.toInt
              uartOutput.append(if (c >= 32 && c < 127) c.toChar else '.')
              print(if (c >= 32 && c < 127) c.toChar else '.')
            }
          }
          done = true
        }
      }

      println(s"\n\n=== Simulation Complete ($cycle cycles) ===")
      println(s"UART Output: '${uartOutput.toString}'")

      val output = uartOutput.toString
      // Boot prints preamble (e.g. "Small boot\nGC init...\nGC done\nCI\nOK\nM0\n")
      // before our test output, so check for substring
      if (!output.contains("I:")) {
        run.finish("FAIL", "Missing test marker 'I:'")
        println("FAIL: Missing test marker 'I:'")
        System.exit(1)
      }

      if (!output.contains("I:TTTTTOK")) {
        run.finish("FAIL", s"Expected 'I:TTTTTOK' in output, got: '$output'")
        println(s"FAIL: Expected 'I:TTTTTOK' in output, got: '$output'")
        System.exit(1)
      }

      run.finish("PASS", s"$cycle cycles, 5 timer interrupts verified")
      println("PASS: Timer interrupt end-to-end test passed")
    }
}
