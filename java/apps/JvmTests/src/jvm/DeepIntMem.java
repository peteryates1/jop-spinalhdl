/*
  READ AND WRITE A STACK WORD THE CACHE IS NO LONGER HOLDING — status item 133.

  THE PRIMITIVE UNDER EVERY STACK WALKER. `Native.rdIntMem`/`wrIntMem` are
  `star / nop / ldmi|stmi` (asm/src/jvm.asm:2190-2198), so the address goes
  through AR, and AR reaches no part of the rotation controller. A word outside
  the resident window therefore reads as ZERO (the read MUX default,
  StackStage.scala) and a write to it is DROPPED, with no fault.

  `jvm.DeepThrow` shows the consequence for `f_athrow`, and `jvm.DeepGc` for the
  collector's root scan. This test isolates the primitive itself — no exception,
  no collector — so a failure here can only be the access path.

  WHAT IT DOES. `across()` keeps a marker in a local, finds that local's stack
  address (`getVP() + 1`: `depth` is local 0, `marker` local 1), and recurses.
  At the bottom it reads that address with `rdIntMem` and overwrites it with
  `wrIntMem`. Back in `across()`, the read must have returned the marker and the
  local must now hold the new value.

  WHY THE WRITE IS CHECKED THROUGH THE LOCAL. Re-reading with `rdIntMem` at the
  bottom would pass if the write and the read took the same wrong path. Reading
  `marker` back as a Java local goes through VP after the window has rotated
  down again, i.e. through a FILL from the spill region — so the write is only
  seen if it really reached the backing store.

  CONTROLS. Depth 5 keeps everything resident, so it must pass on any build: if
  it fails, the probe address is wrong and nothing else here means anything. The
  probe address is also checked directly, before recursing.
*/
package jvm;

import com.jopdesign.sys.Native;

public class DeepIntMem extends TestCase {

	public String toString() {
		return "DeepIntMem";
	}

	static final int MARK = 0x13579BDF;
	static final int MARK2 = 0x2468ACE0;

	/** Stack address of `marker` in the anchor frame. */
	static int probeAddr;
	/** What `rdIntMem(probeAddr)` returned at the bottom. */
	static int probeRead;
	/** SP at the deepest frame: the residency question is about this. */
	static int bottomSp;

	/** Same frame shape as DeepThrow.descend and DeepRecursion.deepSum (9.0 slots/frame measured). */
	static int descend(int n) {
		int local1 = n;
		int local2 = n * 2;
		if (n <= 0) {
			bottomSp = Native.getSP();
			probeRead = Native.rdIntMem(probeAddr);
			Native.wrIntMem(MARK2, probeAddr);
			return 0;
		}
		int r = descend(n - 1);
		if (local1 != n) return -1;
		if (local2 != n * 2) return -1;
		return r + n;
	}

	/**
	 * Returns 0 pass, 1 fail, 2 inconclusive (probe address wrong). Prints
	 * `d<depth>s<sp>` and then the verdict, read and write separately.
	 */
	static int across(int depth) {
		int marker = MARK;
		probeAddr = Native.getVP() + 1;
		System.out.print(" d");
		System.out.print(depth);
		// THE PROBE MUST POINT AT `marker`, checked while it is certainly
		// resident. A wrong slot would make every arm fail for a reason that has
		// nothing to do with the cache.
		if (Native.rdIntMem(probeAddr) != MARK) {
			System.out.print("?addr");
			return 2;
		}
		probeRead = 0;
		int r = descend(depth);
		System.out.print("s");
		System.out.print(bottomSp);
		boolean readOk = probeRead == MARK;
		boolean wroteOk = marker == MARK2;
		boolean depthOk = r == depth * (depth + 1) / 2;
		System.out.print(readOk ? "r" : "R");
		System.out.print(wroteOk ? "w" : "W");
		if (!depthOk) System.out.print("D");
		boolean ok = readOk && wroteOk && depthOk;
		System.out.print(ok ? "+" : "-");
		return ok ? 0 : 1;
	}

	/**
	 * Lower-case `r`/`w` = that half passed, upper-case = it failed, `D` = the
	 * recursion's own locals did not survive (a different defect).
	 *
	 * The ladder spans the threshold. DeepThrow measured the frame base and
	 * cost: SP 466 at depth 40, 601 at 55, i.e. ~106 + 9n. The anchor sits
	 * just below that base, and it leaves the window once the bottom bank of
	 * three is rebased past it, which needs SP beyond ~640: depth ~60.
	 */
	public boolean test() {
		int control = across(5);
		if (control == 2) {
			System.out.print(" INCONCLUSIVE");
			return false;
		}
		boolean ok = control == 0;
		ok = across(60) == 0 && ok;
		ok = across(70) == 0 && ok;
		ok = across(100) == 0 && ok;
		return ok;
	}
}
