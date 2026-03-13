package jvm;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.ParsePosition;

/**
 * Test Phase 8 java.text: DecimalFormat, NumberFormat, parsing.
 * Avoids Double.parseDouble (stubbed on JOP) — tests integer formatting
 * and basic patterns.
 */
public class TextFormatTest extends TestCase {

	public String toString() {
		return "TextFormatTest";
	}

	public boolean test() {
		// Initialize lazy constants (JOP: no clinit allocation)
		java.math.RoundingMode.ensureInstances();

		boolean ok = true;
		ok = ok && formatInteger();
		ok = ok && formatGrouping();
		ok = ok && formatNegative();
		ok = ok && formatPattern();
		ok = ok && parseInteger();
		ok = ok && parseNegative();
		ok = ok && parsePosition();
		ok = ok && symbolsTest();
		ok = ok && factoryMethods();
		ok = ok && fieldPosition();
		return ok;
	}

	// Helper: check if formatted string equals expected
	private boolean eq(String a, String b) {
		if (a.length() != b.length()) return false;
		for (int i = 0; i < a.length(); i++) {
			if (a.charAt(i) != b.charAt(i)) return false;
		}
		return true;
	}

	private boolean formatInteger() {
		DecimalFormat df = new DecimalFormat("0");
		// Simple integer
		String s = df.format(42);
		if (!eq(s, "42")) return false;
		// Zero
		s = df.format(0);
		if (!eq(s, "0")) return false;
		// Large
		s = df.format(1000000);
		if (!eq(s, "1000000")) return false;
		return true;
	}

	private boolean formatGrouping() {
		DecimalFormat df = new DecimalFormat("#,##0");
		String s = df.format(1234567);
		if (!eq(s, "1,234,567")) return false;
		s = df.format(999);
		if (!eq(s, "999")) return false;
		s = df.format(1000);
		if (!eq(s, "1,000")) return false;
		return true;
	}

	private boolean formatNegative() {
		DecimalFormat df = new DecimalFormat("#,##0");
		String s = df.format(-42);
		if (!eq(s, "-42")) return false;
		s = df.format(-1234);
		if (!eq(s, "-1,234")) return false;
		return true;
	}

	private boolean formatPattern() {
		// Minimum integer digits
		DecimalFormat df = new DecimalFormat("000");
		String s = df.format(7);
		if (!eq(s, "007")) return false;
		s = df.format(42);
		if (!eq(s, "042")) return false;
		s = df.format(1234);
		if (!eq(s, "1234")) return false;
		return true;
	}

	private boolean parseInteger() {
		DecimalFormat df = new DecimalFormat("#,##0");
		try {
			Number n = df.parse("12345");
			if (n.longValue() != 12345L) return false;
		} catch (ParseException e) {
			return false;
		}
		// With grouping
		try {
			Number n = df.parse("1,234,567");
			if (n.longValue() != 1234567L) return false;
		} catch (ParseException e) {
			return false;
		}
		return true;
	}

	private boolean parseNegative() {
		DecimalFormat df = new DecimalFormat("#,##0");
		try {
			Number n = df.parse("-42");
			if (n.longValue() != -42L) return false;
		} catch (ParseException e) {
			return false;
		}
		try {
			Number n = df.parse("-1,000");
			if (n.longValue() != -1000L) return false;
		} catch (ParseException e) {
			return false;
		}
		return true;
	}

	private boolean parsePosition() {
		DecimalFormat df = new DecimalFormat("0");
		ParsePosition pp = new ParsePosition(0);
		Number n = df.parse("123abc", pp);
		if (n == null) return false;
		if (n.longValue() != 123L) return false;
		// Parse position should stop at 'a'
		if (pp.getIndex() != 3) return false;
		// Error case
		ParsePosition pp2 = new ParsePosition(0);
		Number n2 = df.parse("abc", pp2);
		if (n2 != null) return false;
		if (pp2.getErrorIndex() < 0) return false;
		return true;
	}

	private boolean symbolsTest() {
		DecimalFormatSymbols dfs = new DecimalFormatSymbols();
		// US English defaults
		if (dfs.getDecimalSeparator() != '.') return false;
		if (dfs.getGroupingSeparator() != ',') return false;
		if (dfs.getMinusSign() != '-') return false;
		if (dfs.getZeroDigit() != '0') return false;
		if (dfs.getPercent() != '%') return false;
		if (dfs.getDigit() != '#') return false;
		// Modify
		dfs.setGroupingSeparator('_');
		if (dfs.getGroupingSeparator() != '_') return false;
		return true;
	}

	private boolean factoryMethods() {
		// NumberFormat.getInstance
		NumberFormat nf = NumberFormat.getInstance();
		String s = nf.format(1234);
		if (!eq(s, "1,234")) return false;
		// getIntegerInstance
		NumberFormat intf = NumberFormat.getIntegerInstance();
		s = intf.format(42);
		if (!eq(s, "42")) return false;
		return true;
	}

	private boolean fieldPosition() {
		FieldPosition fp = new FieldPosition(NumberFormat.INTEGER_FIELD);
		DecimalFormat df = new DecimalFormat("#,##0");
		StringBuffer sb = new StringBuffer();
		df.format(12345, sb, fp);
		// Integer field should have begin/end set
		if (fp.getBeginIndex() >= fp.getEndIndex()) return false;
		if (fp.getEndIndex() <= 0) return false;
		return true;
	}
}
