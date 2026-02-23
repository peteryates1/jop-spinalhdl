package jvm.math;

import jvm.TestCase;

public class ConstLoad extends TestCase {

	public String toString() {
		return "ConstLoad";
	}

	private boolean ism1(int v) { return v == -1; }
	private boolean is0(int v) { return v == 0; }
	private boolean is1(int v) { return v == 1; }
	private boolean is6(int v) { return v == 6; }
	private boolean ism6(int v) { return v == -6; }
	private boolean is127(int v) { return v == 127; }
	private boolean ism127(int v) { return v == -127; }
	private boolean is128(int v) { return v == 128; }
	private boolean ism128(int v) { return v == -128; }
	private boolean is255(int v) { return v == 255; }
	private boolean ism255(int v) { return v == -255; }
	private boolean is32767(int v) { return v == 32767; }
	private boolean ism32767(int v) { return v == -32767; }
	private boolean is32768(int v) { return v == 32768; }
	private boolean ism32768(int v) { return v == -32768; }

	public boolean test() {
		boolean ok = true;

		// iconst
		ok = ok && is0(0);
		ok = ok && is0(-0);
		ok = ok && !is0(1);
		ok = ok && is1(1);
		ok = ok && !is1(-1);
		ok = ok && ism1(-1);
		ok = ok && !ism1(1);

		// bipush
		ok = ok && is6(6);
		ok = ok && !is6(-6);
		ok = ok && ism6(-6);
		ok = ok && !ism6(6);
		ok = ok && is127(127);
		ok = ok && !is127(-127);
		ok = ok && ism127(-127);
		ok = ok && !ism127(127);

		// sipush (at bipush boundary)
		ok = ok && is128(128);
		ok = ok && !is128(-128);
		ok = ok && ism128(-128);
		ok = ok && !ism128(128);

		// sipush
		ok = ok && is255(255);
		ok = ok && !is255(-255);
		ok = ok && ism255(-255);
		ok = ok && !ism255(255);
		ok = ok && is32767(32767);
		ok = ok && !is32767(-32767);
		ok = ok && ism32767(-32767);
		ok = ok && !ism32767(32767);

		// ldc (at sipush boundary)
		ok = ok && is32768(32768);
		ok = ok && !is32768(-32768);
		ok = ok && ism32768(-32768);
		ok = ok && !ism32768(32768);

		return ok;
	}
}
