package test;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.GC;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

/**
 * THE MULTI-BANK FLUSH — the coverage gap item 133's fix was never tested
 * against.
 *
 * A halted core's stack cache is flushed to its spill region so the collector
 * can read the stack with ordinary loads. `StackStage` walks the DIRTY banks
 * one per pass, lowest first. Every validation of that so far — simulation and
 * hardware, `SmpGcTest`'s STACKROOT probe — reported `spMin 64 spMax 90`.
 *
 * The banks are 3 x 192 words above a 64-word scratch area:
 *
 *   bank 0: 64..255    bank 1: 256..447    bank 2: 448..639
 *
 * So a stack 90 words deep lives ENTIRELY IN BANK 0. Every passing test
 * exercised the one-dirty-bank case, and the walk across several dirty banks —
 * the actual loop — has never run. A single-bank pass proves the mechanism
 * starts, not that it terminates.
 *
 * WHAT THIS DOES. Core 1 recurses ~100 frames deep (~9.5 words per frame, so
 * SP lands near 1000, past every bank boundary and well into rotation), parks
 * a `Young` object in the DEEPEST frame, and holds it there — the reference
 * exists nowhere else, so the collector can only keep the object alive by
 * scanning the deep stack. Core 0 then runs minor GCs, each of which must halt
 * core 1 and flush every dirty bank before it can scan.
 *
 * WHAT A FAILURE LOOKS LIKE. If the walk stops after one bank, the deep part
 * of the stack is stale in memory: the collector reads words the core never
 * wrote, misses the reference, frees a live object, and `magic` comes back
 * wrong — or the run hangs, if the flush never reports done. `maxScanSp` says
 * how deep the collector actually looked, which distinguishes "scanned and
 * lost it" from "never scanned that far".
 */
public class SmpDeepFlush implements Runnable {

	static final int MAGIC = 0x5A5ADEEF;
	/**
	 * SIMULATION SWITCH, false for hardware.
	 *
	 * true pins the failing gc-then-release order (in sim `IO_US_CNT` is
	 * deterministic, so the per-run coin flip would pick one arm forever) AND
	 * shortens core 0's give-up spin from 400 passes to 12 -- 40M spin
	 * iterations cost under two seconds on the board and hours in Verilator,
	 * so a FAILING sim run would otherwise never finish, and the failing run is
	 * the one worth having.
	 *
	 * Left false so the paired A/B on hardware keeps randomising.
	 */
	static final boolean FORCE_GC_FIRST = false;
	/** ~9.5 words per frame, so ~1000 words: past bank 2 and into rotation. */
	static final int DEPTH = 2;

	static class Young {
		int magic;
		int p0, p1, p2, p3, p4;
	}

	static volatile int ready;       // core 1 -> core 0: reference parked deep
	static volatile int done;        // core 0 -> core 1: collections finished
	static volatile int magicSeen;   // core 1's read-back AFTER the collections
	static volatile int c1Sp, c1Handle, c1Depth;
	/** How deep core 1 has got. Written on the way DOWN, so a core stuck mid
	 *  recursion still reports a level. Volatile, and never read by core 1. */
	static volatile int c1Mark;
	/** Set by core 1 the instant it reaches main(), BEFORE the `new` below --
	 *  so "never reached main" and "died allocating the Runnable" are separable
	 *  rather than both reading as c1Depth == 0. */
	static volatile int c1Main;

	/**
	 * CORE 1'S LIVE PC, SAMPLED IN HARDWARE — the baseline for the probe below.
	 *
	 * Taken twice, back to back, in the instant BEFORE core 1 is released, i.e.
	 * while it is definitely parked in the microcode `cpux_loop`. That is the
	 * control the failure report needs: on a failing run the question is not
	 * "where is core 1" but "is it STILL IN THE SAME LOOP, and is it moving".
	 * Two samples bracket the loop rather than naming one arbitrary point in it.
	 *
	 * Sampled into statics and printed LATER, on the failure path only. Printing
	 * here would put ~200 bytes of UART traffic inside the very window whose
	 * ordering is under test, and the fault is order-dependent and intermittent:
	 * the measurement would move the rate it is measuring.
	 */
	static int bootPc0, bootPc1;

	static int cpuId;

	static void wrInt(int v) { JVMHelp.wr(Integer.toString(v)); }

	static boolean generational() { return GC.nurseryTop != GC.nurseryBase; }

