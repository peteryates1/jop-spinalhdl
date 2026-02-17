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
 * Instead of pre-loading SDRAM, this harness feeds the .jop file
 * byte-by-byte through the UART RX interface, matching the serial
 * boot protocol.
 *
 * I/O decode:
 *   - UART status (slave 1, sub 0): TDRE=1 always, RDRF=1 when download data available
 *   - UART data (slave 1, sub 1) read: returns next byte from download ROM, advances pointer
 *   - UART data (slave 1, sub 1) write: captures echo byte
 *   - System registers: counter, CPU ID, signal, watchdog
 */
case class JopSdramSerialHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  downloadBytes: Seq[BigInt]  // .jop file as bytes (MSB-first per word)
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

    // UART TX capture
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()

    // UART RX echo capture
    val echoData = out Bits(8 bits)
    val echoValid = out Bool()

    // Download status
    val downloadPtr = out UInt(24 bits)
    val downloadDone = out Bool()

    // I/O debug
    val ioWr = out Bool()
    val ioRd = out Bool()
    val ioAddr = out UInt(8 bits)
    val ioWrData = out Bits(32 bits)
    val ioRdData = out Bits(32 bits)
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

  // Download byte ROM
  val downloadRom = Mem(Bits(8 bits), downloadBytes.length)
  downloadRom.init(downloadBytes.map(b => B(b, 8 bits)))

  // Download pointer (advances on each UART data read)
  val dlPtr = Reg(UInt(24 bits)) init(0)
  val dlDone = dlPtr >= U(downloadBytes.length, 24 bits)

  // System counter
  val sysCntReg = Reg(UInt(32 bits)) init(0)
  sysCntReg := sysCntReg + 1

  // Watchdog register
  val wdReg = Reg(Bits(32 bits)) init(0)

  // UART TX/echo capture
  val uartTxDataReg = Reg(Bits(8 bits)) init(0)
  val uartTxValidReg = Reg(Bool()) init(False)
  val echoDataReg = Reg(Bits(8 bits)) init(0)
  val echoValidReg = Reg(Bool()) init(False)

  // I/O decode
  val ioSubAddr = jopSystem.io.ioAddr(3 downto 0)
  val ioSlaveId = jopSystem.io.ioAddr(5 downto 4)

  // I/O read data - COMBINATIONAL
  val ioRdData = Bits(32 bits)
  ioRdData := 0

  // Track if current cycle has a UART data read (for pointer advance)
  val uartDataRead = False

  switch(ioSlaveId) {
    is(0) {  // System
      switch(ioSubAddr) {
        is(0) { ioRdData := sysCntReg.asBits }
        is(1) { ioRdData := sysCntReg.asBits }
        is(6) { ioRdData := B(0, 32 bits) }  // CPU ID = 0
        is(7) { ioRdData := B(0, 32 bits) }  // Signal = 0
      }
    }
    is(1) {  // UART
      switch(ioSubAddr) {
        is(0) {
          // Status: bit 0 = TDRE (always ready), bit 1 = RDRF (data available)
          val rdrf = !dlDone
          ioRdData := B(0, 30 bits) ## rdrf.asBits ## B"1"
        }
        is(1) {
          // Data read: return next download byte
          ioRdData := B(0, 24 bits) ## downloadRom.readAsync(dlPtr.resized)
          when(jopSystem.io.ioRd) {
            uartDataRead := True
          }
        }
      }
    }
  }
  jopSystem.io.ioRdData := ioRdData

  // Advance download pointer on UART data read
  when(uartDataRead && !dlDone) {
    dlPtr := dlPtr + 1
  }

  // I/O write handling
  uartTxValidReg := False
  echoValidReg := False

  when(jopSystem.io.ioWr) {
    switch(ioSlaveId) {
      is(0) {
        switch(ioSubAddr) {
          is(3) { wdReg := jopSystem.io.ioWrData }
        }
      }
      is(1) {
        switch(ioSubAddr) {
          is(1) {
            // During download: echoes; after download: program UART output
            when(!dlDone) {
              echoDataReg := jopSystem.io.ioWrData(7 downto 0)
              echoValidReg := True
            }.otherwise {
              uartTxDataReg := jopSystem.io.ioWrData(7 downto 0)
              uartTxValidReg := True
            }
          }
        }
      }
    }
  }

  // Interrupts disabled
  jopSystem.io.irq := False
  jopSystem.io.irqEna := False

  // Outputs
  io.pc := jopSystem.io.pc
  io.jpc := jopSystem.io.jpc
  io.instr := jopSystem.io.instr
  io.jfetch := jopSystem.io.jfetch
  io.jopdfetch := jopSystem.io.jopdfetch
  io.aout := jopSystem.io.aout
  io.bout := jopSystem.io.bout
  io.memBusy := jopSystem.io.memBusy
  io.uartTxData := uartTxDataReg
  io.uartTxValid := uartTxValidReg
  io.echoData := echoDataReg
  io.echoValid := echoValidReg
  io.downloadPtr := dlPtr
  io.downloadDone := dlDone
  io.ioWr := jopSystem.io.ioWr
  io.ioRd := jopSystem.io.ioRd
  io.ioAddr := jopSystem.io.ioAddr
  io.ioWrData := jopSystem.io.ioWrData
  io.ioRdData := ioRdData
}

