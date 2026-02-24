package jvm.math;

import jvm.TestCase;

/**
 * Test getstatic_long / putstatic_long microcode paths.
 * These use a different microcode path than getfield_long/putfield_long
 * (jvm_long.inc: f_getstatic_long / f_putstatic_long).
 * LongField.java tests instance long fields + a few statics with small values.
 * This test focuses specifically on static long field operations with
 * various bit patterns to exercise the microcode thoroughly.
 */
public class LongStaticField extends TestCase {

	static long sval1;
	static long sval2;
	static long sval3;

	public String toString() {
		return "LongStaticField";
	}

	public boolean test() {

		boolean ok = true;

		// Basic store and load
		long v1 = 0x123456789ABCDEF0L;
		sval1 = v1;
		ok = ok && (sval1 == v1);

		// Different bit patterns - high word non-zero
		long v2 = 0xCAFEBABE12345678L;
		sval2 = v2;
		ok = ok && (sval2 == v2);

		// Zero
		long v3 = 0L;
		sval3 = v3;
		ok = ok && (sval3 == v3);

		// All bits set
		long vAll = 0xFFFFFFFFFFFFFFFFL;
		sval1 = vAll;
		ok = ok && (sval1 == vAll);

		// Verify other statics were not clobbered
		ok = ok && (sval2 == v2);
		ok = ok && (sval3 == v3);

		// High word only
		long vHigh = 0xDEADBEEF00000000L;
		sval2 = vHigh;
		ok = ok && (sval2 == vHigh);

		// Low word only
		long vLow = 0x00000000DEADBEEFL;
		sval3 = vLow;
		ok = ok && (sval3 == vLow);

		// Arithmetic on static long (putstatic_long after getstatic_long)
		sval1 = 100L;
		sval2 = 200L;
		long sum = sval1 + sval2;
		ok = ok && (sum == 300L);

		sval3 = sval1 + sval2;
		ok = ok && (sval3 == 300L);

		// Cross-word carry: low word overflow into high word
		sval1 = 0x00000000FFFFFFFFL;
		sval1 = sval1 + 1L;
		ok = ok && (sval1 == 0x0000000100000000L);

		// Negative values
		long neg = -1L;
		sval1 = neg;
		ok = ok && (sval1 == -1L);
		ok = ok && (sval1 == 0xFFFFFFFFFFFFFFFFL);

		long negBig = -1000000000000L;
		sval2 = negBig;
		ok = ok && (sval2 == negBig);

		return ok;
	}
}
