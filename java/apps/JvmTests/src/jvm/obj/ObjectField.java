package jvm.obj;

import jvm.TestCase;

public class ObjectField extends TestCase {

	public Object iField;
	private static Object sField;

	public String toString() {
		return "ObjectField";
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && testInstance();
		ok = ok && testStatic();
		return ok;
	}

	private boolean testInstance() {
		boolean ok;
		Object testObject = new Object();

		iField = null;
		someDummy();
		ok = iField == null;

		iField = testObject;
		someDummy();
		ok = ok && (iField == testObject);

		iField = null;
		someDummy();
		ok = ok && (iField == null);

		return ok;
	}

	private boolean testStatic() {
		boolean ok;
		Object testObject = new Object();

		sField = null;
		someDummy();
		ok = sField == null;

		sField = testObject;
		someDummy();
		ok = ok && (sField == testObject);

		sField = null;
		someDummy();
		ok = ok && (sField == null);

		return ok;
	}
}
