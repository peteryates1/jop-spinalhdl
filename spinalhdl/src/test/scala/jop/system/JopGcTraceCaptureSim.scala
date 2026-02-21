package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb.Bmb
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import jop.ddr3._
import java.io.PrintWriter

/**
 * Test harness for trace capture: JopCore with cache path, burstLen=0.
 *
 * Matches the DDR3 FPGA configuration exactly (burstLen=0, addressWidth=26)
 * and exposes BMB cmd/rsp signals for trace recording.
 *
 * Path: JopCore.bmb -> BmbCacheBridge -> LruCacheCore -> CacheToBramAdapter -> BRAM
 *
 * I/O subsystem (BmbSys, BmbUart) is internal to JopCore.
 */
case class JopCoreTraceCaptureHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt]
) extends Component {

  // Match DDR3 FPGA config exactly: addressWidth=26, burstLen=0
  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(addressWidth = 26, mainMemSize = 128 * 1024, burstLen = 0)
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
    // BMB transaction monitoring
    val bmbCmdFire  = out Bool()
    val bmbCmdAddr  = out UInt(config.memConfig.bmbParameter.access.addressWidth bits)
    val bmbCmdWrite = out Bool()
    val bmbCmdData  = out Bits(32 bits)
    val bmbRspFire  = out Bool()
    val bmbRspData  = out Bits(32 bits)
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
  // Cache Memory Path (matching DDR3 top-level architecture)
  // ==========================================================================

  val bmbBridge = new BmbCacheBridge(config.memConfig.bmbParameter, cacheAddrWidth, cacheDataWidth)
  val cache = new LruCacheCore(CacheConfig(addrWidth = cacheAddrWidth, dataWidth = cacheDataWidth))
  // DDR3-like latency: ~10 cycles for read, ~3 cycles for write
  val backend = CacheToBramAdapter(cacheAddrWidth, cacheDataWidth, 128 * 1024,
    readLatency = 10, writeLatency = 3)

  // Initialize 128-bit BRAM from 32-bit word data
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
  // BMB Monitoring (tap cmd/rsp between JopCore and BmbCacheBridge)
  // ==========================================================================

  io.bmbCmdFire  := jopCore.io.bmb.cmd.fire
  io.bmbCmdAddr  := jopCore.io.bmb.cmd.fragment.address
  io.bmbCmdWrite := jopCore.io.bmb.cmd.fragment.opcode === Bmb.Cmd.Opcode.WRITE
  io.bmbCmdData  := jopCore.io.bmb.cmd.fragment.data
  io.bmbRspFire  := jopCore.io.bmb.rsp.fire
  io.bmbRspData  := jopCore.io.bmb.rsp.fragment.data

  // ==========================================================================
  // Core I/O (internal to JopCore — just tie off external interfaces)
  // ==========================================================================

  // Single-core: no CmpSync
  jopCore.io.syncIn.halted := False
  jopCore.io.syncIn.s_out := False

  // No UART RX in trace capture
  jopCore.io.rxd := True

  // Interrupts disabled
  jopCore.io.irq := False
  jopCore.io.irqEna := False

  // Debug RAM (unused)
  jopCore.io.debugRamAddr := 0

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
 * Trace capture simulation.
 *
 * Runs the Small GC app through the cache path (burstLen=0, matching DDR3 FPGA),
 * captures every BMB cmd/rsp transaction, and writes two hex files:
 *   - gc_mem_init.hex: .jop file contents (one 32-bit hex word per line)
 *   - gc_bmb_trace.hex: trace entries (two 32-bit hex words per line)
 *
 * Trace entry format (64 bits):
 *   Word 0 (low):  [31]=isWrite, [30:28]=000, [27:0]=BMB byte address
 *   Word 1 (high): [31:0]=data (write data for WR, expected read data for RD)
 */
object JopGcTraceCaptureSim extends App {

  val jopFilePath = "java/apps/Small/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val initHexPath = "spinalhdl/generated/gc_mem_init.hex"
  val traceHexPath = "spinalhdl/generated/gc_bmb_trace.hex"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")

  // Write memory init hex file — only the .jop data (not padded to full memory size).
  // The replayer fills zeros for addresses beyond this.
  val jopFileData = JopFileLoader.loadJopFile(jopFilePath)
  val jopWords: Seq[BigInt] = jopFileData.words  // just the actual .jop content (8295 words)

  val initPw = new PrintWriter(initHexPath)
  jopWords.foreach { word =>
    initPw.println(f"${word.toLong & 0xFFFFFFFFL}%08x")
  }
  initPw.close()
  println(s"Wrote memory init: $initHexPath (${jopWords.length} words, ${jopWords.length * 4 / 1024} KB)")

  SimConfig
    .compile(JopCoreTraceCaptureHarness(romData, ramData, mainMemData))
    .doSim { dut =>
      val uartOutput = new StringBuilder

      // Trace buffer: (isWrite, byteAddr, data)
      case class TraceEntry(isWrite: Boolean, byteAddr: Long, data: Long)
      val traceEntries = scala.collection.mutable.ArrayBuffer[TraceEntry]()

      // Pending read commands (for pairing cmd with rsp)
      val pendingReads = scala.collection.mutable.Queue[Long]()  // byte addresses

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 40000000  // 40M cycles
      val reportInterval = 200000
      var done = false
      var cycle = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // Capture BMB commands
        if (dut.io.bmbCmdFire.toBoolean) {
          val isWrite = dut.io.bmbCmdWrite.toBoolean
          val byteAddr = dut.io.bmbCmdAddr.toLong & 0xFFFFFFFL
          val data = dut.io.bmbCmdData.toLong & 0xFFFFFFFFL

          if (isWrite) {
            traceEntries += TraceEntry(isWrite = true, byteAddr = byteAddr, data = data)
          } else {
            pendingReads.enqueue(byteAddr)
          }
        }

        // Capture BMB responses (pair with pending reads)
        if (dut.io.bmbRspFire.toBoolean && pendingReads.nonEmpty) {
          val byteAddr = pendingReads.dequeue()
          val data = dut.io.bmbRspData.toLong & 0xFFFFFFFFL
          traceEntries += TraceEntry(isWrite = false, byteAddr = byteAddr, data = data)
        }

        // Check for exception
        if (dut.io.excFired.toBoolean) {
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          val excType = dut.io.excType.toInt
          println(f"\n[$cycle%8d] *** EXCEPTION type=$excType PC=$pc%04x JPC=$jpc%04x ***")
        }

        // Check for UART output
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          print(if (char >= 32 && char < 127) char.toChar else '.')
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          val pc = dut.io.pc.toInt
          val memState = dut.io.debugMemState.toInt
          println(f"\n[$cycle%8d] PC=$pc%04x memState=$memState trace=${traceEntries.length} pending=${pendingReads.length}")
        }

        // Success: past first GC cycle (R14 = second allocation round after first GC)
        val output = uartOutput.toString
        if (output.contains("R14 f=")) {
          println("\n*** GC cycle completed — capturing tail ***")
          // Run a few more cycles to capture any trailing transactions
          for (_ <- 0 until 50000) {
            dut.clockDomain.waitSampling()
            cycle += 1
            if (dut.io.bmbCmdFire.toBoolean) {
              val isWrite = dut.io.bmbCmdWrite.toBoolean
              val byteAddr = dut.io.bmbCmdAddr.toLong & 0xFFFFFFFL
              val data = dut.io.bmbCmdData.toLong & 0xFFFFFFFFL
              if (isWrite) {
                traceEntries += TraceEntry(isWrite = true, byteAddr = byteAddr, data = data)
              } else {
                pendingReads.enqueue(byteAddr)
              }
            }
            if (dut.io.bmbRspFire.toBoolean && pendingReads.nonEmpty) {
              val byteAddr = pendingReads.dequeue()
              val data = dut.io.bmbRspData.toLong & 0xFFFFFFFFL
              traceEntries += TraceEntry(isWrite = false, byteAddr = byteAddr, data = data)
            }
            if (dut.io.uartTxValid.toBoolean) {
              val char = dut.io.uartTxData.toInt
              uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
              print(if (char >= 32 && char < 127) char.toChar else '.')
            }
          }
          done = true
        }
      }

      // Write trace hex file
      val reads = traceEntries.count(!_.isWrite)
      val writes = traceEntries.count(_.isWrite)
      println(s"\n\n=== Trace Capture Complete ===")
      println(s"Cycles: $cycle")
      println(s"Total entries: ${traceEntries.length} (reads=$reads, writes=$writes)")
      println(s"Pending reads at end: ${pendingReads.length}")
      println(s"UART output: '${uartOutput.toString}'")

      {
        val pw = new PrintWriter(traceHexPath)
        traceEntries.foreach { entry =>
          // Word 0 (low): [31]=isWrite, [27:0]=byte address
          val word0 = (if (entry.isWrite) 0x80000000L else 0L) | (entry.byteAddr & 0xFFFFFFFL)
          // Word 1 (high): data
          val word1 = entry.data & 0xFFFFFFFFL
          pw.println(f"$word1%08x $word0%08x")
        }
        pw.close()
      }

      val traceBytes = traceEntries.length * 8
      val initBytes = jopWords.length * 4
      val maxTraceEntries = 16384  // BRAM budget cap
      val usedTraceEntries = traceEntries.length min maxTraceEntries
      val usedTraceBytes = usedTraceEntries * 8
      println(s"\nOutput files:")
      println(s"  $initHexPath (${jopWords.length} words, ${initBytes / 1024} KB)")
      println(s"  $traceHexPath (${traceEntries.length} entries, ${traceBytes / 1024} KB)")
      if (traceEntries.length > maxTraceEntries)
        println(s"  NOTE: Replayer will use first $maxTraceEntries entries (${usedTraceBytes / 1024} KB)")
      println(s"\nEstimated BRAM usage (replayer):")
      println(s"  Init memory: ${initBytes / 1024} KB (~${(initBytes + 2047) / 2048} BRAMs)")
      println(s"  Trace memory: ${usedTraceBytes / 1024} KB (~${(usedTraceBytes + 2047) / 2048} BRAMs)")
      println(s"  Total: ${(initBytes + usedTraceBytes) / 1024} KB")

      if (!uartOutput.toString.contains("R14 f=")) {
        println("\nFAIL: GC did not complete (possible hang)")
        System.exit(1)
      } else {
        println("\nPASS: Trace captured successfully")
      }
    }
}
