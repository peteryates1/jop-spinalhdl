package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.{JopFileLoader, TestHistory}
import jop.memory.JopMemoryConfig
import jop.ddr3._
import java.io.PrintWriter

/**
 * GC simulation with MIG behavioral model at HIGH latency.
 *
 * Real DDR3 on Alchitry Au V2 can have 30-60 cycle read latency on cache misses
 * due to refresh collisions, bank conflicts, and page misses. The standard
 * MigBehavioralModel uses 8-15 cycles which doesn't reproduce the FPGA hang.
 *
 * This variant tests progressively higher latency to find the threshold where
 * the GC hang reproduces in simulation.
 */
object JopSmallGcHighLatencySim extends App {

  val jopFilePath = "java/apps/Small/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/small_gc_high_latency_simulation.log"

  // Parse args: [latMin] [latMax] [burstLen]
  // burstLen=0 matches FPGA config (single-word BC fill); burstLen=8 matches prior sims
  val argList = args.toList
  val latMin = argList.headOption.map(_.toInt).getOrElse(20)
  val latMax = argList.lift(1).map(_.toInt).getOrElse(60)
  val burstLen = argList.lift(2).map(_.toInt).getOrElse(0)  // Default 0 = match FPGA

  println(s"=== High-Latency DDR3 Simulation ===")
  println(s"Read latency range: $latMin - $latMax cycles")
  println(s"burstLen: $burstLen (FPGA uses 0, prior sims used 8)")
  println(s"(Standard sim uses 8-15 cycles; real DDR3 can be 30-60+)")

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"Log file: $logFilePath")

  val run = TestHistory.startRun("JopSmallGcHighLatencySim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .compile(JopCoreWithHighLatencyMigHarness(romData, ramData, mainMemData, latMin, latMax, burstLen))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine(s"=== JOP Small GC High-Latency MIG Simulation (latency=$latMin-$latMax, burstLen=$burstLen) ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 80000000  // 80M cycles — high latency takes longer
      val reportInterval = 200000
      var done = false
      var cycle = 0
      var busyCycles = 0
      var maxBusyStreak = 0
      var currentBusyStreak = 0
      var hangDetected = false

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
          val excType = dut.io.excType.toInt
          val aout = dut.io.aout.toLong & 0xFFFFFFFFL
          val bout = dut.io.bout.toLong & 0xFFFFFFFFL
          val memState = dut.io.debugMemState.toInt
          println(f"\n[$cycle%8d] *** EXCEPTION type=$excType PC=$pc%04x JPC=$jpc%04x aout=0x$aout%08x bout=0x$bout%08x memState=$memState ***")
          logLine(f"[$cycle%8d] EXCEPTION type=$excType PC=$pc%04x JPC=$jpc%04x aout=0x$aout%08x bout=0x$bout%08x memState=$memState")
        }

        // Check for UART output
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          print(if (char >= 32 && char < 127) char.toChar else '.')
          logLine(f"[$cycle%8d] UART: '${if (char >= 32 && char < 127) char.toChar.toString else f"\\x$char%02x"}' (0x$char%02x)")
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          val memState = dut.io.debugMemState.toInt
          val cacheState = dut.io.debugCacheState.toInt
          val adapterState = dut.io.debugAdapterState.toInt
          println(f"\n[$cycle%8d] PC=$pc%04x JPC=$jpc%04x memState=$memState cacheState=$cacheState adapterState=$adapterState busyCycles=$busyCycles maxBusyStreak=$maxBusyStreak UART: '${uartOutput.toString}'")
        }

        // Success: GC cycle completed
        val output = uartOutput.toString
        if (output.contains("R80 f=")) {
          println("\n*** GC cycle completed! ***")
          for (_ <- 0 until 50000) {
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

      log.close()

      println(s"\n\n=== Simulation Complete (${cycle} cycles, latency=$latMin-$latMax, burstLen=$burstLen) ===")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Total busy cycles: $busyCycles, max busy streak: $maxBusyStreak")
      println(s"Log written to: $logFilePath")

      if (hangDetected) {
        run.finish("HANG", s"$cycle cycles, latency=$latMin-$latMax, hung at busy streak $maxBusyStreak")
        println(s"*** HANG REPRODUCED at latency $latMin-$latMax! ***")
        System.exit(2)
      } else if (!uartOutput.toString.contains("GC test start")) {
        run.finish("FAIL", "Did not see 'GC test start'")
        println("FAIL: Did not see 'GC test start'")
        System.exit(1)
      } else if (uartOutput.toString.contains("R80 f=")) {
        run.finish("PASS", s"$cycle cycles, latency=$latMin-$latMax, GC works")
        println(s"PASS: GC works at latency $latMin-$latMax")
      } else {
        run.finish("TIMEOUT", s"$cycle cycles, latency=$latMin-$latMax, GC did not complete")
        println(s"TIMEOUT: GC did not complete at latency $latMin-$latMax")
        System.exit(1)
      }
    }
}

