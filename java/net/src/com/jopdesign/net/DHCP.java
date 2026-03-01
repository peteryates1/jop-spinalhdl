package com.jopdesign.net;

import com.jopdesign.sys.JVMHelp;

/**
 * DHCP client (RFC 2131).
 *
 * States: INIT -> SELECTING -> REQUESTING -> BOUND -> RENEWING -> REBINDING
 *
 * Packets are built directly in Packet buffers using setByte()/setInt() to
 * avoid JOP's expensive byte array allocation (1 word per byte).
 *
 * Intercepts UDP port 68 via UDP.receive() — uses zero UDP connection slots.
 */
public class DHCP {

	// DHCP states
	static final int STATE_INIT       = 0;
	static final int STATE_SELECTING  = 1;
	static final int STATE_REQUESTING = 2;
	static final int STATE_BOUND      = 3;
	static final int STATE_RENEWING   = 4;
	static final int STATE_REBINDING  = 5;

	// DHCP message types (option 53)
	static final int DHCPDISCOVER = 1;
	static final int DHCPOFFER    = 2;
	static final int DHCPREQUEST  = 3;
	static final int DHCPDECLINE  = 4;
	static final int DHCPACK      = 5;
	static final int DHCPNAK      = 6;
	static final int DHCPRELEASE  = 7;

	// DHCP client/server ports
	static final int CLIENT_PORT = 68;
	static final int SERVER_PORT = 67;

	// DHCP fixed header size (before options)
	static final int DHCP_HEADER = 236;

	// Magic cookie
	static final int MAGIC_COOKIE = 0x63825363;

	// Retry parameters
	static final int RETRY_TIMEOUT_MS = 4000;
	static final int MAX_RETRIES = 4;

	// State
	static int state = STATE_INIT;
	static int xid = 0x4A4F5001;   // "JOP\x01"
	static int retryCount = 0;
	static int lastSendTime = 0;

	// Lease timers (in milliseconds, from NumFunctions.now())
	static int leaseStart = 0;
	static int t1Time = 0;         // Renewal time
	static int t2Time = 0;         // Rebinding time
	static int leaseExpiry = 0;    // Lease expiry time

	// Offered/acked parameters (parsed from server responses)
	static int offeredIp = 0;
	static int serverIp = 0;       // DHCP server IP (option 54)
	static int offeredMask = 0;
	static int offeredGateway = 0;
	static int offeredDns = 0;
	static int offeredLeaseTime = 0;
	static int offeredT1 = 0;
	static int offeredT2 = 0;
	static int msgType = 0;        // Last received message type

	/**
	 * Start DHCP discovery. Called from NetLoop.init() when useDhcp is true.
	 */
	public static void start() {
		state = STATE_INIT;
		NetConfig.ip = 0;
		NetConfig.dhcpActive = true;
		NetConfig.dhcpOfferedIp = 0;
		xid++;
		retryCount = 0;
		lastSendTime = 0;

		JVMHelp.wr("DHCP: start\n");
		sendDiscover();
		state = STATE_SELECTING;
	}

	/**
	 * Poll DHCP state machine. Called from NetLoop.poll().
	 * Handles retransmission timeouts and lease renewal.
	 */
	public static void poll() {
		int now = NumFunctions.now();

		switch (state) {
			case STATE_SELECTING:
			case STATE_REQUESTING:
				// Retry on timeout
				if (now - lastSendTime > RETRY_TIMEOUT_MS) {
					retryCount++;
					if (retryCount > MAX_RETRIES) {
						JVMHelp.wr("DHCP: timeout, restart\n");
						start();
						return;
					}
					if (state == STATE_SELECTING) {
						sendDiscover();
					} else {
						sendRequest(true);  // Broadcast request
					}
				}
				break;

			case STATE_BOUND:
				// Check T1 (renewal)
				if (offeredLeaseTime != 0 && now - t1Time > 0) {
					JVMHelp.wr("DHCP: renew\n");
					state = STATE_RENEWING;
					retryCount = 0;
					sendRequest(false);  // Unicast to server
				}
				break;

			case STATE_RENEWING:
				if (now - lastSendTime > RETRY_TIMEOUT_MS) {
					retryCount++;
					if (offeredLeaseTime != 0 && now - t2Time > 0) {
						// T2 expired — switch to rebinding (broadcast)
						JVMHelp.wr("DHCP: rebind\n");
						state = STATE_REBINDING;
						retryCount = 0;
						sendRequest(true);
					} else if (retryCount > MAX_RETRIES) {
						// Retry limit — wait for T2
					} else {
						sendRequest(false);  // Unicast retry
					}
				}
				break;

			case STATE_REBINDING:
				if (now - lastSendTime > RETRY_TIMEOUT_MS) {
					retryCount++;
					if (offeredLeaseTime != 0 && now - leaseExpiry > 0) {
						// Lease expired — restart from scratch
						JVMHelp.wr("DHCP: lease expired\n");
						start();
						return;
					}
					if (retryCount <= MAX_RETRIES) {
						sendRequest(true);  // Broadcast retry
					}
				}
				break;
		}
	}

