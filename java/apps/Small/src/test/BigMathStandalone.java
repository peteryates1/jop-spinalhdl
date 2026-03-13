package test;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Standalone BigMath test for FPGA debugging.
 * Tests BigInteger first, then BigDecimal separately.
 */
public class BigMathStandalone {

	static int passed = 0;
	static int failed = 0;

	static void check(String name, boolean ok) {
		if (ok) {
			passed++;
			System.out.print(name);
			System.out.println(" ok");
		} else {
			failed++;
			System.out.print(name);
			System.out.println(" FAIL");
		}
	}

	public static void main(String[] args) {
		System.out.println("BigMath standalone");

		// Initialize lazy constants (JOP: no clinit allocation)
		BigInteger.ensureConstants();
		RoundingMode.ensureInstances();
		BigDecimal.ensureBdConstants();
		MathContext.ensureConstants();

		// Phase 1: BigInteger only
		System.out.println("--- BigInteger ---");
		check("BI create", testBigIntCreate());
		check("BI arith", testBigIntArith());
		check("BI compare", testBigIntCompare());
		check("BI bitops", testBigIntBitOps());
		check("BI toString", testBigIntToString());

		// Phase 2: BigDecimal
		System.out.println("--- BigDecimal ---");
		check("BD create", testBigDecCreate());
		check("BD arith", testBigDecArith());
		check("BD scale", testBigDecScale());
		check("BD compare", testBigDecCompare());
		check("BD mathctx", testMathContext());

		System.out.print("Result: ");
		System.out.print(passed);
		System.out.print("/");
		int total = passed + failed;
		System.out.println(total);
	}

	static boolean testBigIntCreate() {
		BigInteger zero = BigInteger.ZERO;
		BigInteger one = BigInteger.ONE;
		BigInteger ten = BigInteger.TEN;
		if (zero.intValue() != 0) return false;
		if (one.intValue() != 1) return false;
		if (ten.intValue() != 10) return false;
		BigInteger a = new BigInteger("12345");
		if (a.intValue() != 12345) return false;
		BigInteger neg = new BigInteger("-42");
		if (neg.intValue() != -42) return false;
		BigInteger v = BigInteger.valueOf(999L);
		if (v.intValue() != 999) return false;
		return true;
	}

	static boolean testBigIntArith() {
		BigInteger a = BigInteger.valueOf(100);
		BigInteger b = BigInteger.valueOf(23);
		if (a.add(b).intValue() != 123) return false;
		if (a.subtract(b).intValue() != 77) return false;
		if (a.multiply(b).intValue() != 2300) return false;
		if (a.divide(b).intValue() != 4) return false;
		if (a.remainder(b).intValue() != 8) return false;
		if (a.negate().intValue() != -100) return false;
		if (a.negate().abs().intValue() != 100) return false;
		return true;
	}

	static boolean testBigIntCompare() {
		BigInteger a = BigInteger.valueOf(50);
		BigInteger b = BigInteger.valueOf(100);
		BigInteger c = BigInteger.valueOf(50);
		if (a.compareTo(b) >= 0) return false;
		if (b.compareTo(a) <= 0) return false;
		if (a.compareTo(c) != 0) return false;
		if (!a.equals(c)) return false;
		if (a.equals(b)) return false;
		if (a.signum() != 1) return false;
		if (BigInteger.ZERO.signum() != 0) return false;
		if (a.negate().signum() != -1) return false;
		return true;
	}

	static boolean testBigIntBitOps() {
		BigInteger a = new BigInteger("255");
		BigInteger b = new BigInteger("15");
		if (a.and(b).intValue() != 15) return false;
		if (a.or(BigInteger.valueOf(256)).intValue() != 511) return false;
		if (a.xor(b).intValue() != 240) return false;
		if (BigInteger.ONE.shiftLeft(8).intValue() != 256) return false;
		if (BigInteger.valueOf(256).shiftRight(4).intValue() != 16) return false;
		if (BigInteger.valueOf(255).bitLength() != 8) return false;
		if (BigInteger.valueOf(256).bitLength() != 9) return false;
		return true;
	}

	static boolean testBigIntToString() {
		BigInteger a = BigInteger.valueOf(12345);
		String s = a.toString();
		if (s.length() != 5) return false;
		if (s.charAt(0) != '1') return false;
		if (s.charAt(4) != '5') return false;
		BigInteger neg = BigInteger.valueOf(-42);
		String ns = neg.toString();
		if (ns.charAt(0) != '-') return false;
		if (ns.length() != 3) return false;
		String zs = BigInteger.ZERO.toString();
		if (zs.charAt(0) != '0') return false;
		if (zs.length() != 1) return false;
		return true;
	}

	static boolean testBigDecCreate() {
		BigDecimal zero = BigDecimal.ZERO;
		BigDecimal one = BigDecimal.ONE;
		BigDecimal ten = BigDecimal.TEN;
		if (zero.intValue() != 0) return false;
		if (one.intValue() != 1) return false;
		if (ten.intValue() != 10) return false;
		BigDecimal a = BigDecimal.valueOf(42L);
		if (a.intValue() != 42) return false;
		BigDecimal b = BigDecimal.valueOf(12345L, 2);
		if (b.intValue() != 123) return false;
		return true;
	}

	static boolean testBigDecArith() {
		BigDecimal a = BigDecimal.valueOf(100L);
		BigDecimal b = BigDecimal.valueOf(3L);
		if (a.add(b).intValue() != 103) return false;
		if (a.subtract(b).intValue() != 97) return false;
		if (a.multiply(b).intValue() != 300) return false;
		BigDecimal quot = a.divide(b, 2, RoundingMode.HALF_UP);
		if (quot.intValue() != 33) return false;
		if (a.negate().intValue() != -100) return false;
		return true;
	}

	static boolean testBigDecScale() {
		BigDecimal a = BigDecimal.valueOf(12345L, 2);
		if (a.scale() != 2) return false;
		if (a.unscaledValue().intValue() != 12345) return false;
		BigDecimal b = BigDecimal.valueOf(10L);
		BigDecimal b2 = b.setScale(2);
		if (b2.scale() != 2) return false;
		if (b2.intValue() != 10) return false;
		BigDecimal c = new BigDecimal("12345");
		if (c.precision() != 5) return false;
		return true;
	}

	static boolean testBigDecCompare() {
		BigDecimal a = BigDecimal.valueOf(50L);
		BigDecimal b = BigDecimal.valueOf(100L);
		BigDecimal c = BigDecimal.valueOf(50L);
		if (a.compareTo(b) >= 0) return false;
		if (b.compareTo(a) <= 0) return false;
		if (a.compareTo(c) != 0) return false;
		BigDecimal d = BigDecimal.valueOf(5000L, 2);
		if (a.compareTo(d) != 0) return false;
		return true;
	}

	static boolean testMathContext() {
		MathContext mc = new MathContext(4, RoundingMode.HALF_UP);
		if (mc.getPrecision() != 4) return false;
		if (mc.getRoundingMode() != RoundingMode.HALF_UP) return false;
		MathContext dec32 = MathContext.DECIMAL32;
		if (dec32.getPrecision() != 7) return false;
		MathContext dec64 = MathContext.DECIMAL64;
		if (dec64.getPrecision() != 16) return false;
		return true;
	}
}
