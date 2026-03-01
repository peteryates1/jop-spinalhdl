package test;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

/**
 * 8-core SMP UART test.
 *
 * Each core reads its CPU ID and prints "C&lt;id&gt;\n" to its UART every ~500ms,
 * toggling the watchdog LED. Core 0 also drives ser_txd (CP2102N) as usual.
 * All cores' TXD lines are routed to JP1 header pins for the Pico debug probe.
 *
 * Build: cd java/apps/Small && make clean && make all APP_NAME=SmpUartTest
 */
public class SmpUartTest implements Runnable {
	private static final int WD_INTERVAL = 500000;  // ~500ms in microseconds

	private int cpuID;

	public SmpUartTest(int cpuID) {
		this.cpuID = cpuID;
	}

	public void run() {
		int wd = cpuID;
		int next = 0;

		// Core 0 starts all other cores
		if (cpuID == 0) {
			Native.wr(1, Const.IO_SIGNAL);
		}

		while (true) {
			int now = Native.rd(Const.IO_US_CNT);
			if (next <= now) {
				next = now + WD_INTERVAL;

				// Print "C<id>\r\n"
				JVMHelp.wr('C');
				JVMHelp.wr('0' + cpuID);
				JVMHelp.wr('\r');
				JVMHelp.wr('\n');

				wd = ~wd;
				Native.wr(wd, Const.IO_WD);
			}
		}
	}

	public static void main(String[] args) {
		new SmpUartTest(Native.rdMem(Const.IO_CPU_ID)).run();
	}
}
