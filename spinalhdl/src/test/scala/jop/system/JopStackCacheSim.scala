package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.{JopFileLoader, TestHistory}
import jop.memory.JopMemoryConfig
import jop.MicrocodeNames
import java.io.PrintWriter

/**
 * Test harness for JOP with stack cache enabled.
 *
 * Uses JopCluster (cpuCnt=1) to handle the stack DMA BMB arbitration.
 * The cluster arbiter routes both the core's main BMB and the stack DMA's
 * BMB to the shared BmbOnChipRam.
 */
case class JopStackCacheTestHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt],
  memSize: Int = 512 * 1024
) extends Component {

  val baseConfig = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = memSize),
    useStackCache = true,
    spillBaseAddrOverride = Some(0)  // Dedicated spill BRAM: addresses start at 0
  )

  val io = new Bundle {
    val pc          = out UInt(baseConfig.pcWidth bits)
    val jpc         = out UInt((baseConfig.jpcWidth + 1) bits)
    val aout        = out Bits(baseConfig.dataWidth bits)
    val bout        = out Bits(baseConfig.dataWidth bits)
    val memBusy     = out Bool()
    val uartTxData  = out Bits(8 bits)
    val uartTxValid = out Bool()
    val excFired    = out Bool()

    // Stack cache debug
    val scSp             = out UInt(baseConfig.stackConfig.spWidth bits)
    val scRotState       = out UInt(3 bits)
    val scActiveBankIdx  = out UInt(2 bits)
    val scBankBase       = out Vec(UInt(baseConfig.stackConfig.spWidth bits), 3)
    val scBankResident   = out Bits(3 bits)
    val scBankDirty      = out Bits(3 bits)
    val scNeedsRot       = out Bool()
    val scVp             = out UInt(baseConfig.stackConfig.spWidth bits)

    // Write-snoop debug
    val scPipeWrAddr  = out UInt(baseConfig.stackConfig.spWidth bits)
    val scPipeWrData  = out Bits(baseConfig.dataWidth bits)
    val scPipeWrEn    = out Bool()

    // VP+0 value readback
    val scVp0Data = out Bits(baseConfig.dataWidth bits)

    // Memory controller state debug
    val memCtrlState = out UInt(5 bits)

    // BMB bus debug (arbiter output → BRAM)
    val bmbCmdValid = out Bool()
    val bmbCmdReady = out Bool()
    val bmbCmdAddr  = out UInt(32 bits)
    val bmbCmdOpcode = out Bits(1 bits)
    val bmbCmdSource = out UInt(4 bits)
    val bmbRspValid = out Bool()
    val bmbRspReady = out Bool()
    val bmbRspSource = out UInt(4 bits)

    // BC fill debug
    val bcFillAddr = out UInt(baseConfig.memConfig.addressWidth bits)
    val bcFillLen  = out UInt(10 bits)
    val bcFillCount = out UInt(10 bits)
    val bcRdCapture = out Bits(32 bits)
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
    Seq(BigInt((w >> 24) & 0xFF), BigInt((w >> 16) & 0xFF),
        BigInt((w >> 8) & 0xFF), BigInt((w >> 0) & 0xFF))
  }.padTo(2048, BigInt(0))

  // JOP Cluster with stack cache: cpuCnt=1, useStackCache=true
  // separateStackDmaBus=true: DMA spill/fill uses its own memory to avoid
  // address-wrapping collisions with main BRAM (see docs/stack-cache-debug-log.md)
  val cluster = JopCluster(
    cpuCnt = 1,
    baseConfig = baseConfig,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit),
    separateStackDmaBus = true
  )

  cluster.io.rxd := True

  // Main Block RAM (program data, heap, handles — accessed by MC only)
  val memWords = memSize / 4
  val ram = BmbOnChipRam(
    p = cluster.bmbParameter,
    size = memSize,
    hexInit = null
  )
  val initData = mainMemInit.take(memWords).padTo(memWords, BigInt(0))
  ram.ram.init(initData.map(v => B(v, 32 bits)))
  ram.io.bus << cluster.io.bmb

  // Dedicated spill BRAM (stack cache DMA only — isolated from main memory)
  // 64KB: supports bankBase up to ~16000 (192 words per spill × ~85 rotations)
  val spillBramSize = 64 * 1024
  val spillRam = BmbOnChipRam(
    p = baseConfig.memConfig.bmbParameter,
    size = spillBramSize,
    hexInit = null
  )
  spillRam.io.bus << cluster.io.stackDmaBmb.get

  // BMB bus debug (tap the arbiter → BRAM bus)
  io.bmbCmdValid  := cluster.io.bmb.cmd.valid
  io.bmbCmdReady  := cluster.io.bmb.cmd.ready
  io.bmbCmdAddr   := cluster.io.bmb.cmd.fragment.address.resized
  io.bmbCmdOpcode := cluster.io.bmb.cmd.fragment.opcode
  io.bmbCmdSource := cluster.io.bmb.cmd.fragment.source.resized
  io.bmbRspValid  := cluster.io.bmb.rsp.valid
  io.bmbRspReady  := cluster.io.bmb.rsp.ready
  io.bmbRspSource := cluster.io.bmb.rsp.fragment.source.resized

  // BC fill debug
  io.bcFillAddr  := cluster.io.debugBcFillAddr
  io.bcFillLen   := cluster.io.debugBcFillLen
  io.bcFillCount := cluster.io.debugBcFillCount
  io.bcRdCapture := cluster.io.debugBcRdCapture

  // Output wiring
  io.pc          := cluster.io.pc(0)
  io.jpc         := cluster.io.jpc(0)
  io.aout        := cluster.io.aout(0)
  io.bout        := cluster.io.bout(0)
  io.memBusy     := cluster.io.memBusy(0)
  io.uartTxData  := cluster.io.uartTxData
  io.uartTxValid := cluster.io.uartTxValid
  io.excFired    := cluster.io.debugExc

  // Stack cache debug wiring
  io.scSp             := cluster.io.scDebugSp.get
  io.scRotState       := cluster.io.scDebugRotState.get
  io.scActiveBankIdx  := cluster.io.scDebugActiveBankIdx.get
  io.scBankBase       := cluster.io.scDebugBankBase.get
  io.scBankResident   := cluster.io.scDebugBankResident.get
  io.scBankDirty      := cluster.io.scDebugBankDirty.get
  io.scNeedsRot       := cluster.io.scDebugNeedsRot.get
  io.scVp             := cluster.io.scDebugVp.get

  // Write-snoop debug wiring
  io.scPipeWrAddr  := cluster.io.scDebugPipeWrAddr.get
  io.scPipeWrData  := cluster.io.scDebugPipeWrData.get
  io.scPipeWrEn    := cluster.io.scDebugPipeWrEn.get

  // VP+0 readback wiring
  io.scVp0Data := cluster.io.scDebugVp0Data.get

  // Memory controller state
  io.memCtrlState := cluster.io.debugMemState
}

