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
 * Behavioral model of MIG native UI interface.
 *
 * Models:
 *   - BRAM-backed memory (128-bit words)
 *   - Variable read latency with configurable min/max
 *   - Periodic app_rdy deassertion (simulating refresh)
 *   - Immediate write acceptance (MIG write FIFO behavior)
 *   - Strict ordering (reads after writes to same address return new data)
 *
 * This is more realistic than CacheToBramAdapter because it exercises the
 * CacheToMigAdapter's handling of MIG protocol signals (app_rdy, app_rd_data_valid pulse).
 */
case class MigBehavioralModel(
  addrWidth: Int = 28,
  dataWidth: Int = 128,
  memSizeBytes: BigInt = 128 * 1024,
  readLatencyMin: Int = 8,
  readLatencyMax: Int = 15,
  refreshInterval: Int = 780,   // ~7.8us at 100MHz (DDR3 refresh period)
  refreshDuration: Int = 30     // cycles where app_rdy is deasserted
) extends Component {
  private val wordBytes = dataWidth / 8          // 16 for 128-bit
  private val wordCount = (memSizeBytes / wordBytes).toInt
  private val wordAddrBits = log2Up(wordCount)
  private val byteOffsetBits = log2Up(wordBytes)

  val io = new Bundle {
    val app_addr          = in Bits(addrWidth bits)
    val app_cmd           = in Bits(3 bits)
    val app_en            = in Bool()
    val app_wdf_data      = in Bits(dataWidth bits)
    val app_wdf_end       = in Bool()
    val app_wdf_mask      = in Bits(wordBytes bits)
    val app_wdf_wren      = in Bool()
    val app_rd_data       = out Bits(dataWidth bits)
    val app_rd_data_valid = out Bool()
    val app_rdy           = out Bool()
    val app_wdf_rdy       = out Bool()
  }

  val mem = Mem(Bits(dataWidth bits), wordCount)

  // Refresh simulation: periodically deassert app_rdy
  val refreshCounter = Reg(UInt(log2Up(refreshInterval + refreshDuration + 1) bits)) init(0)
  val inRefresh = refreshCounter >= U(refreshInterval)
  refreshCounter := refreshCounter + 1
  when(refreshCounter >= U(refreshInterval + refreshDuration - 1)) {
    refreshCounter := 0
  }

  // Read pipeline: FIFO of pending reads with countdown timers
  val readQueueDepth = 8
  val readValid = Vec(Reg(Bool()) init(False), readQueueDepth)
  val readAddr = Vec(Reg(UInt(wordAddrBits bits)) init(0), readQueueDepth)
  val readCountdown = Vec(Reg(UInt(log2Up(readLatencyMax + 1) bits)) init(0), readQueueDepth)
  val readHead = Reg(UInt(log2Up(readQueueDepth) bits)) init(0)
  val readTail = Reg(UInt(log2Up(readQueueDepth) bits)) init(0)
  val readCount = Reg(UInt(log2Up(readQueueDepth + 1) bits)) init(0)

  // LFSR for pseudo-random read latency
  val lfsr = Reg(UInt(16 bits)) init(0xACE1)
  lfsr := (lfsr(0) ## (lfsr(15) ^ lfsr(0)) ## lfsr(14 downto 1)).asUInt

  // Command acceptance
  val cmdWordAddr = io.app_addr(wordAddrBits + byteOffsetBits - 1 downto byteOffsetBits).asUInt
  val isReadCmd = io.app_cmd === B"3'x1"
  val isWriteCmd = io.app_cmd === B"3'x0"

  // MIG app_rdy is a registered output — no combinational dependency on app_en.
  // Deassert during refresh or when read queue is nearly full.
  io.app_rdy := !inRefresh && (readCount < U(readQueueDepth - 1))
  io.app_wdf_rdy := !inRefresh

  // Write handling: MIG allows cmd and data on same cycle or different cycles.
  // Track write address from command, pair with data when both are available.
  val writeAddrReg = Reg(UInt(wordAddrBits bits)) init(0)
  val writeAddrValid = Reg(Bool()) init(False)
  val writeDataReg = Reg(Bits(dataWidth bits)) init(0)
  val writeMaskReg = Reg(Bits(wordBytes bits)) init(0)
  val writeDataValid = Reg(Bool()) init(False)

  // Accept write command: latch address
  val writeCmdAccepted = io.app_en && isWriteCmd && io.app_rdy
  when(writeCmdAccepted) {
    writeAddrReg := cmdWordAddr
    writeAddrValid := True
  }

  // Accept write data: latch data+mask
  val writeDataAccepted = io.app_wdf_wren && io.app_wdf_rdy
  when(writeDataAccepted) {
    writeDataReg := io.app_wdf_data
    writeMaskReg := io.app_wdf_mask
    writeDataValid := True
  }

  // Perform write when both address and data are available.
  // Use combinational addr when cmd+data arrive on same cycle (registered addr not ready yet).
  val writeAddr = Mux(writeCmdAccepted, cmdWordAddr, writeAddrReg)
  val writeData = Mux(writeDataAccepted, io.app_wdf_data, writeDataReg)
  val writeMask = Mux(writeDataAccepted, io.app_wdf_mask, writeMaskReg)
  val canWrite = (writeCmdAccepted || writeAddrValid) && (writeDataAccepted || writeDataValid)

  when(canWrite) {
    val oldData = mem.readAsync(writeAddr)
    val newData = Bits(dataWidth bits)
    for (byte <- 0 until wordBytes) {
      val hi = byte * 8 + 7
      val lo = byte * 8
      // MIG mask: 1 = don't write, 0 = write
      newData(hi downto lo) := Mux(writeMask(byte), oldData(hi downto lo), writeData(hi downto lo))
    }
    mem.write(writeAddr, newData)
    writeAddrValid := False
    writeDataValid := False
  }

  // Read command enqueue
  when(io.app_en && isReadCmd && io.app_rdy) {
    readValid(readTail) := True
    readAddr(readTail) := cmdWordAddr
    // Variable latency based on LFSR
    val latRange = readLatencyMax - readLatencyMin
    val latency = if (latRange > 0) {
      U(readLatencyMin) + (lfsr(log2Up(latRange + 1) - 1 downto 0) % U(latRange + 1)).resize(readCountdown(0).getWidth)
    } else {
      U(readLatencyMin, readCountdown(0).getWidth bits)
    }
    readCountdown(readTail) := latency
    readTail := readTail + 1
    readCount := readCount + 1
  }

  // Countdown all pending reads
  for (i <- 0 until readQueueDepth) {
    when(readValid(i) && readCountdown(i) > 0) {
      readCountdown(i) := readCountdown(i) - 1
    }
  }

  // Read data output: head of queue when countdown reaches 0
  io.app_rd_data := mem.readAsync(readAddr(readHead))
  io.app_rd_data_valid := readValid(readHead) && readCountdown(readHead) === 0

  when(io.app_rd_data_valid) {
    readValid(readHead) := False
    readHead := readHead + 1
    readCount := readCount - 1
  }
}

/**
 * Test harness: JopCore -> BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MigBehavioralModel
 *
 * This matches the FPGA architecture exactly, using the real CacheToMigAdapter
 * instead of the simplified CacheToBramAdapter.
 *
 * I/O subsystem (BmbSys, BmbUart) is internal to JopCore.
 */
case class JopCoreWithMigTestHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt]
) extends Component {

  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(addressWidth = 26, mainMemSize = 128 * 1024, burstLen = 8)
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
  // DDR3-like Memory Path: JopCore -> BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MigModel
  // ==========================================================================

  val bmbBridge = new BmbCacheBridge(config.memConfig.bmbParameter, cacheAddrWidth, cacheDataWidth)
  val cache = new LruCacheCore(CacheConfig(addrWidth = cacheAddrWidth, dataWidth = cacheDataWidth))
  val adapter = new CacheToMigAdapter
  val migModel = MigBehavioralModel(
    addrWidth = 28,
    dataWidth = 128,
    memSizeBytes = 128 * 1024,
    readLatencyMin = 8,
    readLatencyMax = 15,
    refreshInterval = 780,
    refreshDuration = 30
  )

  // Initialize MIG model memory from 32-bit words (same packing as cache sim)
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
  io.debugCacheState := cache.io.debugState
  io.debugAdapterState := adapter.io.debugState
}

