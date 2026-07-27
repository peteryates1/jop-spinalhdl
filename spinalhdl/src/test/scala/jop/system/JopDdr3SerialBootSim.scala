package jop.system
import jop.config._

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.{JopFileLoader, TestHistory}
import jop.memory.JopMemoryConfig
import jop.pipeline.JumpTableInitData
import jop.ddr3._
import java.io.PrintWriter

/**
 * Test harness: Serial-boot JopCore with DDR3 cache path and MIG behavioral model.
 *
 * This is the ONE path that no existing simulation covers:
 *   - Serial boot microcode (JumpTableInitData.serial) -- not simulation jump table
 *   - DDR3 cache path (BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MigBehavioralModel)
 *   - MIG memory starts empty (all zeros, matching real DDR3 after calibration)
 *   - UART bit-serial download of .jop file
 *   - GC execution after boot
 *
 * Hypothesis: serial boot writes ~8K words through the cache to DDR3, leaving
 * the cache in a specific dirty state that might trigger the GC hang.
 *
 * Architecture matches JopTop (DDR3) FPGA configuration exactly:
 *   - addressWidth = 26 (28-bit BMB byte address)
 *   - burstLen = 0 (single-word BC fill, matching FPGA)
 *   - supersetJumpTable = JumpTableInitData.serial
 *   - clkFreq = 100 MHz
 *   - 1 Mbaud UART
 *
 * I/O subsystem (Sys, Uart) is internal to JopCore.
 */
