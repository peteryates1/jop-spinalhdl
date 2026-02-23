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
import com.jopdesign.sys.JVMHelp;
public class StackManipulation extends TestCase {
	
	public String toString() {
		return "StackManipulation";
	}

	long l1, l2;

	public boolean test() {

		boolean ok = true;
		
		// dup_x2
		char s[] = new char[2];
		s[0] = s[1] = 'x';
		if (s[0]!='x') { ok=false; JVMHelp.wr('A'); }
		if (s[1]!='x') { ok=false; JVMHelp.wr('B'); }

		long l[] = new long[2];
		long lx;


		l[0] = 123;

		// dup2
		l[0] = lx = 56;

		if (lx!=56) { ok=false; JVMHelp.wr('C'); }
		if (l[0]!=56) { ok=false; JVMHelp.wr('D'); }

		// dup2_x1
		l1 = l2 = 8765;

		if (l1!=8765) { ok=false; JVMHelp.wr('E'); }
		if (l2!=8765) { ok=false; JVMHelp.wr('F'); }

		// dup2_x2
		lx = l[0] = 2;
		if (l[0]!=2) { ok=false; JVMHelp.wr('G'); }
		if (l[1]!=0) { ok=false; JVMHelp.wr('H'); }
		if (lx!=2) { ok=false; JVMHelp.wr('I'); }

		// dup2_x2
		l[0] = l[1] = 1;
		if (l[0]!=1) { ok=false; JVMHelp.wr('J'); }
		if (l[1]!=1) { ok=false; JVMHelp.wr('K'); }

		return ok;
	}

}
