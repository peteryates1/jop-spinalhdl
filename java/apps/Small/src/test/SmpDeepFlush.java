package test;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.GC;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

/**
 * THE MULTI-BANK FLUSH — the coverage gap item 133's fix was never tested
 * against.
 *
 * A halted core's stack cache is flushed to its spill region so the collector
 * can read the stack with ordinary loads. `StackStage` walks the DIRTY banks
 * one per pass, lowest first. Every validation of that so far — simulation and
 * hardware, `SmpGcTest`'s STACKROOT probe — reported `spMin 64 spMax 90`.
 *
 * The banks are 3 x 192 words above a 64-word scratch area:
 *
 *   bank 0: 64..255    bank 1: 256..447    bank 2: 448..639
 *
 * So a stack 90 words deep lives ENTIRELY IN BANK 0. Every passing test
 * exercised the one-dirty-bank case, and the walk across several dirty banks —
 * the actual loop — has never run. A single-bank pass proves the mechanism
 * starts, not that it terminates.
 *
 * WHAT THIS DOES. Core 1 recurses ~100 frames deep (~9.5 words per frame, so
 * SP lands near 1000, past every bank boundary and well into rotation), parks
 * a `Young` object in the DEEPEST frame, and holds it there — the reference
 * exists nowhere else, so the collector can only keep the object alive by
 * scanning the deep stack. Core 0 then runs minor GCs, each of which must halt
 * core 1 and flush every dirty bank before it can scan.
 *
 * WHAT A FAILURE LOOKS LIKE. If the walk stops after one bank, the deep part
 * of the stack is stale in memory: the collector reads words the core never
 * wrote, misses the reference, frees a live object, and `magic` comes back
 * wrong — or the run hangs, if the flush never reports done. `maxScanSp` says
 * how deep the collector actually looked, which distinguishes "scanned and
 * lost it" from "never scanned that far".
 */
public class SmpDeepFlush implements Runnable {

	static final int MAGIC = 0x5A5ADEEF;
	/** ~9.5 words per frame, so ~1000 words: past bank 2 and into rotation. */
	static final int DEPTH = 30;

	static class Young {
		int magic;
		int p0, p1, p2, p3, p4;
	}

	static volatile int ready;       // core 1 -> core 0: reference parked deep
	static volatile int done;        // core 0 -> core 1: collections finished
	static volatile int magicSeen;   // core 1's read-back AFTER the collections
	static volatile int c1Sp, c1Handle, c1Depth;
	/** How deep core 1 has got. Written on the way DOWN, so a core stuck mid
	 *  recursion still reports a level. Volatile, and never read by core 1. */
	static volatile int c1Mark;
	/** Set by core 1 the instant it reaches main(), BEFORE the `new` below --
	 *  so "never reached main" and "died allocating the Runnable" are separable
	 *  rather than both reading as c1Depth == 0. */
	static volatile int c1Main;

	static int cpuId;

	static void wrInt(int v) { JVMHelp.wr(Integer.toString(v)); }

	static boolean generational() { return GC.nurseryTop != GC.nurseryBase; }

	static int churnUntilMinor(int budget, int wanted) {
		if (!generational()) return 0;
		int before = GC.nurseryAllocPtr;
		int seen = 0;
		for (int i = 0; i < budget; i++) {
			Object junk = new Young();
			if (junk == null) return seen;
			int now = GC.nurseryAllocPtr;
			if (now > before) { seen++; before = now; if (seen >= wanted) return seen; }
			else before = now;
		}
		return seen;
	}

	/**
	 * Recurse, then hold the ONLY reference to a fresh object in the deepest
	 * frame across core 0's collections. `probe` is read after the wait, so it
	 * stays live for the whole window and the collector must find it on the
	 * deep stack or lose it.
	 */
	static int deep(int depth) {
		c1Mark = depth;
		if (depth > 0) return deep(depth - 1) + 1;

		Young probe = new Young();
		probe.magic = MAGIC;
		c1Sp = Native.getSP();
		c1Handle = Native.toInt(probe);
		ready = 1;
		while (done == 0) { }
		magicSeen = probe.magic;      // survived, or did not
		return 0;
	}

