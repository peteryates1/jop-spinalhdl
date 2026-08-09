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
  }

  if (cluster.devicePins.contains("uart")) cluster.devicePin[Bool]("uart", "rxd") := True

  val memWords = memSize / 4
  val ram = BmbOnChipRam(p = cluster.bmbParameter, size = memSize, hexInit = null)
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
