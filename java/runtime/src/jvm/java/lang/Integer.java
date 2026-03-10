package java.lang;

public class Integer extends Number implements Comparable<Integer> {

	private final int value;

	public Integer(int value) {
		this.value = value;
	}

	public Integer(String s) {
		this.value = parseInt(s, 10);
	}

	public int intValue() {
		return value;
	}

	public long longValue() {
		return (long) value;
	}

	public float floatValue() {
		return (float) value;
	}

	public double doubleValue() {
		return (double) value;
	}

	public static Integer valueOf(int i) {
		return new Integer(i);
	}

	public static Integer valueOf(String s) {
		return valueOf(parseInt(s, 10));
	}

	public static Integer valueOf(String s, int radix) {
		return valueOf(parseInt(s, radix));
	}

	public int compareTo(Integer anotherInteger) {
		int thisVal = this.value;
		int anotherVal = anotherInteger.value;
		return (thisVal < anotherVal ? -1 : (thisVal == anotherVal ? 0 : 1));
	}

	public boolean equals(Object obj) {
		if (obj instanceof Integer) {
			return value == ((Integer) obj).intValue();
		}
		return false;
	}

	public int hashCode() {
		return value;
	}

	public String toString() {
		return toString(value, 10);
	}

	public static String toString(int i) {
		return toString(i, 10);
	}

	public static String toHexString(int i) {
		return toUnsignedString(i, 4);
	}

	public static String toOctalString(int i) {
		return toUnsignedString(i, 3);
	}

	public static String toBinaryString(int i) {
		return toUnsignedString(i, 1);
	}

	private static String toUnsignedString(int num, int shift) {
		char[] buf = new char[32];
		int pos = 32;
		int mask = (1 << shift) - 1;
		do {
			buf[--pos] = digits[num & mask];
			num >>>= shift;
		} while (num != 0);
		return new String(buf, pos, 32 - pos);
	}

	public static int parseInt(String s) {
		return parseInt(s, 10);
	}

	public static int parseInt(String s, int radix) {
		if (s == null) {
			throw new NumberFormatException("null");
		}
		if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
			throw new NumberFormatException("radix " + radix);
		}
		int result = 0;
		boolean negative = false;
		int i = 0;
		int len = s.length();
		int limit = -MAX_VALUE;

		if (len == 0) {
			throw new NumberFormatException(s);
		}
		char firstChar = s.charAt(0);
		if (firstChar < '0') {
			if (firstChar == '-') {
				negative = true;
				limit = MIN_VALUE;
			} else if (firstChar != '+') {
				throw new NumberFormatException(s);
			}
			if (len == 1) {
				throw new NumberFormatException(s);
			}
			i++;
		}
		int multmin = limit / radix;
		while (i < len) {
			int digit = Character.digit(s.charAt(i++), radix);
			if (digit < 0) {
				throw new NumberFormatException(s);
			}
			if (result < multmin) {
				throw new NumberFormatException(s);
			}
			result *= radix;
			if (result < limit + digit) {
				throw new NumberFormatException(s);
			}
			result -= digit;
		}
		return negative ? result : -result;
	}

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
