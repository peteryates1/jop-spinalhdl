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

public class VarBlockCache extends Cache {

	int[] ctag;

	int next = 0;
	int currentBlock = 0;
	int numBlocks;
	int blockSize;
	int mask;

	boolean stackNext;

	VarBlockCache(int[] main, JopSim js, int size, int num, boolean stkNxt) {
		mem = main;
		sim = js;
		numBlocks = num;
		blockSize = MAX_BC*size/numBlocks;
		mask = 0;
		for (int i=(MAX_BC*size)>>1; i>0; i >>>= 1) {
			mask <<= 1;
			mask |= 1;
		}
		bc = new byte[blockSize * numBlocks];
		ctag = new int[numBlocks];
		resetCache();
		stackNext = stkNxt;
	}

	private void resetCache() {
		for (int i=0; i<numBlocks; ++i) {
			ctag[i] = 0;
		}
	}

	int corrPc(int pc) {
		return (pc - currentBlock*blockSize) & mask;
	}

	int invoke(int start, int len) {
		int off = testCache(start, len);
		return off;
	}

	int ret(int start, int len, int pc) {
		if (stackNext) {
			next = currentBlock;
		}
		int off = testCache(start, len);
		return (off+pc) & mask;
	}

	int testCache(int start, int len) {
		this.lastHit = true;
		if(flush) {
			flush = false;
			resetCache();
		}
		for (int i=0; i<numBlocks; ++i) {
			if (ctag[i]==start) {	// HIT
				currentBlock = i;
				return currentBlock*blockSize;
			}
		}

		// not found
		this.lastHit = false;
		currentBlock = next;
		for (int i=0; i<=len*4/blockSize; ++i) {
			ctag[next] = 0;				// block in use
			++next;
			next %= numBlocks;
		}
		ctag[currentBlock] = start;		// start block

		int off = currentBlock*blockSize;

		loadBc(off, start, len);
		return off;
	}

	void loadBc(int off, int start, int len) {
		// high byte of word is first bc!!!
		for (int i=0; i<len; ++i) {
			int val = sim.readInstrMem(start+i);
			for (int j=0; j<4; ++j) {
				bc[(off+i*4+(3-j)) & mask] = (byte) val;
				val >>>= 8;
			}
		}

		memRead += len*4;
		memTrans++;
	}

	byte bc(int addr) {
		++cacheRead;
		return bc[addr & mask];
	}

	public String toString() {
		return "Variable block cache "+(bc.length/1024)+" KB & "+numBlocks;
	}
}