	/**
	 * SIX DIGITS, NO ALLOCATION. `wrInt` goes through `Integer.toString`, which
	 * allocates, and the failure path must not allocate — an allocation there
	 * can trigger the very collection whose flush is under suspicion.
	 *
	 * Masked non-negative before dividing, because two of the values below are
	 * saturating 32-bit counters that read back as -1 once they top out, and
	 * `/` on a negative int would print junk. Six digits cannot hold a saturated
	 * counter anyway: these values are compared between two samples for MOTION,
	 * and the low digits move first.
	 */
	static void wrRaw(int v) {
		int u = (v & 0x7fffffff) % 1000000;
		JVMHelp.wr('0' + (u / 100000) % 10);
		JVMHelp.wr('0' + (u / 10000) % 10);
		JVMHelp.wr('0' + (u / 1000) % 10);
		JVMHelp.wr('0' + (u / 100) % 10);
		JVMHelp.wr('0' + (u / 10) % 10);
		JVMHelp.wr('0' + u % 10);
		JVMHelp.wr(' ');
	}

	/**
	 * ONE SAMPLE of core 1's hardware state, through the GC root port.
	 *
	 * Every value here already exists in the built bitstream — `JopCluster`
	 * hangs two probe banks off the root port's spare targets, `8 + core` for
	 * the bus bank and `12 + core` for the state bank. Nothing is resynthesised
	 * to read them, which is the point: the failing image can be relinked and
	 * rerun in a minute.
	 *
	 * NON-INVASIVE. `gcRootRamAddr(t)` is only driven when the selector's target
	 * field equals t, so a read of target 9 or 13 leaves BOTH cores' stack-RAM
	 * read ports alone. That matters because the thing being measured is a core
	 * that may be mid-stall: a probe that stole its read port would manufacture
	 * the fault it is looking for. (`SmpGcTest.PROBE_RUNNING_CORE` is false for
	 * exactly that reason, and it reads with a nonzero index, which does steal.)
	 *
	 * Split into its own method because JOPizer caps a method at 512 bytes.
	 */
	static void probeSample() {
		int a = GC.rootRead(13, Const.ROOT_WHAT_STACK, 0);
		JVMHelp.wr("pc ");
		wrRaw(a >>> 16);
		JVMHelp.wr("jpc ");
		wrRaw(a & 0xffff);
		// Cycles core 1 has spent HALTED by the lock manager (`Sys.io.halted`,
		// which carries gcHalt during a stop-the-world). A counter, not a level:
		// if this MOVES between the two samples the core is still being held,
		// which is the stuck-flush hypothesis outright — `othersHalted` ANDs in
		// `stackFlushed`, so a flush that never reports done never releases it.
		JVMHelp.wr("halt ");
		wrRaw(GC.rootRead(9, Const.ROOT_WHAT_B, 0));
		int b = GC.rootRead(13, Const.ROOT_WHAT_B, 0);
		// Commands issued minus responses received. Bounded and self-clearing in
		// a healthy core; a value that sits nonzero is a response the arbiter
		// never returned, which is the one hypothesis the flush-FSM testbench
		// could not test (its DMA owns a private single-cycle RAM).
		JVMHelp.wr("bmbOut ");
		wrRaw(b >>> 8);
		JVMHelp.wr("exc ");
		wrRaw(GC.rootRead(13, Const.ROOT_WHAT_A, 0));
		int e = GC.rootRead(13, Const.ROOT_WHAT_SP, 0);
		JVMHelp.wr("excAt ");
		wrRaw(e >>> 16);
		wrRaw(e & 0xffff);
		JVMHelp.wr("excType ");
		wrRaw(b & 0xff);
		JVMHelp.wr("\r\n");
	}

