package test;

import com.jopdesign.sys.*;

/**
 * Does a registered interrupt handler survive garbage collection?
 *
 * This is the gap left by 78cc968. JVMHelp.ih is
 * `new Runnable[cpus][NUM_INTERRUPTS]` — a 2-D array with a REFERENCE element
 * type, exactly the shape whose inner arrays were being created with
 * OFF_TYPE = IS_OBJ, so the collector never traced their elements. Anything
 * reachable only through ih[core][nr] was therefore collectable while still
 * installed. MultiArrayGcTest proves the array typing is fixed, but it never
 * calls addInterruptHandler, so the actual mechanism stayed untested.
 *
 * Note the boot-time handlers do NOT exercise this: JVMHelp.init() fills every
 * slot with a DummyHandler that is also held by the static field JVMHelp.dh, so
 * it stays rooted no matter how ih is traced. The exposure is a handler whose
 * ONLY reference is the array slot — which is what install() below creates.
 *
 * The test registers such a handler, fires its interrupt in software via
 * IO_SWINT, runs many minor GCs plus a full mark-compact, then fires again and
 * checks that it still runs AND that its own field survived intact (proving the
 * object itself was traced and relocated, not just that some object was called).
 */
public class IntHandlerGcTest {

	/** Source 0 is the timer/scheduler; 1 and 2 are I/O sources. */
	static final int INT_NR = 2;
	static final int TAG = 0x5A5A;
	static final int ROUNDS = 400;

	static int runs;        // bumped by the handler
	static int seenTag;     // the handler's own field, read back through it
	static int[] garbage;   // churn sink

	/** Handler carrying state. Its only reference is ih[core][INT_NR]. */
	static class Handler implements Runnable {
		int tag;
		public void run() {
			runs++;
			seenTag = tag;
		}
	}

	/**
	 * Register in its own frame so no live local keeps the object alive — the
	 * array slot must be the sole reference for this test to mean anything.
	 */
	static void install(int core) {
		Handler h = new Handler();
		h.tag = TAG;
		JVMHelp.addInterruptHandler(core, INT_NR, h);
	}

	static void wrInt(int val) {
		if (val < 0) { JVMHelp.wr('-'); val = -val; }
		boolean lead = false;
		for (int div = 1000000000; div > 0; div /= 10) {
			int d = (val / div) % 10;
			if (d != 0 || lead || div == 1) { JVMHelp.wr((char)('0' + d)); lead = true; }
		}
	}

	/** Raise the interrupt in software and wait for the handler to run. */
	static boolean fire() {
		int before = runs;
		Native.wr(INT_NR, Const.IO_SWINT);
		for (int i = 0; i < 100000; ++i) {
			if (runs != before) return true;
		}
		return false;
	}

	public static void main(String[] args) {

		JVMHelp.wr("IntHandlerGcTest start\n");

		int core = Native.rdMem(Const.IO_CPU_ID);

		// The mask resets to 0, and this app runs no scheduler, so nothing has
		// enabled any source yet. Unmask ours and enable interrupts globally.
		Native.wr(1 << INT_NR, Const.IO_INTMASK);
		Native.wr(1, Const.IO_INT_ENA);

		install(core);

		// Baseline: it must work before we collect, otherwise a later failure
		// would be ambiguous between "GC broke it" and "never worked".
		boolean firstOk = fire();
		JVMHelp.wr("before GC: ran="); JVMHelp.wr(firstOk ? "yes" : "NO");
		JVMHelp.wr(" tag="); wrInt(seenTag); JVMHelp.wr("\n");

		// Churn hard: many minor GCs, and the handler must be promoted out of
		// the nursery. Allocation also overwrites the stack, reducing the chance
		// that a stale slot keeps the handler alive by conservative scanning.
		for (int round = 0; round < ROUNDS; ++round) {
			for (int i = 0; i < 200; ++i) {
				garbage = new int[32];
				garbage[0] = round;
			}
		}
		int minors = GC.gcMinorCount;

		// ...then a full mark-compact, which relocates it again.
		GC.gc();

		seenTag = 0;
		boolean secondOk = fire();

		JVMHelp.wr("after GC:  ran="); JVMHelp.wr(secondOk ? "yes" : "NO");
		JVMHelp.wr(" tag="); wrInt(seenTag); JVMHelp.wr("\n");
		JVMHelp.wr("minor GCs "); wrInt(minors);
		JVMHelp.wr(", major GCs "); wrInt(GC.gcMajorCount);
		JVMHelp.wr(", runs "); wrInt(runs); JVMHelp.wr("\n");

		if (firstOk && secondOk && seenTag == TAG) {
			JVMHelp.wr("INTHANDLER GC OK\n");
		} else {
			JVMHelp.wr("INTHANDLER GC FAIL\n");
		}
		JVMHelp.wr("IntHandlerGcTest done\n");
	}
}
