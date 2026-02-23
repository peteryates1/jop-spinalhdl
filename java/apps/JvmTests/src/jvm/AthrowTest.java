package jvm;

public class AthrowTest extends TestCase {

	public String toString() {
		return "AthrowTest";
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && testSimpleAthrow();
		ok = ok && testNestedAthrow();
		return ok;
	}

	private boolean testSimpleAthrow() {
		boolean exp = false;
		try {
			throw new ArithmeticException();
		} catch (ArithmeticException ae) {
			exp = true;
		}
		return exp;
	}

	private boolean testNestedAthrow() {
		boolean exp = false;
		try {
			try {
				throw new ArithmeticException();
			} catch (IndexOutOfBoundsException ie) {
				return false;
			}
		} catch (ArithmeticException ae) {
			exp = true;
		}
		return exp;
	}
}
