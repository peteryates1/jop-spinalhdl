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

import com.jopdesign.sys.Const;

public class IOSimMin {
	protected JopSim js;

	protected static final int SIM_CACHE_FLUSH = -51;
	protected static final int SIM_CACHE_COST = -52;
	protected static final int SIM_CACHE_DUMP = -53;

	// find JVM exit
	protected static String exitStr = "JVM exit!";
	protected char[] exitBuf = new char[exitStr.length()];

	protected int cpuId;
	protected static int cpuCnt = 1;
	protected static boolean startCMP = false;
	static boolean globalLock = false;

	int moncnt = 0;

	protected int interrupt;
	protected int mask;
	protected boolean intEna;
	protected boolean timeShot;
	protected int nextTimerInt;
	protected int intNr;

	public void setJopSimRef(JopSim jsRef) {
		js = jsRef;
	}

	public void setCpuId(int id) {
		cpuId = id;
		if (id + 1 > cpuCnt) {
			cpuCnt = id + 1;
		}
	}

	public int read(int addr) {

		int val;
		int i;

		try {
			switch (addr) {
			case Const.IO_STATUS:
				val = Const.MSK_UA_TDRE;
				if (System.in.available() != 0) {
					val |= Const.MSK_UA_RDRF;
				}
				break;
			case Const.IO_USB_STATUS:
				val = Const.MSK_UA_TDRE;
				break;
			case Const.IO_STATUS2:
				i = 0;
				val = i;
				break;
			case Const.IO_UART:
				if (System.in.available() != 0) {
					val = System.in.read();
				} else {
					val = '_';
				}
				break;
			case Const.IO_UART2:
				i = 0;
				val = i;
				break;
			case Const.IO_CNT:
				val = (int)js.clkCnt;
				break;
			case Const.IO_US_CNT:
				val = usCnt();
				break;
			case Const.IO_INTNR:
				val = intNr;
				break;
			case Const.IO_EXCPT:
				val = js.exceptReason;
				break;
			case Const.IO_CPU_ID:
				val = cpuId;
				break;
			case Const.IO_CPUCNT:
				val = cpuCnt;
				break;
			case SIM_CACHE_COST:
				val = js.cacheCost;
				break;
			case SIM_CACHE_DUMP:
				js.objectCacheSim.dumpStats();
				val = js.objectCacheSim.getStats().accessCount;
				break;
			default:
				val = 0;
				System.out.println("Default read " + addr);
			}
		} catch (Exception e) {
			System.out.println(e);
			val = 0;
		}

		return val;
	}

	public void write(int addr, int val) {

		switch (addr) {
		case Const.IO_UART:
			if (JopSim.log)
				System.out.print("\t->");
			System.out.print((char) val);
			if (JopSim.log)
				System.out.println("<-");
			// check the output for JVM exit!
			for (int i = 0; i < exitStr.length() - 1; ++i) {
				exitBuf[i] = exitBuf[i + 1];
			}
			exitBuf[exitBuf.length - 1] = (char) val;
			if (new String(exitBuf).equals(exitStr)) {
				JopSim.exit();
			}
			break;
		case Const.IO_USB_DATA:
			break;
		case Const.IO_UART2:
			break;
		case Const.IO_STATUS2:
			break;
		case Const.IO_INT_ENA:
			intEna = (val == 0) ? false : true;
			break;
		case Const.IO_TIMER:
			nextTimerInt = val;
			timeShot = false;
			break;
		case Const.IO_SWINT:
			interrupt |= 1 << val;
			break;
		case Const.IO_WD:
			break;
		case Const.IO_EXCPT:
			js.intExcept = true;
			js.exceptReason = val;
			break;
		case Const.IO_LOCK:
			break;
		case Const.IO_SIGNAL:
			startCMP = (val != 0);
			break;
		case Const.IO_INTMASK:
			mask = val;
			break;
		case Const.IO_INTCLEARALL:
			interrupt = 0;
			break;
		case Const.IO_PERFCNT:
			js.resetStat();
			break;
		case SIM_CACHE_COST:
			js.cacheCost = val;
			break;
		case SIM_CACHE_FLUSH:
			js.cache.flushCache();
			js.objectCacheSim.flushCache();
			break;
		case Const.IO_DEADLINE:
			js.localCnt += (val-((int) js.clkCnt));
			break;
		default:
			System.out.println("Default write " + addr + " " + val);
		}
	}

	boolean monEnter() {
		intEna = false;
		if (moncnt == 0) {
			if (globalLock) {
				return false;
			} else {
				++moncnt;
				globalLock = true;
				return true;
			}
		} else {
			++moncnt;
			return true;
		}
	}

	void monExit() {
		--moncnt;
		if (moncnt == 0) {
			intEna = true;
			globalLock = false;
		}
	}

	boolean intPending() {
		int i;
		if ((nextTimerInt - usCnt() < 0) && !timeShot) {
			timeShot = true;
			interrupt |= 1;
		}
		int val = interrupt & mask;
		if (val != 0 && intEna) {
			for (i = 0; val != 0; ++i) {
				if ((val & 1) != 0) {
					break;
				}
				val >>>= 1;
			}
			intNr = i;
			interrupt &= ~(1 << i);
			intEna = false;
			return true;
		}
		return false;
	}

	public int usCnt() {
		return ((int) System.currentTimeMillis()) * 1000;
	}

}
