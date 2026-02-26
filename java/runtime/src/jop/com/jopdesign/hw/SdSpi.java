package com.jopdesign.hw;

import com.jopdesign.sys.Const;

/**
 * Hardware object for the SPI-mode SD card controller (BmbSdSpi).
 *
 * Raw SPI master for SD cards. Hardware handles byte-level SPI shifting
 * (Mode 0: CPOL=0, CPHA=0, MSB first) and chip-select control.
 * Software drives the SD protocol (CMD0, CMD8, ACMD41, etc.).
 *
 * SPI_CLK = sys_clk / (2 * (divider + 1)).
 *
 * Fields map to sequential I/O registers at Const.IO_SD_SPI:
 *   +0  statusControl  R: status  W: control (csAssert, intEnable)
 *   +1  txRxData       R: RX byte[7:0]  W: TX byte[7:0] (starts transfer)
 *   +2  clockDivider   R/W: clock divider[15:0]
 */
public final class SdSpi extends HardwareObject {

	// Status bits (read)
	public static final int STATUS_BUSY         = 1 << 0;
	public static final int STATUS_CARD_PRESENT = 1 << 1;
	public static final int STATUS_INT_ENABLE   = 1 << 2;

	// Control bits (write)
	public static final int CTRL_CS_ASSERT  = 1 << 0;
	public static final int CTRL_INT_ENABLE = 1 << 1;

	// Singleton
	private static SdSpi instance = null;

	public static SdSpi getInstance() {
		if (instance == null)
			instance = (SdSpi) make(new SdSpi(), Const.IO_SD_SPI, 0);
		return instance;
	}

	// --- Hardware registers (sequential, volatile) ---

	/** +0: R=status (busy/cardPresent/intEnable), W=control (csAssert/intEnable) */
	public volatile int statusControl;
	/** +1: R=RX byte[7:0], W=TX byte[7:0] (starts 8-bit SPI transfer) */
	public volatile int txRxData;
	/** +2: R/W clock divider[15:0] */
	public volatile int clockDivider;

	// --- Convenience methods ---

	/** Return true if an SPI transfer is in progress. */
	public boolean isBusy() {
		return (statusControl & STATUS_BUSY) != 0;
	}

	/** Return true if an SD card is inserted. */
	public boolean isCardPresent() {
		return (statusControl & STATUS_CARD_PRESENT) != 0;
	}

	/** Assert chip select (CS driven low). */
	public void csAssert() {
		statusControl = CTRL_CS_ASSERT;
	}

	/** Deassert chip select (CS driven high). */
	public void csDeassert() {
		statusControl = 0;
	}

	/** Transfer one byte (send and receive simultaneously). */
	public int transfer(int txByte) {
		txRxData = txByte & 0xFF;
		while (isBusy()) { }
		return txRxData & 0xFF;
	}

	/** Send a byte, ignore received data. */
	public void send(int txByte) {
		txRxData = txByte & 0xFF;
		while (isBusy()) { }
	}

	/** Receive a byte (sends 0xFF). */
	public int receive() {
		return transfer(0xFF);
	}

	/** Set SPI clock divider. SPI_CLK = sys_clk / (2 * (div + 1)). */
	public void setClockDivider(int div) {
		clockDivider = div & 0xFFFF;
	}
}
