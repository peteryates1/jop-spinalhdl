package com.jopdesign.fat32;

import java.io.OutputStream;
import java.io.IOException;

/**
 * OutputStream for writing a FAT32 file sector-by-sector.
 * Extends the cluster chain as needed. close() is mandatory
 * to flush the final sector and update the directory entry.
 */
public class Fat32OutputStream extends OutputStream {
	private Fat32FileSystem fs;
	private BlockDevice dev;
	private int[] buf;
	private int currentCluster;
	private int sectorInCluster;
	private int bufPos; // byte position within buf (0-511)
	private int fileSize;
	private int sectorsPerCluster;
	private DirEntry entry;
	private boolean closed;

	Fat32OutputStream(Fat32FileSystem fs, BlockDevice dev, DirEntry entry) throws IOException {
		this.fs = fs;
		this.dev = dev;
		this.buf = new int[128];
		this.currentCluster = entry.getStartCluster();
		this.sectorInCluster = 0;
		this.bufPos = 0;
		this.fileSize = 0;
		this.sectorsPerCluster = fs.getSectorsPerCluster();
		this.entry = entry;
		this.closed = false;
		// Clear buffer
		for (int i = 0; i < 128; i++) buf[i] = 0;
	}

	public void write(int b) throws IOException {
		if (closed) throw new IOException("stream closed");

		// Pack byte into big-endian buffer
		int wordIdx = bufPos >> 2;
		int shift = 24 - (bufPos & 3) * 8;
		buf[wordIdx] = (buf[wordIdx] & ~(0xFF << shift)) | ((b & 0xFF) << shift);
		bufPos++;
		fileSize++;

		// Flush full sector
		if (bufPos >= 512) {
			flushBuffer();
		}
	}

	/** Flush the current buffer to disk and advance to next sector. */
	private void flushBuffer() throws IOException {
		int sector = fs.clusterToSector(currentCluster) + sectorInCluster;
		if (!dev.writeBlock(sector, buf)) {
			throw new IOException("write error");
		}
		// Clear buffer for next sector
		for (int i = 0; i < 128; i++) buf[i] = 0;
		bufPos = 0;

		// Advance to next sector
		sectorInCluster++;
		if (sectorInCluster >= sectorsPerCluster) {
			sectorInCluster = 0;
			// Need a new cluster
			int newCluster = fs.extendChain(currentCluster);
			if (newCluster == -1) {
				throw new IOException("disk full");
			}
			currentCluster = newCluster;
		}
	}

	public void flush() throws IOException {
		// Don't flush partial sector here — that's done in close()
	}

	/**
	 * Close the stream. Flushes the final partial sector, updates
	 * the directory entry's file size, and flushes FAT/FSInfo.
	 */
	public void close() throws IOException {
		if (closed) return;
		closed = true;

		// Flush final partial sector (pad remainder with zeros)
		if (bufPos > 0) {
			int sector = fs.clusterToSector(currentCluster) + sectorInCluster;
			if (!dev.writeBlock(sector, buf)) {
				throw new IOException("write error");
			}
		}

		// Update directory entry with final file size
		entry.fileSize = fileSize;
		if (!fs.updateDirEntry(entry)) {
			throw new IOException("dir update error");
		}

		// Flush FAT and FSInfo
		if (!fs.flush()) {
			throw new IOException("flush error");
		}
	}
}
