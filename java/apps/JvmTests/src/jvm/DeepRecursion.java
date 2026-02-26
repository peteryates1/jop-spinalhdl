/*
  Deep recursion test for 3-bank rotating stack cache.
  Recurses 200+ levels, verifying correct return values at each level.
  With the original 256-entry stack (192 usable), this would overflow.
  With the 3-bank stack cache, each bank holds 192 entries, and the
  DMA spill/fill mechanism transparently rotates banks.
*/
package jvm;

public class DeepRecursion extends TestCase {

	public String toString() {
		return "DeepRecursion";
	}

	/**
	 * Recursive fibonacci-like function that exercises deep call chains.
	 * Each call uses ~5 stack slots (frame pointer, VP, locals, operands).
	 * 200 levels × 5 slots = ~1000 stack entries, crossing 5+ bank boundaries.
	 */
	static int deepSum(int n) {
		if (n <= 0) return 0;
		int local1 = n;
		int local2 = n * 2;
		int result = deepSum(n - 1);
		// Verify locals survived the recursion
		if (local1 != n) return -1;
		if (local2 != n * 2) return -1;
		return result + n;
	}

	/**
	 * Test with moderate recursion depth (50 levels).
	 * Crosses at least 1 bank boundary with 5 slots/frame.
	 */
	static boolean testModerate() {
		int n = 50;
		int expected = n * (n + 1) / 2;  // sum(1..50) = 1275
		int result = deepSum(n);
		return result == expected;
	}

	/**
	 * Test with deep recursion (200 levels).
	 * Crosses multiple bank boundaries, requires DMA spill/fill.
	 */
	static boolean testDeep() {
		int n = 200;
		int expected = n * (n + 1) / 2;  // sum(1..200) = 20100
		int result = deepSum(n);
		return result == expected;
	}

	/**
	 * Test that locals and return values survive bank rotation.
	 * Uses multiple locals per frame to increase stack pressure.
	 */
	static int multiLocal(int n) {
		if (n <= 0) return 0;
		int a = n;
		int b = n + 1;
		int c = n + 2;
		int d = n + 3;
		int result = multiLocal(n - 1);
		// All locals must survive
		if (a != n || b != n + 1 || c != n + 2 || d != n + 3) return -1;
		return result + a + b + c + d;
	}

	static boolean testMultiLocal() {
		// 100 levels × ~8 slots/frame = ~800 entries
		int n = 100;
		// Each level adds n + (n+1) + (n+2) + (n+3) = 4n + 6
		// Sum for i=1..100: 4*sum(1..100) + 6*100 = 4*5050 + 600 = 20800
		int expected = 0;
		for (int i = 1; i <= n; i++) {
			expected = expected + (4 * i + 6);
		}
		int result = multiLocal(n);
		return result == expected;
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && testModerate();
		ok = ok && testDeep();
		ok = ok && testMultiLocal();
		return ok;
	}
}
