package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.{JopFileLoader, TestHistory}
import jop.memory.JopMemoryConfig
import jop.config.JopCoreConfig
import java.io.PrintWriter
import jop.config.MicrocodePaths

/**
 * JVM test suite (jvm.DoAll) with EVERY working `_sw` handler forced to
 * MICROCODE — the simulation counterpart of the ep4cgx150McFallback preset.
 *
 * Most of these handlers are otherwise executed nowhere. The defaults put float
 * and double on the Java trap and `lmul` on it too, so `lmul_sw` has had no
 * coverage at all — despite compute-unit-design.md once recording it as broken
 * and 900f66a claiming a fix.
 *
 * idiv/irem are HARDWARE on purpose in a build about software paths: lmul_sw
 * computes partial products on the ICU via sthw, so one has to exist. That also
 * lets imul stay `mc` so imul_sw is exercised.
 *
 * fadd/fsub/fmul/fdiv are excluded: their `_sw` handlers drive the removed
 * BmbFpu peripheral (item 22).
 *
 * Broader than JopJvmTestsMcFcmpSim, which stays as the narrow, attributable
 * regression for fcmpl/fcmpg alone.
 */
object JopJvmTestsLmulSwSim extends App {

  val jopFilePath = "java/apps/JvmTests/DoAll.jop"
  val romFilePath = MicrocodePaths.simulationRom
  val ramFilePath = MicrocodePaths.simulationRam
  val logFilePath = "spinalhdl/jvmtests_lmulsw_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val bramSize = 512 * 1024  // 512KB — DoAll.jop code is ~280KB, leaving ~230KB heap
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, bramSize / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")

  SimConfig
    .compile(JopCoreTestHarness(romData, ramData, mainMemData, memSize = bramSize,
      coreConfig = Some(JopCoreConfig(
        memConfig = JopMemoryConfig(mainMemSize = bramSize),
        bytecodes = Map(
          // imul = hw is the REAL precondition for lmul_sw, not idiv/irem:
          // IntegerComputeUnitConfig.withMul is needsIntMul == (imul == Hardware),
          // so with imul left at mc the ICU is built with no multiplier and
          // sthw 3 (imul_wide) has nothing to dispatch to.
          "imul" -> "hw",
          "lmul" -> "mc"))))) 
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder
      var lineBuffer = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JOP JVM Tests, lmul = microcode (lmul_sw only) ===")

      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      val maxCycles = 60000000
      val reportInterval = 100000

      for (cycle <- 0 until maxCycles) {
        dut.clockDomain.waitSampling()

        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          if (char == 10) {  // newline
            val line = lineBuffer.toString
            println(line)
            logLine(f"[$cycle%7d] $line")
            lineBuffer.clear()
          } else if (char >= 32 && char < 127) {
            lineBuffer.append(char.toChar)
          }
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '\n')
        }

        if (cycle > 0 && cycle % reportInterval == 0) {
          println(f"  [$cycle%7d cycles]")
        }
      }

      if (lineBuffer.nonEmpty) {
        println(lineBuffer.toString)
        logLine(f"[$maxCycles%7d] ${lineBuffer.toString}")
      }

      logLine("")
      logLine("=== Simulation Complete ===")
      logLine(s"UART Output:\n${uartOutput.toString}")
      log.close()

      println(s"\n=== Simulation Complete ($maxCycles cycles) ===")
      println(s"Full output:\n${uartOutput.toString}")
      println(s"Log: $logFilePath")
    }
}