	/**
	 * IS CORE 1 FROZEN, OR IS IT RUNNING AND NOT LEAVING THE LOOP?
	 *
	 * The whole investigation is stuck on that one binary question, and no
	 * instrument so far could answer it: `c1Main`/`c1Depth`/`c1Mark` are written
	 * by core 1's JAVA code, so they are all zero whenever core 1 never reaches
	 * Java, which is exactly the failing case. They say "it did not arrive",
	 * never "it is not moving".
	 *
	 * A live PC does. Two samples, a spin apart:
	 *
	 *   pc/jpc IDENTICAL, near the boot-loop baseline -> frozen in the microcode
	 *       loop. `extStall := stackRotBusy` freezes the fetch PC outright
	 *       (JopPipeline:204) and is the first suspect.
	 *   pc/jpc MOVING within the boot loop -> executing and the loop test never
	 *       passes, i.e. the released level is not being seen. A data path
	 *       problem, not a stall — and a completely different search.
	 *   pc/jpc elsewhere -> core 1 left the loop and derailed; `exc`/`excAt`
	 *       name where.
	 *   halt MOVING -> it is not the core at all, it is still being held halted.
	 *
	 * Four outcomes, mutually exclusive, one run each. That is worth more than
	 * another arm of the rate experiment.
	 */
	static void probeCore1() {
		if (Native.rdMem(Const.IO_CPUCNT) > 4) {
			// Same bound JopCluster uses (`hasProbeBanks`): above 4 cores the
			// 4-bit target field has no room for the banks and `12 + core` wraps
			// onto a real core, which would print convincing nonsense.
			JVMHelp.wr("  (probe banks omitted above 4 cores)\r\n");
			return;
		}
		JVMHelp.wr("  bootPc ");
		wrRaw(bootPc0 >>> 16);
		wrRaw(bootPc0 & 0xffff);
		wrRaw(bootPc1 >>> 16);
		wrRaw(bootPc1 & 0xffff);
		JVMHelp.wr("\r\n  core1 A: ");
		probeSample();
		for (int i = 0; i < 200000; i++) { }
		JVMHelp.wr("  core1 B: ");
		probeSample();
		// HAND THE PORT BACK. Every read above uses index 0, the not-reading
		// sentinel, so nothing is actually held — but the selector is a register
		// and leaving a nonzero index in it steals the target's read port
		// forever. Clearing it unconditionally costs one write and removes the
		// question.
		Native.wr(0, Const.IO_ROOT_SEL);
	}

	static int churnUntilMinor(int budget, int wanted) {
		if (!generational()) return 0;
		int before = GC.nurseryAllocPtr;
		int seen = 0;
		for (int i = 0; i < budget; i++) {
			Object junk = new Young();
			if (junk == null) return seen;
			int now = GC.nurseryAllocPtr;
			if (now > before) { seen++; before = now; if (seen >= wanted) return seen; }
			else before = now;
		}
		return seen;
	}

	/**
	 * Recurse, then hold the ONLY reference to a fresh object in the deepest
	 * frame across core 0's collections. `probe` is read after the wait, so it
	 * stays live for the whole window and the collector must find it on the
	 * deep stack or lose it.
	 */
	/**
	 * WIDE frames, not deep recursion. Reaching SP past the initial 64..639
	 * window needs ~80 plain frames, and past roughly DEPTH 30 core 1
	 * intermittently never reaches main() at all -- 7 attempts at DEPTH 85 all
	 * failed to start. So the frames are made BIGGER instead of more numerous:
	 * 28 live locals is ~37 words per frame, so 30 frames reach ~1200 and the
	 * window MUST slide, which is the rotation-during-flush case.
	 *
	 * The locals are summed after the recursive call so they stay live across
	 * it and cannot be folded away.
	 */
	static int deep(int depth) {
		c1Mark = depth;
		int w0 = depth + 0;
		int w1 = depth + 1;
		int w2 = depth + 2;
		int w3 = depth + 3;
		int w4 = depth + 4;
		int w5 = depth + 5;
		int w6 = depth + 6;
		int w7 = depth + 7;
		int w8 = depth + 8;
		int w9 = depth + 9;
		int w10 = depth + 10;
		int w11 = depth + 11;
		int w12 = depth + 12;
		int w13 = depth + 13;
		int w14 = depth + 14;
		int w15 = depth + 15;
		int w16 = depth + 16;
		int w17 = depth + 17;
		int w18 = depth + 18;
		int w19 = depth + 19;
		int w20 = depth + 20;
		int w21 = depth + 21;
		int w22 = depth + 22;
		int w23 = depth + 23;
		int w24 = depth + 24;
		int w25 = depth + 25;
		int w26 = depth + 26;
		int w27 = depth + 27;
		if (depth > 0) {
			int r = deep(depth - 1);
			return r + ((w0 + w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8 + w9 + w10 + w11 + w12 + w13 + w14 + w15 + w16 + w17 + w18 + w19 + w20 + w21 + w22 + w23 + w24 + w25 + w26 + w27) & 1);
		}

		Young probe = new Young();
		probe.magic = MAGIC;
		c1Sp = Native.getSP();
		c1Handle = Native.toInt(probe);
		ready = 1;
		while (done == 0) { }
		magicSeen = probe.magic;      // survived, or did not
		return 0;
	}

