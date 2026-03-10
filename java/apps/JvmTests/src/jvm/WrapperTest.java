package jvm;

/**
 * Test wrapper classes and Math from the ported JDK classes (Phase 3).
 * Exercises Integer.parseInt, Boolean, Byte, Short, Number, Comparable,
 * and Math bug fixes (sin/cos/atan/pow).
 */
public class WrapperTest extends TestCase {

	public String toString() {
		return "WrapperTest";
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && integerParseInt();
		ok = ok && integerValueOf();
		ok = ok && integerCompare();
		ok = ok && integerHex();
		ok = ok && booleanTest();
		ok = ok && byteTest();
		ok = ok && shortTest();
		ok = ok && numberTest();
		ok = ok && mathPow();
		ok = ok && floatWrapper();
		ok = ok && doubleWrapper();
		return ok;
	}

	// --- Integer tests ---

	private boolean integerParseInt() {
		if (Integer.parseInt("0") != 0) return false;
		if (Integer.parseInt("42") != 42) return false;
		if (Integer.parseInt("-1") != -1) return false;
		if (Integer.parseInt("2147483647") != 2147483647) return false;
		if (Integer.parseInt("-2147483648") != -2147483648) return false;
		if (Integer.parseInt("FF", 16) != 255) return false;
		if (Integer.parseInt("10", 2) != 2) return false;
		if (Integer.parseInt("77", 8) != 63) return false;
		return true;
	}

	private boolean integerValueOf() {
		Integer a = Integer.valueOf(42);
		Integer b = Integer.valueOf(42);
		if (a.intValue() != 42) return false;
		if (!a.equals(b)) return false;
		if (a.hashCode() != 42) return false;
		return true;
	}

	private boolean integerCompare() {
		Integer a = Integer.valueOf(10);
		Integer b = Integer.valueOf(20);
		Integer c = Integer.valueOf(10);
		if (a.compareTo(b) >= 0) return false;  // 10 < 20
		if (b.compareTo(a) <= 0) return false;  // 20 > 10
		if (a.compareTo(c) != 0) return false;  // 10 == 10
		return true;
	}

	private boolean integerHex() {
		String h = Integer.toHexString(255);
		if (!"ff".equals(h)) return false;
		h = Integer.toHexString(0);
		if (!"0".equals(h)) return false;
		h = Integer.toHexString(16);
		if (!"10".equals(h)) return false;
		return true;
	}

	// --- Boolean ---

	private boolean booleanTest() {
		Boolean t = new Boolean(true);
		Boolean f = new Boolean(false);
		if (!t.booleanValue()) return false;
		if (f.booleanValue()) return false;
		if (!t.equals(new Boolean(true))) return false;
		if (t.equals(f)) return false;
		return true;
	}

	// --- Byte ---

	private boolean byteTest() {
		Byte b = new Byte((byte) 42);
		if (b.byteValue() != 42) return false;
		if (b.intValue() != 42) return false;
		Byte neg = new Byte((byte) -1);
		if (neg.byteValue() != -1) return false;
		if (Byte.parseByte("127") != 127) return false;
		if (Byte.parseByte("-128") != -128) return false;
		return true;
	}

	// --- Short ---

	private boolean shortTest() {
		Short s = new Short((short) 1000);
		if (s.shortValue() != 1000) return false;
		if (s.intValue() != 1000) return false;
		if (Short.parseShort("32767") != 32767) return false;
		if (Short.parseShort("-32768") != -32768) return false;
		return true;
	}

	// --- Number (Integer extends Number) ---

	private boolean numberTest() {
		Number n = Integer.valueOf(99);
		if (n.intValue() != 99) return false;
		if (n.longValue() != 99L) return false;
		return true;
	}

	// --- Float wrapper ---

	private boolean floatWrapper() {
		Float f = new Float(3.14f);
		if (f.intValue() != 3) return false;
		if (f.longValue() != 3L) return false;
		// floatValue
		float fv = f.floatValue();
		if (fv < 3.13f || fv > 3.15f) return false;
		// doubleValue
		double dv = f.doubleValue();
		if (dv < 3.13 || dv > 3.15) return false;
		// floatToIntBits / intBitsToFloat roundtrip
		int bits = Float.floatToIntBits(1.0f);
		if (bits != 0x3f800000) return false;
		float back = Float.intBitsToFloat(bits);
		if (back != 1.0f) return false;
		// isNaN / isInfinite
		if (!Float.isNaN(Float.NaN)) return false;
		if (Float.isNaN(1.0f)) return false;
		if (!Float.isInfinite(Float.POSITIVE_INFINITY)) return false;
		if (Float.isInfinite(1.0f)) return false;
		// equals
		Float a = new Float(1.0f);
		Float b = new Float(1.0f);
		if (!a.equals(b)) return false;
		// compare
		if (Float.compare(1.0f, 2.0f) >= 0) return false;
		if (Float.compare(2.0f, 1.0f) <= 0) return false;
		if (Float.compare(1.0f, 1.0f) != 0) return false;
		return true;
	}

	// --- Double wrapper ---

	private boolean doubleWrapper() {
		Double d = new Double(2.718);
		if (d.intValue() != 2) return false;
		if (d.longValue() != 2L) return false;
		// doubleValue
		double dv = d.doubleValue();
		if (dv < 2.71 || dv > 2.72) return false;
		// floatValue
		float fv = d.floatValue();
		if (fv < 2.71f || fv > 2.72f) return false;
		// doubleToLongBits / longBitsToDouble roundtrip
		long bits = Double.doubleToLongBits(1.0);
		if (bits != 0x3ff0000000000000L) return false;
		double back = Double.longBitsToDouble(bits);
		if (back != 1.0) return false;
		// isNaN / isInfinite
		if (!Double.isNaN(Double.NaN)) return false;
		if (Double.isNaN(1.0)) return false;
		if (!Double.isInfinite(Double.NEGATIVE_INFINITY)) return false;
		if (Double.isInfinite(0.0)) return false;
		// equals
		Double a = new Double(3.0);
		Double b = new Double(3.0);
		if (!a.equals(b)) return false;
		// compare
		if (Double.compare(1.0, 2.0) >= 0) return false;
		if (Double.compare(2.0, 1.0) <= 0) return false;
		if (Double.compare(5.0, 5.0) != 0) return false;
		return true;
	}

	// --- Math.pow ---

	private boolean mathPow() {
		// Integer exponents
		if (Math.pow(2.0, 0.0) != 1.0) return false;
		if (Math.pow(0.0, 5.0) != 0.0) return false;
		if (Math.pow(1.0, 100.0) != 1.0) return false;
		if (Math.pow(2.0, 10.0) != 1024.0) return false;
		if (Math.pow(3.0, 3.0) != 27.0) return false;
		// Negative exponent
		if (Math.pow(2.0, -1.0) != 0.5) return false;
		return true;
	}
}
