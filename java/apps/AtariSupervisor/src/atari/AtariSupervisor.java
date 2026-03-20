package atari;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

/**
 * Atari 800 supervisor running on JOP.
 *
 * Initializes the Atari core via AtariCtrl registers, then polls
 * the CH376T USB host for keyboard input and passes it to the Atari.
 *
 * I/O addresses (from IoAddressAllocator):
 *   AtariCtrl: IO_BASE + 0x40 .. IO_BASE + 0x4F  (0xC0-0xCF)
 *   SdSpi:     IO_BASE + 0x68 .. IO_BASE + 0x6B  (0xE8-0xEB)
 */
public class AtariSupervisor {

	// --- AtariCtrl register offsets from IO_BASE ---
	static final int ATARI_BASE     = Const.IO_BASE + 0x40;
	static final int ATARI_STATUS   = ATARI_BASE + 0;   // R: bit0=osd, bit1=locked; W: bit0=osdEn, bit7=coldReset
	static final int ATARI_CART_SEL = ATARI_BASE + 1;   // W: cartSelect[5:0]
	static final int ATARI_CONFIG   = ATARI_BASE + 2;   // W: [0]=pal, [3:1]=ramSel, [4]=turbo, [5]=a800, [6]=hires
	static final int ATARI_PADDLE01 = ATARI_BASE + 3;   // W: [7:0]=pad0, [15:8]=pad1
	static final int ATARI_PADDLE23 = ATARI_BASE + 4;   // W: [7:0]=pad2, [15:8]=pad3
	static final int ATARI_JOY12    = ATARI_BASE + 7;   // W: [4:0]=joy1_n, [12:8]=joy2_n
	static final int ATARI_JOY34    = ATARI_BASE + 8;   // W: [4:0]=joy3_n, [12:8]=joy4_n
	static final int ATARI_KB_THR   = ATARI_BASE + 9;   // W: [13:8]=throttle, [4]=start, [3]=select, [2]=option
	static final int ATARI_KEYBOARD = ATARI_BASE + 12;  // W: [5:0]=scanCode, [8]=pressed, [9]=shift, [10]=ctrl, [11]=break

	// --- SdSpi register offsets from IO_BASE ---
	static final int SPI_STATUS = Const.IO_SD_SPI;       // R: bit0=busy; W: bit0=csAssert
	static final int SPI_DATA   = Const.IO_SD_SPI + 1;   // R: rx byte; W: tx byte (starts xfer)
	static final int SPI_CLKDIV = Const.IO_SD_SPI + 2;   // W: clock divider

	// --- CH376T commands ---
	static final int CMD_GET_IC_VER    = 0x01;
	static final int CMD_CHECK_EXIST   = 0x06;
	static final int CMD_SET_USB_MODE  = 0x15;
	static final int CMD_GET_STATUS    = 0x22;
	static final int CMD_RD_USB_DATA0  = 0x27;
	static final int CMD_SET_RETRY     = 0x0B;
	static final int CMD_RESET_ALL     = 0x05;
	static final int CMD_SET_USB_ADDR  = 0x13;
	static final int CMD_SET_USB_ID    = 0x12;
	static final int CMD_ISSUE_TKN_X   = 0x4E;
	static final int CMD_CLR_STALL     = 0x41;

	// CH376T USB status codes
	static final int USB_INT_SUCCESS   = 0x14;
	static final int USB_INT_CONNECT   = 0x15;
	static final int USB_INT_DISCONNECT = 0x16;

	// USB token PIDs
	static final int PID_SETUP = 0x0D;
	static final int PID_IN    = 0x09;
	static final int PID_OUT   = 0x01;

	// Timing
	static final int WD_INTERVAL = 100000;  // microseconds between watchdog toggles

	// USB HID keyboard state
	static int lastKeycode = 0;
	static int lastModifiers = 0;
	static int usbDevAddr = 1;
	static int usbEndpIn = 1;   // HID interrupt IN endpoint
	static int hidToggle = 0;   // DATA0/DATA1 toggle for interrupt endpoint

	// Atari keyboard state
	static boolean keyPressed = false;
	static int atariScanCode = 0;

