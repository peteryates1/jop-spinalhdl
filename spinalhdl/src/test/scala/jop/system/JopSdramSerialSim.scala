package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.memory.sdram.sdr._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import jop.memory.JopMemoryConfig
import jop.utils.JopFileLoader
import jop.pipeline.JumpTableInitData

/**
 * Test harness for JopCoreWithSdram with serial download simulation.
 *
 * Uses serial-boot microcode. SDRAM starts empty.
 * UART RX is driven via io.rxd (bit-serial from simulation).
 *
 * I/O subsystem (BmbSys, BmbUart) is internal to JopCore.
 */
case class JopSdramSerialHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt]
) extends Component {

  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(burstLen = 4),
    jumpTable = JumpTableInitData.serial
  )
  val sdramLayout = W9825G6JH6.layout
  val sdramTiming = W9825G6JH6.timingGrade7
  val CAS = 3

  val io = new Bundle {
    val sdram = master(SdramInterface(sdramLayout))

    // Pipeline outputs
    val pc = out UInt(config.pcWidth bits)
    val jpc = out UInt((config.jpcWidth + 1) bits)
    val instr = out Bits(config.instrWidth bits)
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    // Stack outputs
    val aout = out Bits(config.dataWidth bits)
    val bout = out Bits(config.dataWidth bits)

    // Memory status
    val memBusy = out Bool()

    // UART (bit-serial)
    val rxd = in Bool()

    // UART TX snoop (from JopCore debug outputs)
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()
  }

  // JBC init: empty (zeros) — serial boot fills bytecodes from SDRAM
  val jbcInit = Seq.fill(2048)(BigInt(0))

  // JOP System with SDRAM
  val jopSystem = JopCoreWithSdram(
    config = config,
    sdramLayout = sdramLayout,
    sdramTiming = sdramTiming,
    CAS = CAS,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  // SDRAM interface
  io.sdram <> jopSystem.io.sdram

  // Single-core: no CmpSync
  jopSystem.io.syncIn.halted := False
  jopSystem.io.syncIn.s_out := False

  // UART RX from simulation (bit-serial)
  jopSystem.io.rxd := io.rxd

  // Outputs
  io.pc := jopSystem.io.pc
  io.jpc := jopSystem.io.jpc
  io.instr := jopSystem.io.instr
  io.jfetch := jopSystem.io.jfetch
  io.jopdfetch := jopSystem.io.jopdfetch
  io.aout := jopSystem.io.aout
  io.bout := jopSystem.io.bout
  io.memBusy := jopSystem.io.memBusy
  io.uartTxData := jopSystem.io.uartTxData
  io.uartTxValid := jopSystem.io.uartTxValid
}

/**
 * Simulation: SDRAM serial boot with HelloWorld.jop
 *
 * Downloads .jop file via bit-serial UART on io.rxd,
 * verifies echo bytes, waits for "Hello World!" output.
 */
object JopSdramSerialSim extends App {
  val jopFilePath = "java/apps/Smallest/HelloWorld.jop"
  val romFilePath = "asm/generated/serial/mem_rom.dat"
  val ramFilePath = "asm/generated/serial/mem_ram.dat"

  // Load serial-boot microcode
  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  // Load .jop file and convert to byte stream (MSB-first per word)
  val jopData = JopFileLoader.loadJopFile(jopFilePath)
  val downloadBytes = jopData.words.flatMap { word =>
    val w = word.toLong & 0xFFFFFFFFL
    Seq(
      ((w >> 24) & 0xFF).toByte,
      ((w >> 16) & 0xFF).toByte,
      ((w >> 8) & 0xFF).toByte,
      ((w >> 0) & 0xFF).toByte
    )
  }.toArray

  println(s"Serial microcode ROM: ${romData.length} entries")
  println(s"Serial microcode RAM: ${ramData.length} entries")
  println(s"Download: ${jopData.words.length} words = ${downloadBytes.length} bytes")

