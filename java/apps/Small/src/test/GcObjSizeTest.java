package test;

import com.jopdesign.sys.*;

/**
 * Does evacuation still win when objects are large?
 *
 * `0998e9a` replaced sliding compaction with evacuation and took the major GC
 * pause from 2214.9 to 865.6 ms (EP4CGX150) / 689.8 ms (XC7A100T). But
 * `GcMajorPauseTest` allocates `Node { int tag; Node link }` — 2 words, about 3
 * live words per handle — which is as favourable to evacuation as it gets, and
 * it is the only shape that has ever been measured.
 *
 * The two costs scale on different axes:
 *
 *   evacuation copy       O(live WORDS)   — every live object moves, every GC
 *   the sort it replaced  O(handles x log handles)
 *
 * Sliding left objects in place once positioned, so its copy cost was ~10 ms
 * regardless of size; evacuation's was 86 ms at ~3 words/handle. From the
 * measured ~0.8 us/word and the 1085 ms sort at 36k handles, current-status
 * item 24 predicts they break even near **1.36M live words — an average object
 * of about 38 words**. A 40-element int[] is past that.
 *
 * So this test holds the handle count FIXED and varies only object size. The
 * payloads are held in one reference array, so mark does the same amount of
 * work in every row (COUNT pushes from the array, COUNT pops, and int[] has no
 * children to trace) and **copy is the only term that should move**.
 *
 * Two things to read off the output:
 *   - does copy_ms track live_words linearly, as the model says?
 *   - does the pause cross back over what sliding used to cost?
 *
 * **Read `sort_ms`, not `passes`, to see which strategy ran.** `sort_ms` is
 * under GC_TIMING and always populated; `passes` is under GC_SORT_TRACE, which
 * is off for production, so it reads 0 regardless. A non-zero `sort_ms` means
 * `chooseEvacDest` found no disjoint region and fell back to sort-and-slide.
 * On a small heap that fallback engages once live approaches half of it, which
 * is the other thing worth exercising here — the path is otherwise only hit by
 * accident.
 */
public class GcObjSizeTest {

	/** Fixed across every row, so handles and mark work stay constant. */
	static final int COUNT = 12000;

	/** Payload sizes in words. Spans the predicted ~38-word crossover. */
	static final int[] SIZES = { 2, 10, 40, 100, 200 };

	// Typed int[][] rather than Object[]: reading a payload back out of an
	// Object[] would need `(int[]) live[i]`, and checkcast to an ARRAY type is not
	// implemented on JOP (stage3-followups item 6) — it throws bytecode 255 not
	// implemented. `new int[COUNT][]` is anewarray, not multianewarray, so it
	// avoids the 2-dimension limit too.
	static int[][] live;

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

		JVMHelp.wr("GcObjSizeTest start\n");
		JVMHelp.wr("obj_words live_handles live_words pause_ms mark_ms compact_ms sort_ms slide_ms copy_ms passes\n");

		int bad = 0;

		for (int s = 0; s < SIZES.length; ++s) {
			int size = SIZES[s];

			// Drop the previous generation and reclaim it, so each row starts
			// from a clean heap and the numbers are not carrying the last one.
			live = null;
			GC.gc();

			boolean fits = true;
			try {
				live = new int[COUNT][];
				for (int i = 0; i < COUNT; ++i) {
					int[] a = new int[size];
					a[0] = i;                 // touch it, so it cannot be elided
					a[size - 1] = size;
					live[i] = a;
				}
			} catch (OutOfMemoryError e) {
				fits = false;
			}

			if (!fits) {
				wrInt(size); JVMHelp.wr("  does not fit — heap too small\n");
				live = null;
				continue;
			}

			GC.gc();

			wrInt(size); JVMHelp.wr("   ");
			wrInt(GC.gcMajLiveHandles); JVMHelp.wr("      ");
			wrInt(GC.gcMajLiveWords); JVMHelp.wr("   ");
			wrMs(GC.gcMajorLast); JVMHelp.wr("  ");
			wrMs(GC.gcMajTMark); JVMHelp.wr("  ");
			wrMs(GC.gcMajTCompact); JVMHelp.wr("  ");
			wrMs(GC.gcMajTSort); JVMHelp.wr("  ");
			wrMs(GC.gcMajTSlide); JVMHelp.wr("  ");
			wrMs(GC.gcMajTCopyWords); JVMHelp.wr("  ");
			wrInt(GC.gcSortPasses);
			JVMHelp.wr("\n");

			// Integrity: every payload must have survived relocation intact.
			// Evacuation rewrites OFF_PTR for every object on every collection,
			// so a bad destination shows up here as wrong contents rather than
			// as a crash.
			for (int i = 0; i < COUNT; ++i) {
				int[] a = live[i];
				if (a == null || a.length != size || a[0] != i || a[size - 1] != size) {
					++bad;
				}
			}
		}

		live = null;

		JVMHelp.wr("corrupt "); wrInt(bad);
		JVMHelp.wr(", bad handles "); wrInt(GC.gcBadHandleCnt);
		JVMHelp.wr("\n");
		if (bad == 0 && GC.gcBadHandleCnt == 0) {
			JVMHelp.wr("OBJSIZE OK\n");
		} else {
			JVMHelp.wr("OBJSIZE FAIL\n");
		}
		JVMHelp.wr("GcObjSizeTest done\n");
	}
}
