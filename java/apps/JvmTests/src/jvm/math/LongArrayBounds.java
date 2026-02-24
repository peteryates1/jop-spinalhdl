package jvm.math;

import jvm.TestCase;

/**
 * Test ArrayIndexOutOfBoundsException on laload/lastore.
 * Exercises microcode bounds checking in jvm_long.inc.
 */
public class LongArrayBounds extends TestCase {

	public String toString() {
		return "LongArrayBounds";
	}

	public boolean test() {

		boolean ok = true;
		long[] la = new long[3];
		la[0] = 100L;
		la[1] = 200L;
		la[2] = 300L;
		boolean caught;

		// T1: laload negative index
		caught = false;
		try {
			long x = la[-1];
		} catch (ArrayIndexOutOfBoundsException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T1");
		ok &= caught;

		// T2: laload upper bounds
		caught = false;
		try {
			long x = la[3];
		} catch (ArrayIndexOutOfBoundsException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T2");
		ok &= caught;

		// T3: lastore negative index
		caught = false;
		try {
			la[-1] = 99L;
		} catch (ArrayIndexOutOfBoundsException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T3");
		ok &= caught;

		// T4: lastore upper bounds
		caught = false;
		try {
			la[3] = 99L;
		} catch (ArrayIndexOutOfBoundsException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T4");
		ok &= caught;

		// Verify array intact after bounds checks
		ok &= (la[0] == 100L);
		ok &= (la[1] == 200L);
		ok &= (la[2] == 300L);

		return ok;
	}
}
