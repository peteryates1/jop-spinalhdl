package jvm.math;

import jvm.TestCase;

/**
 * Test double arithmetic (dadd, dsub, dmul, ddiv).
 * These are dispatched via SoftFloat64 on JOP.
 * Uses variables to avoid javac constant folding.
 */
public class DoubleArithmetic extends TestCase {

	public String toString() {
		return "DoubleArith";
	}

	public boolean test() {

		boolean ok = true;

		double a = 2.0;
		double b = 3.0;

		// dadd
		double sum = a + b;
		ok = ok && (sum == 5.0);

		// dsub
		double diff = a - b;
		ok = ok && (diff == -1.0);

		// dmul
		double prod = a * b;
		ok = ok && (prod == 6.0);

		// ddiv
		double quot = b / a;
		ok = ok && (quot == 1.5);

		// dneg
		double neg = -a;
		ok = ok && (neg == -2.0);

		// Additional: larger values
		double c = 1000.0;
		double d = 0.001;
		double cd = c * d;
		ok = ok && (cd == 1.0);

		return ok;
	}
}
