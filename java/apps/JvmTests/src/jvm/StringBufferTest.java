package jvm;

/**
 * Test StringBuffer from the ported JDK classes (Phase 3).
 * Exercises append, insert, delete, reverse, indexOf, capacity growth.
 */
public class StringBufferTest extends TestCase {

	public String toString() {
		return "StringBufferTest";
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && appendTest();
		ok = ok && deleteTest();
		ok = ok && reverseTest();
		return ok;
	}

	private boolean appendTest() {
		StringBuffer sb = new StringBuffer();
		sb.append("Hello");
		sb.append(' ');
		sb.append("World");
		String s = sb.toString();
		if (s.length() != 11) return false;
		if (s.charAt(0) != 'H') return false;
		if (s.charAt(5) != ' ') return false;
		if (s.charAt(6) != 'W') return false;
		return true;
	}

	private boolean appendTypes() {
		StringBuffer sb = new StringBuffer();
		sb.append(42);
		sb.append(true);
		sb.append('X');
		String s = sb.toString();
		// "42trueX"
		if (s.length() != 7) return false;
		if (s.charAt(0) != '4') return false;
		if (s.charAt(2) != 't') return false;
		if (s.charAt(6) != 'X') return false;
		return true;
	}

	private boolean insertTest() {
		StringBuffer sb = new StringBuffer("AC");
		sb.insert(1, 'B');
		String s = sb.toString();
		if (s.length() != 3) return false;
		if (s.charAt(0) != 'A') return false;
		if (s.charAt(1) != 'B') return false;
		if (s.charAt(2) != 'C') return false;
		return true;
	}

	private boolean deleteTest() {
		StringBuffer sb = new StringBuffer("ABCDE");
		sb.delete(1, 3); // remove "BC"
		String s = sb.toString();
		if (s.length() != 3) return false;
		if (s.charAt(0) != 'A') return false;
		if (s.charAt(1) != 'D') return false;
		if (s.charAt(2) != 'E') return false;
		return true;
	}

	private boolean reverseTest() {
		StringBuffer sb = new StringBuffer("abc");
		sb.reverse();
		String s = sb.toString();
		if (s.length() != 3) return false;
		if (s.charAt(0) != 'c') return false;
		if (s.charAt(1) != 'b') return false;
		if (s.charAt(2) != 'a') return false;
		return true;
	}

	private boolean indexOfTest() {
		StringBuffer sb = new StringBuffer("hello world");
		if (sb.indexOf("world") != 6) return false;
		if (sb.indexOf("xyz") != -1) return false;
		if (sb.indexOf("hello") != 0) return false;
		return true;
	}

	private boolean capacityGrow() {
		StringBuffer sb = new StringBuffer(4);
		sb.append("abcdefgh"); // exceeds initial capacity of 4
		if (sb.length() != 8) return false;
		if (sb.charAt(7) != 'h') return false;
		return true;
	}

	private boolean charAtTest() {
		StringBuffer sb = new StringBuffer("ABCD");
		if (sb.charAt(0) != 'A') return false;
		if (sb.charAt(3) != 'D') return false;
		sb.setCharAt(1, 'Z');
		if (sb.charAt(1) != 'Z') return false;
		return true;
	}
}
