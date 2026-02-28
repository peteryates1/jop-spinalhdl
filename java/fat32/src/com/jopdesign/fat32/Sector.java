package com.jopdesign.fat32;

/**
 * 512-byte sector buffer with byte-level accessors and dirty tracking.
 *
 * Words are stored in big-endian order (first byte at bits [31:24] of word 0),
 * matching the SD FIFO format used by both SdNative and SdSpi block devices.
 */
public class Sector {
	public final int[] buf;
	public int sectorNum;
	public boolean dirty;
	public boolean valid;

	public Sector() {
		buf = new int[128];
		sectorNum = -1;
		dirty = false;
		valid = false;
	}

	/** Extract byte at offset (0-511) from buffer. */
	public int getByte(int off) {
		return (buf[off >> 2] >>> (24 - (off & 3) * 8)) & 0xFF;
	}

	/** Read little-endian 16-bit value at byte offset. */
	public int getLE16(int off) {
		return getByte(off) | (getByte(off + 1) << 8);
	}

	/** Read little-endian 32-bit value at byte offset. */
	public int getLE32(int off) {
		return getByte(off) | (getByte(off + 1) << 8)
			| (getByte(off + 2) << 16) | (getByte(off + 3) << 24);
	}

	/** Set byte at offset (0-511) in buffer, marks dirty. */
	public void setByte(int off, int val) {
		int wordIdx = off >> 2;
		int shift = 24 - (off & 3) * 8;
		buf[wordIdx] = (buf[wordIdx] & ~(0xFF << shift)) | ((val & 0xFF) << shift);
		dirty = true;
	}

	/** Write little-endian 16-bit value at byte offset, marks dirty. */
	public void setLE16(int off, int val) {
		setByte(off, val & 0xFF);
		setByte(off + 1, (val >> 8) & 0xFF);
	}

	/** Write little-endian 32-bit value at byte offset, marks dirty. */
	public void setLE32(int off, int val) {
		setByte(off, val & 0xFF);
		setByte(off + 1, (val >> 8) & 0xFF);
		setByte(off + 2, (val >> 16) & 0xFF);
		setByte(off + 3, (val >> 24) & 0xFF);
	}

	/** Load sector from device, clears dirty flag. */
	public boolean load(BlockDevice dev, int sector) {
		if (valid && sectorNum == sector) return true;
		if (!flush(dev)) return false;
		if (!dev.readBlock(sector, buf)) {
			valid = false;
			return false;
		}
		sectorNum = sector;
		dirty = false;
		valid = true;
		return true;
	}

	/** Flush dirty sector to device. */
	public boolean flush(BlockDevice dev) {
		if (dirty && valid) {
			if (!dev.writeBlock(sectorNum, buf)) return false;
			dirty = false;
		}
		return true;
	}

	/** Invalidate buffer (force re-read on next load). */
	public void invalidate() {
		valid = false;
		dirty = false;
		sectorNum = -1;
	}
}