case class JopDdr3SerialBootHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  readLatencyMin: Int = 20,
  readLatencyMax: Int = 60
) extends Component {

  // Match FPGA config exactly: addressWidth=26, burstLen=0, serial jump table
  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(addressWidth = 26, mainMemSize = 128 * 1024, burstLen = 0),
    supersetJumpTable = JumpTableInitData.serial,
    clkFreq = 100 MHz,
    devices = Map("uart" -> DeviceInstance(DeviceType.Uart, params = Map("baudRate" -> 2000000)))
  )

  val cacheAddrWidth = 28  // BMB byte address width (addressWidth + 2)
  val cacheDataWidth = 128 // MIG native data width

  val io = new Bundle {
    // Pipeline status
    val pc = out UInt(config.pcWidth bits)
    val jpc = out UInt((config.jpcWidth + 1) bits)
    val instr = out Bits(config.instrWidth bits)
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    // Stack outputs
    val aout = out Bits(config.dataWidth bits)
    val bout = out Bits(config.dataWidth bits)

    // Memory controller status
    val memBusy = out Bool()

    // UART (bit-serial RX from simulation, TX snoop)
    val rxd = in Bool()
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()

    // Debug
    val excFired = out Bool()
    val debugMemState = out UInt(5 bits)
    val debugCacheState = out UInt(4 bits)
    val debugAdapterState = out UInt(3 bits)
  }

  // JBC init: empty (zeros) -- serial boot loads bytecodes from DDR3 via BC_FILL
  val jbcInit = Seq.fill(2048)(BigInt(0))

  // JOP Core with serial-boot microcode (Sys + Uart internal)
  val jopCore = JopCore(
    config = config,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  // ==========================================================================
  // DDR3 Memory Path: JopCore -> BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MigBehavioralModel
  // ==========================================================================

  val bmbBridge = new BmbCacheBridge(config.memConfig.bmbParameter, cacheAddrWidth, cacheDataWidth)
  val cache = new LruCacheCore(CacheConfig(addrWidth = cacheAddrWidth, dataWidth = cacheDataWidth))
  val adapter = new CacheToMigAdapter
  val migModel = MigBehavioralModel(
    addrWidth = 28,
    dataWidth = 128,
    memSizeBytes = 128 * 1024,
    readLatencyMin = readLatencyMin,
    readLatencyMax = readLatencyMax,
    refreshInterval = 780,    // ~7.8us at 100MHz (standard DDR3 refresh period)
    refreshDuration = 30      // cycles where app_rdy is deasserted
  )

  // MIG memory is NOT initialized -- starts all zeros, matching real DDR3 after calibration.
  // Serial boot will download the .jop file via UART and write it through the cache to DDR3.

  // JopCore BMB -> BmbCacheBridge
  bmbBridge.io.bmb.cmd.valid := jopCore.io.bmb.cmd.valid
  bmbBridge.io.bmb.cmd.payload := jopCore.io.bmb.cmd.payload
  jopCore.io.bmb.cmd.ready := bmbBridge.io.bmb.cmd.ready
  jopCore.io.bmb.rsp.valid := bmbBridge.io.bmb.rsp.valid
  jopCore.io.bmb.rsp.payload := bmbBridge.io.bmb.rsp.payload
  bmbBridge.io.bmb.rsp.ready := jopCore.io.bmb.rsp.ready

  // BmbCacheBridge -> LruCacheCore
  cache.io.frontend.req << bmbBridge.io.cache.req
  bmbBridge.io.cache.rsp << cache.io.frontend.rsp

  // LruCacheCore -> CacheToMigAdapter
  adapter.io.cmd.valid         := cache.io.memCmd.valid
  adapter.io.cmd.payload.addr  := cache.io.memCmd.payload.addr
  adapter.io.cmd.payload.write := cache.io.memCmd.payload.write
  adapter.io.cmd.payload.wdata := cache.io.memCmd.payload.data
  adapter.io.cmd.payload.wmask := cache.io.memCmd.payload.mask
  cache.io.memCmd.ready        := adapter.io.cmd.ready

  cache.io.memRsp.valid         := adapter.io.rsp.valid
  cache.io.memRsp.payload.data  := adapter.io.rsp.payload.rdata
  cache.io.memRsp.payload.error := adapter.io.rsp.payload.error
  adapter.io.rsp.ready          := cache.io.memRsp.ready

  // CacheToMigAdapter -> MIG behavioral model
  adapter.io.app_rdy           := migModel.io.app_rdy
  adapter.io.app_wdf_rdy       := migModel.io.app_wdf_rdy
  adapter.io.app_rd_data       := migModel.io.app_rd_data
  adapter.io.app_rd_data_valid := migModel.io.app_rd_data_valid

  migModel.io.app_addr     := adapter.io.app_addr
  migModel.io.app_cmd      := adapter.io.app_cmd
  migModel.io.app_en       := adapter.io.app_en
  migModel.io.app_wdf_data := adapter.io.app_wdf_data
  migModel.io.app_wdf_end  := adapter.io.app_wdf_end
  migModel.io.app_wdf_mask := adapter.io.app_wdf_mask
  migModel.io.app_wdf_wren := adapter.io.app_wdf_wren

  // ==========================================================================
  // Core I/O (internal to JopCore -- just tie off external interfaces)
  // ==========================================================================

  // Single-core: no CmpSync
  jopCore.io.syncIn.halted := False
  jopCore.io.syncIn.s_out := False
  jopCore.io.syncIn.status := False

  // UART RX from simulation (bit-serial)
  if (jopCore.devicePins.contains("uart")) jopCore.devicePin[Bool]("uart", "rxd") := io.rxd

  // Debug RAM (unused)
  jopCore.io.debugRamAddr := 0
  jopCore.io.debugHalt := False

  // Tie off snoop (single-core, no other cores to snoop from)
  jopCore.io.snoopIn.foreach { si =>
    si.valid   := False
    si.isArray := False
    si.handle  := 0
    si.index   := 0
  }

  // ==========================================================================
  // Output Connections
  // ==========================================================================

  io.pc := jopCore.io.pc
  io.jpc := jopCore.io.jpc
  io.instr := jopCore.io.instr
  io.jfetch := jopCore.io.jfetch
  io.jopdfetch := jopCore.io.jopdfetch
  io.aout := jopCore.io.aout
  io.bout := jopCore.io.bout
  io.memBusy := jopCore.io.memBusy
  io.uartTxData := jopCore.io.uartTxData
  io.uartTxValid := jopCore.io.uartTxValid
  io.excFired := jopCore.io.debugExc
  io.debugMemState := jopCore.io.debugMemState
  io.debugCacheState := cache.io.debugState
  io.debugAdapterState := adapter.io.debugState
}

