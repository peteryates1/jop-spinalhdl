package test;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.GC;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

/**
 * WHERE DOES CORE 1 STOP? — status item 133.
 *
 * `SmpGcTest` hangs on the Wukong SMP + stack-cache build with core 0 spinning
 * on `while (stackProbeReady == 0)`, the flag core 1 sets five statements after
 * being released. `SmpCacheTest` PASSES on the same bitstream, so core 1 runs
 * and its writes reach core 0. What is unknown is how far into those five
 * statements core 1 gets.
 *
 * WHY THIS AND NOT A PER-CORE UART. Only core 0 has a console, and the obvious
 * fix is `perCoreConfigs` + `jp1_txd` out the J11 header. But NO preset has
 * ever set that and NO constraint file has ever assigned those pins, so the
 * per-core UART path is unexercised RTL — debugging a hang THROUGH untested
 * output is two unknowns, not one. `SmpCacheTest` already proved the mechanism
 * this uses instead: core 1 writes a static, core 0 reads it. No new bitstream,
 * no new RTL.
 *
 * THE HEAP STATE IS PART OF THE REPRODUCTION. SmpGcTest tenures and runs two
 * minor GCs on core 0 BEFORE releasing core 1, so core 1's first allocation
 * happens against a nursery that is already partly consumed. The leading
 * hypothesis is that this allocation triggers a collection which must halt
 * core 0 — and that the stack-cache flush added for item 133 never completes,
 * because halting a core may stall the very path its flush DMA needs. Release
 * core 1 into a FRESH heap and that would not reproduce, so the churn below is
 * deliberate, not decoration.
 *
 * READING THE RESULT. `step` is the last point core 1 reached:
 *
 *   0  never ran, or its write is invisible (SmpCacheTest says otherwise)
 *   1  running, before any allocation
 *   2  survived `new Young()`   <- if it stops at 1, the allocator is the fault
 *   3  survived Native.getSP()
 *   4  survived Native.toInt()
 *   5  set the ready flag -- core 0 should have seen it
 *
 * Core 0 prints `step` as it changes and gives up after a bounded spin rather
 * than hanging, so a failure produces a number instead of silence.
 */
public class SmpHangProbe implements Runnable {

	static class Young {
		int magic;
		int p0, p1, p2, p3, p4;
	}

	/** SmpGcTest's tenured live set, reproduced. BISECT STEP 2: with only
	 *  garbage allocated, a minor GC traces nothing and moves nothing; with
	 *  these live across it, the collector does real work -- and on a
	 *  multi-core build that includes scanning the OTHER core's roots, which
	 *  is the path item 133 changed. */
	static final int HOLDERS = 24;
	static class Holder { Object ref; int slot; }
	static Holder[] holders;
	static int[] liveTick, pubStep, pubRound, pubSlot, pubExcCnt, pubExcStep;

	/** Last point core 1 reached. Volatile: core 0 polls it. */
	static volatile int step;
	/** The flag SmpGcTest hangs on, reproduced exactly. */
	static volatile int ready;
	/** Core 1's view, published for core 0 to print. */
	static volatile int probeSp, probeHandle;
	/** Core 1's allocation count -- proof it is still running. */
	static volatile int c1Allocs;

	static int cpuId;

	static void wrInt(int v) {
		JVMHelp.wr(Integer.toString(v));
	}

	static boolean generational() {
		return GC.nurseryTop != GC.nurseryBase;
	}

	/** Allocate until `wanted` minor GCs are observed, or the budget runs out. */
	static int churnUntilMinor(int budget, int wanted) {
		if (!generational()) return 0;
		int before = GC.nurseryAllocPtr;
		int seen = 0;
		for (int i = 0; i < budget; i++) {
			Object junk = new Young();
			if (junk == null) return seen;
			int now = GC.nurseryAllocPtr;
			if (now > before) {
				seen++;
				before = now;
				if (seen >= wanted) return seen;
			} else {
				before = now;
			}
		}
		return seen;
	}

