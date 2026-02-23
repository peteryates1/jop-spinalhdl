package jvm.math;

import jvm.TestCase;

public class LongArithmetic extends TestCase {

	public String toString() {
		return "LongArithmetic";
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && addTest();
		ok = ok && subTest();
		ok = ok && mulTest();
		ok = ok && divTest();
		ok = ok && andTest();
		ok = ok && orTest();
		ok = ok && xorTest();
		return ok;
	}

	private boolean addCheck(long a, long b, long r) {
		long help = a + b;
		return (help == r) && (a + b == r);
	}

	private boolean addTest() {
		boolean ok = addCheck(0, 0, 0);
		ok = ok && addCheck(0, -1, -1);
		ok = ok && addCheck(-1, 0, -1);
		ok = ok && addCheck(-1, -1, -2);
		ok = ok && addCheck(0, 1, 1);
		ok = ok && addCheck(1, 0, 1);
		ok = ok && addCheck(1, 1, 2);
		ok = ok && addCheck(736, 2784, 3520);
		ok = ok && addCheck(-255, +254, -1);
		ok = ok && addCheck(0x7fffFFFFL, -1, 0x7fffFFFEL);
		ok = ok && addCheck(0x7fffFFFFL, 1, 0x80000000L);
		ok = ok && addCheck(-2147483648, -1, -2147483649L);
		ok = ok && addCheck(Long.MAX_VALUE, 1, Long.MIN_VALUE);
		ok = ok && addCheck(Long.MIN_VALUE, -1, Long.MAX_VALUE);
		ok = ok && addCheck(0, Long.MAX_VALUE, Long.MAX_VALUE);
		ok = ok && addCheck(0, Long.MIN_VALUE, Long.MIN_VALUE);
		return ok;
	}

	private boolean subCheck(long a, long b, long r) {
		long help = a - b;
		return (help == r) && (a - b == r);
	}

	private boolean subTest() {
		boolean ok = subCheck(0, 0, 0);
		ok = ok && subCheck(0, -1, 1);
		ok = ok && subCheck(-1, 0, -1);
		ok = ok && subCheck(-1, -1, 0);
		ok = ok && subCheck(0, 1, -1);
		ok = ok && subCheck(1, 0, 1);
		ok = ok && subCheck(1, 1, 0);
		ok = ok && subCheck(3520, 2784, 736);
		ok = ok && subCheck(-255, -254, -1);
		ok = ok && subCheck(0x7fffFFFF, -1, 2147483648L);
		ok = ok && subCheck(0x7fffFFFF, 1, 2147483646);
		ok = ok && subCheck(0x80000000L, -1, 0x80000001L);
		ok = ok && subCheck(Long.MAX_VALUE, -1, Long.MIN_VALUE);
		ok = ok && subCheck(Long.MIN_VALUE, 1, Long.MAX_VALUE);
		ok = ok && subCheck(0, Long.MAX_VALUE, -Long.MAX_VALUE);
		ok = ok && subCheck(0, Long.MIN_VALUE, Long.MIN_VALUE);
		return ok;
	}

	private boolean mulCheck(long a, long b, long r) {
		long help = a * b;
		return (help == r) && (a * b == r);
	}

	private boolean mulTest() {
		boolean ok = mulCheck(0, 0, 0);
		ok = ok && mulCheck(0, -1, 0);
		ok = ok && mulCheck(-1, 0, 0);
		ok = ok && mulCheck(-1, -1, 1);
		ok = ok && mulCheck(0, 1, 0);
		ok = ok && mulCheck(1, 0, 0);
		ok = ok && mulCheck(1, 1, 1);
		ok = ok && mulCheck(-1, 1, -1);
		ok = ok && mulCheck(1, -1, -1);
		ok = ok && mulCheck(736, 2784, 2049024);
		ok = ok && mulCheck(-255, 254, -64770);
		ok = ok && mulCheck(255, -254, -64770);
		ok = ok && mulCheck(255, 254, 64770);
		ok = ok && mulCheck(0x40000000, 2, 0x80000000L);
		ok = ok && mulCheck(0x80000001L, 2, 0x100000002L);
		return ok;
	}

	private boolean divCheck(long a, long b, long r) {
		long help = a / b;
		return (help == r) && (a / b == r);
	}

