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

  val jopFilePath = "java/apps/SmpGcTest/SmpGcTest.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/gchalt_deadlock_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"CPU count: $cpuCnt (CmpSync global lock)")
  println(s"App: $jopFilePath")

  SimConfig
    .compile(JopGcHaltTestHarness(cpuCnt, romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      def logLine(s: String): Unit = { log.println(s); log.flush() }

      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      val uartOutput = new StringBuilder
      // The freeze lands by ~57M; 200M only bought 143M cycles of the same
      // frozen snapshot at ~55 minutes a run.
      val maxCycles = 75000000
      // A wedge is "nothing observable changed for this long". SmpGcTest's
      // minor GCs are long but they still move PCs, so PC-stability is the
      // discriminator, not UART silence alone.
      val stallWindow = 200000

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
        val w = jpc >> 2
        if (w < 0 || w >= words) return -1
        val word = mem.getBigInt(w).toLong
        ((word >> (8 * (jpc & 3))) & 0xFF).toInt
      }

      /** Opcodes around the current jpc — this is what names the loop. */
      def bcDump(core: Int, jpc: Int, back: Int = 12, fwd: Int = 12): String = {
        val sb = new StringBuilder
        sb.append(f"  core $core bytecode cache around jpc=0x$jpc%04x:\n")
        var a = (jpc - back).max(0)
        val end = jpc + fwd
        while (a <= end) {
          val b = bcByte(core, a)
          if (b >= 0) {
            val marker = if (a == jpc) " <== jpc" else ""
            sb.append(f"      0x$a%04x: ${b}%3d 0x$b%02x  ${opName.getOrElse(b, "?")}%-14s$marker\n")
          }
          a += 1
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
