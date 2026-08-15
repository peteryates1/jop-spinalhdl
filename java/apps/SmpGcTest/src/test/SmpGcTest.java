package test;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.GC;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

/**
 * Multi-core allocating workload — the failing test for current-status item 1.
 *
 * Item 1: generational GC is unsound on SMP. The card table is per core and
 * `IO_CARD_*` is decoded per core, so a minor GC on core 0 reads only core 0's
 * table. A tenured->nursery reference written by core 1 leaves its mark in core
 * 1's table, core 0 never sees it, and that young object is collected while
 * still live. `GC.init` currently guards this off (`cpuCnt > 1` falls back to
 * classic) — this test is what must fail with the guard REMOVED, and pass again
 * once the card table becomes one cluster-level resource fed from the arbiter.
 *
 * The docs are explicit that the test is the bulk of item 1, and that the
 * existing SMP GC tests would pass either way because none of them constructs a
 * cross-core old->young reference. This one does:
 *
 *   core 0   allocates HOLDERS holders, then allocates enough garbage that they
 *            survive minor GCs and are promoted to the tenured region.
 *   core 1   allocates a fresh Young object (nursery) and stores it into a
 *            TENURED holder — the cross-generation write. Nothing else refers
 *            to that object.
 *   core 0   allocates until the nursery is exhausted, which is what invokes
 *            minorGc() (it is package-private; driving it by allocation avoids
 *            changing the runtime just to test it). A minor GC is *detected*
 *            rather than assumed, by watching GC.nurseryAllocPtr jump back up.
 *   core 0   re-reads every holder and checks the magic word.
 *
 * A collected-while-live object shows up as a wrong magic, because the space is
 * reused by later allocation. That is the whole signal.
 *
 * TWO HAZARDS that would make this pass for the wrong reason, i.e. turn it into
 * another test that cannot fail:
 *
 *  1. STACK RESIDUE. JOP scans stacks conservatively. If core 1 leaves the
 *     Young reference in a stack slot, it stays reachable as a root and
 *     survives regardless of the card table. `publish()` therefore takes the
 *     reference no further than it must, nulls its local, and returns through
 *     `scrub()` which overwrites the frame with unrelated values.
 *  2. A FULL GC INSTEAD OF A MINOR ONE. majorGc() traces the tenured region
 *     too, so it finds the reference by walking and the bug is invisible.
 *     minorGc() falls back to majorGc() when tenure cannot fit the nursery, so
 *     the test keeps allocation small relative to the heap and reports the GC
 *     counts it actually observed.
 *
 * Reported counts let a reader tell a real pass from a vacuous one: "minors=0"
 * means nothing was exercised.
 */
public class SmpGcTest {

	static final int HOLDERS = 24;
	/** Distinctive, and not a plausible value for freed-then-reused memory. */
	static final int MAGIC_BASE = 0x5A5A0000;

	static class Holder {
		Object ref;
		int slot;
	}

	static class Young {
		int magic;
		// Padding so a Young occupies several words: a partially-overwritten
		// object is then far more likely to show a wrong magic than to coincide.
		int p0, p1, p2, p3, p4;
	}

	static Holder[] holders;

	/**
	 * Exceptions caught inside publisher(), per core, and the pubStep at which
	 * the last one was thrown.
	 *
	 * WHY THIS EXISTS: at 4 cores the hardware exception counter reported one
	 * EXC_AB (type 3, array bounds) per publisher, latched at the microcode
	 * bounds-check site. Uncaught, an ArrayIndexOutOfBoundsException unwinds
	 * straight out of publisher() and out of main(), and the core PARKS — which
	 * is exactly what a wedged publisher looks like from outside (heartbeat
	 * stops, bus requests stop, and if the throw happened inside the allocator's
	 * `synchronized (mutex)` the global lock is never released and takes the
	 * whole cluster with it).
	 *
	 * Catching and retrying turns that into a measurement: every index used in
	 * this loop is a constant or a core id, so if the retry SUCCEEDS the index
	 * was valid and the bounds check itself read the wrong array length.
	 */
	static int[] pubExcCnt;
	static int[] pubExcStep;

	/**
	 * Handle of `liveTick`, captured by core 0 so the publishers can read the
	 * SAME length word two different ways every iteration.
	 *
	 * The bounds fault reports length 0 for a word that holds 4, and the heap is
	 * mostly zeros, so "wrong data" and "wrong address" are indistinguishable
	 * from that one number. These two counters separate the paths instead:
	 *   rawLenBad  Native.rdMem(handle+1) -- the plain memory-read state machine
	 *   aLenBad    liveTick.length        -- the handle/array state machine
	 * Both read the identical word over the same BMB port, so
	 *   rawLenBad > 0 => the memory system returns wrong data, and the array
	 *                    path is just where it happens to show
	 *   rawLenBad = 0 and aLenBad > 0 => only the handle path is wrong, and the
	 *                    memory system is exonerated
	 */
	static int liveTickHandle;
	static int[] rawLenBad;
	static int[] aLenBad;

	static volatile int phase;        // 0 = idle, 1 = publishers run, 2 = core0 verifies
	static volatile int publishRound;

	/**
	 * Per-core progress: pubRound[p] is the next round core p has not yet
	 * published. An array rather than one counter because every publisher must
	 * be waited on independently — with a single counter, one slow core is
	 * indistinguishable from one that has finished, and core 0 would verify
	 * holders nobody had written yet. Allocated by core 0 before tenuring, so it
	 * is itself tenured and the writes into it are ordinary SMP array traffic
	 * (A$ snoop invalidation, which SmpCacheTest T1 already proves works).
	 */
	static int[] pubRound;