	private boolean divTest() {
		boolean ok;

		// div-by-zero exception tests omitted (tested in DivZero.java when exceptions enabled)
		ok = divCheck(0, 1, 0);
		ok = ok && divCheck(0, 2, 0);
		ok = ok && divCheck(0, 100, 0);
		ok = ok && divCheck(5, 2, 2);
		ok = ok && divCheck(-5, 2, -2);
		ok = ok && divCheck(5, -2, -2);
		ok = ok && divCheck(-5, -2, 2);
		ok = ok && divCheck(6, 2, 3);
		ok = ok && divCheck(6, -2, -3);
		ok = ok && divCheck(-6, 2, -3);
		ok = ok && divCheck(-6, -2, 3);
		ok = ok && divCheck(2, 6, 0);
		ok = ok && divCheck(-2, 6, 0);
		ok = ok && divCheck(2, -6, 0);
		ok = ok && divCheck(-2, -6, 0);
		ok = ok && divCheck(2049024, 736, 2784);
		ok = ok && divCheck(0x80000000L, 2, 0x40000000);
		ok = ok && divCheck(0x80000002L, 2, 0x40000001);
		ok = ok && divCheck(0x80000001L, 2, 0x40000000);
		ok = ok && divCheck(Long.MIN_VALUE + 2, 2, 0xC000000000000001L);
		ok = ok && divCheck(Long.MIN_VALUE + 1, 2, 0xC000000000000001L);
		ok = ok && !divCheck(Long.MIN_VALUE + 1, 2, 0xC000000000000000L);
		return ok;
	}

	private boolean andCheck(long a, long b, long r) {
		long help = a & b;
		return (help == r) && ((a & b) == r);
	}

	private boolean andTest() {
		boolean ok = andCheck(0, 1, 0);
		ok = ok && andCheck(0, 2, 0);
		ok = ok && andCheck(0, 100, 0);
		ok = ok && andCheck(1, 1, 1);
		ok = ok && andCheck(2, 1, 0);
		ok = ok && andCheck(1, 2, 0);
		ok = ok && andCheck(0xAAAAaaaa, 0x55555555, 0);
		ok = ok && andCheck(0x55555555, 0xAAAAaaaa, 0);
		ok = ok && andCheck(0xAAAAaaaa, 0x5555aaaa, 0x0000aaaa);
		ok = ok && andCheck(0xAAAAaaaa, 0x5555ffff, 0x0000aaaa);
		ok = ok && andCheck(0x5555ffff, 0xAAAAaaaa, 0x0000aaaa);
		ok = ok && andCheck(0xAAAAaaaa, 0xFFFFffff, 0xAAAAaaaa);
		ok = ok && andCheck(0xFFFFffff, 0xAAAAaaaa, 0xAAAAaaaa);
		ok = ok && andCheck(0x55555555AAAAaaaaL, 0xAAAAaaaa55555555L, 0);
		ok = ok && andCheck(0xAAAAaaaa55555555L, 0x55555555AAAAaaaaL, 0);
		ok = ok && andCheck(0x55555555FFFFffffL, 0xAAAAaaaa55555555L, 0x55555555L);
		ok = ok && andCheck(0xAAAAaaaaFFFFffffL, 0x55555555AAAAaaaaL, 0xAAAAaaaaL);
		ok = ok && andCheck(0xFFFFffff55555555L, 0xAAAAaaaaAAAAaaaaL, 0xAAAAaaaa00000000L);
		ok = ok && andCheck(0xFFFFffffAAAAaaaaL, 0x55555555AAAAaaaaL, 0x55555555AAAAaaaaL);
		return ok;
	}

	private boolean orCheck(long a, long b, long r) {
		long help = a | b;
		return (help == r) && ((a | b) == r);
	}

