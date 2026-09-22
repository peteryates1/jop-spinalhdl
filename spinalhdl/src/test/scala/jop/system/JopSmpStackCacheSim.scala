package jop.system

import jop.config._
import spinal.core._
import spinal.core.sim._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import jop.memory.SdramDeviceInfo
import jop.utils.{JopFileLoader, TestHistory, JopSimDefaults}
import jop.generate.BuildLayout
import java.io.PrintWriter

/**
 * SMP **and** a stack cache, together — the combination nothing had ever run.
 *
 * WHY IT NEEDS ITS OWN VEHICLE. A collector scans every OTHER core's stack,
 * because a core's stack is private RAM. Without a stack cache that is a real
 * third port on the 256-word stack RAM. WITH one, the port answers only from
 * the 64-word scratch RAM with the index truncated to 6 bits, so the collector
 * asking for stack word 200 is handed scratch word 8 — microcode variables and
 * the constant pool (status item 133). The combination was first REFUSED at
 * elaboration (`a1c74d3`) and then MADE TO WORK (`6249099`) by flushing a
 * halted core's dirty banks to its spill region, so the collector can read the
 * stack with ordinary loads. Neither commit had a test: `wukongSdrSmp(n)` is
 * the only preset carrying both, and no simulation elaborated it.
 *
 * WHAT PASSING MEANS. `SmpGcTest`'s STACKROOT probe parks a reference on core
 * 1's stack, holds it there, and runs minor collections from core 0. On a
 * cached build core 0 can only see that reference if
 *
 *   - the halt handshake waits for core 1's banks to be FLUSHED, and
 *   - `STACK_SPILL_BASE` names the words core 1 actually spilled to.
 *
 * If either is wrong the reference is missed and the object is freed while
 * live, which the probe reports as `LOST`. The second condition is also pinned
 * structurally by `jop.config.StackSpillGeometryTest`, which is where the
 * 4x spill-base mismatch was caught — Const.java said word 8,380,416 (the
 * 32 MB chip) and the RTL spilled to 2,088,960 (the 8 MB the design is built
 * for). A run of this sim against that mismatch proves nothing about the
 * flush, because every cross-core root read misses for an unrelated reason.
 *
 * Usage: sbt "Test / runMain jop.system.JopSmpStackCacheSdramSim [cpuCnt] [maxCycles] [app.jop]"
 */
object JopSmpStackCacheSdramSim extends App {
  val cpuCnt = if (args.length > 0) args(0).toInt else 2

  // AS BUILT, not as wished. `coreConfig.useStackCache` is false on every
  // preset that actually has one — JopTop decides it — so asking the preset
  // directly would elaborate a plain SMP cluster and quietly pass (item 130).
  //
  // `wukongSdrSmpSim`, not `wukongSdrSmp`: identical but for a 256 KB heap.
  // On the board's 8 MB the nursery outlives SmpGcTest's 20,000-allocation
  // budget, so no minor GC runs, the probe's reference survives trivially, and
  // the run passes having exercised nothing. See `minors` below.
  val presetName = "wukongSdrSmpSim"
  val config = JopConfig.wukongSdrSmpSim(cpuCnt)
  val jsys = config.system
  val builtCfg = config.builtCoreConfig(jsys, jsys.coreConfig)
  val sc = builtCfg.stackConfig.cacheConfig.getOrElse(
    throw new RuntimeException(s"$presetName has no stack cache in this build — this sim would prove nothing"))
  require(cpuCnt >= 2, "the cross-core root scan needs at least two cores")

  // The image must be linked for THIS configuration: Const.STACK_SPILL_BASE is
  // in it, and an image built for another preset would read a different region.
  val jopFilePath = if (args.length > 2) args(2)
    else s"${BuildLayout.default.javaDir(presetName, Seq(cpuCnt.toString))}/apps/SmpGcTest/SmpGcTest.jop"
  val romFilePath = MicrocodePaths.simulationRom
  val ramFilePath = MicrocodePaths.simulationRam
  val logFilePath = s"build/sim-logs/smp_stackcache_sdram_${cpuCnt}core.log"

