package test;

import com.jopdesign.sys.*;

/**
 * VGA DMA framebuffer hardware test for DB_FPGA.
 *
 * Writes RGB565 test patterns directly to SDRAM via Native.wrMem().
 * Places the framebuffer at the top of SDRAM (below stack regions).
 *
 * RGB565: R[15:11] G[10:5] B[4:0], two pixels per 32-bit word
 * (low 16 = first pixel, high 16 = second pixel).
 *
 * Patterns: solid red, solid green, solid blue, color bars, gradient.
 */
public class VgaDmaTest {

	static final int IO_VGA_DMA = Const.IO_VGA_DMA;
	static final int WIDTH = 640;
	static final int HEIGHT = 480;
	static final int WORDS_PER_LINE = WIDTH / 2;
	static final int FB_WORDS = WORDS_PER_LINE * HEIGHT;
	static final int FB_BYTES = FB_WORDS * 4;

	static final int RED     = 0xF800;
	static final int GREEN   = 0x07E0;
	static final int BLUE    = 0x001F;
	static final int CYAN    = 0x07FF;
	static final int MAGENTA = 0xF81F;
	static final int YELLOW  = 0xFFE0;
	static final int WHITE   = 0xFFFF;
	static final int BLACK   = 0x0000;

	static int fbAddr;
	static int wd;

	static void toggleWd() {
		wd = ~wd;
		Native.wr(wd, Const.IO_WD);
	}

	static void delay(int n) {
		for (int i = 0; i < n; i++) { }
	}

	static void wrInt(int val) {
		if (val < 0) { JVMHelp.wr('-'); val = -val; }
		boolean started = false;
		for (int d = 1000000000; d >= 1; d /= 10) {
			int digit = (val / d) % 10;
			if (digit != 0 || started || d == 1) {
				JVMHelp.wr((char) ('0' + digit));
				started = true;
			}
		}
	}

	static void wrHex(int val) {
		JVMHelp.wr("0x");
		for (int i = 28; i >= 0; i -= 4) {
			int nibble = (val >>> i) & 0xF;
			if (nibble < 10)
				JVMHelp.wr((char) ('0' + nibble));
			else
				JVMHelp.wr((char) ('A' + nibble - 10));
		}
	}

	static int pack(int pixel0, int pixel1) {
		return (pixel0 & 0xFFFF) | ((pixel1 & 0xFFFF) << 16);
	}

	static void fillSolid(int color) {
		int word = pack(color, color);
		for (int i = 0; i < FB_WORDS; i++) {
			Native.wrMem(word, fbAddr + i);
			if ((i & 0xFFF) == 0) toggleWd();
		}
	}

	static void fillColorBars() {
		int barWords = WORDS_PER_LINE / 8;
		for (int y = 0; y < HEIGHT; y++) {
			int lineOff = y * WORDS_PER_LINE;
			for (int bar = 0; bar < 8; bar++) {
				int color;
				if (bar == 0) color = RED;
				else if (bar == 1) color = GREEN;
				else if (bar == 2) color = BLUE;
				else if (bar == 3) color = CYAN;
				else if (bar == 4) color = MAGENTA;
				else if (bar == 5) color = YELLOW;
				else if (bar == 6) color = WHITE;
				else color = BLACK;
				int word = pack(color, color);
				int barOff = bar * barWords;
				for (int x = 0; x < barWords; x++) {
					Native.wrMem(word, fbAddr + lineOff + barOff + x);
				}
			}
			if ((y & 31) == 0) toggleWd();
		}
	}

	static void fillBorder() {
		// White border (4px thick) on black background
		int white = pack(WHITE, WHITE);
		int black = pack(BLACK, BLACK);
		int borderW = 2; // 2 words = 4 pixels thick
		for (int y = 0; y < HEIGHT; y++) {
			int lineOff = y * WORDS_PER_LINE;
			boolean topBottom = (y < 4 || y >= HEIGHT - 4);
			for (int x = 0; x < WORDS_PER_LINE; x++) {
				boolean leftRight = (x < borderW || x >= WORDS_PER_LINE - borderW);
				if (topBottom || leftRight)
					Native.wrMem(white, fbAddr + lineOff + x);
				else
					Native.wrMem(black, fbAddr + lineOff + x);
			}
			if ((y & 31) == 0) toggleWd();
		}
	}

