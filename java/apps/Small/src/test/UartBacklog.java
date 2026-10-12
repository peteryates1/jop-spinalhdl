package test;

import com.jopdesign.sys.*;

/**
 * Reads a UART receive BACKLOG with consecutive reads and prints it back.
 *
 * Build:
 *   cd java/apps/Small
 *   make APP_NAME=UartBacklog JOP_PRESET=<preset>
 *
 * Prints "UB ready". Then, repeatedly: waits for a byte, waits 5 ms more so the
 * rest of a burst queues in the RX FIFO behind it, reads the FIFO dry with one
 * Native.rd(IO_UART) per byte, and prints "UB n=<count>" and the bytes in hex.
 *
 * Each read pops the FIFO, whose output moves to the byte queued behind it the
 * cycle after. A memory controller that re-reads the register after the read
 * cycle (status item 68) hands the CPU that NEXT byte, so a burst 41 42 43 44
 * comes back 42 43 44 44. A byte at a time it never shows: an emptied FIFO's
 * output does not move. Sim: jop.system.JopUartBacklogTest.
 */
public class UartBacklog {

	static char[] hex = {'0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f'};

	static void wrInt(int v) {
		if (v >= 10) wrInt(v / 10);
		JVMHelp.wr((char) ('0' + v % 10));
	}

	public static void main(String[] args) {
		int[] buf = new int[256];
		JVMHelp.wr("UB ready\n");
		for (;;) {
			while ((Native.rd(Const.IO_STATUS) & Const.MSK_UA_RDRF) == 0)
				;
			int t0 = Native.rd(Const.IO_US_CNT);
			while (Native.rd(Const.IO_US_CNT) - t0 < 5000)
				;
			int n = 0;
			while ((Native.rd(Const.IO_STATUS) & Const.MSK_UA_RDRF) != 0) {
				int b = Native.rd(Const.IO_UART);
				if (n < buf.length) buf[n] = b;
				n++;
			}
			JVMHelp.wr("UB n=");
			wrInt(n);
			for (int i = 0; i < n && i < buf.length; i++) {
				JVMHelp.wr(' ');
				JVMHelp.wr(hex[(buf[i] >>> 4) & 0xF]);
				JVMHelp.wr(hex[buf[i] & 0xF]);
			}
			JVMHelp.wr('\n');
		}
	}
}
