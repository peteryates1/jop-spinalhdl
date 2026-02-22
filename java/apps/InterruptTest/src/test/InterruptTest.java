package test;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

/**
 * Timer interrupt end-to-end test.
 *
 * Registers a timer interrupt handler, arms the timer, and waits for
 * 5 interrupts.  Each handler invocation prints 'T' and re-arms the
 * timer for the next interrupt.
 *
 * Expected UART output: "I:TTTTTOK"
 */
public class InterruptTest {
	static volatile int timerCount = 0;

	public static void main(String[] args) {
		JVMHelp.wr('I');
		JVMHelp.wr(':');

		// Register timer interrupt handler (interrupt 0)
		JVMHelp.addInterruptHandler(0, new Runnable() {
			public void run() {
				timerCount++;
				JVMHelp.wr('T');
				// Re-arm timer: next interrupt in 5000 us
				int now = Native.rd(Const.IO_US_CNT);
				Native.wr(now + 5000, Const.IO_TIMER);
			}
		});

		// Clear pending interrupts
		Native.wr(1, Const.IO_INTCLEARALL);
		// Set first timer: 5000 us from now
		int now = Native.rd(Const.IO_US_CNT);
		Native.wr(now + 5000, Const.IO_TIMER);
		// Enable timer interrupt (bit 0 of mask)
		Native.wr(1, Const.IO_INTMASK);
		// Global interrupt enable
		Native.wr(1, Const.IO_INT_ENA);

		// Wait for 5 timer interrupts
		while (timerCount < 5) {
			// spin
		}

		JVMHelp.wr('O');
		JVMHelp.wr('K');

		// Disable interrupts
		Native.wr(0, Const.IO_INT_ENA);
		for (;;) {}
	}
}