	/**
	 * Heartbeat per publisher, bumped every loop iteration. Distinguishes the
	 * two hangs that look identical from core 0: a core that has stopped
	 * executing, versus a core still spinning whose pubRound write core 0
	 * cannot see.
	 */
	static int[] liveTick;

	/**
	 * Where each publisher is, to the statement. `liveTick` says only THAT a
	 * publisher stopped; this says WHERE. The observed freeze is inside
	 * `publish()` — heartbeat stuck at a fixed value while core 0 runs on — and
	 * the whole question is which statement it died on, because they do very
	 * different things: allocate a nursery object, write its fields, or store it
	 * into a TENURED holder (the cross-generation write the card table exists
	 * for).
	 *
	 * Codes: 1 entered publish, 2 allocated, 3 magic written, 4 padding written,
	 * 5 stored into the holder, 6 returned, 7 scrubbed, 10 loop top,
	 * 11 entered the publish batch, 12 batch done.
	 */
	static int[] pubStep;
	/** Which holder slot a publisher was on when it stopped. */
	static int[] pubSlot;

	static int cpuCnt;
	static int publishers;            // cores 1..cpuCnt-1

	static int errors;
	static int stalls;
	static int minors;
	static int verified;

	// ---------------------------------------------------------------- helpers

	static void wrInt(int val) {
		if (val < 0) { JVMHelp.wr('-'); val = -val; }
		boolean lead = false;
		for (int div = 1000000000; div > 0; div /= 10) {
			int d = (val / div) % 10;
			if (d != 0 || lead || div == 1) { JVMHelp.wr((char) ('0' + d)); lead = true; }
		}
	}

	/**
	 * Overwrite the current frame with values that cannot be mistaken for a
	 * reference, so a conservative stack scan does not keep a dead Young alive.
	 * Called on the way out of publish().
	 */
	static int scrub() {
		int a = 1, b = 2, c = 3, d = 4, e = 5, f = 6, g = 7, h = 8;
		for (int i = 0; i < 8; i++) {
			a += i; b += a; c += b; d += c; e += d; f += e; g += f; h += g;
		}
		return a ^ b ^ c ^ d ^ e ^ f ^ g ^ h;
	}

	/**
	 * True when a nursery exists, i.e. the collector is generational. GC.init
	 * sets nurseryBase == nurseryTop for the classic heap, which is what the SMP
	 * guard currently forces.
	 */
	/** The card-table bit covering a word address, read the same way
	 *  `GC.scanCardRange` reads it: 32 cards per readable word. */
	static int cardBit(int addr) {
		int card = addr >>> Native.rd(Const.IO_CARD_SHIFT);
		Native.wr(card >>> 5, Const.IO_CARD_IDX);
		return (Native.rd(Const.IO_CARD_DATA) >>> (card & 31)) & 1;
	}

	static boolean generational() {
		return GC.nurseryTop != GC.nurseryBase;
	}

	/**
	 * Allocate garbage until a minor GC is observed, or budget runs out.
	 *
	 * Returns immediately with no nursery: there can be no minor GC to wait for,
	 * so churning the full budget only burns time. That matters — in simulation
	 * the two tenuring calls alone ran past 45M cycles before this check existed,
	 * and JopIhluGcBramSim gave up at its 100M limit having never reached the
	 * interesting part.
	 */
	static int churnUntilMinor(int budget) {
		return churnUntilMinor(budget, 1);
	}

	/**
	 * Churn until `wanted` minor GCs have been observed, or the budget runs out.
	 *
	 * The budget is a SAFETY LIMIT, not a target. This used to run every one of
	 * its 20000 allocations no matter how many minors it had already seen, which
	 * is why the two tenuring calls alone cost ~80M cycles in simulation and the
	 * probe past them was never reached inside a sane cycle cap. Stopping at
	 * `wanted` cuts tenuring to a few hundred allocations and leaves the observed
	 * minor count — the thing every recorded result is quoted against — unchanged.
	 */
	static int churnUntilMinor(int budget, int wanted) {
		if (!generational()) return 0;
		int before = GC.nurseryAllocPtr;
		int seen = 0;
		for (int i = 0; i < budget; i++) {
			Object junk = new Young();
			if (junk == null) return seen;           // never; defeats dead-code removal
			int now = GC.nurseryAllocPtr;
			// The bump pointer grows DOWN; a minor GC re-carves the nursery, so
			// the pointer jumping back UP is the observable event.
			if (now > before) {
				seen++;
				before = now;
				if (seen >= wanted) return seen;
			}
			else before = now;
		}
		return seen;
	}

	// ---------------------------------------------------------------- core 0