  // UART timing: 1 Mbaud at 100 MHz = 100 clock cycles per bit
  // forkStimulus(10) -> 1 clock = 10 sim time units
  val bitPeriod = 1000L  // 100 clocks * 10 sim units = 1000

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .compile(JopSdramSerialHarness(romData, ramData))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)  // 100 MHz

      // SDRAM model — starts empty (no pre-load)
      val sdramModel = SdramModel(
        io = dut.io.sdram,
        layout = dut.sdramLayout,
        clockDomain = dut.clockDomain
      )

      // UART RX defaults to idle (HIGH)
      dut.io.rxd #= true

      dut.clockDomain.waitSampling(5)

      val maxCycles = 6000000  // 6M cycles (download ~3M + execution ~500K + margin)
      var uartOutput = new StringBuilder
      var echoCount = 0
      var echoErrors = 0
      var downloadComplete = false
      var downloadCompleteCycle = -1
      val totalBytes = downloadBytes.length
      var cycle = 0
      var running = true

      println(s"Starting serial boot simulation ($maxCycles max cycles)...")
      println(s"Download: $totalBytes bytes (${totalBytes / 4} words) via bit-serial UART")

      // Fork a thread to send download bytes bit-by-bit on rxd
      val txThread = fork {
        // Small delay before starting download
        sleep(500)

        for (byteIdx <- downloadBytes.indices) {
          val b = downloadBytes(byteIdx).toInt & 0xFF

          // Start bit (LOW)
          dut.io.rxd #= false
          sleep(bitPeriod)

          // 8 data bits (LSB first)
          for (bit <- 0 until 8) {
            dut.io.rxd #= ((b >> bit) & 1) == 1
            sleep(bitPeriod)
          }

          // Stop bit (HIGH)
          dut.io.rxd #= true
          sleep(bitPeriod)

          // Progress report every 1000 bytes
          if ((byteIdx + 1) % 1000 == 0) {
            val pct = ((byteIdx + 1) * 100) / totalBytes
            println(s"  Download: ${byteIdx + 1}/$totalBytes bytes ($pct%)")
          }
        }

        downloadComplete = true
        println(s"Download thread complete ($totalBytes bytes sent)")
      }

      // Main simulation loop
      while (running && cycle < maxCycles) {
        dut.clockDomain.waitSampling()
        cycle += 1

        // Track download completion
        if (downloadComplete && downloadCompleteCycle < 0) {
          downloadCompleteCycle = cycle
        }

        // Capture UART TX
        if (dut.io.uartTxValid.toBoolean) {
          val ch = dut.io.uartTxData.toInt & 0xFF
          if (!downloadComplete) {
            // Echo verification during download
            if (echoCount < downloadBytes.length) {
              val expected = downloadBytes(echoCount).toInt & 0xFF
              if (ch != expected) {
                if (echoErrors < 10) {
                  println(f"[$cycle%7d] ECHO ERROR byte $echoCount: got 0x$ch%02x expected 0x$expected%02x")
                }
                echoErrors += 1
              }
            }
            echoCount += 1
          } else {
            // Program output after download
            val charStr = if (ch >= 32 && ch < 127) ch.toChar.toString else f"\\x$ch%02x"
            uartOutput.append(if (ch >= 32 && ch < 127) ch.toChar else '.')
            println(f"[$cycle%7d] UART TX: '$charStr' (0x$ch%02x)")
            print(if (ch >= 32 && ch < 127) ch.toChar else '.')
          }
        }

        // Verify SDRAM after download (once)
        if (downloadCompleteCycle > 0 && cycle == downloadCompleteCycle + 100) {
          println(f"[$cycle%7d] *** Download complete! $echoCount echoes, $echoErrors errors ***")
          println("Verifying SDRAM contents after download:")
          for (i <- 0 until 10) {
            val byteBase = i * 4
            val b0 = sdramModel.banks(0).data(byteBase + 0) & 0xFF
            val b1 = sdramModel.banks(0).data(byteBase + 1) & 0xFF
            val b2 = sdramModel.banks(0).data(byteBase + 2) & 0xFF
            val b3 = sdramModel.banks(0).data(byteBase + 3) & 0xFF
            // Little-endian: b0=LSB, b3=MSB
            val word = ((b3 << 24) | (b2 << 16) | (b1 << 8) | b0) & 0xFFFFFFFFL
            val expected = downloadBytes(i * 4 + 3).toInt | (downloadBytes(i * 4 + 2).toInt << 8) |
                           (downloadBytes(i * 4 + 1).toInt << 16) | (downloadBytes(i * 4 + 0).toInt << 24)
            val expU = expected.toLong & 0xFFFFFFFFL
            val ok = if (word == expU) "OK" else "MISMATCH"
            println(f"  Word $i%3d: SDRAM=0x$word%08x expected=0x$expU%08x $ok")
          }
        }

        // Progress report every 500k cycles
        if (cycle > 0 && cycle % 500000 == 0) {
          println(f"[$cycle%7d] PC=${dut.io.pc.toInt}%04x JPC=${dut.io.jpc.toInt}%04x busy=${dut.io.memBusy.toBoolean}")
        }

        // Early exit on HelloWorld
        if (uartOutput.toString.contains("Hello World") && downloadCompleteCycle >= 0) {
          println(f"\n[$cycle%7d] *** Hello World detected! Capturing 50k more cycles... ***")
          for (_ <- 0 until 50000) {
            dut.clockDomain.waitSampling()
            if (dut.io.uartTxValid.toBoolean) {
              val c = dut.io.uartTxData.toInt
              uartOutput.append(if (c >= 32 && c < 127) c.toChar else '.')
              print(if (c >= 32 && c < 127) c.toChar else '.')
            }
          }
          println()
          running = false
        }
      }

      println(s"\n=== Simulation Complete ===")
      println(s"Download: $echoCount/$totalBytes echoes, $echoErrors errors")
      if (downloadCompleteCycle >= 0)
        println(s"Download completed at cycle $downloadCompleteCycle")
      else
        println(s"Download NOT completed")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Final PC: ${dut.io.pc.toInt}")
    }
}