	// --- USB HID scancode to Atari 800 scancode translation ---
	// Index = USB HID usage ID, value = Atari scan code (0-63), -1 = unmapped
	// Atari scan codes from Atari 800 keyboard matrix
	static final int[] hidToAtari = {
		-1, -1, -1, -1,  // 0x00-0x03: reserved
		0x3F, // 0x04: A
		0x15, // 0x05: B
		0x12, // 0x06: C
		0x3A, // 0x07: D
		0x2A, // 0x08: E
		0x38, // 0x09: F
		0x3D, // 0x0A: G
		0x39, // 0x0B: H
		0x0D, // 0x0C: I
		0x01, // 0x0D: J
		0x05, // 0x0E: K
		0x00, // 0x0F: L
		0x25, // 0x10: M
		0x23, // 0x11: N
		0x08, // 0x12: O
		0x0A, // 0x13: P
		0x2F, // 0x14: Q
		0x28, // 0x15: R
		0x3E, // 0x16: S
		0x2D, // 0x17: T
		0x0B, // 0x18: U
		0x10, // 0x19: V
		0x2E, // 0x1A: W
		0x16, // 0x1B: X
		0x2B, // 0x1C: Y
		0x17, // 0x1D: Z
		0x1F, // 0x1E: 1
		0x1E, // 0x1F: 2
		0x1A, // 0x20: 3
		0x18, // 0x21: 4
		0x1D, // 0x22: 5
		0x1B, // 0x23: 6
		0x33, // 0x24: 7
		0x35, // 0x25: 8
		0x30, // 0x26: 9
		0x32, // 0x27: 0
		0x0C, // 0x28: Enter/Return
		0x1C, // 0x29: Escape
		0x34, // 0x2A: Backspace
		0x2C, // 0x2B: Tab
		0x21, // 0x2C: Space
		0x06, // 0x2D: - (minus)
		0x07, // 0x2E: = (equals)
		-1,   // 0x2F: [ (no direct Atari equivalent)
		-1,   // 0x30: ] (no direct Atari equivalent)
		-1,   // 0x31: backslash
		-1,   // 0x32: non-US #
		0x02, // 0x33: ; (semicolon)
		-1,   // 0x34: ' (apostrophe)
		-1,   // 0x35: ` (grave)
		0x20, // 0x36: , (comma)
		0x22, // 0x37: . (period)
		0x26, // 0x38: / (slash)
		0x3C, // 0x39: Caps Lock
	};

	// F-key to console key mapping
	// F1=Start, F2=Select, F3=Option, F4=Reset
	static boolean consolStart  = false;
	static boolean consolSelect = false;
	static boolean consolOption = false;

	public static void main(String[] args) {

		int w = 0, wd_next = 0;

		JVMHelp.wr("Atari Supervisor starting...\n");

		// --- Initialize Atari core ---
		initAtari();

		// --- Initialize CH376T ---
		boolean ch376ok = initCH376T();
		if (ch376ok) {
			JVMHelp.wr("CH376T ready\n");
		} else {
			JVMHelp.wr("CH376T init failed\n");
		}

		// --- Main loop ---
		while (true) {
			int now = Native.rd(Const.IO_US_CNT);
			if (wd_next - now < 0) {
				wd_next = now + WD_INTERVAL;
				w = ~w;
				Native.wr(w, Const.IO_WD);
			}

			if (ch376ok) {
				pollKeyboard();
			}
		}
	}

	/** Initialize Atari core registers */
	static void initAtari() {
		// PAL, 48K RAM (ramSelect=3), atari800mode, hires enabled
		// Config reg: [0]=pal=1, [3:1]=ramSel=3, [5]=a800=1, [6]=hires=1
		int config = 1 | (3 << 1) | (1 << 5) | (1 << 6);  // 0x67
		Native.wr(config, ATARI_CONFIG);

		// Joysticks: all released (active low = 0x1F each)
		Native.wr(0x1F1F, ATARI_JOY12);
		Native.wr(0x1F1F, ATARI_JOY34);

		// Paddles: center position
		Native.wr(0x7474, ATARI_PADDLE01);
		Native.wr(0x7474, ATARI_PADDLE23);

		// Console keys: none pressed, throttle=31
		Native.wr(0x1F00, ATARI_KB_THR);

		// No key pressed
		Native.wr(0, ATARI_KEYBOARD);

		JVMHelp.wr("Atari init: PAL 48K 800\n");
	}

	// ===================================================================
	// SPI primitives
	// ===================================================================

	/** Wait for SPI transfer to complete */
	static void spiWait() {
		while ((Native.rd(SPI_STATUS) & 1) != 0) {
			// busy
		}
	}

	/** Send one byte via SPI and return received byte */
	static int spiXfer(int tx) {
		spiWait();
		Native.wr(tx, SPI_DATA);
		spiWait();
		return Native.rd(SPI_DATA) & 0xFF;
	}

