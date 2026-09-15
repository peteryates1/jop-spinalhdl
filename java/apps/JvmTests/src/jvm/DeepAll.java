/*
  Deep-stack entry point for STACK-CACHE configurations only.

  WHY THIS EXISTS. DeepRecursion is excluded from DoAll, and DoAll.java said
  "Run via JopStackCacheSim which includes it explicitly" -- but that sim ran
  DoAll, so each side pointed at the other and the test ran NOWHERE. It had not
  executed in this tree until 2026-09-15 (status item 133).

  It cannot simply be added to DoAll: without the stack cache the classic stack
  is 256 words with 64 of scratch, leaving 192 usable, and deepSum(50) alone
  needs roughly 250. On a non-cache config it does not fail, it HANGS -- 60M
  cycles with no output and no exception, because spOv is not wired to EXC_SPOV.
  Adding it to DoAll would wedge every non-cache board.

  So it gets its own main, and JopStackCacheSim runs THIS.
*/
package jvm;

public class DeepAll {

	public static void main(String[] args) {
		TestCase tc[] = {
				new DeepRecursion(),
		};

		for (int i = 0; i < tc.length; ++i) {
			System.out.print(tc[i].toString());
			if (tc[i].test()) {
				System.out.println(" ok");
			} else {
				System.out.println(" failed!");
			}
		}
		System.out.println("DeepAll done");
	}
}
