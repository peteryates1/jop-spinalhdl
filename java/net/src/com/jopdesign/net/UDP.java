package com.jopdesign.net;

/**
 * UDP transport layer.
 *
 * UDP header layout (at transport offset within packet):
 *   +0  source port      (2 bytes)
 *   +2  destination port (2 bytes)
 *   +4  length           (2 bytes, header + data)
 *   +6  checksum         (2 bytes)
 *   +8  data
 */
public class UDP {

	/** UDP header length in bytes. */
	public static final int UDP_HEADER = 8;

	/**
	 * Process a received UDP packet.
	 *
	 * @param pkt the received Ethernet frame
	 * @param off offset of UDP header within packet
	 * @param len length of UDP segment (from IP)
	 */
	public static void receive(Packet pkt, int off, int len) {
		if (len < UDP_HEADER) return;

		int srcPort = pkt.getShort(off);
		int dstPort = pkt.getShort(off + 2);
		int udpLen = pkt.getShort(off + 4);
		int rxChecksum = pkt.getShort(off + 6);

		// Validate length
		if (udpLen < UDP_HEADER || udpLen > len) return;

		// Verify checksum (if non-zero)
		if (rxChecksum != 0) {
			if (checksum(pkt, off, udpLen) != 0) return;
		}

		int dataLen = udpLen - UDP_HEADER;

		// Intercept DHCP (port 68) and DNS responses before UDPConnection lookup.
		// Use useDhcp (not dhcpActive) so renewal ACKs are also intercepted.
		if (dstPort == 68 && NetConfig.useDhcp) {
			DHCP.receive(pkt, off + UDP_HEADER, dataLen);
			return;
		}
		if (DNS.pendingPort != 0 && dstPort == DNS.pendingPort) {
			DNS.receive(pkt, off + UDP_HEADER, dataLen);
			return;
		}

		// Find matching UDP connection
		UDPConnection conn = UDPConnection.findByPort(dstPort);
		if (conn != null) {
			conn.receiveData(pkt, off + UDP_HEADER, dataLen, IP.getSrcIp(pkt), srcPort);
		} else {
			// No listener — send ICMP port unreachable
			ICMP.sendDestUnreachable(pkt, ICMP.CODE_PORT_UNREACHABLE);
		}
	}

	/**
	 * Send a UDP packet.
	 *
	 * @param destIp destination IP address
	 * @param srcPort source port
	 * @param dstPort destination port
	 * @param data payload bytes
	 * @param dataOff offset into data array
	 * @param dataLen length of payload
	 * @return true if sent, false if ARP not resolved
	 */
	public static boolean send(int destIp, int srcPort, int dstPort,
			byte[] data, int dataOff, int dataLen) {
		Packet pkt = Packet.alloc();
		if (pkt == null) return false;

		int off = IP.IP_OFF + IP.IP_HEADER_LEN;
		int udpLen = UDP_HEADER + dataLen;

		// UDP header
		pkt.setShort(off, srcPort);
		pkt.setShort(off + 2, dstPort);
		pkt.setShort(off + 4, udpLen);
		pkt.setShort(off + 6, 0);  // checksum placeholder

		// Copy payload data
		pkt.copyFrom(data, dataOff, off + UDP_HEADER, dataLen);

		// Calculate UDP checksum (with pseudo-header)
		// First, fill IP header fields needed for pseudo-header
		pkt.setInt(IP.IP_OFF + 12, NetConfig.ip);
		pkt.setInt(IP.IP_OFF + 16, destIp);
		int cksum = checksum(pkt, off, udpLen);
		if (cksum == 0) cksum = 0xFFFF;  // UDP: 0 means no checksum
		pkt.setShort(off + 6, cksum);

		boolean sent = IP.send(pkt, destIp, IP.PROTO_UDP, udpLen);
		pkt.free();
		return sent;
	}

	/**
	 * Send a UDP broadcast packet from a caller-specified source IP.
	 * The caller must have already filled the UDP payload in pkt starting
	 * at IP_OFF + IP_HEADER_LEN + UDP_HEADER. Used by DHCP.
	 *
	 * @param srcIp source IP address (e.g. 0 for DHCP DISCOVER)
	 * @param srcPort source port
	 * @param dstPort destination port
	 * @param pkt packet with payload already loaded
	 * @param payloadLen length of UDP payload (bytes after UDP header)
	 */
	public static void sendBroadcast(int srcIp, int srcPort, int dstPort,
			Packet pkt, int payloadLen) {
		int off = IP.IP_OFF + IP.IP_HEADER_LEN;
		int udpLen = UDP_HEADER + payloadLen;

		// UDP header
		pkt.setShort(off, srcPort);
		pkt.setShort(off + 2, dstPort);
		pkt.setShort(off + 4, udpLen);
		pkt.setShort(off + 6, 0);  // checksum placeholder

		// Set IP addresses for pseudo-header checksum
		pkt.setInt(IP.IP_OFF + 12, srcIp);
		pkt.setInt(IP.IP_OFF + 16, 0xFFFFFFFF);
		int cksum = checksum(pkt, off, udpLen);
		if (cksum == 0) cksum = 0xFFFF;
		pkt.setShort(off + 6, cksum);

		IP.sendBroadcast(pkt, srcIp, IP.PROTO_UDP, udpLen);
	}

	/**
	 * Send a UDP unicast packet from a pre-loaded packet buffer.
	 * The caller must have already filled the UDP payload in pkt starting
	 * at IP_OFF + IP_HEADER_LEN + UDP_HEADER. Used by DNS and DHCP renewal.
	 *
	 * @param destIp destination IP address
	 * @param srcPort source port
	 * @param dstPort destination port
	 * @param pkt packet with payload already loaded
	 * @param payloadLen length of UDP payload (bytes after UDP header)
	 * @return true if sent, false if ARP not resolved
	 */
	public static boolean sendDirect(int destIp, int srcPort, int dstPort,
			Packet pkt, int payloadLen) {
		int off = IP.IP_OFF + IP.IP_HEADER_LEN;
		int udpLen = UDP_HEADER + payloadLen;

		// UDP header
		pkt.setShort(off, srcPort);
		pkt.setShort(off + 2, dstPort);
		pkt.setShort(off + 4, udpLen);
		pkt.setShort(off + 6, 0);  // checksum placeholder

		// Set IP addresses for pseudo-header checksum
		pkt.setInt(IP.IP_OFF + 12, NetConfig.ip);
		pkt.setInt(IP.IP_OFF + 16, destIp);
		int cksum = checksum(pkt, off, udpLen);
		if (cksum == 0) cksum = 0xFFFF;
		pkt.setShort(off + 6, cksum);

		boolean sent = IP.send(pkt, destIp, IP.PROTO_UDP, udpLen);
		return sent;
	}

	/**
	 * Calculate UDP checksum including pseudo-header.
	 * Returns 0 if existing checksum is valid.
	 */
	static int checksum(Packet pkt, int off, int udpLen) {
		// Start with pseudo-header
		int sum = IP.pseudoHeaderChecksum(pkt, IP.PROTO_UDP, udpLen);

		// Add UDP header + data
		int i;
		for (i = 0; i + 1 < udpLen; i += 2) {
			sum += pkt.getShort(off + i);
		}
		// Handle odd byte
		if (i < udpLen) {
			sum += pkt.getByte(off + i) << 8;
		}

		// Fold carries
		while ((sum >>> 16) != 0) {
			sum = (sum & 0xFFFF) + (sum >>> 16);
		}
		return (~sum) & 0xFFFF;
	}
}
