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
	static final int DEPTH = 30;

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
	/**
	 * DID CORE 1 BOOT *EARLY*? — the hole the debounce left, status item 133.
	 *
	 * The park loop now needs TWO consecutive nonzero `io_signal` reads to
	 * proceed, so a single spurious nonzero sends the core back to waiting
	 * instead of into the downloader. But two of them in a row still fall through
	 * to `jmp cpux_boot`, and if the signal was never set that is an EARLY BOOT:
	 * core 1 starts main() before core 0 released it.
	 *
	 * There is an argument that a halt-correlated transient cannot do that — the
	 * two reads are separated by a `wait`, a halt raises bsy, so a halted core
	 * parks INSIDE the first read and cannot reach the second until the halt
	 * releases, leaving at most one read corrupted. And the transient is
	 * halt-correlated: 103 release-then-gc runs across every soak, 0 failures.
	 *
	 * But that is inference from an arm asymmetry, not knowledge of the
	 * mechanism, and four mechanism hypotheses have already died in this item.
	 * `SmpDeepFlush` checks "core 1 never parked"; it has never checked "core 1
	 * parked TOO SOON", and in this app an early boot would probably still pass
	 * because the `ready`/`done` handshake absorbs it. So the 0-of-23 result
	 * says nothing about it either way.
	 *
	 * These are sampled in the same instant as bootPc0/bootPc1 — immediately
	 * before `Native.wr(1, IO_SIGNAL)` — and reported on BOTH paths, because a
	 * passing run is exactly where an undetected early boot would hide.
	 *
	 * Reading: `earlyMain` nonzero means core 1 reached Java before the release,
	 * which is unambiguous. `bootPc` above the park loop's last address (0x16)
	 * means it had left the loop FORWARD, heading for cpux_boot or cpu0_load. A
	 * bootPc BELOW 0x0e is not a fault — in the release-then-gc arm the release
	 * comes so early that core 1 can still be in the boot prologue.
	 */
	static int earlyMain;
	/** Core 1's live SP while still parked — see the sample site. */
	static int bootSp;
	/**
	 * DO WE SEE BAD READS ELSEWHERE? -- the question the localisation raised and
	 * could not answer.
	 *
	 * The startup fault needs core 1, parked in the microcode `cpux_loop`, to
	 * read `io_signal` as NONZERO while it is still zero. `io_signal` reads
	 * `syncIn.s_out`, which is `io.syncOut(i).s_out := io.syncIn(0).s_in` -- a
	 * PURELY COMBINATIONAL broadcast of core 0's `signalReg` (CmpSync:178) --
	 * and an I/O read never leaves the core, so there is no SDRAM, no arbiter
	 * and no DMA response anywhere in that path. Whatever goes wrong happens
	 * INSIDE the core, between `stmra` and `ldmrd`.
	 *
	 * So: does a halted core mis-read at all? These count it. Core 1 checks
	 * three values it already knows, in its deep frame, across every collection
	 * core 0 runs -- which is exactly when it is halted and its banks flushed:
	 *
	 *   IO_CPU_ID  -- `B(cpuId, 32 bits)`, a LITERAL in the read mux
	 *                 (Sys.scala:459). Hardwired 1 on core 1, so any other value
	 *                 is an I/O read that did not deliver its own mux output.
	 *                 This is the cleanest detector in the machine.
	 *   IO_SIGNAL  -- the register actually implicated, 1 by now. Read BOTH ways
	 *                 because `Native.rd` and `Native.rdMem` are different
	 *                 microcode sequences and the boot loop uses the latter's
	 *                 shape (`stmra`/`wait`/`wait`/`ldmrd`); if one is clean and
	 *                 the other is not, that alone localises it.
	 *   word 1     -- a real EXTERNAL memory read, snapshotted once and then
	 *                 re-read. Separates "I/O reads are fine, memory reads are
	 *                 not" from the reverse. It is `mp`, written at boot by
	 *                 cpux_boot and constant thereafter.
	 *
	 * A nonzero count is the defect caught in the act. All zero across millions
	 * of reads would mean a halted core's ORDINARY reads are sound and the fault
	 * needs the specific instant the halt lands mid-read, which the park loop
	 * hits almost every time because it is nothing but back-to-back reads.
	 */
	static volatile int badCpuId, badSigRd, badSigMem, badWord1, verifyLoops;
	/**
	 * Set by core 0 once it has written 0 to IO_SIGNAL — see the clear site.
	 *
	 * WHY THE CHECKS NEEDED THIS. `Sys.io.addr` is FOUR BITS and the read mux has
	 * IO_CPU_ID at 6 and IO_SIGNAL at 7 (Sys.scala:459-460), which differ in BIT 0
	 * ALONE. So a single-bit error in the I/O read address turns a read of
	 * io_signal into a read of io_cpu_id — and on core 1 that is 1, exactly the
	 * spurious nonzero the park loop needs to escape its spin while the signal is
	 * still zero. Addr 5 is IO_LOCK, whose bit 0 is `syncIn.halted`, also 1 during
	 * a stop-the-world; it is bit 1 away.
	 *
	 * AND THE EARLIER VERSION OF THESE CHECKS COULD NOT SEE ANY OF THAT, because
	 * every value it compared was 1: io_cpu_id is 1, and io_signal AFTER the
	 * release is also 1, so a read that returned the neighbouring register
	 * returned the expected answer. 186.9 million checks across 612 halt events,
	 * zero bad, and structurally blind to the one mechanism that fits.
	 *
	 * Clearing io_signal once both cores are running restores the asymmetry the
	 * park loop has: the expected value becomes 0, so a neighbour read shows up.
	 * It is inert to do — `signalReg` is read only by the boot path, and core 1 is
	 * long past it.
	 */
	static volatile int sigCleared;
	/** Snapshot of word 1, taken by core 1 before the collections start. */
	static volatile int word1Ref;


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
		JVMHelp.wr("sp ");
		wrRaw(GC.rootRead(1, Const.ROOT_WHAT_SP, 0));
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
		JVMHelp.wr("bootSp ");
		wrRaw(bootSp);
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
		word1Ref = Native.rdMem(1);
		ready = 1;
		// WAS `while (done == 0) { }`. Same wait, same deep frame, but every pass
		// re-checks three known values, so core 0's collections are now a
		// measurement instead of an empty spin. No allocation: this frame holds
		// the only reference to `probe`, and allocating here would change the
		// very collection under test.
		verifyDeep();
		magicSeen = probe.magic;      // survived, or did not
		return 0;
	}

	/**
	 * Core 1's wait, instrumented. Runs in the DEEPEST frame, so the reference
	 * under test stays live and the flush that copies this frame out is the one
	 * being measured. Kept separate from run() for JOPizer's 512-byte cap.
	 */
	static void verifyDeep() {
		int n = 0, bc = 0, bs = 0, bm = 0, bw = 0;
		int ref = word1Ref;
		while (done == 0) {
			if (Native.rdMem(Const.IO_CPU_ID) != 1) bc++;
			// ONLY MEANINGFUL ONCE CORE 0 HAS CLEARED IT. Until then io_signal
			// reads 1 and is indistinguishable from io_cpu_id, which is the blind
			// spot described at `sigCleared`. After the clear a NONZERO here is
			// the address-glitch signature.
			if (sigCleared != 0) {
				if (Native.rd(Const.IO_SIGNAL) != 0) bs++;
				if (Native.rdMem(Const.IO_SIGNAL) != 0) bm++;
			}
			if (Native.rdMem(1) != ref) bw++;
			n++;
		}
		// Published once at the end rather than per pass: a volatile store every
		// iteration would be a memory write inside the window being measured.
		verifyLoops = n; badCpuId = bc; badSigRd = bs; badSigMem = bm; badWord1 = bw;
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
		// CAN THE FLUSH EVEN BE INVOLVED? The flush FSM only starts on
		// `gcFlushReq && anyDirty` (StackStage.scala:872) and a clean cache
		// reports `gcFlushDone` immediately with `rotBusy` never asserted
		// (:813), so a core whose stack has not left the 64-word scratch area
		// is never flushed and never stalled by one. Core 1 parked in the
		// microcode cpux_loop has barely touched its stack. If this SP is at or
		// below the scratch size then "the flush of a parked core" is the wrong
		// title for this fault and what is left is the HALT, which is OR'd into
		// memBusy (JopCore.scala:340).
		//
		// Target 1 with INDEX 0 reads core 1's live SP register and touches no
		// RAM port: gcRootRamAddr(1) is sel(7..0) = 0, the not-reading
		// sentinel. (SmpGcTest gates its SP read behind PROBE_RUNNING_CORE
		// only because it is grouped with stack-WORD reads, which do steal.)
		bootSp = GC.rootRead(1, Const.ROOT_WHAT_SP, 0);
		earlyMain = c1Main;   // nonzero => core 1 was in Java before the release
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

		// CLEAR io_signal so core 1's checks stop expecting 1 — see `sigCleared`.
		// Core 1 is parked in Java by now (it published `ready`), and the boot
		// path that reads this signal is long behind it, so the write is inert to
		// everything except the detector.
		Native.wr(0, Const.IO_SIGNAL);
		sigCleared = 1;

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

		// DID A HALTED CORE EVER MIS-READ? See the counter declarations. Printed
		// unconditionally, including the exposure: "0 bad" means nothing without
		// the number of reads it is 0 out of.
		JVMHelp.wr("core1 reads ");
		wrInt(verifyLoops);
		JVMHelp.wr(" bad: cpuId ");
		wrInt(badCpuId);
		JVMHelp.wr(" sigRd ");
		wrInt(badSigRd);
		JVMHelp.wr(" sigMem ");
		wrInt(badSigMem);
		JVMHelp.wr(" word1 ");
		wrInt(badWord1);
		JVMHelp.wr(" sigCleared ");
		wrInt(sigCleared);
		// THE EARLY-BOOT CHECK, printed on the passing path too -- see earlyMain.
		JVMHelp.wr("\r\nEARLYBOOT main ");
		wrInt(earlyMain);
		JVMHelp.wr(" bootPc ");
		wrInt(bootPc0 >>> 16);
		JVMHelp.wr(",");
		wrInt(bootPc1 >>> 16);
		JVMHelp.wr((earlyMain == 0 && (bootPc0 >>> 16) <= 0x16 && (bootPc1 >>> 16) <= 0x16)
				? " (no early boot)" : " *** EARLY BOOT ***");
		JVMHelp.wr(badCpuId + badSigRd + badSigMem + badWord1 == 0
				? " (all clean)\r\n" : " *** MIS-READ ***\r\n");

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
