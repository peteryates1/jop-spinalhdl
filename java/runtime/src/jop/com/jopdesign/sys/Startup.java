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

/*
 * Created on 24.05.2004
 *
 * To change the template for this generated file go to
 * Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */
package com.jopdesign.sys;

/**
 * @author Martin martin@jopdesign.com
 *
 * Startup code: brings the JVM up, then runs every <clinit> before main().
 *
 * It used to carry a ~30-opcode JVM interpreter for <clinit> methods above a
 * size threshold, which could not run most bytecodes and printed "bytecode N
 * not implemented" at boot. Item 137 removed it: JOPizer now applies the same
 * size and locals limits to <clinit> as to any other method, so an oversized
 * one is a LINK ERROR naming the class rather than a board that bricks before
 * main(). The interpreter's stack, program counter and bytecode readers went
 * with it.
 */
public class Startup {
	
	// use static vars, don't waste stack space
	/** Size of main memory in 32-bit words */
	static int mem_size;
	/** Size of scratchpad memory in 32-bit words */
	static int spm_size;

	/**
	 * How needs this field, and why?
	 */
	static boolean started;
	
	/**
	 * The start method for CPU 1 to n-1.
	 *
	 * Public just for a quick test.
	 */
	// Commented out - may cause issues before GC init
	// static Runnable[] cpuStart = new Runnable[Native.rdMem(Const.IO_CPUCNT)-1];
	static Runnable[] cpuStart;
	
	/**
	 * called from jvm.asm as first method.
	 * Do all initialization here and call main method.
	 */
	static void boot() {
		// use local variable - statics are not CMP save!
		int val;

		// disable all interrupts locally
		Native.wr(0, Const.IO_INTMASK);

		// only CPU 0 does the initialization stuff
		if (Native.rdMem(Const.IO_CPU_ID) == 0) {
			started = false;

			JVMHelp.wr("\nSmall boot\n");
			spm_size = 0;

			// Memory size: use hardware-configured value if available,
			// otherwise fall back to legacy fixed heap.
			int appEnd = Native.rdMem(0);
			int hwMemEnd = Native.rdMem(Const.IO_MEM_SIZE);
			if (hwMemEnd > appEnd) {
				mem_size = hwMemEnd;
			} else {
				mem_size = appEnd + 262144;  // 1MB heap (256K words)
			}

			JVMHelp.wr("GC init...\n");
			val = Native.rdMem(1);		// pointer to 'special' pointers
			GC.init(mem_size, val+4);
			JVMHelp.wr("GC done\n");

			started = true;
			JVMHelp.wr("CI\n");
			clazzinit();
			JVMHelp.wr("OK\n");
			JVMHelp.init();
		}

		// clear all pending interrupts (e.g. timer after reset)
		Native.wr(1, Const.IO_INTCLEARALL);
		// set global enable
		Native.wr(1, Const.IO_INT_ENA);
		// reset any performance counter
		Native.wr(-1, Const.IO_PERFCNT);

		// request CPU id
		val = Native.rdMem(Const.IO_CPU_ID);

		if (val == 0) {
			// only CPU 0 invokes main()
			JVMHelp.wr("M0\n");
			val = Native.rdMem(1);		// pointer to 'special' pointers
			val = Native.rdMem(val+3);	// pointer to main method structure
			Native.invoke(0, val);		// call main (with null pointer on TOS
			exit();
		} else {
			// Non-zero cores: invoke main() directly.
			// The microcode cpux_loop already waited for IO_SIGNAL,
			// so GC, clazzinit, and JVMHelp are initialized by core 0.
			val = Native.rdMem(1);
			val = Native.rdMem(val+3);
			Native.invoke(0, val);
			for (;;) {
				;			// busy loop for other CPUs exit
			}
		}
	}
	

	static void msg() {
		// Minimal output - just a newline to reset serial state
		JVMHelp.wr('\r');
		JVMHelp.wr('\n');
	}
	
	/**
	 * Add a Runnable for the other CPUs
	 * @param r
	 * @param index
	 */
	public static void setRunnable(Runnable r, int index) {
		cpuStart[index] = r;
	}
	
