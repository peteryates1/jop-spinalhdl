package com.jopdesign.net;

import com.jopdesign.sys.Const;

/**
 * Static network configuration.
 *
 * All addresses stored as 32-bit ints. IP addresses in network byte order
 * (big-endian), e.g. 192.168.0.123 = 0xC0A8007B. MAC addresses split
 * into high 16 bits and low 32 bits.
 */
public class NetConfig {

	/** Our MAC address high 16 bits (e.g. 0x024A for 02:4a:...). This unit's,
	 *  from its board instance via Const (status item 174); 02:00:00:00:00:01,
	 *  shared, on a board no instance covers yet. */
	public static int macHi = Const.ETH_MAC_HI;

	/** Our MAC address low 32 bits (e.g. 0x4F500002 for ...:4f:50:00:02) */
	public static int macLo = Const.ETH_MAC_LO;

	/** Our hostname, sent in DHCP requests (option 12); empty = send none.
	 *  The board instance's name, via Const. */
	public static String hostname = Const.ETH_HOSTNAME;

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

	/** Enable DHCP client (default: static IP) */
	public static boolean useDhcp = false;

	/** True while DHCP discovery is in progress */
	public static boolean dhcpActive = false;

	/** DNS server IP address (set by DHCP option 6, or manually) */
	public static int dnsServer = 0;

	/** IP offered by DHCP server (for receive filter during discovery) */
	public static int dhcpOfferedIp = 0;

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
