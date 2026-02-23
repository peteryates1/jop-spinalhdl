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

package jvm;
import com.jopdesign.sys.JVMHelp;
public class BranchTest1   extends TestCase {

	static class A {}
	static class B {}
	
	public String toString() {
		return "BranchTest1";
	}
	
	public boolean test() {
		A a,b,nullReference;

		boolean Ok=true;
		nullReference=new A();
		a=new A();
		b=new A();
		nullReference=null;

		if(a==b) { Ok=false; JVMHelp.wr('1'); }
		if(!(a!=b)) { Ok=false; JVMHelp.wr('2'); }
		a=b;
		if(!(a==b)) { Ok=false; JVMHelp.wr('3'); }
		if(a!=b) { Ok=false; JVMHelp.wr('4'); }

		if(a==null) { Ok=false; JVMHelp.wr('5'); }
		if(!(a!=null)) { Ok=false; JVMHelp.wr('6'); }

		if(nullReference!=null) { Ok=false; JVMHelp.wr('7'); }
		if(!(nullReference==null)) { Ok=false; JVMHelp.wr('8'); }
		return Ok;
		}

}