package com.jopdesign.hw;

import com.jopdesign.sys.Const;

/**
 * Hardware object for the MDIO PHY management controller (BmbMdio).
 *
 * IEEE 802.3 clause 22 MDIO for PHY register access. Also provides
 * PHY hardware reset control and Ethernet interrupt management.
 *
 * Fields map to sequential I/O registers at Const.IO_MDIO:
 *   +0  command      R: bit0=busy  W: bit0=go, bit1=write(1)/read(0)
 *   +1  data         R: read data[15:0]  W: write data[15:0]
 *   +2  address      W: regAddr[4:0], phyAddr[9:5]
 *   +3  phyReset     W: bit0=reset (active-high register, active-low output)
 *   +4  intControl   R: interrupt pending  W: interrupt enable mask
 */
public final class Mdio extends HardwareObject {

	// Command bits
	public static final int CMD_GO    = 1 << 0;
	public static final int CMD_WRITE = 1 << 1;

	// Interrupt bits (addr 4)
	public static final int INT_ETH_RX   = 1 << 0;
	public static final int INT_ETH_TX   = 1 << 1;
	public static final int INT_MDIO     = 1 << 2;

	// Singleton
	private static Mdio instance = null;

	public static Mdio getInstance() {
		if (instance == null)
			instance = (Mdio) make(new Mdio(), Const.IO_MDIO, 0);
		return instance;
	}

	// --- Hardware registers (sequential, volatile) ---

	/** +0: R=busy, W=go/write command */
	public volatile int command;
	/** +1: R=read data[15:0], W=write data[15:0] */
	public volatile int data;
	/** +2: W=regAddr[4:0] | (phyAddr[4:0] << 5) */
	public volatile int address;
	/** +3: W=PHY reset (bit0: 1=assert, 0=release) */
	public volatile int phyReset;
	/** +4: R=interrupt pending, W=interrupt enable mask */
	public volatile int intControl;

	// --- Convenience methods ---

	/** Return true if an MDIO operation is in progress. */
	public boolean isBusy() {
		return (command & 1) != 0;
	}

	/** Wait for current MDIO operation to complete. */
	public void waitReady() {
		for (int i = 0; i < 100000; i++) {
			if (!isBusy()) return;
		}
	}

	/** Set PHY and register address for next operation. */
	public void setAddress(int phyAddr, int regAddr) {
		address = (regAddr & 0x1F) | ((phyAddr & 0x1F) << 5);
	}

	/** Read a PHY register. Returns 16-bit value, or -1 on timeout. */
	public int read(int phyAddr, int regAddr) {
		setAddress(phyAddr, regAddr);
		command = CMD_GO;  // go=1, write=0
		for (int i = 0; i < 100000; i++) {
			if (!isBusy()) return data & 0xFFFF;
		}
		return -1;
	}

	/** Write a PHY register. Returns true on success. */
	public boolean write(int phyAddr, int regAddr, int value) {
		setAddress(phyAddr, regAddr);
		data = value & 0xFFFF;
		command = CMD_GO | CMD_WRITE;  // go=1, write=1
		for (int i = 0; i < 100000; i++) {
			if (!isBusy()) return true;
		}
		return false;
	}

	/** Assert PHY hardware reset. */
	public void assertReset() {
		phyReset = 1;
	}

	/** Release PHY hardware reset. */
	public void releaseReset() {
		phyReset = 0;
	}
}
