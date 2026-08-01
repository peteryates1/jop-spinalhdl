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
	static Object[] live;
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

	public static void main(String[] args) {

		JVMHelp.wr("GcPauseTest start\n");
		if (!GC.USE_GENERATIONAL) {
			JVMHelp.wr("NOT generational — build with USE_GENERATIONAL=true\n");
		}
		if (!GC.GC_TIMING) {
			JVMHelp.wr("FAIL: GC_TIMING is off, nothing to measure\n");
			return;
		}

		live = new Object[LIVE_SLOTS];

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

		JVMHelp.wr("\n=== major GC ===\n");
		JVMHelp.wr("count   "); wrInt(GC.gcMajorCount); JVMHelp.wr("\n");
		if (GC.gcMajorCount > 0) {
			JVMHelp.wr("worst   "); wrMs(GC.gcMajorMax); JVMHelp.wr("\n");
			JVMHelp.wr("last    "); wrMs(GC.gcMajorLast); JVMHelp.wr("\n");
		}

		JVMHelp.wr("\nfree    "); wrInt(GC.freeMemory()); JVMHelp.wr(" bytes\n");
		JVMHelp.wr("GcPauseTest done\n");
	}
}
