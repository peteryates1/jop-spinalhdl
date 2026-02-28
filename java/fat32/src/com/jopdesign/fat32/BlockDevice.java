package com.jopdesign.fat32;

/**
 * Block device interface for 512-byte sector I/O.
 * Buffers use int[128] in big-endian word order (matching SD FIFO format).
 */
public interface BlockDevice {
	/** Initialize the device. Returns true on success. */
	boolean init();
	/** Read a 512-byte sector into buf (int[128]). Returns true on success. */
	boolean readBlock(int sectorNum, int[] buf);
	/** Write a 512-byte sector from buf (int[128]). Returns true on success. */
	boolean writeBlock(int sectorNum, int[] buf);
}
