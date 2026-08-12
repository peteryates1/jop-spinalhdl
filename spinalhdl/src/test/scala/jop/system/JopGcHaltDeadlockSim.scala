package jop.system

import jop.config._
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.bmb._
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import java.io.PrintWriter

/**
 * Deadlock-diagnosis harness for current-status item 1: the >2-core
 * generational GC hang.
 *
 * This is NOT a pass/fail regression test — it exists to capture the state of
 * the lock unit at the moment the cluster wedges, so the hang can be attributed
 * to a mechanism rather than guessed at. It uses CmpSync (the simpler global
 * lock) because the CmpSync/IHLU bisection already showed the hang is common to
 * both, so whatever is wrong is in the gcHalt protocol itself.
 *
 * WHAT IT HAS ALREADY SHOWN, in order:
 *
 *  1. The original hypothesis — ">= 2 cores assert gcHalt and halt each other"
 *     — is REFUTED. No core has ever been observed asserting gcHalt at a
 *     freeze, in either the broken or the fixed build.
 *  2. Before the GC fix, 4 cores did not wedge at all: core 0 died with an
 *     UNCAUGHT EXCEPTION at ~52M cycles, moments after releasing the
 *     publishers. That is heap corruption from a collector running
 *     concurrently with mutators, not a stop-the-world halt that never
 *     releases.
 *  3. After the fix (monitor taken once at the outermost GC entry; minorGc
 *     stops the world) the exception is gone and the run gets further — but it
 *     then freezes with core 1 holding the CmpSync lock, stalled on the FIRST
 *     microcode word of `goto`, while the others sit mid-`invokevirtual`.
 *
 * (3) is what this version is instrumented for. A core stalls when any term of
 * JopCore.scala:294 is high, so all five are sampled separately — the first
 * version read `cluster.io.halted`, which is the DEBUG halt and always false,
 * and so reported "halted=0/4" through a total freeze.
 */
case class JopGcHaltTestHarness(
  cpuCnt: Int,
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt],
  memSize: Int = 128 * 1024
) extends Component {
  require(cpuCnt >= 2)

  // useCmpSync = true: global lock, so `lockedId`/`state` fully describe who
  // owns the mutex. hasCardTable is required or GC.init disables generational
  // mode and the run proves nothing (the same trap JopIhluGcBramSim fell into).
  val harnessCfg = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = memSize,
      hasCardTable = true, cardTableBudgetBytes = 16 * 1024),
    useCmpSync = true
  )

  val io = new Bundle {
    val pc  = out Vec(UInt(harnessCfg.pcWidth bits), cpuCnt)
    val jpc = out Vec(UInt((harnessCfg.jpcWidth + 1) bits), cpuCnt)
    val halted = out Vec(Bool(), cpuCnt)
    val memBusy = out Vec(Bool(), cpuCnt)
    val uartTxData  = out Bits(8 bits)
    val uartTxValid = out Bool()
    val excFired = out Bool()
  }

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

  val cluster = JopCluster(
    cpuCnt = cpuCnt,
    baseConfig = harnessCfg,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  // The whole point of this harness: the lock/halt protocol state.
  cluster.cmpSync.foreach { cs =>
    cs.state.simPublic()
    cs.lockedId.simPublic()
  }
  for (i <- 0 until cpuCnt) {
    cluster.cores(i).sys.gcHaltReg.simPublic()
    cluster.cores(i).sys.lockReqReg.simPublic()
    // The pipeline stalls on an OR of five terms (JopCore.scala:294). Expose
    // them individually — `cluster.io.halted` is the DEBUG halt
    // (JopCluster.scala:617 wires it from debugHalted), NOT the CmpSync one, so
    // the first version of this probe reported "halted=0/4" while three cores
    // were in fact frozen by the lock unit. Read the term that actually stalls
    // the pipe instead of the one with the convenient name.
    cluster.cores(i).sys.io.halted.simPublic()
    // Hardware exception strobe, per core. `io.excFired` below is core 0 only
    // (JopCluster.scala:631 wires it from cores(0)), and "which core threw" is
    // the whole question here.
    cluster.cores(i).sys.io.exc.simPublic()
    // WHICH exception. Const: 1=SPOV(stack overflow), 2=NP, 3=AB(array bounds),
    // 4=ROLLBACK, 5=MON, 8=DIVZ. SPOV matters more than the others here because
    // `JVMHelp.except()` responds to it with `Native.setSP(Const.STACK_OFF)` —
    // it DISCARDS THE STACK — which would explain a core resuming in the wrong
    // method with the wrong operand values, and `sp` was indeed 55 at the bad
    // store against ~180 a few thousand cycles earlier.
    cluster.cores(i).sys.excTypeReg.simPublic()
    // BYTECODE CACHE FILLS. The first fault is core 1 invoking a method and
    // then running straight through ~450 bytes and off the end into zero-filled
    // cache, nop-sliding until something decoded as an invoke — the SAME call
    // site and target having returned normally 200 cycles earlier. So either
    // the invoke's method pointer was wrong or the fill delivered wrong bytes,
    // and the fill's own parameters distinguish them: `bcRdCaptureReg` is TOS at
    // `bcRd`, packing start = val>>>10 and length = val & 0x3ff. Matching start
    // against the link file's method table says which method was asked for.
    cluster.cores(i).memCtrl.bcRdCaptureReg.simPublic()
    cluster.cores(i).memCtrl.io.memIn.bcRd.simPublic()
    // The EXTENT of the method currently in the bytecode cache. jpc must stay
    // inside [bcCacheStart*4, +bcFillLen*4) modulo the 12-bit jpc space; the
    // cache is circular by design (fill uses `(bcCacheStartReg + bcFillCount)
    // .resized`, fetch truncates to jpcWidth), so wrapping is legal and only
    // leaving the extent is not. This turns "core 1 eventually executed
    // garbage" into a timestamped first event.
    cluster.cores(i).memCtrl.bcCacheStartReg.simPublic()
    cluster.cores(i).memCtrl.bcFillLen.simPublic()
    // Top of stack. A store lands with the ADDRESS from the bytecode operand
    // and the DATA from `io.aout` (BmbMemoryController:626,630), and the two
    // disagreed at the wedge: `iconst_0; putstatic phase` stored 6. Reading A
    // and B at the store separates the two candidate explanations — the core is
    // not running the bytecode the cache shows, or `valueReg` is not capturing
    // the `aout` that belongs to the store.
    cluster.cores(i).pipeline.stack.a.simPublic()
    cluster.cores(i).pipeline.stack.b.simPublic()
    cluster.cores(i).pipeline.stack.sp.simPublic()
    cluster.cores(i).pipeline.io.hwBusy.simPublic()
    cluster.cores(i).extBusy.simPublic()
    // The bytecode cache. Dumping the opcodes around jpc is the only thing that
    // says WHICH loop a wedged core is in: the microcode PC only names the
    // bytecode handler, and reading it across 2M-cycle samples was misleading
    // enough to produce two wrong readings already.
    cluster.cores(i).pipeline.bcfetch.jbcRamWord.simPublic()
  }

  if (cluster.devicePins.contains("uart")) cluster.devicePin[Bool]("uart", "rxd") := True

  val memWords = memSize / 4
  val ram = BmbOnChipRam(p = cluster.bmbParameter, size = memSize, hexInit = null)
  // Read the heap from the simulator so the handle lists can be walked WITHOUT
  // touching the runtime. Instrumenting GC.java to find the corruption does not
  // work: adding a per-iteration counter to the list walks shifted the code
  // size, which shifted the heap start, which changed the allocation pattern
  // (minors after tenuring went 196 -> 198) and the freeze stopped happening —
  // five clean runs. Verilator is deterministic, so that is not luck, it is a
  // different binary. Only an observer outside the binary can look at the
  // failing one.
  ram.ram.simPublic()
  // WRITE WATCHPOINT. Every store the cluster makes passes through this one
  // command channel — it is the arbiter output (JopCluster: `io.bmb <>
  // arbiter.io.output`), the same snoop point the cluster card table uses. It
  // carries `source`, which is the arbiter input index and therefore the core
  // id for source < cpuCnt, so a store can be attributed without instrumenting
  // anything in the binary.
  //
  // This is the tool for "who wrote that": chasing wild control flow backwards
  // from the exception it eventually causes has already produced two retracted
  // readings. A watchpoint on a known-good static goes the other way — from the
  // first bad value to the instant and the core that produced it.
  ram.io.bus.cmd.valid.simPublic()
  ram.io.bus.cmd.ready.simPublic()
  ram.io.bus.cmd.fragment.address.simPublic()
  ram.io.bus.cmd.fragment.opcode.simPublic()
  ram.io.bus.cmd.fragment.data.simPublic()
  ram.io.bus.cmd.fragment.mask.simPublic()
  val hasBusSource = cluster.bmbParameter.access.sourceWidth > 0
  if (hasBusSource) ram.io.bus.cmd.fragment.source.simPublic()
  // The RESPONSE side too. The open question is whether the wake-up read that
  // yields a null method pointer returns 0 ON THE BUS, or returns the right
  // value and the core loses it. Those are different bugs in different places,
  // and only the response channel separates them.
  ram.io.bus.rsp.valid.simPublic()
  ram.io.bus.rsp.ready.simPublic()
  ram.io.bus.rsp.fragment.data.simPublic()
  ram.io.bus.rsp.fragment.opcode.simPublic()
  if (hasBusSource) ram.io.bus.rsp.fragment.source.simPublic()

  val initData = mainMemInit.take(memWords).padTo(memWords, BigInt(0))
  ram.ram.init(initData.map(v => B(v, 32 bits)))
  ram.io.bus << cluster.io.bmb

  for (i <- 0 until cpuCnt) {
    io.pc(i)      := cluster.io.pc(i)
    io.jpc(i)     := cluster.io.jpc(i)
    io.halted(i)  := cluster.io.halted(i)
    io.memBusy(i) := cluster.io.memBusy(i)
  }
  io.uartTxData  := cluster.io.uartTxData
  io.uartTxValid := cluster.io.uartTxValid
  io.excFired    := cluster.io.debugExc
}

