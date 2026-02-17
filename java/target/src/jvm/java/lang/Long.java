package java.lang;

public class Long {
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
}
