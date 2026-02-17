package com.jopdesign.sys;

public class Startup {
	
	static void msg() {
		JVMHelp.wr("\nJOP start\n");
	}
	
	public static void exit() {
		JVMHelp.wr("JVM exit!\n");
		for (;;) ;
	}
	
	static void boot() {
		// use local variable - statics are not CMP save!
		int val;
		
		Native.wr(0, Const.IO_INTMASK);
		
		val = Native.rdMem(Const.IO_CPU_ID);
		if (val == 0) {
			msg();
		}
		
		// clear all pending interrupts (e.g. timer after reset)
		Native.wr(1, Const.IO_INTCLEARALL);
		
		// set global enable
		Native.wr(1, Const.IO_INT_ENA);
		
		// reset any performance counter
		Native.wr(-1, Const.IO_PERFCNT);
		
		// request CPU id
//		if (val == 0) {
			// only CPU 0 invokes main()  // temporarily all start main
			val = Native.rdMem(1);		// pointer to 'special' pointers
			val = Native.rdMem(val+3);	// pointer to main method structure
			Native.invoke(0, val);		// call main (with null pointer on TOS
			exit();
//		}
	}
	
	/**
	 * @return Processor speed in MHz
	 */
	static int getSpeed() {
		int start=0, end=0;
		int val = Native.rd(Const.IO_US_CNT) + 5;
		while (Native.rd(Const.IO_US_CNT)-val<0);
		start = Native.rd(Const.IO_CNT);
		val += 32;	// wait 32 us
		while (Native.rd(Const.IO_US_CNT)-val<0);
		end = Native.rd(Const.IO_CNT);
		// round and divide by 32
		return (end-start+16)>>5;
	}
}
