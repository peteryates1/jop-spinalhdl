package com.jopdesign.fat32;

/**
 * FAT32 FSInfo sector: tracks free cluster count and next-free hint.
 */
public class FsInfo {
	/** Free cluster count (0xFFFFFFFF if unknown). */
	public int freeCount;
	/** Next free cluster hint. */
	public int nextFree;
	/** Sector number of the FSInfo sector. */
	public int sectorNum;

	private BlockDevice dev;

	public FsInfo(BlockDevice dev) {
		this.dev = dev;
		freeCount = -1;
		nextFree = 2;
	}

	/** Load FSInfo from the given sector. Returns true on success. */
	public boolean load(int sector) {
		sectorNum = sector;
		Sector s = new Sector();
		if (!s.load(dev, sector)) return false;

		// Validate signatures
		int sig1 = s.getLE32(0);    // 0x41615252
		int sig2 = s.getLE32(484);  // 0x61417272
		int sig3 = s.getLE16(510);  // 0xAA55
		if (sig1 != 0x41615252 || sig2 != 0x61417272 || sig3 != 0xAA55) {
			return false;
		}

		freeCount = s.getLE32(488);
		nextFree = s.getLE32(492);
		if (nextFree < 2) nextFree = 2;
		return true;
	}

	/** Write FSInfo back to disk. Re-reads sector first since buffer is shared. */
	public boolean flush() {
		Sector s = new Sector();
		if (!s.load(dev, sectorNum)) return false;
		s.setLE32(488, freeCount);
		s.setLE32(492, nextFree);
		return s.flush(dev);
	}
}
