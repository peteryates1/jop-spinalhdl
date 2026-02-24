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
 * Simple BRAM backend for LruCacheCore's 128-bit memCmd/memRsp interface.
 *
 * Provides 1-cycle read latency and immediate write acceptance,
 * matching the CacheToMigAdapter protocol from the cache's perspective.
 *
 * Mask convention (matching MIG): mask bit = 1 means "don't write this byte".
 */
/**
 * @param readLatency  Extra cycles of read latency (0=1-cycle total, 10=11-cycle total like DDR3)
 * @param writeLatency Extra cycles of write latency (0=1-cycle total, 2=3-cycle total like DDR3)
 */
case class CacheToBramAdapter(
  addrWidth: Int,
  dataWidth: Int,
  memSizeBytes: BigInt,
  readLatency: Int = 0,
  writeLatency: Int = 0
) extends Component {
  private val wordBytes = dataWidth / 8          // 16 for 128-bit
  private val wordCount = (memSizeBytes / wordBytes).toInt
  private val wordAddrBits = log2Up(wordCount)
  private val byteOffsetBits = log2Up(wordBytes)

  val io = new Bundle {
    val cmd = slave Stream(CacheReq(addrWidth, dataWidth))
    val rsp = master Stream(CacheRsp(dataWidth))
  }

  val mem = Mem(Bits(dataWidth bits), wordCount)

  val cmdWordAddr = io.cmd.payload.addr(wordAddrBits + byteOffsetBits - 1 downto byteOffsetBits).asUInt

  // Read port: readSync gives data on cycle AFTER address is presented
  val readData = mem.readSync(cmdWordAddr, enable = io.cmd.fire)

  // Write port: byte-masked write using inverted cache mask (cache: 1=keep, Mem: 1=write)
  mem.write(
    address = cmdWordAddr,
    data = io.cmd.payload.data,
    enable = io.cmd.fire && io.cmd.payload.write,
    mask = ~io.cmd.payload.mask
  )

  // Latency counter: after accepting cmd, count down before presenting response
  val maxLatency = readLatency max writeLatency
  val latencyBits = if (maxLatency > 0) log2Up(maxLatency + 1) + 1 else 1
  val pending = RegInit(False)
  val pendingIsWrite = RegInit(False)
  val latencyCount = Reg(UInt(latencyBits bits)) init(0)

  io.cmd.ready := !pending
  io.rsp.valid := pending && (latencyCount === 0)
  io.rsp.payload.data := Mux(pendingIsWrite, B(0, dataWidth bits), readData)
  io.rsp.payload.error := False

  when(!pending && io.cmd.fire) {
    pendingIsWrite := io.cmd.payload.write
    pending := True
    latencyCount := Mux(io.cmd.payload.write, U(writeLatency, latencyBits bits), U(readLatency, latencyBits bits))
  }

  when(pending && latencyCount > 0) {
    latencyCount := latencyCount - 1
  }

  when(pending && latencyCount === 0 && io.rsp.fire) {
    pending := False
  }
}

/**
 * Test harness for JopCore with LruCacheCore in the memory path.
 *
 * Path: JopCore.bmb -> BmbCacheBridge -> LruCacheCore -> CacheToBramAdapter -> BRAM
 *
 * This tests the same cache path used by DDR3 FPGA but with BRAM backing,
 * allowing simulation debugging of cache-related issues.
 *
 * I/O subsystem (BmbSys, BmbUart) is internal to JopCore.
 */
case class JopCoreWithCacheTestHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt]
) extends Component {

  // Match DDR3 config: addressWidth=26 (28-bit BMB byte addr), burstLen=8
  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(addressWidth = 26, mainMemSize = 128 * 1024, burstLen = 8)
  )

  val cacheAddrWidth = 28   // BMB byte address width
  val cacheDataWidth = 128  // Cache line width (matching DDR3 MIG)

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
  }

  // Extract JBC init from main memory (same as JopCoreTestHarness)
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
  // Cache Memory Path (matching DDR3 top-level architecture)
  // ==========================================================================

  val bmbBridge = new BmbCacheBridge(config.memConfig.bmbParameter, cacheAddrWidth, cacheDataWidth)
  val cache = new LruCacheCore(CacheConfig(addrWidth = cacheAddrWidth, dataWidth = cacheDataWidth))
  // DDR3-like latency: ~10 cycles for read, ~3 cycles for write (matching MIG behavior)
  val backend = CacheToBramAdapter(cacheAddrWidth, cacheDataWidth, 128 * 1024,
    readLatency = 10, writeLatency = 3)

  // Initialize 128-bit BRAM from 32-bit word data
  // Pack 4 consecutive 32-bit words into each 128-bit word (little-endian lanes)
  val memWords32 = mainMemInit.take(128 * 1024 / 4).padTo(128 * 1024 / 4, BigInt(0))
  val memWords128 = memWords32.grouped(4).toSeq.map { group =>
    val g = group.padTo(4, BigInt(0))
    (g(3) << 96) | (g(2) << 64) | (g(1) << 32) | g(0)
  }
  backend.mem.init(memWords128.map(v => B(v, 128 bits)))

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

  // LruCacheCore -> CacheToBramAdapter
  backend.io.cmd.valid         := cache.io.memCmd.valid
  backend.io.cmd.payload.addr  := cache.io.memCmd.payload.addr
  backend.io.cmd.payload.write := cache.io.memCmd.payload.write
  backend.io.cmd.payload.data  := cache.io.memCmd.payload.data
  backend.io.cmd.payload.mask  := cache.io.memCmd.payload.mask
  cache.io.memCmd.ready        := backend.io.cmd.ready

  cache.io.memRsp.valid         := backend.io.rsp.valid
  cache.io.memRsp.payload.data  := backend.io.rsp.payload.data
  cache.io.memRsp.payload.error := backend.io.rsp.payload.error
  backend.io.rsp.ready          := cache.io.memRsp.ready

  // ==========================================================================
  // Core I/O (internal to JopCore — just tie off external interfaces)
  // ==========================================================================

  // Single-core: no CmpSync
  jopCore.io.syncIn.halted := False
  jopCore.io.syncIn.s_out := False
  jopCore.io.syncIn.status := False

  // No UART RX in test harness
  jopCore.io.rxd := True

  // Debug RAM (unused)
  jopCore.io.debugRamAddr := 0
  jopCore.io.debugHalt := False
  jopCore.io.snoopIn.foreach { si =>
    si.valid := False; si.isArray := False; si.handle := 0; si.index := 0
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
  io.excType := 0  // Exception type not easily snooped with internal I/O
  io.debugMemState := jopCore.io.debugMemState
}

/**
 * GC simulation with LruCacheCore in the memory path.
 * Tests the same cache logic used on DDR3 FPGA.
 */
object JopSmallGcCacheSim extends App {

  val jopFilePath = "java/apps/Small/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/small_gc_cache_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"Log file: $logFilePath")

  val run = TestHistory.startRun("JopSmallGcCacheSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .compile(JopCoreWithCacheTestHarness(romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JOP Small GC Cache Simulation Log ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 40000000  // 40M cycles — cache path is slower than direct BRAM
      val reportInterval = 200000
      var done = false
      var cycle = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

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
          println(f"\n[$cycle%8d] PC=$pc%04x JPC=$jpc%04x memState=$memState UART: '${uartOutput.toString}'")
        }

        // Success: GC cycle completed (R14 = second allocation after first GC)
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

      println(s"\n\n=== Simulation Complete (${cycle} cycles) ===")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Log written to: $logFilePath")

      if (!uartOutput.toString.contains("GC test start")) {
        run.finish("FAIL", "Did not see 'GC test start'")
        println("FAIL: Did not see 'GC test start'")
        System.exit(1)
      }
      if (!uartOutput.toString.contains("R0 f=")) {
        run.finish("FAIL", "Did not see allocation rounds")
        println("FAIL: Did not see allocation rounds")
        System.exit(1)
      }
      if (uartOutput.toString.contains("R80 f=")) {
        run.finish("PASS", s"$cycle cycles, GC works through cache path")
        println("PASS: GC works through cache path")
      } else {
        run.finish("FAIL", s"$cycle cycles, GC did not complete (possible hang)")
        println("FAIL: GC did not complete (possible hang)")
        System.exit(1)
      }
    }
}
