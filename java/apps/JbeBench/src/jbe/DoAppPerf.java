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
	static final String[] NAME = {
		"cycles", "stall", "idle/direct", "bytecode fill", "statics",
		"bounds check", "handle deref", "element", "A$ fill", "GC copy", "other"
	};

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
		int cycles = read(0);
		int stall = read(1);
		LowLevel.msg("== ");
		LowLevel.msg(bench);
		LowLevel.lf();
		LowLevel.msg("cycles", cycles);
		LowLevel.msg("stall", stall);
		if (cycles > 0) {
			// Scale before dividing; cycles can be large, so divide first when
			// it would overflow. 2^31 / 1000 is about 2.1 M.
			int p = (cycles > 2000000) ? (stall / (cycles / 1000)) : (stall * 1000 / cycles);
			LowLevel.msg("stall/1000", p);
		}
		for (int i = 2; i < N; ++i) {
			int v = read(i);
			if (v != 0) {
				LowLevel.msg(NAME[i], v);
				if (stall > 0) {
					int q = (stall > 2000000) ? (v / (stall / 1000)) : (v * 1000 / stall);
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
