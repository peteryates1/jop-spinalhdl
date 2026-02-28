package jvm;

/**
 * Test string concatenation with int values.
 * Bug: StringBuilder had no toString() method, causing infinite recursion
 * via Object.toString() which itself does "Object " + hashCode().
 */
public class StringConcat extends TestCase {

	public String toString() {
		return "StringConcat";
	}

	public boolean test() {
		// T0: StringBuilder.toString() basic
		StringBuilder sb = new StringBuilder("hello");
		String s = sb.toString();
		if (s.length() != 5) return false;

		// T1: String + int concatenation
		int val = 42;
		String result = "x=" + val;
		if (result.length() != 4) return false;  // "x=42"

		// T2: int at start
		result = "" + 7;
		if (result.length() != 1) return false;

		// T3: negative int
		result = "n=" + (-1);
		if (result.length() != 4) return false;  // "n=-1"

		// T4: zero
		result = "" + 0;
		if (result.length() != 1) return false;

		// T5: Integer.toString direct
		String numStr = String.valueOf(123);
		if (numStr.length() != 3) return false;

		// T6: multiple appends
		result = "a" + 1 + "b" + 2;
		if (result.length() != 4) return false;  // "a1b2"

		return true;
	}
}
