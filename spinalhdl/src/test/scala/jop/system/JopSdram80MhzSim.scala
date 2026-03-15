package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.memory.sdram.sdr._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import jop.config._
import jop.memory.{JopMemoryConfig, SdramDeviceInfo}
import jop.utils.{JopFileLoader, TestHistory}

/**
 * Quick test: run JopCoreWithSdram at 80 MHz to verify SdramCtrlNoCke
 * timing parameters are correct at the dual-cluster SDR clock frequency.
 *
 * If this passes, the SDRAM controller logic is fine at 80 MHz
 * and the dual-system SDR issue is hardware-specific (timing/routing).
 * If this fails, there's a logic bug in the SDRAM controller at 80 MHz.
 */
case class JopCoreWithSdram80MhzHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt]
) extends Component {

  // Match the dual-system SDR config: single core, no burst, 80 MHz
  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(burstLen = 0),
    clkFreq = HertzNumber(80000000)
  )

  val md = MemoryDevice.W9825G6JH6

  val io = new Bundle {
    val sdram = master(SdramInterface(SdramDeviceInfo.layoutFor(md)))
    val pc = out UInt(config.pcWidth bits)
    val jpc = out UInt((config.jpcWidth + 1) bits)
    val memBusy = out Bool()
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()
  }

  val jbcInit = Seq.fill(2048)(BigInt(0))

  val jopSystem = JopCoreWithSdram(
    config = config,
    memDevice = md,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit),
    clockFreqHz = 80000000L
  )

  io.sdram <> jopSystem.io.sdram
  jopSystem.io.syncIn.halted := False
  jopSystem.io.syncIn.s_out := False
  jopSystem.io.syncIn.status := False
  jopSystem.io.rxd := True

  io.pc := jopSystem.io.pc
  io.jpc := jopSystem.io.jpc
  io.memBusy := jopSystem.io.memBusy
  io.uartTxData := jopSystem.io.uartTxData
  io.uartTxValid := jopSystem.io.uartTxValid
}

object JopSdram80MhzSim extends App {
  val jopFilePath = "java/apps/Smallest/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"=== SDRAM 80 MHz Test ===")
  println(s"Loaded ROM: ${romData.length}, RAM: ${ramData.length}, Main: ${mainMemData.length}")

  val run = TestHistory.startRun("JopSdram80MhzSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(80 MHz)))
    .compile(JopCoreWithSdram80MhzHarness(romData, ramData, mainMemData))
    .doSim { dut =>
      var uartOutput = new StringBuilder

      dut.clockDomain.forkStimulus(period = 12500)  // 12.5 ns = 80 MHz

      // Create SDRAM sim model
      val sdramModel = SdramModel(
        io = dut.io.sdram,
        layout = SdramDeviceInfo.layoutFor(dut.md),
        clockDomain = dut.clockDomain
      )

      // Pre-load SDRAM with program
      for (wordIdx <- mainMemData.indices) {
        val word = mainMemData(wordIdx).toLong & 0xFFFFFFFFL
        val byteAddr = wordIdx * 4
        sdramModel.write(byteAddr + 0, ((word >>  0) & 0xFF).toByte)
        sdramModel.write(byteAddr + 1, ((word >>  8) & 0xFF).toByte)
        sdramModel.write(byteAddr + 2, ((word >> 16) & 0xFF).toByte)
        sdramModel.write(byteAddr + 3, ((word >> 24) & 0xFF).toByte)
      }

      dut.clockDomain.waitSampling(5)

      val maxCycles = 10000000
      val reportInterval = 1000000
      var done = false
      var cycle = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          print(if (char >= 32 && char < 127) char.toChar else '.')
        }

        if (cycle % reportInterval == 0) {
          println(f"\n[$cycle%6d] PC=${dut.io.pc.toInt}%04x JPC=${dut.io.jpc.toInt}%04x UART='${uartOutput.toString}'")
        }

        if (uartOutput.toString.contains("Hello World")) {
          for (_ <- 0 until 10000) {
            dut.clockDomain.waitSampling()
            if (dut.io.uartTxValid.toBoolean) {
              val char = dut.io.uartTxData.toInt
              uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
              print(if (char >= 32 && char < 127) char.toChar else '.')
            }
          }
          done = true
        }
      }

      println(s"\n\n=== 80 MHz SDRAM Sim Complete ($cycle cycles) ===")
      println(s"UART Output: '${uartOutput.toString}'")

      if (uartOutput.toString.contains("Hello World")) {
        run.finish("PASS", s"$cycle cycles, SDRAM works at 80 MHz")
        println("PASS: SdramCtrlNoCke works correctly at 80 MHz")
      } else {
        run.finish("FAIL", s"No Hello World at 80 MHz")
        println("FAIL: SDRAM may have a logic issue at 80 MHz!")
        System.exit(1)
      }
    }
}
