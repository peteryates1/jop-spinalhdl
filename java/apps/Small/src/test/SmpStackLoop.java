package test;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.GC;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

/**
 * DOES A STOP-THE-WORLD STOP A CORE THAT NEVER TOUCHES MEMORY? -- status item 165.
 *
 * A GC halt reaches a core as `bsy`, and the fetch stage freezes on `bsy` only
 * at a microcode `wait`. Only bytecodes that touch memory have one. A loop of
 * local loads and stores, `iinc`, arithmetic and branches has none, so a core
 * running it is marked halted and keeps executing.
 *
 * Core 1 runs exactly such a loop: 0 + 1 + ... + (N-1), in locals, a pass at a
 * time, each pass checked against N(N-1)/2. Core 0 allocates until it has run
 * MINORS minor collections, then stops core 1 and reports.
 *
 * Without a stack cache the collector reads core 1's stack words through a
 * port that takes over core 1's stack-RAM read address (`effectiveRdAddr` in
 * JopCoreConfig.scala). A core 1 still running then reads the words being
 * scanned in place of its own locals, and the pass comes out wrong.
 *
 * Run by `JopSmpStackLoopSim`, which also counts, in the RTL, the cycles core 1
 * executed while marked halted.
 */
public class SmpStackLoop implements Runnable {

	/** Iterations per pass. Within sipush range, so the loop needs no ldc. */
	static final int N = 20000;
	/** N(N-1)/2 = 199,990,000, which fits an int. */
	static final int WANT = N * (N - 1) / 2;
	/** Minor collections core 0 runs while core 1 loops. */
	static final int MINORS = 8;

	static int cpuId;
	static volatile int stop;        // core 0 -> core 1
	static volatile int c1Started;   // core 1 -> core 0
	static volatile int c1Passes, c1Bad, c1FirstBad, c1Done;
	static Object sink;

	/**
	 * Core 1. Between the volatile accesses at the top of each pass, the inner
	 * loop is iload, iadd, istore, iinc and if_icmplt: no `wait` anywhere.
	 */
	static void core1() {
		c1Started = 1;
		int passes = 0, bad = 0;
		while (stop == 0) {
			int s = 0;
			for (int i = 0; i < N; i++) s += i;
			if (s != WANT) {
				if (bad == 0) c1FirstBad = s;
				bad++;
			}
			passes++;
			c1Passes = passes;
			c1Bad = bad;
		}
		c1Done = 1;
		for (;;) { }
	}

	static void core0() {
		Native.wr(1, Const.IO_SIGNAL);
		for (int i = 0; i < 1000000 && c1Started == 0; i++) { }
		if (c1Started == 0) {
			JVMHelp.wr("SMPSTACKLOOP STALLED (core 1 never started)\r\n");
			return;
		}
		int m0 = GC.gcMinorCount;
		while (GC.gcMinorCount - m0 < MINORS) sink = new int[16];
		int minors = GC.gcMinorCount - m0;
		stop = 1;
		for (int i = 0; i < 200000 && c1Done == 0; i++) { }
		report(minors);
	}

	static void report(int minors) {
		JVMHelp.wr("SmpStackLoop: minors ");
		wrInt(minors);
		JVMHelp.wr(" passes ");
		wrInt(c1Passes);
		JVMHelp.wr(" bad ");
		wrInt(c1Bad);
		if (c1Bad != 0) {
			JVMHelp.wr(" first got ");
			wrInt(c1FirstBad);
			JVMHelp.wr(" want ");
			wrInt(WANT);
		}
		JVMHelp.wr("\r\n");
		if (c1Done == 0) JVMHelp.wr("SMPSTACKLOOP STALLED (core 1 never finished its pass)\r\n");
		else if (c1Bad != 0) JVMHelp.wr("SMPSTACKLOOP FAIL (core 1 computed a wrong sum)\r\n");
		else JVMHelp.wr("SMPSTACKLOOP OK\r\n");
	}

	static void wrInt(int v) { JVMHelp.wr(Integer.toString(v)); }

	public void run() {
		if (cpuId == 0) core0();
		else core1();
	}

	public static void main(String[] args) {
		cpuId = Native.rdMem(Const.IO_CPU_ID);
		new SmpStackLoop().run();
	}
}
