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
 * UART RX is driven from simulation via io.uartRxData/uartRxValid.
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

    // UART TX output (from JOP)
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()

    // UART RX input (from simulation)
    val uartRxData = in Bits(8 bits)
    val uartRxValid = in Bool()
    val uartRxRead = out Bool()  // Pulses when microcode reads a byte

    // I/O debug
    val ioWr = out Bool()
    val ioRd = out Bool()
    val ioAddr = out UInt(8 bits)
    val ioWrData = out Bits(32 bits)

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

  // System counter
  val sysCntReg = Reg(UInt(32 bits)) init(0)
  sysCntReg := sysCntReg + 1

  // UART TX capture
  val uartTxDataReg = Reg(Bits(8 bits)) init(0)
  val uartTxValidReg = Reg(Bool()) init(False)

  // UART RX read tracking
  val uartRxReadReg = Reg(Bool()) init(False)

  // I/O read data - combinational
  val ioRdData = Bits(32 bits)
  ioRdData := 0

  val ioSubAddr = jopSystem.io.ioAddr(3 downto 0)
  val ioSlaveId = jopSystem.io.ioAddr(5 downto 4)

  uartRxReadReg := False

  switch(ioSlaveId) {
    is(0) {  // System
      switch(ioSubAddr) {
        is(0) { ioRdData := sysCntReg.asBits }        // Counter
        is(1) { ioRdData := sysCntReg.asBits }        // Microsecond counter
        is(6) { ioRdData := B(0, 32 bits) }           // CPU ID = 0
        is(7) { ioRdData := B(0, 32 bits) }           // Signal
      }
    }
    is(1) {  // UART
      switch(ioSubAddr) {
        is(0) {
          // Status: bit 0 = TDRE (TX ready), bit 1 = RDRF (RX data available)
          ioRdData := B(0, 30 bits) ## io.uartRxValid.asBits ## B"1"
        }
        is(1) {
          // Data read: return RX byte, signal read to simulation
          ioRdData := B(0, 24 bits) ## io.uartRxData
          when(jopSystem.io.ioRd) {
            uartRxReadReg := True
          }
        }
      }
    }
  }
  jopSystem.io.ioRdData := ioRdData

  // I/O write handling
  uartTxValidReg := False
  when(jopSystem.io.ioWr) {
    switch(ioSlaveId) {
      is(1) {  // UART
        switch(ioSubAddr) {
          is(1) {
            uartTxDataReg := jopSystem.io.ioWrData(7 downto 0)
            uartTxValidReg := True
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
  io.memBusy := jopSystem.io.memBusy
  io.uartTxData := uartTxDataReg
  io.uartTxValid := uartTxValidReg
  io.uartRxRead := uartRxReadReg
  io.ioWr := jopSystem.io.ioWr
  io.ioRd := jopSystem.io.ioRd
  io.ioAddr := jopSystem.io.ioAddr
  io.ioWrData := jopSystem.io.ioWrData

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
 * Feeds HelloWorld.jop bytes via simulated UART RX, verifies echo,
 * then waits for "Hello World!" output.
 */
object JopSdramSerialBootSim extends App {

  val jopFilePath = "/home/peter/git/jop/java/Smallest/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/serial/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/serial/mem_ram.dat"

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

      // UART RX state
      var byteIdx = 0
      var echoBytes = 0
      var echoErrors = 0
      var downloadComplete = false
      var uartOutput = new StringBuilder

      // Drive UART RX defaults
      dut.io.uartRxData #= 0
      dut.io.uartRxValid #= false

      dut.clockDomain.waitSampling(10)

      println(s"Starting serial boot simulation...")
      println(s"Downloading ${jopBytes.length} bytes...")

      val maxCycles = 600000
      val startTime = System.currentTimeMillis()
      var lastReport = 0
      var done = false

      // Post-download debug logging
      var postDownloadLogCount = 0
      val maxPostDownloadLogs = 200
      var downloadCycle = 0
      var lastJpc = -1
      var lastPc = -1
      var ioLogCount = 0

      var cycle = 0
      while (cycle < maxCycles && !done) {
        // Drive UART RX: provide next byte if available
        if (!downloadComplete && byteIdx < jopBytes.length) {
          dut.io.uartRxData #= (jopBytes(byteIdx).toInt & 0xFF)
          dut.io.uartRxValid #= true
        } else {
          dut.io.uartRxValid #= false
        }

        dut.clockDomain.waitSampling()

        // Check if microcode read a UART RX byte
        if (dut.io.uartRxRead.toBoolean && !downloadComplete) {
          byteIdx += 1
          if (byteIdx >= jopBytes.length) {
            downloadComplete = true
            downloadCycle = cycle
            println(s"\nDownload complete at cycle $cycle ($byteIdx bytes sent)")

            // Verify SDRAM contents by reading SdramModel memory directly
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
            // Check key addresses
            val mp = jopWords(1).toInt
            println(f"\n  mp = $mp (word[1])")
            for (i <- 0 until 3) {
              val addr = mp + i
              val b0 = sdramModel.banks(0).data(addr * 4 + 0) & 0xFF
              val b1 = sdramModel.banks(0).data(addr * 4 + 1) & 0xFF
              val b2 = sdramModel.banks(0).data(addr * 4 + 2) & 0xFF
              val b3 = sdramModel.banks(0).data(addr * 4 + 3) & 0xFF
              val word = ((b3 & 0xFF) << 24) | ((b2 & 0xFF) << 16) | ((b1 & 0xFF) << 8) | (b0 & 0xFF)
              val expected = jopWords(addr).toLong & 0xFFFFFFFFL
              val match_ = if (word.toLong == expected) "OK" else "MISMATCH"
              println(f"  SDRAM[$addr%4d] = 0x${word.toLong & 0xFFFFFFFFL}%08x  expected 0x$expected%08x  $match_")
            }
            println("=== End Verification ===\n")
          }
        }

        // Capture UART TX (echo during download, then "Hello World!")
        if (dut.io.uartTxValid.toBoolean) {
          val ch = dut.io.uartTxData.toInt & 0xFF
          if (!downloadComplete) {
            echoBytes += 1
            val expectedIdx = echoBytes - 1
            if (expectedIdx < jopBytes.length) {
              val expected = jopBytes(expectedIdx).toInt & 0xFF
              if (ch != expected) {
                echoErrors += 1
                if (echoErrors <= 5) {
                  println(f"\n  Echo mismatch at byte $expectedIdx: sent 0x$expected%02x, got 0x$ch%02x")
                }
              }
            }
          } else {
            val c = if (ch >= 32 && ch < 127) ch.toChar else '.'
            uartOutput.append(c)
            print(if (ch == 10) '\n' else c)
          }
        }

        // Post-download detailed logging
        if (downloadComplete && (cycle - downloadCycle) < 5000) {
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          val busy = dut.io.memBusy.toBoolean

          // Log BMB transactions
          val cmdFire = dut.io.bmbCmdValid.toBoolean && dut.io.bmbCmdReady.toBoolean
          if (cmdFire && postDownloadLogCount < maxPostDownloadLogs) {
            val addr = dut.io.bmbCmdAddr.toLong
            val op = if (dut.io.bmbCmdOpcode.toInt == 0) "RD" else "WR"
            println(f"  [${cycle - downloadCycle}%5d] BMB CMD $op addr=0x$addr%08x (word=${addr/4}%d)  PC=$pc%04x")
            postDownloadLogCount += 1
          }
          if (dut.io.bmbRspValid.toBoolean && postDownloadLogCount < maxPostDownloadLogs) {
            val data = dut.io.bmbRspData.toLong & 0xFFFFFFFFL
            println(f"  [${cycle - downloadCycle}%5d] BMB RSP data=0x$data%08x  PC=$pc%04x")
            postDownloadLogCount += 1
          }

          // Log I/O operations
          if (dut.io.ioRd.toBoolean && ioLogCount < 50) {
            val addr = dut.io.ioAddr.toInt
            println(f"  [${cycle - downloadCycle}%5d] IO RD addr=0x$addr%02x  PC=$pc%04x JPC=$jpc%04x")
            ioLogCount += 1
          }
          if (dut.io.ioWr.toBoolean && ioLogCount < 50) {
            val addr = dut.io.ioAddr.toInt
            val data = dut.io.ioWrData.toLong & 0xFFFFFFFFL
            println(f"  [${cycle - downloadCycle}%5d] IO WR addr=0x$addr%02x data=0x$data%08x  PC=$pc%04x JPC=$jpc%04x")
            ioLogCount += 1
          }

          // Log JPC changes
          if (jpc != lastJpc) {
            if (postDownloadLogCount < maxPostDownloadLogs) {
              println(f"  [${cycle - downloadCycle}%5d] JPC: $lastJpc%d -> $jpc%d  PC=$pc%04x busy=$busy")
              postDownloadLogCount += 1
            }
            lastJpc = jpc
          }
        }

        // Progress report
        if (cycle - lastReport >= 100000) {
          lastReport = cycle
          val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
          val rate = if (elapsed > 0) (cycle / elapsed).toInt else 0
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          val busy = dut.io.memBusy.toBoolean
          val status = if (downloadComplete) "running" else s"downloading ${byteIdx}/${jopBytes.length}"
          println(f"\n[$cycle%7d] PC=$pc%04x JPC=$jpc%04x busy=$busy $status ($rate cycles/sec)")
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
        println(s"Bytes sent: $byteIdx / ${jopBytes.length}")
        println(s"Echo bytes: $echoBytes, errors: $echoErrors")
        println(s"Download complete: $downloadComplete")
        println(s"UART output: '${uartOutput.toString}'")
      }
      println(s"Final PC: ${dut.io.pc.toInt}, JPC: ${dut.io.jpc.toInt}")
    }
}
