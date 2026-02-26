package test;

import com.jopdesign.sys.*;

/**
 * VGA text controller hardware test for DB_FPGA.
 *
 * Tests BmbVgaText register map:
 *   +0 write: Control (bit0=enable)
 *   +0 read:  Status (bit0=enabled, bit1=vblank, bit2=scrollBusy)
 *   +1 write: Cursor position (col[6:0], row[12:8])
 *   +2 write: Write char+attr at cursor, auto-advance (char[7:0], attr[15:8])
 *   +3 write: Set default attribute (fg[3:0], bg[7:4])
 *   +7 write: Direct write (char[7:0], attr[15:8], col[22:16], row[28:24])
 *   +8 write: Clear screen
 *   +9 write: Scroll up 1 row
 *
 * Attribute byte: (bg << 4) | fg
 * CGA palette: 0=black,1=blue,2=green,3=cyan,4=red,5=magenta,
 *   6=brown,7=ltgray,8=dkgray,9=ltblue,10=ltgreen,11=ltcyan,
 *   12=ltred,13=ltmagenta,14=yellow,15=white
 */
public class VgaTest {

	static int wd;

	static void toggleWd() {
		wd = ~wd;
		Native.wr(wd, Const.IO_WD);
	}

	static void delay(int n) {
		for (int i = 0; i < n; i++) {
			// busy wait
		}
	}

	/** Wait until scrollBusy/clearBusy clears (status bit 2). */
	static void waitVgaReady() {
		for (int i = 0; i < 100000; i++) {
			int st = Native.rd(Const.IO_VGA + 0);
			if ((st & 4) == 0) return;
		}
	}

	/** Set cursor position. */
	static void setCursor(int col, int row) {
		Native.wr((col & 0x7F) | ((row & 0x1F) << 8), Const.IO_VGA + 1);
	}

	/** Write a character with attribute at cursor, auto-advance. */
	static void writeChar(int ch, int attr) {
		Native.wr((ch & 0xFF) | ((attr & 0xFF) << 8), Const.IO_VGA + 2);
	}

	/** Write a string with given attribute at cursor, auto-advancing. */
	static void writeString(String s, int attr) {
		for (int i = 0; i < s.length(); i++) {
			writeChar(s.charAt(i), attr);
		}
	}

	/** Direct-write a character at specified position (no cursor change). */
	static void directWrite(int ch, int attr, int col, int row) {
		Native.wr((ch & 0xFF) | ((attr & 0xFF) << 8)
			| ((col & 0x7F) << 16) | ((row & 0x1F) << 24),
			Const.IO_VGA + 7);
	}

	static void wrInt(int val) {
		if (val < 0) {
			JVMHelp.wr('-');
			val = -val;
		}
		boolean started = false;
		for (int d = 1000000000; d >= 1; d /= 10) {
			int digit = (val / d) % 10;
			if (digit != 0 || started || d == 1) {
				JVMHelp.wr((char)('0' + digit));
				started = true;
			}
		}
	}

	static void wrHex(int val) {
		char[] hex = {'0','1','2','3','4','5','6','7',
		              '8','9','A','B','C','D','E','F'};
		for (int i = 28; i >= 0; i -= 4) {
			JVMHelp.wr(hex[(val >>> i) & 0xF]);
		}
	}

	public static void main(String[] args) {

		JVMHelp.wr("VGA Test start\n");

		// --- 1. Enable VGA ---
		JVMHelp.wr("1. Enable VGA\n");
		Native.wr(1, Const.IO_VGA + 0);
		toggleWd();

		// --- 2. Clear screen ---
		JVMHelp.wr("2. Clear screen\n");
		Native.wr(0x0F, Const.IO_VGA + 3);  // default attr: white on black
		Native.wr(0, Const.IO_VGA + 8);      // trigger clear
		waitVgaReady();
		toggleWd();

		// --- 3. Font diagnostic: show A-Z, a-z, 0-9 ---
		JVMHelp.wr("3. Font diagnostic\n");
		int white = 0x0F;  // white on black

		// Row 0: ABCDEFGHIJKLM
		setCursor(0, 0);
		writeString("ABCDEFGHIJKLM", white);

		// Row 1: NOPQRSTUVWXYZ
		setCursor(0, 1);
		writeString("NOPQRSTUVWXYZ", white);

		// Row 2: abcdefghijklm
		setCursor(0, 2);
		writeString("abcdefghijklm", white);

		// Row 3: nopqrstuvwxyz
		setCursor(0, 3);
		writeString("nopqrstuvwxyz", white);

		// Row 4: 0123456789
		setCursor(0, 4);
		writeString("0123456789", white);

		// Row 6: Each char with its hex code for easy identification
		setCursor(0, 6);
		writeString("Hello World", white);

		setCursor(0, 7);
		writeString("JOP VGA Test", white);

		// Row 9-10: Large colored blocks for visibility
		setCursor(0, 9);
		int redOnWhite = (15 << 4) | 4;  // bg=white, fg=red
		writeString("RED ON WHITE", redOnWhite);

		setCursor(0, 10);
		int whiteOnBlue = (1 << 4) | 15;  // bg=blue, fg=white
		writeString("WHITE ON BLUE", whiteOnBlue);

		// Row 12: Test each char individually with spacing
		setCursor(0, 12);
		for (int ch = 'A'; ch <= 'Z'; ch++) {
			writeChar(ch, white);
			writeChar(' ', white);
			if ((ch - 'A') == 12) {
				setCursor(0, 13);
			}
		}

		toggleWd();

		for (;;) {
			toggleWd();
			delay(500000);
		}
	}
}
