/*
  This file is part of JOP, the Java Optimized Processor
    see <http://www.jopdesign.com/>

  Copyright (C) 2007, Alberto Andreotti

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

package jvm.math;

import jvm.TestCase;


public class FloatTest extends TestCase {

	public String toString() {
		return "FloatTest";
	}
	
	public boolean test() {

		boolean ok = true;
		
		float f1 = 1.3F;
		float f2 = 2.9F;

		float f3 = f1+f2;

		ok = ok && test_f2i();
		
		int i = (int) f3;
		ok = ok && (i==4);

		f3 = f1-f2;
		i = (int) f3;
		ok = ok && (i==-1);

		f1 = 0F;
		f2 = 1F;
		f3 = 2F;
		
		i = (int) (f1+f2+f3);
		ok = ok && (i==3);

		ok = ok && test_fmul();
		ok = ok && test_fdiv();
		ok = ok && test_fneg();
		ok = ok && test_fcmp();
		ok = ok && test_frem();

		return ok;
	}

	boolean test_fmul() {
		boolean ok = true;
		float a = 2.0F;
		float b = 3.0F;
		ok = ok && ((int)(a * b) == 6);
		a = 0.0F;
		b = 100.0F;
		ok = ok && ((int)(a * b) == 0);
		a = -2.0F;
		b = 3.0F;
		ok = ok && ((int)(a * b) == -6);
		a = 1.5F;
		b = 4.0F;
		ok = ok && ((int)(a * b) == 6);
		a = 10.0F;
		b = 10.0F;
		ok = ok && ((int)(a * b) == 100);
		return ok;
	}

	boolean test_fdiv() {
		boolean ok = true;
		float a = 6.0F;
		float b = 2.0F;
		ok = ok && ((int)(a / b) == 3);
		a = 0.0F;
		b = 1.0F;
		ok = ok && ((int)(a / b) == 0);
		a = -6.0F;
		b = 2.0F;
		ok = ok && ((int)(a / b) == -3);
		a = 100.0F;
		b = 10.0F;
		ok = ok && ((int)(a / b) == 10);
		a = 7.0F;
		b = 2.0F;
		ok = ok && ((int)(a / b) == 3);
		return ok;
	}

	boolean test_fneg() {
		boolean ok = true;
		float f = 1.0F;
		ok = ok && ((int)(-f) == -1);
		f = -5.0F;
		ok = ok && ((int)(-f) == 5);
		f = 0.0F;
		ok = ok && ((int)(-f) == 0);
		return ok;
	}

	boolean test_fcmp() {
		boolean ok = true;
		float a = 1.0F;
		float b = 2.0F;
		float c = 1.0F;

		ok = ok && (a < b);
		ok = ok && !(a > b);
		ok = ok && (a == c);
		ok = ok && !(a != c);
		ok = ok && (b > a);
		ok = ok && !(b < a);

		float neg = -1.0F;
		ok = ok && (neg < a);
		ok = ok && (a > neg);

		return ok;
	}

	boolean test_frem() {
		boolean ok = true;
		// frem: float remainder via SoftFloat
		float a = 7.0F;
		float b = 3.0F;
		float r = a % b;
		// 7.0 % 3.0 = 1.0
		ok = ok && ((int) r == 1);

		a = 10.0F;
		b = 3.0F;
		r = a % b;
		// 10.0 % 3.0 = 1.0
		ok = ok && ((int) r == 1);

		a = 6.0F;
		b = 3.0F;
		r = a % b;
		// 6.0 % 3.0 = 0.0
		ok = ok && ((int) r == 0);

		a = -7.0F;
		b = 3.0F;
		r = a % b;
		// -7.0 % 3.0 = -1.0
		ok = ok && ((int) r == -1);

		a = 7.0F;
		b = -3.0F;
		r = a % b;
		// 7.0 % -3.0 = 1.0 (sign follows dividend)
		ok = ok && ((int) r == 1);

		return ok;
	}

	boolean test_f2i() {
		
		boolean ok = true;
		
		ok = ok && (((int) 0F) == 0);
		ok = ok && (((int) 1F) == 1);
		ok = ok && (((int) 2F) == 2);
		ok = ok && (((int) 0.1F) == 0);
		ok = ok && (((int) 0.4F) == 0);
		ok = ok && (((int) 0.7F) == 0);
		ok = ok && (((int) 0.9999F) == 0);
		ok = ok && (((int) 99.9999F) == 99);

		ok = ok && (((int) -0F) == 0);
		ok = ok && (((int) -1F) == -1);
		ok = ok && (((int) -2F) == -2);
		ok = ok && (((int) -0.1F) == 0);
		ok = ok && (((int) -0.3F) == 0);
		ok = ok && (((int) -0.99F) == 0);
		ok = ok && (((int) -1.1F) == -1);
		ok = ok && (((int) -999.999F) == -999);

		return ok;
	}
}
