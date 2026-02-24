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
 * Test harness for serial boot simulation.
 *
 * Uses serial-boot microcode. SDRAM starts empty.
 * UART RX is driven via io.rxd (bit-serial from simulation).
 *
 * I/O subsystem (BmbSys, BmbUart) is internal to JopCore.
 */
case class JopSdramSerialBootHarness(
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

    // Pipeline status
    val pc = out UInt(config.pcWidth bits)
    val jpc = out UInt((config.jpcWidth + 1) bits)
    val memBusy = out Bool()

    // UART (bit-serial)
    val rxd = in Bool()

    // UART TX snoop (from JopCore debug outputs)
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()

    // BMB debug
    val bmbCmdValid  = out Bool()
    val bmbCmdReady  = out Bool()
    val bmbCmdAddr   = out UInt(config.memConfig.bmbParameter.access.addressWidth bits)
    val bmbCmdOpcode = out Bits(1 bits)
    val bmbRspValid  = out Bool()
    val bmbRspData   = out Bits(32 bits)
  }

  // JOP System with SDRAM backend, empty JBC (serial boot loads from SDRAM)
  val jbcInit = Seq.fill(2048)(BigInt(0))
  val jopSystem = JopCoreWithSdram(
    config = config,
    sdramLayout = sdramLayout,
    sdramTiming = sdramTiming,
    CAS = CAS,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  io.sdram <> jopSystem.io.sdram

  // Single-core: no CmpSync
  jopSystem.io.syncIn.halted := False
  jopSystem.io.syncIn.s_out := False
  jopSystem.io.syncIn.status := False

  // UART RX from simulation (bit-serial)
  jopSystem.io.rxd := io.rxd

  // Outputs
  io.pc := jopSystem.io.pc
  io.jpc := jopSystem.io.jpc
  io.memBusy := jopSystem.io.memBusy
  io.uartTxData := jopSystem.io.uartTxData
  io.uartTxValid := jopSystem.io.uartTxValid

  // BMB debug
  io.bmbCmdValid := jopSystem.io.bmbCmdValid
  io.bmbCmdReady := jopSystem.io.bmbCmdReady
  io.bmbCmdAddr := jopSystem.io.bmbCmdAddr
  io.bmbCmdOpcode := jopSystem.io.bmbCmdOpcode
  io.bmbRspValid := jopSystem.io.bmbRspValid
  io.bmbRspData := jopSystem.io.bmbRspData
}

/**
 * Serial boot download simulation.
 *
 * Feeds HelloWorld.jop bytes via bit-serial UART on io.rxd,
 * verifies echo via uartTxData/uartTxValid snoop,
 * then waits for "Hello World!" output.
 */
object JopSdramSerialBootSim extends App {

  val jopFilePath = "java/apps/Smallest/HelloWorld.jop"
  val romFilePath = "asm/generated/serial/mem_rom.dat"
  val ramFilePath = "asm/generated/serial/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  // Parse .jop file into 32-bit words (same format as download.py)
  val jopWords = JopFileLoader.loadJopFile(jopFilePath).words

  // Convert words to bytes (MSB first, matching serial protocol)
  val jopBytes: Array[Byte] = jopWords.flatMap { word =>
    val w = word.toLong & 0xFFFFFFFFL
    Array(
      ((w >> 24) & 0xFF).toByte,
      ((w >> 16) & 0xFF).toByte,
      ((w >> 8) & 0xFF).toByte,
      ((w >> 0) & 0xFF).toByte
    )
  }.toArray

  println(s"Loaded serial ROM: ${romData.length} entries")
  println(s"Loaded serial RAM: ${ramData.length} entries")
  println(s"JOP file: ${jopWords.length} words (${jopBytes.length} bytes)")

