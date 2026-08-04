package test;

import com.jopdesign.sys.*;

/**
 * Stage 3 minor-GC pause measurement.
 *
 * Allocates a steady stream of garbage (with a small, deliberately retained
 * live set so promotion is non-zero) to drive minor GCs, then reports the
 * per-phase split of the worst pause seen. All times come from IO_US_CNT, so
 * they are microseconds regardless of core clock and compare directly between
 * the 100 MHz EP4CGX150 and the XC7A100T.
 *
 * The point of the exercise is to replace the 2019 back-of-envelope estimate
 * (~75 ms for a 4 MB nursery, dominated by software zeroing) with measured
 * numbers now that the zero-fill DMA does that phase in hardware.
 */
public class GcPauseTest {

	/** Live set retained across collections, so each minor GC has real work to do. */
	static int[][] live;
	/** Sink for the garbage, to stop anything being optimised away. */
	static int[] garbage;

	static final int LIVE_SLOTS = 64;
	/**
	 * Sized so the 4 MB nursery on the DDR3 board fills many times over: each
	 * round allocates ~204 objects of ~160 B, so ~26k objects fill that nursery
	 * and 2000 rounds gives ~15 minor GCs there (and ~80 on the smaller SDR
	 * heap). One collection is not a worst case.
	 */
	static final int ROUNDS = 2000;

	static void wrInt(int val) {
		if (val < 0) { JVMHelp.wr('-'); val = -val; }
		boolean lead = false;
		for (int div = 1000000000; div > 0; div /= 10) {
			int d = (val / div) % 10;
			if (d != 0 || lead || div == 1) { JVMHelp.wr((char)('0' + d)); lead = true; }
		}
	}

	/** Print a microsecond value as "12.345 ms". */
	static void wrMs(int us) {
		wrInt(us / 1000);
		JVMHelp.wr('.');
		int frac = us % 1000;
		JVMHelp.wr((char)('0' + (frac / 100) % 10));
		JVMHelp.wr((char)('0' + (frac / 10) % 10));
		JVMHelp.wr((char)('0' + frac % 10));
		JVMHelp.wr(" ms");
	}

	static void row(String name, int us, int totalUs) {
		JVMHelp.wr("  ");
		JVMHelp.wr(name);
		wrMs(us);
		JVMHelp.wr("  (");
		// Integer percent of the total pause.
		wrInt(totalUs > 0 ? (us * 100) / totalUs : 0);
		JVMHelp.wr("%)\n");
	}

	/** Full-width dump of the heap pointers (GEN_TRACE's printer truncates). */
	static void dumpPtrs(String when) {
		JVMHelp.wr(when);
		JVMHelp.wr(": hStart="); wrInt(GC.heapStart);
		JVMHelp.wr(" hSize="); wrInt(GC.heapSize);
		JVMHelp.wr(" top="); wrInt(GC.heapStart + GC.heapSize);
		JVMHelp.wr(" copy="); wrInt(GC.copyPtr);
		JVMHelp.wr(" alloc="); wrInt(GC.allocPtr);
		JVMHelp.wr(" nBase="); wrInt(GC.nurseryBase);
		JVMHelp.wr(" nTop="); wrInt(GC.nurseryTop);
		JVMHelp.wr(" nAlloc="); wrInt(GC.nurseryAllocPtr);
		JVMHelp.wr("\n");
	}

