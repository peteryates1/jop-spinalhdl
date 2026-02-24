package jop.system

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
 * Same as JopSmpTestHarness but with useIhlu=true, so the IHLU replaces
 * CmpSync for per-object locking.
 */
case class JopIhluTestHarness(
  cpuCnt: Int,
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt],
  memSize: Int = 128 * 1024
) extends Component {
  require(cpuCnt >= 2)

  val io = new Bundle {
    val pc  = out Vec(UInt(11 bits), cpuCnt)
    val jpc = out Vec(UInt(12 bits), cpuCnt)
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
    baseConfig = JopCoreConfig(
      memConfig = JopMemoryConfig(mainMemSize = memSize),
      useIhlu = true
    ),
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
  cluster.cores(0).bmbSys.signalReg.simPublic()
  cluster.cores(0).bmbSys.lockReqPulseReg.simPublic()
  cluster.cores(0).bmbSys.lockDataReg.simPublic()
  cluster.cores(0).bmbSys.lockOpReg.simPublic()

  // No UART RX in simulation
  cluster.io.rxd := True

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
        if (dut.cluster.cores(0).bmbSys.lockReqPulseReg.toBoolean) {
          val op = dut.cluster.cores(0).bmbSys.lockOpReg.toBoolean
          val data = dut.cluster.cores(0).bmbSys.lockDataReg.toLong & 0xFFFFFFFFL
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

  val jopFilePath = "java/apps/Small/HelloWorld.jop"
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
        if (output.contains("R80 f=")) {
          println("\n*** Multiple GC cycles completed with IHLU! ***")
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
      val freePattern = """R\d+ f=(\d+)""".r
      val freeVals = freePattern.findAllMatchIn(uartOutput.toString).map(_.group(1).toInt).toList
      val gcOccurred = freeVals.length >= 2 && freeVals.sliding(2).exists { case List(a, b) => b > a case _ => false }
      if (!gcOccurred) {
        run.finish("FAIL", "GC never triggered")
        println("FAIL: GC never triggered")
        System.exit(1)
      }
      val gcCycles = freeVals.sliding(2).count { case List(a, b) => b > a case _ => false }
      run.finish("PASS", s"$cpuCnt cores, $cycle cycles, $gcCycles GC cycles, IHLU verified")
      println(s"PASS: $cpuCnt cores, $gcCycles GC cycles with IHLU in $cycle cycles")
    }
}