/**
 * Test harness with configurable high-latency MIG behavioral model.
 */
case class JopCoreWithHighLatencyMigHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt],
  readLatencyMin: Int,
  readLatencyMax: Int,
  burstLen: Int = 0  // 0 matches FPGA config; 8 matches prior sims
) extends Component {

  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(addressWidth = 26, mainMemSize = 128 * 1024, burstLen = burstLen)
  )

  val cacheAddrWidth = 28
  val cacheDataWidth = 128

  val io = new Bundle {
    val pc = out UInt(config.pcWidth bits)
    val jpc = out UInt((config.jpcWidth + 1) bits)
    val instr = out Bits(config.instrWidth bits)
    val jfetch = out Bool()
    val jopdfetch = out Bool()
    val aout = out Bits(config.dataWidth bits)
    val bout = out Bits(config.dataWidth bits)
    val memBusy = out Bool()
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()
    val excFired = out Bool()
    val excType = out Bits(8 bits)
    val debugMemState = out UInt(5 bits)
    val debugCacheState = out UInt(3 bits)
    val debugAdapterState = out UInt(3 bits)
  }

  // Extract JBC init from main memory
  val mpAddr = if (mainMemInit.length > 1) mainMemInit(1).toInt else 0
  val bootMethodStructAddr = if (mainMemInit.length > mpAddr) mainMemInit(mpAddr).toInt else 0
  val bootMethodStartLen = if (mainMemInit.length > bootMethodStructAddr) mainMemInit(bootMethodStructAddr).toLong else 0
  val bootCodeStart = (bootMethodStartLen >> 10).toInt
  val bytecodeStartWord = if (bootCodeStart > 0) bootCodeStart else 35
  val bytecodeWords = mainMemInit.slice(bytecodeStartWord, bytecodeStartWord + 512)

  val jbcInit = bytecodeWords.flatMap { word =>
    val w = word.toLong & 0xFFFFFFFFL
    Seq(
      BigInt((w >> 24) & 0xFF),
      BigInt((w >> 16) & 0xFF),
      BigInt((w >> 8) & 0xFF),
      BigInt((w >> 0) & 0xFF)
    )
  }.padTo(2048, BigInt(0))

  // JOP Core (BmbSys + BmbUart internal)
  val jopCore = JopCore(
    config = config,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  // ==========================================================================
  // DDR3-like Memory Path with HIGH LATENCY MIG model
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
    refreshInterval = 780,    // ~7.8us at 100MHz (standard DDR3)
    refreshDuration = 30      // cycles where app_rdy is deasserted
  )

  // Initialize MIG model memory from 32-bit words
  val memWords32 = mainMemInit.take(128 * 1024 / 4).padTo(128 * 1024 / 4, BigInt(0))
  val memWords128 = memWords32.grouped(4).toSeq.map { group =>
    val g = group.padTo(4, BigInt(0))
    (g(3) << 96) | (g(2) << 64) | (g(1) << 32) | g(0)
  }
  migModel.mem.init(memWords128.map(v => B(v, 128 bits)))

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
  // Core I/O
  // ==========================================================================

  jopCore.io.syncIn.halted := False
  jopCore.io.syncIn.s_out := False
  jopCore.io.rxd := True
  jopCore.io.debugRamAddr := 0
  jopCore.io.debugHalt := False

  // Tie off snoop (single-core, no other cores to snoop from)
  jopCore.io.snoopIn.foreach { si =>
    si.valid   := False
    si.isArray := False
    si.handle  := 0
    si.index   := 0
  }

  // Outputs
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
  io.excType := 0
  io.debugMemState := jopCore.io.debugMemState
  io.debugCacheState := cache.io.debugState
  io.debugAdapterState := adapter.io.debugState
}
