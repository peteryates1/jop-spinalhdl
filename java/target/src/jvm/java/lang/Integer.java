package java.lang;

public class Integer {
	static final char[] digits = {
		'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
		'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j',
		'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't',
		'u', 'v', 'w', 'x', 'y', 'z',
	};
	
	/**
	 * The minimum value an <code>int</code> can represent is -2147483648 (or
	 * -2<sup>31</sup>).
	 */
	public static final int MIN_VALUE = 0x80000000;

	/**
	 * The maximum value an <code>int</code> can represent is 2147483647 (or
	 * 2<sup>31</sup> - 1).
	 */
	public static final int MAX_VALUE = 0x7fffffff;
	  
	public static String toString(int num, int radix) {

		if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX)
			radix = 10;

		// For negative numbers, print out the absolute value w/ a leading '-'.
		// Use an array large enough for a binary number.
		char[] buffer = new char[33];
		int i = 33;
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
			buffer[--i] = digits[num % radix];
			num /= radix;
		} while (num > 0 && i > 0);

		if (isNeg)
			buffer[--i] = '-';

		// Package constructor avoids an array copy.
		return new String(buffer, i, 33 - i);
	}
}