	static void core0() {
		JVMHelp.wr("SmpGcTest: cores ");
		wrInt(cpuCnt);
		JVMHelp.wr(", publishers ");
		wrInt(publishers);
		JVMHelp.wr("\r\n");
		pubRound = new int[cpuCnt];
		liveTick = new int[cpuCnt];
		pubStep = new int[cpuCnt];
		pubSlot = new int[cpuCnt];
		pubExcCnt = new int[cpuCnt];
		pubExcStep = new int[cpuCnt];
		rawLenBad = new int[cpuCnt];
		aLenBad = new int[cpuCnt];
		liveTickHandle = Native.toInt(liveTick);
		for (int p = 0; p < cpuCnt; p++) {
			pubRound[p] = 0; liveTick[p] = 0; pubStep[p] = 0; pubSlot[p] = -1;
		}
		holders = new Holder[HOLDERS];
		for (int i = 0; i < HOLDERS; i++) {
			holders[i] = new Holder();
			holders[i].slot = i;
			holders[i].ref = null;
		}

		// Promote the holders: they are live across minor GCs, so surviving a
		// few moves them into the tenured region. Without this the holders are
		// themselves young and the write is young->young, which the card table
		// is not involved in at all.
		JVMHelp.wr("SmpGcTest: tenuring\r\n");
		minors += churnUntilMinor(20000);
		minors += churnUntilMinor(20000);

		JVMHelp.wr("SmpGcTest: minors after tenuring ");
		wrInt(minors);
		JVMHelp.wr("\r\n");

		JVMHelp.wr("layout: cardShift ");
		wrInt(Native.rd(Const.IO_CARD_SHIFT));
		JVMHelp.wr(" nurseryBase ");
		wrInt(GC.nurseryBase);
		JVMHelp.wr("\r\n");

		// The handles of every array publisher() indexes. The bounds fault
		// reports the handle it faulted on; without this there is no way to tell
		// "the length read came back wrong" from "the handle was wrong".
		JVMHelp.wr("arrays: liveTick ");
		wrInt(Native.toInt(liveTick));
		JVMHelp.wr(" pubStep ");
		wrInt(Native.toInt(pubStep));
		JVMHelp.wr(" pubRound ");
		wrInt(Native.toInt(pubRound));
		JVMHelp.wr(" pubSlot ");
		wrInt(Native.toInt(pubSlot));
		JVMHelp.wr(" pubExcCnt ");
		wrInt(Native.toInt(pubExcCnt));
		JVMHelp.wr(" pubExcStep ");
		wrInt(Native.toInt(pubExcStep));
		JVMHelp.wr(" holders ");
		wrInt(Native.toInt(holders));
		JVMHelp.wr(" len ");
		wrInt(cpuCnt);
		JVMHelp.wr("\r\n");

		Native.wr(1, Const.IO_SIGNAL);   // release core 1

		// Run the stack-root test before the main rounds.
		if (publishers >= 1) {
			while (stackProbeReady == 0) { }
			// 3 + 3 minors, matching the `STACKROOT minors 6` run every recorded
			// result is quoted against. Core 1 holds the reference throughout.
			int pm = churnUntilMinor(20000, 3) + churnUntilMinor(20000, 3);
			// Dump core 1's stack RAM while it is still holding the reference.
			// GATED: this reads a RUNNING core's stack RAM — see PROBE_RUNNING_CORE.
			int portSp = PROBE_RUNNING_CORE ? GC.rootRead(1, Const.ROOT_WHAT_SP, 0) : -1;
			int foundAt = -1;
			int nonZero = 0;
			for (int i = 0; PROBE_RUNNING_CORE && i < 256; i++) {
				int w = GC.rootRead(1, Const.ROOT_WHAT_STACK, i);
				if (w != 0) nonZero++;
				if (w == stackProbeHandle && foundAt < 0) foundAt = i;
			}
			// RELEASE THE PORT. Core 1 is RUNNING throughout this dump (it spins
			// on stackProbeGcDone holding the reference), so leaving rootSel set
			// makes its every stack-RAM read return the last word scanned. Without
			// this the probe corrupts the core it is measuring: core 1 reported
			// ptr/space/type all identically 0x400000 and a magic of memStart,
			// which reads as a lost object but is purely the instrument.
			GC.rootRelease();
			JVMHelp.wr("dump: selfSp ");
			wrInt(stackProbeSp);
			JVMHelp.wr(" portSp ");
			wrInt(portSp);
			JVMHelp.wr(" handle ");
			wrInt(stackProbeHandle);
			JVMHelp.wr(" foundAt ");
			wrInt(foundAt);
			JVMHelp.wr(" nonZero ");
			wrInt(nonZero);
			JVMHelp.wr("\r\n");

			// What does THIS core think the object is now? Handle layout:
			// H[0] = data pointer, H[2] = space/mark. If core 0 sees the magic at
			// the promoted location and core 1 does not, the two disagree and it
			// is coherence; if core 0 also sees garbage, the object was genuinely
			// lost and the collector is at fault.
			int hPtr = Native.rdMem(stackProbeHandle);
			JVMHelp.wr("core0 view: ptr ");
			wrInt(hPtr);
			JVMHelp.wr(" space ");
			wrInt(Native.rdMem(stackProbeHandle + GC.OFF_SPACE));
			JVMHelp.wr(" type ");
			wrInt(Native.rdMem(stackProbeHandle + GC.OFF_TYPE));
			JVMHelp.wr(" magic ");
			wrInt(hPtr != 0 ? Native.rdMem(hPtr) : -1);
			JVMHelp.wr("\r\n");

			// Heap boundaries, so the value core 1 reads can be CLASSIFIED rather
			// than guessed at: handle area is [memStart, heapStart), tenure is
			// [heapStart, nurseryBase), nursery is [nurseryBase, tenureTop).
			// Anything outside all three is not a heap address at all.
			JVMHelp.wr("bounds: memStart ");
			wrInt(Native.rdMem(0));
			JVMHelp.wr(" heapStart ");
			wrInt(GC.heapStart);
			JVMHelp.wr(" copyPtr ");
			wrInt(GC.copyPtr);
			JVMHelp.wr(" allocPtr ");
			wrInt(GC.allocPtr);
			JVMHelp.wr(" nurseryBase ");
			wrInt(GC.nurseryBase);
			JVMHelp.wr(" tenureTop ");
			wrInt(GC.tenureTop);
			JVMHelp.wr(" memSize ");
			wrInt(Native.rdMem(Const.IO_MEM_SIZE));
			JVMHelp.wr("\r\n");

			stackProbeGcDone = 1;
			while (stackProbeDone == 0) { }
			JVMHelp.wr("STACKROOT minors ");
			wrInt(pm);
			JVMHelp.wr(" magic ");
			wrInt(stackProbeMagic);
			JVMHelp.wr(stackProbeMagic == STACK_PROBE_MAGIC
					? " OK (other core's stack IS scanned)\r\n"
					: " LOST (other core's stack is NOT scanned)\r\n");

			// THE COMPARISON. Same handle, both cores, raw and cached paths.
			JVMHelp.wr("core1 view: ptr ");
			wrInt(stackProbeC1Ptr);
			JVMHelp.wr(" space ");
			wrInt(stackProbeC1Space);
			JVMHelp.wr(" type ");
			wrInt(stackProbeC1Type);
			JVMHelp.wr(" raw ");
			wrInt(stackProbeC1Raw);
			JVMHelp.wr(" field ");
			wrInt(stackProbeMagic);
			JVMHelp.wr("\r\n");

			// Core 0 re-reads AFTER core 1 has, so the two views are compared at
			// the same point in time. No collection runs in between, so any
			// difference from "core0 view" above is core 0's own doing.
			int hPtr2 = Native.rdMem(stackProbeHandle);
			JVMHelp.wr("core0 after: ptr ");
			wrInt(hPtr2);
			JVMHelp.wr(" space ");
			wrInt(Native.rdMem(stackProbeHandle + GC.OFF_SPACE));
			JVMHelp.wr(" type ");
			wrInt(Native.rdMem(stackProbeHandle + GC.OFF_TYPE));
			JVMHelp.wr(" raw ");
			wrInt(hPtr2 != 0 ? Native.rdMem(hPtr2) : -1);
			JVMHelp.wr(hPtr2 == stackProbeC1Ptr
					? " PTR-AGREE\r\n" : " PTR-DIFFER (cores disagree on the handle)\r\n");
			// Does the cross-core root port work at all? Read core 1's SP, A and
			// B, and a couple of stack words. If SP reads back as junk the scan
			// loop never runs and the fix is inert.
			JVMHelp.wr("scan calls ");
			wrInt(GC.otherRootCalls);
			JVMHelp.wr(" words ");
			wrInt(GC.otherRootWords);
			JVMHelp.wr(" cands ");
			wrInt(GC.otherRootCands);
			JVMHelp.wr(" young ");
			wrInt(GC.otherRootYoung);
			JVMHelp.wr("\r\n");
			JVMHelp.wr("  lastYoung ");
			wrInt(GC.lastYoungHandle);
			JVMHelp.wr(" probeHandle ");
			wrInt(stackProbeHandle);
			JVMHelp.wr(GC.lastYoungHandle == stackProbeHandle ? " MATCH" : " differ");
			JVMHelp.wr(" spMin ");
			wrInt(GC.minScanSp);
			JVMHelp.wr(" spMax ");
			wrInt(GC.maxScanSp);
			JVMHelp.wr("\r\n");
			if (PROBE_RUNNING_CORE) {
			JVMHelp.wr("rootport: sp ");
			int rsp = GC.rootRead(1, Const.ROOT_WHAT_SP, 0);
			wrInt(rsp);
			JVMHelp.wr(" A ");
			wrInt(GC.rootRead(1, Const.ROOT_WHAT_A, 0));
			JVMHelp.wr(" B ");
			wrInt(GC.rootRead(1, Const.ROOT_WHAT_B, 0));
			JVMHelp.wr(" w64 ");
			wrInt(GC.rootRead(1, Const.ROOT_WHAT_STACK, 64));
			JVMHelp.wr(" w70 ");
			wrInt(GC.rootRead(1, Const.ROOT_WHAT_STACK, 70));
			// Same obligation as the dump above — core 1 runs on after this.
			GC.rootRelease();
			JVMHelp.wr("\r\n");
			}
		}

		for (int round = 0; round < 8; round++) {
			// A/B THE CARD CLEAR INSIDE ONE BINARY. Even rounds clear the card
			// table at the end of each minor GC (the shipping behaviour), odd
			// rounds leave it dirty. Rebuilding to switch this instead moves the
			// code and changes where the run dies, which is what made the first
			// attempt at this question unusable. Here nothing varies but the
			// flag.
			GC.cardClearEnabled = (round & 1) == 0;
			publishRound = round;
			phase = 1;                                  // publishers: store now
			// Wait for EVERY publisher independently. One shared counter would
			// let a slow core look like a finished one and core 0 would verify
			// holders nobody had written.
			// Bounded, and it reports WHO did not arrive. An unbounded wait here
			// turns any publisher fault into a silent hang, which is exactly
			// what it did at 4 cores: "minors after tenuring 6" and then
			// nothing, with no way to tell a lost progress write from a crashed
			// core from a GC that never returned.
			boolean all = false;
			int spins = 0;
			while (!all) {
				all = true;
				for (int p = 1; p < cpuCnt; p++) {
					if (pubRound[p] <= round) { all = false; }
				}
				// Allocate while waiting. A minor GC triggered by ANOTHER core
				// has to halt this one, and a tight loop that never touches the
				// heap appears to give it no opportunity to do so: at 4 cores a
				// publisher froze mid-publish (heartbeat stopped dead at 117)
				// while every other core spun millions of iterations. If this
				// allocation clears the hang, the waiting loop was starving the
				// collector of a safepoint.
				if (!all) { Object y = new Young(); if (y == null) return; }
				if (!all && ++spins > 2000000) {
					JVMHelp.wr("STALL round ");
					wrInt(round);
					JVMHelp.wr(" phase ");
					wrInt(phase);
					JVMHelp.wr(" publishRound ");
					wrInt(publishRound);
					for (int p = 1; p < cpuCnt; p++) {
						JVMHelp.wr(" pub[");
						wrInt(p);
						JVMHelp.wr("]=");
						wrInt(pubRound[p]);
					}
					JVMHelp.wr(" live=");
					for (int p = 1; p < cpuCnt; p++) { wrInt(liveTick[p]); JVMHelp.wr(","); }
					// Exceptions CAUGHT inside the publisher loop, and where.
					// Non-zero with the loop still ticking means the access was
					// retried successfully — i.e. a spurious bounds fault.
					JVMHelp.wr(" caught=");
					for (int p = 1; p < cpuCnt; p++) {
						wrInt(pubExcCnt[p]); JVMHelp.wr("@"); wrInt(pubExcStep[p]); JVMHelp.wr(",");
					}
					// WHERE it stopped, and whether its cross-generation store
					// landed. `step` names the statement; `holder` distinguishes
					// "died before the store" from "stored and then died", which
					// point at completely different mechanisms.
					for (int p = 1; p < cpuCnt; p++) {
						JVMHelp.wr(" step[");
						wrInt(p);
						JVMHelp.wr("]=");
						wrInt(pubStep[p]);
						JVMHelp.wr(" slot=");
						wrInt(pubSlot[p]);
						int s = pubSlot[p];
						if (s >= 0 && s < HOLDERS) {
							JVMHelp.wr(" holder=");
							Object o = holders[s].ref;
							if (o == null) {
								JVMHelp.wr("null");
							} else {
								wrInt(((Young) o).magic - MAGIC_BASE);
							}
						}
					}
					JVMHelp.wr("\r\n");
					// BUS COUNTERS per core, straight from the arbiter inputs.
					// This is the whole point of the stall dump: it separates
					// "the core is asking and not being served" (req climbing,
					// gnt flat => starvation in the arbiter or SDRAM controller)
					// from "the core is not asking at all" (req flat => it is
					// wedged elsewhere and the bus is a red herring). `busy` is
					// cycles spent with a request outstanding and ungranted, so a
					// slow path and a stopped one look different.
					// Read through the root port: target >= 8 selects the counter
					// bank of core (target-8). See JopCluster.
					dumpCores();

					spins = 0;
					++stalls;
					if (stalls > 6) { JVMHelp.wr("SMPGC STALLED\r\n"); phase = 3; return; }
				}
			}
			phase = 2;

			// Publishers are done and no GC has run yet this round, so this is
			// the one moment the evidence is intact: is the card MARKED for the
			// holder core 1 just wrote, and does that holder lie inside a range
			// `scanCards()` actually visits? It scans only [tenureBase,copyPtr)
			// and [allocPtr,tenureTop) — the middle is presumed free — so an
			// object in the gap is never reached however dirty its card is.
			if (round == 0) {
				int hd = Native.rdMem(Native.toInt(holders[0]));
				JVMHelp.wr("probe: h0d ");
				wrInt(hd);
				JVMHelp.wr(" card ");
				wrInt(cardBit(hd));
				JVMHelp.wr(" copyPtr ");
				wrInt(GC.copyPtr);
				JVMHelp.wr(" allocPtr ");
				wrInt(GC.allocPtr);
				JVMHelp.wr(" tenureTop ");
				wrInt(GC.tenureTop);
				JVMHelp.wr("\r\n");
			}

			int m = churnUntilMinor(20000);
			minors += m;

			// One line per round: which setting it ran under, how many minor
			// GCs actually happened (a round with none proves nothing), and how
			// many references were lost in it. That is the whole experiment.
			int errBefore = errors;

			for (int i = 0; i < HOLDERS; i++) {
				Object o = holders[i].ref;
				if (o == null) continue;
				// Read the magic through the handle instead of casting. A lost
				// reference points at reused space that is often no longer a
				// Young at all, so `(Young) o` throws ClassCastException and the
				// run dies on the FIRST loss — which hides how many were lost.
				// JOP object layout: handle -> data pointer, fields from there.
				int yMagic = Native.rdMem(Native.rdMem(Native.toInt(o)));
				int want = MAGIC_BASE | (round << 8) | i;
				verified++;
				if (yMagic != want) {
					errors++;
					if (errors <= 4) {
						JVMHelp.wr("  LOST slot ");
						wrInt(i);
						JVMHelp.wr(" round ");
						wrInt(round);
						JVMHelp.wr(GC.cardClearEnabled ? " clear=ON" : " clear=OFF");
						JVMHelp.wr(" magic ");
						wrInt(yMagic);
						JVMHelp.wr(" want ");
						wrInt(want);
						// WHERE the lost holder lives. Only slot 0 is lost, and
						// deterministically, so the answer is an address, not a
						// race. The write barrier marks the card of the holder's
						// DATA word (that is what `ref` is part of), so print
						// that and the card it lands in.
						int hh = Native.toInt(holders[i]);
						int dd = Native.rdMem(hh);
						JVMHelp.wr(" hdlr d=");
						wrInt(dd);
						JVMHelp.wr(" card=");
						wrInt(dd >>> Native.rd(Const.IO_CARD_SHIFT));
						JVMHelp.wr(" yAddr=");
						wrInt(Native.toInt(o));
						// THE SAME FIELD, READ RAW. `o` came from `holders[i].ref`,
						// a getfield — that goes through the object cache. This
						// reads the identical word straight out of memory
						// (handle -> data pointer -> field 0 == `ref`), which is
						// uncached. The two CANNOT legitimately differ:
						//   rawRef == yAddr  -> the reference core 0 holds is the
						//     one in memory, so the publisher's store landed and
						//     the stale value is the OBJECT's magic.
						//   rawRef != yAddr  -> core 0's object cache handed back a
						//     reference the publisher has already overwritten, i.e.
						//     a MISSED SNOOP INVALIDATION, with no GC involved.
						// The second is the only reading that survives `minors 0`.
						JVMHelp.wr(" rawRef=");
						wrInt(Native.rdMem(dd));
						JVMHelp.wr(" rawMagic=");
						wrInt(Native.rdMem(Native.rdMem(Native.rdMem(dd))));
						JVMHelp.wr("\r\n");
					}
				}
			}
			JVMHelp.wr("R");
			wrInt(round);
			JVMHelp.wr(GC.cardClearEnabled ? " clear=ON  " : " clear=OFF ");
			JVMHelp.wr("minors ");
			wrInt(m);
			JVMHelp.wr(" lost ");
			wrInt(errors - errBefore);
			JVMHelp.wr(" haltLeak ");
			wrInt(GC.haltDeltaMax);
			JVMHelp.wr("\r\n");
			// EVERY ROUND, not just at the end and not just on a stall. With the
			// publisher catch in place a bounds fault no longer wedges anything,
			// so the run can sail past it and die somewhere else entirely — two
			// runs have now ended on an unrelated derailment with the fault
			// operands never printed. This is the only place guaranteed to be
			// reached after the fault has happened.
			dumpCores();
		}

		phase = 3;   // tell core 1 to stop

		JVMHelp.wr("minors ");
		wrInt(minors);
		JVMHelp.wr(" verified ");
		wrInt(verified);
		JVMHelp.wr(" errors ");
		wrInt(errors);
		// Always printed, not only from the stall dump: the run that PASSES is
		// the interesting one here. A completed 8-round run with a non-zero
		// count is the whole result — the publisher hit a bounds fault, retried
		// the identical access, and got through. See pubExcCnt.
		JVMHelp.wr(" caught=");
		for (int p = 1; p < cpuCnt; p++) {
			wrInt(pubExcCnt[p]); JVMHelp.wr("@"); wrInt(pubExcStep[p]); JVMHelp.wr(",");
		}
		// And the hardware's own count, which cannot be confused with a Java
		// control-flow artefact: exceptions taken, plus where the FIRST one was.
		for (int c = 1; c < cpuCnt; c++) {
			JVMHelp.wr("\r\n  hw core "); wrInt(c);
			JVMHelp.wr(" exc "); wrInt(GC.rootRead(12 + c, Const.ROOT_WHAT_A, 0));
			int at = GC.rootRead(12 + c, Const.ROOT_WHAT_SP, 0);
			JVMHelp.wr(" excAt pc "); wrInt(at >>> 16);
			JVMHelp.wr(" jpc "); wrInt(at & 0xffff);
			int tb = GC.rootRead(12 + c, Const.ROOT_WHAT_B, 0);
		JVMHelp.wr(" type "); wrInt(tb & 0xff);
		// Outstanding BMB commands minus responses. Must return to 0; a value
		// that grows means the response stream has slipped and every read is
		// getting the previous transaction's data.
		JVMHelp.wr(" bmbOut "); wrInt(tb >>> 8);
			// The operands of that fault, so a PASSING run reports them too —
			// with the catch in place the run completes, and the fault would
			// otherwise leave no trace at all.
			JVMHelp.wr(" abIdx "); wrInt(GC.rootRead(8 + c, Const.ROOT_WHAT_STACK, 0));
			JVMHelp.wr(" abLen "); wrInt(GC.rootRead(8 + c, Const.ROOT_WHAT_SP, 0));
			JVMHelp.wr(" abHdl "); wrInt(GC.rootRead(8 + c, Const.ROOT_WHAT_A, 0));
		}
		GC.rootRelease();
		JVMHelp.wr("\r\n");

		if (minors == 0 || verified == 0) {
			// Guard against the item-2 failure mode: a test that reports success
			// while having exercised nothing.
			JVMHelp.wr("SMPGC INCONCLUSIVE (nothing exercised)\r\n");
		} else if (errors == 0) {
			JVMHelp.wr("SMPGC OK\r\n");
		} else {
			JVMHelp.wr("SMPGC FAIL\r\n");
		}
		JVMHelp.wr("SmpGcTest done\r\n");
	}

