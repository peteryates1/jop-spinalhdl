package com.jopdesign.net;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.Native;

/**
 * Numeric utility functions for TCP sequence number arithmetic.
 *
 * TCP sequence numbers are unsigned 32-bit values that wrap around.
 * These functions handle the wraparound correctly.
 */
public class NumFunctions {

	/**
	 * Check if sequence number a is before b (unsigned 32-bit comparison
	 * with wraparound). Uses the standard approach: (a - b) is negative
	 * in signed arithmetic when a is "before" b in the circular space.
	 */
	public static boolean seqBefore(int a, int b) {
		return (a - b) < 0;
	}

	/**
	 * Check if a <= b in sequence number space.
	 */
	public static boolean seqBeforeOrEqual(int a, int b) {
		return a == b || (a - b) < 0;
	}

	/**
	 * Check if a > b in sequence number space.
	 */
	public static boolean seqAfter(int a, int b) {
		return (b - a) < 0;
	}

	/**
	 * Check if a >= b in sequence number space.
	 */
	public static boolean seqAfterOrEqual(int a, int b) {
		return a == b || (b - a) < 0;
	}

	/**
	 * Check if testVal is between smallerVal and biggerVal with overflow.
	 * Used for timeout checks with millisecond timestamps.
	 */
	public static boolean isBetween(int smallerVal, int biggerVal, int testVal) {
		if (biggerVal < smallerVal) {
			// Wrapped around
			return (testVal > smallerVal) || (testVal < biggerVal);
		}
		return (testVal > smallerVal) && (testVal < biggerVal);
	}

	/**
	 * Check if testVal is between or equal to biggerVal.
	 */
	public static boolean isBetweenOrEqualBigger(int smallerVal, int biggerVal, int testVal) {
		if (testVal == biggerVal) return true;
		if (biggerVal < smallerVal) {
			return (testVal > smallerVal) || (testVal <= biggerVal);
		}
		return (testVal > smallerVal) && (testVal <= biggerVal);
	}

	/**
	 * Check if testVal is between or equal to smallerVal.
	 */
	public static boolean isBetweenOrEqualSmaller(int smallerVal, int biggerVal, int testVal) {
		if (testVal == smallerVal) return true;
		if (biggerVal < smallerVal) {
			return (testVal >= smallerVal) || (testVal < biggerVal);
		}
		return (testVal >= smallerVal) && (testVal < biggerVal);
	}

	/**
	 * Get the current time in milliseconds (from hardware microsecond counter).
	 * JOP IO_US_CNT returns microseconds as a 32-bit value.
	 * We divide by 1000 for milliseconds (wraps every ~49 days).
	 */
	public static int now() {
		return Native.rd(Const.IO_US_CNT) / 1000;
	}
}