/**
 * JVM test suite BRAM simulation with 3-bank rotating stack cache enabled.
 */
object JopJvmTestsStackCacheBramSim extends App {

  val jopFilePath = "java/apps/JvmTests/DoAll.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/jvmtests_stackcache_bram_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 512 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")
  println(s"Stack cache: ENABLED (3-bank rotating)")

  SimConfig
    .compile(JopStackCacheTestHarness(romData, ramData, mainMemData, memSize = 512 * 1024))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      val uartOutput = new StringBuilder
      val lineBuffer = new StringBuilder
      var deepRecursionStarted = false
      var deepRecursionCycleStart = 0

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      val rotNames = Array("IDLE", "SPILL_S", "SPILL_W", "FILL_S", "FILL_W", "ZERO_F")

      // Memory controller state names (from BmbMemoryController.State enum order)
      val memStateNames = Array(
        "IDLE", "RD_WAIT", "WR_WAIT", "IAST_W", "PF_WAIT",
        "H_READ", "H_WAIT", "H_CALC", "H_ACC", "H_DATA",
        "HB_RD", "HB_WAIT", "NP_EXC", "AB_EXC",
        "BC_CHK", "BC_R1", "BC_LOOP", "BC_CMD",
        "AC_CMD", "AC_WAIT",
        "CP_SET", "CP_RD", "CP_RDW", "CP_WR", "CP_STOP",
        "GS_RD", "PS_WR", "LAST"
      )
      def memStateName(s: Int): String = if (s >= 0 && s < memStateNames.length) memStateNames(s) else s"?$s"

      def printStackCacheState(cycle: Int, label: String = ""): Unit = {
        val spVal = dut.io.scSp.toInt
        val rotState = dut.io.scRotState.toInt
        val rotName = if (rotState < rotNames.length) rotNames(rotState) else s"?$rotState"
        val activeBank = dut.io.scActiveBankIdx.toInt
        val b0base = dut.io.scBankBase(0).toInt
        val b1base = dut.io.scBankBase(1).toInt
        val b2base = dut.io.scBankBase(2).toInt
        val residentBits = dut.io.scBankResident.toInt
        val dirtyBits = dut.io.scBankDirty.toInt
        val needsRot = dut.io.scNeedsRot.toBoolean
        val pcVal = dut.io.pc.toInt

        def bankStr(i: Int, base: Int) = {
          val r = if ((residentBits & (1 << i)) != 0) "R" else "."
          val d = if ((dirtyBits & (1 << i)) != 0) "D" else "."
          val a = if (i == activeBank) "*" else " "
          f"$a$i[$base%5d $r$d]"
        }

        val msg = f"  SC[$cycle%8d] SP=$spVal%5d rot=$rotName%-8s " +
          s"${bankStr(0, b0base)} ${bankStr(1, b1base)} ${bankStr(2, b2base)} " +
          f"NR=$needsRot%5s PC=$pcVal%4d $label"
        println(msg)
        logLine(msg)
      }

      // Build PC -> instruction mnemonic lookup from ROM data
      val instrMask = (1 << 10) - 1  // 10-bit instruction
      def instrAt(pc: Int): Int = if (pc >= 0 && pc < romData.length) (romData(pc).toInt & instrMask) else 0
      def disasmAt(pc: Int): String = MicrocodeNames.disasm(instrAt(pc))

      logLine("=== JOP JVM Tests BRAM Simulation (Stack Cache Enabled) ===")

      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      // Internal signal access for data-path debugging
      val stackStg = dut.cluster.cores(0).pipeline.stack
      val decodeStg = dut.cluster.cores(0).pipeline.decode
      val pipeStg = dut.cluster.cores(0).pipeline

      val maxCycles = 20000000  // Extended to see if DeepRecursion completes
      val reportInterval = 100000
      var lastRotState = 0
      var spillCount = 0
      var fillCount = 0
      var maxSp = 0
      var lastSp = 0
      var spDecreaseCount = 0
      var firstDecreaseCycle = 0

      // Stuck detection: track SP min/max over a sliding window
      var stuckWindowStart = -1   // -1 = not started
      var stuckWindowMinSp = Int.MaxValue
      var stuckWindowMaxSp = 0
      var stuckDetected = false
      var stuckTraceCycles = 0
      val stuckTraceLimit = 800   // Print this many cycles of trace when stuck
      val stuckThreshold = 10000  // Detect stuck if SP range < 30 for this many cycles

      // Write-snoop: log writes after 3rd spill completes (where corruption starts)
      val wrSnoopLimit = 10000  // Log first N writes after 3rd spill
      var wrSnoopCount = 0
      var wrSnoopActive = false   // Only activate after 3rd spill
      var prevPc = 0

      // Post-rotation trace: detailed per-cycle trace after 3rd rotation completes
      var postRotTraceActive = false
      var postRotTraceCnt = 0
      val postRotTraceLimit = 500  // Trace 500 cycles after 3rd rotation

      // Freeze detector: detects when PC stops changing for N cycles
      var freezePrevPc = -1
      var freezeSamePcCount = 0
      var freezeDetected = false
      var freezeTraceCycles = 0
      val freezeTraceLimit = 200
      val freezeThreshold = 100  // PC unchanged for 100 cycles = freeze

      // Circular backlog buffer: stores last N cycles for dump when freeze detected
      val backlogSize = 800  // Enough to capture full spill (~600 cycles) + lead-up
      val backlog = new Array[String](backlogSize)
      var backlogIdx = 0
      var backlogActive = false  // Only active after 3rd spill

      // VP+0 corruption detector: traces every cycle from VP change until VP+0 is read
      var lastVp = 0
      var vp0CorruptionDetected = false
      var vp0TraceActive = false
      var vp0TraceCount = 0
      val vp0TraceLimit = 200  // Per-cycle trace around first corruption (reduced)

      // VP oscillation detector: count repeated transitions at same levels
      var vpOscVpA = -1
      var vpOscVpB = -1
      var vpOscCount = 0
      var vpOscLoggedCount = 0  // How many we've actually logged (limit output)

      // Compute which bank (if any) a virtual address maps to
      def bankHitAnalysis(addr: Int): String = {
        if (addr < 64) return "SCRATCH"
        val residentBits = dut.io.scBankResident.toInt
        val hits = (0 until 3).filter { i =>
          val base = dut.io.scBankBase(i).toInt
          val resident = (residentBits & (1 << i)) != 0
          addr >= base && addr < base + 192 && resident
        }
        if (hits.isEmpty) "NO_BANK_HIT!"
        else {
          val i = hits.head
          val base = dut.io.scBankBase(i).toInt
          f"B$i@${addr - base}%3d"
        }
      }

      for (cycle <- 0 until maxCycles) {
        dut.clockDomain.waitSampling()

        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          if (char == 10) {  // newline
            val line = lineBuffer.toString
            println(line)
            logLine(f"[$cycle%7d] $line")
            lineBuffer.clear()
          } else if (char >= 32 && char < 127) {
            lineBuffer.append(char.toChar)
          }
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '\n')
        }

        // Detect DeepRecursion from lineBuffer
        if (!deepRecursionStarted && lineBuffer.toString.contains("Deep")) {
          deepRecursionStarted = true
          deepRecursionCycleStart = cycle
          stuckWindowStart = cycle
          backlogActive = true
          stuckWindowMinSp = Int.MaxValue
          stuckWindowMaxSp = 0
          println(s"  >>> DeepRecursion detected at cycle $cycle <<<")
          printStackCacheState(cycle, "START")
        }

        // Debug during DeepRecursion
        if (deepRecursionStarted) {
          val rotState = dut.io.scRotState.toInt
          val spVal = dut.io.scSp.toInt
          val vpVal = dut.io.scVp.toInt
          val aVal = dut.io.aout.toLong & 0xFFFFFFFFL
          val bVal = dut.io.bout.toLong & 0xFFFFFFFFL
          val pcVal = dut.io.pc.toInt

          // Write-snoop: log bank writes (only after 3rd spill)
          if (dut.io.scPipeWrEn.toBoolean && wrSnoopActive && wrSnoopCount < wrSnoopLimit) {
            val wrAddr = dut.io.scPipeWrAddr.toInt
            val wrData = dut.io.scPipeWrData.toLong & 0xFFFFFFFFL
            val instrName = disasmAt(prevPc)
            val bankInfo = bankHitAnalysis(wrAddr)
            val msg = f"  WR[$cycle%8d] addr=$wrAddr%5d data=0x${wrData}%08X $bankInfo%-14s SP=$spVal%5d VP=$vpVal%5d irPC=$prevPc%4d ${instrName}%-12s A=0x${aVal}%08X B=0x${bVal}%08X"
            logLine(msg)
            println(msg)
            // Alert on writes that miss all banks
            if (wrAddr >= 64 && bankInfo.contains("NO_BANK")) {
              val alertMsg = f"  !!! WRITE MISS addr=$wrAddr%5d $bankInfo - B0=${dut.io.scBankBase(0).toInt}%5d B1=${dut.io.scBankBase(1).toInt}%5d B2=${dut.io.scBankBase(2).toInt}%5d res=${dut.io.scBankResident.toInt} !!!"
              println(alertMsg)
              logLine(alertMsg)
            }
            wrSnoopCount += 1
            if (wrSnoopCount == wrSnoopLimit) {
              val msg2 = s"  >>> Write snoop limit ($wrSnoopLimit) reached <<<"
              println(msg2)
              logLine(msg2)
            }
          }

          // VP+0 value monitor: read bank RAM at vp0 every cycle
          val vp0Val = dut.io.scVp0Data.toLong & 0xFFFFFFFFL
          val vp0Bank = bankHitAnalysis(vpVal)

          // Detect VP changes (invoke/return transitions)
          val jpcVal = dut.io.jpc.toInt
          if (vpVal != lastVp && vpVal >= 64 && spillCount >= 3) {
            // Track oscillation: same two VP levels repeating
            val isOscillation = (lastVp == vpOscVpA && vpVal == vpOscVpB) ||
                                (lastVp == vpOscVpB && vpVal == vpOscVpA)
            if (isOscillation) {
              vpOscCount += 1
            } else {
              // New pattern — report accumulated oscillation if any
              if (vpOscCount > 10) {
                val oscMsg = f"  VP_OSC_END[$cycle%8d] oscillation $vpOscVpA<->$vpOscVpB ended after $vpOscCount round-trips"
                println(oscMsg)
                logLine(oscMsg)
              }
              vpOscVpA = lastVp
              vpOscVpB = vpVal
              vpOscCount = 1
              vpOscLoggedCount = 0
            }

            // Rate-limit output: log first 20, then every 100th, plus when pattern changes
            val shouldLog = vpOscLoggedCount < 20 || vpOscCount % 100 == 0 || !isOscillation
            if (shouldLog) {
              val oscInfo = if (vpOscCount > 1) f" osc#$vpOscCount" else ""
              val msg = f"  VP_CHG[$cycle%8d] VP: $lastVp%5d -> $vpVal%5d VP+0=$vp0Bank ram[VP+0]=0x${vp0Val}%08X SP=$spVal%5d PC=$pcVal%4d JPC=$jpcVal%5d A=0x${aVal}%08X B=0x${bVal}%08X$oscInfo"
              println(msg)
              logLine(msg)
              vpOscLoggedCount += 1
            }
            // If VP+0 reads as 0 and VP is in the bank range, flag it (only first time)
            if (vp0Val == 0L && vpVal >= 1024 && !vp0CorruptionDetected) {
              vp0CorruptionDetected = true
              vp0TraceActive = true
              vp0TraceCount = 0
              val alertMsg = f"  !!! VP+0 CORRUPTION: VP=$vpVal%5d ram[VP+0]=0x${vp0Val}%08X at cycle $cycle !!!"
              println(alertMsg)
              logLine(alertMsg)
              printStackCacheState(cycle, "VP0_CORRUPT")
            }
          }
          lastVp = vpVal

          // Per-cycle trace around VP+0 corruption
          if (vp0TraceActive && vp0TraceCount < vp0TraceLimit) {
            val wrEn = dut.io.scPipeWrEn.toBoolean
            val wrAddr = dut.io.scPipeWrAddr.toInt
            val wrData = dut.io.scPipeWrData.toLong & 0xFFFFFFFFL
            val wrBankInfo = if (wrEn) bankHitAnalysis(wrAddr) else ""
            val wrStr = if (wrEn) f"WR[$wrAddr%5d]=0x${wrData}%08X $wrBankInfo" else ""
            val instrName = disasmAt(prevPc)
            val msg = f"  VT[$cycle%8d] PC=$pcVal%4d SP=$spVal%5d VP=$vpVal%5d vp0=0x${vp0Val}%08X A=0x${aVal}%08X B=0x${bVal}%08X ${instrName}%-12s $wrStr"
            logLine(msg)
            println(msg)
            vp0TraceCount += 1
            if (vp0TraceCount >= vp0TraceLimit) {
              println("  --- END VP0 TRACE ---")
              logLine("  --- END VP0 TRACE ---")
            }
          }

          // Post-rotation trace: detailed per-cycle log after 3rd rotation
          if (postRotTraceActive && postRotTraceCnt < postRotTraceLimit) {
            val memBusy = dut.io.memBusy.toBoolean
            val busyStr = if (memBusy) "MB" else "  "
            val instrName = disasmAt(prevPc)
            val instrWord = instrAt(prevPc)
            val jfetch = if (prevPc >= 0 && prevPc < romData.length) ((romData(prevPc).toInt >> 11) & 1) else 0
            val opd = if (prevPc >= 0 && prevPc < romData.length) ((romData(prevPc).toInt >> 10) & 1) else 0
            val wrEn = dut.io.scPipeWrEn.toBoolean
            val wrAddr = dut.io.scPipeWrAddr.toInt
            val wrData = dut.io.scPipeWrData.toLong & 0xFFFFFFFFL
            val wrStr = if (wrEn) f"WR[$wrAddr%5d]=0x${wrData}%08X ${bankHitAnalysis(wrAddr)}" else ""
            val mcs = dut.io.memCtrlState.toInt
            val mcsName = memStateName(mcs)
            val cmdV = if (dut.io.bmbCmdValid.toBoolean) "V" else "."
            val cmdR = if (dut.io.bmbCmdReady.toBoolean) "R" else "."
            val cmdSrc = dut.io.bmbCmdSource.toInt
            val cmdAddr = dut.io.bmbCmdAddr.toLong & 0xFFFFFFFFL
            val cmdOp = if (dut.io.bmbCmdOpcode.toInt == 0) "R" else "W"
            val rspV = if (dut.io.bmbRspValid.toBoolean) "V" else "."
            val rspR = if (dut.io.bmbRspReady.toBoolean) "R" else "."
            val rspSrc = dut.io.bmbRspSource.toInt
            val bmbStr = f"BMB:cmd=$cmdV$cmdR s$cmdSrc $cmdOp@0x${cmdAddr}%08X rsp=$rspV$rspR s$rspSrc"
            val bcAddr = dut.io.bcFillAddr.toLong & 0xFFFFFFFFL
            val bcLen = dut.io.bcFillLen.toInt
            val bcCnt = dut.io.bcFillCount.toInt
            val bcCap = dut.io.bcRdCapture.toLong & 0xFFFFFFFFL
            val bcStr = if (mcs >= 14 && mcs <= 17) f"BC:a=0x${bcAddr}%06X len=$bcLen cnt=$bcCnt cap=0x${bcCap}%08X" else ""
            val msg = f"  PR[$cycle%8d] PC=$pcVal%4d SP=$spVal%5d VP=$vpVal%5d jf=$jfetch od=$opd $busyStr ${instrName}%-12s MC=$mcsName%-8s A=0x${aVal}%08X B=0x${bVal}%08X $bcStr $wrStr"
            logLine(msg)
            println(msg)
            // Data-path debug: show what drives A register each cycle
            val dpEnaA = decodeStg.aluControlDecode.enaAReg.toBoolean
            val dpSelAmux = if (decodeStg.aluControlDecode.selAmuxReg.toBoolean) "lmux" else "alu "
            val dpSelLmux = decodeStg.aluControlDecode.selLmuxReg.toInt
            val dpSelLmuxName = dpSelLmux match {
              case 0 => "log"; case 1 => "shf"; case 2 => "ram"; case 3 => "opd"
              case 4 => "din"; case 5 => "rmx"; case _ => s"?$dpSelLmux"
            }
            val dpDinSel = pipeStg.dinMuxSel.toInt
            val dpDinSelName = dpDinSel match {
              case 0 => "memRd"; case 1 => "mul"; case 2 => "bcSt"; case 3 => "zero"
            }
            val dpAmux = stackStg.amux.toLong & 0xFFFFFFFFL
            val dpLmux = stackStg.lmux.toLong & 0xFFFFFFFFL
            val dpRamDout = stackStg.ramDout.toLong & 0xFFFFFFFFL
            val dpRdAddr = stackStg.ramRdaddrReg.toInt
            val dpRBusy = stackStg.rotBusyDly.toBoolean
            val dpRdBank = bankHitAnalysis(dpRdAddr)
            val dpMsg = f"    DP[$cycle%8d] enaA=$dpEnaA%-5s selAm=$dpSelAmux selLm=$dpSelLmuxName%-4s dinSel=$dpDinSelName%-5s rBdly=$dpRBusy%-5s rdAddr=$dpRdAddr%5d($dpRdBank) amux=0x${dpAmux}%08X lmux=0x${dpLmux}%08X ramDout=0x${dpRamDout}%08X"
            logLine(dpMsg)
            println(dpMsg)
            postRotTraceCnt += 1
            if (postRotTraceCnt >= postRotTraceLimit) {
              val endMsg = "  --- END POST-ROT TRACE ---"
              println(endMsg)
              logLine(endMsg)
            }
          }

          // Track SP direction
          if (spVal > maxSp) maxSp = spVal
          if (spVal < lastSp - 5 && spDecreaseCount == 0) {
            spDecreaseCount += 1
            firstDecreaseCycle = cycle
            println(f"  >>> SP DECREASED at cycle $cycle: $lastSp -> $spVal (max was $maxSp) <<<")
            printStackCacheState(cycle, "SP_DEC")
          }
          lastSp = spVal

          // Count spill/fill operations and print detailed state on every transition
          if (rotState != lastRotState) {
            if (rotState == 1) spillCount += 1   // SPILL_START
            if (rotState == 3) fillCount += 1    // FILL_START
            val rotName = if (rotState < rotNames.length) rotNames(rotState) else s"?$rotState"
            val prevRotName = if (lastRotState < rotNames.length) rotNames(lastRotState) else s"?$lastRotState"
            val msg = f"  ROT[$cycle%8d] $prevRotName%-8s -> $rotName%-8s SP=$spVal%5d VP=$vpVal%5d A=0x${aVal}%08X B=0x${bVal}%08X PC=$pcVal%4d"
            println(msg)
            logLine(msg)
            printStackCacheState(cycle, s"ROT_$rotName")
            lastRotState = rotState

            // Activate write snoop and post-rotation trace after 3rd spill completes
            if (spillCount >= 3 && rotState == 0 && !wrSnoopActive) {  // IDLE after 3rd spill
              wrSnoopActive = true
              wrSnoopCount = 0
              postRotTraceActive = true
              postRotTraceCnt = 0
              val activateMsg = f"  >>> WRITE SNOOP + POST-ROT TRACE ACTIVE at cycle $cycle (spill #$spillCount complete) <<<"
              println(activateMsg)
              logLine(activateMsg)
            }
          }

          // Print detailed state every 500 cycles (first 50K cycles of DeepRecursion)
          val drCycles = cycle - deepRecursionCycleStart
          if (drCycles > 0 && drCycles % 500 == 0 && drCycles < 50000) {
            val rotName = if (rotState < rotNames.length) rotNames(rotState) else s"?$rotState"
            val msg = f"  SP[$cycle%8d] sp=$spVal%5d vp=$vpVal%5d max=$maxSp%5d rot=$rotName%-8s A=0x${aVal}%08X B=0x${bVal}%08X PC=$pcVal%4d spills=$spillCount fills=$fillCount"
            println(msg)
            logLine(msg)
          }

          // Stuck detection: if SP stays in a narrow range for too long
          if (!stuckDetected && stuckWindowStart >= 0) {
            if (spVal < stuckWindowMinSp) stuckWindowMinSp = spVal
            if (spVal > stuckWindowMaxSp) stuckWindowMaxSp = spVal

            if (stuckWindowMaxSp - stuckWindowMinSp > 30) {
              // Range too wide, reset window
              stuckWindowStart = cycle
              stuckWindowMinSp = spVal
              stuckWindowMaxSp = spVal
            }

            if (cycle - stuckWindowStart > stuckThreshold) {
              stuckDetected = true
              val msg = f"  >>> STUCK DETECTED at cycle $cycle: SP range [$stuckWindowMinSp, $stuckWindowMaxSp] for ${cycle - stuckWindowStart} cycles <<<"
              println(msg)
              logLine(msg)
              printStackCacheState(cycle, "STUCK")
              println("  --- BEGIN STUCK TRACE (per-cycle with write snoop) ---")
              logLine("  --- BEGIN STUCK TRACE ---")
            }
          }

          // Print per-cycle trace when stuck (includes write snoop + instruction disasm)
          if (stuckDetected && stuckTraceCycles < stuckTraceLimit) {
            val jpcVal = dut.io.jpc.toInt
            val memBusy = dut.io.memBusy.toBoolean
            val wrEn = dut.io.scPipeWrEn.toBoolean
            val wrAddr = dut.io.scPipeWrAddr.toInt
            val wrData = dut.io.scPipeWrData.toLong & 0xFFFFFFFFL
            val wrBankInfo = if (wrEn) bankHitAnalysis(wrAddr) else ""
            val wrStr = if (wrEn) f"WR[$wrAddr%5d]=0x${wrData}%08X $wrBankInfo" else ""
            val instrName = disasmAt(prevPc)
            val instrWord = instrAt(prevPc)
            // Classify stack behavior from ir[9:6]
            val ir96 = (instrWord >> 6) & 0xF
            val stackOp = ir96 match {
              case 0 | 1 => "POP "
              case 2 | 3 => "PUSH"
              case 6 => "BZ  "
              case 7 => "BNZ "
              case _ => "    "
            }
            val busyStr = if (memBusy) "MEMBUSY" else "       "
            val msg = f"  T[$cycle%8d] PC=$pcVal%4d SP=$spVal%5d VP=$vpVal%5d A=0x${aVal}%08X B=0x${bVal}%08X $busyStr $stackOp ${instrName}%-12s $wrStr"
            println(msg)
            logLine(msg)
            // Print bank state every 50 cycles within stuck trace
            if (stuckTraceCycles % 50 == 0) {
              printStackCacheState(cycle, "STUCK_BANK")
            }
            stuckTraceCycles += 1
            if (stuckTraceCycles >= stuckTraceLimit) {
              println("  --- END STUCK TRACE ---")
              logLine("  --- END STUCK TRACE ---")
            }
          }

          // Backlog: record per-cycle state in circular buffer (always, including during rotation)
          if (backlogActive) {
            val memBusy = dut.io.memBusy.toBoolean
            val busyStr = if (memBusy) "MB" else "  "
            val instrName = disasmAt(prevPc)
            val wrEn = dut.io.scPipeWrEn.toBoolean
            val wrAddr = dut.io.scPipeWrAddr.toInt
            val wrData = dut.io.scPipeWrData.toLong & 0xFFFFFFFFL
            val wrStr = if (wrEn) f"WR[$wrAddr%5d]=0x${wrData}%08X" else ""
            val mcs = dut.io.memCtrlState.toInt
            val mcsName = memStateName(mcs)
            val rotName = if (rotState < rotNames.length) rotNames(rotState) else s"?$rotState"
            backlog(backlogIdx % backlogSize) = f"  BL[$cycle%8d] PC=$pcVal%4d SP=$spVal%5d VP=$vpVal%5d $busyStr ${instrName}%-12s MC=$mcsName%-8s rot=$rotName%-8s A=0x${aVal}%08X B=0x${bVal}%08X $wrStr"
            backlogIdx += 1
          }

          // Freeze detector: detect when PC stops changing (only when rotation is IDLE)
          if (spillCount >= 3 && !freezeDetected && rotState == 0) {
            if (pcVal == freezePrevPc) {
              freezeSamePcCount += 1
              if (freezeSamePcCount >= freezeThreshold) {
                freezeDetected = true
                val memBusy = dut.io.memBusy.toBoolean
                val freezeMsg = f"  >>> FREEZE DETECTED at cycle $cycle: PC=$pcVal for $freezeSamePcCount cycles, memBusy=$memBusy <<<"
                println(freezeMsg)
                logLine(freezeMsg)
                printStackCacheState(cycle, "FREEZE")

                // Dump backlog
                println("  --- BACKLOG (last ~300 cycles before freeze) ---")
                logLine("  --- BACKLOG ---")
                val total = if (backlogIdx < backlogSize) backlogIdx else backlogSize
                val start = if (backlogIdx > backlogSize) backlogIdx - backlogSize else 0
                for (i <- 0 until total) {
                  val entry = backlog((start + i) % backlogSize)
                  if (entry != null) {
                    println(entry)
                    logLine(entry)
                  }
                }
                println("  --- END BACKLOG ---")
                logLine("  --- END BACKLOG ---")
              }
            } else {
              freezeSamePcCount = 0
            }
            freezePrevPc = pcVal
          }

          // Per-cycle trace after freeze detected (first N cycles)
          if (freezeDetected && freezeTraceCycles < freezeTraceLimit) {
            val memBusy = dut.io.memBusy.toBoolean
            val busyStr = if (memBusy) "MEMBUSY" else "       "
            val instrName = disasmAt(prevPc)
            val mcs = dut.io.memCtrlState.toInt
            val mcsName = memStateName(mcs)
            val rotName = if (rotState < rotNames.length) rotNames(rotState) else s"?$rotState"
            val msg = f"  FZ[$cycle%8d] PC=$pcVal%4d SP=$spVal%5d VP=$vpVal%5d A=0x${aVal}%08X B=0x${bVal}%08X $busyStr MC=$mcsName%-8s rot=$rotName%-8s ${instrName}%-12s"
            println(msg)
            logLine(msg)
            freezeTraceCycles += 1
          }
        }

        if (cycle > 0 && cycle % reportInterval == 0 && !deepRecursionStarted) {
          println(f"  [$cycle%7d cycles]")
        }

        // Track previous PC for instruction disassembly (ir = rom[prevPc])
        prevPc = dut.io.pc.toInt
      }

      // Summary
      println(s"\n  Stack cache summary: spills=$spillCount fills=$fillCount maxSp=$maxSp spDecreases=$spDecreaseCount wrSnooped=$wrSnoopCount")

      if (lineBuffer.nonEmpty) {
        println(lineBuffer.toString)
        logLine(f"[$maxCycles%7d] ${lineBuffer.toString}")
      }

      logLine("")
      logLine("=== Simulation Complete ===")
      logLine(s"UART Output:\n${uartOutput.toString}")
      log.close()

      println(s"\n=== Simulation Complete ($maxCycles cycles) ===")
      println(s"Full output:\n${uartOutput.toString}")
      println(s"Log: $logFilePath")
    }
}