	/**
	 * @return RAM size in 32 bit words
	 */
	static int getRamSize(int offset) {

		// change for DE2-70 VGA board
		int size = 0;
		int firstWord = Native.rd(offset+0);
		int val;
		// increment in 512 Bytes
		for (size=0; ; size+=((512)>>2)) {
			val = Native.rd(offset+size);
			Native.wr(0xaaaa5555, offset+size);
			if (Native.rd(offset+size)!=0xaaaa5555) break;
			Native.wr(0x12345678, offset+size);
			if (Native.rd(offset+size)!=0x12345678) break;
			if (size!=0) {
				// invalidate cache
				Native.invalidate();
				if (Native.rd(offset+0)!=firstWord) break;				
			}
			// restore current word
			Native.wr(val, offset+size);
		}
		// restore the first word
		Native.wr(firstWord, offset+0);

		return size;
	}
	
	
	/**
	 * @return Processor speed in MHz
	 */
	static int getSpeed() {
		
		int start=0, end=0;
		int val = Native.rd(Const.IO_US_CNT) + 5;
		
		while (Native.rd(Const.IO_US_CNT)-val<0) {
			;
		}
		start = Native.rd(Const.IO_CNT);
		val += 32;	// wait 32 us
		while (Native.rd(Const.IO_US_CNT)-val<0) {
			;
		}
		end = Native.rd(Const.IO_CNT);
		
		// round and divide by 32
		return (end-start+16)>>5;
	}
	
	static void version() {

		// BTW: why not using System.out.println()?
		int version = Native.rdIntMem(64-2);
		if (version==0x12345678) {
			// not in the new location, try the old one
			version = Native.rdIntMem(64);
		}
		JVMHelp.wr(" V ");
		// take care with future GC - JVMHelp.intVal allocates
		// a buffer!
		if (version==0x12345678) {
			JVMHelp.wr("pre2005");
		} else {
			JVMHelp.intVal(version);
		}
		JVMHelp.wr("\r\n");
		int speed = getSpeed();
		JVMHelp.intVal(speed);
		JVMHelp.wr("MHz, ");
		JVMHelp.intVal(mem_size/1024*4);
		JVMHelp.wr("KB RAM");
		if (spm_size!=0) {
			JVMHelp.wr(", ");
			JVMHelp.intVal(spm_size*4);
			JVMHelp.wr("Byte on-chip RAM");
		}
		JVMHelp.wr(", ");
		JVMHelp.intVal(Native.rdMem(Const.IO_CPUCNT));
		JVMHelp.wr("CPUs");
		JVMHelp.wr("\r\n");
	}

	public static void exit() {
		
		for (;RtThreadImpl.mission;) {
			RtThreadImpl.sleepMs(1000);
		}
		JVMHelp.wr("\r\nJVM exit!\r\n");
		// Park this core WITHOUT holding a lock.
		//
		// This used to be `synchronized (stack) { for (;;) ; }`. On one core
		// that is harmless — the monitor was only ever a way to get interrupts
		// disabled, which monitorenter does as a side effect, and nothing was
		// ever going to release it. On SMP it is fatal: `synchronized` takes the
		// GLOBAL lock under CmpSync, and CmpSync halts every non-owner for as
		// long as it is held (CmpSync.scala:141-147), so ONE core reaching
		// exit() froze the entire cluster permanently. That was the 4-core
		// generational GC "hang" of current-status item 1 — the collector was
		// idle and the heap intact; a core had simply exited.
		//
		// Disable interrupts directly to keep the original intent. The other
		// park loops in this file (the non-zero-core wait in boot(), and the
		// ones at ~286 and ~416) already spin without a monitor.
		Native.wr(0, Const.IO_INT_ENA);
		for (;;) ;
	}
	
	public static int getSPMSize() {
		return spm_size*4;
	}
	static void clazzinit() {

		// +8, not +6: two special pointers were appended for the Cloneable and
		// Serializable class-info addresses. Microcode only reads slots 1 and 2
		// (jjp/jjhp), so appending is safe, but this offset must follow.
		int table = Native.rdMem(1)+8;		// start of clinit table
		int cnt = Native.rdMem(table);		// number of methods
		++table;
		for (int i=0; i<cnt; ++i) {
			int addr = Native.rdMem(table+i);
			// THE METHOD STRUCT IS NOT DECODED HERE ANY MORE. This used to
			// unpack len/locals/args and the constant-pool address to decide
			// whether to interpret and to seed the interpreter; every one of
			// those values was then unused once the decision became "always
			// invoke". Native.invoke reads the struct itself.
			// ALWAYS INVOKE. A <clinit> is an ordinary method now -- status
			// item 137. It used to be interpreted above a size threshold, by a
			// ~30-opcode interpreter that printed "bytecode N not implemented"
			// and spun forever on anything else; and the threshold here (256)
			// disagreed with JOPizer's (512), so a <clinit> of 1024-2047 bytes
			// was handed to the interpreter while still perfectly invokable.
			//
			// JOPizer now applies the same size and locals limits to <clinit>
			// as to every other method, so an oversized one is a LINK ERROR
			// naming the class rather than a board that bricks before main().
			// Measured across four applications when this changed: 46 <clinit>
			// methods, largest 55 words against a limit of 512.
			Native.invoke(addr);
		}
	}



}