object JopGcHaltDeadlockSim extends App {
  val cpuCnt = if (args.length > 0) args(0).toInt else 4

  // argv: <cpuCnt> [detailFromCycle] [seed] [app.jop] [maxCycles]
  //
  // The app is a parameter because the fault is now known to be at CORE
  // WAKE-UP, not in the workload: core 3 fails at jpc=0x0003, in its first few
  // bytecodes, immediately after being released. SmpGcTest only reaches that
  // point at 56M cycles because it churns two tenuring rounds first, so it
  // costs ~45 minutes to observe something that happens within a few hundred
  // cycles of the release. Any app that starts N cores reaches the same moment
  // in ~250k cycles -- e.g. java/apps/Small/NCoreHelloWorld.jop.
  val jopFilePath = if (args.length > 3) args(3) else "java/apps/SmpGcTest/SmpGcTest.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/gchalt_deadlock_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  // argv: <cpuCnt> [detailFromCycle] [seed]
  val simSeed = if (args.length > 2) args(2).toInt else 70704150

  println(s"CPU count: $cpuCnt (CmpSync global lock)")
  println(s"App: $jopFilePath")
  println(s"Sim seed: $simSeed (PINNED — see the note at doSim)")

  SimConfig
    .addSimulatorFlag("--x-initial 0")
    .compile(JopGcHaltTestHarness(cpuCnt, romData, ramData, mainMemData))
    // PIN THE SEED. `doSim` without one picks a fresh seed every run, and this
    // harness had been running with 748489979, 617838352, 370588204, 70704150
    // on four consecutive invocations of the same binary — so "Verilator is
    // deterministic" was only ever true for a FIXED seed. It is not a harmless
    // detail here: with the seed floating, the failure moves (in one run core 1
    // dies mid-workload at 56.18M, in another core 3 dies at wake-up at 56.06M
    // and never executes main() at all), and any conclusion of the form "I
    // changed X and the failure went away" has an uncontrolled variable in it.
    // See the note in current-status item 1 about five clean runs after
    // instrumenting GC.java — that inference was drawn without this control.
    .doSim(seed = simSeed) { dut =>
      val log = new PrintWriter(logFilePath)
      def logLine(s: String): Unit = { log.println(s); log.flush() }

      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      val uartOutput = new StringBuilder
      // The freeze lands by ~57M; 200M only bought 143M cycles of the same
      // frozen snapshot at ~55 minutes a run.
      val maxCycles = if (args.length > 4) args(4).toInt else 75000000
      // A wedge is "nothing observable changed for this long". SmpGcTest's
      // minor GCs are long but they still move PCs, so PC-stability is the
      // discriminator, not UART silence alone.
      val stallWindow = 200000
      // Cycle from which the per-cycle sampling below is switched on. See the
      // note at its use. Set to 0 to instrument the whole run.
      val DETAIL_FROM = if (args.length > 1) args(1).toInt else 55000000

      var cycle = 0
      var done = false
      var deadlockCycle = -1

      val lastPc = Array.fill(cpuCnt)(-1)
      val pcStuckFor = Array.fill(cpuCnt)(0)
      var pcStableFor = 0
      // Report each core the first time it goes quiet for a long stretch, so a
      // partial freeze is visible even if one core keeps moving forever.
      val reportedStuck = Array.fill(cpuCnt)(false)

      // Static-field addresses are READ FROM THE LINK FILE, never hardcoded.
      // They shift whenever the app is relinked: fixing Startup.exit() grew the
      // runtime and moved SmpGcTest.phase from 287 to 292, so a hardcoded probe
      // reported phase=0 with null arrays and looked exactly like catastrophic
      // heap corruption. It was reading the wrong words.
      val linkPath = jopFilePath + ".link.txt"
      val staticAddr: Map[String, Int] = {
        val re = """static\s+(\S+?)([IJFDZBCS]|\[[IJFDZBCSL].*)\s+(\d+)""".r
        scala.io.Source.fromFile(linkPath).getLines().collect {
          case re(name, _, addr) => name -> addr.toInt
        }.toMap
      }
      def sAddr(name: String): Int = staticAddr.getOrElse(name, {
        println(s"WARNING: static '$name' not found in $linkPath"); -1
      })
      // Reverse map, so a watched address reports the field it belongs to
      // rather than a bare number. Built from the same parse, so it moves with
      // the link file too.
      val staticName: Map[Int, String] = staticAddr.map(_.swap)

      val ADDR_FREE_LIST  = sAddr("com.jopdesign.sys.GC.freeList")
      val ADDR_USE_LIST   = sAddr("com.jopdesign.sys.GC.useList")
      val ADDR_YOUNG_LIST = sAddr("com.jopdesign.sys.GC.youngList")
      val OFF_NEXT = 4

      def rd(word: Int): Int =
        if (word < 0 || word >= dut.memWords) 0
        else dut.ram.ram.getBigInt(word).toLong.toInt

      /** Walk an OFF_NEXT chain, reporting its length or where it cycles.
        * Floyd is unnecessary here — the handle count bounds the walk, so a walk
        * that exceeds it is following a cycle by definition. */
      def walkList(name: String, headAddr: Int): String = {
        val head = rd(headAddr)
        if (head == 0) return f"    $name%-10s head=0 (empty)"
        val seen = scala.collection.mutable.LinkedHashSet[Int]()
        var ref = head
        var steps = 0
        val limit = 70000            // > MAX_HANDLES, so any real list terminates
        while (ref != 0 && steps < limit) {
          if (seen.contains(ref)) {
            val cyc = seen.toList.dropWhile(_ != ref)
            return f"    $name%-10s head=0x$head%06x  *** CYCLE after $steps steps at " +
                   f"0x$ref%06x, loop length ${cyc.length} *** " +
                   cyc.take(6).map(a => f"0x$a%06x").mkString(" -> ")
          }
          seen += ref
          ref = rd(ref + OFF_NEXT)
          steps += 1
        }
        if (steps >= limit) f"    $name%-10s head=0x$head%06x  *** RUNAWAY, >$limit steps ***"
        else f"    $name%-10s head=0x$head%06x  length=$steps (terminates)"
      }

      def heapLists(): String =
        "  handle lists (read from RAM, runtime untouched):\n" +
        walkList("youngList", ADDR_YOUNG_LIST) + "\n" +
        walkList("useList", ADDR_USE_LIST) + "\n" +
        walkList("freeList", ADDR_FREE_LIST)

      // SmpGcTest's own statics, same link file. Reading these says WHERE each
      // core is in the test's state machine, which the microcode PC cannot:
      // a frozen core that is still incrementing liveTick[] is running the
      // publisher loop, not stuck in the collector.
      val ADDR_PHASE        = sAddr("test.SmpGcTest.phase")
      val ADDR_PUB_ROUND    = sAddr("test.SmpGcTest.publishRound")
      val ADDR_HOLDERS      = sAddr("test.SmpGcTest.holders")
      val ADDR_PUBROUND_ARR = sAddr("test.SmpGcTest.pubRound")
      val ADDR_LIVETICK_ARR = sAddr("test.SmpGcTest.liveTick")

      /** JOP handle: H[0] = data pointer, H[1] = array length. */
      def arrayElem(handleAddr: Int, idx: Int): Int = {
        val h = rd(handleAddr)
        if (h == 0) 0 else rd(rd(h) + idx)
      }
      def arrayLen(handleAddr: Int): Int = {
        val h = rd(handleAddr)
        if (h == 0) 0 else rd(h + 1)
      }

      // GC internals, same link file. These say which PHASE the stuck core is
      // in — grayList != GREY_END means a mark drain, gcPhase != 0 means the
      // incremental collector, and the nursery/alloc pointers show whether a
      // minor GC has just finished.
      val ADDR_HANDLE_CNT = sAddr("com.jopdesign.sys.GC.handle_cnt")
      val ADDR_TO_SPACE   = sAddr("com.jopdesign.sys.GC.toSpace")
      val ADDR_COPY_PTR   = sAddr("com.jopdesign.sys.GC.copyPtr")
      val ADDR_ALLOC_PTR  = sAddr("com.jopdesign.sys.GC.allocPtr")
      val ADDR_GRAY_LIST  = sAddr("com.jopdesign.sys.GC.grayList")
      val ADDR_NUR_BASE   = sAddr("com.jopdesign.sys.GC.nurseryBase")
      val ADDR_NUR_TOP    = sAddr("com.jopdesign.sys.GC.nurseryTop")
      val ADDR_NUR_ALLOC  = sAddr("com.jopdesign.sys.GC.nurseryAllocPtr")
      val ADDR_GC_PHASE   = sAddr("com.jopdesign.sys.GC.gcPhase")
      val ADDR_MINOR_CNT  = sAddr("com.jopdesign.sys.GC.gcMinorCount")
      val ADDR_YOUNG_OBJ  = sAddr("com.jopdesign.sys.GC.youngObjects")
      val ADDR_COMPACT_LIST = sAddr("com.jopdesign.sys.GC.compactList")
      val ADDR_NEW_USE_LIST = sAddr("com.jopdesign.sys.GC.newUseList")

      def gcState(): String =
        f"  GC: gcPhase=${rd(ADDR_GC_PHASE)} grayList=0x${rd(ADDR_GRAY_LIST)}%06x " +
        f"minors=${rd(ADDR_MINOR_CNT)} youngObjects=${rd(ADDR_YOUNG_OBJ)} toSpace=${rd(ADDR_TO_SPACE)}\n" +
        f"      copyPtr=0x${rd(ADDR_COPY_PTR)}%06x allocPtr=0x${rd(ADDR_ALLOC_PTR)}%06x " +
        f"nursery=[0x${rd(ADDR_NUR_BASE)}%06x..0x${rd(ADDR_NUR_TOP)}%06x] alloc=0x${rd(ADDR_NUR_ALLOC)}%06x\n" +
        f"      compactList=0x${rd(ADDR_COMPACT_LIST)}%06x newUseList=0x${rd(ADDR_NEW_USE_LIST)}%06x " +
        f"handle_cnt=${rd(ADDR_HANDLE_CNT)}"

      def appState(): String = {
        val n = arrayLen(ADDR_LIVETICK_ARR).max(0).min(16)
        val ticks = (0 until n).map(i => arrayElem(ADDR_LIVETICK_ARR, i)).mkString(",")
        val rounds = (0 until n).map(i => arrayElem(ADDR_PUBROUND_ARR, i)).mkString(",")
        f"  SmpGcTest: phase=${rd(ADDR_PHASE)} publishRound=${rd(ADDR_PUB_ROUND)} " +
        f"pubRound=[$rounds] liveTick=[$ticks]"
      }

      // Enough of the JVM opcode set to read a loop, plus JOP's jopsys_*
      // extensions (JopSim.java: 206 lock, 207 unlock, 209 rd, 210 wr).
      val opName = Map(
        0x00 -> "nop", 0x01 -> "aconst_null",
        0x02 -> "iconst_m1", 0x03 -> "iconst_0", 0x04 -> "iconst_1",
        0x05 -> "iconst_2", 0x06 -> "iconst_3", 0x07 -> "iconst_4", 0x08 -> "iconst_5",
        0x10 -> "bipush", 0x11 -> "sipush", 0x12 -> "ldc", 0x13 -> "ldc_w",
        0x15 -> "iload", 0x19 -> "aload",
        0x1a -> "iload_0", 0x1b -> "iload_1", 0x1c -> "iload_2", 0x1d -> "iload_3",
        0x2a -> "aload_0", 0x2b -> "aload_1", 0x2c -> "aload_2", 0x2d -> "aload_3",
        0x2e -> "iaload", 0x32 -> "aaload",
        0x36 -> "istore", 0x3a -> "astore",
        0x3b -> "istore_0", 0x3c -> "istore_1", 0x3d -> "istore_2", 0x3e -> "istore_3",
        0x4b -> "astore_0", 0x4c -> "astore_1", 0x4d -> "astore_2", 0x4e -> "astore_3",
        0x4f -> "iastore", 0x53 -> "aastore",
        0x57 -> "pop", 0x59 -> "dup",
        0x60 -> "iadd", 0x64 -> "isub", 0x68 -> "imul", 0x6c -> "idiv",
        0x7e -> "iand", 0x80 -> "ior", 0x82 -> "ixor",
        0x78 -> "ishl", 0x7a -> "ishr", 0x7c -> "iushr",
        0x84 -> "iinc", 0x91 -> "i2b", 0x92 -> "i2c",
        0x99 -> "ifeq", 0x9a -> "ifne", 0x9b -> "iflt", 0x9c -> "ifge",
        0x9d -> "ifgt", 0x9e -> "ifle",
        0x9f -> "if_icmpeq", 0xa0 -> "if_icmpne", 0xa1 -> "if_icmplt",
        0xa2 -> "if_icmpge", 0xa3 -> "if_icmpgt", 0xa4 -> "if_icmple",
        0xa5 -> "if_acmpeq", 0xa6 -> "if_acmpne",
        0xa7 -> "goto", 0xac -> "ireturn", 0xb0 -> "areturn", 0xb1 -> "return",
        0xb2 -> "getstatic", 0xb3 -> "putstatic", 0xb4 -> "getfield", 0xb5 -> "putfield",
        0xb6 -> "invokevirtual", 0xb7 -> "invokespecial", 0xb8 -> "invokestatic",
        0xbb -> "new", 0xbc -> "newarray", 0xbd -> "anewarray", 0xbe -> "arraylength",
        0xbf -> "athrow", 0xc0 -> "checkcast", 0xc2 -> "monitorenter", 0xc3 -> "monitorexit",
        206 -> "jopsys_lock", 207 -> "jopsys_unlock", 209 -> "jopsys_rd", 210 -> "jopsys_wr"
      )

      /** Read one byte of the bytecode cache. Little-endian byte select:
        * byte 0 is bits 7:0 (BytecodeFetchStage). */
      def bcByte(core: Int, jpc: Int): Int = {
        val mem = dut.cluster.cores(core).pipeline.bcfetch.jbcRamWord
        val words = dut.cluster.cores(core).pipeline.bcfetch.jbcWordDepth
        // ALIAS rather than give up. jpc is jpcWidth+1 bits (12) but the cache
        // covers only jpcWidth (11 => 2KB), so a jpc past the end silently wraps
        // into the low 2KB and the core executes whatever is there. Returning -1
        // for those printed an EMPTY dump at exactly the moment worth seeing —
        // the out-of-range jpc IS the event.
        if (jpc < 0) return -1
        val w = (jpc >> 2) % words
        val word = mem.getBigInt(w).toLong
        ((word >> (8 * (jpc & 3))) & 0xFF).toInt
      }

      /** True when this jpc is past the end of the cache and therefore aliased. */
      def bcAliased(core: Int, jpc: Int): Boolean =
        (jpc >> 2) >= dut.cluster.cores(core).pipeline.bcfetch.jbcWordDepth

      /** Opcodes around the current jpc — this is what names the loop. */
      def bcDump(core: Int, jpc: Int, back: Int = 12, fwd: Int = 12): String = {
        val sb = new StringBuilder
        val depth = dut.cluster.cores(core).pipeline.bcfetch.jbcWordDepth
        sb.append(f"  core $core bytecode cache around jpc=0x$jpc%04x " +
                  f"(cache ${depth * 4} bytes, jpc 0x000..0x${depth * 4 - 1}%03x)")
        if (bcAliased(core, jpc))
          sb.append(f" *** jpc PAST END OF CACHE — fetch aliases to 0x${jpc % (depth * 4)}%04x ***")
        sb.append(":\n")
        var a = (jpc - back).max(0)
        val end = jpc + fwd
        while (a <= end) {
          val b = bcByte(core, a)
          if (b >= 0) {
            val marker = if (a == jpc) " <== jpc" else ""
            val alias = if (bcAliased(core, a)) f"  [aliased from 0x$a%04x]" else ""
            sb.append(f"      0x${a % (depth * 4)}%04x: ${b}%3d 0x$b%02x  " +
                      f"${opName.getOrElse(b, "?")}%-14s$marker$alias\n")
          }
          a += 1
        }
        sb.toString
      }

      // ------------------------------------------------------- write watchpoint
      //
      // `phase` reads 6 at the wedge and the application only ever assigns it
      // 0..3, so something writes where it should not. Rather than trace wild
      // execution backwards from the exception it eventually raises, watch the
      // word itself and report the first store that puts it out of range,
      // naming the core and the bytecode it was running.
      //
      // The window is the whole SmpGcTest static block plus a few words either
      // side: an off-by-N store into a neighbouring field is exactly the shape
      // being looked for, and the neighbours are cheap to include.
      val WATCH_LO = sAddr("test.SmpGcTest.HOLDERS") - 2      // 288
      val WATCH_HI = sAddr("test.SmpGcTest.verified") + 2     // 301
      val WATCH_ARR_LO = sAddr("test.SmpGcTest.holders") - 2  // 373
      val WATCH_ARR_HI = sAddr("test.SmpGcTest.liveTick") + 2 // 379

      // The static watch is SmpGcTest-specific. With another app those names are
      // absent, sAddr returns -1, and the windows would collapse onto words 0-1
      // — the special-pointer table — reporting nonsense. Switch it off instead.
      val watchEnabled = sAddr("test.SmpGcTest.phase") > 0
      if (!watchEnabled)
        println(s"NOTE: $linkPath has no SmpGcTest statics — static watchpoint disabled. " +
                "The null-fill and exception triggers still apply.")

      // GC STATIC WATCH — independent of the SmpGcTest window above, because it
      // is the runtime's own state and applies whatever app is loaded.
      //
      // `handle_cnt` is assigned exactly once, in GC.init (`full_heap_size >> 4`,
      // capped at MAX_HANDLES), and never reassigned. It was observed going
      // 1117 -> 4 mid-run, which is fatal in a quiet way: every handle-list walk
      // hoists it as its cycle-guard limit, so a bogus small value makes
      // gcListOverrun TRUNCATE valid chains — the collector then drops live
      // objects and blames a "cyclic or corrupt list".
      val gcWatchEnabled = ADDR_HANDLE_CNT > 0
      val GC_WATCH_LO = ADDR_HANDLE_CNT - 2
      val GC_WATCH_HI = ADDR_HANDLE_CNT + 2

      def watched(word: Int): Boolean = (watchEnabled && (
        (word >= WATCH_LO && word <= WATCH_HI) ||
        (word >= WATCH_ARR_LO && word <= WATCH_ARR_HI))) ||
        (gcWatchEnabled && word >= GC_WATCH_LO && word <= GC_WATCH_HI)

      /** The value each watched field is allowed to take. `None` = anything.
        * A violation is the event this probe exists to catch. */
      def violation(word: Int, v: Int): Option[String] = {
        if (word == sAddr("test.SmpGcTest.phase") && (v < 0 || v > 3))
          Some(s"phase := $v — the application only ever assigns 0..3")
        else if (word == sAddr("test.SmpGcTest.publishRound") && (v < 0 || v > 7))
          Some(s"publishRound := $v — rounds are 0..7")
        else if (word == sAddr("test.SmpGcTest.HOLDERS") && v != 24)
          Some(s"HOLDERS := $v — static final, must be 24")
        else if (word == sAddr("test.SmpGcTest.MAGIC_BASE") && v != 0x5A5A0000)
          Some(f"MAGIC_BASE := 0x$v%08x — static final, must be 0x5a5a0000")
        else if (word == sAddr("test.SmpGcTest.cpuCnt") && v != cpuCnt)
          Some(s"cpuCnt := $v — must be $cpuCnt")
        else if (word == sAddr("test.SmpGcTest.publishers") && v != cpuCnt - 1)
          Some(s"publishers := $v — must be ${cpuCnt - 1}")
        // Bounds, not an exact value: handle_cnt depends on heap size, so pin
        // only what cannot be legitimate. Anything under 64 makes every
        // list-walk guard fire on a valid chain.
        else if (word == ADDR_HANDLE_CNT && (v < 64 || v > 65536))
          Some(s"handle_cnt := $v — set once in GC.init and never reassigned; " +
               "a value this small makes every handle-list walk guard truncate")
        else None
      }

      // Per-core jpc history. The store reaches the bus a few cycles after the
      // putstatic issues, so the jpc sampled at the write has already moved on;
      // the trailing history is what names the method. Recorded only on change,
      // so a spin loop does not flush it.
      // Sized to reach BACK PAST the derailment, not just to name the current
      // method. The 64-entry version showed core 0 arriving at main()'s first
      // bytecode and said nothing about how it got there; at roughly one entry
      // per two cycles this covers ~30k cycles, which spans the invoke chain
      // before it.
      val HIST = 16384
      val histJpc = Array.fill(cpuCnt, HIST)(-1)
      val histPc  = Array.fill(cpuCnt, HIST)(-1)
      val histCyc = Array.fill(cpuCnt, HIST)(-1)
      // TOS and SP alongside, sampled on the same jpc-change edge rather than
      // every cycle, so the cost is a read per recorded entry and not per clock.
      // `iconst_0` must leave A = 0; if the trace shows A = 6 there, the core is
      // not executing the bytecode the cache shows and the search moves to the
      // fetch path rather than to the store path.
      val histA   = Array.fill(cpuCnt, HIST)(0)
      val histSp  = Array.fill(cpuCnt, HIST)(0)
      val histIdx = Array.fill(cpuCnt)(0)
      val lastJpc = Array.fill(cpuCnt)(-1)
      val escapeSeen = Array.fill(cpuCnt)(0)
      val poisonSeen = Array.fill(cpuCnt)(0)

      /** Ordered oldest-first view of the ring. */
      def histOrdered(core: Int): Seq[(Int, Int, Int, Int, Int)] =
        (0 until HIST).map(k => (histIdx(core) + k) % HIST)
          .filter(i => histCyc(core)(i) >= 0)
          .map(i => (histCyc(core)(i), histPc(core)(i), histJpc(core)(i),
                     histA(core)(i), histSp(core)(i)))

      /** Printing 16k lines is useless. Two views instead:
        *
        *  - CONTROL TRANSFERS: entries where jpc did not simply advance, which
        *    is every branch, invoke and return. This is the call chain, and it
        *    is what says how a core reached a method it had no business being
        *    in. A large cycle gap on such an entry is a bytecode cache fill,
        *    i.e. an invoke or a return into a method that had been evicted.
        *  - the last 80 entries in full, for the instruction-level detail.
        */
      def historyOf(core: Int): String = {
        val h = histOrdered(core)
        val sb = new StringBuilder
        sb.append(f"  core $core%d control transfers (${h.length} samples held, oldest first):\n")
        for (k <- 1 until h.length) {
          val (c0, _, j0, _, _) = h(k - 1)
          val (c1, p1, j1, _, s1) = h(k)
          val d = j1 - j0
          if (d < 0 || d > 8) {
            val gap = c1 - c0
            val kind = if (gap > 40) "invoke/return + cache fill"
                       else if (d < 0) "backward branch" else "forward branch"
            sb.append(f"      cycle $c1%9d  0x$j0%04x -> 0x$j1%04x  (gap ${gap}%4d, $kind)  " +
                      f"pc=0x$p1%04x sp=$s1%d\n")
          }
        }
        sb.append(f"  core $core%d last 80 samples (A = top of stack at the jpc change):\n")
        for ((c, p, j, a, s) <- h.takeRight(80))
          sb.append(f"      cycle $c%9d  pc=0x$p%04x jpc=0x$j%04x  A=0x$a%08x ($a) sp=$s%d\n")
        sb.toString
      }

      var writeLog = 0
      val WRITE_LOG_MAX = 4000
      var anomalyCycle = -1

      // Hardware exception strobes, every core, whole run. The ring buffer can
      // only reach back so far; this is the cheap signal that covers everything
      // before it. SmpGcTest throws nothing, so ANY exception here is the fault
      // — and `f_athrow` resumes by faking a return frame and calling
      // Native.setSP(), which is a mechanism that can land a core in an
      // arbitrary method with an arbitrary stack. That is what the wedge looks
      // like from the other end, so it is worth knowing whether one fired.
      // ---------------------------------------------------- HALT LEAK DETECTOR
      //
      // The question the hardware could not answer without changing the thing it
      // was measuring: while one core asserts gcHaltReg for a stop-the-world,
      // does any OTHER core keep executing? If so the collector moves objects
      // and rewrites handles underneath a running mutator, which accounts for
      // both observed faults — a lost cross-generation reference and a
      // wild-pointer crash — with one mechanism.
      //
      // This is pure observation of signals already sampled, so it perturbs
      // nothing. A core advancing here is not automatically a bug: BOTH lock
      // units deliberately exempt the current lock owner from gcHalt
      // (CmpSync.scala:141-147), so the owner is *designed* to keep running.
      // That exemption is itself a candidate root cause, so record ownership
      // alongside the advance rather than filtering it out.
      var haltActive = false
      var haltStart = 0
      var haltAsserter = -1
      val advDuringHalt = Array.fill(cpuCnt)(0)
      val ownerDuringHalt = Array.fill(cpuCnt)(false)
      val lastPcH = Array.fill(cpuCnt)(-1)
      var haltWindows = 0
      var leakWindows = 0
      var haltLogged = 0

      val lastExc = Array.fill(cpuCnt)(false)
      var excCount = 0
      val excName = Map(0 -> "none", 1 -> "SPOV(stack overflow)", 2 -> "NP(null pointer)",
                        3 -> "AB(array bounds)", 4 -> "ROLLBACK", 5 -> "MON", 8 -> "DIVZ")
      // The FIRST exception is the origin of the whole failure — the storm runs
      // from ~56.18M and core 0 only derails at 56.61M, 437k cycles later — so
      // dump everything there rather than at the consequence.
      var fullExcDumps = 0

      // Method table, so a fill's start address becomes a method name. Same
      // link file, same mechanical rule as the statics — never hardcoded.
      val methodTable: Vector[(Int, String)] = {
        val re = """bytecode\s+(\S+)\s+(\d+)""".r
        scala.io.Source.fromFile(linkPath).getLines().collect {
          case re(name, addr) => (addr.toInt, name)
        }.toVector.sortBy(_._1)
      }
      def methodAt(word: Int): String = {
        val before = methodTable.takeWhile(_._1 <= word)
        if (before.isEmpty) "(before first method)"
        else {
          val (start, name) = before.last
          if (start == word) name else f"$name +${word - start}"
        }
      }

      // Ring of recent fills per core.
      val FILLS = 48
      val fillCyc = Array.fill(cpuCnt, FILLS)(-1)
      val fillVal = Array.fill(cpuCnt, FILLS)(0)
      val fillIdx = Array.fill(cpuCnt)(0)
      val lastBcRd = Array.fill(cpuCnt)(false)
      val fillPend = Array.fill(cpuCnt)(-1)
      var nullFillDumps = 0
      var fillLogged = 0
      var pendingNullDump = -1

      // Every BMB transaction, command and response, in a ring. The wake-up
      // sequence for a non-zero core is only three memory reads
      // (Startup.boot(): rdMem(1), rdMem(val+3), then the invoke's own read of
      // the method struct), so a couple of hundred entries covers it with room
      // to spare. Recording BOTH sides is the point: if a read returns 0 on the
      // bus the fault is in memory/arbitration, and if it returns the right
      // value the fault is in the core that received it.
      val BMB = 512
      val bmbCyc  = Array.fill(BMB)(-1)
      val bmbKind = Array.fill(BMB)(0)      // 0 = read cmd, 1 = write cmd, 2 = rsp
      val bmbSrc  = Array.fill(BMB)(-1)
      val bmbAddr = Array.fill(BMB)(0)
      val bmbData = Array.fill(BMB)(0)
      var bmbIdx  = 0
      // Pending read addresses per source, so a response can be paired with its
      // command and checked against what the RAM actually holds. That check is
      // the whole point: it says whether a read that yields 0 was given 0 BY
      // MEMORY (so the address was wrong, and the fault is upstream in whatever
      // computed it) or whether memory holds something else and the bus lost it
      // (so the fault is in the arbiter/controller). BMB responses are in order
      // per source, so a FIFO per source is sufficient.
      val pendingRd = Array.fill(16)(scala.collection.mutable.Queue[Int]())
      val bmbWant = Array.fill(BMB)(0)      // RAM content at the read address
      val bmbBad  = Array.fill(BMB)(false)  // response disagreed with RAM
      var mismatches = 0

      def bmbLog(): String = {
        val sb = new StringBuilder
        sb.append("  BMB transactions at the arbiter output (word addresses):\n")
        for (k <- 0 until BMB) {
          val i = (bmbIdx + k) % BMB
          if (bmbCyc(i) >= 0) {
            val what = bmbKind(i) match {
              case 0 => f"READ  word=${bmbAddr(i)}%7d"
              case 1 => f"WRITE word=${bmbAddr(i)}%7d data=0x${bmbData(i)}%08x"
              case _ => f"  rsp                data=0x${bmbData(i)}%08x (${bmbData(i)}%d)" +
                        (if (bmbAddr(i) != 0) "  *** ERROR RESPONSE ***" else "") +
                        (if (bmbBad(i)) f"  *** BUS DISAGREES WITH RAM, which holds " +
                                        f"0x${bmbWant(i)}%08x ***" else "  (matches RAM)")
            }
            sb.append(f"      cycle ${bmbCyc(i)}%9d  src=${bmbSrc(i)}%2d  $what\n")
          }
        }
        sb.toString
      }

      def fillsOf(core: Int): String = {
        val sb = new StringBuilder
        sb.append(f"  core $core%d recent bytecode cache fills (start/len from TOS at bcRd):\n")
        for (k <- 0 until FILLS) {
          val i = (fillIdx(core) + k) % FILLS
          if (fillCyc(core)(i) >= 0 && fillVal(core)(i) != -1) {
            val v = fillVal(core)(i)
            val start = v >>> 10
            val len = v & 0x3ff
            sb.append(f"      cycle ${fillCyc(core)(i)}%9d  start=$start%6d len=$len%4d  " +
                      f"raw=0x$v%08x  ${methodAt(start)}\n")
          }
        }
        sb.toString
      }

      def snapshot(tag: String): String = {
        val sb = new StringBuilder
        sb.append(f"--- $tag at cycle $cycle%d ---\n")
        val st = dut.cluster.cmpSync.map(_.state.toEnum.toString).getOrElse("n/a")
        val owner = dut.cluster.cmpSync.map(_.lockedId.toInt.toString).getOrElse("n/a")
        sb.append(s"  CmpSync: state=$st lockedId=$owner\n")
        for (i <- 0 until cpuCnt) {
          val c  = dut.cluster.cores(i)
          val h  = c.sys.io.halted.toBoolean          // CmpSync/IHLU halt
          val mb = dut.io.memBusy(i).toBoolean        // memCtrl busy
          val hw = c.pipeline.io.hwBusy.toBoolean     // compute unit busy
          val eb = c.extBusy.toBoolean                // I/O device busy
          val gh = c.sys.gcHaltReg.toBoolean
          val rq = c.sys.lockReqReg.toBoolean
          sb.append(f"  core $i%d: pc=${dut.io.pc(i).toInt}%04x jpc=${dut.io.jpc(i).toInt}%04x " +
                    f"syncHalt=$h%-5s memBusy=$mb%-5s hwBusy=$hw%-5s extBusy=$eb%-5s " +
                    f"gcHalt=$gh%-5s lockReq=$rq%-5s\n")
        }
        val nHalt = (0 until cpuCnt).count(dut.cluster.cores(_).sys.io.halted.toBoolean)
        val nGcHalt = (0 until cpuCnt).count(dut.cluster.cores(_).sys.gcHaltReg.toBoolean)
        sb.append(s"  => halted=$nHalt/$cpuCnt  gcHalt asserted by $nGcHalt core(s)\n")
        // Only on a wedge: walking 3 lists costs real sim time, and the periodic
        // progress snapshots fire every 2M cycles.
        // appState is a handful of RAM reads, so include it in every snapshot —
        // comparing consecutive ones shows whether a "frozen" core is actually
        // still making progress. The list walk is 1000+ reads, so wedge only.
        sb.append(appState() + "\n" + gcState() + "\n")
        if (tag.startsWith("WEDGED") || tag.startsWith("CORE") || tag.startsWith("TERMINAL")) {
          sb.append(heapLists() + "\n")
          // Dump the running core(s) — the halted ones are stopped wherever the
          // lock caught them and say nothing about the cause.
          for (i <- 0 until cpuCnt if !dut.cluster.cores(i).sys.io.halted.toBoolean)
            sb.append(bcDump(i, dut.io.jpc(i).toInt))
        }
        sb.toString
      }

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        if (dut.io.uartTxValid.toBoolean) {
          val c = dut.io.uartTxData.toInt
          val ch = if (c >= 32 && c < 127) c.toChar else '.'
          uartOutput.append(ch)
          print(ch)
        }

        // Record jpc transitions before looking at the bus, so the history is
        // current when a watched store fires on this same cycle.
        if (cycle >= DETAIL_FROM) for (i <- 0 until cpuCnt) {
          val j = dut.io.jpc(i).toInt
          if (j != lastJpc(i)) {
            lastJpc(i) = j
            val k = histIdx(i)
            histJpc(i)(k) = j
            histPc(i)(k)  = dut.io.pc(i).toInt
            histCyc(i)(k) = cycle
            histA(i)(k)   = dut.cluster.cores(i).pipeline.stack.a.toLong.toInt
            histSp(i)(k)  = dut.cluster.cores(i).pipeline.stack.sp.toInt
            histIdx(i) = (k + 1) % HIST

            // POISON READ. 0x12345678 is the fill pattern of the ENTIRE stack RAM
            // init (asm/generated/mem_ram.dat, all 256 entries), so seeing it at
            // the top of stack means a slot that was never written has been read.
            // That is the suspected first cause of the null method pointer.
            //
            // CAVEAT, and why this prints context rather than just asserting: the
            // linked image contains exactly ONE constant with this value (an
            // Integer in the constant pool). If the dump below shows an `ldc`,
            // this is that constant and not a poison read — discount it and look
            // for the next.
            if (poisonSeen(i) < 3 && histA(i)(k) == 0x12345678) {
              poisonSeen(i) += 1
              val mStart = dut.cluster.cores(i).memCtrl.bcCacheStartReg.toInt * 4
              val mLen   = dut.cluster.cores(i).memCtrl.bcFillLen.toInt * 4
              println(f"\n*** POISON READ *** core $i at cycle $cycle%9d: " +
                      f"A = 0x12345678 (unwritten stack-RAM fill pattern)")
              println(f"    jpc=0x$j%04x pc=0x${dut.io.pc(i).toInt}%04x " +
                      f"sp=${dut.cluster.cores(i).pipeline.stack.sp.toInt}  " +
                      f"loaded method [0x$mStart%04x, 0x${mStart + mLen}%04x)")
              print(bcDump(i, j, back = 6, fwd = 6))
            }

            // METHOD ESCAPE. The first jpc outside the loaded method's extent is
            // the derailment itself; everything after it (the 0xE8 dispatch, the
            // stcp, the handle_cnt corruption) is consequence. Reported once per
            // core so a derailed core does not drown the log.
            if (escapeSeen(i) < 5) {
              val mStart = dut.cluster.cores(i).memCtrl.bcCacheStartReg.toInt * 4
              val mLen   = dut.cluster.cores(i).memCtrl.bcFillLen.toInt * 4
              if (mLen > 0) {
                val off = (j - mStart) & 0xFFF
                // TOLERANCE. jpc legitimately runs a little past the final
                // `return` while the fetch pipeline drains — the first version of
                // this check fired at cycle 652 on exactly that, jpc one byte past
                // a method ending in 0xb1. A bytecode plus a two-byte operand
                // reaches +3, so 8 is clear of the pipeline and far short of the
                // ~0x110 overshoot actually being hunted.
                if (off >= mLen + 8) {
                  escapeSeen(i) += 1
                  println(f"\n*** METHOD ESCAPE *** core $i at cycle $cycle%9d: " +
                          f"jpc=0x$j%04x is outside the loaded method " +
                          f"[0x$mStart%04x, 0x${mStart + mLen}%04x) " +
                          f"(start=0x$mStart%04x len=$mLen bytes, offset into method $off)")
                  println(f"    pc=0x${dut.io.pc(i).toInt}%04x " +
                          f"sp=${dut.cluster.cores(i).pipeline.stack.sp.toInt} " +
                          f"A=0x${dut.cluster.cores(i).pipeline.stack.a.toLong.toInt}%08x")
                  print(bcDump(i, j))
                }
              }
            }
          }
        }

        // Fine-grained sampling costs ~3 signal reads per core per cycle and
        // dominates the run: ungated it turned a 50-minute simulation into
        // 2.5 hours. Everything it looks for lives in a known window — the
        // first exception is at 56,176,845 and nothing throws before it, which
        // runs 3 and 4 established over the whole run — so it is switched on
        // shortly before that. Verilator is deterministic and reading a signal
        // does not perturb the DUT, so this changes only wall-clock time.
        // Raise DETAIL_FROM only with evidence that nothing interesting happens
        // earlier; the exception log is what provides that evidence.
        val detail = cycle >= DETAIL_FROM

        // Resolve the PREVIOUS cycle's fill from the RTL's own capture register.
        //
        // This used to read `pipeline.stack.a` on the cycle `bcRd` was seen, on
        // the reasoning that `bcRdCaptureReg := io.aout` latches the same value
        // at the coming edge. That reasoning is wrong often enough to matter:
        // the cross-check caught a fill this probe recorded as 0 which the
        // design had captured as 0x20. Read the register the design actually
        // uses, one cycle on, and stop guessing at the sampling point.
        if (detail) for (i <- 0 until cpuCnt) if (fillPend(i) >= 0) {
          val k = fillPend(i)
          fillPend(i) = -1
          val v = dut.cluster.cores(i).memCtrl.bcRdCaptureReg.toLong.toInt
          fillVal(i)(k) = v
          // Log the opening fills unconditionally. Comparing the boot sequence
          // at 2 cores against 4 is what says where they diverge — the memory
          // image is identical, so any difference is the design reacting to core
          // count, which is the thing under investigation.
          if (fillLogged < 40) {
            fillLogged += 1
            logLine(f"FILL  cycle=${fillCyc(i)(k)}%9d core=$i%d raw=0x$v%08x " +
                    f"start=${v >>> 10}%7d len=${v & 0x3ff}%4d  ${methodAt(v >>> 10)}")
          }
          if (v == 0 && nullFillDumps < 2 && pendingNullDump < 0) {
            nullFillDumps += 1
            pendingNullDump = i
          }
        }

        if (detail) for (i <- 0 until cpuCnt) {
          val r = dut.cluster.cores(i).memCtrl.io.memIn.bcRd.toBoolean
          if (r && !lastBcRd(i)) {
            val k = fillIdx(i)
            fillCyc(i)(k) = cycle
            fillVal(i)(k) = -1            // filled in next cycle from the RTL
            fillPend(i) = k
            fillIdx(i) = (k + 1) % FILLS
            // The trigger for a fill of start=0/len=0 lives in the resolve block
            // above, because the value is only known a cycle later.
          }
          lastBcRd(i) = r
        }

        if (detail) {
          var asserter = -1
          for (i <- 0 until cpuCnt)
            if (asserter < 0 && dut.cluster.cores(i).sys.gcHaltReg.toBoolean) asserter = i
          val owner =
            if (dut.cluster.cmpSync.exists(_.state.toEnum.toString == "LOCKED"))
              dut.cluster.cmpSync.map(_.lockedId.toInt).getOrElse(-1)
            else -1

          if (asserter >= 0) {
            if (!haltActive) {
              haltActive = true; haltStart = cycle; haltAsserter = asserter
              for (i <- 0 until cpuCnt) { advDuringHalt(i) = 0; ownerDuringHalt(i) = false }
            }
            for (i <- 0 until cpuCnt if i != asserter) {
              val pc = dut.io.pc(i).toInt
              if (pc != lastPcH(i)) advDuringHalt(i) += 1
              if (owner == i) ownerDuringHalt(i) = true
            }
          } else if (haltActive) {
            haltActive = false
            haltWindows += 1
            val leaked = (0 until cpuCnt).exists(i => i != haltAsserter && advDuringHalt(i) > 0)
            if (leaked) leakWindows += 1
            if (leaked && haltLogged < 40) {
              haltLogged += 1
              val who = (0 until cpuCnt).filter(i => i != haltAsserter && advDuringHalt(i) > 0)
                .map(i => f"core $i advanced ${advDuringHalt(i)}%d cycles" +
                          (if (ownerDuringHalt(i)) " (HELD THE LOCK — exempt by design)" else ""))
                .mkString("; ")
              logLine(f"HALTLEAK window ${haltStart}%9d..$cycle%9d (${cycle - haltStart}%6d cy) " +
                      f"asserted by core $haltAsserter%d: $who")
            }
          }
          for (i <- 0 until cpuCnt) lastPcH(i) = dut.io.pc(i).toInt
        }

        if (detail) for (i <- 0 until cpuCnt) {
          val e = dut.cluster.cores(i).sys.io.exc.toBoolean
          if (e && !lastExc(i)) {
            excCount += 1
            val t = dut.cluster.cores(i).sys.excTypeReg.toInt
            val nm = excName.getOrElse(t, s"unknown($t)")
            if (excCount <= 400)
              logLine(f"EXC   cycle=$cycle%9d core=$i%d type=$t%d $nm%-20s " +
                      f"pc=0x${dut.io.pc(i).toInt}%04x jpc=0x${dut.io.jpc(i).toInt}%04x " +
                      f"A=0x${dut.cluster.cores(i).pipeline.stack.a.toLong.toInt}%08x " +
                      f"sp=${dut.cluster.cores(i).pipeline.stack.sp.toInt}")
            if (fullExcDumps < 3) {
              fullExcDumps += 1
              val sb = new StringBuilder
              sb.append(s"\n*** EXCEPTION #$excCount on core $i: $nm ***\n")
              sb.append(fillsOf(i))
              sb.append(historyOf(i))
              sb.append(bcDump(i, dut.io.jpc(i).toInt, back = 24, fwd = 24))
              sb.append(snapshot(s"AT EXCEPTION #$excCount core $i"))
              println("\n" + sb.toString)
              logLine(sb.toString)
            }
          }
          lastExc(i) = e
        }

        if (pendingNullDump >= 0) {
          val i = pendingNullDump
          pendingNullDump = -1
          val cap = dut.cluster.cores(i).memCtrl.bcRdCaptureReg.toLong.toInt
          val sb = new StringBuilder
          sb.append(s"\n*** NULL METHOD POINTER: bytecode cache fill with " +
                    s"start=0 len=0 on core $i ***\n")
          sb.append(f"  RTL's own capture one cycle on: bcRdCaptureReg=0x$cap%08x " +
                    (if (cap == 0) "-- CONFIRMS the probe's sample\n"
                     else "-- DISAGREES with the probe's stack.a sample, trust this one\n"))
          sb.append(bmbLog())
          sb.append(fillsOf(i))
          sb.append(historyOf(i))
          sb.append(snapshot(s"AT NULL FILL core $i"))
          println("\n" + sb.toString)
          logLine(sb.toString)
        }

        // BMB command and response ring. Both channels, every cycle they fire.
        if (detail) {
          if (dut.ram.io.bus.cmd.valid.toBoolean && dut.ram.io.bus.cmd.ready.toBoolean) {
            val isWrite = dut.ram.io.bus.cmd.fragment.opcode.toInt == 1
            bmbCyc(bmbIdx)  = cycle
            bmbKind(bmbIdx) = if (isWrite) 1 else 0
            bmbSrc(bmbIdx)  = if (dut.hasBusSource) dut.ram.io.bus.cmd.fragment.source.toInt else -1
            val w = (dut.ram.io.bus.cmd.fragment.address.toLong >> 2).toInt
            bmbAddr(bmbIdx) = w
            bmbData(bmbIdx) = if (isWrite) dut.ram.io.bus.cmd.fragment.data.toLong.toInt else 0
            bmbIdx = (bmbIdx + 1) % BMB
            val s = if (dut.hasBusSource) dut.ram.io.bus.cmd.fragment.source.toInt else 0
            if (!isWrite && s < pendingRd.length) pendingRd(s).enqueue(w)
          }
          if (dut.ram.io.bus.rsp.valid.toBoolean && dut.ram.io.bus.rsp.ready.toBoolean) {
            bmbCyc(bmbIdx)  = cycle
            bmbKind(bmbIdx) = 2
            val s = if (dut.hasBusSource) dut.ram.io.bus.rsp.fragment.source.toInt else 0
            val got = dut.ram.io.bus.rsp.fragment.data.toLong.toInt
            bmbSrc(bmbIdx)  = s
            bmbAddr(bmbIdx) = dut.ram.io.bus.rsp.fragment.opcode.toInt   // non-zero = error
            bmbData(bmbIdx) = got
            if (s < pendingRd.length && pendingRd(s).nonEmpty) {
              val w = pendingRd(s).dequeue()
              // Only meaningful for addresses inside the RAM. `rd()` returns 0
              // for anything out of range, so checking those would manufacture
              // mismatches: an early boot read of word 10790418 did exactly
              // that, and the "bus disagrees" it produced was this probe's
              // artifact, not the design's.
              if (w >= 0 && w < dut.memWords) {
                val want = rd(w)
                bmbWant(bmbIdx) = want
                if (want != got) { bmbBad(bmbIdx) = true; mismatches += 1 }
              } else bmbWant(bmbIdx) = got   // unknown; do not flag
            } else bmbBad(bmbIdx) = false
            bmbIdx = (bmbIdx + 1) % BMB
          }
        }

        // The watchpoint itself. Ordered cheapest-first: `valid` is one signal
        // read per cycle and everything else is behind it, so the cost on a
        // 75M-cycle run stays small.
        if (dut.ram.io.bus.cmd.valid.toBoolean && dut.ram.io.bus.cmd.ready.toBoolean &&
            dut.ram.io.bus.cmd.fragment.opcode.toInt == 1) {
          val word = (dut.ram.io.bus.cmd.fragment.address.toLong >> 2).toInt
          if (watched(word)) {
            val data = dut.ram.io.bus.cmd.fragment.data.toLong.toInt
            val mask = dut.ram.io.bus.cmd.fragment.mask.toInt
            val src  = if (dut.hasBusSource) dut.ram.io.bus.cmd.fragment.source.toInt else -1
            val core = if (src >= 0 && src < cpuCnt) src else -1
            val name = staticName.getOrElse(word, s"<word $word>")
            val bad  = violation(word, data)
            val where = if (core >= 0)
              f" pc=0x${dut.io.pc(core).toInt}%04x jpc=0x${dut.io.jpc(core).toInt}%04x" +
              f" A=0x${dut.cluster.cores(core).pipeline.stack.a.toLong.toInt}%08x" +
              f" B=0x${dut.cluster.cores(core).pipeline.stack.b.toLong.toInt}%08x" +
              f" sp=${dut.cluster.cores(core).pipeline.stack.sp.toInt}" else ""
            val line = f"WRITE cycle=$cycle%9d src=$src%d word=$word%d ($name) " +
                       f"data=0x$data%08x ($data) mask=0x$mask%x$where"
            if (bad.isDefined) {
              val sb = new StringBuilder
              sb.append(s"\n*** OUT-OF-RANGE STORE *** ${bad.get}\n")
              sb.append(line + "\n")
              if (core >= 0) {
                sb.append(historyOf(core))
                sb.append(bcDump(core, dut.io.jpc(core).toInt, back = 24, fwd = 24))
              } else {
                sb.append(s"  source $src is not a core port — DMA or debug master\n")
              }
              sb.append(snapshot("AT OUT-OF-RANGE STORE"))
              println("\n" + sb.toString)
              logLine(sb.toString)
              if (anomalyCycle < 0) anomalyCycle = cycle
            } else if (writeLog < WRITE_LOG_MAX) {
              writeLog += 1
              logLine(line)
            }
          }
        }

        // Once the first bad store is caught, the rest of the run only repeats
        // the wedge that is already understood. Give it a window to show what
        // the corruption leads to, then stop rather than burn 20 more minutes.
        if (anomalyCycle > 0 && cycle - anomalyCycle > 1000000) {
          println("\n" + snapshot("TERMINAL after out-of-range store"))
          done = true
        }

        // Per-core, not "all cores at once". The 200M run never tripped the
        // detector because ONE core (3) kept creeping through a software-imul
        // loop while the other three were frozen solid — an all-cores-stable
        // test cannot see a three-out-of-four freeze.
        var allStuck = true
        for (i <- 0 until cpuCnt) {
          val pc = dut.io.pc(i).toInt
          if (pc != lastPc(i)) { lastPc(i) = pc; pcStuckFor(i) = 0 }
          else pcStuckFor(i) += 1
          if (pcStuckFor(i) < stallWindow) allStuck = false
        }
        pcStableFor = if (allStuck) stallWindow else 0

        for (i <- 0 until cpuCnt) {
          if (!reportedStuck(i) && pcStuckFor(i) >= stallWindow) {
            reportedStuck(i) = true
            val snap = snapshot(s"CORE $i FROZEN for $stallWindow cycles")
            println("\n" + snap)
            logLine(snap)
          }
          if (pcStuckFor(i) == 0) reportedStuck(i) = false
        }

        if (pcStableFor >= stallWindow) {
          deadlockCycle = cycle
          val snap = snapshot("WEDGED")
          println("\n" + snap)
          logLine(snap)
          done = true
        }

        if (cycle % 2000000 == 0) {
          val snap = snapshot("progress")
          println("\n" + snap)
          logLine(snap)
        }

        // The first run of this probe did NOT wedge at 4 cores — core 0 died
        // with an uncaught exception moments after releasing the publishers,
        // and the surviving cores kept spinning so PC-stability never tripped.
        // A crash is the outcome to catch, so stop on it explicitly rather than
        // running out the clock and reporting "inconclusive".
        val out = uartOutput.toString
        if (out.contains("SmpGcTest done") || out.contains("SMPGC FAIL") ||
            out.contains("SMPGC STALLED") || out.contains("Uncaught exception") ||
            out.contains("JVM exit!")) {
          println("\n" + snapshot("TERMINAL"))
          done = true
        }
      }

