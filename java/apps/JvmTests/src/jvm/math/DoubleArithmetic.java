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

		// drem: double remainder via SoftFloat64
		double r = a / b; // 2.0 / 3.0 -- just for variable setup
		double dr = 7.0;
		double dd = 3.0;
		double drem = dr % dd;
		// 7.0 % 3.0 = 1.0
		ok = ok && (drem == 1.0);

		dr = -7.0;
		dd = 3.0;
		drem = dr % dd;
		// -7.0 % 3.0 = -1.0
		ok = ok && (drem == -1.0);

		// dcmpl / dcmpg: double comparison
		ok = ok && test_dcmp();

		return ok;
	}

	/**
	 * Test dcmpl and dcmpg bytecodes.
	 * dcmpl: returns -1 for NaN. Used by javac for > and >= comparisons.
	 * dcmpg: returns 1 for NaN. Used by javac for < and <= comparisons.
	 */
	boolean test_dcmp() {
		boolean ok = true;
		double a = 1.0;
		double b = 2.0;
		double c = 1.0;

		// dcmpg path (a < b uses dcmpg)
		ok = ok && (a < b);
		ok = ok && !(a > b);
		ok = ok && (a == c);
		ok = ok && !(a != c);
		// dcmpl path (b > a uses dcmpl)
		ok = ok && (b > a);
		ok = ok && !(b < a);

		double neg = -1.0;
		ok = ok && (neg < a);
		ok = ok && (a > neg);

		// Edge: zero comparisons
		double z = 0.0;
		ok = ok && (z < a);
		ok = ok && !(z > a);
		ok = ok && (z == z);

		return ok;
	}
}