	/**
	 * Process a received DHCP packet (called from UDP.receive() intercept).
	 *
	 * @param pkt the received packet
	 * @param off offset of DHCP payload (after UDP header)
	 * @param len length of DHCP payload
	 */
	public static void receive(Packet pkt, int off, int len) {
		if (len < DHCP_HEADER + 4) return;  // Need at least header + magic cookie

		// Verify BOOTREPLY (op=2) and our xid
		int op = pkt.getByte(off);
		if (op != 2) return;

		int rxXid = pkt.getInt(off + 4);
		if (rxXid != xid) return;

		// Verify magic cookie
		int cookie = pkt.getInt(off + DHCP_HEADER);
		if (cookie != MAGIC_COOKIE) return;

		// Extract yiaddr (your IP address)
		offeredIp = pkt.getInt(off + 16);

		// Parse options
		msgType = 0;
		offeredMask = 0;
		offeredGateway = 0;
		offeredDns = 0;
		offeredLeaseTime = 0;
		offeredT1 = 0;
		offeredT2 = 0;
		serverIp = 0;
		parseOptions(pkt, off + DHCP_HEADER + 4, off + len);

		// Server IP fallback: use siaddr if option 54 not present
		if (serverIp == 0) {
			serverIp = pkt.getInt(off + 20);
		}

		switch (state) {
			case STATE_SELECTING:
				if (msgType == DHCPOFFER) {
					JVMHelp.wr("DHCP: OFFER ");
					wrIp(offeredIp);
					JVMHelp.wr('\n');
					NetConfig.dhcpOfferedIp = offeredIp;
					state = STATE_REQUESTING;
					retryCount = 0;
					sendRequest(true);  // Broadcast request
				}
				break;

			case STATE_REQUESTING:
			case STATE_RENEWING:
			case STATE_REBINDING:
				if (msgType == DHCPACK) {
					applyLease();
				} else if (msgType == DHCPNAK) {
					JVMHelp.wr("DHCP: NAK, restart\n");
					start();
				}
				break;
		}
	}

	/**
	 * Apply a successful DHCP lease to NetConfig.
	 */
	private static void applyLease() {
		NetConfig.ip = offeredIp;
		if (offeredMask != 0) NetConfig.mask = offeredMask;
		if (offeredGateway != 0) NetConfig.gateway = offeredGateway;
		if (offeredDns != 0) NetConfig.dnsServer = offeredDns;

		NetConfig.dhcpActive = false;
		NetConfig.dhcpOfferedIp = 0;
		state = STATE_BOUND;

		// Set lease timers
		int now = NumFunctions.now();
		leaseStart = now;
		if (offeredLeaseTime != 0) {
			int leaseMs = offeredLeaseTime * 1000;
			// T1 default: 50% of lease
			t1Time = now + (offeredT1 != 0 ? offeredT1 * 1000 : leaseMs / 2);
			// T2 default: 87.5% of lease
			t2Time = now + (offeredT2 != 0 ? offeredT2 * 1000 : (leaseMs * 7) / 8);
			leaseExpiry = now + leaseMs;
		} else {
			// Infinite lease
			t1Time = 0;
			t2Time = 0;
			leaseExpiry = 0;
		}

		JVMHelp.wr("DHCP: BOUND ");
		wrIp(NetConfig.ip);
		JVMHelp.wr(" gw=");
		wrIp(NetConfig.gateway);
		JVMHelp.wr(" dns=");
		wrIp(NetConfig.dnsServer);
		JVMHelp.wr('\n');

		// Announce our new IP on the LAN
		Arp.sendGratuitous();
	}

