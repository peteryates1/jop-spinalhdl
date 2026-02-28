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

		// T7: StringBuilder resize (triggers System.arraycopy)
		// Default capacity = 16, so appending >16 chars forces ensureCapacity
		sb = new StringBuilder();
		sb.append("abcdefghij");      // 10 chars
		sb.append("klmnopqrst");      // 20 total — exceeds 16, triggers resize
		s = sb.toString();
		if (s.length() != 20) return false;
		if (s.charAt(0) != 'a') return false;
		if (s.charAt(19) != 't') return false;

		// T8: resize with int append (resize + valueOf)
		sb = new StringBuilder();
		sb.append("value=");           // 6 chars
		sb.append(1234567890);         // 10 digit number — 16 total, exactly at capacity
		sb.append("!");                // 17 total — triggers resize
		s = sb.toString();
		if (s.length() != 17) return false;

		return true;
	}
}
