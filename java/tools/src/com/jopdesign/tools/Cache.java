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

package com.jopdesign.tools;

import java.util.*;
import java.text.*;

public class Cache {

	static final int MAX_BC = 1024;		// per function
	static final int MAX_BC_MASK = 0x3ff;
	byte[] bc = new byte[MAX_BC];

	int[] mem;
	JopSim sim;

	int memRead = 0;
	int memTrans = 0;
	int cacheRead = 0;

	protected boolean lastHit = true;
	protected boolean flush = false;
	int wordsLastRead;

	LinkedList<Cache> test = new LinkedList<Cache>();
	Cache use;

	// dummy constructor for child classes
	Cache() {
	}

	Cache(int[] main, JopSim js) {

		mem = main;
		sim = js;

		// Default configuration: 4 * 1KB, 16 blocks
		test.add(new VarBlockCache(main, js, 4, 16, false));

		use = test.get(0);
	}

	int cnt() {
		return test.size();
	}
	void use(int nr) {
		use = test.get(nr);
	}

	int ret(int start, int len, int pc) {
		this.wordsLastRead = len;
		return use.ret(start, len, pc);
	}

	int corrPc(int pc) {
		return use.corrPc(pc);
	}

	int invoke(int start, int len) {
		this.wordsLastRead = len;
		return use.invoke(start, len);
	}

	byte bc(int addr) {
		return use.bc(addr);
	}

	void stat() {

		DecimalFormatSymbols dfs = new DecimalFormatSymbols();
		dfs.setDecimalSeparator('.');
		DecimalFormat mbf = new DecimalFormat("0.00", dfs);
		DecimalFormat mbt = new DecimalFormat("0.000", dfs);

		float mbib = (float) use.memRead/use.cacheRead;
		float mtib = (float) use.memTrans/use.cacheRead;

		String delim = " & ";

		System.out.print(use);
		System.out.print(delim);
		System.out.print(mbf.format(mbib));
		System.out.print(delim);
		System.out.print(mbt.format(mtib));
		System.out.print(delim);
		System.out.print(" \\\\");
		System.out.println();
	}

	public void flushCache() {
		use.flush=true;
	}

	void resetCnt() {
		use.memRead = 0;
		use.memTrans = 0;
		use.cacheRead = 0;
	}

	void rawData() {
		System.out.println(use.memRead/4+use.memTrans*5);
	}

	public String toString() {
		String s = getClass().toString();
		s = s.substring(s.lastIndexOf('.')+1);
		return s;
	}

	int instrBytes() {
		return use.cacheRead;
	}

	boolean lastAccessWasHit() {
		return use.lastHit;
	}
}
