package jop.system
import jop.config._

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import spinal.lib.memory.sdram.sdr._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import jop.memory.{JopMemoryConfig, BmbSdramCtrl32, SdramDeviceInfo}
import jop.utils.{JopFileLoader, TestHistory}
import java.io.PrintWriter

/**
 * SMP SDRAM Test Harness: N JOP cores sharing BmbSdramCtrl32 via JopCluster.
 *
 * Unlike JopSmpTestHarness (which uses BmbOnChipRam), this harness goes through
 * BmbSdramCtrl32 + SdramCtrlNoCke, exercising the 32-to-16 bit bridge,
 * burst read state machine, and response reassembly under multi-core arbitrated traffic.
 *
 * Uses SdramCtrlNoCke (local copy with CKE gating disabled) since the Altera
 * controller is a BlackBox that can't be simulated with Verilator.
 *
 * Both Array Cache (A$) and Object Cache (O$) are safe for SMP via
 * cross-core snoop invalidation: each core's iastore/putfield broadcasts
 * on the snoop bus, and other cores selectively invalidate matching lines.
 */
case class JopSmpSdramTestHarness(
  cpuCnt: Int,
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt]
) extends Component {
  require(cpuCnt >= 1)

  val md = MemoryDevice.W9825G6JH6

  // ONE config for the cluster and the io widths.
  //
  // hasCardTable is not optional here: without it IO_CARD_SHIFT reads 0, GC.init
  // falls back to the classic collector, and the run reports "no card table -
  // generational disabled" — which made this harness incomparable with
  // JopIhluTestHarness and produced a spurious core-1 no-start in the BRAM
  // twin. burstLen = 4 (4-word burst BC_FILL) is preserved from the original.
  // TAKEN FROM THE BOARD PRESET, not hand-rolled.
  //
  // This used to read `JopMemoryConfig(burstLen = 4, hasCardTable = true, ...)`,
  // which is NOT what ep4cgx150Smp builds: the board runs burstLen = 0 (no
  // burst, pipelined single-word) and hasBackendFill = true. Those are exactly
  // the two multi-command paths in BmbSdramCtrl32 — so the harness exercised a
  // burst path the board never uses and never exercised the fill path the board
  // does, while appearing to be "the same design at 4 cores".
  //
  // That is not a detail: this harness was used to conclude that a fault seen on
  // the board does not reproduce in simulation, i.e. to argue the RTL was
  // innocent. A harness that silently differs from the thing it models can only
  // produce that kind of wrong answer, so it now derives from the preset and
  // cannot drift again.
  // memoryStyle is the ONE thing that must not come from the board: Cyclone IV
  // selects MemoryStyle.AlteraLpm, whose lpm_rom/lpm_ram_dp are BlackBoxes with
  // no Verilog body, so Verilator fails with "Cannot find file containing
  // module: 'rom'". Generic infers the same memories. Everything else — burstLen,
  // hasBackendFill, the caches, addressWidth — is the board's.
  val boardCfg = JopConfig.ep4cgx150Smp(cpuCnt, 60).system.coreConfig
  val harnessCfg = boardCfg.copy(useCmpSync = false, memoryStyle = Some(MemoryStyle.Generic))

  val io = new Bundle {
    // SDRAM interface (exposed for simulation model)
    val sdram = master(SdramInterface(SdramDeviceInfo.layoutFor(md)))

    // Per-core pipeline outputs
    // Widths from the CONFIG, not literals — an 11-bit pc against a 12-bit
    // JopCoreConfig.pcWidth failed elaboration with WIDTH MISMATCH, which is
    // why this sim had not run since the microcode ROM widened to 4K.
    val pc  = out Vec(UInt(harnessCfg.pcWidth bits), cpuCnt)
    val jpc = out Vec(UInt((harnessCfg.jpcWidth + 1) bits), cpuCnt)

    // Per-core stack outputs
    val aout = out Vec(Bits(32 bits), cpuCnt)
    val bout = out Vec(Bits(32 bits), cpuCnt)

    // Per-core memory busy
    val memBusy = out Vec(Bool(), cpuCnt)

    // Per-core halted status
    val halted = out Vec(Bool(), cpuCnt)

    // UART output (from core 0 debug snoop)
    val uartTxData  = out Bits(8 bits)
    val uartTxValid = out Bool()

    // Per-core watchdog output
    val wd = out Vec(Bits(32 bits), cpuCnt)

    // Exception debug (core 0)
    val excFired = out Bool()

    // Array-bounds fault, per core. The 4-core hardware wedge is an EXC_AB whose
    // operands are all provably right (index = the core's own id, handle = a
    // real array, length word = 4 in memory) while the check sees length 0, so
    // the fault is a bad READ. Stopping the simulation ON it is the only way to
    // see the BMB transaction that produced the zero.
    val abFire   = out Vec(Bool(), cpuCnt)
    val abIndex  = out Vec(UInt(harnessCfg.memConfig.addressWidth bits), cpuCnt)
    val abLength = out Vec(UInt(harnessCfg.memConfig.addressWidth bits), cpuCnt)
    val abHandle = out Vec(UInt(harnessCfg.memConfig.addressWidth bits), cpuCnt)
  }

  // Extract JBC init from main memory (same as JopSmpTestHarness)
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
  }.padTo(harnessCfg.jbcDepth, BigInt(0))

  // ====================================================================
  // JOP Cluster: N cores with arbiter + CmpSync
  // ====================================================================

  val cluster = JopCluster(
    cpuCnt = cpuCnt,
    baseConfig = harnessCfg,
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
  cluster.cores(0).sys.signalReg.simPublic()

  // No UART RX in simulation
  if (cluster.devicePins.contains("uart")) cluster.devicePin[Bool]("uart", "rxd") := True

  // ====================================================================
  // SDRAM Controller (shared, using SdramCtrlNoCke for simulation)
  // ====================================================================

  val sdramCtrl = BmbSdramCtrl32(
    bmbParameter = cluster.bmbParameter,
    layout = SdramDeviceInfo.layoutFor(md),
    timing = SdramDeviceInfo.timingFor(md),
    CAS = md.casLatency,
    useAlteraCtrl = false
  )

  sdramCtrl.io.bmb <> cluster.io.bmb

  // THE BLOCK-FILL SIDEBAND, which the board has and this harness did not.
  // It is the only path that can preempt BmbSdramCtrl32 between the two 16-bit
  // halves of a 32-bit transfer, so leaving it unconnected removed the most
  // interesting interaction in the bridge from every run this harness ever did.
  cluster.io.fill match {
    case Some(f) => f <> sdramCtrl.io.fill
    case None =>
      sdramCtrl.io.fill.cmd   := False
      sdramCtrl.io.fill.start := 0
      sdramCtrl.io.fill.end   := 0
      sdramCtrl.io.fill.value := 0
  }
  io.sdram <> sdramCtrl.io.sdram

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
  for (i <- 0 until cpuCnt) {
    io.abFire(i)   := cluster.io.debugAbFire(i)
    io.abIndex(i)  := cluster.io.debugAbIndex(i)
    io.abLength(i) := cluster.io.debugAbLength(i)
    io.abHandle(i) := cluster.io.debugAbHandle(i)
  }
}

