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
		if (!generational()) return 0;
		int before = GC.nurseryAllocPtr;
		int seen = 0;
		for (int i = 0; i < budget; i++) {
			Object junk = new Young();
			if (junk == null) return seen;           // never; defeats dead-code removal
			int now = GC.nurseryAllocPtr;
			// The bump pointer grows DOWN; a minor GC re-carves the nursery, so
			// the pointer jumping back UP is the observable event.
			if (now > before) { seen++; before = now; }
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

		Native.wr(1, Const.IO_SIGNAL);   // release core 1

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
		}

		phase = 3;   // tell core 1 to stop

		JVMHelp.wr("minors ");
		wrInt(minors);
		JVMHelp.wr(" verified ");
		wrInt(verified);
		JVMHelp.wr(" errors ");
		wrInt(errors);
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
	static void publish(int id, int round, int slot) {
		pubSlot[id] = slot;
		pubStep[id] = 1;
		Young y = new Young();
		pubStep[id] = 2;
		y.magic = MAGIC_BASE | (round << 8) | slot;
		pubStep[id] = 3;
		y.p0 = slot; y.p1 = round; y.p2 = 0; y.p3 = 0; y.p4 = 0;
		pubStep[id] = 4;
		holders[slot].ref = y;    // TENURED holder <- NURSERY object
		pubStep[id] = 5;
		y = null;
	}

	/**
	 * Publisher core. Each takes a DISJOINT slice of the holders — two cores
	 * writing the same holder would make a lost object ambiguous (whose store
	 * went missing?) and could mask a fault by overwriting it with a good one.
	 */
	static void publisher(int id) {
		int round = 0;
		while (true) {
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
