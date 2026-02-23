package jvm.obj;

import jvm.TestCase;

public class ByteField extends TestCase {

	public byte iField;
	private static byte sField;

	public String toString() {
		return "ByteField";
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

		iField = 0x7F;
		someDummy();
		ok = ok && (iField == 127);

		iField++;
		someDummy();
		ok = ok && (iField == -128);

		iField = (byte) 0xFF;
		someDummy();
		ok = ok && (iField == -1);

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

		sField = 0x7F;
		someDummy();
		ok = ok && (sField == 127);

		sField++;
		someDummy();
		ok = ok && (sField == -128);

		sField = (byte) 0xFF;
		someDummy();
		ok = ok && (sField == -1);

		sField++;
		someDummy();
		ok = ok && (sField == 0);

		return ok;
	}
}
