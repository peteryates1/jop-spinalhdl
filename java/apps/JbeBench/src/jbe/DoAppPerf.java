/*
  Memory-stall profile of the DoApp benchmarks, read from the hardware
  performance counters at IO_PERFCNT.

  WHY THIS EXISTS. The same profile is measured in simulation for BRAM, SDR and
  DDR3, but DDR2 cannot be simulated at all -- its controller is Altera
  ALTMEMPHY vendor IP with no model -- so the A-E115FB can only be profiled on
  the board. Running this on every board also checks the simulated numbers
  against the hardware they claim to describe.

  Requires a bitstream built with JopMemoryConfig.hasPerfCounters = true.
  Without it IO_PERFCNT reads as 0 and every category will print zero, which is
  the signature of the wrong bitstream rather than a workload with no stalls.

  Protocol: write n >= 0 selects counter n, write < 0 resets all, read returns
  the selected counter. Categories mirror MemProfile.group in the simulations so
  the two tables can be compared line for line.
*/

package jbe;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.Native;

public class DoAppPerf {

	// Must match Sys.perf.catOf.
	static final int N = 11;

	/* Deliberately a method, not a `static final String[]`. An array initialiser
	   runs in <clinit>, and the array version of this class threw "Uncaught
	   exception" during clazzinit on every board -- it was the only app in the
	   tree using a static String[]. Literals returned from a method are
	   resolved without allocating anything at class-init time. */
	static String name(int i) {
		if (i == 0) return "cycles";
		if (i == 1) return "stall";
		if (i == 2) return "idle/direct";
		if (i == 3) return "bytecode fill";
		if (i == 4) return "statics";
		if (i == 5) return "bounds check";
		if (i == 6) return "handle deref";
		if (i == 7) return "element";
		if (i == 8) return "A$ fill";
		if (i == 9) return "GC copy";
		return "other";
	}

	static void reset() {
		Native.wr(-1, Const.IO_PERFCNT);
	}

	static int read(int idx) {
		Native.wr(idx, Const.IO_PERFCNT);
		return Native.rd(Const.IO_PERFCNT);
	}

	/** Print all counters, plus stall as a permille of cycles.
	  * Permille, not percent: integer division only, and percent loses too much
	  * on categories that are a fraction of a percent. */
	static void dump(String bench) {
		/* SNAPSHOT EVERYTHING FIRST, then print.
		   Reading a counter, printing, then reading the next one measures the
		   printing too: LowLevel.msg does statics and method-cache work, so
		   every category read after the first was inflated. It showed up as the
		   category sum exceeding the `stall` snapshot by a CONSTANT per board --
		   +95.5k on the i5, +170k on the CYC5000, +122k on the A-E115FB,
		   identical across benchmarks because the printing is identical. Only
		   0.09-0.47 %, but it is bias, not noise, and it costs nothing to remove.
		   A local array is fine here; it was a STATIC array that broke <clinit>. */
		int[] v = new int[N];
		for (int i = 0; i < N; ++i) v[i] = read(i);

		int cycles = v[0];
		int stall = v[1];
		LowLevel.msg("== ");
		LowLevel.msg(bench);
		LowLevel.lf();
		LowLevel.msg("cycles", cycles);
		LowLevel.msg("stall", stall);
		if (cycles > 0) {
			int p = (cycles > 2000000) ? (stall / (cycles / 1000)) : (stall * 1000 / cycles);
			LowLevel.msg("stall/1000", p);
		}
		for (int i = 2; i < N; ++i) {
			if (v[i] != 0) {
				LowLevel.msg(name(i), v[i]);
				if (stall > 0) {
					int q = (stall > 2000000) ? (v[i] / (stall / 1000)) : (v[i] * 1000 / stall);
					LowLevel.msg("  of stall/1000", q);
				}
			}
		}
		LowLevel.lf();
	}

	public static void main(String[] args) {

		LowLevel.msg("DoApp memory-stall profile (IO_PERFCNT)");
		LowLevel.lf();

		// One benchmark per reset, so each profile covers only its own work.
		// Execute.perform calibrates by re-running until it exceeds a second,
		// so the counters span the calibration too -- which is fine, it is the
		// same code doing the same thing.
		reset();
		Execute.perform(new BenchKfl());
		dump("Kfl");

		reset();
		Execute.perform(new BenchUdpIp());
		dump("UdpIp");

		reset();
		Execute.perform(new BenchLift());
		dump("Lift");

		LowLevel.msg("profile done");
		LowLevel.lf();
	}
}
