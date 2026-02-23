package jvm.math;

import jvm.TestCase;

public class IntArithmetic extends TestCase {

	public String toString() {
		return "IntArithmetic";
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
		ok = ok && incTest();
		return ok;
	}

	private boolean addCheck(int a, int b, int r) {
		int help = a + b;
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
		ok = ok && addCheck(0x7fffFFFF, -1, 0x7fffFFFE);
		ok = ok && addCheck(0x7fffFFFF, 1, -2147483648);
		ok = ok && addCheck(-2147483648, -1, 2147483647);
		return ok;
	}

	private boolean subCheck(int a, int b, int r) {
		int help = a - b;
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
		ok = ok && subCheck(0x7fffFFFF, -1, -2147483648);
		ok = ok && subCheck(0x7fffFFFF, 1, 2147483646);
		ok = ok && subCheck(-2147483648, -1, -2147483647);
		return ok;
	}

	private boolean mulCheck(int a, int b, int r) {
		int help = a * b;
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
		ok = ok && mulCheck(0x40000000, 2, 0x80000000);
		ok = ok && mulCheck(0x80000001, 2, 0x00000002);
		return ok;
	}

	private boolean divCheck(int a, int b, int r) {
		int help = a / b;
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
		ok = ok && divCheck(0x80000000, 2, 0xC0000000);
		ok = ok && divCheck(0x80000002, 2, 0xC0000001);
		ok = ok && divCheck(0x80000001, 2, 0xC0000001);
		ok = ok && !divCheck(0x80000001, 2, 0xC0000000);
		return ok;
	}

	private boolean andCheck(int a, int b, int r) {
		int help = a & b;
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
		return ok;
	}

	private boolean orCheck(int a, int b, int r) {
		int help = a | b;
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
		return ok;
	}

	private boolean xorCheck(int a, int b, int r) {
		int help = a ^ b;
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
		return ok;
	}

	private boolean incTest() {
		boolean ok;
		int x = 0;
		ok = x == 0;
		// iinc
		x++;
		ok = ok && x == 1;
		x += 5;
		ok = ok && x == 6;
		x += 120;
		ok = ok && x == 126;
		// larger increments via iadd (JOP toolchain doesn't support wide iinc)
		x = x + 1004;
		ok = ok && x == 1130;
		x = x + 32767;
		ok = ok && x == 33897;
		x = x + (-32768);
		ok = ok && x == 1129;
		return ok;
	}
}