	/**
	 * Send a DHCP DISCOVER broadcast.
	 */
	private static void sendDiscover() {
		Packet pkt = Packet.alloc();
		if (pkt == null) return;

		int off = IP.IP_OFF + IP.IP_HEADER_LEN + UDP.UDP_HEADER;
		clearDhcpHeader(pkt, off);

		// op=BOOTREQUEST, htype=Ethernet, hlen=6
		pkt.setByte(off, 1);
		pkt.setByte(off + 1, 1);
		pkt.setByte(off + 2, 6);
		pkt.setInt(off + 4, xid);
		// flags: broadcast bit (0x8000) — ask server to broadcast reply
		pkt.setShort(off + 10, 0x8000);

		// chaddr: our MAC at offset +28
		setChaddr(pkt, off);

		// Magic cookie
		pkt.setInt(off + DHCP_HEADER, MAGIC_COOKIE);

		// Options
		int optOff = off + DHCP_HEADER + 4;

		// Option 53: DHCP Message Type = DISCOVER
		optOff = putOption(pkt, optOff, 53, 1, DHCPDISCOVER);

		// Option 55: Parameter Request List
		pkt.setByte(optOff++, 55);
		pkt.setByte(optOff++, 4);
		pkt.setByte(optOff++, 1);   // Subnet Mask
		pkt.setByte(optOff++, 3);   // Router
		pkt.setByte(optOff++, 6);   // DNS
		pkt.setByte(optOff++, 51);  // Lease Time

		// End option
		pkt.setByte(optOff++, 255);

		// Pad to minimum 300 bytes
		int payloadLen = optOff - off;
		if (payloadLen < 300) payloadLen = 300;

		UDP.sendBroadcast(0, CLIENT_PORT, SERVER_PORT, pkt, payloadLen);
		lastSendTime = NumFunctions.now();
		pkt.free();
	}

	/**
	 * Send a DHCP REQUEST.
	 * @param broadcast true for broadcast (REQUESTING/REBINDING), false for unicast (RENEWING)
	 */
	private static void sendRequest(boolean broadcast) {
		Packet pkt = Packet.alloc();
		if (pkt == null) return;

		int off = IP.IP_OFF + IP.IP_HEADER_LEN + UDP.UDP_HEADER;
		clearDhcpHeader(pkt, off);

		// op=BOOTREQUEST, htype=Ethernet, hlen=6
		pkt.setByte(off, 1);
		pkt.setByte(off + 1, 1);
		pkt.setByte(off + 2, 6);
		pkt.setInt(off + 4, xid);

		if (broadcast) {
			pkt.setShort(off + 10, 0x8000);  // Broadcast flag
		}

		// ciaddr: our current IP (if renewing/rebinding, otherwise 0)
		if (state == STATE_RENEWING || state == STATE_REBINDING) {
			pkt.setInt(off + 12, NetConfig.ip);
		}

		// chaddr: our MAC
		setChaddr(pkt, off);

		// Magic cookie
		pkt.setInt(off + DHCP_HEADER, MAGIC_COOKIE);

		// Options
		int optOff = off + DHCP_HEADER + 4;

		// Option 53: DHCP Message Type = REQUEST
		optOff = putOption(pkt, optOff, 53, 1, DHCPREQUEST);

		// Option 50: Requested IP (only in REQUESTING state)
		if (state == STATE_REQUESTING) {
			optOff = putOption(pkt, optOff, 50, 4, offeredIp);
		}

		// Option 54: Server Identifier (only in REQUESTING state)
		if (state == STATE_REQUESTING && serverIp != 0) {
			optOff = putOption(pkt, optOff, 54, 4, serverIp);
		}

		// Option 55: Parameter Request List
		pkt.setByte(optOff++, 55);
		pkt.setByte(optOff++, 4);
		pkt.setByte(optOff++, 1);   // Subnet Mask
		pkt.setByte(optOff++, 3);   // Router
		pkt.setByte(optOff++, 6);   // DNS
		pkt.setByte(optOff++, 51);  // Lease Time

		// End option
		pkt.setByte(optOff++, 255);

		// Pad to minimum 300 bytes
		int payloadLen = optOff - off;
		if (payloadLen < 300) payloadLen = 300;

		if (broadcast) {
			int srcIp = (state == STATE_REBINDING) ? NetConfig.ip : 0;
			UDP.sendBroadcast(srcIp, CLIENT_PORT, SERVER_PORT, pkt, payloadLen);
		} else {
			UDP.sendDirect(serverIp, CLIENT_PORT, SERVER_PORT, pkt, payloadLen);
		}
		lastSendTime = NumFunctions.now();
		pkt.free();
	}

