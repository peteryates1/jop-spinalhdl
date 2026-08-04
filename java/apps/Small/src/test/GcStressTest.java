package test;

import com.jopdesign.sys.*;

public class GcStressTest {

	static int[] data;

	static void wrInt(int val) {
		if (val < 0) {
			JVMHelp.wr('-');
			val = -val;
		}
		// Full 32-bit range. This used to start at `val >= 10000` and print only
		// the low five digits, which made both the round counter and
		// GC.freeMemory() wrap invisibly — a 705k-round soak appeared to cycle
		// through the same numbers, and free memory on a large heap was
		// unreadable, so the log could only be judged by counting lines.
		boolean lead = false;
		for (int div = 1000000000; div > 0; div /= 10) {
			int d = (val / div) % 10;
			if (d != 0 || lead || div == 1) { JVMHelp.wr((char)('0' + d)); lead = true; }
		}
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
