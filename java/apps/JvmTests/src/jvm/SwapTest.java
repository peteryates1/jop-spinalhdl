package jvm;

/**
 * Test the SWAP bytecode (0x5F).
 *
 * javac never emits SWAP, so InjectSwap (BCEL) replaces the method
 * bodies at link time. The placeholder return values here are
 * deliberately wrong after injection — the test verifies that SWAP
 * reverses the top two stack elements.
 *
 * doSwap(a, b): injected body = iload_0, iload_1, SWAP, pop, ireturn -> returns b
 *   Without SWAP: [a, b] -> pop -> [a] -> returns a
 *   With SWAP:    [b, a] -> pop -> [b] -> returns b
 */
public class SwapTest extends TestCase {

	public String toString() {
		return "SwapTest";
	}

	/**
	 * Placeholder: returns a (wrong). After injection: returns b (correct).
	 */
	private static int doSwap(int a, int b) {
		return a;
	}

	public boolean test() {
		boolean ok = true;
		if (doSwap(42, 99) != 99) { ok = false; System.out.print(" T1"); }
		return ok;
	}
}