/**
 * Simulation: SDRAM serial boot with HelloWorld.jop
 */
object JopSdramSerialSim extends App {
  val jopFilePath = "/home/peter/git/jopmin/java/Smallest/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/serial/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/serial/mem_ram.dat"

  // Load serial-boot microcode
  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  // Load .jop file and convert to byte stream (MSB-first per word)
  val jopData = JopFileLoader.loadJopFile(jopFilePath)
  val downloadBytes = jopData.words.flatMap { word =>
    val w = word.toLong & 0xFFFFFFFFL
    Seq(
      BigInt((w >> 24) & 0xFF),
      BigInt((w >> 16) & 0xFF),
      BigInt((w >> 8) & 0xFF),
      BigInt((w >> 0) & 0xFF)
    )
  }

  println(s"Serial microcode ROM: ${romData.length} entries")
  println(s"Serial microcode RAM: ${ramData.length} entries")
  println(s"Download: ${jopData.words.length} words = ${downloadBytes.length} bytes")

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .compile(JopSdramSerialHarness(romData, ramData, downloadBytes))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)  // 100 MHz

      // SDRAM model — starts empty (no pre-load)
      val sdramModel = SdramModel(
        io = dut.io.sdram,
        layout = dut.sdramLayout,
        clockDomain = dut.clockDomain
      )

      dut.clockDomain.waitSampling(5)

      val maxCycles = 2000000
      var uartOutput = new StringBuilder
      var echoCount = 0
      var echoErrors = 0
      var lastDlPtr = -1
      var downloadCompleteCycle = -1
      val totalBytes = downloadBytes.length
      var cycle = 0
      var running = true

      println(s"Starting serial boot simulation ($maxCycles max cycles)...")
      println(s"Download: $totalBytes bytes (${totalBytes / 4} words)")

      while (running && cycle < maxCycles) {
        dut.clockDomain.waitSampling()
        cycle += 1

        val dlPtr = dut.io.downloadPtr.toInt
        val dlDone = dut.io.downloadDone.toBoolean

        // Track download progress
        if (dlPtr != lastDlPtr && dlPtr % 1000 == 0) {
          val pct = (dlPtr * 100) / totalBytes
          println(f"[$cycle%7d] Download progress: $dlPtr/$totalBytes bytes ($pct%d%%), echoes=$echoCount, errors=$echoErrors")
          lastDlPtr = dlPtr
        }

        // Detect download completion
        if (dlDone && downloadCompleteCycle < 0) {
          downloadCompleteCycle = cycle
          println(f"[$cycle%7d] *** Download complete! $echoCount echoes, $echoErrors errors ***")

          // Verify SDRAM contents
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

        // Check echo bytes
        if (dut.io.echoValid.toBoolean) {
          val echoByte = dut.io.echoData.toInt & 0xFF
          // Verify against what was sent (echoCount tracks which byte)
          if (echoCount < downloadBytes.length) {
            val expected = downloadBytes(echoCount).toInt & 0xFF
            if (echoByte != expected) {
              if (echoErrors < 10) {
                println(f"[$cycle%7d] ECHO ERROR byte $echoCount: got 0x$echoByte%02x expected 0x$expected%02x")
              }
              echoErrors += 1
            }
          }
          echoCount += 1
        }

        // Log all I/O operations throughout the simulation (not just first 20k cycles)
        if (downloadCompleteCycle >= 0) {
          val ioRd = dut.io.ioRd.toBoolean
          val ioWr = dut.io.ioWr.toBoolean
          if (ioRd || ioWr) {
            val pc = dut.io.pc.toInt
            val jpc = dut.io.jpc.toInt
            val ioAddr = dut.io.ioAddr.toInt
            val ioRdData = dut.io.ioRdData.toLong & 0xFFFFFFFFL
            val ioWrData = dut.io.ioWrData.toLong & 0xFFFFFFFFL
            if (ioRd) println(f"[$cycle%7d] PC=$pc%04x JPC=$jpc%04x IO_RD[0x$ioAddr%02x]=0x$ioRdData%08x")
            if (ioWr) println(f"[$cycle%7d] PC=$pc%04x JPC=$jpc%04x IO_WR[0x$ioAddr%02x]=0x$ioWrData%08x")
          }
        }

        // Capture UART TX (post-download program output)
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          val charStr = if (char >= 32 && char < 127) char.toChar.toString else f"\\x$char%02x"
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          println(f"[$cycle%7d] UART TX: '$charStr' (0x$char%02x)")
          print(if (char >= 32 && char < 127) char.toChar else '.')
        }

        // Progress report every 100k cycles
        if (cycle > 0 && cycle % 100000 == 0) {
          println(f"[$cycle%7d] PC=${dut.io.pc.toInt}%04x JPC=${dut.io.jpc.toInt}%04x busy=${dut.io.memBusy.toBoolean} dlPtr=$dlPtr")
        }

        // Early exit on HelloWorld (capture 50k more cycles then stop)
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
        println(s"Download NOT completed (ptr=${dut.io.downloadPtr.toInt}/$totalBytes)")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Final PC: ${dut.io.pc.toInt}")
    }
}
