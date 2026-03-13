package jvm;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Test Phase 7 java.math: BigInteger and BigDecimal.
 * Keeps operations small to avoid excessive cycle counts in BRAM sim.
 */
public class BigMathTest extends TestCase {

	public String toString() {
		return "BigMathTest";
	}

	public boolean test() {
		// Initialize lazy constants (JOP: no clinit allocation)
		BigInteger.ensureConstants();
		RoundingMode.ensureInstances();
		BigDecimal.ensureBdConstants();
		MathContext.ensureConstants();

		boolean ok = true;
		ok = ok && bigIntCreate();
		ok = ok && bigIntArith();
		ok = ok && bigIntCompare();
		ok = ok && bigIntBitOps();
		ok = ok && bigIntToString();
		ok = ok && bigDecCreate();
		ok = ok && bigDecArith();
		ok = ok && bigDecScale();
		ok = ok && bigDecCompare();
		ok = ok && mathContext();
		return ok;
	}

	// --- BigInteger tests ---

	private boolean bigIntCreate() {
		BigInteger zero = BigInteger.ZERO;
		BigInteger one = BigInteger.ONE;
		BigInteger ten = BigInteger.TEN;
		if (zero.intValue() != 0) return false;
		if (one.intValue() != 1) return false;
		if (ten.intValue() != 10) return false;
		// String constructor
		BigInteger a = new BigInteger("12345");
		if (a.intValue() != 12345) return false;
		// Negative
		BigInteger neg = new BigInteger("-42");
		if (neg.intValue() != -42) return false;
		// valueOf
		BigInteger v = BigInteger.valueOf(999L);
		if (v.intValue() != 999) return false;
		return true;
	}

	private boolean bigIntArith() {
		BigInteger a = BigInteger.valueOf(100);
		BigInteger b = BigInteger.valueOf(23);
		// add
		BigInteger sum = a.add(b);
		if (sum.intValue() != 123) return false;
		// subtract
		BigInteger diff = a.subtract(b);
		if (diff.intValue() != 77) return false;
		// multiply
		BigInteger prod = a.multiply(b);
		if (prod.intValue() != 2300) return false;
		// divide
		BigInteger quot = a.divide(b);
		if (quot.intValue() != 4) return false;  // 100/23 = 4
		// remainder
		BigInteger rem = a.remainder(b);
		if (rem.intValue() != 8) return false;  // 100%23 = 8
		// negate
		BigInteger neg = a.negate();
		if (neg.intValue() != -100) return false;
		// abs
		if (neg.abs().intValue() != 100) return false;
		return true;
	}

	private boolean bigIntCompare() {
		BigInteger a = BigInteger.valueOf(50);
		BigInteger b = BigInteger.valueOf(100);
		BigInteger c = BigInteger.valueOf(50);
		if (a.compareTo(b) >= 0) return false;  // 50 < 100
		if (b.compareTo(a) <= 0) return false;  // 100 > 50
		if (a.compareTo(c) != 0) return false;  // 50 == 50
		if (!a.equals(c)) return false;
		if (a.equals(b)) return false;
		// signum
		if (a.signum() != 1) return false;
		if (BigInteger.ZERO.signum() != 0) return false;
		if (a.negate().signum() != -1) return false;
		return true;
	}

	private boolean bigIntBitOps() {
		BigInteger a = new BigInteger("255");   // 0xFF
		BigInteger b = new BigInteger("15");    // 0x0F
		// and
		BigInteger andResult = a.and(b);
		if (andResult.intValue() != 15) return false;
		// or
		BigInteger orResult = a.or(BigInteger.valueOf(256));  // 0xFF | 0x100
		if (orResult.intValue() != 511) return false;  // 0x1FF
		// xor
		BigInteger xorResult = a.xor(b);
		if (xorResult.intValue() != 240) return false;  // 0xF0
		// shiftLeft
		BigInteger shifted = BigInteger.ONE.shiftLeft(8);
		if (shifted.intValue() != 256) return false;
		// shiftRight
		BigInteger sr = BigInteger.valueOf(256).shiftRight(4);
		if (sr.intValue() != 16) return false;
		// bitLength
		if (BigInteger.valueOf(255).bitLength() != 8) return false;
		if (BigInteger.valueOf(256).bitLength() != 9) return false;
		return true;
	}