  require(new java.io.File(jopFilePath).exists,
    s"$jopFilePath is missing — build it with:\n" +
    s"""  make -C java JOP_PRESET="$presetName $cpuCnt" tools runtime\n""" +
    s"""  make -C java/apps/SmpGcTest JOP_PRESET="$presetName $cpuCnt"""")

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val imageWords = JopFileLoader.loadJopFile(jopFilePath).words.length
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, imageWords)

  val memWords = (builtCfg.memConfig.mainMemSize / 4).toInt
  val spillWords = builtCfg.memConfig.stackRegionWordsPerCore
  println(s"=== SMP + stack cache, $cpuCnt cores ($presetName $cpuCnt, as built) ===")
  println(s"Image:        $jopFilePath ($imageWords words)")
  println(f"Memory:       $memWords%,d words (${builtCfg.memConfig.mainMemSize.toLong / 1024 / 1024} MB), " +
          f"heap ends at ${builtCfg.memConfig.usableMemWords(cpuCnt)}%,d")
  println(f"Spill core 0: ${sc.spillBaseAddr}%,d (0x${sc.spillBaseAddr.toHexString}), $spillWords%,d words per core")
  for (c <- 1 until cpuCnt)
    println(f"Spill core $c: ${sc.spillBaseAddr - c * spillWords}%,d")
  println(s"Log file:     $logFilePath")

  val run = TestHistory.startRun(s"JopSmpStackCacheSdramSim-$cpuCnt", "sim-verilator",
    jopFilePath, romFilePath, ramFilePath)

