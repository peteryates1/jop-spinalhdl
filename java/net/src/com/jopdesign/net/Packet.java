package com.jopdesign.net;

/**
 * Network frame buffer with byte-level accessors.
 *
 * Stores Ethernet frames as int[] in little-endian byte order within each
 * 32-bit word, matching the BmbEth hardware format:
 *   word[0] bits [7:0]   = byte 0
 *   word[0] bits [15:8]  = byte 1
 *   word[0] bits [23:16] = byte 2
 *   word[0] bits [31:24] = byte 3
 *
 * Statically allocated pool of MAX_PACKETS buffers.
 */
public class Packet {

	/** Maximum Ethernet frame size in bytes. */
	public static final int MAX_BYTES = 1536;

	/** Maximum frame size in 32-bit words. */
	public static final int MAX_WORDS = MAX_BYTES / 4;

	/** Frame data (little-endian byte order within each word). */
	public final int[] buf;

	/** Frame length in bytes (set by receive, or by sender before send). */
	public int len;

	/** True if this packet is currently in use (allocated from pool). */
	public boolean inUse;

	// --- Static packet pool ---

	private static final Packet[] pool = new Packet[NetConfig.MAX_PACKETS];
	private static boolean poolInit = false;

	public Packet() {
		buf = new int[MAX_WORDS];
		len = 0;
		inUse = false;
	}

	/** Initialize the packet pool. Call once at startup. */
	public static void initPool() {
		for (int i = 0; i < NetConfig.MAX_PACKETS; i++) {
			pool[i] = new Packet();
		}
		poolInit = true;
	}

	/** Allocate a packet from the pool. Returns null if none available. */
	public static Packet alloc() {
		for (int i = 0; i < NetConfig.MAX_PACKETS; i++) {
			if (!pool[i].inUse) {
				pool[i].inUse = true;
				pool[i].len = 0;
				return pool[i];
			}
		}
		return null;
	}

	/** Return a packet to the pool. */
	public void free() {
		inUse = false;
	}

	// --- Byte-level accessors (little-endian word storage) ---

	/** Get byte at frame offset. */
	public int getByte(int off) {
		return (buf[off >> 2] >>> ((off & 3) * 8)) & 0xFF;
	}

	/** Set byte at frame offset. */
	public void setByte(int off, int val) {
		int wordIdx = off >> 2;
		int shift = (off & 3) * 8;
		buf[wordIdx] = (buf[wordIdx] & ~(0xFF << shift)) | ((val & 0xFF) << shift);
	}

	/**
	 * Get 16-bit value at frame offset in network byte order (big-endian).
	 * byte[off] is the high byte, byte[off+1] is the low byte.
	 */
	public int getShort(int off) {
		return (getByte(off) << 8) | getByte(off + 1);
	}

	/**
	 * Set 16-bit value at frame offset in network byte order (big-endian).
	 */
	public void setShort(int off, int val) {
		setByte(off, (val >>> 8) & 0xFF);
		setByte(off + 1, val & 0xFF);
	}

	/**
	 * Get 32-bit value at frame offset in network byte order (big-endian).
	 * byte[off] is the most significant byte.
	 */
	public int getInt(int off) {
		return (getByte(off) << 24) | (getByte(off + 1) << 16)
			| (getByte(off + 2) << 8) | getByte(off + 3);
	}

	/**
	 * Set 32-bit value at frame offset in network byte order (big-endian).
	 */
	public void setInt(int off, int val) {
		setByte(off, (val >>> 24) & 0xFF);
		setByte(off + 1, (val >>> 16) & 0xFF);
		setByte(off + 2, (val >>> 8) & 0xFF);
		setByte(off + 3, val & 0xFF);
	}

	/**
	 * Copy bytes from this packet to a byte array.
	 */
	public void copyTo(int srcOff, byte[] dst, int dstOff, int count) {
		for (int i = 0; i < count; i++) {
			dst[dstOff + i] = (byte) getByte(srcOff + i);
		}
	}

	/**
	 * Copy bytes from a byte array into this packet.
	 */
	public void copyFrom(byte[] src, int srcOff, int dstOff, int count) {
		for (int i = 0; i < count; i++) {
			setByte(dstOff + i, src[srcOff + i] & 0xFF);
		}
	}
}
