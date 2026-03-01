package com.jopdesign.net;

/**
 * Static network configuration.
 *
 * All addresses stored as 32-bit ints. IP addresses in network byte order
 * (big-endian), e.g. 192.168.0.123 = 0xC0A8007B. MAC addresses split
 * into high 16 bits and low 32 bits.
 */
public class NetConfig {

	/** Our MAC address high 16 bits (e.g. 0x0200 for 02:00:...) */
	public static int macHi = 0x0200;

	/** Our MAC address low 32 bits (e.g. 0x00000001 for ...:00:00:00:01) */
	public static int macLo = 0x00000001;

	/** Our IP address (default 192.168.0.123) */
	public static int ip = (192 << 24) | (168 << 16) | (0 << 8) | 123;

	/** Subnet mask (default 255.255.255.0) */
	public static int mask = (255 << 24) | (255 << 16) | (255 << 8) | 0;

	/** Gateway IP (default 192.168.0.1) */
	public static int gateway = (192 << 24) | (168 << 16) | (0 << 8) | 1;

	/** Maximum number of packet buffers */
	public static final int MAX_PACKETS = 8;

	/** Maximum number of UDP connections */
	public static final int MAX_UDP_CONN = 4;

	/** Maximum number of TCP connections */
	public static final int MAX_TCP_CONN = 4;

	/** IP TTL for outgoing packets */
	public static final int IP_TTL = 64;

	/** TCP maximum segment size (data bytes, excl. headers) */
	public static final int TCP_MSS = 1460;

	/** TCP receive window size */
	public static final int TCP_WINDOW = 4096;

	/** TCP retransmit timeout in milliseconds */
	public static final int TCP_RETRANSMIT_TIMEOUT = 1000;

	/** TCP maximum retransmits before giving up */
	public static final int TCP_MAX_RETRANSMITS = 8;

	/** ARP cache size */
	public static final int ARP_CACHE_SIZE = 8;

	/** ARP request timeout in poll cycles */
	public static final int ARP_TIMEOUT = 2000;

	/** Check if an IP is on our local subnet. */
	public static boolean isLocal(int destIp) {
		return (destIp & mask) == (ip & mask);
	}

	/** Get the next-hop IP for a destination (direct or gateway). */
	public static int nextHop(int destIp) {
		if (isLocal(destIp)) {
			return destIp;
		}
		return gateway;
	}

	/**
	 * Get MAC byte at position 0-5 (0 = most significant).
	 * MAC is stored as macHi[15:0] = bytes 0,1 and macLo[31:0] = bytes 2,3,4,5.
	 */
	public static int getMacByte(int pos) {
		if (pos == 0) return (macHi >>> 8) & 0xFF;
		if (pos == 1) return macHi & 0xFF;
		return (macLo >>> (8 * (5 - pos))) & 0xFF;
	}
}
