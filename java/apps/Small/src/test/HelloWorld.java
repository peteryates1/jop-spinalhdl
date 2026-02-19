package test;

import com.jopdesign.sys.*;

public class HelloWorld {

	static int[] data;

	static void wrInt(int val) {
		if (val < 0) {
			JVMHelp.wr('-');
			val = -val;
		}
		if (val >= 10000) JVMHelp.wr((char)('0' + (val / 10000) % 10));
		if (val >= 1000) JVMHelp.wr((char)('0' + (val / 1000) % 10));
		if (val >= 100) JVMHelp.wr((char)('0' + (val / 100) % 10));
		if (val >= 10) JVMHelp.wr((char)('0' + (val / 10) % 10));
		JVMHelp.wr((char)('0' + val % 10));
	}

	public static void main(String[] args) {

		JVMHelp.wr("GC test start\n");
		int w = 0;

		for (int round = 0; ; ++round) {
			JVMHelp.wr("R");
			wrInt(round);

			// Allocate arrays that become garbage each iteration
			for (int i = 0; i < 10; ++i) {
				data = new int[32];
				data[0] = round;
				data[31] = i;
			}

			JVMHelp.wr(" f=");
			wrInt(GC.freeMemory());
			JVMHelp.wr("\n");

			// Watchdog — toggle every 512 rounds for visible ~1 Hz LED blink
			if ((round & 0x1FF) == 0) w = ~w;
			Native.wr(w, Const.IO_WD);
		}
	}
}
