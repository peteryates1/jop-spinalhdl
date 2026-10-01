/*
  THROW FROM A STACK DEEPER THAN THE RESIDENT WINDOW — status item 133.

  WHAT THIS IS FOR. `f_athrow` unwinds frame by frame, reading each frame's
  saved pc/vp/cp/mp out of the stack:

      JVM.java:742  int fp = Native.getSP()-4;
      JVM.java:743  while (fp > Const.STACK_OFF+5) {
      JVM.java:745      int pc = Native.rdIntMem(fp+1)-1;
      JVM.java:746      int vp = Native.rdIntMem(fp+2);
      JVM.java:747      int cp = Native.rdIntMem(fp+3);
      JVM.java:748      int mp = Native.rdIntMem(fp+4);

  `Native.rdIntMem` addresses through AR: `jopsys_rdint` is `star / nop / ldmi`
  (asm/src/jvm.asm:2190-2193), and `star` (0x01a) latches AR. The stack cache
  rotates only on `smuxSignal`, and `selSmux = 3` — the case that routes an
  address rather than SP — is selected by exactly ONE instruction, `stsp` (0x01b,
  DecodeStage.scala:397). `star` is not it, and AR appears nowhere in the
  rotation controller (StackStage.scala:700-1000).

  So an AR-addressed read outside the resident window does not rotate. It
  returns ZERO — `ramDout := 0` is the mux default (StackStage.scala:511) — and
  the matching write is dropped (:622-631). `spOv` is keyed on `sp` alone
  (:1215-1222), so nothing faults. The unwind reads zeros for pc/vp/cp/mp and
  keeps going.

  WHY THE EXISTING TEST CANNOT SEE IT. `AthrowTest` is FLAT: its throws happen a
  couple of frames deep, so SP never approaches 639 and no bank has ever been
  rebased. The whole stack is resident and the unwind is correct. The resident
  window is 576 words (3 banks x 192 above 64 of scratch, bases 64/256/448), so
  the fault begins only once SP has exceeded 639 and a rotation has moved the
  window up.

  WHAT THIS DOES. Recurses with wide-ish frames until SP is well past 639 — the
  same shape as DeepRecursion, which measures maxSp 1905 at 200 levels — and
  THEN throws, so the unwind has to cross frames that are no longer resident.

  HOW IT CAN FAIL. Several ways, and all of them are informative:
    - the exception is not caught by the right handler (pc/mp read as 0, so the
      handler search runs against the wrong method)
    - it is reported uncaught
    - the machine wedges, which in DeepAll looks like "DeepThrow" and then
      silence — item 133 records that a non-cache board does exactly that when
      the stack overflows, because spOv was not wired
  A PASS means the unwind survived a non-resident stack, which would refute the
  RTL reading above and is worth knowing just as much.

  It lives in DeepAll rather than DoAll for the reason DeepAll exists: without a
  stack cache this depth overflows a 192-word usable stack and hangs.
*/
package jvm;

import com.jopdesign.sys.Native;

public class DeepThrow extends TestCase {

	public String toString() {
		return "DeepThrow";
	}

	/** Distinct from any exception the runtime raises on its own, so a catch
	 *  here cannot be satisfied by an unrelated fault. */
	static class DeepMarker extends RuntimeException {
	}

	/** Set at the bottom, so a failure can be told apart from never arriving. */
	static int reachedBottom;
	/** Set in the handler, to distinguish "wrong handler" from "not caught". */
	static int caught;
	/** SP measured at the deepest frame — the residency question is about this. */
	static int bottomSp;

	/**
	 * Same frame shape as DeepRecursion.deepSum — a few locals plus operands, so
	 * roughly 5 stack slots per level and 200 levels lands SP near 1000, several
	 * bank boundaries past the 576-word window.
	 *
	 * The locals are checked on the way back up in the non-throwing arm so this
	 * method is also a plain deep-recursion test when called that way.
	 */
	/** Selects the throw WITHOUT widening the frame — see DEPTH. */
	static boolean throwAtBottom;

	static int descend(int n) {
		int local1 = n;
		int local2 = n * 2;
		if (n <= 0) {
			reachedBottom = 1;
			// MEASURE SP, do not estimate it. The first sweep assumed ~5 slots
			// per frame and put its lowest "deep" point at SP ~564, supposedly
			// inside the 639 window — but DeepRecursion reaches maxSp 1905 at 200
			// levels, i.e. ~9.2 slots/frame, so that point was actually at ~984
			// and already past the boundary. Reported from the deepest frame,
			// BEFORE the throw, so a mis-resume cannot swallow it.
			bottomSp = Native.getSP();
			if (throwAtBottom) {
				throw new DeepMarker();
			}
			return 0;
		}
		int r = descend(n - 1);
		if (local1 != n) return -1;
		if (local2 != n * 2) return -1;
		return r + n;
	}

	/**
	 * CONTROL: the same depth with no throw. If this fails, the depth itself is
	 * the problem and the throw result would say nothing — the same reason every
	 * sweep in item 133 carries a no-stimulus arm.
	 */
	/**
	 * DEPTH IS THE MINIMUM THAT CROSSES THE WINDOW, not the maximum writable.
	 *
	 * The first version used 200 levels with a wider frame (two params, three
	 * locals) and WEDGED IN THIS CONTROL ARM — before reaching the throw at all.
	 * Two separate problems with that:
	 *   - at ~9 slots/frame it exceeded `DeepRecursion`'s proven maxSp 1905, and
	 *     one run reached 21410, past this sim's private 64 KB (16,384-word)
	 *     spill RAM — so it corrupted memory off the end rather than testing
	 *     anything about the unwind.
	 *   - adding two print markers changed maxSp from 21410 to 1905, i.e. the
	 *     symptom moved when the app was instrumented. Item 133 records exactly
	 *     that signature for method-cache placement.
	 * The residency threshold is 639. 120 levels at deepSum's ~5 slots/frame
	 * lands near 1150 — comfortably past 639, comfortably inside 1905.
	 */
	static final int DEPTH = 120;