/**
 * Waveform-capture simulation for stack cache VP+0 corruption debugging.
 *
 * Runs with FST waveform enabled but disables capture until the 3rd spill
 * completes, then enables capture for ~5000 cycles covering the corruption
 * at cycle ~5245249.  Stops simulation shortly after.
 *
 * Result: small FST file with full signal visibility around the bug.
 */
object JopStackCacheWaveSim extends App {

  val jopFilePath = "java/apps/JvmTests/DoAll.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 512 * 1024 / 4)

  println(s"Stack cache waveform capture sim")
  println(s"Loaded ROM: ${romData.length}, RAM: ${ramData.length}, main mem: ${mainMemData.length}")

  SimConfig
    .withFstWave
    .addSimulatorFlag("-Wno-SYMRSVDWORD")
    .addSimulatorFlag("--public-flat-rw")
    .addSimulatorFlag("--trace-depth")
    .addSimulatorFlag("99")
    .compile(JopStackCacheTestHarness(romData, ramData, mainMemData, memSize = 512 * 1024))
    .doSim { dut =>
      val rotNames = Array("IDLE", "SPILL_S", "SPILL_W", "FILL_S", "FILL_W", "ZERO_F")

      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      // Start with waveform capture OFF for speed
      disableSimWave()

      var lastRotState = 0
      var spillCount = 0
      var waveEnabled = false
      var waveStartCycle = 0
      val waveDuration = 6000  // capture this many cycles after enabling

      val maxCycles = 5260000  // enough to reach corruption + margin

      for (cycle <- 0 until maxCycles) {
        dut.clockDomain.waitSampling()

        // Track rotation state to count spills
        val rotState = dut.io.scRotState.toInt
        if (rotState != lastRotState) {
          if (rotState == 1) spillCount += 1  // SPILL_START
          val rotName = if (rotState < rotNames.length) rotNames(rotState) else s"?$rotState"
          val prevName = if (lastRotState < rotNames.length) rotNames(lastRotState) else s"?$lastRotState"
          val spVal = dut.io.scSp.toInt
          val vpVal = dut.io.scVp.toInt
          println(f"  [$cycle%8d] ROT: $prevName%-8s -> $rotName%-8s SP=$spVal%5d VP=$vpVal%5d spill#$spillCount")

          // Enable waveform capture when 3rd spill completes (returns to IDLE)
          if (spillCount >= 3 && rotState == 0 && !waveEnabled) {
            println(f"  [$cycle%8d] >>> ENABLING WAVEFORM CAPTURE <<<")
            enableSimWave()
            waveEnabled = true
            waveStartCycle = cycle
          }
          lastRotState = rotState
        }

        // Stop simulation after capturing enough cycles
        if (waveEnabled && (cycle - waveStartCycle) >= waveDuration) {
          println(f"  [$cycle%8d] >>> STOPPING: captured $waveDuration cycles of waveform <<<")
          simSuccess()
        }

        // Progress report
        if (cycle > 0 && cycle % 500000 == 0) {
          println(f"  [$cycle%8d] spills=$spillCount")
        }
      }

      println(s"  Reached maxCycles=$maxCycles without capturing. spills=$spillCount")
    }
}
