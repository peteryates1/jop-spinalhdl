package test;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.GC;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

/**
 * DOES A STOP-THE-WORLD STOP A CORE THAT NEVER TOUCHES MEMORY? -- status item 165.
 *
 * A GC halt reaches a core as `bsy`, and the fetch stage froze on `bsy` only at
 * a microcode `wait`. Only bytecodes that touch memory have one. A loop of
 * local loads and stores, `iinc`, arithmetic and branches has none, so a core
 * running it was marked halted and kept executing.
 *
 * Every core but 0 runs exactly such a loop: 0 + 1 + ... + (N-1), in locals, a
 * pass at a time, each pass checked against N(N-1)/2. Core 0 allocates until it
 * has run MINORS minor collections, then stops the others and reports.
 *
 * Without a stack cache the collector reads another core's stack words through
 * a port that takes over that core's stack-RAM read address (`effectiveRdAddr`
 * in JopCoreConfig.scala). A core still running then reads the words being
 * scanned in place of its own locals, and the pass comes out wrong.
 *
 * PER CORE, not one shared counter: with more than two cores a single `c1Bad`
 * would be overwritten by whichever core finished last, and a core that got a
 * wrong sum could be hidden by one that did not.
 *
 * Run by `JopSmpStackLoopSim`, which also counts, in the RTL, the cycles core 1
 * executed while marked halted; on a board by `hw_verify.py --app
 * Small/SmpStackLoop --expect-text "SMPSTACKLOOP OK"`.
 */
public class SmpStackLoop implements Runnable {

	/** Iterations per pass. Within sipush range, so the loop needs no ldc. */
	static final int N = 20000;
	/** N(N-1)/2 = 199,990,000, which fits an int. */
	static final int WANT = N * (N - 1) / 2;
	/** Minor collections core 0 runs while the others loop. */
	static final int MINORS = 8;

	/** Core 0's, set before the release. NOT a shared `cpuId`: every core's
	 *  main() would write its own id into one static and read back another's,
	 *  so two cores took one slot and a slot was never set -- on a board, at 8
	 *  cores, "not every core started". Each core reads IO_CPU_ID itself. */
	static int cpuCnt;
	static volatile int stop;        // core 0 -> the others
	/** Per core, allocated by core 0 BEFORE the release, so every core sees them. */
	static int[] started, passes, bad, firstBad, done;
	static Object sink;

	/**
	 * Every core but 0. Between the array stores at the top of each pass the
	 * inner loop is iload, iadd, istore, iinc and if_icmpge: no `wait` anywhere.
	 */
	static void looper(int me) {
		started[me] = 1;
		int p = 0, b = 0;
		while (stop == 0) {
			int s = 0;
			for (int i = 0; i < N; i++) s += i;
			if (s != WANT) {
				if (b == 0) firstBad[me] = s;
				b++;
			}
			p++;
			passes[me] = p;
			bad[me] = b;
		}
		done[me] = 1;
		for (;;) { }
	}

	/** How many of cores 1..cpuCnt-1 have a non-zero slot. */
	static int count(int[] a) {
		int n = 0;
		for (int c = 1; c < cpuCnt; c++) if (a[c] != 0) n++;
		return n;
	}

	static void core0() {
		cpuCnt = Native.rdMem(Const.IO_CPUCNT);
		started = new int[cpuCnt];
		passes = new int[cpuCnt];
		bad = new int[cpuCnt];
		firstBad = new int[cpuCnt];
		done = new int[cpuCnt];
		Native.wr(1, Const.IO_SIGNAL);
		int others = cpuCnt - 1;
		for (int i = 0; i < 1000000 && count(started) < others; i++) { }
		if (count(started) < others) {
			JVMHelp.wr("SMPSTACKLOOP STALLED (not every core started)\r\n");
			return;
		}
		int m0 = GC.gcMinorCount;
		while (GC.gcMinorCount - m0 < MINORS) sink = new int[16];
		int minors = GC.gcMinorCount - m0;
		stop = 1;
		for (int i = 0; i < 200000 && count(done) < others; i++) { }
		report(minors);
	}

	static void report(int minors) {
		int totalPasses = 0, totalBad = 0, coresBad = 0, coresIdle = 0, first = 0;
		for (int c = 1; c < cpuCnt; c++) {
			totalPasses += passes[c];
			totalBad += bad[c];
			if (bad[c] != 0) {
				if (coresBad == 0) first = firstBad[c];
				coresBad++;
			}
			if (passes[c] == 0) coresIdle++;
		}
		JVMHelp.wr("SmpStackLoop: cores ");
		wrInt(cpuCnt);
		JVMHelp.wr(" minors ");
		wrInt(minors);
		JVMHelp.wr(" passes ");
		wrInt(totalPasses);
		JVMHelp.wr(" bad ");
		wrInt(totalBad);
		JVMHelp.wr(" on ");
		wrInt(coresBad);
		JVMHelp.wr(" cores");
		if (totalBad != 0) {
			JVMHelp.wr(" first got ");
			wrInt(first);
			JVMHelp.wr(" want ");
			wrInt(WANT);
		}
		JVMHelp.wr("\r\n");
		if (count(done) < cpuCnt - 1) JVMHelp.wr("SMPSTACKLOOP STALLED (not every core finished its pass)\r\n");
		else if (totalBad != 0) JVMHelp.wr("SMPSTACKLOOP FAIL (a core computed a wrong sum)\r\n");
		else if (coresIdle != 0) JVMHelp.wr("SMPSTACKLOOP INCONCLUSIVE (a core finished no pass)\r\n");
		else JVMHelp.wr("SMPSTACKLOOP OK\r\n");
	}

	static void wrInt(int v) { JVMHelp.wr(Integer.toString(v)); }

	public void run() {
		int id = Native.rdMem(Const.IO_CPU_ID);
		if (id == 0) core0();
		else looper(id);
	}

	public static void main(String[] args) {
		new SmpStackLoop().run();
	}
}
