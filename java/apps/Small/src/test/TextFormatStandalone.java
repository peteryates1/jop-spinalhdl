package test;

import java.math.RoundingMode;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.math.MathContext;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.ParsePosition;

/**
 * Standalone test for BigMath (Phase 7) + TextFormat (Phase 8) on FPGA.
 */
public class TextFormatStandalone {

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

	static boolean eq(String a, String b) {
		if (a.length() != b.length()) return false;
		for (int i = 0; i < a.length(); i++) {
			if (a.charAt(i) != b.charAt(i)) return false;
		}
		return true;
	}

	public static void main(String[] args) {
		System.out.println("TextFormat standalone");

		// Initialize lazy constants
		RoundingMode.ensureInstances();
		BigInteger.ensureConstants();
		BigDecimal.ensureBdConstants();
		MathContext.ensureConstants();

		// --- BigMath tests ---
		System.out.println("--- BigMath ---");
		check("BI create", testBigIntCreate());
		check("BI arith", testBigIntArith());
		check("BD create", testBigDecCreate());
		check("BD arith", testBigDecArith());

		// --- TextFormat tests ---
		System.out.println("--- TextFormat ---");
		check("TF integer", testFormatInteger());
		check("TF grouping", testFormatGrouping());
		check("TF negative", testFormatNegative());
		check("TF pattern", testFormatPattern());
		check("TF parse", testParseInteger());
		check("TF parseNeg", testParseNegative());
		check("TF parsPos", testParsePosition());
		check("TF symbols", testSymbols());
		check("TF factory", testFactory());
		check("TF fieldPos", testFieldPosition());

		System.out.print("Result: ");
		System.out.print(passed);
		System.out.print("/");
		int total = passed + failed;
		System.out.println(total);
	}

	// --- BigInteger ---
	static boolean testBigIntCreate() {
		if (BigInteger.ZERO.intValue() != 0) return false;
		if (BigInteger.ONE.intValue() != 1) return false;
		BigInteger a = new BigInteger("12345");
		if (a.intValue() != 12345) return false;
		return true;
	}

	static boolean testBigIntArith() {
		BigInteger a = BigInteger.valueOf(100);
		BigInteger b = BigInteger.valueOf(23);
		if (a.add(b).intValue() != 123) return false;
		if (a.multiply(b).intValue() != 2300) return false;
		if (a.divide(b).intValue() != 4) return false;
		return true;
	}

	// --- BigDecimal ---
	static boolean testBigDecCreate() {
		if (BigDecimal.ZERO.intValue() != 0) return false;
		BigDecimal a = BigDecimal.valueOf(42L);
		if (a.intValue() != 42) return false;
		return true;
	}

	static boolean testBigDecArith() {
		BigDecimal a = BigDecimal.valueOf(100L);
		BigDecimal b = BigDecimal.valueOf(3L);
		if (a.add(b).intValue() != 103) return false;
		BigDecimal quot = a.divide(b, 2, RoundingMode.HALF_UP);
		if (quot.intValue() != 33) return false;
		return true;
	}

	// --- TextFormat ---
	static boolean testFormatInteger() {
		DecimalFormat df = new DecimalFormat("0");
		if (!eq(df.format(42), "42")) return false;
		if (!eq(df.format(0), "0")) return false;
		if (!eq(df.format(1000000), "1000000")) return false;
		return true;
	}

	static boolean testFormatGrouping() {
		DecimalFormat df = new DecimalFormat("#,##0");
		if (!eq(df.format(1234567), "1,234,567")) return false;
		if (!eq(df.format(999), "999")) return false;
		if (!eq(df.format(1000), "1,000")) return false;
		return true;
	}

	static boolean testFormatNegative() {
		DecimalFormat df = new DecimalFormat("#,##0");
		if (!eq(df.format(-42), "-42")) return false;
		if (!eq(df.format(-1234), "-1,234")) return false;
		return true;
	}

	static boolean testFormatPattern() {
		DecimalFormat df = new DecimalFormat("000");
		if (!eq(df.format(7), "007")) return false;
		if (!eq(df.format(42), "042")) return false;
		if (!eq(df.format(1234), "1234")) return false;
		return true;
	}

	static boolean testParseInteger() {
		DecimalFormat df = new DecimalFormat("#,##0");
		try {
			Number n = df.parse("12345");
			if (n.longValue() != 12345L) return false;
			n = df.parse("1,234,567");
			if (n.longValue() != 1234567L) return false;
		} catch (ParseException e) {
			return false;
		}
		return true;
	}

	static boolean testParseNegative() {
		DecimalFormat df = new DecimalFormat("#,##0");
		try {
			Number n = df.parse("-42");
			if (n.longValue() != -42L) return false;
			n = df.parse("-1,000");
			if (n.longValue() != -1000L) return false;
		} catch (ParseException e) {
			return false;
		}
		return true;
	}

	static boolean testParsePosition() {
		DecimalFormat df = new DecimalFormat("0");
		ParsePosition pp = new ParsePosition(0);
		Number n = df.parse("123abc", pp);
		if (n == null) return false;
		if (n.longValue() != 123L) return false;
		if (pp.getIndex() != 3) return false;
		return true;
	}

	static boolean testSymbols() {
		DecimalFormatSymbols dfs = new DecimalFormatSymbols();
		if (dfs.getDecimalSeparator() != '.') return false;
		if (dfs.getGroupingSeparator() != ',') return false;
		if (dfs.getMinusSign() != '-') return false;
		return true;
	}

	static boolean testFactory() {
		NumberFormat nf = NumberFormat.getInstance();
		if (!eq(nf.format(1234), "1,234")) return false;
		NumberFormat intf = NumberFormat.getIntegerInstance();
		if (!eq(intf.format(42), "42")) return false;
		return true;
	}

	static boolean testFieldPosition() {
		FieldPosition fp = new FieldPosition(NumberFormat.INTEGER_FIELD);
		DecimalFormat df = new DecimalFormat("#,##0");
		StringBuffer sb = new StringBuffer();
		df.format(12345, sb, fp);
		if (fp.getBeginIndex() >= fp.getEndIndex()) return false;
		if (fp.getEndIndex() <= 0) return false;
		return true;
	}
}
