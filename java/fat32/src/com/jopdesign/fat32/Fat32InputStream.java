package com.jopdesign.fat32;

import java.io.InputStream;
import java.io.IOException;

/**
 * InputStream for reading a FAT32 file sector-by-sector.
 * Follows the FAT chain to read consecutive clusters.
 */
public class Fat32InputStream extends InputStream {
	private Fat32FileSystem fs;
	private BlockDevice dev;
	private Sector sector;
	private int currentCluster;
	private int sectorInCluster;
	private int posInSector;
	private int bytesRemaining;
	private int sectorsPerCluster;
	private boolean eof;

	Fat32InputStream(Fat32FileSystem fs, BlockDevice dev, DirEntry entry) throws IOException {
		this.fs = fs;
		this.dev = dev;
		this.sector = new Sector();
		this.currentCluster = entry.getStartCluster();
		this.sectorInCluster = 0;
		this.posInSector = 0;
		this.bytesRemaining = entry.fileSize;
		this.sectorsPerCluster = fs.getSectorsPerCluster();
		this.eof = (bytesRemaining <= 0 || currentCluster < 2);

		if (!eof) {
			if (!sector.load(dev, fs.clusterToSector(currentCluster))) {
				throw new IOException("read error");
			}
		}
	}

	public int read() throws IOException {
		if (eof || bytesRemaining <= 0) return -1;

		int b = sector.getByte(posInSector);
		posInSector++;
		bytesRemaining--;

		if (bytesRemaining <= 0) {
			eof = true;
			return b;
		}

		// Advance to next sector if needed
		if (posInSector >= 512) {
			posInSector = 0;
			sectorInCluster++;
			if (sectorInCluster >= sectorsPerCluster) {
				// Follow FAT chain
				sectorInCluster = 0;
				currentCluster = fs.readFatEntry(currentCluster);
				if (currentCluster < 2 || currentCluster >= 0x0FFFFFF8) {
					eof = true;
					return b;
				}
			}
			int sec = fs.clusterToSector(currentCluster) + sectorInCluster;
			if (!sector.load(dev, sec)) {
				throw new IOException("read error");
			}
		}

		return b;
	}

	public int available() {
		return bytesRemaining > 0 ? bytesRemaining : 0;
	}

	public void close() {
		eof = true;
	}
}
