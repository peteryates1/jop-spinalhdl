package test;

import com.jopdesign.sys.*;

/**
 * MathTest's checks, one at a time, so a failure names itself.
 *
 * `MathTest` is a single boolean built from five private sub-tests chained with
 * `&&`, so on hardware it reports only "failed!" and short-circuits — you
 * cannot tell abs from sqrt from sin. It is the last failure left on
 * `wukongFull` after the FCU and DCU compare fixes (current-status item 28),
 * and it is a *clean* failure rather than the crash that was there before, so
 * it is a different defect.
 *
 * Each check below is the exact expression `MathTest` uses, reported
 * individually. Values are printed scaled by 1000 as integers, so no float
 * printing is needed — the point is to see WHICH one is wrong and by how much,
 * not to format nicely.
 */
public class MathBugTest {

	static int fails;

	static void wrInt(int val) {
		if (val < 0) { JVMHelp.wr('-'); val = -val; }
		boolean lead = false;
		for (int div = 1000000000; div > 0; div /= 10) {
			int d = (val / div) % 10;
			if (d != 0 || lead || div == 1) { JVMHelp.wr((char)('0' + d)); lead = true; }
		}
	}

	/** Report a float as an integer scaled by 1000, with a pass/fail band. */
	static void checkF(String what, float got, float lo, float hi) {
		JVMHelp.wr(what);
		JVMHelp.wr(" x1000=");
		wrInt((int)(got * 1000.0f));
		boolean ok = !(got < lo || got > hi);
		JVMHelp.wr(ok ? " ok\n" : " FAIL\n");
		if (!ok) ++fails;
	}

	static void checkD(String what, double got, double lo, double hi) {
		JVMHelp.wr(what);
		JVMHelp.wr(" x1000=");
		wrInt((int)(got * 1000.0));
		boolean ok = !(got < lo || got > hi);
		JVMHelp.wr(ok ? " ok\n" : " FAIL\n");
		if (!ok) ++fails;
	}

	static void checkI(String what, int got, int want) {
		JVMHelp.wr(what);
		JVMHelp.wr(" got ");
		wrInt(got);
		JVMHelp.wr(got == want ? " ok\n" : " FAIL\n");
		if (got != want) ++fails;
	}

	public static void main(String[] args) {

		JVMHelp.wr("MathBugTest start\n");

		// --- absTest ---
		checkI("abs(5)      ", Math.abs(5), 5);
		checkI("abs(-5)     ", Math.abs(-5), 5);
		checkI("abs(-1L)    ", (int)Math.abs(-1L), 1);
		checkF("abs(-3.5f)  ", Math.abs(-3.5f), 3.4f, 3.6f);
		checkD("abs(-7.25)  ", Math.abs(-7.25), 7.24, 7.26);

		// --- minMaxTest ---
		checkI("min(3,7)    ", Math.min(3, 7), 3);
		checkI("max(-1,1)   ", Math.max(-1, 1), 1);
		checkI("min(100L,..)", (int)Math.min(100L, 200L), 100);

		// --- sqrtTest ---
		checkF("sqrt(4.0f)  ", Math.sqrt(4.0f), 1.99f, 2.01f);
		checkD("sqrt(9.0)   ", Math.sqrt(9.0), 2.99, 3.01);
		checkF("sqrt(1.0f)  ", Math.sqrt(1.0f), 0.99f, 1.01f);

		// --- sinTest ---
		checkF("sin(0)      ", Math.sin(0.0f), -0.01f, 0.01f);
		checkF("sin(pi/2)   ", Math.sin((float)(Math.PI / 2.0)), 0.99f, 1.01f);
		checkF("sin(pi)     ", Math.sin((float)Math.PI), -0.10f, 0.10f);
		checkD("sin(pi/2) d ", Math.sin(Math.PI / 2.0), 0.99, 1.01);

		// --- cosTest ---
		checkF("cos(0)      ", Math.cos(0.0f), 0.99f, 1.01f);
		checkF("cos(pi/2)   ", Math.cos((float)(Math.PI / 2.0)), -0.10f, 0.10f);
		checkF("cos(pi)     ", Math.cos((float)Math.PI), -1.05f, -0.95f);
		checkD("cos(0) d    ", Math.cos(0.0), 0.99, 1.01);

		// --- atanTest ---
		checkF("atan(0)     ", Math.atan(0.0f), -0.01f, 0.01f);
		checkF("atan(1)     ", Math.atan(1.0f), 0.70f, 0.90f);

		JVMHelp.wr("fails ");
		wrInt(fails);
		JVMHelp.wr(fails == 0 ? "\nMATHBUG OK\n" : "\nMATHBUG FAIL\n");
		JVMHelp.wr("MathBugTest done\n");
	}
}