/**
 * Serial Boot + DDR3 Cache Simulation for JOP.
 *
 * Tests the one path that no existing simulation covers:
 *   1. Serial-boot microcode (not simulation jump table)
 *   2. DDR3 cache path with MIG behavioral model (not direct BRAM)
 *   3. UART download of .jop file
 *   4. GC execution after boot
 *
 * Serial boot writes ~8K words through the cache to DDR3, leaving the cache
 * in a specific dirty state that might trigger the GC hang observed on FPGA.
 *
 * Flow:
 *   1. Load serial ROM/RAM (asm/generated/serial/)
 *   2. Load .jop file bytes for UART download
 *   3. Start JopCore with empty DDR3 (all zeros)
 *   4. Drive UART RX to download the .jop file byte-by-byte (1 Mbaud)
 *   5. Serial boot microcode receives bytes, writes words to DDR3 via cache
 *   6. After download, boot jumps to Java main()
 *   7. Monitor GC progress (R0, R1, ..., R12, R14)
 *   8. Detect hangs (busy for 500k+ cycles)
 *   9. Report PASS/FAIL/HANG
 *
 * Usage:
 *   sbt "testOnly jop.system.JopDdr3SerialBootSim"
 *   or: sbt "runMain jop.system.JopDdr3SerialBootSim [latMin] [latMax]"
 *
 * Default latency: 20-60 cycles (realistic DDR3).
 */
object JopDdr3SerialBootSim extends App {

  val jopFilePath = "java/apps/Smallest/HelloWorld.jop"
  val romFilePath = "asm/generated/serial/mem_rom.dat"
  val ramFilePath = "asm/generated/serial/mem_ram.dat"
  val logFilePath = "spinalhdl/ddr3_serial_boot_simulation.log"

  // Parse args: [latMin] [latMax]
  val argList = args.toList
  val latMin = argList.headOption.map(_.toInt).getOrElse(20)
  val latMax = argList.lift(1).map(_.toInt).getOrElse(60)

  println(s"=== DDR3 Serial Boot Simulation ===")
  println(s"This tests the one path NO existing simulation covers:")
  println(s"  Serial boot microcode + DDR3 cache + UART download + GC execution")
  println(s"Read latency range: $latMin - $latMax cycles (realistic DDR3)")
  println(s"burstLen: 0 (matching FPGA config)")
  println(s"addressWidth: 26 (matching FPGA config)")
  println(s"jumpTable: serial (matching FPGA config)")
  println()

  // Load serial-boot ROM/RAM
  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  // Parse .jop file into 32-bit words
  val jopFileData = JopFileLoader.loadJopFile(jopFilePath)
  val jopWords = jopFileData.words

  // Convert words to bytes (MSB first, matching serial download protocol)
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
  println(s"Log file: $logFilePath")
  println()

  // UART timing: 2 Mbaud at 100 MHz = 50 clock cycles per bit
  // forkStimulus(10) -> 1 clock = 10 sim time units
  val bitPeriod = 500L  // 50 clocks * 10 sim units = 500