	// ---------------------------------------------------------------- core 1

	/**
	 * The cross-generation write. Allocates a young object and stores it into a
	 * tenured holder, keeping the reference out of any surviving stack slot.
	 */
	/**
	 * ROOT-CAUSE PROBE. A static IS scanned by getYoungRoots() (via
	 * addrStaticRefs) no matter which core collects; a core's STACK is not —
	 * `Native.rdIntMem` reads core-private RAM, so a GC on core 0 cannot see
	 * core 1's stack at all. Parking `y` here across the window between
	 * allocating it and storing it makes it reachable to a collector on the
	 * other core. If the losses stop, that invisibility is the bug.
	 */
	static Object keepAlive;

	// ------------------------------------------------ STACK-ROOT TEST (item 1)
	// Direct test of the suspected root cause, independent of the main workload
	// and of code layout: core 1 holds a reference ONLY in a local (i.e. only in
	// its own stack RAM) while core 0 runs minor GCs, then reports whether the
	// object survived. `Native.rdIntMem` reads core-PRIVATE memory, so
	// getYoungRoots()/getStackRoots() can only ever see the collecting core's
	// stack — nothing in the runtime scans another core's. If that is the bug,
	// this object is collected and its magic comes back wrong.
	static volatile int stackProbeReady;   // core 1 -> core 0: reference is held
	static volatile int stackProbeGcDone;  // core 0 -> core 1: collections done
	static volatile int stackProbeMagic;   // core 1 -> core 0: what survived
	static volatile int stackProbeSp;      // core 1's OWN view of its SP
	static volatile int stackProbeHandle;  // the handle core 1 is holding
	static volatile int stackProbeDone;    // separate flag: 0 is a LEGAL magic
	                                       // (a collected object reads back zeroed)
	// Core 1's OWN view of the same handle, so the two cores can be compared
	// word for word. `stackProbeMagic` alone cannot distinguish the three
	// remaining explanations for the wrong value core 1 reads.
	static volatile int stackProbeC1Ptr;   // core 1's read of H[OFF_PTR]
	static volatile int stackProbeC1Space; // core 1's read of H[OFF_SPACE]
	static volatile int stackProbeC1Type;  // core 1's read of H[OFF_TYPE]
	static volatile int stackProbeC1Raw;   // core 1's RAW read of the data word
	static final int STACK_PROBE_MAGIC = 0x5A5A0FFF;