	private boolean orTest() {
		boolean ok = orCheck(0, 1, 1);
		ok = orCheck(1, 0, 1);
		ok = orCheck(-1, 0, -1);
		ok = ok && orCheck(0, 2, 2);
		ok = ok && orCheck(0, 100, 100);
		ok = ok && orCheck(1, 1, 1);
		ok = ok && orCheck(2, 1, 3);
		ok = ok && orCheck(1, 2, 3);
		ok = ok && orCheck(0xAAAAaaaa, 0x55555555, 0xFFFFffff);
		ok = ok && orCheck(0x55555555, 0xAAAAaaaa, 0xFFFFffff);
		ok = ok && orCheck(0xAAAAaaaa, 0x5555aaaa, 0xFFFFaaaa);
		ok = ok && orCheck(0xAAAAaaaa, 0x5555ffff, 0xFFFFffff);
		ok = ok && orCheck(0x5555ffff, 0xAAAAaaaa, 0xFFFFffff);
		ok = ok && orCheck(0xAAAAaaaa, 0xFFFFffff, 0xFFFFffff);
		ok = ok && orCheck(0xFFFFffff, 0xAAAAaaaa, 0xFFFFffff);
		ok = ok && orCheck(0xAAAAaaaa, 0xAAAA0000, 0xAAAAaaaa);
		ok = ok && orCheck(0x5555aaaa, 0xAAAA0000, 0xFFFFaaaa);
		ok = ok && orCheck(0x55555555AAAAaaaaL, 0xAAAAaaaa55555555L, 0xFFFFffffFFFFffffL);
		ok = ok && orCheck(0xAAAAaaaa55555555L, 0x55555555AAAAaaaaL, 0xFFFFffffFFFFffffL);
		ok = ok && orCheck(0x55555555FFFFffffL, 0xAAAAaaaa55555555L, 0xFFFFffffFFFFffffL);
		ok = ok && orCheck(0xAAAAaaaaFFFFffffL, 0x55555555AAAAaaaaL, 0xFFFFffffFFFFffffL);
		ok = ok && orCheck(0xFFFFffff55555555L, 0xAAAAaaaaAAAAaaaaL, 0xFFFFffffFFFFffffL);
		ok = ok && orCheck(0xFFFFffffAAAAaaaaL, 0x55555555AAAAaaaaL, 0xFFFFffffAAAAaaaaL);
		return ok;
	}

	private boolean xorCheck(long a, long b, long r) {
		long help = a ^ b;
		return (help == r) && ((a ^ b) == r);
	}

	private boolean xorTest() {
		boolean ok = xorCheck(0, 1, 1);
		ok = xorCheck(1, 0, 1);
		ok = xorCheck(-1, 0, -1);
		ok = ok && xorCheck(0, 2, 2);
		ok = ok && xorCheck(0, 100, 100);
		ok = ok && xorCheck(1, 1, 0);
		ok = ok && xorCheck(2, 1, 3);
		ok = ok && xorCheck(1, 2, 3);
		ok = ok && xorCheck(0xAAAAaaaa, 0x55555555, 0xFFFFffff);
		ok = ok && xorCheck(0x55555555, 0xAAAAaaaa, 0xFFFFffff);
		ok = ok && xorCheck(0xAAAAaaaa, 0x5555aaaa, 0xFFFF0000);
		ok = ok && xorCheck(0xAAAAaaaa, 0x5555ffff, 0xFFFF5555);
		ok = ok && xorCheck(0x5555ffff, 0xAAAAaaaa, 0xFFFF5555);
		ok = ok && xorCheck(0xAAAAaaaa, 0xFFFFffff, 0x55555555);
		ok = ok && xorCheck(0xFFFFffff, 0xAAAAaaaa, 0x55555555);
		ok = ok && xorCheck(0xAAAAaaaa, 0xAAAA0000, 0x0000aaaa);
		ok = ok && xorCheck(0x5555aaaa, 0xAAAA0000, 0xFFFFaaaa);
		ok = ok && xorCheck(0x55555555AAAAaaaaL, 0xAAAAaaaa55555555L, 0xFFFFffffFFFFffffL);
		ok = ok && xorCheck(0xAAAAaaaa55555555L, 0x55555555AAAAaaaaL, 0xFFFFffffFFFFffffL);
		ok = ok && xorCheck(0x55555555FFFFffffL, 0xAAAAaaaa55555555L, 0xFFFFffffAAAAaaaaL);
		ok = ok && xorCheck(0xAAAAaaaaFFFFffffL, 0x55555555AAAAaaaaL, 0xFFFFffff55555555L);
		ok = ok && xorCheck(0xFFFFffff55555555L, 0xAAAAaaaaAAAAaaaaL, 0x55555555FFFFffffL);
		ok = ok && xorCheck(0xFFFFffffAAAAaaaaL, 0x55555555AAAAaaaaL, 0xAAAAaaaa00000000L);
		return ok;
	}
}
