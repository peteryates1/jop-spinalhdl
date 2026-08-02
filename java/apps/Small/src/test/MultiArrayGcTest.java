package test;

import com.jopdesign.sys.*;

/**
 * Regression test for the GC metadata of multi-dimensional arrays.
 *
 * DoAll already covers multianewarray functionally (MultiArray, ArrayMulti) and
 * both pass — reads and writes work because OFF_TYPE is never consulted by
 * iaload/iastore, only by the collector. This test covers the part those miss:
 * that the GC can actually determine the size of the INNER arrays.
 *
 * JVM.f_multianewarray takes the inner arrays' type from a constant-pool entry
 * that holds a primitive type code (4..11) for a primitive element type but 0
 * for a REFERENCE element type. That 0 was passed straight to f_newarray, so
 * the inner arrays of any Object[][] were created with OFF_TYPE = IS_OBJ. The
 * collector then dereferenced their length as a method table (a size larger
 * than the heap) and, worse, never scanned their elements as references — the
 * exact shape of JVMHelp.ih = new Runnable[cpus][NUM_INTERRUPTS], whose
 * registered interrupt handlers were therefore invisible to the GC.
 *
 * Primitive 2-D arrays were unaffected, so this needs the reference case to
 * catch it. Passes when both retained arrays survive repeated collections
 * intact AND the collector's bad-handle censuses stay at zero.
 */
public class MultiArrayGcTest {

	static final int ROWS = 7;
	static final int COLS = 5;
	static final int ROUNDS = 400;

	/** Retained across collections so the inner arrays must be promoted. */
	static int[][] grid;
	/** Element type for the reference case (a typed class avoids a checkcast,
	 *  which JOP does not implement for array types). */
	static class Cell { int r; int c; }
	/** The case that was actually broken: a reference element type. */
	static Cell[][] refGrid;
	/** Garbage sink to drive minor GCs. */
	static int[] garbage;

	static void wrInt(int val) {
		if (val < 0) { JVMHelp.wr('-'); val = -val; }
		boolean lead = false;
		for (int div = 1000000000; div > 0; div /= 10) {
			int d = (val / div) % 10;
			if (d != 0 || lead || div == 1) { JVMHelp.wr((char)('0' + d)); lead = true; }
		}
	}

	public static void main(String[] args) {

		JVMHelp.wr("MultiArrayGcTest start\n");

		// A genuine multianewarray: both dimensions given.
		grid = new int[ROWS][COLS];
		for (int r = 0; r < ROWS; ++r) {
			for (int c = 0; c < COLS; ++c) {
				grid[r][c] = r * 100 + c;
			}
		}

		// The reference case: inner arrays must be IS_REFARR so the GC both sizes
		// them and traces the elements. Store real objects so premature
		// collection would show up as corruption.
		refGrid = new Cell[ROWS][COLS];
		for (int r = 0; r < ROWS; ++r) {
			for (int c = 0; c < COLS; ++c) {
				Cell cell = new Cell();
				cell.r = r; cell.c = c;
				refGrid[r][c] = cell;
			}
		}

		// Churn so the retained grids are carried through many collections.
		for (int round = 0; round < ROUNDS; ++round) {
			for (int i = 0; i < 200; ++i) {
				garbage = new int[32];
				garbage[0] = round;
			}
		}
		GC.gc();     // and through a full mark-compact

		// 1. Contents must survive.
		int bad = 0;
		for (int r = 0; r < ROWS; ++r) {
			int[] row = grid[r];
			if (row == null) { ++bad; continue; }
			if (row.length != COLS) { ++bad; continue; }
			for (int c = 0; c < COLS; ++c) {
				if (row[c] != r * 100 + c) ++bad;
			}
		}

		for (int r = 0; r < ROWS; ++r) {
			Cell[] row = refGrid[r];
			if (row == null || row.length != COLS) { ++bad; continue; }
			for (int c = 0; c < COLS; ++c) {
				Cell cell = row[c];
				if (cell == null) { ++bad; continue; }
				if (cell.r != r || cell.c != c) ++bad;
			}
		}

		JVMHelp.wr("minor GCs "); wrInt(GC.gcMinorCount);
		JVMHelp.wr(", corrupt "); wrInt(bad); JVMHelp.wr("\n");

		// 2. The collector must never have seen an un-sizeable handle. This is
		//    the assertion the functional MultiArray tests cannot make.
		JVMHelp.wr("badYoung "); wrInt(GC.gcBadYoungCnt);
		JVMHelp.wr(", badCompact "); wrInt(GC.gcBadHandleCnt);
		if (GC.gcBadYoungCnt != 0) {
			JVMHelp.wr(" (first type="); wrInt(GC.gcBadYoungW3);
			JVMHelp.wr(" alen="); wrInt(GC.gcBadYoungW1);
			JVMHelp.wr(" size="); wrInt(GC.gcBadYoungSize);
			JVMHelp.wr(")");
		}
		JVMHelp.wr("\n");

		if (bad == 0 && GC.gcBadYoungCnt == 0 && GC.gcBadHandleCnt == 0) {
			JVMHelp.wr("MULTIARRAY GC OK\n");
		} else {
			JVMHelp.wr("MULTIARRAY GC FAIL\n");
		}
		JVMHelp.wr("MultiArrayGcTest done\n");
	}
}