/**
 * GC simulation with MIG behavioral model.
 * Tests the actual CacheToMigAdapter (not CacheToBramAdapter).
 */
object JopSmallGcMigSim extends App {

  val jopFilePath = "java/apps/Small/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/small_gc_mig_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"Log file: $logFilePath")

  val run = TestHistory.startRun("JopSmallGcMigSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .compile(JopCoreWithMigTestHarness(romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JOP Small GC MIG Simulation Log ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 60000000  // 60M cycles — MIG model is slower
      val reportInterval = 200000
      var done = false
      var cycle = 0
      var busyCycles = 0
      var maxBusyStreak = 0
      var currentBusyStreak = 0

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
            val msg = f"[$cycle%8d] *** POTENTIAL HANG: busy for 100k cycles! memState=$memState cacheState=$cacheState adapterState=$adapterState PC=$pc%04x JPC=$jpc%04x aout=0x$aout%08x bout=0x$bout%08x ***"
            println(s"\n$msg")
            logLine(msg)
          }
          if (currentBusyStreak >= 200000) {
            val msg = f"[$cycle%8d] *** HANG CONFIRMED: busy for 200k+ cycles, aborting ***"
            println(s"\n$msg")
            logLine(msg)
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
          println(f"\n[$cycle%8d] PC=$pc%04x JPC=$jpc%04x memState=$memState busyCycles=$busyCycles maxBusyStreak=$maxBusyStreak UART: '${uartOutput.toString}'")
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

      println(s"\n\n=== Simulation Complete (${cycle} cycles) ===")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Total busy cycles: $busyCycles, max busy streak: $maxBusyStreak")
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
        run.finish("PASS", s"$cycle cycles, GC works with MIG behavioral model")
        println("PASS: GC works with MIG behavioral model")
      } else {
        run.finish("FAIL", s"$cycle cycles, GC did not complete (possible hang)")
        println("FAIL: GC did not complete (possible hang)")
        System.exit(1)
      }
    }
}
