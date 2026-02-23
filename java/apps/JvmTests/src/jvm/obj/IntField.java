package jvm.obj;

import jvm.TestCase;

public class IntField extends TestCase {

	public int iField;
	private static int sField;

	public String toString() {
		return "IntField";
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

		iField = -1;
		someDummy();
		ok = ok && (iField == -1);

		iField = 0x7FFFFFFF;
		someDummy();
		ok = ok && (iField == 0x7FFFFFFF);

		iField++;
		someDummy();
		ok = ok && (iField == 0x80000000);

		iField = 0xFFFFFFFF;
		someDummy();
		ok = ok && (iField == 0xFFFFFFFF);

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

		sField = -1;
		someDummy();
		ok = ok && (sField == -1);

		sField = 0x7FFFFFFF;
		someDummy();
		ok = ok && (sField == 0x7FFFFFFF);

		sField++;
		someDummy();
		ok = ok && (sField == 0x80000000);

		sField = 0xFFFFFFFF;
		someDummy();
		ok = ok && (sField == 0xFFFFFFFF);

		sField++;
		someDummy();
		ok = ok && (sField == 0);

		return ok;
	}
}
