package jop.system

import spinal.core._
import spinal.core.sim._
import jop.utils.{JopFileLoader, JopSimDefaults}

/**
 * Sweep the method cache geometry and count what it actually does.
 *
 * WHY. Item 50 measured bytecode fill at 62.8 % of Kfl's stall, and stall at
 * ~52 % of cycles — so the method cache owns about a third of every cycle Kfl
 * executes, the largest single line item in the profile. Item 37 said the same
 * thing from transaction counts. Neither separates the THREE causes, which
 * have three different fixes:
 *
 *   capacity      — the working set does not fit          -> raise jpcWidth
 *   fragmentation — a 4-word method burns a 32-word block -> raise blockBits
 *   conflict      — FIFO evicted something still hot      -> change replacement
 *
 * This counts misses directly at `MethodCache.io.inCache` rather than inferring
 * them from stall cycles, so the answer does not depend on the memory backend.
 * BRAM is used deliberately: it makes each fill cheap and the run fast, and the
 * MISS COUNT is a property of the cache geometry, not of the memory behind it.
 * Multiply by the per-fill cost from item 50 to get the stall each geometry
 * would cost on real memory.
 *
 * `wordsFilled` matters as much as the miss count: with variable-size blocks a
 * miss on a big method costs far more than a miss on a small one, and
 * fragmentation shows up as words filled rising while misses stay flat.
 *
 *   sbt "Test/runMain jop.system.MethodCacheSweepSim"
 */
object MethodCacheSweepSim extends App {

  val romData = JopFileLoader.loadMicrocodeRom("asm/generated/mem_rom.dat")
  val ramData = JopFileLoader.loadStackRam("asm/generated/mem_ram.dat")
  val bramSize = 2 * 1024 * 1024
  val mainMemData =
    JopFileLoader.jopFileToMemoryInit("java/apps/JbeBench/JbeBench.jop", bramSize / 4)

  // clkMhz = 5 shrinks DoApp's calibrate-to-one-simulated-second by 20x so all
  // three benchmarks fit one run (see JopCoreLargeBramHarness.clkMhz).
  val CLK_MHZ = 5
  val MAX_CYCLES = sys.env.getOrElse("MCACHE_MAX_CYCLES", "90000000").toLong
  val benches = Seq("Kfl", "UdpIp", "Lift")

  /** (jpcWidth, blockBits). blockWords = 1 << (jpcWidth - 2 - blockBits). */
  val only = sys.env.get("MCACHE_ONLY").map(_.split(",").map(_.trim).toSet)
  val allGeometries = Seq(
    (11, 4),   // 2 KB, 16 x 32w  — TODAY
    (11, 5),   // 2 KB, 32 x 16w  — same size, finer blocks: isolates FRAGMENTATION
    (11, 6),   // 2 KB, 64 x  8w
    (12, 4),   // 4 KB, 16 x 64w  — more size, coarser blocks
    (12, 5),   // 4 KB, 32 x 32w  — more size, SAME block size: isolates CAPACITY
    (12, 6),   // 4 KB, 64 x 16w
    (13, 6),   // 8 KB, 64 x 32w  — same block size again
    (13, 7),   // 8 KB, 128 x 16w
    (14, 7)    // 16 KB, 128 x 32w
  )
  val geometries = only match {
    case Some(sel) => allGeometries.filter { case (j, b) => sel.contains(s"$j/$b") }
    case None      => allGeometries
  }

  case class Result(lookups: Long, misses: Long, words: Long, cycles: Long)

