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
	 * Words per core's PRIVATE working set. 16384 words = 64 KB, far beyond the
	 * object cache (16 entries x 16 fields) and array cache (16 x 4 elements),
	 * so every access goes to shared memory -- which is the point.
	 */
	static final int WORDS = 16384;

	/** Passes over the working set. Sized for ~1 s per core at 36 MHz. */
	static final int ITERATIONS = 24;

	/**
	 * Stride in words, chosen odd and larger than a cache line so consecutive
	 * accesses never share one, and coprime with WORDS so a pass still touches
	 * every element.
	 */
	static final int STRIDE = 517;

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

	/**
	 * The workload: stride-walk a PRIVATE array, read-modify-write.
	 *
	 * NOT a JBE benchmark, and that is deliberate. Every JBE workload is
	 * single-core code built on static state -- Kfl's BBSys alone has 52 statics,
	 * Triac 44, and even BenchSieve keeps its flags array static. Running one of
	 * them on N cores does not give N independent instances; it gives N cores
	 * mutating one state machine. The first version of this harness did exactly
	 * that and the 4-core run never terminated. That was a bug here, not a
	 * finding about the hardware.
	 *
	 * So this measures what the scaling question actually needs: aggregate
	 * MEMORY-SYSTEM throughput, with each core on its own working set and no
	 * sharing at all. What it deliberately does not model is a real application's
	 * instruction mix -- for that, run DoApp on one core and quote it separately.
	 */
	static void runOne(int id) {
		int[] buf = new int[WORDS];      // private to this core
		int acc = 0;
		int t0 = LowLevel.timeMicros();
		for (int it = 0; it < ITERATIONS; it++) {
			int idx = 0;
			for (int i = 0; i < WORDS; i++) {
				buf[idx] = buf[idx] + idx + it;
				acc += buf[idx];
				idx += STRIDE;
				if (idx >= WORDS) idx -= WORDS;
			}
		}
		int t1 = LowLevel.timeMicros();
		sink = acc;                       // keep the work live
		checks[id] = acc;                 // ...and make it verifiable, see below
		micros[id] = t1 - t0;
		done[id] = 1;
	}

	/** Consumed so the optimiser cannot discard the loop. */
	static int sink;

	/**
	 * Per-core accumulator, so the benchmark checks itself.
	 *
	 * `acc` was already computed and thrown away into `sink`, which measured
	 * throughput and proved nothing about correctness. Every core walks an
	 * IDENTICAL deterministic sequence over its OWN private buffer, so all
	 * cpuCnt values must be bit-identical -- to each other, and to the
	 * single-core value on any other build. Printing it costs one array write
	 * per core and turns each run into a correctness check.
	 *
	 * This exists because a build that VIOLATED setup timing by 3.059 ns still
	 * produced perfectly plausible rates with all eight cores in lockstep. A
	 * coherent rate only shows the machine kept running; it says nothing about
	 * whether the arithmetic was right, and silent corruption is exactly how a
	 * marginal path fails. Now a mismatch is visible.
	 */
	static int[] checks;

	/**
	 * iterations/s from a microsecond duration.
	 *
	 * `ITERATIONS * 1000000` is 4.096e9 and overflows a signed int -- the first
	 * version of this returned -358 1/s, which is at least an obvious wrong
	 * answer rather than a plausible one. Done in long, which JOP supports
	 * (LongArithmetic is in the JVM suite); this runs once per core at the end
	 * of a run, so microcode long division costs nothing that matters.
	 */
	/** Thousands of word-accesses per second. */
	static int ratePerSec(int us) {
		if (us <= 0) return 0;
		return (int) (((long) ITERATIONS * (long) WORDS * 1000L) / (long) us);
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
		checks = new int[cpuCnt];

		JVMHelp.wr("Scale: cores ");
		wrInt(cpuCnt);
		JVMHelp.wr(" words ");
		wrInt(WORDS);
		JVMHelp.wr(" x ");
		wrInt(ITERATIONS);
		JVMHelp.wr(" passes (private memwalk)\r\n");

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
			JVMHelp.wr(" kacc/s");
			if (done[i] == 0 && i != 0) JVMHelp.wr("  (DID NOT FINISH)");
			JVMHelp.wr("\r\n");
		}

		JVMHelp.wr("AGGREGATE ");
		wrInt(total);
		JVMHelp.wr(" kacc/s over ");
		wrInt(cpuCnt);
		JVMHelp.wr(" cores\r\n");

		// Correctness, not speed: every core ran the same deterministic walk over
		// its own buffer, so all these must agree. Compare CHECK across builds
		// too -- it is the same value at any core count and any clock, so it
		// detects a marginal-timing build that computes wrong answers while
		// reporting believable rates.
		boolean same = true;
		for (int i = 1; i < cpuCnt; i++) if (checks[i] != checks[0]) same = false;
		JVMHelp.wr("CHECK ");
		wrInt(checks[0]);
		JVMHelp.wr(same ? "  all cores agree\r\n" : "  MISMATCH\r\n");
		if (!same) {
			for (int i = 0; i < cpuCnt; i++) {
				JVMHelp.wr("  core ");
				wrInt(i);
				JVMHelp.wr(" check ");
				wrInt(checks[i]);
				JVMHelp.wr("\r\n");
			}
		}
		JVMHelp.wr("Scale done\r\n");
	}
}
