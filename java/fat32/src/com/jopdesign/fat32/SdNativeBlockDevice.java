package com.jopdesign.fat32;

import com.jopdesign.hw.SdNative;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Const;
import com.jopdesign.sys.Native;

/**
 * BlockDevice implementation for SD Native mode (4-bit bus).
 * Uses the SdNative hardware object for card init and block I/O.
 */
public class SdNativeBlockDevice implements BlockDevice {
	private SdNative sd;
	private int rca;
	private boolean initialized;

	public SdNativeBlockDevice() {
		initialized = false;
	}

	private void toggleWd() {
		Native.wr(~Native.rd(Const.IO_WD), Const.IO_WD);
	}

	private void delay(int n) {
		for (int i = 0; i < n; i++) { }
	}

	private int sendCmd(int idx, int arg, boolean expectResp, boolean longResp) {
		sd.sendCmd(idx, arg, expectResp, longResp);
		return sd.waitCmd();
	}

	public boolean init() {
		sd = SdNative.getInstance();

		// Slow clock for init: divider=99 -> ~400kHz at 80MHz
		sd.setClockDivider(99);
		delay(10000);

		// Check card detect
		if (!sd.isCardPresent()) {
			JVMHelp.wr("SD: no card\n");
			return false;
		}

		// CMD0: GO_IDLE_STATE (open drain)
		sd.statusControl = SdNative.CTRL_OPEN_DRAIN;
		delay(10000);
		sendCmd(0, 0, false, false);
		sd.statusControl = 0;
		delay(10000);

		// CMD8: SEND_IF_COND
		int st = sendCmd(8, 0x000001AA, true, false);
		if ((st & SdNative.STATUS_CMD_TIMEOUT) != 0) {
			JVMHelp.wr("SD: CMD8 timeout\n");
			return false;
		}
		int resp = sd.cmdArgResponse0;
		if ((resp & 0xFF) != 0xAA) {
			JVMHelp.wr("SD: CMD8 bad pattern\n");
			return false;
		}

		// ACMD41: SD_SEND_OP_COND
		int acmdArg = (1 << 30) | 0x00FF8000;
		boolean ready = false;
		for (int attempt = 0; attempt < 1000; attempt++) {
			st = sendCmd(55, 0, true, false);
			if ((st & SdNative.STATUS_CMD_TIMEOUT) != 0) {
				JVMHelp.wr("SD: CMD55 timeout\n");
				return false;
			}
			st = sendCmd(41, acmdArg, true, false);
			if ((st & SdNative.STATUS_CMD_TIMEOUT) != 0) {
				delay(10000);
				toggleWd();
				continue;
			}
			resp = sd.cmdArgResponse0;
			if ((resp & (1 << 31)) != 0) {
				ready = true;
				break;
			}
			delay(10000);
			if ((attempt & 0x3F) == 0) toggleWd();
		}
		if (!ready) {
			JVMHelp.wr("SD: ACMD41 timeout\n");
			return false;
		}

		// CMD2: ALL_SEND_CID
		st = sendCmd(2, 0, true, true);
		if ((st & SdNative.STATUS_CMD_TIMEOUT) != 0) {
			JVMHelp.wr("SD: CMD2 timeout\n");
			return false;
		}
		toggleWd();

		// CMD3: SEND_RELATIVE_ADDR
		st = sendCmd(3, 0, true, false);
		if ((st & SdNative.STATUS_CMD_TIMEOUT) != 0) {
			JVMHelp.wr("SD: CMD3 timeout\n");
			return false;
		}
		rca = sd.cmdArgResponse0 & 0xFFFF0000;
		toggleWd();

		// CMD7: SELECT_CARD
		st = sendCmd(7, rca, true, false);
		if ((st & SdNative.STATUS_CMD_TIMEOUT) != 0) {
			JVMHelp.wr("SD: CMD7 timeout\n");
			return false;
		}

		// Set clock divider=3 -> ~10MHz (required for reliable writes)
		sd.setClockDivider(3);
		delay(10000);

		// CMD16: SET_BLOCKLEN 512
		st = sendCmd(16, 512, true, false);
		if ((st & SdNative.STATUS_CMD_TIMEOUT) != 0) {
			JVMHelp.wr("SD: CMD16 timeout\n");
			return false;
		}
		toggleWd();

		initialized = true;
		return true;
	}

	public boolean readBlock(int sectorNum, int[] buf) {
		if (!initialized) return false;
		sd.setBlockLength(512);
		sd.startRead();
		int st = sendCmd(17, sectorNum, true, false);
		if ((st & SdNative.STATUS_CMD_TIMEOUT) != 0) return false;
		st = sd.waitData();
		if ((st & (SdNative.STATUS_DATA_CRC_ERR | SdNative.STATUS_DATA_TIMEOUT)) != 0)
			return false;
		for (int i = 0; i < 128; i++) {
			buf[i] = sd.dataFifo;
			if ((i & 0x1F) == 0) toggleWd();
		}
		return true;
	}

	public boolean writeBlock(int sectorNum, int[] buf) {
		if (!initialized) return false;
		sd.setBlockLength(512);
		// Fill TX FIFO before issuing CMD24 (critical ordering)
		for (int i = 0; i < 128; i++) {
			sd.dataFifo = buf[i];
			if ((i & 0x1F) == 0) toggleWd();
		}
		int st = sendCmd(24, sectorNum, true, false);
		if ((st & SdNative.STATUS_CMD_TIMEOUT) != 0) return false;
		sd.startWrite();
		st = sd.waitData();
		if ((st & (SdNative.STATUS_DATA_CRC_ERR | SdNative.STATUS_DATA_TIMEOUT)) != 0)
			return false;
		return true;
	}
}
