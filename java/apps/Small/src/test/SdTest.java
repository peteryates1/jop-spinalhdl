package test;

import com.jopdesign.sys.*;

/**
 * SD card test for QMTECH EP4CGX150 + DB_FPGA daughter board.
 *
 * Exercises the BmbSdNative controller: card detect, SD init sequence,
 * and reads block 0.
 *
 * Register map at IO_SD (IO_BASE + 0x30), offsets 0..9:
 *   0: Status (read) / Control (write)
 *   1: Response[31:0] (read) / CMD argument (write)
 *   2: Response[63:32] (read) / CMD index+flags (write)
 *   3: Response[95:64] (read, R2 only)
 *   4: Response[127:96] (read, R2 only)
 *   5: Data FIFO pop (read) / Data FIFO push (write)
 *   6: RX FIFO occupancy (read) / Clock divider (write)
 *   7: CRC error flags (read) / Data control (write)
 *   8: cardPresent|busWidth4 (read) / busWidth4 (write)
 *   9: — / Block length (write)
 */
public class SdTest {

	// Register offsets from IO_SD
	static final int REG_STATUS  = Const.IO_SD + 0;
	static final int REG_RESP0   = Const.IO_SD + 1;
	static final int REG_RESP1   = Const.IO_SD + 2;
	static final int REG_RESP2   = Const.IO_SD + 3;
	static final int REG_RESP3   = Const.IO_SD + 4;
	static final int REG_FIFO    = Const.IO_SD + 5;
	static final int REG_CLKDIV  = Const.IO_SD + 6;
	static final int REG_DATCTRL = Const.IO_SD + 7;
	static final int REG_BUSW    = Const.IO_SD + 8;
	static final int REG_BLKLEN  = Const.IO_SD + 9;

	// Status bits (read from REG_STATUS)
	static final int ST_CMD_BUSY      = 1 << 0;
	static final int ST_CMD_RESP_VALID = 1 << 1;
	static final int ST_CMD_CRC_ERR   = 1 << 2;
	static final int ST_CMD_TIMEOUT   = 1 << 3;
	static final int ST_DAT_BUSY      = 1 << 4;
	static final int ST_DAT_CRC_ERR   = 1 << 5;
	static final int ST_DAT_TIMEOUT   = 1 << 6;
	static final int ST_CARD_PRESENT  = 1 << 7;
	static final int ST_TX_FIFO_EMPTY = 1 << 8;
	static final int ST_TX_FIFO_FULL  = 1 << 9;
	static final int ST_RX_FIFO_EMPTY = 1 << 10;
	static final int ST_RX_FIFO_DATA  = 1 << 11;

	// Control bits (write to REG_STATUS)
	static final int CTL_SEND_CMD  = 1 << 0;
	static final int CTL_ABORT     = 1 << 1;
	static final int CTL_OPEN_DRAIN = 1 << 2;

	static int wd;

	static char[] hexChars = {'0','1','2','3','4','5','6','7',
	                          '8','9','A','B','C','D','E','F'};

	static void wrHex(int val) {
		for (int i = 28; i >= 0; i -= 4) {
			JVMHelp.wr(hexChars[(val >>> i) & 0xF]);
		}
	}

	static void wrHexByte(int val) {
		JVMHelp.wr(hexChars[(val >>> 4) & 0xF]);
		JVMHelp.wr(hexChars[val & 0xF]);
	}

	static void toggleWd() {
		wd = ~wd;
		Native.wr(wd, Const.IO_WD);
	}

	static void delay(int n) {
		for (int i = 0; i < n; i++) {
			// busy wait
		}
	}

	static int sdReadStatus() {
		return Native.rd(REG_STATUS);
	}

	/**
	 * Wait for command to complete (cmdBusy clears).
	 * Returns status register value, or -1 on timeout.
	 */
	static int sdWaitCmd() {
		for (int i = 0; i < 1000000; i++) {
			int st = sdReadStatus();
			if ((st & ST_CMD_BUSY) == 0) {
				return st;
			}
			if ((i & 0xFFFF) == 0) toggleWd();
		}
		return -1;
	}