  JopSimDefaults.config
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(jsys.clkFreq)))
    .compile(JopSmpSdramTestHarness(cpuCnt, romData, ramData, mainMemData, coreCfg = Some(builtCfg)))
    .doSim { dut =>
      val log = { new java.io.File(logFilePath).getParentFile.mkdirs(); new PrintWriter(logFilePath) }
      val uart = new StringBuilder
      def logLine(msg: String): Unit = { log.println(msg); log.flush() }

      logLine(s"=== SMP + stack cache, $cpuCnt cores, spill base ${sc.spillBaseAddr} ===")

      dut.clockDomain.forkStimulus(10)

      val sdramModel = SdramModel(io = dut.io.sdram,
        layout = SdramDeviceInfo.layoutFor(dut.md), clockDomain = dut.clockDomain)
      for (wordIdx <- mainMemData.indices) {
        val word = mainMemData(wordIdx).toLong & 0xFFFFFFFFL
        val byteAddr = wordIdx * 4
        sdramModel.write(byteAddr + 0, ((word >>  0) & 0xFF).toByte)
        sdramModel.write(byteAddr + 1, ((word >>  8) & 0xFF).toByte)
        sdramModel.write(byteAddr + 2, ((word >> 16) & 0xFF).toByte)
        sdramModel.write(byteAddr + 3, ((word >> 24) & 0xFF).toByte)
      }
      dut.clockDomain.waitSampling(5)

      // SmpGcTest on SDRAM is far slower than the BRAM twin. The STACKROOT
      // verdict lands well before the rounds finish, so the run is allowed to
      // stop on it — a cap reached BEFORE the verdict is reported as
      // INCONCLUSIVE, not as a pass.
      val maxCycles = if (args.length > 1) args(1).toInt else 300000000
      var cycle = 0
      var done = false
      var stackRootSeen = false
      var lastMark = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        if (dut.io.excFired.toBoolean)
          logLine(f"[$cycle%10d] EXCEPTION PC=${dut.io.pc(0).toInt}%04x JPC=${dut.io.jpc(0).toInt}%04x")

        for (i <- 0 until cpuCnt if dut.io.abFire(i).toBoolean)
          logLine(f"[$cycle%10d] AB FAULT core $i idx=${dut.io.abIndex(i).toLong} " +
                  f"len=${dut.io.abLength(i).toLong} handle=${dut.io.abHandle(i).toLong}")

        if (dut.io.uartTxValid.toBoolean) {
          val ch = dut.io.uartTxData.toInt
          uart.append(if (ch >= 32 && ch < 127) ch.toChar else if (ch == 10) '\n' else '.')
          print(if (ch >= 32 && ch < 127) ch.toChar else if (ch == 10) '\n' else '.')
          val s = uart.toString
          if (s.length > lastMark) {
            if (s.contains("STACKROOT")) stackRootSeen = true
            // Any terminal verdict ends the run; SmpGcTest prints exactly one.
            if (s.contains("SMPGC OK") || s.contains("SMPGC FAIL") ||
                s.contains("SMPGC STALLED") || s.contains("SMPGC INCONCLUSIVE")) done = true
            lastMark = s.length
          }
        }

        if (cycle % 5000000 == 0) {
          val pcs = (0 until cpuCnt).map(i => f"C$i:PC=${dut.io.pc(i).toInt}%04x").mkString(" ")
          println(f"\n[$cycle%10d] $pcs")
        }
      }

      log.println(s"\n--- UART ---\n${uart.toString}")
      log.close()

      val out = uart.toString
      println(f"\n\n=== complete: $cycle%,d cycles ===")
      println(s"Log written to: $logFilePath")

      def fail(why: String): Unit = { run.finish("FAIL", why); println(s"FAIL: $why"); System.exit(1) }

      // THE STACK-ROOT VERDICT FIRST. "SMPGC OK" can be reached without the
      // probe ever running (it is gated on `publishers >= 1`), so a pass that
      // never printed STACKROOT says nothing about the cross-core scan — which
      // is the entire point of this sim.
      if (!stackRootSeen)
        fail(s"STACKROOT probe never ran in $cycle cycles — nothing exercised the cross-core scan")
      if (out.contains("LOST (other core's stack is NOT scanned)"))
        fail("STACKROOT LOST: the collector did not see the reference held on core 1's stack — " +
             "a halted core's banks are not reaching its spill region, or not at STACK_SPILL_BASE")
      if (!out.contains("OK (other core's stack IS scanned)"))
        fail("STACKROOT printed neither OK nor LOST")

      // A REFERENCE THAT SURVIVED NO COLLECTION PROVES NOTHING. `churnUntilMinor`
      // gives up after 20,000 allocations, so on a heap large enough to absorb
      // them the probe reports its object intact having never been at risk —
      // and prints OK. The minor count is on the same line, so demand it.
      val minors = raw"STACKROOT minors (-?\d+)".r.findFirstMatchIn(out).map(_.group(1).toInt)
      minors match {
        case None => fail("STACKROOT line carried no minor count")
        case Some(n) if n < 1 =>
          fail(s"STACKROOT ran $n minor GCs — the reference was never at risk, so OK means nothing. " +
               "The nursery is absorbing SmpGcTest's whole allocation budget; shrink the heap.")
        case Some(n) => println(s"STACKROOT: $n minor GCs with the reference held on core 1's stack")
      }

      if (out.contains("SMPGC FAIL")) fail("SmpGcTest reported SMPGC FAIL")
      if (out.contains("SMPGC STALLED")) fail("SmpGcTest reported SMPGC STALLED")
      if (out.contains("SMPGC INCONCLUSIVE")) fail("SmpGcTest reported SMPGC INCONCLUSIVE (nothing exercised)")
      if (!out.contains("SMPGC OK")) fail(s"no SMPGC verdict within $maxCycles cycles")

      run.finish("PASS", s"$cpuCnt cores, stack cache, STACKROOT OK + SMPGC OK, $cycle cycles")
      println(s"PASS: $cpuCnt cores with a stack cache — cross-core roots scanned, SMPGC OK")
    }
}
