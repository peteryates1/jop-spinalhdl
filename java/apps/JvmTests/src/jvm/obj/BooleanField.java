package jvm.obj;

import jvm.TestCase;

public class BooleanField extends TestCase {

	public boolean iField;
	private static boolean sField;

	public String toString() {
		return "BooleanField";
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && testInstance();
		ok = ok && testStatic();
		return ok;
	}

	private boolean testInstance() {
		boolean ok;

		iField = false;
		someDummy();
		ok = iField == false;

		iField = true;
		someDummy();
		ok = ok && (iField == true);

		return ok;
	}

	private boolean testStatic() {
		boolean ok;

		sField = false;
		someDummy();
		ok = sField == false;

		sField = true;
		someDummy();
		ok = ok && (sField == true);

		return ok;
	}
}