	static void fillGradient() {
		for (int y = 0; y < HEIGHT; y++) {
			int lineOff = y * WORDS_PER_LINE;
			for (int x = 0; x < WORDS_PER_LINE; x++) {
				int px0 = x * 2;
				int px1 = px0 + 1;
				int r0 = (px0 * 31) / (WIDTH - 1);
				int r1 = (px1 * 31) / (WIDTH - 1);
				Native.wrMem(pack(r0 << 11, r1 << 11), fbAddr + lineOff + x);
			}
			if ((y & 31) == 0) toggleWd();
		}
	}

	static void reportStatus() {
		int status = Native.rd(IO_VGA_DMA + 0);
		JVMHelp.wr("  st=");
		wrHex(status);
		JVMHelp.wr(" base=");
		wrHex(Native.rd(IO_VGA_DMA + 1));
		JVMHelp.wr(" sz=");
		wrInt(Native.rd(IO_VGA_DMA + 2));
		JVMHelp.wr('\n');
	}

	static void showPattern(int num, String name) {
		JVMHelp.wr(num + ". " + name + "...");
		toggleWd();
	}

	static void waitSeconds(int sec) {
		for (int i = 0; i < sec * 10; i++) {
			delay(8000000);
			toggleWd();
		}
	}

	public static void main(String[] args) {

		JVMHelp.wr("VGA DMA Test\n");
		toggleWd();

		// Place framebuffer at top of SDRAM
		int memEnd = Native.rd(Const.IO_MEM_SIZE);
		if (memEnd == 0) memEnd = 8 * 1024 * 1024;
		fbAddr = memEnd - FB_WORDS;
		int fbByteAddr = fbAddr << 2;

		JVMHelp.wr("fb: ");
		wrHex(fbByteAddr);
		JVMHelp.wr(" (");
		wrInt(FB_WORDS);
		JVMHelp.wr(" words)\n");

		// Configure DMA
		Native.wr(fbByteAddr, IO_VGA_DMA + 1);
		Native.wr(FB_BYTES, IO_VGA_DMA + 2);

		// Fill border BEFORE enabling DMA
		JVMHelp.wr("1. Border\n");
		fillBorder();
		JVMHelp.wr("  filled, enabling DMA\n");

		// Enable DMA
		Native.wr(1, IO_VGA_DMA + 0);
		reportStatus();

		waitSeconds(10);
		reportStatus();

		// Color bars
		JVMHelp.wr("2. Color bars\n");
		fillColorBars();
		JVMHelp.wr("  filled\n");
		reportStatus();
		waitSeconds(10);

		// Red gradient
		JVMHelp.wr("3. Gradient\n");
		fillGradient();
		JVMHelp.wr("  filled\n");
		reportStatus();
		waitSeconds(10);

		// Solid red
		JVMHelp.wr("4. Solid RED\n");
		fillSolid(RED);
		JVMHelp.wr("  filled\n");
		reportStatus();
		waitSeconds(10);

		// Solid green
		JVMHelp.wr("5. Solid GREEN\n");
		fillSolid(GREEN);
		JVMHelp.wr("  filled\n");
		reportStatus();
		waitSeconds(10);

		// Solid blue
		JVMHelp.wr("6. Solid BLUE\n");
		fillSolid(BLUE);
		JVMHelp.wr("  filled\n");
		reportStatus();
		waitSeconds(10);

		// Final: border and loop
		JVMHelp.wr("7. Border\n");
		fillBorder();
		JVMHelp.wr("  done, looping\n");
		reportStatus();

		for (;;) {
			toggleWd();
			delay(500000);
		}
	}
}
