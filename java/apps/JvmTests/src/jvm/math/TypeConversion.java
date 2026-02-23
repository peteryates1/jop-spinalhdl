package jvm.math;

import jvm.TestCase;

public class TypeConversion extends TestCase {

	public String toString() {
		return "TypeConversion";
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && i2x();
		ok = ok && l2x();
		ok = ok && f2x();
		ok = ok && d2x();
		return ok;
	}

	private boolean i2x() {
		boolean res;
		int a;
		byte b;
		long l;
		short s;
		char c;
		float f;
		double d;

		a = 0;
		b = (byte) a; s = (short) a; c = (char) a; l = a; f = a; d = a;
		res = a == 0 && b == 0 && s == 0 && c == 0 && l == 0L && f == 0.0f && d == 0.0;

		a = 1;
		b = (byte) a; s = (short) a; c = (char) a; l = a; f = a; d = a;
		res = res && a == 1 && b == 1 && s == 1 && c == 1 && l == 1L && f == 1.0f && d == 1.0;

		a = 2;
		b = (byte) a; s = (short) a; c = (char) a; l = a; f = a; d = a;
		res = res && a == 2 && b == 2 && s == 2 && c == 2 && l == 2L && f == 2.0f && d == 2.0;

		a = -1;
		b = (byte) a; s = (short) a; c = (char) a; l = a; f = a; d = a;
		res = res && a == -1 && b == -1 && s == -1 && c == 65535 && l == -1L && f == -1.0f && d == -1.0;

		a = Integer.MAX_VALUE;
		b = (byte) a; s = (short) a; c = (char) a; l = a; f = a; d = a;
		res = res && a == Integer.MAX_VALUE && b == -1 && s == -1 && c == 65535 && l == Integer.MAX_VALUE;

		return res;
	}

	private boolean l2x() {
		boolean res;
		long a;
		byte b;
		short s;
		char c;
		int i;
		float f;
		double d;

		a = 0;
		b = (byte) a; s = (short) a; c = (char) a; i = (int) a; f = a; d = a;
		res = a == 0 && b == 0 && s == 0 && c == 0 && i == 0 && f == 0.0f && d == 0.0;

		a = 1;
		b = (byte) a; s = (short) a; c = (char) a; i = (int) a; f = a; d = a;
		res = res && a == 1 && b == 1 && s == 1 && c == 1 && i == 1 && f == 1.0f && d == 1.0;

		a = 2;
		b = (byte) a; s = (short) a; c = (char) a; i = (int) a; f = a; d = a;
		res = res && a == 2 && b == 2 && s == 2 && c == 2 && i == 2 && f == 2.0f && d == 2.0;

		a = -1;
		b = (byte) a; s = (short) a; c = (char) a; i = (int) a; f = a; d = a;
		res = res && a == -1 && b == -1 && s == -1 && c == 65535 && i == -1 && f == -1.0f && d == -1.0;

		a = Integer.MAX_VALUE;
		b = (byte) a; s = (short) a; c = (char) a; i = (int) a; f = a; d = a;
		res = res && a == Integer.MAX_VALUE && b == -1 && s == -1 && c == 65535 && i == Integer.MAX_VALUE;

		a = Long.MAX_VALUE;
		b = (byte) a; s = (short) a; c = (char) a; i = (int) a; f = a; d = a;
		res = res && a == Long.MAX_VALUE && b == -1 && s == -1 && c == 65535 && i == -1;

		return res;
	}

	private boolean f2x() {
		boolean res;
		float f;
		int i;
		long l;
		double d;

		f = 0.0f;
		i = (int) f; l = (long) f; d = f;
		res = f == 0.0 && i == 0 && l == 0 && d == 0.0;

		f = 1.0f;
		i = (int) f; l = (long) f; d = f;
		res = res && f == 1.0 && i == 1 && l == 1 && d == 1.0;

		f = -0.0f;
		i = (int) f; l = (long) f; d = f;
		res = res && f == 0.0 && i == 0 && l == 0 && d == 0.0;

		f = -1.0f;
		i = (int) f; l = (long) f; d = f;
		res = res && f == -1.0 && i == -1 && l == -1 && d == -1.0;

		f = 1.5f;
		i = (int) f; l = (long) f; d = f;
		res = res && f == 1.5 && i == 1 && l == 1 && d == 1.5;

		f = -1.5f;
		i = (int) f; l = (long) f; d = f;
		res = res && f == -1.5 && i == -1 && l == -1 && d == -1.5;

		return res;
	}

	private boolean d2x() {
		boolean res;
		double d;
		float f;
		int i;
		long l;

		d = 0.0;
		i = (int) d; l = (long) d; f = (float) d;
		res = d == 0.0 && i == 0 && l == 0 && f == 0.0;

		d = -0.0;
		i = (int) d; l = (long) d; f = (float) d;
		res = res && d == 0.0 && i == 0 && l == 0 && f == 0.0;

		d = 1.0;
		i = (int) d; l = (long) d; f = (float) d;
		res = res && d == 1.0 && i == 1 && l == 1 && f == 1.0;

		d = -1.0;
		i = (int) d; l = (long) d; f = (float) d;
		res = res && d == -1.0 && i == -1 && l == -1 && f == -1.0;

		d = 1.5;
		i = (int) d; l = (long) d; f = (float) d;
		res = res && d == 1.5 && i == 1 && l == 1 && f == 1.5;

		d = -1.5;
		i = (int) d; l = (long) d; f = (float) d;
		res = res && d == -1.5 && i == -1 && l == -1 && f == -1.5;

		return res;
	}
}
