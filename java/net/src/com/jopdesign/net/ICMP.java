package com.jopdesign.net;

import com.jopdesign.sys.JVMHelp;

/**
 * ICMP protocol handler: echo reply (ping), destination unreachable.
 *
 * ICMP header layout (at payloadOff within packet):
 *   +0  type     (1 byte)
 *   +1  code     (1 byte)
 *   +2  checksum (2 bytes)
 *   +4  rest of header (4 bytes, varies by type)
 *   +8  data
 */
public class ICMP {

	/** ICMP types. */
	public static final int TYPE_ECHO_REPLY = 0;
	public static final int TYPE_DEST_UNREACHABLE = 3;
	public static final int TYPE_ECHO_REQUEST = 8;

	/** ICMP destination unreachable codes. */
	public static final int CODE_PORT_UNREACHABLE = 3;

	/** Diagnostic counters. */
	public static int pingRxCount = 0;
	public static int pingSentCount = 0;
	public static int pingFailCount = 0;

	/**
	 * Process a received ICMP packet.
	 *
	 * @param pkt the received Ethernet frame
	 * @param off offset of ICMP header within packet
	 * @param len length of ICMP data
	 */
	public static void receive(Packet pkt, int off, int len) {
		if (len < 8) return;

		// Verify ICMP checksum
		if (checksum(pkt, off, len) != 0) return;

		int type = pkt.getByte(off);
		if (type == TYPE_ECHO_REQUEST) {
			pingRxCount++;
			sendEchoReply(pkt, off, len);
		}
	}

	/**
	 * Send an ICMP echo reply in response to an echo request.
	 * Reuses the received packet buffer, swapping addresses.
	 */
	private static void sendEchoReply(Packet rxPkt, int off, int len) {
		Packet pkt = Packet.alloc();
		if (pkt == null) return;

		int srcIp = IP.getSrcIp(rxPkt);

		// Copy ICMP data from request
		int icmpOff = IP.IP_OFF + IP.IP_HEADER_LEN;
		for (int i = 0; i < len; i++) {
			pkt.setByte(icmpOff + i, rxPkt.getByte(off + i));
		}

		// Change type to echo reply
		pkt.setByte(icmpOff + 0, TYPE_ECHO_REPLY);
		pkt.setByte(icmpOff + 1, 0);  // code = 0

		// Recalculate ICMP checksum
		pkt.setShort(icmpOff + 2, 0);
		int cksum = checksum(pkt, icmpOff, len);
		pkt.setShort(icmpOff + 2, cksum);

		// Send via IP
		if (IP.send(pkt, srcIp, IP.PROTO_ICMP, len)) {
			pingSentCount++;
		} else {
			pingFailCount++;
		}
		pkt.free();
	}

	/**
	 * Send ICMP destination unreachable (port unreachable).
	 * Includes the original IP header + 8 bytes of original payload.
	 */
	public static void sendDestUnreachable(Packet rxPkt, int code) {
		Packet pkt = Packet.alloc();
		if (pkt == null) return;

		int srcIp = IP.getSrcIp(rxPkt);
		int icmpOff = IP.IP_OFF + IP.IP_HEADER_LEN;

		// ICMP header
		pkt.setByte(icmpOff + 0, TYPE_DEST_UNREACHABLE);
		pkt.setByte(icmpOff + 1, code);
		pkt.setShort(icmpOff + 2, 0);  // checksum placeholder
		pkt.setInt(icmpOff + 4, 0);     // unused

		// Copy original IP header + first 8 bytes of payload
		int copyLen = IP.IP_HEADER_LEN + 8;
		int rxIpOff = IP.IP_OFF;
		if (rxPkt.len - rxIpOff < copyLen) {
			copyLen = rxPkt.len - rxIpOff;
		}
		for (int i = 0; i < copyLen; i++) {
			pkt.setByte(icmpOff + 8 + i, rxPkt.getByte(rxIpOff + i));
		}

		int icmpLen = 8 + copyLen;
		int cksum = checksum(pkt, icmpOff, icmpLen);
		pkt.setShort(icmpOff + 2, cksum);

		IP.send(pkt, srcIp, IP.PROTO_ICMP, icmpLen);
		pkt.free();
	}

	/**
	 * Calculate ICMP checksum over the ICMP header + data.
	 * Returns 0 if existing checksum is valid.
	 */
	private static int checksum(Packet pkt, int off, int len) {
		int sum = 0;
		int i;
		for (i = 0; i + 1 < len; i += 2) {
			sum += pkt.getShort(off + i);
		}
		// Handle odd byte
		if (i < len) {
			sum += pkt.getByte(off + i) << 8;
		}
		// Fold carries
		while ((sum >>> 16) != 0) {
			sum = (sum & 0xFFFF) + (sum >>> 16);
		}
		return (~sum) & 0xFFFF;
	}
}
