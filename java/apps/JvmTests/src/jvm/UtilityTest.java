package jvm;

import java.util.Random;

/**
 * Test Phase 6 utility classes: Random, Enum, Long wrapper.
 */
public class UtilityTest extends TestCase {

	public String toString() {
		return "UtilityTest";
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && randomBasic();
		ok = ok && randomRange();
		ok = ok && longWrapper();
		ok = ok && longParsing();
		ok = ok && integerBitOps();
		return ok;
	}

	private boolean randomBasic() {
		// Seeded Random should produce deterministic values
		Random r1 = new Random(12345L);
		Random r2 = new Random(12345L);
		// Same seed must produce same sequence
		for (int i = 0; i < 5; i++) {
			if (r1.nextInt() != r2.nextInt()) return false;
		}
		return true;
	}

	private boolean randomRange() {
		Random r = new Random(42L);
		// nextInt(bound) must be in [0, bound)
		for (int i = 0; i < 20; i++) {
			int val = r.nextInt(100);
			if (val < 0 || val >= 100) return false;
		}
		// nextBoolean should not crash
		r.nextBoolean();
		// nextLong should not crash
		r.nextLong();
		return true;
	}

	private boolean longWrapper() {
		Long a = new Long(42L);
		Long b = new Long(42L);
		Long c = new Long(-1L);
		// longValue
		if (a.longValue() != 42L) return false;
		// intValue
		if (a.intValue() != 42) return false;
		// doubleValue
		// (skip float/double comparison — use int check)
		// equals
		if (!a.equals(b)) return false;
		if (a.equals(c)) return false;
		// hashCode consistency
		if (a.hashCode() != b.hashCode()) return false;
		// valueOf
		Long d = Long.valueOf(100L);
		if (d.longValue() != 100L) return false;
		return true;
	}

	private boolean longParsing() {
		if (Long.parseLong("0") != 0L) return false;
		if (Long.parseLong("123456789") != 123456789L) return false;
		if (Long.parseLong("-1") != -1L) return false;
		if (Long.parseLong("FF", 16) != 255L) return false;
		// Large value
		if (Long.parseLong("9223372036854775807") != Long.MAX_VALUE) return false;
		return true;
	}

	private boolean integerBitOps() {
		// numberOfLeadingZeros
		if (Integer.numberOfLeadingZeros(1) != 31) return false;
		if (Integer.numberOfLeadingZeros(0) != 32) return false;
		if (Integer.numberOfLeadingZeros(-1) != 0) return false;
		if (Integer.numberOfLeadingZeros(256) != 23) return false;
		// numberOfTrailingZeros
		if (Integer.numberOfTrailingZeros(1) != 0) return false;
		if (Integer.numberOfTrailingZeros(8) != 3) return false;
		if (Integer.numberOfTrailingZeros(0) != 32) return false;
		// bitCount
		if (Integer.bitCount(0) != 0) return false;
		if (Integer.bitCount(1) != 1) return false;
		if (Integer.bitCount(0xFF) != 8) return false;
		if (Integer.bitCount(-1) != 32) return false;
		// highestOneBit
		if (Integer.highestOneBit(0) != 0) return false;
		if (Integer.highestOneBit(255) != 128) return false;
		if (Integer.highestOneBit(1) != 1) return false;
		// signum
		if (Integer.signum(42) != 1) return false;
		if (Integer.signum(0) != 0) return false;
		if (Integer.signum(-7) != -1) return false;
		return true;
	}
}
