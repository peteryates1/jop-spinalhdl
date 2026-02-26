package com.jopdesign.hw;

import com.jopdesign.sys.Const;

/**
 * Hardware object for the VGA text controller (BmbVgaText).
 *
 * 80x30 characters, 8x16 font, 640x480@60Hz, RGB565 output.
 *
 * Fields map directly to sequential I/O registers at Const.IO_VGA:
 *   +0  statusControl    R: status  W: control (bit0=enable)
 *   +1  cursorPos        R/W: col[6:0], row[12:8]
 *   +2  charAttr         W: char[7:0], attr[15:8] (auto-advance cursor)
 *   +3  defaultAttr      R/W: default attribute (fg[3:0], bg[7:4])
 *   +4  paletteWrite     W: index[19:16], rgb565[15:0]
 *   +5  columns          R: number of columns (80)
 *   +6  rows             R: number of rows (30)
 *   +7  directWrite      W: char[7:0], attr[15:8], col[22:16], row[28:24]
 *   +8  clearScreen      W: any write triggers clear
 *   +9  scrollUp         W: any write triggers scroll
 *
 * Attribute byte: (bg << 4) | fg
 * CGA palette: 0=black, 1=blue, 2=green, 3=cyan, 4=red, 5=magenta,
 *   6=brown, 7=ltgray, 8=dkgray, 9=ltblue, 10=ltgreen, 11=ltcyan,
 *   12=ltred, 13=ltmagenta, 14=yellow, 15=white
 */
public final class VgaText extends HardwareObject {

	// Color constants
	public static final int BLACK       = 0;
	public static final int BLUE        = 1;
	public static final int GREEN       = 2;
	public static final int CYAN        = 3;
	public static final int RED         = 4;
	public static final int MAGENTA     = 5;
	public static final int BROWN       = 6;
	public static final int LIGHT_GRAY  = 7;
	public static final int DARK_GRAY   = 8;
	public static final int LIGHT_BLUE  = 9;
	public static final int LIGHT_GREEN = 10;
	public static final int LIGHT_CYAN  = 11;
	public static final int LIGHT_RED   = 12;
	public static final int LIGHT_MAGENTA = 13;
	public static final int YELLOW      = 14;
	public static final int WHITE       = 15;

	// Status bits
	public static final int STATUS_ENABLED = 1;
	public static final int STATUS_VBLANK  = 2;
	public static final int STATUS_BUSY    = 4;

	// Singleton
	private static VgaText instance = null;

	public static VgaText getInstance() {
		if (instance == null)
			instance = (VgaText) make(new VgaText(), Const.IO_VGA, 0);
		return instance;
	}

	// --- Hardware registers (sequential, volatile) ---

	/** +0: R=status (enabled/vblank/busy), W=control (bit0=enable) */
	public volatile int statusControl;
	/** +1: R/W cursor position: col[6:0], row[12:8] */
	public volatile int cursorPos;
	/** +2: W char+attr at cursor, auto-advance: char[7:0], attr[15:8] */
	public volatile int charAttr;
	/** +3: R/W default attribute: fg[3:0], bg[7:4] */
	public volatile int defaultAttr;
	/** +4: W palette: index[19:16], rgb565[15:0] */
	public volatile int paletteWrite;
	/** +5: R columns (80) */
	public volatile int columns;
	/** +6: R rows (30) */
	public volatile int rows;
	/** +7: W direct write: char[7:0], attr[15:8], col[22:16], row[28:24] */
	public volatile int directWrite;
	/** +8: W clear screen (any write triggers) */
	public volatile int clearScreen;
	/** +9: W scroll up (any write triggers) */
	public volatile int scrollUp;

	// --- Convenience methods ---

	/** Build an attribute byte from foreground and background color. */
	public static int attr(int fg, int bg) {
		return (bg << 4) | (fg & 0xF);
	}

	/** Enable VGA output. */
	public void enable() {
		statusControl = 1;
	}

	/** Disable VGA output. */
	public void disable() {
		statusControl = 0;
	}

	/** Return true if a scroll or clear operation is in progress. */
	public boolean isBusy() {
		return (statusControl & STATUS_BUSY) != 0;
	}

	/** Wait until scroll/clear completes. */
	public void waitReady() {
		for (int i = 0; i < 100000; i++) {
			if (!isBusy()) return;
		}
	}

	/** Set cursor position. */
	public void setCursor(int col, int row) {
		cursorPos = (col & 0x7F) | ((row & 0x1F) << 8);
	}

	/** Write a character with attribute at cursor, auto-advance. */
	public void writeChar(int ch, int attribute) {
		charAttr = (ch & 0xFF) | ((attribute & 0xFF) << 8);
	}

	/** Write a string with given attribute at cursor, auto-advancing. */
	public void writeString(String s, int attribute) {
		for (int i = 0; i < s.length(); i++) {
			writeChar(s.charAt(i), attribute);
		}
	}

	/** Write a character at a specific position without moving cursor. */
	public void writeAt(int ch, int attribute, int col, int row) {
		directWrite = (ch & 0xFF) | ((attribute & 0xFF) << 8)
			| ((col & 0x7F) << 16) | ((row & 0x1F) << 24);
	}

	/** Clear screen with default attribute. */
	public void clear() {
		clearScreen = 0;
		waitReady();
	}

	/** Set default attribute (used by clear). */
	public void setDefaultAttr(int attribute) {
		defaultAttr = attribute;
	}

	/** Clear screen with a specific attribute. */
	public void clear(int attribute) {
		defaultAttr = attribute;
		clearScreen = 0;
		waitReady();
	}

	/** Scroll up one row. Bottom row is cleared with default attribute. */
	public void scroll() {
		scrollUp = 0;
		waitReady();
	}
}
