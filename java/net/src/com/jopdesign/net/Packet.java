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

	// --- Bulk paths ---
	//
	// One call per pass over the data, the accessors written out inline. On
	// JOP a call invalidates the object and array caches, so a byte accessor
	// costs about 1000 cycles a byte -- getShort per two bytes as much --
	// and a 1460-byte TCP segment that went through them once per pass took
	// about 100 ms to acknowledge (NetBench, 2026-11-10).

	/**
	 * Copy bytes from this packet to a byte array.
	 */
	public void copyTo(int srcOff, byte[] dst, int dstOff, int count) {
		int[] b = buf;
		int end = srcOff + count;
		for (int i = srcOff; i < end; i++) {
			dst[dstOff++] = (byte) (b[i >> 2] >>> ((i & 3) << 3));
		}
	}

	/**
	 * Copy bytes from a byte array into this packet.
	 */
	public void copyFrom(byte[] src, int srcOff, int dstOff, int count) {
		int[] b = buf;
		int end = dstOff + count;
		for (int i = dstOff; i < end; i++) {
			int w = i >> 2;
			int shift = (i & 3) << 3;
			b[w] = (b[w] & ~(0xFF << shift)) | ((src[srcOff++] & 0xFF) << shift);
		}
	}

	/**
	 * Copy count bytes of another packet, from srcOff, into this one at dstOff.
	 */
	public void copy(Packet src, int srcOff, int dstOff, int count) {
		int[] s = src.buf;
		int[] b = buf;
		int end = dstOff + count;
		for (int i = dstOff; i < end; i++) {
			int w = i >> 2;
			int shift = (i & 3) << 3;
			int v = (s[srcOff >> 2] >>> ((srcOff & 3) << 3)) & 0xFF;
			b[w] = (b[w] & ~(0xFF << shift)) | (v << shift);
			srcOff++;
		}
	}

	/**
	 * The Internet checksum's sum over bytes [off, off + len): the 16-bit
	 * big-endian words, a trailing odd byte as the high half of a word, its
	 * carries folded in or not -- the caller adds whatever else the checksum
	 * covers (a pseudo-header), folds the total and complements it. Zero
	 * exactly when every byte is.
	 *
	 * The words are summed as they are stored, little-endian, and the folded
	 * sum byte-swapped, which is the big-endian sum (RFC 1071, byte order
	 * independence): a word at a time where getShort took two bytes a call.
	 */
	public int sum16(int off, int len) {
		int sum = 0;
		if ((off & 1) != 0) {
			// No header this stack builds starts on an odd byte
			int i;
			for (i = 0; i + 1 < len; i += 2) {
				sum += getShort(off + i);
			}
			if (i < len) {
				sum += getByte(off + i) << 8;
			}
			return sum;
		}
		int[] b = buf;
		int w = off >> 2;
		int rest = len;
		if ((off & 2) != 0) {
			// Starts in the upper half of a word
			if (rest == 1) {
				return ((b[w] >>> 16) & 0xFF) << 8;
			}
			if (rest >= 2) {
				sum = b[w] >>> 16;
				w++;
				rest -= 2;
			}
		}
		while (rest >= 4) {
			int v = b[w];
			sum += (v & 0xFFFF) + (v >>> 16);
			w++;
			rest -= 4;
		}
		if (rest >= 2) {
			int v = b[w];
			sum += v & 0xFFFF;
			if (rest == 3) {
				sum += (v >>> 16) & 0xFF;
			}
		} else if (rest == 1) {
			sum += b[w] & 0xFF;
		}
		while ((sum >>> 16) != 0) {
			sum = (sum & 0xFFFF) + (sum >>> 16);
		}
		return ((sum & 0xFF) << 8) | (sum >>> 8);
	}
}
