package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig

/**
 * Echo test harness: JopCore with BRAM.
 * UART RX driven via io.rxd (bit-serial from simulation).
 *
 * I/O subsystem (BmbSys, BmbUart) is internal to JopCore.
 */
case class JopEchoHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt]
) extends Component {

  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = 128 * 1024)
  )

  val io = new Bundle {
    val pc = out UInt(config.pcWidth bits)
    val jpc = out UInt((config.jpcWidth + 1) bits)
    val memBusy = out Bool()

    // UART TX snoop (from JopCore debug outputs)
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()

    // UART RX (bit-serial from simulation)
    val rxd = in Bool()
  }

  // Empty JBC (echo.asm doesn't use it)
  val jbcInit = Seq.fill(2048)(BigInt(0))

  // JOP Core (BmbSys + BmbUart internal)
  val jopCore = JopCore(
    config = config,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  // BRAM (echo.asm doesn't access it, but BMB needs a slave)
  val ram = BmbOnChipRam(
    p = config.memConfig.bmbParameter,
    size = config.memConfig.mainMemSize,
    hexInit = null
  )
  ram.io.bus << jopCore.io.bmb

  // Single-core: no CmpSync
  jopCore.io.syncIn.halted := False
  jopCore.io.syncIn.s_out := False

  // UART RX from simulation (bit-serial)
  jopCore.io.rxd := io.rxd

  // Interrupts disabled
  jopCore.io.irq := False
  jopCore.io.irqEna := False

  // Debug RAM port (unused)
  jopCore.io.debugRamAddr := 0

  // Outputs
  io.pc := jopCore.io.pc
  io.jpc := jopCore.io.jpc
  io.memBusy := jopCore.io.memBusy
  io.uartTxData := jopCore.io.uartTxData
  io.uartTxValid := jopCore.io.uartTxValid
}

/**
 * Simulation of echo.asm microcode.
 * Tests UART I/O: polls status, reads byte, echoes it.
 * Uses bit-serial UART via io.rxd.
 */
object JopEchoSim extends App {

  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  println(s"Loaded echo ROM: ${romData.length} entries")
  println(s"Loaded echo RAM: ${ramData.length} entries")

  // UART timing: 1 Mbaud at 100 MHz = 100 clock cycles per bit
  // forkStimulus(10) -> 1 clock = 10 sim time units
  val bitPeriod = 1000L  // 100 clocks * 10 sim units

  SimConfig
    .compile(JopEchoHarness(romData, ramData))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)  // 100 MHz

      // UART RX defaults to idle (HIGH)
      dut.io.rxd #= true

      var txOutput = new StringBuilder
      var echoCount = 0

      val testBytes = Array(0x41, 0x42, 0x43, 0x55, 0xAA)
      var startupByteSeen = false

      dut.clockDomain.waitSampling(10)
      println("Starting echo simulation...")

      // Fork a thread to send test bytes bit-by-bit after startup
      val txThread = fork {
        // Wait for startup output (microcode sends initial byte)
        while (!startupByteSeen) {
          sleep(100)
        }

        // Small delay after startup
        sleep(5000)

        for (byteIdx <- testBytes.indices) {
          val b = testBytes(byteIdx)
          println(f"  >> Feeding byte 0x$b%02x to RX (bit-serial)")

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

          // Inter-byte gap (wait for echo to be processed)
          sleep(bitPeriod * 5)
        }
      }

      // Main simulation loop
      // 5 bytes * ~15 bit periods * 100 clocks = ~7500 clocks + startup ~500 = ~8000
      val maxCycles = 50000
      var cycle = 0

      while (cycle < maxCycles && echoCount < testBytes.length) {
        dut.clockDomain.waitSampling()
        cycle += 1

        // Check UART TX
        if (dut.io.uartTxValid.toBoolean) {
          val ch = dut.io.uartTxData.toInt & 0xFF
          val c = if (ch >= 32 && ch < 127) ch.toChar else '.'
          txOutput.append(c)

          if (!startupByteSeen) {
            startupByteSeen = true
            println(f"  [$cycle%5d] *** Startup byte: '$c' (0x$ch%02x) ***")
          } else {
            echoCount += 1
            println(f"  [$cycle%5d] *** Echo #$echoCount: '$c' (0x$ch%02x) ***")
          }
        }
      }

      println(s"\n=== Echo Simulation Result ===")
      println(s"Cycles: $cycle")
      println(s"TX output: '${txOutput.toString}'")
      println(s"Echoed: $echoCount / ${testBytes.length}")

      if (echoCount == testBytes.length) {
        println("SUCCESS: All bytes echoed!")
      } else {
        println("FAIL: Not all bytes echoed")
      }
    }
}