  // UART timing: 1 Mbaud at 100 MHz = 100 clock cycles per bit
  // forkStimulus(10) -> 1 clock = 10 sim time units
  val bitPeriod = 1000L  // 100 clocks * 10 sim units = 1000

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .compile(JopSdramSerialBootHarness(romData, ramData))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)  // 100 MHz

      // Create SDRAM model (starts empty)
      val sdramModel = SdramModel(
        io = dut.io.sdram,
        layout = dut.sdramLayout,
        clockDomain = dut.clockDomain
      )

      // UART RX defaults to idle (HIGH)
      dut.io.rxd #= true

      dut.clockDomain.waitSampling(10)

      println(s"Starting serial boot simulation...")
      println(s"Downloading ${jopBytes.length} bytes via bit-serial UART (1 Mbaud)...")

      // Track echo bytes and UART output
      var echoBytes = 0
      var echoErrors = 0
      var uartOutput = new StringBuilder
      var downloadComplete = false

      // Fork a thread to send bytes bit-by-bit on rxd
      val txThread = fork {
        // Small delay before starting download
        sleep(500)

        for (byteIdx <- jopBytes.indices) {
          val b = jopBytes(byteIdx).toInt & 0xFF

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
            val pct = ((byteIdx + 1) * 100) / jopBytes.length
            println(s"  Download: ${byteIdx + 1}/${jopBytes.length} bytes ($pct%)")
          }
        }

        downloadComplete = true
        println(s"Download thread complete (${jopBytes.length} bytes sent)")
      }

      // Main simulation loop
      val maxCycles = 6000000  // 6M cycles (download ~3M + execution ~500K + margin)
      val startTime = System.currentTimeMillis()
      var lastReport = 0
      var done = false
      var cycle = 0

      while (cycle < maxCycles && !done) {
        dut.clockDomain.waitSampling()

        // Capture UART TX (echoes during download, then program output)
        if (dut.io.uartTxValid.toBoolean) {
          val ch = dut.io.uartTxData.toInt & 0xFF
          if (!downloadComplete) {
            // Echo verification
            if (echoBytes < jopBytes.length) {
              val expected = jopBytes(echoBytes).toInt & 0xFF
              if (ch != expected) {
                echoErrors += 1
                if (echoErrors <= 5) {
                  println(f"\n  Echo mismatch at byte $echoBytes: sent 0x$expected%02x, got 0x$ch%02x")
                }
              }
            }
            echoBytes += 1
          } else {
            val c = if (ch >= 32 && ch < 127) ch.toChar else '.'
            uartOutput.append(c)
            print(if (ch == 10) '\n' else c)
          }
        }

        // Progress report
        if (cycle - lastReport >= 500000) {
          lastReport = cycle
          val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
          val rate = if (elapsed > 0) (cycle / elapsed).toInt else 0
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          val busy = dut.io.memBusy.toBoolean
          val status = if (downloadComplete) "running" else s"downloading (echoes=$echoBytes/${jopBytes.length})"
          println(f"\n[$cycle%7d] PC=$pc%04x JPC=$jpc%04x busy=$busy $status ($rate cycles/sec)")
        }

        // Verify SDRAM after download
        if (downloadComplete && echoBytes >= jopBytes.length && echoBytes == jopBytes.length) {
          println("\n=== SDRAM Content Verification ===")
          for (i <- 0 until 10) {
            val b0 = sdramModel.banks(0).data(i * 4 + 0) & 0xFF
            val b1 = sdramModel.banks(0).data(i * 4 + 1) & 0xFF
            val b2 = sdramModel.banks(0).data(i * 4 + 2) & 0xFF
            val b3 = sdramModel.banks(0).data(i * 4 + 3) & 0xFF
            val word = ((b3 & 0xFF) << 24) | ((b2 & 0xFF) << 16) | ((b1 & 0xFF) << 8) | (b0 & 0xFF)
            val expected = jopWords(i).toLong & 0xFFFFFFFFL
            val match_ = if (word.toLong == expected) "OK" else "MISMATCH"
            println(f"  SDRAM[$i%3d] = 0x${word.toLong & 0xFFFFFFFFL}%08x  expected 0x$expected%08x  $match_")
          }
          println("=== End Verification ===\n")
          // Avoid re-printing
          echoBytes += 1
        }

        // Early exit on Hello World
        if (uartOutput.toString.contains("Hello World!")) {
          println(s"\n\n*** Hello World! detected at cycle $cycle ***")
          var extra = 0
          while (extra < 50000) {
            dut.clockDomain.waitSampling()
            if (dut.io.uartTxValid.toBoolean) {
              val ch = dut.io.uartTxData.toInt & 0xFF
              val c = if (ch >= 32 && ch < 127) ch.toChar else '.'
              uartOutput.append(c)
              print(if (ch == 10) '\n' else c)
            }
            extra += 1
          }
          println(s"\n\nFull output: '${uartOutput.toString}'")
          println(s"Echo bytes: $echoBytes, errors: $echoErrors")
          done = true
        }

        cycle += 1
      }

      if (!done) {
        println(s"\n\n=== Simulation Complete (no Hello World detected) ===")
        println(s"Echo bytes: $echoBytes / ${jopBytes.length}")
        println(s"Echo errors: $echoErrors")
        println(s"Download complete: $downloadComplete")
        println(s"UART output: '${uartOutput.toString}'")
      }
      println(s"Final PC: ${dut.io.pc.toInt}, JPC: ${dut.io.jpc.toInt}")
    }
}