	/** Assert CS (active low) */
	static void csAssert() {
		Native.wr(1, SPI_STATUS);  // bit0 = CS assert
	}

	/** Deassert CS */
	static void csDeassert() {
		Native.wr(0, SPI_STATUS);
	}

	/** Set SPI clock divider. SPI_CLK = sys_clk / (2 * (div + 1)) */
	static void spiSetDiv(int div) {
		Native.wr(div, SPI_CLKDIV);
	}

	// ===================================================================
	// CH376T SPI protocol
	// ===================================================================

	/** Send a CH376T command byte (preceded by 0x57, 0xAB header) */
	static void ch376Cmd(int cmd) {
		csAssert();
		spiXfer(0x57);
		spiXfer(0xAB);
		spiXfer(cmd);
	}

	/** End a CH376T command (deassert CS) */
	static void ch376End() {
		csDeassert();
	}

	/** Send command + 1 data byte */
	static void ch376CmdData(int cmd, int data) {
		ch376Cmd(cmd);
		spiXfer(data);
		ch376End();
	}

	/** Read status after interrupt (GET_STATUS) */
	static int ch376GetStatus() {
		ch376Cmd(CMD_GET_STATUS);
		int status = spiXfer(0xFF);
		ch376End();
		return status;
	}

	/** Wait for CH376T interrupt with timeout (microseconds) */
	static int ch376WaitInt(int timeoutUs) {
		int deadline = Native.rd(Const.IO_US_CNT) + timeoutUs;
		while (true) {
			int now = Native.rd(Const.IO_US_CNT);
			if (deadline - now < 0) return -1;  // timeout
			// INT# is active low, directly readable in SdSpi as "card detect"
			// When INT# is low, cardPresent bit (bit 1) will be high
			// Actually: check by issuing GET_STATUS periodically
			// For simplicity, poll GET_STATUS
			int status = ch376GetStatus();
			if (status != 0) return status;
			// Small delay between polls
		}
	}

	// ===================================================================
	// CH376T initialization
	// ===================================================================

	/** Initialize CH376T module. Returns true on success. */
	static boolean initCH376T() {
		// Set SPI clock to ~1 MHz for init (divider = 28 at 56.67 MHz)
		spiSetDiv(28);

		// Small delay after power-up
		delay(50000);

		// Reset
		ch376Cmd(CMD_RESET_ALL);
		ch376End();
		delay(100000);  // 100ms after reset

		// Check existence: send 0xA5, expect ~0xA5 = 0x5A back
		ch376Cmd(CMD_CHECK_EXIST);
		spiXfer(0xA5);
		int resp = spiXfer(0xFF);
		ch376End();
		if (resp != 0x5A) {
			JVMHelp.wr("CH376T check: 0x");
			wrHex(resp);
			JVMHelp.wr("\n");
			return false;
		}

		// Get IC version
		ch376Cmd(CMD_GET_IC_VER);
		int ver = spiXfer(0xFF);
		ch376End();
		JVMHelp.wr("CH376T v0x");
		wrHex(ver & 0x3F);
		JVMHelp.wr("\n");

		// Set USB host mode
		ch376CmdData(CMD_SET_USB_MODE, 0x06);  // mode 6 = USB host, auto-detect
		delay(20000);

		// Read mode response
		ch376Cmd(CMD_SET_USB_MODE);
		// Actually the response comes as status byte
		ch376End();

		// Wait for device connect (up to 2 seconds)
		JVMHelp.wr("Waiting for USB...\n");
		int status = ch376WaitInt(2000000);
		if (status == USB_INT_CONNECT) {
			JVMHelp.wr("USB connected\n");
			// Speed up SPI for normal operation (~4 MHz)
			spiSetDiv(7);
			return setupHidKeyboard();
		}

		JVMHelp.wr("No USB device\n");
		return false;
	}

	/** Set up a USB HID keyboard after device connect */
	static boolean setupHidKeyboard() {
		// For a basic HID keyboard, we need to:
		// 1. Set device address (SET_ADDRESS via SETUP token)
		// 2. Set configuration (SET_CONFIGURATION)
		// 3. Then poll interrupt IN endpoint for HID reports

		// Set USB address to 1
		ch376CmdData(CMD_SET_USB_ADDR, usbDevAddr);

		// The CH376T handles enumeration internally in host mode 6
		// After connect, we can directly read HID reports via GET_STATUS polling

		hidToggle = 0;  // Start with DATA0
		return true;
	}

	// ===================================================================
	// USB HID keyboard polling
	// ===================================================================

