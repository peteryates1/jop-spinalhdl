package jop.system

import jop.config._
import jop.generate.BuildLayout
import jop.memory.SdramDeviceInfo
import jop.utils.{JopFileLoader, JopSimDefaults, SimApp}
import spinal.core._
import spinal.core.sim._
import spinal.lib.memory.sdram.sdr.sim.SdramModel

/**
 * DOES A STOP-THE-WORLD STOP A CORE THAT NEVER TOUCHES MEMORY? -- status item 165.
 *
 * A GC halt reaches a core as `bsy`, which freezes the fetch stage only at a
 * microcode `wait` (`pcwait && bsy`, FetchStage). Only bytecodes that touch
 * memory have one, so a core in a loop of local loads and stores, `iinc`,
 * arithmetic and branches is marked halted and keeps executing.
 *
 * `SmpStackLoop` puts core 1 in exactly that loop while core 0 runs minor
 * collections. This probe watches every stop-the-world (core 0's `gcHaltReg`)
 * and counts, in the RTL:
 *
 *   - cycles core 1 EXECUTED while marked halted (its fetch stage not frozen);
 *   - of those, cycles after the collector's acknowledgement (`othersHalted`);
 *   - of those, cycles while the collector's root port held core 1's stack-RAM
 *     read address (`debugRamAddr != 0`), when core 1's own reads return the
 *     words being scanned;
 *   - how often the acknowledgement rose, and fell again while the halt was
 *     still held (the window item 165 was filed for), and which of core 1's
 *     terms -- halted, flushed, stopped -- ended it;
 *   - cycles core 1's memory controller was out of IDLE after the
 *     acknowledgement, i.e. a transaction of the halted core running into the
 *     collection.
 *
 * THE PROPERTY: once the collector holds the acknowledgement, no other core
 * executes, and none has a memory transaction in flight. A core may run on
 * between the halt and its acknowledgement -- that is what the
 * acknowledgement waits for -- but not after it.
 *
 * RED 2026-11-12, on the RTL as it stood (status item 165):
 *
 *   JopSmpStackLoopSim        168,051 cycles after the acknowledgement in 8
 *                             collections, and 8 of core 1's 9 passes wrong
 *   JopSmpStackLoopCacheSim   2,861 cycles; acknowledgement withdrawn 189 of
 *                             190 times while the halt was held
 *     with SmpGcTest.jop      6 cycles, at most 2 per collection; withdrawn once
 *
 * GREEN 2026-11-12 with the fix (`sys_gcstop`, and `coreStopped` in the
 * acknowledgement): in all three runs core 1 executed nothing and had no
 * memory transaction after the acknowledgement, the acknowledgement was never
 * withdrawn, and every pass was right. Before the last term of `coreStopped`
 * (`StackStage.writesQuiet`) the stack-cache run still saw the acknowledgement
 * rise for one cycle and fall, twice in 8 collections.
 */
object StackLoopProbe {

  /** Make the probed signals readable. Call inside the elaboration block. */
  def expose(cluster: JopCluster): Unit = {
    val c0 = cluster.cores(0)
    val c1 = cluster.cores(1)
    c0.sys.gcHaltReg.simPublic()
    c0.sys.io.syncIn.othersHalted.simPublic()
    c1.sys.io.halted.simPublic()
    c1.pipeline.fetch.io.frozen.simPublic()
    c1.io.debugRamAddr.simPublic()
    c1.sys.io.coreStopped.simPublic()
    c1.sys.io.gcFlushDone.simPublic()
    c1.memCtrl.io.debug.idle.simPublic()
  }