	static void core0() {
		JVMHelp.wr("SmpHangProbe: cores ");
		wrInt(Native.rdMem(Const.IO_CPUCNT));
		JVMHelp.wr(generational() ? ", generational\r\n" : ", CLASSIC GC (probe is weaker)\r\n");

		// Reproduce SmpGcTest's pre-release heap state -- see the class comment.
		int cpuCnt = Native.rdMem(Const.IO_CPUCNT);
		liveTick = new int[cpuCnt];
		pubStep = new int[cpuCnt];
		pubRound = new int[cpuCnt];
		pubSlot = new int[cpuCnt];
		pubExcCnt = new int[cpuCnt];
		pubExcStep = new int[cpuCnt];
		holders = new Holder[HOLDERS];
		for (int i = 0; i < HOLDERS; i++) {
			holders[i] = new Holder();
			holders[i].slot = i;
			holders[i].ref = null;
		}

		JVMHelp.wr("SmpHangProbe: tenuring\r\n");
		int minors = churnUntilMinor(20000, 1) + churnUntilMinor(20000, 1);
		JVMHelp.wr("SmpHangProbe: minors ");
		wrInt(minors);
		JVMHelp.wr(" nurseryAllocPtr ");
		wrInt(GC.nurseryAllocPtr);
		JVMHelp.wr("\r\n");

		JVMHelp.wr("SmpHangProbe: releasing core 1\r\n");
		Native.wr(1, Const.IO_SIGNAL);

		// BOUNDED. SmpGcTest spins here forever; the whole point is to come back
		// with a number. The inner count is a plain spin because a timer read per
		// iteration would itself be memory traffic.
		int last = -1;
		for (int outer = 0; outer < 200; outer++) {
			for (int i = 0; i < 200000; i++) {
				int s = step;
				if (s != last) {
					last = s;
					JVMHelp.wr("  core1 step ");
					wrInt(s);
					JVMHelp.wr("\r\n");
				}
				if (ready != 0) {
					JVMHelp.wr("SmpHangProbe: READY seen, sp ");
					wrInt(probeSp);
					JVMHelp.wr(" handle ");
					wrInt(probeHandle);
					JVMHelp.wr("\r\n");
					// BISECT STEP 3 — THE ACTUAL DIFFERENCE FROM SmpGcTest.
					// Everything above ran its collections while core 1 was
					// still PARKED. SmpGcTest churns AFTER the handshake, so a
					// minor GC must halt a core that is RUNNING with dirty
					// stack-cache banks -- the path item 133's flush-on-halt
					// added and nothing has exercised. If the hang is there,
					// this is where it stops.
					JVMHelp.wr("SmpHangProbe: churn with core 1 RUNNING\r\n");
					int pm = churnUntilMinor(20000, 1);
					JVMHelp.wr("SmpHangProbe: post-release minors ");
					wrInt(pm);
					JVMHelp.wr("\r\n");

					// STEP 4. Core 0 stops allocating and only WATCHES. Core 1
					// is now allocating hard, so the next collection is core
					// 1's, and core 0 is the one that must halt and flush. If
					// core 0's heartbeat stops while core 1's allocation count
					// has frozen too, both are stuck in that handshake.
					JVMHelp.wr("SmpHangProbe: watching core 1 allocate\r\n");
					// BISECT STEP 5 — BOTH CORES ALLOCATING AT ONCE.
					// Step 4 had core 0 idle while core 1 allocated, so every
					// collection had exactly one requester. SmpGcTest's round 0
					// has core 0 allocating in its wait loop WHILE core 1
					// allocates in its publish loop, so both can request a
					// stop-the-world at the same moment -- each then needing
					// the other to halt and flush. That is the case no probe
					// has reached.
					int prev = -1;
					for (int w = 0; w < 30; w++) {
						for (int k = 0; k < 4000; k++) { Object y = new Young(); if (y == null) return; }
						int a = c1Allocs;
						JVMHelp.wr("  w");
						wrInt(w);
						JVMHelp.wr("=");
						wrInt(a);
						JVMHelp.wr(a == prev ? " FROZEN\r\n" : "\r\n");
						prev = a;
					}
					JVMHelp.wr("SmpHangProbe PASS (core 0 never froze)\r\n");
					return;
				}
			}
		}
		JVMHelp.wr("SmpHangProbe: GAVE UP, core1 reached step ");
		wrInt(step);
		JVMHelp.wr("\r\nSmpHangProbe HANG\r\n");
	}

	/** Core 1: SmpGcTest's publisher(1) prologue, one static write per step. */
	static void core1() {
		step = 1;
		Young probe = new Young();
		step = 2;
		probe.magic = 0x5A5A0001;
		probeSp = Native.getSP();
		step = 3;
		probeHandle = Native.toInt(probe);
		step = 4;
		ready = 1;
		step = 5;
		// BISECT STEP 4 — THE DIRECTION NEVER TESTED.
		// Steps 1-3 had core 0 doing all the allocating, so every collection
		// was initiated by core 0 and halted core 1. SmpGcTest's round 0 has
		// BOTH cores allocating, so a GC can be initiated by CORE 1 and must
		// halt CORE 0 -- the reverse handshake, and the one that would explain
		// why core 0 never reaches its own 2,000,000-spin STALL report: a
		// frozen core does not spin.
		// BISECT STEP 6 — CROSS-GENERATION STORES, the card-table path.
		// Every earlier step had core 1 allocating GARBAGE that dies at once,
		// so a minor GC traces nothing from it. SmpGcTest's publisher stores
		// each young object into a TENURED holder -- an old->young reference
		// that the collector can only find through the card table. That is the
		// machinery items 131/132 are about, and on an SMP stack-cache build it
		// runs alongside scanOtherCoreRoots.
		int slot = 0;
		while (true) {
			Young y = new Young();
			if (y == null) return;
			y.magic = 0x5A5A0000 | slot;
			holders[slot].ref = y;          // TENURED holder <- NURSERY object
			slot++;
			if (slot >= HOLDERS) slot = 0;
			c1Allocs++;
		}
	}

	public void run() {
		if (cpuId == 0) core0(); else core1();
	}

	public static void main(String[] args) {
		cpuId = Native.rdMem(Const.IO_CPU_ID);
		new SmpHangProbe().run();
	}
}