  def run(jpcWidth: Int, blockBits: Int): Map[String, Result] = {
    val out = scala.collection.mutable.Map[String, Result]()
    JopSimDefaults.config.compile {
      val d = JopCoreLargeBramHarness(romData, ramData, mainMemData, bramSize,
        acacheFieldBits = 2, clkMhz = CLK_MHZ, jpcWidth = jpcWidth, blockBits = blockBits)
      // Count at the cache itself. `find` is a one-cycle pulse on bcRd (method
      // invoke/return only) and `inCache` is the registered verdict, valid when
      // the lookup leaves S1 — so sample the verdict on the IDLE/S2 transition
      // rather than on the pulse.
      d.jopSystem.memCtrl.methodCache.io.find.simPublic()
      d.jopSystem.memCtrl.methodCache.io.inCache.simPublic()
      d
    }.doSim(s"mcache_${jpcWidth}_$blockBits", 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      var lookups, misses, words, cyc = 0L
      // inCache is a Reg written in S1. find sampled at T -> S1 runs at T+1 ->
      // the verdict is readable at T+2. Counting down beats decoding the enum.
      var pend = 0
      val uart = new StringBuilder
      val line = new StringBuilder
      var done = false
      val mc = dut.jopSystem.memCtrl.methodCache

      while (cyc < MAX_CYCLES && !done) {
        dut.clockDomain.waitSampling()
        cyc += 1

        if (mc.io.find.toBoolean) { lookups += 1; pend = 2 }
        else if (pend > 0) {
          pend -= 1
          if (pend == 0 && !mc.io.inCache.toBoolean) misses += 1
        }
        // Words pulled for a fill: a BMB command issued from a BC_* state.
        if (dut.io.bmbCmdValid.toBoolean && dut.io.bmbCmdReady.toBoolean &&
            MemProfile.group(dut.io.debugMemState.toInt) == "bytecode fill") words += 1

        if (dut.io.uartTxValid.toBoolean) {
          val c = dut.io.uartTxData.toInt
          if (c == 10) {
            val l = line.toString.trim
            benches.find(b => l.startsWith(b)).foreach { b =>
              out(b) = Result(lookups, misses, words, cyc)
              lookups = 0; misses = 0; words = 0; cyc = 0   // per-benchmark deltas
            }
            if (l.contains("JVM exit")) done = true
            line.clear()
          } else if (c >= 32 && c < 127) line.append(c.toChar)
          if (c >= 32 && c < 127) uart.append(c.toChar) else if (c == 10) uart.append('|')
        }
      }
      if (out.isEmpty)
        println(s"    NO BENCHMARK LINES after $cyc cycles. UART: '${uart.toString.take(300)}'")
    }
    out.toMap
  }

  val all = geometries.map { case (j, b) =>
    val blockWords = 1 << (j - 2 - b)
    val label = f"${1 << j}%5dB ${1 << b}%4d x ${blockWords}%3dw"
    println(s"\n>>> $label  (jpcWidth=$j blockBits=$b)")
    val r = try run(j, b) catch { case e: Throwable =>
      println(s"    FAILED: ${e.getClass.getSimpleName}: ${e.getMessage.take(160)}"); Map.empty[String, Result] }
    (label, j, b, r)
  }

  println("\n" + "=" * 96)
  println("METHOD CACHE SWEEP — misses counted at MethodCache.io.inCache, BRAM backend")
  println("=" * 96)
  for (bench <- benches) {
    println(f"\n== $bench")
    println(f"  ${"geometry"}%-22s ${"lookups"}%10s ${"misses"}%10s ${"miss%"}%7s ${"words filled"}%13s ${"w/miss"}%7s")
    val base = all.headOption.flatMap(_._4.get(bench))
    for ((label, _, _, r) <- all; res <- r.get(bench)) {
      val mp = if (res.lookups > 0) res.misses * 100.0 / res.lookups else 0.0
      val wpm = if (res.misses > 0) res.words.toDouble / res.misses else 0.0
      val delta = base.map(bs => f"  (${(res.words - bs.words) * 100.0 / bs.words}%+.1f%% words)").getOrElse("")
      println(f"  $label%-22s ${res.lookups}%10d ${res.misses}%10d ${mp}%6.1f%% ${res.words}%13d ${wpm}%7.1f$delta")
    }
  }
  println("\nReading it: misses falling with SIZE at fixed block size = capacity.")
  println("Words-filled falling with BLOCK COUNT at fixed size = fragmentation.")
  println("Neither moving = conflict, and the fix is replacement policy, not geometry.")
}