	/**
	 * Parse DHCP options (TLV format).
	 */
	private static void parseOptions(Packet pkt, int optOff, int maxOff) {
		while (optOff < maxOff) {
			int opt = pkt.getByte(optOff++);
			if (opt == 255) break;   // End
			if (opt == 0) continue;  // Pad

			if (optOff >= maxOff) break;
			int optLen = pkt.getByte(optOff++);
			if (optOff + optLen > maxOff) break;

			switch (opt) {
				case 1:  // Subnet Mask
					if (optLen >= 4) offeredMask = pkt.getInt(optOff);
					break;
				case 3:  // Router
					if (optLen >= 4) offeredGateway = pkt.getInt(optOff);
					break;
				case 6:  // DNS Server
					if (optLen >= 4) offeredDns = pkt.getInt(optOff);
					break;
				case 51: // Lease Time (seconds)
					if (optLen >= 4) offeredLeaseTime = pkt.getInt(optOff);
					break;
				case 53: // DHCP Message Type
					if (optLen >= 1) msgType = pkt.getByte(optOff);
					break;
				case 54: // Server Identifier
					if (optLen >= 4) serverIp = pkt.getInt(optOff);
					break;
				case 58: // Renewal Time (T1)
					if (optLen >= 4) offeredT1 = pkt.getInt(optOff);
					break;
				case 59: // Rebinding Time (T2)
					if (optLen >= 4) offeredT2 = pkt.getInt(optOff);
					break;
			}

			optOff += optLen;
		}
	}

	/**
	 * Zero out the 236-byte DHCP fixed header area in the packet.
	 */
	private static void clearDhcpHeader(Packet pkt, int off) {
		// Zero in 4-byte chunks for efficiency
		for (int i = 0; i < DHCP_HEADER; i += 4) {
			pkt.setInt(off + i, 0);
		}
		// Also zero the options area up to 300 bytes total
		for (int i = DHCP_HEADER; i < 300; i += 4) {
			pkt.setInt(off + i, 0);
		}
	}

	/**
	 * Set chaddr (client hardware address) at offset +28 in DHCP header.
	 */
	private static void setChaddr(Packet pkt, int off) {
		for (int i = 0; i < 6; i++) {
			pkt.setByte(off + 28 + i, NetConfig.getMacByte(i));
		}
	}

	/**
	 * Write a DHCP option with a 1-byte or 4-byte value.
	 * Returns the new offset after the option.
	 */
	private static int putOption(Packet pkt, int off, int optCode, int optLen, int value) {
		pkt.setByte(off++, optCode);
		pkt.setByte(off++, optLen);
		if (optLen == 1) {
			pkt.setByte(off++, value);
		} else if (optLen == 4) {
			pkt.setInt(off, value);
			off += 4;
		}
		return off;
	}

	/** Print an IP address in dotted decimal. */
	private static void wrIp(int ip) {
		wrInt((ip >>> 24) & 0xFF);
		JVMHelp.wr('.');
		wrInt((ip >>> 16) & 0xFF);
		JVMHelp.wr('.');
		wrInt((ip >>> 8) & 0xFF);
		JVMHelp.wr('.');
		wrInt(ip & 0xFF);
	}

	/** Print a positive integer in decimal. */
	private static void wrInt(int val) {
		boolean started = false;
		for (int d = 1000000000; d >= 1; d /= 10) {
			int digit = (val / d) % 10;
			if (digit != 0 || started || d == 1) {
				JVMHelp.wr((char)('0' + digit));
				started = true;
			}
		}
	}
}