	/**
	 * Read another core's stack RAM while that core is RUNNING.
	 *
	 * OFF, and it must stay off outside a deliberate experiment. `rootSel` drives
	 * the TARGET core's stack-RAM read address, so for as long as it is set that
	 * core's own reads return the scanned word instead of the one it asked for.
	 * Releasing after the loop is not enough — the theft spans the whole block.
	 *
	 * This is not theoretical. With these blocks on, `rootRead(1, STACK, 64)`
	 * parked rootSel at word 64, whose contents are 0x12345678 (the mem_ram.dat
	 * fill pattern). Core 1, concurrently executing `liveTick[id]++`, took that
	 * value as `id`, so `iload_0` yielded 0x12345678, the index arrived at the
	 * bounds check as 0x345678 = 3430008 against a length of 2, and the hardware
	 * correctly raised an array-bounds exception. Every downstream symptom —
	 * the derailment, the null method pointer, the corrupted GC.handle_cnt —
	 * followed from a fault the probe itself manufactured.
	 *
	 * `scanOtherCoreRoots()` is unaffected: the cores it reads are HALTED.
	 */
	static final boolean PROBE_RUNNING_CORE = false;


	/**
	 * Per-core hardware probe, split out of core0() because that method hit
	 * JOPizer's 512-byte method limit ("wrong size: test.SmpGcTest.core0()V").
	 * Keep new probes here rather than inline.
	 */
	static void dumpCores() {
	for (int c = 0; c < cpuCnt; c++) {
		JVMHelp.wr("  core[");
		wrInt(c);
		// THE BOUNDS FAULT'S OPERANDS, latched in hardware at the
		// first EXC_AB on this core. Slots 0..2 used to carry
		// req/gnt/busy; those answered their question (not
		// starvation) and saturate to -1 anyway. Reading:
		//   idx < len   -> impossible, the check compares these
		//                  two, so a wrong pair means one of them
		//                  was not what the pipeline/memory held
		//   len absurd  -> the LENGTH word came back wrong, i.e.
		//                  the BMB response for handle+1
		//   hdl absurd  -> the handle itself was wrong, and the
		//                  length is whatever lives at hdl+1
		JVMHelp.wr("] abIdx ");
		wrInt(GC.rootRead(8 + c, Const.ROOT_WHAT_STACK, 0));
		JVMHelp.wr(" abLen ");
		wrInt(GC.rootRead(8 + c, Const.ROOT_WHAT_SP, 0));
		int abh = GC.rootRead(8 + c, Const.ROOT_WHAT_A, 0);
		JVMHelp.wr(" abHdl ");
		wrInt(abh);
		// THE SAME WORD, READ BACK NOW, from a core that is working. The fault
		// reported length 0 for an array that is cpuCnt long. If this reads the
		// correct length then memory was never wrong and the READ was — a BMB
		// response that did not belong to that request. If it reads 0 too, the
		// handle in `abHdl` is not the array's and the fault is downstream of a
		// bad handle, which is a different bug entirely.
		if (abh != 0) {
			JVMHelp.wr(" nowLen ");
			wrInt(Native.rdMem(abh + GC.OFF_MTAB_ALEN));
			JVMHelp.wr(" nowPtr ");
			wrInt(Native.rdMem(abh + GC.OFF_PTR));
		}
		// Cycles halted by the LOCK MANAGER. The req/gnt counters
		// showed the stalled core stops asking rather than being
		// starved; a core waiting on a monitor issues no memory
		// traffic, so it looks identical from the bus. Large here
		// => blocked in Ihlu/CmpSync. Small => spinning in
		// ordinary code, and neither bus nor lock is to blame.
		JVMHelp.wr(" halt ");
		wrInt(GC.rootRead(8 + c, Const.ROOT_WHAT_B, 0));
		// WHERE the core is, sampled live: microcode pc, bytecode
		// jpc, and how many hardware exceptions it has taken. A
		// wedged core shows a fixed pc/jpc; a non-zero exc on it
		// names the cause outright, since the exception path has
		// already been found derailing cores this session.
		// The live pc/jpc are packed one per half-word (the root
		// port has 4 bits of target and this bank owns 12..15, so
		// there was no room for a slot each).
		int live = GC.rootRead(12 + c, Const.ROOT_WHAT_STACK, 0);
		JVMHelp.wr(" pc ");
		wrInt(live >>> 16);
		JVMHelp.wr(" jpc ");
		wrInt(live & 0xffff);
		JVMHelp.wr(" exc ");
		wrInt(GC.rootRead(12 + c, Const.ROOT_WHAT_A, 0));
		// The FIRST exception this core took, latched in hardware
		// and never overwritten. The live pc is where the core
		// ended up; this is where it went wrong. A core with
		// exc>0 and a wedge is explained by this line.
		int at = GC.rootRead(12 + c, Const.ROOT_WHAT_SP, 0);
		JVMHelp.wr(" excAt pc ");
		wrInt(at >>> 16);
		JVMHelp.wr(" jpc ");
		wrInt(at & 0xffff);
		int tb2 = GC.rootRead(12 + c, Const.ROOT_WHAT_B, 0);
		JVMHelp.wr(" type ");
		wrInt(tb2 & 0xff);
		// Live BMB commands issued minus responses received. Bounded and
		// self-clearing (0 or 1 in a healthy core); a growing value would mean
		// the response stream has slipped a beat and every read after it
		// returns the previous transaction's data.
		JVMHelp.wr(" bmbOut ");
		wrInt(tb2 >>> 8);
		if (c > 0) {
			JVMHelp.wr(" rawLenBad ");
			wrInt(rawLenBad[c]);
			JVMHelp.wr(" aLenBad ");
			wrInt(aLenBad[c]);
		}
		JVMHelp.wr("\r\n");
	}
		GC.rootRelease();
	}

