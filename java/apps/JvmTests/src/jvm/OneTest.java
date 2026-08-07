package jvm;

/**
 * Run a single TestCase, for isolating a DoAll failure on one board.
 *
 * `DoAll` dies at `CollectionTest` on the Wukong (current-status item 28) while
 * passing 66/66 on three other boards, and a crash inside a 66-test run tells
 * you very little: the preceding 59 tests have allocated, collected and left
 * the heap in a state nothing else reproduces. This runs one test from a cold
 * start so a failure can be attributed to that test rather than to whatever
 * came before it.
 *
 * Edit the single line below and rebuild:
 *   make -C java/apps/JvmTests APP_NAME=OneTest
 */
public class OneTest {

	public static void main(String[] args) {

		TestCase tc = new CollectionTest();

		System.out.print("OneTest: ");
		System.out.print(tc.toString());
		if (tc.test()) {
			System.out.println(" ok");
		} else {
			System.out.println(" failed!");
		}
		System.out.println("OneTest done");
	}
}
