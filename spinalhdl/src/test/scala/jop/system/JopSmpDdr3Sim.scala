package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.memory.JopMemoryConfig
import jop.ddr3._
import jop.utils.{JopFileLoader, TestHistory}
import java.io.PrintWriter

/**
 * SMP DDR3 Test Harness: N JOP cores sharing DDR3 cache path via JopCluster.
 *
 * Combines the JopCluster SMP pattern (JopSmpSdramTestHarness) with the
 * DDR3 memory path (JopCoreWithMigTestHarness):
 *   JopCluster.bmb -> BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MigBehavioralModel
 *
 * Uses addressWidth=26 and burstLen=4 to match the SMP FPGA DDR3 configuration.
 * Variable read latency 20-60 cycles models realistic DDR3 timing.
 */
case class JopSmpDdr3TestHarness(
  cpuCnt: Int,
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt]
) extends Component {
  require(cpuCnt >= 1)

  val cacheAddrWidth = 28  // BMB byte address width (addressWidth=26 + 2)
  val cacheDataWidth = 128 // MIG native data width

  val io = new Bundle {
    // Per-core pipeline outputs
    val pc  = out Vec(UInt(11 bits), cpuCnt)
    val jpc = out Vec(UInt(12 bits), cpuCnt)

    // Per-core stack outputs
    val aout = out Vec(Bits(32 bits), cpuCnt)
    val bout = out Vec(Bits(32 bits), cpuCnt)

    // Per-core memory busy
    val memBusy = out Vec(Bool(), cpuCnt)

    // Per-core halted status
    val halted = out Vec(Bool(), cpuCnt)

    // UART output (from core 0 debug snoop)
    val uartTxData  = out Bits(8 bits)
    val uartTxValid = out Bool()

    // Per-core watchdog output
    val wd = out Vec(Bits(32 bits), cpuCnt)

    // Exception debug (core 0)
    val excFired = out Bool()

    // Cache/adapter debug
    val debugMemState     = out UInt(5 bits)
    val debugCacheState   = out UInt(3 bits)
    val debugAdapterState = out UInt(3 bits)
  }

  // Extract JBC init from main memory (same as JopSmpSdramTestHarness)
  val mpAddr = if (mainMemInit.length > 1) mainMemInit(1).toInt else 0
  val bootMethodStructAddr = if (mainMemInit.length > mpAddr) mainMemInit(mpAddr).toInt else 0
  val bootMethodStartLen = if (mainMemInit.length > bootMethodStructAddr) mainMemInit(bootMethodStructAddr).toLong else 0
  val bootCodeStart = (bootMethodStartLen >> 10).toInt
  val bytecodeStartWord = if (bootCodeStart > 0) bootCodeStart else 35
  val bytecodeWords = mainMemInit.slice(bytecodeStartWord, bytecodeStartWord + 512)
  val jbcInit = bytecodeWords.flatMap { word =>
    val w = word.toLong & 0xFFFFFFFFL
    Seq(BigInt((w >> 24) & 0xFF), BigInt((w >> 16) & 0xFF),
        BigInt((w >> 8) & 0xFF), BigInt((w >> 0) & 0xFF))
  }.padTo(2048, BigInt(0))

  // ====================================================================
  // JOP Cluster: N cores with arbiter + CmpSync
  // ====================================================================

  val cluster = JopCluster(
    cpuCnt = cpuCnt,
    baseConfig = JopCoreConfig(
      memConfig = JopMemoryConfig(addressWidth = 26, burstLen = 4)
    ),
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  // Expose CmpSync internals for simulation debugging
  cluster.cmpSync.foreach { sync =>
    sync.state.simPublic()
    sync.lockedId.simPublic()
  }

  // Expose core 0's signal register for SMP boot debugging
  cluster.cores(0).bmbSys.signalReg.simPublic()

  // No UART RX in simulation
  cluster.io.rxd := True

  // ====================================================================
  // DDR3 Memory Path: JopCluster.bmb -> BmbCacheBridge -> LruCacheCore
  //   -> CacheToMigAdapter -> MigBehavioralModel
  // ====================================================================

  val bmbBridge = new BmbCacheBridge(cluster.bmbParameter, cacheAddrWidth, cacheDataWidth)
  val cache = new LruCacheCore(CacheConfig(addrWidth = cacheAddrWidth, dataWidth = cacheDataWidth, setCount = 512))
  val adapter = new CacheToMigAdapter
  val migModel = MigBehavioralModel(
    addrWidth = 28,
    dataWidth = 128,
    memSizeBytes = 128 * 1024,
    readLatencyMin = 20,
    readLatencyMax = 60,
    refreshInterval = 780,
    refreshDuration = 30
  )

  // Initialize MIG model memory from 32-bit words
  val memWords32 = mainMemInit.take(128 * 1024 / 4).padTo(128 * 1024 / 4, BigInt(0))
  val memWords128 = memWords32.grouped(4).toSeq.map { group =>
    val g = group.padTo(4, BigInt(0))
    (g(3) << 96) | (g(2) << 64) | (g(1) << 32) | g(0)
  }
  migModel.mem.init(memWords128.map(v => B(v, 128 bits)))

  // JopCluster BMB -> BmbCacheBridge
  bmbBridge.io.bmb <> cluster.io.bmb

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

  // ====================================================================
  // Per-core Debug Output Wiring
  // ====================================================================

  for (i <- 0 until cpuCnt) {
    io.pc(i)      := cluster.io.pc(i)
    io.jpc(i)     := cluster.io.jpc(i)
    io.aout(i)    := cluster.io.aout(i)
    io.bout(i)    := cluster.io.bout(i)
    io.memBusy(i) := cluster.io.memBusy(i)
    io.wd(i)      := cluster.io.wd(i)
    io.halted(i)  := cluster.io.halted(i)
  }

  // UART output (core 0 debug snoop)
  io.uartTxData  := cluster.io.uartTxData
  io.uartTxValid := cluster.io.uartTxValid

  // Exception debug (core 0)
  io.excFired := cluster.io.debugExc

  // Cache/adapter debug
  io.debugMemState     := cluster.io.debugMemState
  io.debugCacheState   := cache.io.debugState
  io.debugAdapterState := adapter.io.debugState
}

/**
 * SMP DDR3 NCoreHelloWorld simulation.
 *
 * Tests N cores (default 2) running NCoreHelloWorld through the DDR3 cache path
 * (BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MigBehavioralModel).
 *
 * Usage: sbt "Test/runMain jop.system.JopSmpDdr3NCoreHelloWorldSim [cpuCnt]"
 */
object JopSmpDdr3NCoreHelloWorldSim extends App {
  val cpuCnt = if (args.length > 0) args(0).toInt else 2

  val jopFilePath = "java/apps/Small/NCoreHelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/smp_ddr3_ncore_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"CPU count: $cpuCnt")
  println(s"Log file: $logFilePath")

  val run = TestHistory.startRun("JopSmpDdr3NCoreHelloWorldSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .compile(JopSmpDdr3TestHarness(cpuCnt, romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine(s"=== JOP SMP DDR3 NCoreHelloWorld Simulation ($cpuCnt cores) ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 10000000  // 10M cycles (DDR3 latency higher than SDRAM)
      val reportInterval = 500000
      var done = false
      var cycle = 0

      // Track per-core watchdog values and toggle counts
      val lastWd = Array.fill(cpuCnt)(0)
      val wdToggles = Array.fill(cpuCnt)(0)

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // Check for exception firing (core 0)
        if (dut.io.excFired.toBoolean) {
          val pc0 = dut.io.pc(0).toInt
          val jpc0 = dut.io.jpc(0).toInt
          println(f"\n[$cycle%8d] *** EXCEPTION PC=$pc0%04x JPC=$jpc0%04x ***")
          logLine(f"[$cycle%8d] EXCEPTION PC=$pc0%04x JPC=$jpc0%04x")
        }

        // Check for UART output (core 0)
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          print(if (char >= 32 && char < 127) char.toChar else '.')
          logLine(f"[$cycle%8d] UART: '${if (char >= 32 && char < 127) char.toChar.toString else f"\\x$char%02x"}' (0x$char%02x)")
        }

        // Monitor per-core watchdog changes
        for (i <- 0 until cpuCnt) {
          val wd = dut.io.wd(i).toInt
          if (wd != lastWd(i)) {
            wdToggles(i) += 1
            if (wdToggles(i) <= 10) {
              println(f"\n[$cycle%8d] Core $i WD: $wd (toggle #${wdToggles(i)})")
              logLine(f"[$cycle%8d] Core $i WD: $wd (toggle #${wdToggles(i)})")
            }
            lastWd(i) = wd
          }
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          val pcStr = (0 until cpuCnt).map(i => f"C${i}:PC=${dut.io.pc(i).toInt}%04x").mkString(" ")
          val haltedStr = (0 until cpuCnt).map(i => if (dut.io.halted(i).toBoolean) "H" else ".").mkString
          val wdStr = (0 until cpuCnt).map(i => f"C${i}:WD=${lastWd(i)}").mkString(" ")
          val memState = dut.io.debugMemState.toInt
          val cacheState = dut.io.debugCacheState.toInt
          val adapterState = dut.io.debugAdapterState.toInt
          println(f"\n[$cycle%8d] $pcStr halted=$haltedStr $wdStr toggles=${wdToggles.mkString(",")} mem=$memState cache=$cacheState adapter=$adapterState")
        }

        // Exit after all cores have toggled watchdog at least once
        if (wdToggles.forall(_ >= 1)) {
          println(s"\n*** All cores toggling watchdog! toggles=${wdToggles.mkString(",")} ***")
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

      log.close()

      println(s"\n\n=== SMP DDR3 Simulation Complete ($cpuCnt cores, $cycle cycles) ===")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Per-core WD toggles: ${wdToggles.zipWithIndex.map { case (t, i) => s"C$i=$t" }.mkString(" ")}")
      println(s"Log written to: $logFilePath")

      if (!uartOutput.toString.contains("NCoreHelloWorld")) {
        run.finish("FAIL", "Did not see 'NCoreHelloWorld' from core 0")
        println("FAIL: Did not see 'NCoreHelloWorld' from core 0")
        System.exit(1)
      }
      for (i <- 0 until cpuCnt) {
        if (wdToggles(i) < 1) {
          run.finish("FAIL", s"Core $i never toggled watchdog (expected >= 1)")
          println(s"FAIL: Core $i never toggled watchdog (expected >= 1)")
          System.exit(1)
        }
      }
      run.finish("PASS", s"$cpuCnt cores, $cycle cycles, SMP DDR3 NCoreHelloWorld working")
      println(s"PASS: All $cpuCnt cores running on DDR3!")
    }
}
