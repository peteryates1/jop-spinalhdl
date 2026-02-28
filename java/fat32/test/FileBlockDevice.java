package com.jopdesign.fat32;

import java.io.RandomAccessFile;
import java.io.IOException;

/**
 * BlockDevice backed by a file (disk image) for simulation testing.
 * Reads/writes 512-byte sectors using RandomAccessFile.
 */
public class FileBlockDevice implements BlockDevice {
	private RandomAccessFile raf;

	public FileBlockDevice(String path) throws IOException {
		raf = new RandomAccessFile(path, "rw");
	}

	public boolean init() {
		return raf != null;
	}

	public boolean readBlock(int sectorNum, int[] buf) {
		try {
			raf.seek((long) sectorNum * 512);
			byte[] raw = new byte[512];
			int n = raf.read(raw);
			if (n < 512) {
				// Pad with zeros if short read
				for (int i = n; i < 512; i++) raw[i] = 0;
			}
			// Pack into int[128] big-endian (first byte at bits [31:24])
			for (int i = 0; i < 128; i++) {
				buf[i] = ((raw[i * 4] & 0xFF) << 24)
					   | ((raw[i * 4 + 1] & 0xFF) << 16)
					   | ((raw[i * 4 + 2] & 0xFF) << 8)
					   | (raw[i * 4 + 3] & 0xFF);
			}
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	public boolean writeBlock(int sectorNum, int[] buf) {
		try {
			raf.seek((long) sectorNum * 512);
			byte[] raw = new byte[512];
			// Unpack from int[128] big-endian
			for (int i = 0; i < 128; i++) {
				raw[i * 4]     = (byte) ((buf[i] >> 24) & 0xFF);
				raw[i * 4 + 1] = (byte) ((buf[i] >> 16) & 0xFF);
				raw[i * 4 + 2] = (byte) ((buf[i] >> 8) & 0xFF);
				raw[i * 4 + 3] = (byte) (buf[i] & 0xFF);
			}
			raf.write(raw);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	public void close() {
		try { if (raf != null) raf.close(); } catch (IOException e) { }
	}
}
