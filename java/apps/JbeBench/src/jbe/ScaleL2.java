/*
  Part of the JOP-SpinalHDL benchmark work; derived from Scale.java, which in
  turn drives JavaBenchEmbedded, Copyright (C) 2001-2008 Martin Schoeberl, GPLv3.
*/

package jbe;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

/**
 * DRAM L2 capacity probe, multicore: sweep the per-core working set across the
 * L2's size and see where, if anywhere, capacity starts to matter.
 *
 * WHY THIS EXISTS. `l2SetCount` was measured single-core on two boards and five
 * bitstreams: 4 KB and 32 KB of L2 gave BIT-IDENTICAL Kfl/UdpIp/Lift. That says
 * nothing about multicore, where the L2 is SHARED behind the BMB arbiter and N
 * cores put N working sets through it. The obvious next step -- run Kfl on N
 * cores -- is blocked: every JBE workload is built on static state (Kfl's BBSys
 * alone has 52 statics), so N cores mutate one state machine rather than running
 * N instances. Scale.java records that the first attempt at exactly that never
 * terminated on 4 cores.
 *
 * WHY SCALE ITSELF CANNOT ANSWER IT. `Scale` walks 16384 words = 64 KB per core.
 * That exceeds BOTH L2 sizes under test (32 KB and 4 KB), so both thrash equally
 * and the comparison is blind by construction. It was built to measure DRAM
 * bandwidth and MSHR scaling, and it is the right tool for that -- just not for
 * capacity.
 *
 * WHAT THIS DOES DIFFERENTLY. Same private-memwalk kernel, same no-sharing
 * property, but sweeping the working set from well inside a 4 KB L2 to well
 * outside a 32 KB one. Read the SHAPE of the curve, not any single row:
 *
 *   - if a 4 KB and a 32 KB L2 track each other at every size, capacity is
 *     irrelevant multicore too, and the default can drop to 64 sets
 *   - if they diverge once the aggregate working set passes 4 KB but not
 *     32 KB, that is the capacity effect, and the default must stay size- or
 *     core-count-dependent
 *
 * The work per phase is held CONSTANT (TOTAL_ACC word-accesses), so rates are
 * directly comparable across sizes -- a smaller set simply gets more passes.
 *
 * WHAT IT STILL DOES NOT MODEL: a real application's instruction mix. It is a
 * data probe, and `docs/` is emphatic that JbeScale-derived numbers must be
 * checked against DoApp before being acted on. The same caution applies here.
 * This answers "does L2 capacity matter to the memory system under N cores",
 * not "how much faster is real code".
 */
public class ScaleL2 {

	/** Per-core working sets in words. 4 bytes/word: 1 KB, 4 KB, 16 KB, 64 KB. */
	static final int[] SIZES = { 256, 1024, 4096, 16384 };

	/** Largest of SIZES -- allocated once per core, then a prefix is walked. */
	static final int MAXWORDS = 16384;

	/**
	 * Word-accesses per phase, held constant so every size does the same work
	 * and the rates are comparable. Matches Scale's 16384 x 24.
	 */
	static final int TOTAL_ACC = 393216;

	/**
	 * Stride, reduced modulo the working set. Odd, so it stays coprime with any
	 * power-of-two size and a pass still touches every element; larger than a
	 * cache line so consecutive accesses never share one.
	 */
	static final int STRIDE = 517;

	static int cpuCnt;
	static int nSizes;

	/** micros[phase * cpuCnt + core], written by that core only. */
	static int[] micros;
	/** Per-core checksum of the last phase -- correctness, not speed. */
	static int[] checks;
	/** goPhase[0]: the phase core 0 has released. Polled by the others. */
	static int[] goPhase;
	/** donePhase[core]: last phase that core finished, plus one. */
	static int[] donePhase;

	static int sink;

	static void wrInt(int val) {
		if (val < 0) { JVMHelp.wr('-'); val = -val; }
		boolean lead = false;
		for (int div = 1000000000; div > 0; div /= 10) {
			int d = (val / div) % 10;
			if (d != 0 || lead || div == 1) { JVMHelp.wr((char) ('0' + d)); lead = true; }
		}
	}

