package test;

import com.jopdesign.sys.*;

/**
 * `multianewarray` beyond two dimensions (current-status item 23).
 *
 * `f_multianewarray` was hardcoded to `dim == 2` and printed "dimensions not
 * supported" for anything else, so `new int[a][b][c]` was an unimplemented trap.
 *
 * The part that needs testing is not the loop, it is the **GC metadata**. Only
 * the innermost level carries the element type; every level above it is a
 * reference array. Getting that wrong at two levels was `78cc968` — inner arrays
 * typed `IS_OBJ`, so the collector could neither compute their size nor scan
 * their elements, and objects reachable only through them were collected while
 * live. The failure mode is premature collection with no visible fault, which is
 * why `MultiArrayGcTest` exists and why this test churns the heap rather than
 * just reading the arrays back.
 *
 * DoAll's `MultiArray` passed throughout that bug, so "the values read back
 * correctly" proves nothing on its own. Every case here is checked *after*
 * repeated collections, including a full mark-compact.
 */
public class MultiDimTest {

	static int fails;

	// Held in statics so the nests are traced as roots across collections.
	static int[][][] i3;
	static int[][][][] i4;
	static Cell[][][] r3;
	static int[][] i2;

	static class Cell { int v; }

	static void check(String what, boolean ok) {
		JVMHelp.wr(what);
		if (ok) {
			JVMHelp.wr(" ok\n");
		} else {
			JVMHelp.wr(" FAIL\n");
			++fails;
		}
	}

	/** Allocate garbage so the collector has to run and move things. */
	static void churn(int rounds) {
		for (int i = 0; i < rounds; ++i) {
			int[] junk = new int[24];
			junk[0] = i;
			junk[23] = i;
		}
	}

	static boolean verifyI3() {
		if (i3 == null || i3.length != 3) return false;
		for (int a = 0; a < 3; ++a) {
			if (i3[a] == null || i3[a].length != 4) return false;
			for (int b = 0; b < 4; ++b) {
				if (i3[a][b] == null || i3[a][b].length != 5) return false;
				for (int c = 0; c < 5; ++c) {
					if (i3[a][b][c] != a * 100 + b * 10 + c) return false;
				}
			}
		}
		return true;
	}

	static boolean verifyI4() {
		if (i4 == null || i4.length != 2) return false;
		for (int a = 0; a < 2; ++a) {
			for (int b = 0; b < 2; ++b) {
				for (int c = 0; c < 2; ++c) {
					if (i4[a][b][c].length != 3) return false;
					for (int d = 0; d < 3; ++d) {
						if (i4[a][b][c][d] != a + b + c + d) return false;
					}
				}
			}
		}
		return true;
	}

	static boolean verifyR3() {
		if (r3 == null || r3.length != 2) return false;
		for (int a = 0; a < 2; ++a) {
			for (int b = 0; b < 3; ++b) {
				for (int c = 0; c < 2; ++c) {
					Cell x = r3[a][b][c];
					// The reference elements must have survived tracing. Under
					// the 78cc968 defect these were the objects that vanished.
					if (x == null || x.v != a * 100 + b * 10 + c) return false;
				}
			}
		}
		return true;
	}

	public static void main(String[] args) {

		JVMHelp.wr("MultiDimTest start\n");

		// --- 3-D primitive ---
		i3 = new int[3][4][5];
		for (int a = 0; a < 3; ++a)
			for (int b = 0; b < 4; ++b)
				for (int c = 0; c < 5; ++c)
					i3[a][b][c] = a * 100 + b * 10 + c;
		check("3-D int, fresh    ", verifyI3());

		// --- 4-D primitive ---
		i4 = new int[2][2][2][3];
		for (int a = 0; a < 2; ++a)
			for (int b = 0; b < 2; ++b)
				for (int c = 0; c < 2; ++c)
					for (int d = 0; d < 3; ++d)
						i4[a][b][c][d] = a + b + c + d;
		check("4-D int, fresh    ", verifyI4());

		// --- 3-D reference: the case the GC metadata actually matters for ---
		r3 = new Cell[2][3][2];
		for (int a = 0; a < 2; ++a)
			for (int b = 0; b < 3; ++b)
				for (int c = 0; c < 2; ++c) {
					Cell x = new Cell();
					x.v = a * 100 + b * 10 + c;
					r3[a][b][c] = x;
				}
		check("3-D ref, fresh    ", verifyR3());

		// --- 2-D must still work: this is the path that already existed ---
		i2 = new int[4][6];
		for (int a = 0; a < 4; ++a)
			for (int b = 0; b < 6; ++b)
				i2[a][b] = a * 10 + b;
		boolean ok2 = true;
		for (int a = 0; a < 4; ++a)
			for (int b = 0; b < 6; ++b)
				if (i2[a][b] != a * 10 + b) ok2 = false;
		check("2-D int, fresh    ", ok2);

		// --- degenerate shapes the spec allows ---
		int[][][] empty = new int[0][2][3];
		check("3-D zero outer    ", empty.length == 0);
		int[][][] midZero = new int[2][0][3];
		check("3-D zero middle   ", midZero.length == 2 && midZero[0].length == 0);

		// --- now make the collector work, and re-check everything ---
		int before = GC.gcMinorCount;
		churn(30000);
		GC.gc();                       // force a full mark-compact too
		churn(10000);
		GC.gc();

		JVMHelp.wr("minor GCs ");
		JVMHelp.wr(Integer.toString(GC.gcMinorCount - before));
		JVMHelp.wr(", bad handles ");
		JVMHelp.wr(Integer.toString(GC.gcBadHandleCnt));
		JVMHelp.wr("\n");

		check("3-D int, after GC ", verifyI3());
		check("4-D int, after GC ", verifyI4());
		check("3-D ref, after GC ", verifyR3());

		boolean ok2b = true;
		for (int a = 0; a < 4; ++a)
			for (int b = 0; b < 6; ++b)
				if (i2[a][b] != a * 10 + b) ok2b = false;
		check("2-D int, after GC ", ok2b);

		if (fails == 0 && GC.gcBadHandleCnt == 0) {
			JVMHelp.wr("MULTIDIM OK\n");
		} else {
			JVMHelp.wr("MULTIDIM FAIL\n");
		}
		JVMHelp.wr("MultiDimTest done\n");
	}
}
