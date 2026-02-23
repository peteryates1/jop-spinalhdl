package jvm.obj;

import jvm.TestCase;

public class CharField extends TestCase {

	public char iField;
	private static char sField;

	public String toString() {
		return "CharField";
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && testInstance();
		ok = ok && testStatic();
		return ok;
	}

	private boolean testInstance() {
		boolean ok;

		iField = 0;
		someDummy();
		ok = iField == 0;

		iField = 1;
		someDummy();
		ok = ok && (iField == 1);

		iField = 0x7FFF;
		someDummy();
		ok = ok && (iField == 32767);

		iField++;
		someDummy();
		ok = ok && (iField == 32768);

		iField = 0xFFFF;
		someDummy();
		ok = ok && (iField == 65535);

		iField++;
		someDummy();
		ok = ok && (iField == 0);

		return ok;
	}

	private boolean testStatic() {
		boolean ok;

		sField = 0;
		someDummy();
		ok = sField == 0;

		sField = 1;
		someDummy();
		ok = ok && (sField == 1);

		sField = 0x7FFF;
		someDummy();
		ok = ok && (sField == 32767);

		sField++;
		someDummy();
		ok = ok && (sField == 32768);

		sField = 0xFFFF;
		someDummy();
		ok = ok && (sField == 65535);

		sField++;
		someDummy();
		ok = ok && (sField == 0);

		return ok;
	}
}
