package com.jopdesign.fat32;

/**
 * Random-access reader for a FAT32 file.
 *
 * Caches the current FAT chain position and SD sector for efficient
 * sequential and near-sequential reads (common for ATR disk images
 * where the Atari reads sectors 1, 2, 3, ... in order).
 *
 * Supports forward and backward seeking. Forward seeks follow the FAT
 * chain incrementally; backward seeks restart from the beginning.
 */
public class Fat32RandomAccessFile {

	private Fat32FileSystem fs;
	private BlockDevice dev;
	private int startCluster;
	private int fileSize;
	private int sectorsPerCluster;

	// Cached chain position
	private int cachedClusterIdx;   // index in cluster chain (0-based)
	private int cachedClusterNum;   // actual cluster number at that index

	// Cached sector data
	private Sector sector;

	/**
	 * Open a file for random-access reading.
	 *
	 * @param fs   Mounted FAT32 filesystem
	 * @param dev  Block device
	 * @param entry Directory entry of the file
	 */
	public Fat32RandomAccessFile(Fat32FileSystem fs, BlockDevice dev, DirEntry entry) {
		this.fs = fs;
		this.dev = dev;
		this.startCluster = entry.getStartCluster();
		this.fileSize = entry.fileSize;
		this.sectorsPerCluster = fs.getSectorsPerCluster();
		this.cachedClusterIdx = 0;
		this.cachedClusterNum = startCluster;
		this.sector = new Sector();
	}

	/** Get file size in bytes. */
	public int getFileSize() {
		return fileSize;
	}

	/**
	 * Read bytes from the file at an arbitrary offset.
	 *
	 * @param fileOffset  Byte offset in the file
	 * @param dest        Destination buffer (word array, big-endian byte packing)
	 * @param destOff     Byte offset in dest to start writing
	 * @param len         Number of bytes to read
	 * @return Number of bytes actually read, or -1 on error
	 */
	public int readBytes(int fileOffset, int[] dest, int destOff, int len) {
		if (fileOffset < 0 || fileOffset >= fileSize) return -1;
		if (fileOffset + len > fileSize) len = fileSize - fileOffset;

		int bytesRead = 0;
		while (bytesRead < len) {
			// Which SD sector within the file?
			int fileSectorIdx = (fileOffset + bytesRead) / 512;
			int offsetInSector = (fileOffset + bytesRead) % 512;

			// Find the cluster for this file sector
			int clusterIdx = fileSectorIdx / sectorsPerCluster;
			int sectorInCluster = fileSectorIdx % sectorsPerCluster;

			int cluster = seekToCluster(clusterIdx);
			if (cluster < 2) return -1;  // FAT chain error

			// Compute LBA
			int lba = fs.clusterToSector(cluster) + sectorInCluster;

			// Load sector (uses cache)
			if (!sector.load(dev, lba)) return -1;

			// Copy bytes
			int avail = 512 - offsetInSector;
			int toCopy = len - bytesRead;
			if (toCopy > avail) toCopy = avail;

			for (int i = 0; i < toCopy; i++) {
				int b = sector.getByte(offsetInSector + i);
				setDestByte(dest, destOff + bytesRead + i, b);
			}
			bytesRead += toCopy;
		}
		return bytesRead;
	}

	/**
	 * Read a single byte from the file.
	 *
	 * @param fileOffset Byte offset in the file
	 * @return Byte value (0-255), or -1 on error
	 */
	public int readByte(int fileOffset) {
		if (fileOffset < 0 || fileOffset >= fileSize) return -1;

		int fileSectorIdx = fileOffset / 512;
		int offsetInSector = fileOffset % 512;

		int clusterIdx = fileSectorIdx / sectorsPerCluster;
		int sectorInCluster = fileSectorIdx % sectorsPerCluster;

		int cluster = seekToCluster(clusterIdx);
		if (cluster < 2) return -1;

		int lba = fs.clusterToSector(cluster) + sectorInCluster;
		if (!sector.load(dev, lba)) return -1;

		return sector.getByte(offsetInSector);
	}

	/**
	 * Seek to a cluster by index in the chain.
	 * Uses cached position for efficient forward seeks.
	 * Falls back to restart for backward seeks.
	 */
	private int seekToCluster(int targetIdx) {
		if (targetIdx == cachedClusterIdx) {
			return cachedClusterNum;
		}

		int idx;
		int cluster;

		if (targetIdx > cachedClusterIdx) {
			// Forward: continue from cached position
			idx = cachedClusterIdx;
			cluster = cachedClusterNum;
		} else {
			// Backward: restart from beginning
			idx = 0;
			cluster = startCluster;
		}

		while (idx < targetIdx) {
			cluster = fs.readFatEntry(cluster);
			if (cluster >= 0x0FFFFFF8 || cluster < 2) return -1;
			idx++;
		}

		cachedClusterIdx = idx;
		cachedClusterNum = cluster;
		return cluster;
	}

	/** Set byte at offset in a big-endian word array (same packing as Sector). */
	private static void setDestByte(int[] buf, int off, int val) {
		int wordIdx = off >> 2;
		int shift = 24 - (off & 3) * 8;
		buf[wordIdx] = (buf[wordIdx] & ~(0xFF << shift)) | ((val & 0xFF) << shift);
	}
}
