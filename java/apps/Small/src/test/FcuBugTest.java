package test;

import com.jopdesign.sys.*;

/**
 * The float operations HashMap performs, in isolation.
 *
 * current-status item 28: `CollectionTest` hangs on any Wukong config with
 * `float -> hw`, and passes without it — but `CollectionTest` contains no
 * float at all. `HashMap` does:
 *
 *   threshold = (int)(capacity * loadFactor);            // fmul, then f2i
 *   if (loadFactor <= 0 || Float.isNaN(loadFactor))      // fcmpl/fcmpg
 *
 * If `f2i` returns the wrong threshold — 0 rather than 12 for the default
 * 16 x 0.75f — then `size >= threshold` is true on the very first put, every
 * put resizes, and the map never settles. That is a silent hang with no trap,
 * which is the observed symptom.
 *
 * This does exactly those operations and prints integers only, so it needs no
 * float printing and no collections. Run it on an FCU build and a non-FCU build
 * and compare.
 */
public class FcuBugTest {

	static int fails;

	static void wrInt(int val) {
		if (val < 0) { JVMHelp.wr('-'); val = -val; }
		boolean lead = false;
		for (int div = 1000000000; div > 0; div /= 10) {
			int d = (val / div) % 10;
			if (d != 0 || lead || div == 1) { JVMHelp.wr((char)('0' + d)); lead = true; }
		}
	}

	static void check(String what, int got, int want) {
		JVMHelp.wr(what);
		JVMHelp.wr(" got ");
		wrInt(got);
		JVMHelp.wr(" want ");
		wrInt(want);
		if (got == want) {
			JVMHelp.wr(" ok\n");
		} else {
			JVMHelp.wr(" FAIL\n");
			++fails;
		}
	}

	public static void main(String[] args) {

		JVMHelp.wr("FcuBugTest start\n");

		// The exact HashMap expression, at each capacity it grows through.
		float lf = 0.75f;
		check("thresh(16)  ", (int)(16 * lf), 12);
		check("thresh(32)  ", (int)(32 * lf), 24);
		check("thresh(64)  ", (int)(64 * lf), 48);
		check("thresh(4)   ", (int)(4 * lf), 3);

		// f2i on its own, no multiply involved.
		float f12 = 12.0f;
		float f12_7 = 12.75f;
		check("f2i 12.0    ", (int)f12, 12);
		check("f2i 12.75   ", (int)f12_7, 12);

		// i2f then back, the round trip HashMap relies on.
		int cap = 16;
		float capf = (float)cap;
		check("i2f/f2i 16  ", (int)capf, 16);

		// fmul alone, result still a float, compared not converted.
		float prod = 16 * lf;
		check("fmul>11     ", prod > 11.0f ? 1 : 0, 1);
		check("fmul<13     ", prod < 13.0f ? 1 : 0, 1);

		// The guard HashMap's constructor runs.
		check("lf<=0 false ", (lf <= 0) ? 1 : 0, 0);
		check("lf!=NaN     ", (lf != lf) ? 1 : 0, 0);

		JVMHelp.wr("fails ");
		wrInt(fails);
		JVMHelp.wr("\n");
		JVMHelp.wr(fails == 0 ? "FCUBUG OK\n" : "FCUBUG FAIL\n");
		JVMHelp.wr("FcuBugTest done\n");
	}
}