	/** Poll CH376T for keyboard input, update Atari registers */
	static void pollKeyboard() {
		// Issue interrupt IN token to HID endpoint
		ch376Cmd(CMD_ISSUE_TKN_X);
		spiXfer(hidToggle != 0 ? 0x80 : 0x00);  // toggle bit in high nibble
		spiXfer((PID_IN << 4) | (usbEndpIn & 0x0F));  // PID_IN + endpoint
		ch376End();

		// Wait for response (short timeout — we're polling)
		int status = ch376WaitInt(5000);  // 5ms timeout

		if (status == USB_INT_SUCCESS) {
			hidToggle ^= 1;  // Toggle DATA0/DATA1

			// Read HID report (8 bytes: modifier, reserved, key1..key6)
			ch376Cmd(CMD_RD_USB_DATA0);
			int len = spiXfer(0xFF);
			if (len >= 3) {
				int modifier = spiXfer(0xFF);     // byte 0: modifier keys
				spiXfer(0xFF);                    // byte 1: reserved
				int keycode = spiXfer(0xFF);      // byte 2: first key

				// Read remaining bytes to drain the buffer
				for (int i = 3; i < len; i++) {
					spiXfer(0xFF);
				}
				ch376End();

				processHidReport(modifier, keycode);
			} else {
				// Drain whatever bytes there are
				for (int i = 0; i < len; i++) {
					spiXfer(0xFF);
				}
				ch376End();
			}
		}
		// NAK (0x2A) is normal — no new data, just ignore
	}

	/** Process a USB HID keyboard report */
	static void processHidReport(int modifier, int keycode) {
		// Check for F-key console mappings (even when other keys are pressed)
		boolean f1 = false, f2 = false, f3 = false, f4 = false;
		// F1-F4 are HID 0x3A-0x3D
		// We only get keycode for byte 2, but F-keys could be in any position
		// For simplicity, check first key only
		if (keycode == 0x3A) f1 = true;
		if (keycode == 0x3B) f2 = true;
		if (keycode == 0x3C) f3 = true;
		if (keycode == 0x3D) f4 = true;

		// Update console keys
		consolStart  = f1;
		consolSelect = f2;
		consolOption = f3;
		updateConsoleKeys();

		// Cold reset on F4
		if (f4) {
			Native.wr(0x80, ATARI_STATUS);  // bit 7 = cold reset pulse
		}

		// Handle regular key
		if (keycode != lastKeycode || modifier != lastModifiers) {
			if (keycode == 0 && lastKeycode != 0) {
				// Key released
				keyPressed = false;
				Native.wr(0, ATARI_KEYBOARD);  // pressed=0
			} else if (keycode != 0 && keycode < hidToAtari.length) {
				int atariCode = hidToAtari[keycode];
				if (atariCode >= 0) {
					// Key pressed
					boolean shift = (modifier & 0x22) != 0;  // left or right shift
					boolean ctrl  = (modifier & 0x11) != 0;  // left or right ctrl
					boolean brk   = (keycode == 0x48);        // Pause/Break key

					int kbReg = (atariCode & 0x3F)
						| (1 << 8)                // pressed
						| (shift ? (1 << 9) : 0)  // shift
						| (ctrl  ? (1 << 10) : 0) // control
						| (brk   ? (1 << 11) : 0); // break

					Native.wr(kbReg, ATARI_KEYBOARD);
					keyPressed = true;
					atariScanCode = atariCode;
				}
			}
			lastKeycode = keycode;
			lastModifiers = modifier;
		}
	}

	/** Update console key register (Start/Select/Option + throttle) */
	static void updateConsoleKeys() {
		int throttle = 31;  // max throttle
		int reg = (throttle << 8)
			| (consolStart  ? (1 << 4) : 0)
			| (consolSelect ? (1 << 3) : 0)
			| (consolOption ? (1 << 2) : 0);
		Native.wr(reg, ATARI_KB_THR);
	}

	/** Print a byte as 2-digit hex */
	static void wrHex(int val) {
		int hi = (val >> 4) & 0xF;
		int lo = val & 0xF;
		JVMHelp.wr(hi < 10 ? '0' + hi : 'A' + hi - 10);
		JVMHelp.wr(lo < 10 ? '0' + lo : 'A' + lo - 10);
	}

	/** Delay in microseconds (busy-wait on IO_US_CNT) */
	static void delay(int us) {
		int start = Native.rd(Const.IO_US_CNT);
		while (Native.rd(Const.IO_US_CNT) - start < us) {
			// wait
		}
	}
}