  /** Run to the app's verdict line, then judge. `okLine` is the app's pass line. */
  def run(cd: ClockDomain, cluster: JopCluster, uartValid: Bool, uartData: Bits,
          maxCycles: Int, verdictPrefixes: Seq[String], okLine: String): Unit = {
    val c0 = cluster.cores(0)
    val c1 = cluster.cores(1)
    val uart = new StringBuilder

    var windows = 0
    var stwCycles, exemptCycles, runHalted, runAfterAck, runHijacked, hijackCycles = 0L
    var ackRises, ackFalls = 0
    var minWithdrawn, maxWithdrawn = -1
    var inStw = false
    var start, ackAt, ackRoseAt = 0
    var wRun, wRunAfterAck, wRunHijacked = 0L
    var wFalls = 0
    var lastAck = false
    var maxRunAfterAck = 0L
    var memAfterAck = 0L
    var fallsLogged = 0

    var cycle = 0
    var done = false
    while (cycle < maxCycles && !done) {
      cycle += 1
      cd.waitSampling()

      if (uartValid.toBoolean) {
        val ch = uartData.toInt
        val c = if (ch >= 32 && ch < 127) ch.toChar else if (ch == 10) '\n' else '.'
        uart.append(c); print(c)
        if (c == '\n' && verdictPrefixes.exists(p => uart.indexOf(p) >= 0)) done = true
      }

      val gh = c0.sys.gcHaltReg.toBoolean
      if (gh && !inStw) {
        inStw = true; windows += 1; start = cycle; ackAt = 0
        wRun = 0; wRunAfterAck = 0; wRunHijacked = 0; wFalls = 0
      }
      if (inStw) {
        val ack = c0.sys.io.syncIn.othersHalted.toBoolean
        if (ack && !lastAck) { ackRises += 1; ackRoseAt = cycle; if (ackAt == 0) ackAt = cycle }
        if (!ack && lastAck && gh) {
          ackFalls += 1; wFalls += 1
          val len = cycle - ackRoseAt
          if (minWithdrawn < 0 || len < minWithdrawn) minWithdrawn = len
          if (len > maxWithdrawn) maxWithdrawn = len
          // The acknowledgement is registered, so the term that ended it is
          // the one low NOW or a cycle ago; print all three.
          if (fallsLogged < 20) {
            fallsLogged += 1
            println(f"\n[ack withdrawn] cycle $cycle%,d after $len%,d high: core 1 halted=${c1.sys.io.halted.toBoolean} " +
                    f"flushed=${c1.sys.io.gcFlushDone.toBoolean} stopped=${c1.sys.io.coreStopped.toBoolean} " +
                    f"memIdle=${c1.memCtrl.io.debug.idle.toBoolean}")
          }
        }
        lastAck = ack
        val halted = c1.sys.io.halted.toBoolean
        val running = !c1.pipeline.fetch.io.frozen.toBoolean
        val hijack = c1.io.debugRamAddr.toInt != 0
        stwCycles += 1
        if (!halted) exemptCycles += 1
        if (hijack) hijackCycles += 1
        if (halted && running) {
          runHalted += 1; wRun += 1
          if (ackAt != 0) { runAfterAck += 1; wRunAfterAck += 1 }
          if (hijack) { runHijacked += 1; wRunHijacked += 1 }
        }
        if (ackAt != 0 && !c1.memCtrl.io.debug.idle.toBoolean) memAfterAck += 1
        if (!gh) {
          inStw = false; lastAck = false
          if (wRunAfterAck > maxRunAfterAck) maxRunAfterAck = wRunAfterAck
          if (windows <= 30)
            println(f"\n[STW $windows%2d] at $start%,d: ${cycle - start}%,d cycles, ack after " +
                    f"${if (ackAt == 0) -1 else ackAt - start}%,d, withdrawn $wFalls%d times; core 1 ran " +
                    f"$wRun%,d while halted, $wRunAfterAck%,d after the ack, " +
                    f"$wRunHijacked%,d with its read port taken")
        }
      }
    }

    val out = uart.toString
    println(f"\n=== $cycle%,d cycles, $windows stop-the-worlds ($stwCycles%,d cycles) ===")
    println(f"PROBE: core 1 executed while halted:          $runHalted%,d cycles")
    println(f"PROBE:   of those, after the acknowledgement: $runAfterAck%,d (worst window $maxRunAfterAck%,d)")
    println(f"PROBE:   of those, with its read port taken:  $runHijacked%,d (port taken $hijackCycles%,d cycles in all)")
    println(f"PROBE: core 1 memory transaction after ack:   $memAfterAck%,d cycles")
    println(f"PROBE: core 1 exempt (not halted) in a STW:   $exemptCycles%,d cycles")
    println(f"PROBE: acknowledgement rose $ackRises%d times, withdrawn $ackFalls%d times while held" +
            (if (ackFalls > 0) f" (after $minWithdrawn%d-$maxWithdrawn%d cycles)" else ""))

    def fail(why: String): Unit = { println(s"FAIL: $why"); System.exit(1) }
    if (!verdictPrefixes.exists(out.contains)) fail(s"no verdict line in $cycle cycles")
    if (windows == 0) fail("no stop-the-world happened, so nothing was tested")
    if (runAfterAck > 0)
      fail(f"core 1 executed $runAfterAck%,d cycles after the collector's acknowledgement")
    if (memAfterAck > 0)
      fail(f"core 1 had a memory transaction in flight $memAfterAck%,d cycles after the acknowledgement")
    if (!out.contains(okLine)) fail(s"the app did not print $okLine")
    println(s"PASS: $windows stop-the-worlds, core 1 never executed after the acknowledgement")
  }
}

