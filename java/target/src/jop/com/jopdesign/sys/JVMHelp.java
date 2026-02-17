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

package com.jopdesign.sys;

public class JVMHelp {
	
	public static Object makeHWObject(Object o, int address, int idx, int cp) {
		int ref = Native.toInt(o);
		int pcl = Native.rdMem(ref + 1);
		int p = Native.rdMem(cp - 1);
		p = Native.rdMem(p + 1);
		p += idx * 2;
		Native.wrMem(address, p);
		Native.wrMem(pcl, p + 1);
		return Native.toObject(p);
	}
	
	static void noim() {
		int i;
		wr('n');
		wr('i');
		wr(' ');
		i = Native.getSP(); // sp of noim();
		int sp = Native.rdIntMem(i - 4); // sp of calling function
		int pc = Native.rdIntMem(sp - 3) - 1; // one to high
		i = Native.rdIntMem(sp); // mp
		wrSmall(i);
		wr(' ');
		int start = Native.rdMem(i) >>> 10;
		wrSmall(start);
		wr(' ');
		wrByte(pc);
		wr(' ');

		int val = Native.rdMem(start + (pc >> 2));
		for (i = (pc & 0x03); i < 3; ++i)
			val >>= 8;
		val &= 0xff;
		wrByte(val);

		Object o = new Object();
		synchronized (o) {

			wr("\r\nJOP: bytecode ");
			wrByte(val);
			wr(" not implemented");

			trace(sp);

			for (;;)
				;
		}
	}
	
	static void trace(int sp) {

		int fp, mp, vp, pc, /*addr,*/ loc, args;
		int val;

//		for (int i=0; i<1024; ++i) {
//			wrSmall(i);
//			wrSmall(Native.rdIntMem(i));
//			wr('\n');
//		}
		wr("saved sp=");
		wrSmall(sp);
		wr('\n');

		fp = sp - 4; // first frame point is easy, since last sp points to the end of the frame

		wr("  mp     pc     fp");
		wr('\n');

		while (fp > Const.STACK_OFF + 5) {
			mp = Native.rdIntMem(fp + 4);
			vp = Native.rdIntMem(fp + 2);
			pc = Native.rdIntMem(fp + 1);
			val = Native.rdMem(mp);
//			addr = val >>> 10; // address of callee

			wrSmall(mp);
//			wrSmall(addr);
			wrSmall(pc);
			wrSmall(fp);
			wr('\n');

			val = Native.rdMem(mp + 1); // cp, locals, args
			args = val & 0x1f;
			loc = (val >>> 5) & 0x1f;
			fp = vp + args + loc; // new fp can be calc. with vp and count of local vars
		}
		wr('\n');
	}
	
	public static void wr(int c) {
		// busy wait on free tx buffer
		// but ncts is not used anymore =>
		// no wait on an open serial line, just wait
		// on the baud rate
		while ((Native.rd(Const.IO_UART_STATUS) & 1) == 0);
		Native.wr(c, Const.IO_UART_DATA);
	}

	public static void wr(String s) {
		int i = s.length();
		for (int j = 0; j < i; ++j) {
			wr(s.charAt(j));
		}
	}

	public static void wrByte(int i) {
		wr('0' + i / 100);
		wr('0' + i / 10 % 10);
		wr('0' + i % 10);
		wr(' ');
	}

	public static void wrSmall(int i) {
		wr('0' + i / 100000 % 10);
		wr('0' + i / 10000 % 10);
		wr('0' + i / 1000 % 10);
		wr('0' + i / 100 % 10);
		wr('0' + i / 10 % 10);
		wr('0' + i % 10);
		wr(' ');
	}
}
