package com.jopdesign.net;

/**
 * IPv4 layer: send/receive, header checksum, protocol dispatch.
 *
 * IP header layout (at ETH_HEADER offset in packet):
 *   +0  version/IHL  (1 byte)
 *   +1  DSCP/ECN     (1 byte)
 *   +2  total length (2 bytes, big-endian)
 *   +4  identification (2 bytes)
 *   +6  flags/fragment offset (2 bytes)
 *   +8  TTL          (1 byte)
 *   +9  protocol     (1 byte)
 *   +10 header checksum (2 bytes)
 *   +12 source IP    (4 bytes)
 *   +16 destination IP (4 bytes)
 */
public class IP {

	/** IP header offset within Ethernet frame. */
	public static final int IP_OFF = EthDriver.ETH_HEADER;

	/** Minimum IP header length in bytes. */
	public static final int IP_HEADER_LEN = 20;

	/** Protocol numbers. */
	public static final int PROTO_ICMP = 1;
	public static final int PROTO_TCP = 6;
	public static final int PROTO_UDP = 17;

	/** Packet identification counter. */
	private static int packetId = 0;

	/**
	 * Send an IP packet. The caller should have filled in the IP payload
	 * (starting at IP_OFF + IP_HEADER_LEN). This method fills in the IP
	 * header and Ethernet header, does ARP resolution, and sends.
	 *
	 * @param pkt the packet (payload must already be in place)
	 * @param destIp destination IP address
	 * @param protocol IP protocol number (PROTO_ICMP, PROTO_TCP, PROTO_UDP)
	 * @param dataLen length of IP payload (data after IP header)
	 * @return true if sent, false if ARP not yet resolved
	 */
	public static boolean send(Packet pkt, int destIp, int protocol, int dataLen) {
		int totalLen = IP_HEADER_LEN + dataLen;

		// Fill IP header
		pkt.setByte(IP_OFF + 0, 0x45);        // IPv4, IHL=5
		pkt.setByte(IP_OFF + 1, 0x00);        // DSCP/ECN
		pkt.setShort(IP_OFF + 2, totalLen);    // Total length
		pkt.setShort(IP_OFF + 4, packetId++);  // Identification
		pkt.setShort(IP_OFF + 6, 0x4000);      // Don't Fragment
		pkt.setByte(IP_OFF + 8, NetConfig.IP_TTL);
		pkt.setByte(IP_OFF + 9, protocol);
		pkt.setShort(IP_OFF + 10, 0);          // Checksum placeholder
		pkt.setInt(IP_OFF + 12, NetConfig.ip);  // Source IP
		pkt.setInt(IP_OFF + 16, destIp);        // Destination IP

		// Calculate and set IP header checksum
		int cksum = checksumIpHeader(pkt);
		pkt.setShort(IP_OFF + 10, cksum);

		// Ethernet header
		EthDriver.setSrcMac(pkt);
		EthDriver.setEtherType(pkt, EthDriver.ETHERTYPE_IP);

		// ARP resolution for next hop
		int nextHop = NetConfig.nextHop(destIp);
		if (!Arp.resolve(pkt, nextHop)) {
			return false;
		}

		pkt.len = EthDriver.ETH_HEADER + totalLen;
		if (pkt.len < 60) pkt.len = 60;
		EthDriver.send(pkt);
		return true;
	}

	/**
	 * Send an IP packet to broadcast (255.255.255.255) with a caller-specified
	 * source IP address and broadcast Ethernet MAC. Used by DHCP which must
	 * send from 0.0.0.0 before we have an IP address.
	 *
	 * @param pkt the packet (payload must already be in place)
	 * @param srcIp source IP address (e.g. 0 for DHCP DISCOVER)
	 * @param protocol IP protocol number
	 * @param dataLen length of IP payload
	 */
	public static void sendBroadcast(Packet pkt, int srcIp, int protocol, int dataLen) {
		int totalLen = IP_HEADER_LEN + dataLen;

		// Fill IP header
		pkt.setByte(IP_OFF + 0, 0x45);
		pkt.setByte(IP_OFF + 1, 0x00);
		pkt.setShort(IP_OFF + 2, totalLen);
		pkt.setShort(IP_OFF + 4, packetId++);
		pkt.setShort(IP_OFF + 6, 0x4000);      // Don't Fragment
		pkt.setByte(IP_OFF + 8, NetConfig.IP_TTL);
		pkt.setByte(IP_OFF + 9, protocol);
		pkt.setShort(IP_OFF + 10, 0);           // Checksum placeholder
		pkt.setInt(IP_OFF + 12, srcIp);
		pkt.setInt(IP_OFF + 16, 0xFFFFFFFF);    // Broadcast destination

		int cksum = checksumIpHeader(pkt);
		pkt.setShort(IP_OFF + 10, cksum);

		// Broadcast Ethernet header
		EthDriver.setDstMac(pkt, EthDriver.BCAST_MAC_HI, EthDriver.BCAST_MAC_LO);
		EthDriver.setSrcMac(pkt);
		EthDriver.setEtherType(pkt, EthDriver.ETHERTYPE_IP);

		pkt.len = EthDriver.ETH_HEADER + totalLen;
		if (pkt.len < 60) pkt.len = 60;
		EthDriver.send(pkt);
	}