      logLine(s"UART: ${uartOutput.toString}")
      log.close()

      println(s"\n\n=== gcHalt deadlock probe: $cpuCnt cores, $cycle cycles ===")
      println(s"UART output: '${uartOutput.toString.takeRight(400)}'")
      println(f"HALT CHECK: $haltWindows%d stop-the-world windows observed, " +
              f"$leakWindows%d of them had another core still executing.")
      logLine(f"HALT CHECK: $haltWindows windows, $leakWindows leaked")
      if (haltWindows == 0)
        println("  (no halt window seen in the instrumented range — lower DETAIL_FROM " +
                "or run further; the check proves nothing without windows)")
      else if (leakWindows == 0)
        println("  Stop-the-world HOLDS: no core advanced while another asserted gcHalt. " +
                "The lost reference is then NOT explained by a leaking halt — look elsewhere.")
      else
        println("  Stop-the-world LEAKS. Check the per-window lines for whether the " +
                "runner held the lock (exempt by design, and then the design is the bug).")

      if (anomalyCycle > 0)
        println(s"WATCHPOINT: first out-of-range store to a SmpGcTest static at " +
                s"cycle $anomalyCycle — see the log for the core and its bytecode.")
      else
        println("WATCHPOINT: no out-of-range store to a SmpGcTest static was seen. " +
                "If a static nevertheless reads out of range, it was not written " +
                "through the arbiter output — look at DMA or at the probe's own addresses.")

