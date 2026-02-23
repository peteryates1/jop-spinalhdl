package jvm.math;

import jvm.TestCase;

public class DivZero extends TestCase {

	public String toString() {
		return "DivZero";
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && testIdivZero();
		ok = ok && testIremZero();
		ok = ok && testNormalAfterException();
		return ok;
	}

	private boolean testIdivZero() {
		boolean caught = false;
		try {
			int a = 42;
			int b = 0;
			int c = a / b;
		} catch (ArithmeticException e) {
			caught = true;
		}
		return caught;
	}

	private boolean testIremZero() {
		boolean caught = false;
		try {
			int a = 42;
			int b = 0;
			int c = a % b;
		} catch (ArithmeticException e) {
			caught = true;
		}
		return caught;
	}

	private boolean testNormalAfterException() {
		boolean caught = false;
		try {
			int a = 1;
			int b = 0;
			int c = a / b;
		} catch (ArithmeticException e) {
			caught = true;
		}
		// verify normal division still works after exception recovery
		int x = 100 / 5;
		return caught && (x == 20);
	}
}