	private boolean bigIntToString() {
		BigInteger a = BigInteger.valueOf(12345);
		String s = a.toString();
		if (s.length() != 5) return false;
		if (s.charAt(0) != '1') return false;
		if (s.charAt(4) != '5') return false;
		// Negative
		BigInteger neg = BigInteger.valueOf(-42);
		String ns = neg.toString();
		if (ns.charAt(0) != '-') return false;
		if (ns.length() != 3) return false;
		// Zero
		String zs = BigInteger.ZERO.toString();
		if (zs.charAt(0) != '0') return false;
		if (zs.length() != 1) return false;
		return true;
	}

	// --- BigDecimal tests ---

	private boolean bigDecCreate() {
		BigDecimal zero = BigDecimal.ZERO;
		BigDecimal one = BigDecimal.ONE;
		BigDecimal ten = BigDecimal.TEN;
		if (zero.intValue() != 0) return false;
		if (one.intValue() != 1) return false;
		if (ten.intValue() != 10) return false;
		// int constructor
		BigDecimal a = BigDecimal.valueOf(42L);
		if (a.intValue() != 42) return false;
		// Scale constructor
		BigDecimal b = BigDecimal.valueOf(12345L, 2);  // 123.45
		if (b.intValue() != 123) return false;  // truncated to int
		return true;
	}

	private boolean bigDecArith() {
		BigDecimal a = BigDecimal.valueOf(100L);
		BigDecimal b = BigDecimal.valueOf(3L);
		// add
		BigDecimal sum = a.add(b);
		if (sum.intValue() != 103) return false;
		// subtract
		BigDecimal diff = a.subtract(b);
		if (diff.intValue() != 97) return false;
		// multiply
		BigDecimal prod = a.multiply(b);
		if (prod.intValue() != 300) return false;
		// divide with scale
		BigDecimal quot = a.divide(b, 2, RoundingMode.HALF_UP);
		// 100/3 = 33.33... → 33.33 with scale 2
		// intValue should be 33
		if (quot.intValue() != 33) return false;
		// negate
		BigDecimal neg = a.negate();
		if (neg.intValue() != -100) return false;
		return true;
	}

	private boolean bigDecScale() {
		// valueOf with scale
		BigDecimal a = BigDecimal.valueOf(12345L, 2);  // 123.45
		if (a.scale() != 2) return false;
		if (a.unscaledValue().intValue() != 12345) return false;
		// setScale
		BigDecimal b = BigDecimal.valueOf(10L);  // 10
		BigDecimal b2 = b.setScale(2);  // 10.00
		if (b2.scale() != 2) return false;
		if (b2.intValue() != 10) return false;
		// precision
		BigDecimal c = new BigDecimal("12345");
		if (c.precision() != 5) return false;
		return true;
	}

	private boolean bigDecCompare() {
		BigDecimal a = BigDecimal.valueOf(50L);
		BigDecimal b = BigDecimal.valueOf(100L);
		BigDecimal c = BigDecimal.valueOf(50L);
		if (a.compareTo(b) >= 0) return false;  // 50 < 100
		if (b.compareTo(a) <= 0) return false;  // 100 > 50
		if (a.compareTo(c) != 0) return false;  // 50 == 50
		// Different scale, same value
		BigDecimal d = BigDecimal.valueOf(5000L, 2);  // 50.00
		if (a.compareTo(d) != 0) return false;  // 50 == 50.00
		return true;
	}

	private boolean mathContext() {
		MathContext mc = new MathContext(4, RoundingMode.HALF_UP);
		if (mc.getPrecision() != 4) return false;
		if (mc.getRoundingMode() != RoundingMode.HALF_UP) return false;
		// Standard contexts
		MathContext dec32 = MathContext.DECIMAL32;
		if (dec32.getPrecision() != 7) return false;
		MathContext dec64 = MathContext.DECIMAL64;
		if (dec64.getPrecision() != 16) return false;
		return true;
	}
}
