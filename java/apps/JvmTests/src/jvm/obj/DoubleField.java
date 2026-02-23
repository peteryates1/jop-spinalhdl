package jvm.obj;

import jvm.TestCase;

public class DoubleField extends TestCase {

	public double iField;
	private static double sField;

	public String toString() {
		return "DoubleField";
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

		iField = (float) -0.5;
		someDummy();
		ok = ok && (iField == -0.5);

		iField++;
		someDummy();
		ok = ok && (iField == 0.5);

		iField = (float) 3.141592;
		someDummy();
		ok = ok && (3.141591 <= iField && iField <= 3.141593);

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

		sField = (float) -0.5;
		someDummy();
		ok = ok && (sField == -0.5);

		sField++;
		someDummy();
		ok = ok && (sField == 0.5);

		sField = (float) 3.141592;
		someDummy();
		ok = ok && (3.141591 <= sField && sField <= 3.141593);

		return ok;
	}
}