	public static void main(String[] args) {

		JVMHelp.wr("GcPauseTest start\n");
		if (!GC.USE_GENERATIONAL) {
			JVMHelp.wr("NOT generational — build with USE_GENERATIONAL=true\n");
		}
		if (!GC.GC_TIMING) {
			JVMHelp.wr("FAIL: GC_TIMING is off, nothing to measure\n");
			return;
		}

		live = new int[LIVE_SLOTS][];

		// Churn: mostly garbage, with a rotating slice kept alive so that the
		// copy phase actually promotes objects instead of measuring an empty heap.
		for (int round = 0; round < ROUNDS; ++round) {
			for (int i = 0; i < 200; ++i) {
				garbage = new int[32];
				garbage[0] = round;
				garbage[31] = i;
			}
			// Retain a few per round, overwriting the oldest slots.
			for (int i = 0; i < 4; ++i) {
				int[] keep = new int[16];
				keep[0] = round;
				live[(round * 4 + i) % LIVE_SLOTS] = keep;
			}
		}

		JVMHelp.wr("\n=== minor GC pause ===\n");
		JVMHelp.wr("count   "); wrInt(GC.gcMinorCount); JVMHelp.wr("\n");

		if (GC.gcMinorCount == 0) {
			JVMHelp.wr("no minor GC ran — increase ROUNDS or shrink the nursery\n");
		} else {
			JVMHelp.wr("worst   "); wrMs(GC.gcMinorMax); JVMHelp.wr("\n");
			JVMHelp.wr("mean    "); wrMs(GC.gcMinorTotal / GC.gcMinorCount); JVMHelp.wr("\n");
			JVMHelp.wr("last    "); wrMs(GC.gcMinorLast); JVMHelp.wr("\n");

			int worst = GC.gcMinorMax;
			JVMHelp.wr("worst-pause phase split:\n");
			row("roots  ", GC.gcWRoots, worst);
			// "roots" is two different scans with different scaling: the
			// stack+static walk is O(roots), the dirty-card walk is O(heap).
			row("  stk/st", GC.gcWRootScan, worst);
			row("  cardsc", GC.gcWCardScan, worst);
			row("mark   ", GC.gcWMark,  worst);
			row("copy   ", GC.gcWCopy,  worst);
			row("zero   ", GC.gcWZero,  worst);
			row("cards  ", GC.gcWCards, worst);

			// The sweep is O(useList entries visited); this is the constant that
			// actually sets the pause, measured rather than inferred.
			JVMHelp.wr("  swept handles  "); wrInt(GC.gcWSweptHandles); JVMHelp.wr("\n");
			if (GC.gcWSweptHandles > 0) {
				JVMHelp.wr("  sweep ns/handle "); wrInt((GC.gcWCopy * 1000) / GC.gcWSweptHandles); JVMHelp.wr("\n");
				JVMHelp.wr("  pause ns/handle "); wrInt((worst * 1000) / GC.gcWSweptHandles); JVMHelp.wr("\n");
			}
			JVMHelp.wr("last GC: nursery words "); wrInt(GC.gcMinorNurseryWords);
			JVMHelp.wr(", promoted "); wrInt(GC.gcMinorPromotedWords);
			JVMHelp.wr(", swept "); wrInt(GC.gcSweptHandles);
			JVMHelp.wr(" (copied "); wrInt(GC.gcCopiedHandles);
			JVMHelp.wr(", reclaimed "); wrInt(GC.gcReclaimedHandles); JVMHelp.wr(")\n");
		}

		// The churn above never fills tenure, so it leaves the major path — the
		// youngList splice and the nursery re-carve — completely untested. Drive
		// it explicitly through GC.gc() (what Runtime.gc() calls), which lands
		// there directly rather than via majorGc(), then keep allocating and check
		// the retained set survived. A stale nursery bound or lost young handle
		// shows up here as corrupted or missing data.
		JVMHelp.wr("\n=== major GC path ===\n");
		int liveBefore = 0;
		for (int i = 0; i < LIVE_SLOTS; ++i) if (live[i] != null) ++liveBefore;

		dumpPtrs("before gc1");
		JVMHelp.wr("[gc1"); GC.gc(); JVMHelp.wr("]\n");
		dumpPtrs("after gc1");
		// Allocate again afterwards: this is what would corrupt the heap if the
		// nursery had not been re-carved.
		JVMHelp.wr("[alloc");
		for (int i = 0; i < 500; ++i) {
			garbage = new int[32];
			garbage[0] = i;
		}
		JVMHelp.wr("]");
		JVMHelp.wr("[gc2"); GC.gc(); JVMHelp.wr("]");   // youngList already spliced/empty
		JVMHelp.wr("[verify");

		int liveAfter = 0, bad = 0;
		for (int i = 0; i < LIVE_SLOTS; ++i) {
			int[] a = live[i];
			if (a == null) continue;
			++liveAfter;
			if (a.length != 16) ++bad;
		}
		JVMHelp.wr("]\n");
		JVMHelp.wr("retained before "); wrInt(liveBefore);
		JVMHelp.wr(", after "); wrInt(liveAfter);
		JVMHelp.wr(", corrupt "); wrInt(bad); JVMHelp.wr("\n");
		if (liveAfter == liveBefore && bad == 0) {
			JVMHelp.wr("MAJOR OK\n");
		} else {
			JVMHelp.wr("MAJOR FAIL\n");
		}

		if (GC.gcBadYoungCnt != 0) {
			JVMHelp.wr("BADSZ young "); wrInt(GC.gcBadYoungCnt);
			JVMHelp.wr(" first at minorGc#"); wrInt(GC.gcBadYoungGc);
			JVMHelp.wr(" ref="); wrInt(GC.gcBadYoung); JVMHelp.wr(" size="); wrInt(GC.gcBadYoungSize); JVMHelp.wr("\n");
			JVMHelp.wr("  handle: ptr="); wrInt(GC.gcBadYoungW0);
			JVMHelp.wr(" mtab/alen="); wrInt(GC.gcBadYoungW1);
			JVMHelp.wr(" space="); wrInt(GC.gcBadYoungW2);
			JVMHelp.wr(" type="); wrInt(GC.gcBadYoungW3); JVMHelp.wr("\n");
			JVMHelp.wr("          next="); wrInt(GC.gcBadYoungW4);
			JVMHelp.wr(" grey="); wrInt(GC.gcBadYoungW5);
			JVMHelp.wr(" w6="); wrInt(GC.gcBadYoungW6);
			JVMHelp.wr(" w7="); wrInt(GC.gcBadYoungW7); JVMHelp.wr("\n");
			JVMHelp.wr("  nBase="); wrInt(GC.nurseryBase);
			JVMHelp.wr(" nTop="); wrInt(GC.nurseryTop);
			JVMHelp.wr(" hStart="); wrInt(GC.heapStart); JVMHelp.wr("\n");
		}
		JVMHelp.wr("born-bad "); wrInt(GC.gcOddNewCnt);
		if (GC.gcOddNewCnt != 0) {
			JVMHelp.wr(" first ref="); wrInt(GC.gcOddNew);
			JVMHelp.wr(" type="); wrInt(GC.gcOddNewType);
			JVMHelp.wr(" alen="); wrInt(GC.gcOddNewAlen);
			JVMHelp.wr(" size="); wrInt(GC.gcOddNewSize);
			JVMHelp.wr(" isArray="); wrInt(GC.gcOddNewIsArray);
			JVMHelp.wr(" reqSize="); wrInt(GC.gcOddNewReq);
		}
		JVMHelp.wr("\n");
		if (GC.gcBadHandleCnt != 0) {
			JVMHelp.wr("BAD HANDLES "); wrInt(GC.gcBadHandleCnt);
			JVMHelp.wr(" first: ref="); wrInt(GC.gcBadHandle);
			JVMHelp.wr(" size="); wrInt(GC.gcBadHandleSize);
			JVMHelp.wr(" type="); wrInt(GC.gcBadHandleType);
			JVMHelp.wr(" alen="); wrInt(GC.gcBadHandleAlen);
			JVMHelp.wr(" ptr="); wrInt(GC.gcBadHandlePtr); JVMHelp.wr("\n");
		}
		JVMHelp.wr("count   "); wrInt(GC.gcMajorCount); JVMHelp.wr("\n");
		if (GC.gcMajorCount > 0) {
			JVMHelp.wr("worst   "); wrMs(GC.gcMajorMax); JVMHelp.wr("\n");
			JVMHelp.wr("last    "); wrMs(GC.gcMajorLast); JVMHelp.wr("\n");
		}

		JVMHelp.wr("\nfree    "); wrInt(GC.freeMemory()); JVMHelp.wr(" bytes\n");
		JVMHelp.wr("GcPauseTest done\n");
	}
}
