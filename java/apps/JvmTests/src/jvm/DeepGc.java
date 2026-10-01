/*
  A COLLECTION FROM DEEP IN THE STACK MUST STILL SEE THE SHALLOW FRAMES' ROOTS
  — status item 133.

  THE COLLECTOR SCANS ITS OWN STACK THROUGH AR:

      GC.java  getStackRoots()   for (j = STACK_OFF; j <= sp; ++j)
                                     pushFast(Native.rdIntMem(j), ...);
      GC.java  getYoungRoots()   ... pushYoung(Native.rdIntMem(j));

  and `rdIntMem` is `star / nop / ldmi`. With a stack cache, a word below the
  resident window reads as ZERO (see jvm.DeepIntMem), so every reference held in
  a frame the cache has evicted is invisible to the scan. The object is then
  unreachable as far as the collector knows, and it is reclaimed while still in
  use. Nothing faults: this is silent heap corruption on every stack-cache build
  (single-core DDR3, and SDR on boards that ask for the cache), triggered by any
  collection that starts more than ~60 frames deep.

  WHAT THIS DOES. `holdAcross()` allocates a Box and keeps it ONLY in a local,
  recurses, runs a full collection at the bottom, and checks the Box on the way
  back. A reclaimed handle has OFF_PTR zeroed (GC.java sweep) or has been reused,
  so `magic` no longer reads back either way.

  CONTROLS, both required for a deep failure to mean anything:
    - shallow + GC: the root is resident, so the collector must find it. If this
      fails the test is wrong, not the cache.
    - deep, no GC: the depth alone must not disturb the Box.

  FIXED 2026-10-01 (StackStage's AR controller serves the evicted words). Red
  before: `g5+ n100+ g70- g100-`. This test is now the guard.

  IN THE FAILING STATE THIS CAN TAKE THE REST OF THE RUN WITH IT. The same scan
  also misses DeepAll.main's own `tc` array and the test objects, so the line
  after this one may be garbage. The verdict is printed here, before returning,
  for that reason — and this test runs BEFORE DeepThrow, which itself ends the
  run when it fails.
*/
package jvm;

import com.jopdesign.sys.GC;
import com.jopdesign.sys.Native;

public class DeepGc extends TestCase {

	public String toString() {
		return "DeepGc";
	}

	static class Box {
		int magic;
		Box(int m) {
			magic = m;
		}
	}

	/** Distinct per arm, and unlikely to be found at address 0 of the image. */
	static final int MAGIC = 0x5EED0000;

	static boolean collectAtBottom;
	static int bottomSp;

	/** Same frame shape as DeepThrow.descend and DeepRecursion.deepSum. */
	static int descend(int n) {
		int local1 = n;
		int local2 = n * 2;
		if (n <= 0) {
			bottomSp = Native.getSP();
			if (collectAtBottom) {
				GC.gc();
			}
			return 0;
		}
		int r = descend(n - 1);
		if (local1 != n) return -1;
		if (local2 != n * 2) return -1;
		return r + n;
	}

	private boolean holdAcross(int depth, boolean collect) {
		Box held = new Box(MAGIC + depth);
		collectAtBottom = collect;
		System.out.print(collect ? " g" : " n");
		System.out.print(depth);
		int r = descend(depth);
		collectAtBottom = false;
		System.out.print("s");
		System.out.print(bottomSp);
		boolean ok = r == depth * (depth + 1) / 2 && held.magic == MAGIC + depth;
		System.out.print(ok ? "+" : "-");
		return ok;
	}

	/**
	 * `g<depth>` collects at the bottom, `n<depth>` does not. The deep rungs
	 * follow DeepIntMem's: the anchor frame leaves the window near depth 60.
	 */
	public boolean test() {
		boolean ok = holdAcross(5, true);        // control: resident root
		ok = holdAcross(100, false) && ok;       // control: depth alone
		ok = holdAcross(70, true) && ok;
		ok = holdAcross(100, true) && ok;
		return ok;
	}
}