	static void core0() {
		JVMHelp.wr("SmpDeepFlush: cores ");
		wrInt(Native.rdMem(Const.IO_CPUCNT));
		JVMHelp.wr(generational() ? ", generational\r\n" : ", CLASSIC GC\r\n");

		int minors = churnUntilMinor(20000, 1) + churnUntilMinor(20000, 1);
		JVMHelp.wr("SmpDeepFlush: tenuring minors ");
		wrInt(minors);
		JVMHelp.wr("\r\n");

		Native.wr(1, Const.IO_SIGNAL);

		int spins = 0;
		for (int o = 0; o < 400 && ready == 0; o++) {
			for (int i = 0; i < 100000 && ready == 0; i++) { }
			spins = o;
		}
		if (ready == 0) {
			// NO ALLOCATION IN THIS PATH. wrInt -> Integer.toString allocates,
			// and an allocation here can trigger the very collection whose
			// flush is under suspicion -- the first version of this test
			// truncated mid-`wrInt`, which is indistinguishable from the bug.
			// Fixed strings only, so the report always gets out.
			JVMHelp.wr("SmpDeepFlush: core 1 never parked. ");
			if (c1Main == 0) JVMHelp.wr("never reached main()\r\n");
			else if (c1Depth == 0) JVMHelp.wr("reached main(), died before run()\r\n");
			else if (c1Mark == DEPTH) JVMHelp.wr("STARTED, still at the TOP frame\r\n");
			else if (c1Mark > 0) JVMHelp.wr("DESCENDING, stuck mid-recursion\r\n");
			else JVMHelp.wr("reached the BOTTOM but never published\r\n");
			JVMHelp.wr("SmpDeepFlush INCONCLUSIVE\r\n");
			return;
		}

		JVMHelp.wr("SmpDeepFlush: core1 parked at sp ");
		wrInt(c1Sp);
		JVMHelp.wr(" depth ");
		wrInt(c1Depth);
		JVMHelp.wr(" handle ");
		wrInt(c1Handle);
		// The point of the whole exercise: which banks that SP spans.
		JVMHelp.wr(c1Sp > 448 ? " (spans banks 0,1,2)\r\n"
				: c1Sp > 256 ? " (spans banks 0,1)\r\n" : " (bank 0 ONLY — no new coverage)\r\n");

		// Each of these halts core 1 and must flush every dirty bank first.
		int pm = churnUntilMinor(20000, 3) + churnUntilMinor(20000, 3);
		JVMHelp.wr("SmpDeepFlush: minors ");
		wrInt(pm);
		JVMHelp.wr(" scanned sp ");
		wrInt(GC.minScanSp);
		JVMHelp.wr("..");
		wrInt(GC.maxScanSp);
		JVMHelp.wr(" words ");
		wrInt(GC.otherRootWords);
		JVMHelp.wr("\r\n");

		// Core 0's own view of the object, before releasing core 1.
		int hPtr = Native.rdMem(c1Handle);
		JVMHelp.wr("core0 view: ptr ");
		wrInt(hPtr);
		JVMHelp.wr(" magic ");
		wrInt(hPtr != 0 ? Native.rdMem(hPtr) : -1);
		JVMHelp.wr("\r\n");

		done = 1;
		for (int o = 0; o < 400 && magicSeen == 0; o++)
			for (int i = 0; i < 100000 && magicSeen == 0; i++) { }

		JVMHelp.wr("core1 read back magic ");
		wrInt(magicSeen);
		JVMHelp.wr(magicSeen == MAGIC ? " OK\r\n" : " WRONG\r\n");

		// Banks are 64..255 / 256..447 / 448..639, so SP past 256 means the
		// flush had to walk MORE THAN ONE dirty bank -- the loop that every
		// previous validation (spMax 90) left unexercised.
		boolean twoBanks = c1Sp > 256;
		boolean threeBanks = c1Sp > 448;
		boolean scannedDeep = GC.maxScanSp > 256;
		boolean survived = magicSeen == MAGIC;
		if (!twoBanks) JVMHelp.wr("SmpDeepFlush INCONCLUSIVE (one bank only — no new coverage)\r\n");
		else if (!scannedDeep) JVMHelp.wr("SmpDeepFlush FAIL (collector never scanned past bank 0)\r\n");
		else if (!survived) JVMHelp.wr("SmpDeepFlush FAIL (live object lost from a deep frame)\r\n");
		else if (threeBanks) JVMHelp.wr("SmpDeepFlush OK (three banks)\r\n");
		else JVMHelp.wr("SmpDeepFlush OK (two banks; three untested — see DEPTH)\r\n");
	}

	public void run() {
		if (cpuId == 0) core0();
		else { c1Depth = DEPTH; deep(DEPTH); for (;;) { } }
	}

	public static void main(String[] args) {
		cpuId = Native.rdMem(Const.IO_CPU_ID);
		if (cpuId != 0) c1Main = 1;      // before ANY allocation
		new SmpDeepFlush().run();
	}
}
