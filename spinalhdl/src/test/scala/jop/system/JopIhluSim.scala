package jop.system
import jop.config._

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.{JopFileLoader, TestHistory}
import jop.memory.JopMemoryConfig
import java.io.PrintWriter

/**
 * IHLU SMP Test Harness: N JOP cores sharing BmbOnChipRam via BmbArbiter.
 *
 * Same as JopSmpTestHarness but with useCmpSync=false (default), so the
 * IHLU replaces CmpSync for per-object locking.
 */
case class JopIhluTestHarness(
  cpuCnt: Int,
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt],
  memSize: Int = 128 * 1024
) extends Component {
  require(cpuCnt >= 2)

  // Derived from the config the cluster is actually built with, not hardcoded.
  // These were previously fixed at pc=11 / jpc=12 — transposed, since the
  // JopCoreConfig defaults are pcWidth=12 and jpcWidth=11. It happened to
  // elaborate while pcWidth was 11; once it grew to 12 both IHLU sims failed
  // with "WIDTH MISMATCH (11 bits <- 12 bits) on io_pc_*" and have not run
  // since, which is why the IHLU verification claim went stale unnoticed.
  // hasCardTable matters here, not just for completeness: without it
  // IO_CARD_SHIFT reads 0, GC.init falls back to the classic collector, and the
  // sim reports "no card table - generational disabled" having exercised
  // nothing generational. That is how this harness could run SmpGcTest to
  // completion and still say INCONCLUSIVE. With it, the cluster-level card
  // table is instantiated and minor GCs actually happen.
  val harnessCfg = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = memSize,
      hasCardTable = true, cardTableBudgetBytes = 16 * 1024),
    useCmpSync = false
  )

  val io = new Bundle {
    val pc  = out Vec(UInt(harnessCfg.pcWidth bits), cpuCnt)
    val jpc = out Vec(UInt((harnessCfg.jpcWidth + 1) bits), cpuCnt)  // JopCluster exposes jpcWidth+1
    val aout = out Vec(Bits(32 bits), cpuCnt)
    val bout = out Vec(Bits(32 bits), cpuCnt)
    val memBusy = out Vec(Bool(), cpuCnt)
    val halted = out Vec(Bool(), cpuCnt)
    val uartTxData  = out Bits(8 bits)
    val uartTxValid = out Bool()
    val wd = out Vec(Bits(32 bits), cpuCnt)
    val excFired = out Bool()
    val excType  = out Bits(8 bits)
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

  // ====================================================================
  // JOP Cluster with IHLU enabled
  // ====================================================================

  val cluster = JopCluster(
    cpuCnt = cpuCnt,
    baseConfig = harnessCfg,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  // Expose IHLU internals for simulation debugging
  cluster.ihlu.foreach { ihlu =>
    ihlu.state.simPublic()
    ihlu.curCpu.simPublic()
    for (i <- 0 until cpuCnt) {
      ihlu.syncFlag(i).simPublic()
      ihlu.registerIn(i).simPublic()
      ihlu.registerOut(i).simPublic()
    }
    for (s <- 0 until ihlu.config.lockSlots) {
      ihlu.valid(s).simPublic()
      ihlu.owner(s).simPublic()
      ihlu.entry(s).simPublic()
      ihlu.count(s).simPublic()
    }
  }

  // Expose core 0's signal register for SMP boot debugging
  cluster.cores(0).sys.signalReg.simPublic()
  cluster.cores(0).sys.lockReqPulseReg.simPublic()
  cluster.cores(0).sys.lockDataReg.simPublic()
  cluster.cores(0).sys.lockOpReg.simPublic()

  // No UART RX in simulation
  if (cluster.devicePins.contains("uart")) cluster.devicePin[Bool]("uart", "rxd") := True

  // ====================================================================
  // Shared Block RAM
  // ====================================================================

  val memWords = (memSize / 4)
  val ram = BmbOnChipRam(
    p = cluster.bmbParameter,
    size = memSize,
    hexInit = null
  )

  val initData = mainMemInit.take(memWords).padTo(memWords, BigInt(0))
  ram.ram.init(initData.map(v => B(v, 32 bits)))

  ram.io.bus << cluster.io.bmb

  // ====================================================================
  // Output Wiring
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

  io.uartTxData  := cluster.io.uartTxData
  io.uartTxValid := cluster.io.uartTxValid
  io.excFired := cluster.io.debugExc
  io.excType  := 0
}

/**
 * IHLU SMP NCoreHelloWorld simulation: N cores (default 2) with IHLU.
 *
 * Validates that per-object locking works correctly for the standard
 * SMP test case. This test exercises monitorenter/monitorexit with real
 * Java synchronized blocks (GC.mutex, PrintStream monitor).
 */
object JopIhluNCoreHelloWorldSim extends App {
  val cpuCnt = if (args.length > 0) args(0).toInt else 2

  val jopFilePath = "java/apps/Small/NCoreHelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/ihlu_ncore_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"CPU count: $cpuCnt (IHLU enabled)")
  println(s"Log file: $logFilePath")

  val run = TestHistory.startRun("JopIhluNCoreHelloWorldSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .compile(JopIhluTestHarness(cpuCnt, romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine(s"=== JOP IHLU NCoreHelloWorld Simulation ($cpuCnt cores) ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 40000000  // 40M cycles
      val reportInterval = 5000000
      var done = false
      var cycle = 0

      // Track per-core watchdog values and toggle counts
      val lastWd = Array.fill(cpuCnt)(0)
      val wdToggles = Array.fill(cpuCnt)(0)

      // Track IHLU lock operations
      var lockOps = 0
      var unlockOps = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // Check for exception firing
        if (dut.io.excFired.toBoolean) {
          val pc0 = dut.io.pc(0).toInt
          val jpc0 = dut.io.jpc(0).toInt
          println(f"\n[$cycle%8d] *** EXCEPTION PC=$pc0%04x JPC=$jpc0%04x ***")
          logLine(f"[$cycle%8d] EXCEPTION PC=$pc0%04x JPC=$jpc0%04x")
        }

        // Monitor IHLU lock pulses from core 0
        if (dut.cluster.cores(0).sys.lockReqPulseReg.toBoolean) {
          val op = dut.cluster.cores(0).sys.lockOpReg.toBoolean
          val data = dut.cluster.cores(0).sys.lockDataReg.toLong & 0xFFFFFFFFL
          if (op) unlockOps += 1 else lockOps += 1
          if (lockOps + unlockOps <= 20) {
            val opStr = if (op) "UNLOCK" else "LOCK"
            println(f"\n[$cycle%8d] IHLU $opStr data=0x$data%08x (lock=$lockOps unlock=$unlockOps)")
            logLine(f"[$cycle%8d] IHLU $opStr data=0x$data%08x")
          }
        }

        // Check for UART output
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
            if (wdToggles(i) <= 5) {
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

          // IHLU slot usage
          var activeSlots = 0
          dut.cluster.ihlu.foreach { ihlu =>
            for (s <- 0 until ihlu.config.lockSlots) {
              if (ihlu.valid(s).toBoolean) activeSlots += 1
            }
          }
          println(f"\n[$cycle%8d] $pcStr halted=$haltedStr slots=$activeSlots lock=$lockOps unlock=$unlockOps toggles=${wdToggles.mkString(",")}")
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

      println(s"\n\n=== IHLU SMP NCoreHelloWorld Simulation Complete ($cpuCnt cores, $cycle cycles) ===")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"IHLU lock operations: $lockOps locks, $unlockOps unlocks")
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
      if (lockOps == 0) {
        run.finish("FAIL", "No IHLU lock operations observed")
        println("FAIL: No IHLU lock operations observed")
        System.exit(1)
      }
      if (lockOps != unlockOps) {
        run.finish("FAIL", s"Lock/unlock mismatch: $lockOps locks, $unlockOps unlocks")
        println(s"FAIL: Lock/unlock mismatch: $lockOps locks, $unlockOps unlocks")
        System.exit(1)
      }
      run.finish("PASS", s"$cpuCnt cores, $cycle cycles, $lockOps lock ops, IHLU verified")
      println(s"PASS: $cpuCnt cores, $lockOps IHLU lock ops in $cycle cycles")
    }
}

/**
 * IHLU SMP GC simulation: 2 cores running Small GC app with IHLU.
 *
 * Validates that per-object locking works correctly during GC,
 * including the drain mechanism (lock owners exempt from gcHalt).
 */
object JopIhluGcBramSim extends App {
  val cpuCnt = 2

  // SmpGcTest, not HelloWorld. This sim used to load a SINGLE-CORE app, so core
  // 1 parked in the boot-wait loop and IHLU was never exercised at all —
  // verified by running it to 49M cycles with core 1 never moving. It therefore
  // could not fail for the reason it exists, which is current-status item 2.
  // SmpGcTest is the multi-core allocating workload item 1 needed; reusing it
  // here is what makes this sim mean something.
  val jopFilePath = "java/apps/SmpGcTest/SmpGcTest.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/ihlu_gc_bram_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"CPU count: $cpuCnt (IHLU enabled)")

  val run = TestHistory.startRun("JopIhluGcBramSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .compile(JopIhluTestHarness(cpuCnt, romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine(s"=== JOP IHLU GC BRAM Simulation ($cpuCnt cores) ===")

      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      val maxCycles = 100000000  // 100M cycles
      val reportInterval = 100000
      var done = false
      var cycle = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        if (dut.io.excFired.toBoolean) {
          val pc0 = dut.io.pc(0).toInt
          val jpc0 = dut.io.jpc(0).toInt
          println(f"\n[$cycle%8d] *** EXCEPTION PC=$pc0%04x JPC=$jpc0%04x ***")
          logLine(f"[$cycle%8d] EXCEPTION PC=$pc0%04x JPC=$jpc0%04x")
        }

        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          print(if (char >= 32 && char < 127) char.toChar else '.')
        }

        if (cycle > 0 && cycle % reportInterval == 0) {
          val pcStr = (0 until cpuCnt).map(i => f"C${i}:PC=${dut.io.pc(i).toInt}%04x").mkString(" ")
          val haltedStr = (0 until cpuCnt).map(i => if (dut.io.halted(i).toBoolean) "H" else ".").mkString
          println(f"\n[$cycle%8d] $pcStr halted=$haltedStr UART: '${uartOutput.toString.takeRight(40)}'")
        }

        val output = uartOutput.toString
        // Stop when the payload says it is finished. This looked for "R80 f=",
        // a marker of the OLD single-core payload that SmpGcTest never prints,
        // so the sim could never stop early: it ran the full 100M cycles (21
        // minutes) despite the app completing around 10M.
        if (output.contains("SmpGcTest done")) {
          println("\n*** SmpGcTest completed with IHLU! ***")
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

      println(s"\n\n=== IHLU GC Simulation Complete ($cpuCnt cores, $cycle cycles) ===")
      println(s"UART Output: '${uartOutput.toString}'")

      val out = uartOutput.toString

      if (!out.contains("SmpGcTest done")) {
        run.finish("FAIL", "SmpGcTest did not run to completion")
        println("FAIL: SmpGcTest did not run to completion")
        System.exit(1)
      }
      if (out.contains("SMPGC FAIL")) {
        run.finish("FAIL", "cross-core references were lost")
        println("FAIL: SMPGC FAIL — cross-core references lost")
        System.exit(1)
      }

      // The point of this sim is that core 1 actually RAN. SmpGcTest's
      // "verified N" counts holders core 0 checked after the other cores stored
      // into them, so a non-zero N is proof the second core executed — which is
      // exactly what the old HelloWorld payload could never demonstrate. A
      // passing run with verified=0 is the vacuous outcome this sim had before.
      val verified = """verified (\d+)""".r.findFirstMatchIn(out).map(_.group(1).toInt).getOrElse(0)
      if (verified == 0) {
        run.finish("FAIL", "verified=0 — the second core never published")
        println("FAIL: verified=0 — the second core never published, IHLU unexercised")
        System.exit(1)
      }

      // INCONCLUSIVE is the honest result while GC.java's SMP guard is in place:
      // no nursery means no minor GC to exercise. It is a pass for IHLU (both
      // cores ran, locks were taken) but NOT evidence about the card table, so
      // say which was demonstrated rather than blurring them.
      // LAST match, not first. `findFirstMatchIn` picked up "STACKROOT minors 6"
      // — a mid-run probe — and reported 6 where the run's own summary line says
      // 10. A verdict that under-reports what it exercised is a small lie in the
      // one place that has to be trustworthy.
      val minors = """minors (\d+)""".r.findAllMatchIn(out).map(_.group(1).toInt).toSeq.lastOption.getOrElse(0)
      val note = if (minors == 0) "classic GC (SMP guard on) — IHLU exercised, card table NOT"
                 else s"$minors minor GCs — IHLU and shared card table exercised"
      run.finish("PASS", s"$cpuCnt cores, $cycle cycles, verified=$verified, $note")
      println(s"PASS: $cpuCnt cores, verified=$verified, $note")
    }
}
