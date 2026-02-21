package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.{JopFileLoader, TestHistory}
import jop.memory.JopMemoryConfig
import java.io.PrintWriter

/**
 * SMP Test Harness: N JOP cores sharing BmbOnChipRam via BmbArbiter.
 *
 * Each core has internal BmbSys (with unique cpuId). Only core 0 has BmbUart.
 * CmpSync provides global lock synchronization.
 *
 * Uses BRAM (zero-latency) to keep simulation fast while testing multicore logic.
 */
case class JopSmpTestHarness(
  cpuCnt: Int,
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt],
  memSize: Int = 128 * 1024
) extends Component {
  require(cpuCnt >= 1)

  val io = new Bundle {
    // Per-core pipeline outputs
    val pc  = out Vec(UInt(11 bits), cpuCnt)
    val jpc = out Vec(UInt(12 bits), cpuCnt)

    // Per-core stack outputs
    val aout = out Vec(Bits(32 bits), cpuCnt)
    val bout = out Vec(Bits(32 bits), cpuCnt)

    // Per-core memory busy
    val memBusy = out Vec(Bool(), cpuCnt)

    // Per-core halted status (from CmpSync via internal BmbSys)
    val halted = out Vec(Bool(), cpuCnt)

    // UART output (from core 0 debug snoop)
    val uartTxData  = out Bits(8 bits)
    val uartTxValid = out Bool()

    // Per-core watchdog output
    val wd = out Vec(Bits(32 bits), cpuCnt)

    // Exception debug (core 0)
    val excFired = out Bool()
    val excType  = out Bits(8 bits)
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
    Seq(BigInt((w >> 24) & 0xFF), BigInt((w >> 16) & 0xFF),
        BigInt((w >> 8) & 0xFF), BigInt((w >> 0) & 0xFF))
  }.padTo(2048, BigInt(0))

  // ====================================================================
  // JOP Cluster: N cores with arbiter + CmpSync
  // ====================================================================

  val cluster = JopCluster(
    cpuCnt = cpuCnt,
    baseConfig = JopCoreConfig(
      memConfig = JopMemoryConfig(mainMemSize = memSize)
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

  // Expose I/O write signals from core 0 memory controller for debugging
  cluster.cores(0).memCtrl.io.ioWr.simPublic()
  cluster.cores(0).memCtrl.io.ioAddr.simPublic()
  cluster.cores(0).memCtrl.io.ioWrData.simPublic()
  cluster.cores(0).ioWrCounter.simPublic()

  // Expose memory controller internal state for putfield debugging
  cluster.cores(0).memCtrl.handleIsWrite.simPublic()
  cluster.cores(0).memCtrl.handleDataPtr.simPublic()
  cluster.cores(0).memCtrl.handleIndex.simPublic()
  cluster.cores(0).memCtrl.addrReg.simPublic()
  cluster.cores(0).memCtrl.state.simPublic()
  cluster.cores(0).memCtrl.io.bcopd.simPublic()
  cluster.cores(0).memCtrl.io.memIn.putfield.simPublic()
  cluster.cores(0).memCtrl.io.memIn.getfield.simPublic()

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
  io.excType  := 0  // Exception type not easily snooped with internal I/O
}

/**
 * SMP BRAM simulation: 2 cores running Small GC app.
 * Verifies both cores boot, print output, and GC works.
 */
object JopSmpBramSim extends App {
  val cpuCnt = 2

  val jopFilePath = "java/apps/Small/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/smp_bram_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"CPU count: $cpuCnt")
  println(s"Log file: $logFilePath")

  val run = TestHistory.startRun("JopSmpBramSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .compile(JopSmpTestHarness(cpuCnt, romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine(s"=== JOP SMP BRAM Simulation ($cpuCnt cores) ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 20000000  // 20M cycles
      val reportInterval = 100000
      var done = false
      var cycle = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // Check for exception firing (core 0)
        if (dut.io.excFired.toBoolean) {
          val pc0 = dut.io.pc(0).toInt
          val jpc0 = dut.io.jpc(0).toInt
          val excType = dut.io.excType.toInt
          println(f"\n[$cycle%8d] *** EXCEPTION type=$excType PC=$pc0%04x JPC=$jpc0%04x ***")
          logLine(f"[$cycle%8d] EXCEPTION type=$excType PC=$pc0%04x JPC=$jpc0%04x")
        }

        // Check for UART output (core 0)
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          print(if (char >= 32 && char < 127) char.toChar else '.')
          logLine(f"[$cycle%8d] UART: '${if (char >= 32 && char < 127) char.toChar.toString else f"\\x$char%02x"}' (0x$char%02x)")
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          val pcStr = (0 until cpuCnt).map(i => f"C${i}:PC=${dut.io.pc(i).toInt}%04x").mkString(" ")
          val haltedStr = (0 until cpuCnt).map(i => if (dut.io.halted(i).toBoolean) "H" else ".").mkString
          println(f"\n[$cycle%8d] $pcStr halted=$haltedStr UART: '${uartOutput.toString}'")
        }

        // Exit after a full GC cycle
        val output = uartOutput.toString
        if (output.contains("R14 f=")) {
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

      println(s"\n\n=== SMP Simulation Complete ($cpuCnt cores, $cycle cycles) ===")
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
      run.finish("PASS", s"$cpuCnt cores, $cycle cycles, SMP GC allocation test working")
      println("PASS: SMP GC allocation test working")
    }
}

/**
 * SMP NCoreHelloWorld simulation: 2 cores, no GC.
 * Core 0: prints "Hello World!" and toggles watchdog
 * Core 1: just toggles watchdog
 * Verifies both cores run independently and toggle their watchdog LEDs.
 */
object JopSmpNCoreHelloWorldSim extends App {
  val cpuCnt = 2

  val jopFilePath = "java/apps/Smallest/NCoreHelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/smp_ncore_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"CPU count: $cpuCnt")
  println(s"Log file: $logFilePath")

  val run = TestHistory.startRun("JopSmpNCoreHelloWorldSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .compile(JopSmpTestHarness(cpuCnt, romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine(s"=== JOP SMP NCoreHelloWorld Simulation ($cpuCnt cores) ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 40000000  // 40M cycles (~400ms at 100MHz)
      val reportInterval = 5000000
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
          val excType = dut.io.excType.toInt
          println(f"\n[$cycle%8d] *** EXCEPTION type=$excType PC=$pc0%04x JPC=$jpc0%04x ***")
          logLine(f"[$cycle%8d] EXCEPTION type=$excType PC=$pc0%04x JPC=$jpc0%04x")
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
          println(f"\n[$cycle%8d] $pcStr halted=$haltedStr $wdStr toggles=${wdToggles.mkString(",")}")
        }

        // Exit after both cores have toggled watchdog at least 3 times
        if (wdToggles.forall(_ >= 3)) {
          println(s"\n*** Both cores toggling watchdog! toggles=${wdToggles.mkString(",")} ***")
          // Run a bit more to collect more UART output
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

      println(s"\n\n=== SMP NCoreHelloWorld Simulation Complete ($cpuCnt cores, $cycle cycles) ===")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Per-core WD toggles: ${wdToggles.zipWithIndex.map { case (t, i) => s"C$i=$t" }.mkString(" ")}")
      println(s"Log written to: $logFilePath")

      if (!uartOutput.toString.contains("Hello World!")) {
        run.finish("FAIL", "Did not see 'Hello World!' from core 0")
        println("FAIL: Did not see 'Hello World!' from core 0")
        System.exit(1)
      }
      for (i <- 0 until cpuCnt) {
        if (wdToggles(i) < 3) {
          run.finish("FAIL", s"Core $i only toggled watchdog ${wdToggles(i)} times (expected >= 3)")
          println(s"FAIL: Core $i only toggled watchdog ${wdToggles(i)} times (expected >= 3)")
          System.exit(1)
        }
      }
      run.finish("PASS", s"$cpuCnt cores, $cycle cycles, both cores toggling watchdog LEDs")
      println("PASS: Both cores running and toggling watchdog LEDs!")
    }
}

/**
 * Diagnostic SMP sim for Small/NCoreHelloWorld.
 * Monitors signal register and halted state to debug core 1 boot failure.
 */
object JopSmpSmallNCoreDebugSim extends App {
  val cpuCnt = 2
  val bramSize = 128 * 1024  // Test: back to 128KB to confirm crash is reproducible

  val jopFilePath = "java/apps/Small/NCoreHelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, bramSize / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"BRAM size: ${bramSize / 1024}KB")
  println(s"CPU count: $cpuCnt")

  SimConfig
    .compile(JopSmpTestHarness(cpuCnt, romData, ramData, mainMemData, memSize = bramSize))
    .doSim { dut =>
      var uartOutput = new StringBuilder

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 2000000  // 2M cycles (enough to diagnose)
      val reportInterval = 500000
      var cycle = 0
      var signalSetCycle = -1
      var core1BootCycle = -1
      var lastPc1 = -1
      var ioWrToSys0Count = 0  // Writes to BmbSys (slave 0)

      val lastWd = Array.fill(cpuCnt)(0)
      val wdToggles = Array.fill(cpuCnt)(0)

      while (cycle < maxCycles) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // Monitor when putfield fires in IDLE state
        val putfieldFires = dut.cluster.cores(0).memCtrl.io.memIn.putfield.toBoolean
        val bcopdVal = dut.cluster.cores(0).memCtrl.io.bcopd.toInt & 0xFFFF
        val memState = dut.cluster.cores(0).memCtrl.state.toBigInt.toInt

        if (putfieldFires && cycle > 100000 && cycle < 200000) {
          val boutVal = dut.io.bout(0).toLong & 0xFFFFFFFFL
          val aoutVal = dut.io.aout(0).toLong & 0xFFFFFFFFL
          println(f"\n[$cycle%8d] PUTFIELD fires: bcopd=0x$bcopdVal%04x bout=0x$boutVal%08x aout=0x$aoutVal%08x state=$memState")
        }

        // Monitor HANDLE_ACCESS: check if putfield reaches I/O path
        val handleIsWrite = dut.cluster.cores(0).memCtrl.handleIsWrite.toBoolean
        val handleDataPtr = dut.cluster.cores(0).memCtrl.handleDataPtr.toLong
        val addrRegVal = dut.cluster.cores(0).memCtrl.addrReg.toLong
        val handleIdx = dut.cluster.cores(0).memCtrl.handleIndex.toLong

        // Log when handleDataPtr is in I/O range (top 2 of 24 bits = 11 = 0xC00000+)
        if (handleIsWrite && handleDataPtr >= 0xC00000L && cycle < 200000) {
          println(f"\n[$cycle%8d] HANDLE_WRITE: dataPtr=0x$handleDataPtr%06x idx=$handleIdx addrReg=0x$addrRegVal%06x state=$memState")
        }

        // Monitor I/O writes from core 0's memory controller
        if (dut.cluster.cores(0).memCtrl.io.ioWr.toBoolean) {
          val addr = dut.cluster.cores(0).memCtrl.io.ioAddr.toInt
          val data = dut.cluster.cores(0).memCtrl.io.ioWrData.toLong
          val slaveId = (addr >> 4) & 3
          val subAddr = addr & 0xF
          if (slaveId == 0 && ioWrToSys0Count < 20) {
            ioWrToSys0Count += 1
            println(f"\n[$cycle%8d] IO_WR slave=$slaveId addr=$subAddr data=0x$data%08x (BmbSys write #$ioWrToSys0Count)")
          } else if (slaveId == 0) {
            ioWrToSys0Count += 1
          }
        }

        // Monitor signal register (core 0's BmbSys)
        val signalSet = dut.cluster.cores(0).bmbSys.signalReg.toBoolean
        if (signalSet && signalSetCycle < 0) {
          signalSetCycle = cycle
          println(f"\n[$cycle%8d] *** SIGNAL SET by core 0 ***")
        }

        // Monitor core 1 PC changes
        val pc1 = dut.io.pc(1).toInt
        if (pc1 != lastPc1 && (pc1 != 0x10 || lastPc1 < 0)) {
          if (pc1 != 0x10 && lastPc1 == 0x10 && core1BootCycle < 0) {
            core1BootCycle = cycle
            println(f"\n[$cycle%8d] *** CORE 1 LEFT SIGNAL LOOP, PC=$pc1%04x ***")
          }
          lastPc1 = pc1
        }

        // Check for UART output
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          print(if (char >= 32 && char < 127) char.toChar else '.')
        }

        // Monitor watchdog changes
        for (i <- 0 until cpuCnt) {
          val wd = dut.io.wd(i).toInt
          if (wd != lastWd(i)) {
            wdToggles(i) += 1
            if (wdToggles(i) <= 5) {
              println(f"\n[$cycle%8d] Core $i WD: $wd (toggle #${wdToggles(i)})")
            }
            lastWd(i) = wd
          }
        }

        // Progress report
        if (cycle % reportInterval == 0) {
          val pcStr = (0 until cpuCnt).map(i => f"C${i}:PC=${dut.io.pc(i).toInt}%04x").mkString(" ")
          val haltedStr = (0 until cpuCnt).map(i => if (dut.io.halted(i).toBoolean) "H" else ".").mkString
          val ioWrCount = dut.cluster.cores(0).ioWrCounter.toInt
          println(f"\n[$cycle%8d] $pcStr halted=$haltedStr signal=$signalSet ioWr=$ioWrCount sysWr=$ioWrToSys0Count toggles=${wdToggles.mkString(",")}")
        }
      }

      println(s"\n\n=== Diagnostic SMP Sim Complete ($cycle cycles) ===")
      println(s"Signal set at cycle: $signalSetCycle")
      println(s"Core 1 boot at cycle: $core1BootCycle")
      println(s"Total IO writes from core 0: ${dut.cluster.cores(0).ioWrCounter.toInt}")
      println(s"BmbSys writes: $ioWrToSys0Count")
      println(s"UART: '${uartOutput.toString}'")
      println(s"WD toggles: ${wdToggles.zipWithIndex.map { case (t, i) => s"C$i=$t" }.mkString(" ")}")
    }
}