	static void publish(int id, int round, int slot) {
		pubSlot[id] = slot;
		pubStep[id] = 1;
		Young y = new Young();
		keepAlive = y;
		pubStep[id] = 2;
		y.magic = MAGIC_BASE | (round << 8) | slot;
		pubStep[id] = 3;
		y.p0 = slot; y.p1 = round; y.p2 = 0; y.p3 = 0; y.p4 = 0;
		pubStep[id] = 4;
		holders[slot].ref = y;    // TENURED holder <- NURSERY object
		pubStep[id] = 5;
		keepAlive = null;
		y = null;
	}

	/**
	 * Publisher core. Each takes a DISJOINT slice of the holders — two cores
	 * writing the same holder would make a lost object ambiguous (whose store
	 * went missing?) and could mask a fault by overwriting it with a good one.
	 */
	static void publisher(int id) {
		if (id == 1) {
			Young probe = new Young();
			probe.magic = STACK_PROBE_MAGIC;
			// Publish this core's own view so core 0 can compare: if core 0
			// cannot find this exact handle anywhere in the 256-word RAM, the
			// address space or the read path is wrong; if it finds it above the
			// SP core 0 reads, the SP is wrong.
			stackProbeSp = Native.getSP();
			stackProbeHandle = Native.toInt(probe);
			stackProbeReady = 1;
			while (stackProbeGcDone == 0) { }   // reference lives ONLY here
			// Read the SAME handle three ways before dropping the reference:
			//   rdMem(h + OFF_*)  raw handle words
			//   rdMem(H[OFF_PTR]) raw data word, wherever the handle now points
			//   probe.magic       the getfield path, through the object cache
			// raw disagreeing with field => the cache is stale.
			// both agreeing but differing from core 0 => the handle words are stale.
			// all three agreeing with core 0 => the object really was lost, and the
			// fault is in the collector, not in coherence.
			int h = stackProbeHandle;
			stackProbeC1Ptr   = Native.rdMem(h + GC.OFF_PTR);
			stackProbeC1Space = Native.rdMem(h + GC.OFF_SPACE);
			stackProbeC1Type  = Native.rdMem(h + GC.OFF_TYPE);
			stackProbeC1Raw   = stackProbeC1Ptr != 0 ? Native.rdMem(stackProbeC1Ptr) : -1;
			stackProbeMagic = probe.magic;
			stackProbeDone = 1;
			probe = null;
		}
		int round = 0;
		while (true) {
			try {
				// Same word, two paths — see liveTickHandle.
				if (Native.rdMem(liveTickHandle + GC.OFF_MTAB_ALEN) != cpuCnt) {
					rawLenBad[id] = rawLenBad[id] + 1;
				}
				if (liveTick.length != cpuCnt) {
					aLenBad[id] = aLenBad[id] + 1;
				}
				liveTick[id] = liveTick[id] + 1;
				pubStep[id] = 10;
				int ph = phase;
				if (ph == 3) return;
				if (ph == 1 && round == publishRound) {
					pubStep[id] = 11;
					for (int i = id - 1; i < HOLDERS; i += publishers) {
						publish(id, round, i);
						pubStep[id] = 6;
						if (scrub() == 0x7fffffff) JVMHelp.wr("");  // keep scrub() live
						pubStep[id] = 7;
					}
					pubStep[id] = 12;
					pubRound[id] = round + 1;
					round++;
				}
			} catch (Throwable t) {
				// See pubExcCnt. Deliberately swallows and retries: the point is
				// to find out whether the SAME access succeeds a moment later.
				pubExcCnt[id] = pubExcCnt[id] + 1;
				pubExcStep[id] = pubStep[id];
			}
		}
	}

	// ---------------------------------------------------------------- entry

	public static void main(String[] args) {
		int cpuId = Native.rdMem(Const.IO_CPU_ID);
		cpuCnt = Native.rdMem(Const.IO_CPUCNT);
		publishers = cpuCnt - 1;
		if (cpuId == 0) {
			phase = 0;
			publishRound = 0;
			core0();
		} else {
			// EVERY other core publishes. More publishers means more concurrent
			// cross-core stores between minor GCs, so the window the shared card
			// table has to cover is wider — and it puts >1 writer on the
			// cluster's config-write priority mux, which two cores never did.
			publisher(cpuId);
		}
	}
}