	/**
	 * Send an SD command and wait for completion.
	 *
	 * @param idx        command index (0-63)
	 * @param arg        32-bit argument
	 * @param expectResp true if response expected
	 * @param longResp   true for R2 (136-bit) response
	 * @return status register after command completes, or -1 on poll timeout
	 */
	static int sdCmd(int idx, int arg, boolean expectResp, boolean longResp) {
		// Write argument
		Native.wr(arg, REG_RESP0);

		// Write command index + flags
		int cmdReg = idx & 0x3F;
		if (expectResp) cmdReg |= (1 << 6);
		if (longResp) cmdReg |= (1 << 7);
		Native.wr(cmdReg, REG_RESP1);

		// Send command
		Native.wr(CTL_SEND_CMD, REG_STATUS);

		// Wait for completion
		return sdWaitCmd();
	}

	/**
	 * Wait for data transfer to complete (datBusy clears).
	 * Returns status, or -1 on timeout.
	 */
	static int sdWaitData() {
		for (int i = 0; i < 5000000; i++) {
			int st = sdReadStatus();
			if ((st & ST_DAT_BUSY) == 0) {
				return st;
			}
			if ((i & 0xFFFF) == 0) toggleWd();
		}
		return -1;
	}

	public static void main(String[] args) {

		JVMHelp.wr("SD Card Test\n");

		// Set slow clock for init: divider=99 -> ~400kHz at 80MHz
		Native.wr(99, REG_CLKDIV);
		delay(10000);

		// Check card detect
		int st = sdReadStatus();
		if ((st & ST_CARD_PRESENT) == 0) {
			JVMHelp.wr("No card detected!\n");
			for (;;) { toggleWd(); delay(500000); }
		}
		JVMHelp.wr("Card detected!\n");

		// --- CMD0: GO_IDLE_STATE (no response) ---
		// Set open-drain mode for CMD line during init
		Native.wr(CTL_OPEN_DRAIN, REG_STATUS);
		delay(10000);
		sdCmd(0, 0, false, false);
		// Clear open-drain
		Native.wr(0, REG_STATUS);
		delay(10000);
		JVMHelp.wr("CMD0: ok\n");
		toggleWd();

		// --- CMD8: SEND_IF_COND (R7) ---
		// arg: VHS=1 (2.7-3.6V), check pattern=0xAA
		st = sdCmd(8, 0x000001AA, true, false);
		if (st == -1 || (st & ST_CMD_TIMEOUT) != 0) {
			JVMHelp.wr("CMD8: timeout (not SD 2.0+)\n");
			for (;;) { toggleWd(); delay(500000); }
		}
		int resp0 = Native.rd(REG_RESP0);
		if ((resp0 & 0xFF) != 0xAA) {
			JVMHelp.wr("CMD8: bad check pattern ");
			wrHex(resp0);
			JVMHelp.wr('\n');
			for (;;) { toggleWd(); delay(500000); }
		}
		JVMHelp.wr("CMD8: ok (AA)\n");
		toggleWd();

		// --- ACMD41: SD_SEND_OP_COND (loop until ready) ---
		// ACMD = CMD55 + CMD41
		// arg: HCS=1 (bit 30), voltage window 3.2-3.4V
		int acmdArg = (1 << 30) | 0x00FF8000;
		boolean ready = false;
		for (int attempt = 0; attempt < 1000; attempt++) {
			// CMD55: APP_CMD
			st = sdCmd(55, 0, true, false);
			if (st == -1 || (st & ST_CMD_TIMEOUT) != 0) {
				JVMHelp.wr("CMD55: timeout\n");
				for (;;) { toggleWd(); delay(500000); }
			}

			// CMD41: SD_SEND_OP_COND
			st = sdCmd(41, acmdArg, true, false);
			if (st == -1 || (st & ST_CMD_TIMEOUT) != 0) {
				// Some cards don't respond to first ACMD41, retry
				delay(10000);
				toggleWd();
				continue;
			}

			resp0 = Native.rd(REG_RESP0);
			if ((resp0 & (1 << 31)) != 0) {
				// Card ready (busy bit cleared)
				ready = true;
				break;
			}
			delay(10000);
			if ((attempt & 0x3F) == 0) toggleWd();
		}
		if (!ready) {
			JVMHelp.wr("ACMD41: timeout\n");
			for (;;) { toggleWd(); delay(500000); }
		}
		JVMHelp.wr("ACMD41: card ready\n");
		toggleWd();

		// --- CMD2: ALL_SEND_CID (R2, long response) ---
		st = sdCmd(2, 0, true, true);
		if (st == -1 || (st & ST_CMD_TIMEOUT) != 0) {
			JVMHelp.wr("CMD2: timeout\n");
			for (;;) { toggleWd(); delay(500000); }
		}
		JVMHelp.wr("CID: ");
		wrHex(Native.rd(REG_RESP3));
		JVMHelp.wr(' ');
		wrHex(Native.rd(REG_RESP2));
		JVMHelp.wr(' ');
		wrHex(Native.rd(REG_RESP1));
		JVMHelp.wr(' ');
		wrHex(Native.rd(REG_RESP0));
		JVMHelp.wr('\n');
		toggleWd();

		// --- CMD3: SEND_RELATIVE_ADDR (R6) ---
		st = sdCmd(3, 0, true, false);
		if (st == -1 || (st & ST_CMD_TIMEOUT) != 0) {
			JVMHelp.wr("CMD3: timeout\n");
			for (;;) { toggleWd(); delay(500000); }
		}
		resp0 = Native.rd(REG_RESP0);
		int rca = resp0 & 0xFFFF0000; // RCA is in upper 16 bits
		JVMHelp.wr("RCA: ");
		wrHex(rca);
		JVMHelp.wr('\n');
		toggleWd();

		// --- CMD7: SELECT_CARD (R1b) ---
		st = sdCmd(7, rca, true, false);
		if (st == -1 || (st & ST_CMD_TIMEOUT) != 0) {
			JVMHelp.wr("CMD7: timeout\n");
			for (;;) { toggleWd(); delay(500000); }
		}
		JVMHelp.wr("Card selected\n");
		toggleWd();

		// Speed up clock: divider=1 -> ~20MHz at 80MHz
		// (use divider=3 for ~10MHz if there are issues)
		Native.wr(1, REG_CLKDIV);
		delay(10000);

		// --- CMD16: SET_BLOCKLEN 512 (R1) ---
		st = sdCmd(16, 512, true, false);
		if (st == -1 || (st & ST_CMD_TIMEOUT) != 0) {
			JVMHelp.wr("CMD16: timeout\n");
			for (;;) { toggleWd(); delay(500000); }
		}
		JVMHelp.wr("CMD16: ok\n");
		toggleWd();

		// --- Read block 0 ---
		JVMHelp.wr("Reading block 0...\n");

		// Set block length register
		Native.wr(512, REG_BLKLEN);

		// CMD17: READ_SINGLE_BLOCK, arg=block address 0
		// First, start data read
		Native.wr(1, REG_DATCTRL); // bit0 = startRead

		st = sdCmd(17, 0, true, false);
		if (st == -1 || (st & ST_CMD_TIMEOUT) != 0) {
			JVMHelp.wr("CMD17: timeout\n");
			for (;;) { toggleWd(); delay(500000); }
		}

		// Wait for data transfer to complete
		st = sdWaitData();
		if (st == -1) {
			JVMHelp.wr("Data read timeout\n");
			for (;;) { toggleWd(); delay(500000); }
		}
		if ((st & ST_DAT_CRC_ERR) != 0) {
			JVMHelp.wr("Data CRC error\n");
			for (;;) { toggleWd(); delay(500000); }
		}
		if ((st & ST_DAT_TIMEOUT) != 0) {
			JVMHelp.wr("Data timeout\n");
			for (;;) { toggleWd(); delay(500000); }
		}

		// Read and display first 64 bytes (16 words) from FIFO
		for (int row = 0; row < 4; row++) {
			// Print address
			wrHexByte(row * 16);
			JVMHelp.wr(':');
			for (int col = 0; col < 4; col++) {
				JVMHelp.wr(' ');
				int word = Native.rd(REG_FIFO);
				wrHex(word);
			}
			JVMHelp.wr('\n');
			toggleWd();
		}

		// Drain remaining FIFO (512 bytes = 128 words, we read 16)
		for (int i = 16; i < 128; i++) {
			Native.rd(REG_FIFO);
			if ((i & 0x1F) == 0) toggleWd();
		}

		JVMHelp.wr("Done\n");

		// Idle loop
		for (;;) {
			toggleWd();
			delay(500000);
		}
	}
}