	/**
	 * Process a received IP packet. Validates header, dispatches to
	 * ICMP/TCP/UDP handlers.
	 *
	 * @param pkt the received Ethernet frame
	 */
	public static void receive(Packet pkt) {
		// Validate minimum length
		if (pkt.len < IP_OFF + IP_HEADER_LEN) return;

		int versionIhl = pkt.getByte(IP_OFF + 0);
		if ((versionIhl >>> 4) != 4) return;
		int ihl = (versionIhl & 0x0F) * 4;
		if (ihl < IP_HEADER_LEN) return;

		int totalLen = pkt.getShort(IP_OFF + 2);
		if (totalLen < ihl) return;

		// Verify destination is us or broadcast
		int destIp = pkt.getInt(IP_OFF + 16);
		if (destIp != NetConfig.ip && destIp != 0xFFFFFFFF) {
			if (NetConfig.ip != 0 && destIp == (NetConfig.ip | ~NetConfig.mask)) {
				// Subnet broadcast — accept
			} else if (NetConfig.dhcpActive
					&& (destIp == NetConfig.dhcpOfferedIp || NetConfig.ip == 0)) {
				// DHCP: accept unicast to offered IP, or anything when no IP
			} else {
				return;
			}
		}

		// Verify header checksum
		if (checksumIpHeader(pkt) != 0) return;

		// Learn sender's MAC/IP for ARP cache (so we can reply)
		int srcIp = pkt.getInt(IP_OFF + 12);
		if (srcIp != 0) {
			int srcMacHi = EthDriver.getSrcMacHi(pkt);
			int srcMacLo = EthDriver.getSrcMacLo(pkt);
			Arp.learnFrom(srcIp, srcMacHi, srcMacLo);
		}

		int protocol = pkt.getByte(IP_OFF + 9);
		int payloadOff = IP_OFF + ihl;
		int payloadLen = totalLen - ihl;

		switch (protocol) {
			case PROTO_ICMP:
				ICMP.receive(pkt, payloadOff, payloadLen);
				break;
			case PROTO_TCP:
				TCP.receive(pkt, payloadOff, payloadLen);
				break;
			case PROTO_UDP:
				UDP.receive(pkt, payloadOff, payloadLen);
				break;
		}
	}

	/**
	 * Calculate IP header checksum. Returns 0 if the existing checksum
	 * is valid; returns the correct checksum to set if checksum field is 0.
	 */
	public static int checksumIpHeader(Packet pkt) {
		int ihl = (pkt.getByte(IP_OFF + 0) & 0x0F) * 4;
		int sum = 0;
		for (int i = 0; i < ihl; i += 2) {
			sum += pkt.getShort(IP_OFF + i);
		}
		// Fold carries
		while ((sum >>> 16) != 0) {
			sum = (sum & 0xFFFF) + (sum >>> 16);
		}
		return (~sum) & 0xFFFF;
	}

	/**
	 * Calculate TCP/UDP pseudo-header checksum contribution.
	 * Used by TCP and UDP checksum calculations.
	 */
	public static int pseudoHeaderChecksum(Packet pkt, int protocol, int transportLen) {
		int srcIp = pkt.getInt(IP_OFF + 12);
		int dstIp = pkt.getInt(IP_OFF + 16);
		int sum = 0;
		sum += (srcIp >>> 16) & 0xFFFF;
		sum += srcIp & 0xFFFF;
		sum += (dstIp >>> 16) & 0xFFFF;
		sum += dstIp & 0xFFFF;
		sum += protocol;
		sum += transportLen;
		return sum;
	}

	/** Get source IP from a received packet. */
	public static int getSrcIp(Packet pkt) {
		return pkt.getInt(IP_OFF + 12);
	}

	/** Get destination IP from a received packet. */
	public static int getDstIp(Packet pkt) {
		return pkt.getInt(IP_OFF + 16);
	}

	/** Get IP protocol from a received packet. */
	public static int getProtocol(Packet pkt) {
		return pkt.getByte(IP_OFF + 9);
	}
}