	static void core0() {
		JVMHelp.wr("SmpDeepFlush: cores ");
		wrInt(Native.rdMem(Const.IO_CPUCNT));
		JVMHelp.wr(generational() ? ", generational\r\n" : ", CLASSIC GC\r\n");

		// PAIRED A/B IN ONE IMAGE. The startup fault's rate depends on image
		// LAYOUT, so compiling two variants and comparing them confounds the
		// question with the thing under test: 8-of-8 in one build against
		// 4-of-5 in another proves nothing (p ~ 0.38). Both orders are
		// therefore compiled in and chosen per RUN from a hardware counter, so
		// the two arms share one layout exactly and differ only in order.
		// FORCE_GC_FIRST pins the failing order for SIMULATION, where IO_US_CNT
		// is deterministic and the random pick would choose one arm forever.
		// Left false for hardware so the paired A/B above still randomises.
		boolean gcFirst = FORCE_GC_FIRST || (Native.rd(Const.IO_US_CNT) & 1) == 0;
		JVMHelp.wr(gcFirst ? "MODE gc-then-release\r\n" : "MODE release-then-gc\r\n");
		if (gcFirst) {
			int pre = churnUntilMinor(20000, 1) + churnUntilMinor(20000, 1);
			if (pre < 0) return;   // never; defeats dead-code removal
		}

		// DIAGNOSTIC ORDER: release core 1 BEFORE any collection.
		//
		// Core 0 used to run two minor GCs here first, which is exactly the
		// window in which defect A froze a core still parked in the microcode
		// cpux_loop. A is fixed, but this app still fails to start core 1
		// intermittently, so the coincidence is worth removing: if core 1
		// always starts when released into a heap that has never been
		// collected, a boot-time GC is implicated; if it still fails, the GC is
		// exonerated and the cause is elsewhere.
		//
		// It costs the test nothing -- core 1 parks deep either way, and core 0
		// collects afterwards.
		// THE CONTROL, taken while core 1 is provably still parked. Two reads
		// back to back so the pair brackets the microcode loop; no printing, so
		// the release is not delayed. See bootPc0/bootPc1.
		bootPc0 = GC.rootRead(13, Const.ROOT_WHAT_STACK, 0);
		bootPc1 = GC.rootRead(13, Const.ROOT_WHAT_STACK, 0);
		Native.wr(0, Const.IO_ROOT_SEL);

		Native.wr(1, Const.IO_SIGNAL);

		// The give-up bound is 400 passes on hardware, where 40M spin iterations
		// cost under two seconds. In SIMULATION that is hours, so a FAILING run
		// would never finish -- and a failing run is the one we are hunting.
		// FORCE_GC_FIRST marks a sim build, so shorten it there.
		int limit = FORCE_GC_FIRST ? 12 : 400;
		int spins = 0;
		for (int o = 0; o < limit && ready == 0; o++) {
			for (int i = 0; i < 100000 && ready == 0; i++) { }
			spins = o;
		}
		if (ready == 0) {
			// NO ALLOCATION IN THIS PATH. wrInt -> Integer.toString allocates,
			// and an allocation here can trigger the very collection whose
			// flush is under suspicion -- the first version of this test
			// truncated mid-`wrInt`, which is indistinguishable from the bug.
			// Fixed strings only, so the report always gets out.
			JVMHelp.wr("SmpDeepFlush: core 1 never parked. ");
			if (c1Main == 0) JVMHelp.wr("never reached main()\r\n");
			else if (c1Depth == 0) JVMHelp.wr("reached main(), died before run()\r\n");
			else if (c1Mark == DEPTH) JVMHelp.wr("STARTED, still at the TOP frame\r\n");
			else if (c1Mark > 0) JVMHelp.wr("DESCENDING, stuck mid-recursion\r\n");
			else JVMHelp.wr("reached the BOTTOM but never published\r\n");
			probeCore1();
			JVMHelp.wr("SmpDeepFlush INCONCLUSIVE\r\n");
			return;
		}

		JVMHelp.wr("SmpDeepFlush: core1 parked at sp ");
		wrInt(c1Sp);
		JVMHelp.wr(" depth ");
		wrInt(c1Depth);
		JVMHelp.wr(" handle ");
		wrInt(c1Handle);
		// The point of the whole exercise: which banks that SP spans.
		// The three banks initially cover 64..639 as one contiguous window, so
		// an SP beyond 639 PROVES a rotation happened -- the window had to
		// slide, evicting and rebasing a bank. That is the case where a
		// rotation can collide with a flush walk, which the flush claims to
		// handle ("re-entering IDLE between each so a rotation would still
		// win") and which nothing had ever exercised.
		JVMHelp.wr(c1Sp > 639 ? " (3 banks + ROTATION)\r\n"
				: c1Sp > 448 ? " (spans banks 0,1,2)\r\n"
				: c1Sp > 256 ? " (spans banks 0,1)\r\n" : " (bank 0 ONLY — no new coverage)\r\n");

		// Each of these halts core 1 and must flush every dirty bank first.
		int pm = churnUntilMinor(20000, 3) + churnUntilMinor(20000, 3);
		JVMHelp.wr("SmpDeepFlush: minors ");
		wrInt(pm);
		JVMHelp.wr(" scanned sp ");
		wrInt(GC.minScanSp);
		JVMHelp.wr("..");
		wrInt(GC.maxScanSp);
		JVMHelp.wr(" words ");
		wrInt(GC.otherRootWords);
		JVMHelp.wr("\r\n");

		// Core 0's own view of the object, before releasing core 1.
		int hPtr = Native.rdMem(c1Handle);
		JVMHelp.wr("core0 view: ptr ");
		wrInt(hPtr);
		JVMHelp.wr(" magic ");
		wrInt(hPtr != 0 ? Native.rdMem(hPtr) : -1);
		JVMHelp.wr("\r\n");

		done = 1;
		for (int o = 0; o < 400 && magicSeen == 0; o++)
			for (int i = 0; i < 100000 && magicSeen == 0; i++) { }

		JVMHelp.wr("core1 read back magic ");
		wrInt(magicSeen);
		JVMHelp.wr(magicSeen == MAGIC ? " OK\r\n" : " WRONG\r\n");

		// Banks are 64..255 / 256..447 / 448..639, so SP past 256 means the
		// flush had to walk MORE THAN ONE dirty bank -- the loop that every
		// previous validation (spMax 90) left unexercised.
		// c1Sp IS WRITTEN ONCE, by core 1 at the deepest frame. A run printed
		// "sp 478 (spans banks 0,1,2)" and then the verdict for c1Sp > 639, so
		// it read differently at two points in the same method. Re-read it here
		// and print it: either the two agree and the first reading was
		// misparsed, or a static is changing under us, which is its own defect.
		JVMHelp.wr("c1Sp re-read ");
		wrInt(c1Sp);
		JVMHelp.wr(" maxScanSp ");
		wrInt(GC.maxScanSp);
		JVMHelp.wr("\r\n");
		boolean twoBanks = c1Sp > 256;
		boolean threeBanks = c1Sp > 448;
		boolean scannedDeep = GC.maxScanSp > 256;
		boolean survived = magicSeen == MAGIC;
		if (!twoBanks) JVMHelp.wr("SmpDeepFlush INCONCLUSIVE (one bank only — no new coverage)\r\n");
		else if (!scannedDeep) JVMHelp.wr("SmpDeepFlush FAIL (collector never scanned past bank 0)\r\n");
		else if (!survived) JVMHelp.wr("SmpDeepFlush FAIL (live object lost from a deep frame)\r\n");
		else if (c1Sp > 639) JVMHelp.wr("SmpDeepFlush OK (three banks + rotation)\r\n");
		else if (threeBanks) JVMHelp.wr("SmpDeepFlush OK (three banks, no rotation)\r\n");
		else JVMHelp.wr("SmpDeepFlush OK (two banks)\r\n");
	}

	public void run() {
		if (cpuId == 0) core0();
		else { c1Depth = DEPTH; deep(DEPTH); for (;;) { } }
	}

	public static void main(String[] args) {
		cpuId = Native.rdMem(Const.IO_CPU_ID);
		if (cpuId != 0) c1Main = 1;      // before ANY allocation
		new SmpDeepFlush().run();
	}
}
