package jop.system

import spinal.core._
import spinal.core.sim._
import jop.utils.{JopFileLoader, JopSimDefaults}
import org.scalatest.funsuite.AnyFunSuite

/**
 * Verify the IO_PERFCNT hardware counters against an independent tally, in
 * simulation, BEFORE trusting them on a board.
 *
 * The counters exist because DDR2 cannot be simulated (`Ddr2BlackBox` is
 * ALTMEMPHY vendor IP), so the A-E115FB can only be profiled on hardware. But
 * a counter that is wrong on hardware is worse than no counter at all: there
 * is nothing to check it against there. Here there is — the testbench watches
 * the same `memBusy` and `debugMemState` the RTL does and must agree exactly.
 *
 * Exact agreement is the bar, not approximate: both count the same signal on
 * the same clock. Any drift means the hardware categorisation disagrees with
 * `MemProfile.group`, which would silently make the hardware and simulation
 * tables incomparable — the one thing this whole exercise is for.
 */
class PerfCounterVerifySim extends AnyFunSuite {

  test("IO_PERFCNT counters match an independent tally of the same signals") {
    val romData = JopFileLoader.loadMicrocodeRom("asm/generated/mem_rom.dat")
    val ramData = JopFileLoader.loadStackRam("asm/generated/mem_ram.dat")
    val bramSize = 2 * 1024 * 1024
    val mainMemData =
      JopFileLoader.jopFileToMemoryInit("java/apps/JbeBench/JbeBench.jop", bramSize / 4)

    // Same category order as Sys.perf.catOf.
    val IDX_CYCLES = 0; val IDX_STALL = 1
    val catIdx = Map("idle/direct" -> 2, "bytecode fill" -> 3, "statics" -> 4,
      "bounds check" -> 5, "handle deref" -> 6, "element" -> 7,
      "A$ line fill" -> 8, "GC copy" -> 9)

    JopSimDefaults.config.compile {
      val d = JopCoreLargeBramHarness(romData, ramData, mainMemData, bramSize,
        acacheFieldBits = 2, perfCounters = true, clkMhz = 5)
      d.jopSystem.sys.perf.counters.foreach(_.simPublic())
      d
    }.doSim("perfcnt_verify", 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      val hw = dut.jopSystem.sys.perf.counters
      val expect = scala.collection.mutable.Map[Int, Long]().withDefaultValue(0L)
      val CYCLES = 3000000

      // Compare DELTAS, not absolutes. Startup.java ends boot with
      // `Native.wr(-1, Const.IO_PERFCNT)`, which this implementation honours as
      // "reset all" -- so the counters legitimately restart partway through and
      // an absolute comparison against a tally started at cycle 0 is measuring
      // the reset, not the counters. (That write used to be a no-op, which is
      // why nothing noticed before.) Let boot finish, snapshot, then compare.
      dut.clockDomain.waitSampling(4000000)
      val base = (0 until hw.length).map(i => hw(i).toBigInt)

      for (_ <- 0 until CYCLES) {
        dut.clockDomain.waitSampling()
        expect(IDX_CYCLES) += 1
        if (dut.io.memBusy.toBoolean) {
          expect(IDX_STALL) += 1
          catIdx.get(MemProfile.group(dut.io.debugMemState.toInt)).foreach(i => expect(i) += 1)
        }
      }

      val names = Map(IDX_CYCLES -> "cycles", IDX_STALL -> "stall") ++ catIdx.map(_.swap)
      var bad = 0
      for (i <- 0 until hw.length) {
        val got = hw(i).toBigInt - base(i)
        val want = expect(i)
        val label = names.getOrElse(i, s"cat$i")
        // Tolerance of ONE, and no more. Snapshotting a synchronous counter
        // from a testbench is half a cycle out of phase with starting a tally:
        // the snapshot already includes the edge just passed, while the tally
        // begins at the next. Any counter that is live at that instant reads +1.
        // Anything beyond 1 is a real disagreement in categorisation.
        val diff = (got - want).abs
        if (diff > 1) { println(f"  MISMATCH $label%-14s hw=$got%,d tb=$want%,d"); bad += 1 }
        else if (want > 0) println(f"  ok       $label%-14s $got%,d" + (if (diff == 1) "  (+1 phase)" else ""))
      }
      assert(bad == 0, s"$bad counter(s) disagree with the independent tally by more than the 1-cycle phase")
    }
  }
}