  val run = TestHistory.startRun("JopDdr3SerialBootSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .compile(JopDdr3SerialBootHarness(romData, ramData, latMin, latMax))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine(s"=== JOP DDR3 Serial Boot Simulation Log ===")
      logLine(s"Latency: $latMin-$latMax cycles")
      logLine(s"JOP file: $jopFilePath (${jopWords.length} words, ${jopBytes.length} bytes)")
      logLine(s"MIG memory: empty (all zeros, matching real DDR3 after calibration)")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz

      // UART RX defaults to idle (HIGH)
      dut.io.rxd #= true

      dut.clockDomain.waitSampling(10)

      println(s"Starting DDR3 serial boot simulation...")
      println(s"Downloading ${jopBytes.length} bytes via bit-serial UART (2 Mbaud)...")
      logLine(s"Starting download: ${jopBytes.length} bytes via UART (2 Mbaud)")

      // Expected checksum: XOR of all jopWords (including word[0] = word count)
      val expectedChecksum: Long = jopWords.foldLeft(0L) { (acc, w) => acc ^ (w.toLong & 0xFFFFFFFFL) } & 0xFFFFFFFFL
      println(s"Expected checksum: 0x${expectedChecksum.toHexString}")
      logLine(s"Expected checksum: 0x${expectedChecksum.toHexString}")

      // Protocol state
      var handshakeComplete = false   // FPGA sent 0xAA, we sent 0x55
      var txComplete = false          // all jopBytes sent on rxd
      var downloadComplete = false    // 4-byte checksum received from FPGA
      var downloadCompleteCycle = 0
      var checksumMatch = false
      val checksumBuf = scala.collection.mutable.ArrayBuffer[Int]()

      // GC progress tracking
      var gcStartSeen = false
      var lastGcRound = -1
      var gcCompleteSeen = false

      // Hang detection
      var busyCycles = 0
      var maxBusyStreak = 0
      var currentBusyStreak = 0
      var hangDetected = false

      // Bit-serial byte transmitter on rxd (FPGA UART RX)
      def sendByte(b: Int): Unit = {
        dut.io.rxd #= false; sleep(bitPeriod)  // start bit
        for (bit <- 0 until 8) { dut.io.rxd #= ((b >> bit) & 1) == 1; sleep(bitPeriod) }
        dut.io.rxd #= true; sleep(bitPeriod)   // stop bit
      }

      // TX thread: waits for FPGA 0xAA (set by main loop), sends 0x55 ACK, then streams all jopBytes
      val txThread = fork {
        while (!handshakeComplete) sleep(100)

        val ackMsg = "TX: sending 0x55 ACK (entering download mode)"
        println(ackMsg); logLine(ackMsg)
        sendByte(0x55)
        sleep(bitPeriod * 5)  // brief gap: let FPGA transition to xram_loop

        for (byteIdx <- jopBytes.indices) {
          sendByte(jopBytes(byteIdx).toInt & 0xFF)
          if ((byteIdx + 1) % 2000 == 0) {
            val pct = ((byteIdx + 1) * 100) / jopBytes.length
            val msg = s"  Download: ${byteIdx + 1}/${jopBytes.length} bytes ($pct%)"
            println(msg); logLine(msg)
          }
        }

        txComplete = true
        val doneMsg = s"TX: all ${jopBytes.length} bytes sent, awaiting 4-byte checksum from FPGA"
        println(doneMsg); logLine(doneMsg)
      }

      // Main simulation loop
      // Serial download is ~3M cycles (jopBytes.length * 10 bits * 100 clocks/bit = ~8.7K * 1000 = ~8.7M)
      // Then GC execution: up to ~60M cycles with high latency
      // Total budget: 100M cycles
      val maxCycles = 100000000  // 100M cycles
      val reportInterval = 500000
      var done = false
      var cycle = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // Track busy streaks for hang detection
        if (dut.io.memBusy.toBoolean) {
          busyCycles += 1
          currentBusyStreak += 1
          if (currentBusyStreak > maxBusyStreak) {
            maxBusyStreak = currentBusyStreak
          }
          // Detect potential hang: busy for > 100k cycles
          if (currentBusyStreak == 100000) {
            val memState = dut.io.debugMemState.toInt
            val cacheState = dut.io.debugCacheState.toInt
            val adapterState = dut.io.debugAdapterState.toInt
            val pc = dut.io.pc.toInt
            val jpc = dut.io.jpc.toInt
            val aout = dut.io.aout.toLong & 0xFFFFFFFFL
            val bout = dut.io.bout.toLong & 0xFFFFFFFFL
            val msg = f"[$cycle%8d] *** POTENTIAL HANG: busy for 100k cycles! memState=$memState%d(0x$memState%02x) cacheState=$cacheState adapterState=$adapterState PC=$pc%04x JPC=$jpc%04x aout=0x$aout%08x bout=0x$bout%08x ***"
            println(s"\n$msg")
            logLine(msg)
          }
          if (currentBusyStreak >= 500000) {
            val memState = dut.io.debugMemState.toInt
            val cacheState = dut.io.debugCacheState.toInt
            val adapterState = dut.io.debugAdapterState.toInt
            val pc = dut.io.pc.toInt
            val jpc = dut.io.jpc.toInt
            val aout = dut.io.aout.toLong & 0xFFFFFFFFL
            val bout = dut.io.bout.toLong & 0xFFFFFFFFL
            val msg = f"[$cycle%8d] *** HANG CONFIRMED: busy for 500k+ cycles ***\n" +
              f"  memState=$memState%d (0x$memState%02x)\n" +
              f"  cacheState=$cacheState adapterState=$adapterState\n" +
              f"  PC=$pc%04x JPC=$jpc%04x\n" +
              f"  aout=0x$aout%08x bout=0x$bout%08x\n" +
              f"  downloadComplete=$downloadComplete checksumMatch=$checksumMatch\n" +
              f"  UART so far: '${uartOutput.toString}'"
            println(s"\n$msg")
            logLine(msg)
            hangDetected = true
            done = true
          }
        } else {
          currentBusyStreak = 0
        }

        // Check for exception
        if (dut.io.excFired.toBoolean) {
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          val aout = dut.io.aout.toLong & 0xFFFFFFFFL
          val bout = dut.io.bout.toLong & 0xFFFFFFFFL
          val memState = dut.io.debugMemState.toInt
          val msg = f"[$cycle%8d] *** EXCEPTION PC=$pc%04x JPC=$jpc%04x aout=0x$aout%08x bout=0x$bout%08x memState=$memState ***"
          println(s"\n$msg")
          logLine(msg)
        }

        // Capture UART TX output from FPGA
        if (dut.io.uartTxValid.toBoolean) {
          val ch = dut.io.uartTxData.toInt & 0xFF

          if (!handshakeComplete) {
            // Waiting for rdy_send (0xAA from FPGA)
            if (ch == 0xAA) {
              handshakeComplete = true
              val msg = f"[$cycle%8d] FPGA rdy_send: got 0xAA, signaling TX thread to send 0x55"
              println(s"\n$msg"); logLine(msg)
            }
          } else if (txComplete && !downloadComplete) {
            // Collecting 4-byte checksum from FPGA (sent after all N words received)
            checksumBuf.append(ch)
            if (checksumBuf.length == 4) {
              val cs = (((checksumBuf(0).toLong & 0xFF) << 24) |
                        ((checksumBuf(1).toLong & 0xFF) << 16) |
                        ((checksumBuf(2).toLong & 0xFF) << 8)  |
                         (checksumBuf(3).toLong & 0xFF)) & 0xFFFFFFFFL
              checksumMatch = cs == expectedChecksum
              downloadComplete = true
              downloadCompleteCycle = cycle
              val result = if (checksumMatch) "PASS" else "FAIL"
              val msg = f"[$cycle%8d] Checksum: 0x$cs%08x expected=0x$expectedChecksum%08x $result"
              println(s"\n$msg"); logLine(msg)
              if (checksumMatch) {
                // ACK so FPGA jumps to downloaded code
                fork { sendByte(0x55) }
                logLine(s"  ACK sent: FPGA should jump to HelloWorld")
              } else {
                logLine(s"  CHECKSUM MISMATCH -- download corrupted, aborting")
                done = true
              }
            }
          } else if (downloadComplete && checksumMatch) {
            // Program output (HelloWorld, GC rounds, etc.)
            val c = ch.toChar
            uartOutput.append(c)
            if (ch >= 32 && ch < 127) {
              print(c)
            } else if (ch == 10) {
              print('\n')
            } else if (ch == 13) {
              // ignore CR
            } else {
              print(f"[0x$ch%02x]")
            }
            logLine(f"[$cycle%8d] UART: '${if (ch >= 32 && ch < 127) ch.toChar.toString else f"\\x$ch%02x"}' (0x$ch%02x)")

            // Track GC progress (for SmallGC variant)
            val output = uartOutput.toString
            if (!gcStartSeen && output.contains("GC test start")) {
              gcStartSeen = true
              val msg = f"[$cycle%8d] GC test started (${cycle - downloadCompleteCycle} cycles after download)"
              println(s"\n$msg"); logLine(msg)
            }
            for (round <- (0 to 12) ++ Seq(14)) {
              if (round > lastGcRound && output.contains(s"R$round f=")) {
                lastGcRound = round
                val msg = f"[$cycle%8d] GC round R$round completed"
                println(s"\n$msg"); logLine(msg)
              }
            }
          }
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          val memState = dut.io.debugMemState.toInt
          val cacheState = dut.io.debugCacheState.toInt
          val adapterState = dut.io.debugAdapterState.toInt
          val status = if (!downloadComplete) {
            if (!handshakeComplete) "waiting for FPGA 0xAA"
            else if (!txComplete) s"downloading (${checksumBuf.length} checksum bytes so far)"
            else s"awaiting checksum (${checksumBuf.length}/4 bytes)"
          } else if (!gcStartSeen) {
            s"booting (${cycle - downloadCompleteCycle} cycles since download)"
          } else if (!gcCompleteSeen) {
            s"GC running (lastRound=R$lastGcRound)"
          } else {
            "complete"
          }
          val msg = f"[$cycle%8d] PC=$pc%04x JPC=$jpc%04x memState=$memState cacheState=$cacheState adapterState=$adapterState busyStreak=$currentBusyStreak maxBusyStreak=$maxBusyStreak $status"
          println(f"\n$msg")
          logLine(msg)
        }

        // Success: program output seen (HelloWorld or GC)
        if (downloadComplete && checksumMatch && !gcCompleteSeen) {
          val output = uartOutput.toString
          val programDone = output.contains("Hello World!") || output.contains("R80 f=")
          if (programDone) {
            gcCompleteSeen = true
            val label = if (output.contains("Hello World!")) "Hello World!" else "GC completed"
            val msg = f"[$cycle%8d] *** $label ***"
            println(s"\n$msg"); logLine(msg)
            for (_ <- 0 until 100000) {
              dut.clockDomain.waitSampling()
              if (dut.io.uartTxValid.toBoolean) {
                val ch = dut.io.uartTxData.toInt
                uartOutput.append(ch.toChar)
                if (ch >= 32 && ch < 127) print(ch.toChar)
                else if (ch == 10) print('\n')
              }
            }
            done = true
          }
        }
      }

      log.close()

      // ==========================================================================
      // Final Report
      // ==========================================================================

      println(s"\n\n${"=" * 70}")
      println(s"DDR3 Serial Boot Simulation Complete")
      println(s"${"=" * 70}")
      println(s"Cycles: $cycle")
      println(s"MIG latency: $latMin-$latMax cycles")
      println(s"Download: ${jopBytes.length} bytes, complete=$downloadComplete, checksumMatch=$checksumMatch")
      println(s"Download complete: $downloadComplete (cycle $downloadCompleteCycle)")
      println(s"Total busy cycles: $busyCycles, max busy streak: $maxBusyStreak")
      println(s"GC started: $gcStartSeen, last round: R$lastGcRound, completed: $gcCompleteSeen")
      println(s"UART output: '${uartOutput.toString}'")
      println(s"Log: $logFilePath")
      println()

      // Determine result
      if (hangDetected) {
        val memState = dut.io.debugMemState.toInt
        val cacheState = dut.io.debugCacheState.toInt
        val adapterState = dut.io.debugAdapterState.toInt
        val notes = s"$cycle cycles, latency=$latMin-$latMax, hung at busyStreak=$maxBusyStreak " +
          s"memState=$memState cacheState=$cacheState adapterState=$adapterState " +
          s"downloadComplete=$downloadComplete gcStarted=$gcStartSeen lastRound=R$lastGcRound"
        run.finish("HANG", notes)
        println(s"*** RESULT: HANG ***")
        println(s"  Serial boot + DDR3 cache path REPRODUCES the hang!")
        println(s"  memState=$memState cacheState=$cacheState adapterState=$adapterState")
        if (!downloadComplete) {
          println(s"  Hang occurred DURING download (before GC) -- cache dirty state from serial writes")
        } else if (!gcStartSeen) {
          println(s"  Hang occurred AFTER download but BEFORE GC start -- boot sequence issue")
        } else {
          println(s"  Hang occurred DURING GC (round R$lastGcRound) -- matches FPGA behavior!")
        }
        System.exit(2)
      } else if (!downloadComplete) {
        run.finish("FAIL", s"$cycle cycles, download did not complete (checksum not received)")
        println(s"FAIL: Download did not complete")
        System.exit(1)
      } else if (!checksumMatch) {
        run.finish("FAIL", f"$cycle cycles, checksum mismatch (expected 0x$expectedChecksum%08x)")
        println(s"FAIL: Checksum mismatch")
        System.exit(1)
      } else if (gcCompleteSeen) {
        run.finish("PASS", s"$cycle cycles, latency=$latMin-$latMax, serial boot + DDR3 cache works")
        println(s"PASS: Serial boot + DDR3 cache path works correctly!")
        println(s"  UART output: ${uartOutput.toString.take(200)}")
      } else {
        run.finish("TIMEOUT", s"$cycle cycles, download OK (checksum match) but no program output")
        println(s"TIMEOUT: Download succeeded but no program output within $cycle cycles")
        System.exit(1)
      }
    }
}