/**
 * Two cores, BRAM, no stack cache: the same cluster as `JopSmpBramSim`. Here
 * the collector reads core 1's stack through the root port, which takes over
 * core 1's stack-RAM read address while it reads.
 *
 * Usage: sbt "Test / runMain jop.system.JopSmpStackLoopSim [maxCycles]"
 */
object JopSmpStackLoopSim extends App {
  val memSize = 64 * 1024
  val jopFilePath = SimApp.jop("Small", "SmpStackLoop")
  require(new java.io.File(jopFilePath).exists,
    s"$jopFilePath is missing -- build it with: make -C java/apps/Small APP_NAME=SmpStackLoop")
  val romData = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
  val ramData = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, memSize / 4)
  val maxCycles = if (args.nonEmpty) args(0).toInt else 20000000

  JopSimDefaults.config
    .compile {
      val dut = JopSmpTestHarness(2, romData, ramData, mainMemData, memSize = memSize)
      StackLoopProbe.expose(dut.cluster)
      dut
    }
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)
      StackLoopProbe.run(dut.clockDomain, dut.cluster, dut.io.uartTxValid, dut.io.uartTxData,
        maxCycles, Seq("SMPSTACKLOOP "), "SMPSTACKLOOP OK")
    }
}

/**
 * Two cores, SDRAM, WITH a stack cache (`wukongSdrSmpSim 2`, as built). Here
 * the collector reads core 1's stack from its spill region, and the
 * acknowledgement waits for core 1's dirty banks to be flushed there -- the
 * build item 165 was found on.
 *
 * Usage: sbt "Test / runMain jop.system.JopSmpStackLoopCacheSim [maxCycles] [app.jop]"
 *   The app defaults to SmpStackLoop. SmpGcTest's image measures the
 *   acknowledgement under the real two-core GC workload instead, and
 *   SmpDeepFlush's with core 1 parked ~100 frames deep, across every bank.
 */
object JopSmpStackLoopCacheSim extends App {
  val cpuCnt = 2
  val presetName = "wukongSdrSmpSim"
  val config = JopConfig.wukongSdrSmpSim(cpuCnt)
  val jsys = config.system
  val builtCfg = config.builtCoreConfig(jsys, jsys.coreConfig)
  require(builtCfg.stackConfig.cacheConfig.isDefined, s"$presetName has no stack cache as built")
  val appDir = BuildLayout.default.javaDir(presetName, Seq(cpuCnt.toString))
  val maxCycles = if (args.nonEmpty) args(0).toInt else 100000000
  val jopFilePath = if (args.length > 1) args(1) else s"$appDir/apps/Small/SmpStackLoop.jop"
  require(new java.io.File(jopFilePath).exists,
    s"$jopFilePath is missing -- build it with:\n" +
    s"""  make -C java JOP_PRESET="$presetName $cpuCnt" tools runtime\n""" +
    s"""  make -C java/apps/Small JOP_PRESET="$presetName $cpuCnt" APP_NAME=SmpStackLoop""")
  val romData = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
  val ramData = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)
  val imageWords = JopFileLoader.loadJopFile(jopFilePath).words.length
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, imageWords)
  // Each app's own verdict lines; the run ends on the first and passes on the OK.
  val (verdicts, okLine) = new java.io.File(jopFilePath).getName match {
    case "SmpGcTest.jop" =>
      (Seq("SMPGC OK", "SMPGC FAIL", "SMPGC STALLED", "SMPGC INCONCLUSIVE"), "SMPGC OK")
    case "SmpDeepFlush.jop" =>
      (Seq("SmpDeepFlush OK", "SmpDeepFlush FAIL", "SmpDeepFlush INCONCLUSIVE"), "SmpDeepFlush OK")
    case _ =>
      (Seq("SMPSTACKLOOP "), "SMPSTACKLOOP OK")
  }

  JopSimDefaults.config
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(jsys.clkFreq)))
    .compile {
      val dut = JopSmpSdramTestHarness(cpuCnt, romData, ramData, mainMemData, coreCfg = Some(builtCfg))
      StackLoopProbe.expose(dut.cluster)
      dut
    }
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val sdram = SdramModel(io = dut.io.sdram,
        layout = SdramDeviceInfo.layoutFor(dut.md), clockDomain = dut.clockDomain)
      for (i <- mainMemData.indices) {
        val w = mainMemData(i).toLong & 0xFFFFFFFFL
        for (b <- 0 until 4) sdram.write(i * 4 + b, ((w >> (8 * b)) & 0xFF).toByte)
      }
      dut.clockDomain.waitSampling(5)
      StackLoopProbe.run(dut.clockDomain, dut.cluster, dut.io.uartTxValid, dut.io.uartTxData,
        maxCycles, verdicts, okLine)
    }
}
