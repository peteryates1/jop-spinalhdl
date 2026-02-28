package com.jopdesign.fat32;

import com.jopdesign.hw.SdSpi;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Const;
import com.jopdesign.sys.Native;

/**
 * BlockDevice implementation for SD SPI mode.
 * Uses the SdSpi hardware object for card init and block I/O.
 */
public class SdSpiBlockDevice implements BlockDevice {
	private SdSpi spi;
	private boolean initialized;
	private boolean sdhc; // true if SDHC (block addressing)

	public SdSpiBlockDevice() {
		initialized = false;
	}

	private void toggleWd() {
		Native.wr(~Native.rd(Const.IO_WD), Const.IO_WD);
	}

	private void delay(int n) {
		for (int i = 0; i < n; i++) { }
	}

	/** Send SD command in SPI mode. Returns R1 response byte. */
	private int spiCmd(int cmd, int arg, int crc) {
		spi.send(0x40 | cmd);
		spi.send((arg >> 24) & 0xFF);
		spi.send((arg >> 16) & 0xFF);
		spi.send((arg >> 8) & 0xFF);
		spi.send(arg & 0xFF);
		spi.send(crc);
		// Wait for response (R1 byte, MSB=0)
		for (int i = 0; i < 10; i++) {
			int r = spi.receive();
			if ((r & 0x80) == 0) return r;
		}
		return 0xFF;
	}

	public boolean init() {
		spi = SdSpi.getInstance();

		// Slow clock for init
		spi.setClockDivider(99);
		delay(10000);

		// 80 dummy clocks with CS deasserted
		spi.csDeassert();
		for (int i = 0; i < 10; i++) {
			spi.send(0xFF);
		}

		// CMD0: GO_IDLE_STATE
		spi.csAssert();
		int r1 = spiCmd(0, 0, 0x95);
		if (r1 != 0x01) {
			JVMHelp.wr("SPI: CMD0 failed\n");
			spi.csDeassert();
			return false;
		}

		// CMD8: SEND_IF_COND
		r1 = spiCmd(8, 0x000001AA, 0x87);
		if (r1 == 0x01) {
			// SD v2 card — read 4-byte R7 response
			int r7 = 0;
			for (int i = 0; i < 4; i++) {
				r7 = (r7 << 8) | spi.receive();
			}
			if ((r7 & 0xFF) != 0xAA) {
				JVMHelp.wr("SPI: CMD8 bad pattern\n");
				spi.csDeassert();
				return false;
			}

			// ACMD41: SD_SEND_OP_COND with HCS
			boolean ready = false;
			for (int attempt = 0; attempt < 1000; attempt++) {
				spiCmd(55, 0, 0xFF);
				r1 = spiCmd(41, 0x40000000, 0xFF);
				if (r1 == 0x00) {
					ready = true;
					break;
				}
				delay(10000);
				if ((attempt & 0x3F) == 0) toggleWd();
			}
			if (!ready) {
				JVMHelp.wr("SPI: ACMD41 timeout\n");
				spi.csDeassert();
				return false;
			}

			// CMD58: READ_OCR — check CCS bit
			spiCmd(58, 0, 0xFF);
			int ocr = 0;
			for (int i = 0; i < 4; i++) {
				ocr = (ocr << 8) | spi.receive();
			}
			sdhc = (ocr & 0x40000000) != 0;
		} else {
			// SD v1 or MMC — not SDHC
			sdhc = false;
			boolean ready = false;
			for (int attempt = 0; attempt < 1000; attempt++) {
				spiCmd(55, 0, 0xFF);
				r1 = spiCmd(41, 0, 0xFF);
				if (r1 == 0x00) {
					ready = true;
					break;
				}
				delay(10000);
				if ((attempt & 0x3F) == 0) toggleWd();
			}
			if (!ready) {
				JVMHelp.wr("SPI: ACMD41 timeout\n");
				spi.csDeassert();
				return false;
			}
		}

		// Speed up clock: divider=1 -> ~20MHz
		spi.setClockDivider(1);
		delay(10000);

		spi.csDeassert();
		spi.send(0xFF);
		toggleWd();

		initialized = true;
		return true;
	}

	public boolean readBlock(int sectorNum, int[] buf) {
		if (!initialized) return false;
		int addr = sdhc ? sectorNum : (sectorNum << 9);
		spi.csAssert();
		int r1 = spiCmd(17, addr, 0xFF);
		if (r1 != 0x00) {
			spi.csDeassert();
			return false;
		}
		// Wait for data token 0xFE
		for (int i = 0; i < 100000; i++) {
			int b = spi.receive();
			if (b == 0xFE) break;
			if (b != 0xFF) {
				spi.csDeassert();
				return false;
			}
			if ((i & 0xFFFF) == 0) toggleWd();
		}
		// Read 512 bytes, pack into int[128] big-endian
		for (int i = 0; i < 128; i++) {
			int w = spi.receive() << 24;
			w |= spi.receive() << 16;
			w |= spi.receive() << 8;
			w |= spi.receive();
			buf[i] = w;
			if ((i & 0x1F) == 0) toggleWd();
		}
		// Read 2 CRC bytes (discard)
		spi.receive();
		spi.receive();
		spi.csDeassert();
		spi.send(0xFF);
		return true;
	}

	public boolean writeBlock(int sectorNum, int[] buf) {
		if (!initialized) return false;
		int addr = sdhc ? sectorNum : (sectorNum << 9);
		spi.csAssert();
		int r1 = spiCmd(24, addr, 0xFF);
		if (r1 != 0x00) {
			spi.csDeassert();
			return false;
		}
		// Data token
		spi.send(0xFE);
		// Unpack 512 bytes from int[128] big-endian
		for (int i = 0; i < 128; i++) {
			int w = buf[i];
			spi.send((w >> 24) & 0xFF);
			spi.send((w >> 16) & 0xFF);
			spi.send((w >> 8) & 0xFF);
			spi.send(w & 0xFF);
			if ((i & 0x1F) == 0) toggleWd();
		}
		// 2 dummy CRC bytes
		spi.send(0xFF);
		spi.send(0xFF);
		// Check data response token
		int resp = spi.receive() & 0x1F;
		if (resp != 0x05) {
			spi.csDeassert();
			return false;
		}
		// Wait for busy (MISO held low by card)
		for (int i = 0; i < 500000; i++) {
			if (spi.receive() == 0xFF) break;
			if ((i & 0xFFFF) == 0) toggleWd();
		}
		spi.csDeassert();
		spi.send(0xFF);
		return true;
	}
}