      if (deadlockCycle > 0) {
        val nGcHalt = (0 until cpuCnt).count(dut.cluster.cores(_).sys.gcHaltReg.toBoolean)
        val nHalt = (0 until cpuCnt).count(dut.cluster.cores(_).sys.io.halted.toBoolean)
        println(s"WEDGED at cycle $deadlockCycle: $nHalt/$cpuCnt halted, $nGcHalt asserting gcHalt")
        if (nGcHalt >= 2)
          println("CONFIRMED: >=2 cores assert gcHalt simultaneously — mutual halt, " +
                  "consistent with the non-reentrant monitorexit dropping the lock inside gc().")
        else
          println(s"REFUTED as stated: only $nGcHalt core(s) assert gcHalt at the wedge.")
      } else {
        val out = uartOutput.toString
        if (out.contains("SmpGcTest done"))
          println(if (out.contains("SMPGC FAIL")) "COMPLETED but SMPGC FAIL — references were lost."
                  else "COMPLETED: SmpGcTest ran to the end at 4 cores.")
        else if (out.contains("Uncaught exception") || out.contains("JVM exit!"))
          println("CRASHED: uncaught exception — the collector and the mutators " +
                  "are still interfering.")
        else if (out.contains("SMPGC STALLED"))
          println("STALLED: publishers stopped making progress.")
        else
          println(s"Neither completion nor crash within $maxCycles cycles (inconclusive).")
      }
      println(s"Log: $logFilePath")
    }
}
