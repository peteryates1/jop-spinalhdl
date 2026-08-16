/*
  Part of the JOP-SpinalHDL benchmark work; the workload it drives is
  JavaBenchEmbedded, Copyright (C) 2001-2008 Martin Schoeberl, GPLv3.
*/

package jbe;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

/**
 * Aggregate throughput scaling: run the same benchmark on EVERY core at once and
 * sum the work done.
 *
 * WHAT THIS ANSWERS. We can build 12 cores, but nothing has ever measured
 * whether 12 cores are FASTER at real work than one. The arithmetic makes the
 * question sharp: one core at 80 MHz does 7742 Kfl iterations/s, and a 12-core
 * build at 36 MHz does 4401/s on core 0 — so perfect scaling would be 52,812/s,
 * 6.8x. But all twelve share one memory controller through one arbiter, and Kfl
 * is memory-latency-bound, so the real figure is somewhere between 7742 and
 * 52,812 and nothing currently says where. That number is the entire
 * justification for core scaling, and it is presently an assumption
 * (current-status items 5, 11 and 31).
 *
 * METHOD. Every core runs a FIXED iteration count and records the microseconds
 * it took; core 0 then reports each core's rate and the sum. Two deliberate
 * choices:
 *
 *  - Fixed count rather than `Execute`'s calibrate-to-one-second loop, because
 *    calibration would have each core running a different amount of work and
 *    make the aggregate meaningless.
 *  - Only core 0 prints. The others would interleave on the UART, and the
 *    output is the measurement.
 *
 * HOLD THE CLOCK FIXED when sweeping core counts. On the EP4CGX150 the usable
 * clock falls with core count (80/60/50/36 MHz), so a naive sweep measures
 * cores AND clock together and cannot separate them. Build every point at the
 * lowest common clock.
 *
 * WHAT IT DOES NOT SEPARATE: every core allocates through one global lock, so a
 * flat curve may be lock contention rather than memory bandwidth. That is still
 * the honest answer to "does adding cores help on this workload", but it is not
 * by itself a verdict on the arbiter — see the allocation-profile work for
 * teasing those apart.
 */
public class Scale {

	/**
	 * Iterations per core. Sized so one core takes roughly a second at 36 MHz
	 * (Kfl measured 4401/s there), which is long enough to swamp the startup
	 * transient without making a 12-core sweep tedious.
	 */
	static final int ITERATIONS = 4096;

	static int cpuCnt;
	/** Microseconds each core took for ITERATIONS. Written by that core only. */
	static int[] micros;
	/** Set to 1 by each core when its result is stored. */
	static int[] done;

	static void wrInt(int val) {
		if (val < 0) { JVMHelp.wr('-'); val = -val; }
		boolean lead = false;
		for (int div = 1000000000; div > 0; div /= 10) {
			int d = (val / div) % 10;
			if (d != 0 || lead || div == 1) { JVMHelp.wr((char) ('0' + d)); lead = true; }
		}
	}

	/** Run the workload and record how long it took. */
	static void runOne(int id) {
		BenchKfl bench = new BenchKfl();
		int t0 = LowLevel.timeMicros();
		bench.test(ITERATIONS);
		int t1 = LowLevel.timeMicros();
		micros[id] = t1 - t0;
		done[id] = 1;
	}

	/**
	 * iterations/s from a microsecond duration.
	 *
	 * `ITERATIONS * 1000000` is 4.096e9 and overflows a signed int -- the first
	 * version of this returned -358 1/s, which is at least an obvious wrong
	 * answer rather than a plausible one. Done in long, which JOP supports
	 * (LongArithmetic is in the JVM suite); this runs once per core at the end
	 * of a run, so microcode long division costs nothing that matters.
	 */
	static int ratePerSec(int us) {
		if (us <= 0) return 0;
		return (int) (((long) ITERATIONS * 1000000L) / (long) us);
	}

	public static void main(String[] args) {
		int cpuId = Native.rdMem(Const.IO_CPU_ID);
		cpuCnt = Native.rdMem(Const.IO_CPUCNT);

		if (cpuId != 0) {
			// Arrays are allocated by core 0 before it releases anyone, so they
			// are visible by the time a secondary core gets here.
			runOne(cpuId);
			for (;;) { }        // park; core 0 owns the report
		}

		micros = new int[cpuCnt];
		done = new int[cpuCnt];

		JVMHelp.wr("Scale: cores ");
		wrInt(cpuCnt);
		JVMHelp.wr(" iterations ");
		wrInt(ITERATIONS);
		JVMHelp.wr(" (Kfl)\r\n");

		Native.wr(1, Const.IO_SIGNAL);   // release the other cores
		runOne(0);

		// Bounded wait: a core that never finishes must show up as a missing
		// row, not as a hung benchmark.
		int spins = 0;
		boolean all = false;
		while (!all && spins < 200000000) {
			all = true;
			for (int i = 1; i < cpuCnt; i++) if (done[i] == 0) all = false;
			spins++;
		}

		int total = 0;
		for (int i = 0; i < cpuCnt; i++) {
			int r = (done[i] != 0 || i == 0) ? ratePerSec(micros[i]) : 0;
			total += r;
			JVMHelp.wr("  core ");
			wrInt(i);
			JVMHelp.wr(": ");
			wrInt(micros[i]);
			JVMHelp.wr(" us -> ");
			wrInt(r);
			JVMHelp.wr(" 1/s");
			if (done[i] == 0 && i != 0) JVMHelp.wr("  (DID NOT FINISH)");
			JVMHelp.wr("\r\n");
		}

		JVMHelp.wr("AGGREGATE ");
		wrInt(total);
		JVMHelp.wr(" 1/s over ");
		wrInt(cpuCnt);
		JVMHelp.wr(" cores\r\n");
		JVMHelp.wr("Scale done\r\n");
	}
}
