package test;

import com.jopdesign.sys.*;

/**
 * Worst-case major (full mark-compact) GC pause, and how it scales.
 *
 * Follow-up 5 in docs/gc/stage3-followups.md: 5e0a3a0 made a major GC O(live)
 * rather than O(heap) and replaced the O(n^2) handle sort with a merge sort, but
 * nothing had ever measured the result. Nothing in the suite drives enough
 * promotion to fire one naturally on the 256 MB board, so this forces them
 * explicitly at increasing live-set sizes.
 *
 * Each step retains STEP more objects (so the live set only grows), then calls
 * GC.gc() and reports the pause with its mark/compact split. A roughly linear
 * pause against live handles is the expected O(live) behaviour; anything
 * super-linear would mean something is still O(heap) or worse.
 *
 * The retained objects are held in a plain reference array — deliberately, since
 * that is also what the collector has to trace.
 */
public class GcMajorPauseTest {

	static final int STEPS = 6;
	static final int STEP = 6000;          // objects added per step
	static final int TOTAL = STEPS * STEP; // must stay under GC.MAX_HANDLES (65536)

	/** Element type: a small object with a field, so tracing has work to do. */
	static class Node { int tag; Node link; }

	static Node[] live;
	static int[] garbage;

	static void wrInt(int val) {
		if (val < 0) { JVMHelp.wr('-'); val = -val; }
		boolean lead = false;
		for (int div = 1000000000; div > 0; div /= 10) {
			int d = (val / div) % 10;
			if (d != 0 || lead || div == 1) { JVMHelp.wr((char)('0' + d)); lead = true; }
		}
	}

	static void wrMs(int us) {
		wrInt(us / 1000);
		JVMHelp.wr('.');
		int f = us % 1000;
		JVMHelp.wr((char)('0' + (f / 100) % 10));
		JVMHelp.wr((char)('0' + (f / 10) % 10));
		JVMHelp.wr((char)('0' + f % 10));
	}

	public static void main(String[] args) {

		JVMHelp.wr("GcMajorPauseTest start\n");
		JVMHelp.wr("live_objs  pause_ms  mark_ms  compact_ms  live_handles  live_words\n");

		live = new Node[TOTAL];
		int filled = 0;

		for (int step = 0; step < STEPS; ++step) {
			// Grow the live set.
			for (int i = 0; i < STEP; ++i) {
				Node n = new Node();
				n.tag = filled;
				// Chain a few together so marking has to traverse, not just scan.
				if (filled > 0 && (filled & 7) != 0) n.link = live[filled - 1];
				live[filled] = n;
				++filled;
			}
			// Some garbage too, so the collector has something to reclaim.
			for (int i = 0; i < 2000; ++i) {
				garbage = new int[16];
				garbage[0] = i;
			}

			GC.gc();

			wrInt(filled); JVMHelp.wr("      ");
			wrMs(GC.gcMajorLast); JVMHelp.wr("    ");
			wrMs(GC.gcMajTMark); JVMHelp.wr("   ");
			wrMs(GC.gcMajTCompact); JVMHelp.wr("      ");
			wrInt(GC.gcMajLiveHandles); JVMHelp.wr("       ");
			wrInt(GC.gcMajLiveWords); JVMHelp.wr("\n");
		}

		// Integrity: every retained object must have survived every collection
		// with its tag and its chain intact.
		int bad = 0;
		for (int i = 0; i < filled; ++i) {
			Node n = live[i];
			if (n == null || n.tag != i) { ++bad; continue; }
			if (i > 0 && (i & 7) != 0 && n.link == null) ++bad;
		}

		JVMHelp.wr("\nmajor GCs "); wrInt(GC.gcMajorCount);
		JVMHelp.wr(", worst "); wrMs(GC.gcMajorMax); JVMHelp.wr(" ms");
		JVMHelp.wr(", corrupt "); wrInt(bad); JVMHelp.wr("\n");
		JVMHelp.wr(bad == 0 ? "MAJOR PAUSE OK\n" : "MAJOR PAUSE FAIL\n");
		JVMHelp.wr("GcMajorPauseTest done\n");
	}
}