	/** One phase: stride-walk the first `size` words of a PRIVATE buffer. */
	static void runPhase(int id, int phase, int size, int[] buf) {
		int iters = TOTAL_ACC / size;
		if (iters < 1) iters = 1;
		int stride = STRIDE % size;
		if (stride == 0) stride = 1;

		int acc = 0;
		int t0 = LowLevel.timeMicros();
		for (int it = 0; it < iters; it++) {
			int idx = 0;
			for (int i = 0; i < size; i++) {
				buf[idx] = buf[idx] + idx + it;
				acc += buf[idx];
				idx += stride;
				if (idx >= size) idx -= size;
			}
		}
		int t1 = LowLevel.timeMicros();

		sink = acc;
		checks[id] = acc;
		micros[phase * cpuCnt + id] = t1 - t0;
	}

	/** Thousands of word-accesses per second, from a microsecond duration. */
	static int ratePerSec(int size, int us) {
		if (us <= 0) return 0;
		int iters = TOTAL_ACC / size;
		if (iters < 1) iters = 1;
		return (int) (((long) iters * (long) size * 1000L) / (long) us);
	}

	/** Every core runs every phase; core 0 gates the start of each one. */
	static void runAll(int id) {
		int[] buf = new int[MAXWORDS];    // private to this core
		for (int p = 0; p < nSizes; p++) {
			if (id != 0) {
				// Bounded: a core that never sees its release shows up as a
				// missing row rather than hanging the run.
				int spins = 0;
				while (goPhase[0] < p && spins < 200000000) spins++;
			}
			runPhase(id, p, SIZES[p], buf);
			donePhase[id] = p + 1;
		}
	}

	public static void main(String[] args) {
		int cpuId = Native.rdMem(Const.IO_CPU_ID);
		cpuCnt = Native.rdMem(Const.IO_CPUCNT);
		nSizes = SIZES.length;

		if (cpuId != 0) {
			runAll(cpuId);
			for (;;) { }              // park; core 0 owns the report
		}

		micros = new int[nSizes * cpuCnt];
		checks = new int[cpuCnt];
		goPhase = new int[1];
		donePhase = new int[cpuCnt];
		goPhase[0] = -1;

		JVMHelp.wr("ScaleL2: cores ");
		wrInt(cpuCnt);
		JVMHelp.wr(" sweeping private working set (constant work per phase)\r\n");

		Native.wr(1, Const.IO_SIGNAL);   // release the other cores

		int[] buf = new int[MAXWORDS];
		for (int p = 0; p < nSizes; p++) {
			goPhase[0] = p;               // release this phase
			runPhase(0, p, SIZES[p], buf);
			donePhase[0] = p + 1;
			// Wait for every core to finish the phase before starting the next,
			// so all cores are on the same working set while it is timed.
			int spins = 0;
			boolean all = false;
			while (!all && spins < 200000000) {
				all = true;
				for (int i = 1; i < cpuCnt; i++) if (donePhase[i] < p + 1) all = false;
				spins++;
			}
		}

		for (int p = 0; p < nSizes; p++) {
			int total = 0;
			for (int i = 0; i < cpuCnt; i++) {
				total += ratePerSec(SIZES[p], micros[p * cpuCnt + i]);
			}
			JVMHelp.wr("  set ");
			wrInt(SIZES[p] * 4);
			JVMHelp.wr(" B/core  aggregate ");
			wrInt(total);
			JVMHelp.wr(" kacc/s  (");
			wrInt(SIZES[p] * 4 * cpuCnt);
			JVMHelp.wr(" B total)\r\n");
		}

		// Same deterministic walk on every core over its own buffer, so the
		// last-phase checksums must agree. A build that violates timing can
		// report believable rates and wrong arithmetic; this makes that visible.
		boolean same = true;
		for (int i = 1; i < cpuCnt; i++) if (checks[i] != checks[0]) same = false;
		JVMHelp.wr("CHECK ");
		wrInt(checks[0]);
		JVMHelp.wr(same ? "  all cores agree\r\n" : "  MISMATCH\r\n");
		JVMHelp.wr("ScaleL2 done\r\n");
	}
}