	private boolean testDeepNoThrow() {
		reachedBottom = 0;
		throwAtBottom = false;
		int expected = DEPTH * (DEPTH + 1) / 2;
		int r = descend(DEPTH);
		return reachedBottom == 1 && r == expected;
	}

	/** Superseded by the sweep in test(); kept because it names the intent. */
	private boolean testDeepThrow() {
		reachedBottom = 0;
		caught = 0;
		throwAtBottom = true;
		try {
			descend(DEPTH);
			return false;           // must not return normally
		} catch (DeepMarker d) {
			caught = 1;
		}
		throwAtBottom = false;
		return reachedBottom == 1 && caught == 1;
	}

	/**
	 * And once more at a depth that stays INSIDE the window, as a second control:
	 * if the shallow throw passes and the deep one does not, the difference is
	 * residency and nothing else. 20 levels x ~5 slots keeps SP under 200.
	 */
	private boolean testShallowThrow() {
		reachedBottom = 0;
		caught = 0;
		throwAtBottom = true;
		try {
			descend(20);
			return false;
		} catch (DeepMarker d) {
			caught = 1;
		}
		throwAtBottom = false;
		return reachedBottom == 1 && caught == 1;
	}

	/**
	 * MARKERS, because the first version of this test WEDGED and could not say
	 * which arm it died in — the same defect this project's own notes record
	 * about an unbounded spin: a hang must yield a number, not silence. Each arm
	 * announces itself before running, so the last marker printed localises the
	 * failure to one arm without a second run.
	 */
	/**
	 * Throw at a given depth, reporting before and after so a WEDGE localises
	 * itself. Returns false if the exception was not caught correctly.
	 */
	private boolean throwAt(int n) {
		reachedBottom = 0;
		caught = 0;
		throwAtBottom = true;
		bottomSp = -1;
		System.out.print(" d");
		System.out.print(n);
		try {
			// Reach the bottom WITHOUT throwing first, purely to report the SP
			// that depth produces — so the threshold is measured rather than
			// inferred, and a wedge in the throwing pass still has its SP on the
			// wire from this pass.
			throwAtBottom = false;
			descend(n);
			System.out.print("s");
			System.out.print(bottomSp);
			throwAtBottom = true;
			descend(n);
			throwAtBottom = false;
			return false;               // must not return normally
		} catch (DeepMarker d) {
			caught = 1;
		}
		throwAtBottom = false;
		boolean ok = reachedBottom == 1 && caught == 1;
		System.out.print(ok ? "+" : "-");
		return ok;
	}

	/**
	 * THE THRESHOLD SWEEP. SP is roughly 64 + 5n for this frame shape, so the
	 * 639 residency boundary sits near n = 115. Throwing at a ladder of depths
	 * either side of it turns "deep throws wedge" into "throws wedge exactly
	 * when the stack crosses the resident window", which is the actual claim.
	 *
	 * Each attempt prints `t<depth>` BEFORE it runs and `+`/`-` after, so a hang
	 * leaves the failing depth as the last thing on the wire — the one run gives
	 * the threshold instead of a bisect.
	 */
	public boolean test() {
		boolean ok = true;
		System.out.print(" [shallow");
		ok = ok && testShallowThrow();
		System.out.print(" ok]");

		System.out.print(" [deepNoThrow");
		ok = ok && testDeepNoThrow();
		System.out.print(" ok]");

		System.out.print(" [sweep");
		// Bracket the MEASURED boundary. At ~9.2 slots/frame SP crosses 639 near
		// depth 62, so these run from comfortably inside to comfortably outside.
		// THE MEASURED LADDER. Frame cost is 9.0 slots, from two passing points
		// (d40 -> SP 466, d55 -> SP 601). Results as of 2026-10-01:
		//
		//   d40  SP 466  pass
		//   d55  SP 601  pass
		//   d58  SP 628  FAIL — "Uncaught exception"
		//   d60  SP 646  FAIL — garbage output
		//   d100 SP ~984 FAIL — mis-resume, skips the rest of its own frame
		//
		// The crossing is BELOW the window's last word (639) because `f_athrow`
		// is itself a Java method and calls more Java during the unwind, so the
		// unwind runs with SP above the throw site and rebases the window out
		// from under the frames it is walking. Do not quote 639 as the trigger.
		//
		// The ladder is kept whole rather than trimmed to the first failure: when
		// this is fixed, every rung must pass, and the three distinct symptoms
		// are each worth re-checking.
		//
		// FIXED 2026-10-01: every rung passes. The reads needed the AR controller
		// (served from the spill region); d60 and d100 ALSO needed the VP
		// rotation restricted to VP <= SP, because f_athrow's tail runs with VP
		// far above SP and the rotation zero-filled its own frame.
		ok = throwAt(40) && ok;
		ok = throwAt(55) && ok;
		ok = throwAt(58) && ok;
		ok = throwAt(60) && ok;
		// AND FAR PAST IT. After `setSP(fp+4)` f_athrow still runs in its own
		// frame, so VP sits far ABOVE SP for its last few bytecodes. At d60
		// they are under one window apart; here (SP ~984) they are more than a
		// window apart, so no placement of the window covers both -- the
		// locals can only be served from the spill region.
		ok = throwAt(100) && ok;
		System.out.print(" done]");
		return ok;
	}
}