/**
 * SMP SDRAM NCoreHelloWorld simulation.
 *
 * Tests N cores (default 4) running NCoreHelloWorld through the SDRAM path
 * (BmbSdramCtrl32 + SdramCtrlNoCke + SdramModel).
 *
 * Usage: sbt "Test / runMain jop.system.JopSmpSdramNCoreHelloWorldSim [cpuCnt]"
 */
object JopSmpSdramNCoreHelloWorldSim extends App {
  val cpuCnt = if (args.length > 0) args(0).toInt else 4

  // App is overridable so this harness can run the SAME workload as the board.
  // The 4-core hardware stall is SmpGcTest on SDRAM; NCoreHelloWorld only wakes
  // the cores and toggles watchdogs, so passing it says nothing about the heavy
  // GC workload that actually stalls.
  //   argv: [cpuCnt] [maxCycles] [app.jop]
  val jopFilePath = if (args.length > 2) args(2) else "java/apps/Small/NCoreHelloWorld.jop"
  val romFilePath = MicrocodePaths.simulationRom
  val ramFilePath = MicrocodePaths.simulationRam
  val logFilePath = "spinalhdl/smp_sdram_ncore_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"CPU count: $cpuCnt")
  println(s"Log file: $logFilePath")

  val run = TestHistory.startRun("JopSmpSdramNCoreHelloWorldSim", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .compile(JopSmpSdramTestHarness(cpuCnt, romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine(s"=== JOP SMP SDRAM NCoreHelloWorld Simulation ($cpuCnt cores) ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz

      // Create SDRAM simulation model
      val sdramModel = SdramModel(
        io = dut.io.sdram,
        layout = SdramDeviceInfo.layoutFor(dut.md),
        clockDomain = dut.clockDomain
      )

      // Initialize SDRAM with program data
      for (wordIdx <- mainMemData.indices) {
        val word = mainMemData(wordIdx).toLong & 0xFFFFFFFFL
        val byteAddr = wordIdx * 4
        sdramModel.write(byteAddr + 0, ((word >>  0) & 0xFF).toByte)
        sdramModel.write(byteAddr + 1, ((word >>  8) & 0xFF).toByte)
        sdramModel.write(byteAddr + 2, ((word >> 16) & 0xFF).toByte)
        sdramModel.write(byteAddr + 3, ((word >> 24) & 0xFF).toByte)
      }

      dut.clockDomain.waitSampling(5)

      // 5M was the default and it is NOT enough once the card table is present:
      // the run stopped inside "GC init...." with every core at zero, which
      // reads as a total wedge but is just the cap. SDRAM costs far more cycles
      // per access than the BRAM twin (which finishes in ~261k). Overridable so
      // a cap can be ruled out before a failure is believed.
      val maxCycles = if (args.length > 1) args(1).toInt else 40000000
      // argv[3]: stop after this many array-bounds faults (0 = never stop).
      val abStop = if (args.length > 3) args(3).toInt else 1
      val reportInterval = 500000
      var done = false
      var cycle = 0
      var abCount = 0

      // Track per-core watchdog values and toggle counts
      val lastWd = Array.fill(cpuCnt)(0)
      val wdToggles = Array.fill(cpuCnt)(0)

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // THE BOUNDS FAULT. Report every occurrence with its operands; the
        // hardware only ever showed the FIRST one per core, and whether the
        // later ones look the same is itself a data point. `abStop` (argv 3)
        // ends the run on the Nth fault so a VCD window can be aimed at it.
        for (i <- 0 until cpuCnt) {
          if (dut.io.abFire(i).toBoolean) {
            abCount += 1
            val idx = dut.io.abIndex(i).toLong
            val len = dut.io.abLength(i).toLong
            val hdl = dut.io.abHandle(i).toLong
            val msg = f"[$cycle%9d] *** AB FAULT #$abCount core $i idx=$idx len=$len handle=$hdl pc=${dut.io.pc(i).toInt}%04x jpc=${dut.io.jpc(i).toInt}"
            println("\n" + msg)
            logLine(msg)
            if (abStop > 0 && abCount >= abStop) {
              println(s"\n*** stopping on AB fault #$abCount (argv[3]=$abStop) ***")
              done = true
            }
          }
        }

        // Check for exception firing (core 0)
        if (dut.io.excFired.toBoolean) {
          val pc0 = dut.io.pc(0).toInt
          val jpc0 = dut.io.jpc(0).toInt
          println(f"\n[$cycle%8d] *** EXCEPTION PC=$pc0%04x JPC=$jpc0%04x ***")
          logLine(f"[$cycle%8d] EXCEPTION PC=$pc0%04x JPC=$jpc0%04x")
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

        // Exit after all cores have toggled watchdog at least once
        // Only an exit condition for the NCoreHelloWorld workload this harness
        // was written for. SmpGcTest toggles no watchdogs and must run on to the
        // fault, so the early exit is suppressed whenever an app was named.
        if (args.length <= 2 && wdToggles.forall(_ >= 1)) {
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

      println(s"\n\n=== SMP SDRAM Simulation Complete ($cpuCnt cores, $cycle cycles) ===")
      println(s"UART Output: '${uartOutput.toString}'")
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
      run.finish("PASS", s"$cpuCnt cores, $cycle cycles, SMP SDRAM NCoreHelloWorld working")
      println(s"PASS: All $cpuCnt cores running on SDRAM!")
    }
}
