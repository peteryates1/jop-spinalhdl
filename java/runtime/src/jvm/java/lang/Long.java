package java.lang;

public class Long extends Number {

	private final long value;

	public Long(long value) {
		this.value = value;
	}

	public Long(String s) {
		this.value = parseLong(s, 10);
	}

	public long longValue() {
		return value;
	}

	public int intValue() {
		return (int) value;
	}

	public float floatValue() {
		return (float) value;
	}

	public double doubleValue() {
		return (double) value;
	}

	public static Long valueOf(long l) {
		return new Long(l);
	}

	public static Long valueOf(String s) {
		return valueOf(parseLong(s, 10));
	}

	public boolean equals(Object obj) {
		if (obj instanceof Long) {
			return value == ((Long) obj).value;
		}
		return false;
	}

	public int hashCode() {
		return (int)(value ^ (value >>> 32));
	}

	/**
	 * Table for calculating digits, used in Character, Long, and Integer.
	 */
	static final char[] digits = {
		'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
		'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j',
		'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't',
		'u', 'v', 'w', 'x', 'y', 'z'
	};

	/**
	 * The minimum value a <code>long</code> can represent is
	 * -9223372036854775808L (or -2<sup>63</sup>).
	 */
	public static final long MIN_VALUE = 0x8000000000000000L;

	/**
	 * The maximum value a <code>long</code> can represent is
	 * 9223372036854775807 (or 2<sup>63</sup> - 1).
	 */
	public static final long MAX_VALUE = 0x7fffffffffffffffL;
	
	public static String toString(long num) {
		return toString(num, 10);
	}
	
	/**
	 * Converts the <code>long</code> to a <code>String</code> using the
	 * specified radix (base). If the radix exceeds
	 * <code>Character.MIN_RADIX</code> or <code>Character.MAX_RADIX</code>,
	 * 10 is used instead. If the result is negative, the leading character is
	 * '-' ('\\u002D'). The remaining characters come from
	 * <code>Character.forDigit(digit, radix)</code> ('0'-'9','a'-'z').
	 * 
	 * @param num
	 *            the <code>long</code> to convert to <code>String</code>
	 * @param radix
	 *            the radix (base) to use in the conversion
	 * @return the <code>String</code> representation of the argument
	 */
	public static String toString(long num, int radix) {
		// Use the Integer toString for efficiency if possible.
		if ((int) num == num)
			return Integer.toString((int) num, radix);

		if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX)
			radix = 10;

		// For negative numbers, print out the absolute value w/ a leading '-'.
		// Use an array large enough for a binary number.
		char[] buffer = new char[65];
		int i = 65;
		boolean isNeg = false;
		if (num < 0) {
			isNeg = true;
			num = -num;

			// When the value is MIN_VALUE, it overflows when made positive
			if (num < 0) {
				buffer[--i] = digits[(int) (-(num + radix) % radix)];
				num = -(num / radix);
			}
		}

		do {
			buffer[--i] = digits[(int) (num % radix)];
			num /= radix;
		} while (num > 0 && i > 0);

		if (isNeg)
			buffer[--i] = '-';

		// Package constructor avoids an array copy.
		return new String(buffer, i, 65 - i);
	}

	public static int signum(long i) {
		return (int) ((i >> 63) | (-i >>> 63));
	}

	public static long parseLong(String s) {
		return parseLong(s, 10);
	}

	public static long parseLong(String s, int radix) {
		if (s == null) throw new NumberFormatException("null");
		int len = s.length();
		if (len == 0) throw new NumberFormatException(s);
		boolean negative = false;
		int i = 0;
		if (s.charAt(0) == '-') {
			negative = true;
			i++;
		} else if (s.charAt(0) == '+') {
			i++;
		}
		if (i >= len) throw new NumberFormatException(s);
		long result = 0;
		while (i < len) {
			int digit = Character.digit(s.charAt(i++), radix);
			if (digit < 0) throw new NumberFormatException(s);
			result = result * radix + digit;
		}
		return negative ? -result : result;
	}

	public static int numberOfLeadingZeros(long i) {
		if (i == 0) return 64;
		int n = 1;
		int x = (int)(i >>> 32);
		if (x == 0) { n += 32; x = (int)i; }
		if (x >>> 16 == 0) { n += 16; x <<= 16; }
		if (x >>> 24 == 0) { n +=  8; x <<=  8; }
		if (x >>> 28 == 0) { n +=  4; x <<=  4; }
		if (x >>> 30 == 0) { n +=  2; x <<=  2; }
		n -= x >>> 31;
		return n;
	}

	public static int bitCount(long i) {
		i = i - ((i >>> 1) & 0x5555555555555555L);
		i = (i & 0x3333333333333333L) + ((i >>> 2) & 0x3333333333333333L);
		i = (i + (i >>> 4)) & 0x0f0f0f0f0f0f0f0fL;
		i = i + (i >>> 8);
		i = i + (i >>> 16);
		i = i + (i >>> 32);
		return (int)i & 0x7f;
	}
}
