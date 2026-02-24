/*
  This file is part of JOP, the Java Optimized Processor
    see <http://www.jopdesign.com/>

  Copyright (C) 2001-2008, Martin Schoeberl (martin@jopdesign.com)

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package jvm;

/**
 * Test null pointer exception detection for various bytecodes.
 *
 * Tests hardware NPE detection (getfield/putfield handle dereference
 * checks addrReg === 0 in BmbMemoryController) and microcode NPE
 * detection (invokevirtual/invokespecial check objectref before dispatch).
 *
 * @author Martin Schoeberl (martin@jopdesign.com)
 *
 */
public class NullPointer extends TestCase implements NullTestIface {

	public String toString() {
		return "NullPointer";
	}

	int ival;
	long lval;
	NullPointer rval;

	/**
	 * an invokevirtual
	 */
	public void foo() {

	}

	/**
	 * an invokespecial (private method call)
	 */
	private void bar() {

	}

	/**
	 * invokeinterface implementation
	 */
	public void ifaceCall() {

	}

	public boolean test() {

		boolean ok = true;

		// Test 0: explicit throw NPE (verifies exception table + type matching)
		boolean caught = false;
		try {
			throw new NullPointerException();
		} catch (NullPointerException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T0");
		ok &= caught;

		// Test 1: invokevirtual on null (microcode NPE path)
		NullPointer nullObj = null;
		caught = false;
		try {
			nullObj.foo();
		} catch (NullPointerException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T1");
		ok &= caught;

		// Test 2: getfield int on null (hardware NPE from memory controller)
		int i;
		caught = false;
		try {
			i = nullObj.ival;
		} catch (NullPointerException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T2");
		ok &= caught;

		// Test 3: invokespecial on null (private method call)
		caught = false;
		try {
			nullObj.bar();
		} catch (NullPointerException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T3");
		ok &= caught;

		// Test 4: putfield int on null (hardware NPE from memory controller)
		caught = false;
		try {
			nullObj.ival = 42;
		} catch (NullPointerException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T4");
		ok &= caught;

		// Test 5: getfield long on null (hardware NPE, long field)
		long l;
		caught = false;
		try {
			l = nullObj.lval;
		} catch (NullPointerException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T5");
		ok &= caught;

		// Test 6: putfield long on null (hardware NPE, long field)
		caught = false;
		try {
			nullObj.lval = 123L;
		} catch (NullPointerException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T6");
		ok &= caught;

		// Test 7: getfield ref on null (hardware NPE, reference field)
		NullPointer r;
		caught = false;
		try {
			r = nullObj.rval;
		} catch (NullPointerException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T7");
		ok &= caught;

		// Test 8: putfield ref on null (hardware NPE, reference field)
		caught = false;
		try {
			nullObj.rval = null;
		} catch (NullPointerException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T8");
		ok &= caught;

		// Test 9: invokeinterface on null (microcode NPE path)
		NullTestIface nullIface = null;
		caught = false;
		try {
			nullIface.ifaceCall();
		} catch (NullPointerException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T9");
		ok &= caught;

		// Test 10: iaload on null int array (hardware NPE)
		int[] nullArr = null;
		caught = false;
		try {
			i = nullArr[0];
		} catch (NullPointerException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T10");
		ok &= caught;

		// Test 11: iastore on null int array (hardware NPE)
		caught = false;
		try {
			nullArr[0] = 42;
		} catch (NullPointerException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T11");
		ok &= caught;

		// Test 12: aaload on null Object array (hardware NPE)
		Object[] nullObjArr = null;
		caught = false;
		try {
			Object x = nullObjArr[0];
		} catch (NullPointerException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T12");
		ok &= caught;

		return ok;
	}
}
