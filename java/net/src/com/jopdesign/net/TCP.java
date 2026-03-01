package com.jopdesign.net;

/**
 * TCP transport layer — state machine ported from jtcpip.
 *
 * Handles connection establishment (3-way handshake), data transfer,
 * and connection teardown (FIN/ACK). All TCP header manipulation
 * operates directly on Packet byte accessors.
 *
 * TCP header layout (at 'off' within packet):
 *   +0   source port     (2 bytes)
 *   +2   dest port       (2 bytes)
 *   +4   seq number      (4 bytes)
 *   +8   ack number      (4 bytes)
 *   +12  data offset + flags (2 bytes): [15:12]=offset, [5:0]=flags
 *   +14  window          (2 bytes)
 *   +16  checksum        (2 bytes)
 *   +18  urgent pointer  (2 bytes)
 *   +20  options (variable)
 */
public class TCP {

	// TCP flags (bit positions in lower byte of data offset/flags field)
	public static final int FLAG_FIN = 0x01;
	public static final int FLAG_SYN = 0x02;
	public static final int FLAG_RST = 0x04;
	public static final int FLAG_PSH = 0x08;
	public static final int FLAG_ACK = 0x10;
	public static final int FLAG_URG = 0x20;

	/** ISS counter (simple incrementing for now). */
	private static int issCounter = 0;

	private static int getInitialSeqNr() {
		// Use timer for some randomness
		issCounter += NumFunctions.now() + 64000;
		return issCounter;
	}

	// ===== TCP Header accessors on Packet =====

	static int getSrcPort(Packet pkt, int off)  { return pkt.getShort(off); }
	static int getDstPort(Packet pkt, int off)  { return pkt.getShort(off + 2); }
	static int getSeqNr(Packet pkt, int off)    { return pkt.getInt(off + 4); }
	static int getAckNr(Packet pkt, int off)    { return pkt.getInt(off + 8); }

	static int getDataOffset(Packet pkt, int off) {
		return (pkt.getShort(off + 12) >>> 12) & 0xF;
	}
	static int getFlags(Packet pkt, int off) {
		return pkt.getShort(off + 12) & 0x3F;
	}
	static int getWindow(Packet pkt, int off)   { return pkt.getShort(off + 14); }
	static int getChecksum(Packet pkt, int off)  { return pkt.getShort(off + 16); }

	static boolean isSYN(Packet pkt, int off) { return (getFlags(pkt, off) & FLAG_SYN) != 0; }
	static boolean isACK(Packet pkt, int off) { return (getFlags(pkt, off) & FLAG_ACK) != 0; }
	static boolean isFIN(Packet pkt, int off) { return (getFlags(pkt, off) & FLAG_FIN) != 0; }
	static boolean isRST(Packet pkt, int off) { return (getFlags(pkt, off) & FLAG_RST) != 0; }
	static boolean isPSH(Packet pkt, int off) { return (getFlags(pkt, off) & FLAG_PSH) != 0; }

	/** Get TCP data length (total segment - header). */
	static int getDataLength(Packet pkt, int tcpOff, int tcpLen) {
		int headerLen = getDataOffset(pkt, tcpOff) * 4;
		return tcpLen - headerLen;
	}

	/** Get offset of TCP data within the packet. */
	static int getDataStart(Packet pkt, int tcpOff) {
		return tcpOff + getDataOffset(pkt, tcpOff) * 4;
	}

	/**
	 * Check for MSS option in SYN packet.
	 * TCP option format: kind(1) + length(1) + data(variable)
	 * MSS option: kind=2, length=4, mss(2)
	 */
	static int getMSS(Packet pkt, int tcpOff) {
		int headerLen = getDataOffset(pkt, tcpOff) * 4;
		int optStart = tcpOff + 20;  // Options start after fixed header
		int optEnd = tcpOff + headerLen;
		int i = optStart;
		while (i < optEnd) {
			int kind = pkt.getByte(i);
			if (kind == 0) break;        // End of options
			if (kind == 1) { i++; continue; }  // NOP
			if (i + 1 >= optEnd) break;
			int optLen = pkt.getByte(i + 1);
			if (optLen < 2) break;
			if (kind == 2 && optLen == 4 && i + 3 < optEnd) {
				return pkt.getShort(i + 2);
			}
			i += optLen;
		}
		return -1;  // Not found
	}

	// ===== Checksum =====

	/**
	 * Calculate TCP checksum including pseudo-header.
	 */
	static int checksum(Packet pkt, int tcpOff, int tcpLen) {
		// Pseudo-header
		int sum = IP.pseudoHeaderChecksum(pkt, IP.PROTO_TCP, tcpLen);

		// TCP header + data
		int i;
		for (i = 0; i + 1 < tcpLen; i += 2) {
			sum += pkt.getShort(tcpOff + i);
		}
		if (i < tcpLen) {
			sum += pkt.getByte(tcpOff + i) << 8;
		}
		while ((sum >>> 16) != 0) {
			sum = (sum & 0xFFFF) + (sum >>> 16);
		}
		return (~sum) & 0xFFFF;
	}

	// ===== Receive path =====

	/**
	 * Process a received TCP segment. Called from IP layer.
	 */
	public static void receive(Packet pkt, int off, int len) {
		if (len < 20) return;

		// Verify checksum
		if (checksum(pkt, off, len) != 0) return;

		int dstPort = getDstPort(pkt, off);
		int srcPort = getSrcPort(pkt, off);
		int srcIP = IP.getSrcIp(pkt);

		TCPConnection conn = TCPConnection.getConnection(dstPort, srcIP, srcPort);
		if (conn == null) {
			// No matching connection
			if (!isRST(pkt, off)) {
				sendReset(pkt, off, len);
			}
			return;
		}

		conn.timeLastRemoteActivity = NumFunctions.now();

		switch (conn.state) {
			case TCPConnection.STATE_LISTEN:
				handleListen(conn, pkt, off, len);
				break;
			case TCPConnection.STATE_SYN_SENT:
				handleSynSent(conn, pkt, off, len);
				break;
			case TCPConnection.STATE_SYN_RCVD:
				handleSynRcvd(conn, pkt, off, len);
				break;
			case TCPConnection.STATE_ESTABLISHED:
				handleEstablished(conn, pkt, off, len);
				break;
			case TCPConnection.STATE_FIN_WAIT_1:
				handleFinWait1(conn, pkt, off, len);
				break;
			case TCPConnection.STATE_FIN_WAIT_2:
				handleFinWait2(conn, pkt, off, len);
				break;
			case TCPConnection.STATE_CLOSE_WAIT:
				handleCloseWait(conn, pkt, off, len);
				break;
			case TCPConnection.STATE_CLOSING:
				handleClosing(conn, pkt, off, len);
				break;
			case TCPConnection.STATE_LAST_ACK:
				handleLastAck(conn, pkt, off, len);
				break;
			case TCPConnection.STATE_TIME_WAIT:
				handleTimeWait(conn, pkt, off, len);
				break;
		}
	}

	// ===== State handlers =====

	/** LISTEN: expect SYN for passive open. */
	private static void handleListen(TCPConnection conn, Packet pkt, int off, int len) {
		if (isRST(pkt, off)) return;     // Ignore RST in LISTEN
		if (isACK(pkt, off)) {           // ACK in LISTEN → send RST
			sendReset(pkt, off, len);
			return;
		}
		if (!isSYN(pkt, off)) return;    // Must be SYN

		// Extract remote info
		conn.remoteIP = IP.getSrcIp(pkt);
		conn.remotePort = getSrcPort(pkt, off);
		conn.initialRemoteSeqNr = getSeqNr(pkt, off);
		conn.rcvNext = conn.initialRemoteSeqNr + 1;

		// Check for MSS option
		int mss = getMSS(pkt, off);
		if (mss > 0) conn.maxSndSegSize = mss;

		// Update window
		conn.sndWindow = getWindow(pkt, off);

		// Choose our ISS and prepare SYN+ACK
		conn.initialSeqNr = getInitialSeqNr();
		conn.sndUnack = conn.initialSeqNr;
		conn.sndNext = conn.initialSeqNr;

		conn.synToSend = true;
		conn.synToSendSeq = conn.sndNext + 1;
		conn.sndUnackTime = NumFunctions.now();

		conn.setState(TCPConnection.STATE_SYN_RCVD);

		// Send SYN+ACK immediately
		sendSynAck(conn);
	}

	/** SYN_SENT: we sent SYN, waiting for SYN+ACK. */
	private static void handleSynSent(TCPConnection conn, Packet pkt, int off, int len) {
		int ackNr = getAckNr(pkt, off);
		boolean hasAck = isACK(pkt, off);
		boolean hasSyn = isSYN(pkt, off);

		// Check ACK
		if (hasAck) {
			if (NumFunctions.seqBeforeOrEqual(ackNr, conn.initialSeqNr)
					|| NumFunctions.seqAfter(ackNr, conn.sndNext)) {
				if (!isRST(pkt, off)) sendReset(pkt, off, len);
				return;
			}
		}

		// RST
		if (isRST(pkt, off)) {
			if (hasAck) conn.abort();
			return;
		}

		// SYN
		if (hasSyn) {
			conn.initialRemoteSeqNr = getSeqNr(pkt, off);
			conn.rcvNext = conn.initialRemoteSeqNr + 1;
			conn.sndWindow = getWindow(pkt, off);

			int mss = getMSS(pkt, off);
			if (mss > 0) conn.maxSndSegSize = mss;

			if (hasAck) {
				// SYN+ACK received — complete handshake
				conn.sndUnack = ackNr;
				conn.synToSend = false;
				conn.numRetransmissions = 0;
				conn.setState(TCPConnection.STATE_ESTABLISHED);
				// Send ACK
				sendAck(conn);
			} else {
				// Simultaneous open: received SYN without ACK
				conn.setState(TCPConnection.STATE_SYN_RCVD);
				sendSynAck(conn);
			}
		}
	}

	/** SYN_RCVD: we sent SYN+ACK, waiting for ACK. */
	private static void handleSynRcvd(TCPConnection conn, Packet pkt, int off, int len) {
		if (!isSeqAcceptable(conn, pkt, off, len)) {
			if (!isRST(pkt, off)) sendAck(conn);
			return;
		}
		if (isRST(pkt, off)) {
			conn.setState(TCPConnection.STATE_LISTEN);
			return;
		}
		if (!isACK(pkt, off)) return;

		int ackNr = getAckNr(pkt, off);
		if (NumFunctions.seqBeforeOrEqual(conn.sndUnack, ackNr)
				&& NumFunctions.seqBeforeOrEqual(ackNr, conn.sndNext)) {
			handleAck(conn, pkt, off);
			conn.synToSend = false;
			conn.numRetransmissions = 0;
			conn.setState(TCPConnection.STATE_ESTABLISHED);

			// Process any data in this segment
			processData(conn, pkt, off, len);
		} else {
			sendReset(pkt, off, len);
		}
	}

	/** ESTABLISHED: normal data transfer. */
	private static void handleEstablished(TCPConnection conn, Packet pkt, int off, int len) {
		if (!isSeqAcceptable(conn, pkt, off, len)) {
			if (!isRST(pkt, off)) sendAck(conn);
			return;
		}
		if (isRST(pkt, off)) {
			conn.abort();
			return;
		}
		if (isSYN(pkt, off)) {
			// SYN in established = error
			sendReset(pkt, off, len);
			conn.abort();
			return;
		}
		if (!isACK(pkt, off)) return;

		handleAck(conn, pkt, off);
		processData(conn, pkt, off, len);

		if (isFIN(pkt, off)) {
			// Only process FIN when all data in the segment was consumed
			int finSeq = getSeqNr(pkt, off) + getDataLength(pkt, off, len);
			if (conn.rcvNext == finSeq) {
				processFin(conn);
				conn.setState(TCPConnection.STATE_CLOSE_WAIT);
				sendAck(conn);
			}
			// else: data not fully consumed, FIN handled on retransmit
		}
	}

	/** FIN_WAIT_1: we sent FIN, waiting for ACK of our FIN. */
	private static void handleFinWait1(TCPConnection conn, Packet pkt, int off, int len) {
		if (!isSeqAcceptable(conn, pkt, off, len)) {
			if (!isRST(pkt, off)) sendAck(conn);
			return;
		}
		if (isRST(pkt, off)) { conn.abort(); return; }
		if (isSYN(pkt, off)) { sendReset(pkt, off, len); conn.abort(); return; }
		if (!isACK(pkt, off)) return;

		handleAck(conn, pkt, off);
		processData(conn, pkt, off, len);

		boolean finAcked = NumFunctions.seqAfter(getAckNr(pkt, off), conn.finToSendSeq)
			|| getAckNr(pkt, off) == conn.finToSendSeq;

		if (isFIN(pkt, off)) {
			// Only process FIN when all data in the segment was consumed
			int finSeq = getSeqNr(pkt, off) + getDataLength(pkt, off, len);
			if (conn.rcvNext == finSeq) {
				processFin(conn);
				if (finAcked) {
					conn.setState(TCPConnection.STATE_TIME_WAIT);
					conn.timeLastRemoteActivity = NumFunctions.now();
				} else {
					conn.setState(TCPConnection.STATE_CLOSING);
				}
				sendAck(conn);
			}
			// else: data not fully consumed, FIN handled on retransmit
		} else if (finAcked) {
			conn.finToSend = false;
			conn.setState(TCPConnection.STATE_FIN_WAIT_2);
		}
	}

	/** FIN_WAIT_2: our FIN was ACKed, waiting for remote FIN. */
	private static void handleFinWait2(TCPConnection conn, Packet pkt, int off, int len) {
		if (!isSeqAcceptable(conn, pkt, off, len)) {
			if (!isRST(pkt, off)) sendAck(conn);
			return;
		}
		if (isRST(pkt, off)) { conn.abort(); return; }
		if (isSYN(pkt, off)) { sendReset(pkt, off, len); conn.abort(); return; }
		if (!isACK(pkt, off)) return;

		handleAck(conn, pkt, off);
		processData(conn, pkt, off, len);

		if (isFIN(pkt, off)) {
			// Only process FIN when all data in the segment was consumed
			int finSeq = getSeqNr(pkt, off) + getDataLength(pkt, off, len);
			if (conn.rcvNext == finSeq) {
				processFin(conn);
				conn.setState(TCPConnection.STATE_TIME_WAIT);
				conn.timeLastRemoteActivity = NumFunctions.now();
				sendAck(conn);
			}
			// else: data not fully consumed, FIN handled on retransmit
		}
	}

	/** CLOSE_WAIT: remote sent FIN, waiting for user to close. */
	private static void handleCloseWait(TCPConnection conn, Packet pkt, int off, int len) {
		if (!isSeqAcceptable(conn, pkt, off, len)) {
			if (!isRST(pkt, off)) sendAck(conn);
			return;
		}
		if (isRST(pkt, off)) { conn.abort(); return; }
		if (isSYN(pkt, off)) { sendReset(pkt, off, len); conn.abort(); return; }
		if (!isACK(pkt, off)) return;

		handleAck(conn, pkt, off);
	}

	/** CLOSING: simultaneous close, waiting for ACK of our FIN. */
	private static void handleClosing(TCPConnection conn, Packet pkt, int off, int len) {
		if (!isSeqAcceptable(conn, pkt, off, len)) {
			if (!isRST(pkt, off)) sendAck(conn);
			return;
		}
		if (isRST(pkt, off)) { conn.abort(); return; }
		if (isSYN(pkt, off)) { sendReset(pkt, off, len); conn.abort(); return; }
		if (!isACK(pkt, off)) return;

		handleAck(conn, pkt, off);

		boolean finAcked = NumFunctions.seqAfterOrEqual(getAckNr(pkt, off), conn.finToSendSeq);
		if (finAcked) {
			conn.setState(TCPConnection.STATE_TIME_WAIT);
			conn.timeLastRemoteActivity = NumFunctions.now();
		}
	}

	/** LAST_ACK: waiting for ACK of our FIN after remote close. */
	private static void handleLastAck(TCPConnection conn, Packet pkt, int off, int len) {
		if (isRST(pkt, off)) {
			TCPConnection.deleteConnection(conn);
			return;
		}
		if (!isACK(pkt, off)) return;

		boolean finAcked = NumFunctions.seqAfterOrEqual(getAckNr(pkt, off), conn.finToSendSeq);
		if (finAcked) {
			TCPConnection.deleteConnection(conn);
		}
	}

	/** TIME_WAIT: handle retransmitted FIN. */
	private static void handleTimeWait(TCPConnection conn, Packet pkt, int off, int len) {
		if (isRST(pkt, off)) {
			TCPConnection.deleteConnection(conn);
			return;
		}
		// Retransmitted FIN — re-ACK and restart timer
		if (isFIN(pkt, off)) {
			sendAck(conn);
			conn.timeLastRemoteActivity = NumFunctions.now();
		}
	}

	// ===== Common helpers =====

	/**
	 * Check if a received segment's sequence number is acceptable.
	 * RFC 793 Section 3.3 sequence number validation.
	 */
	private static boolean isSeqAcceptable(TCPConnection conn, Packet pkt, int off, int len) {
		int seqNr = getSeqNr(pkt, off);
		int segLen = getDataLength(pkt, off, len);
		if (isSYN(pkt, off)) segLen++;
		if (isFIN(pkt, off)) segLen++;
		int rcvWnd = conn.rcvWindow;

		if (segLen == 0) {
			if (rcvWnd == 0) {
				return seqNr == conn.rcvNext;
			}
			return NumFunctions.seqBeforeOrEqual(conn.rcvNext, seqNr)
				&& NumFunctions.seqBefore(seqNr, conn.rcvNext + rcvWnd);
		}
		if (rcvWnd == 0) return false;

		// Check if start or end of segment falls within window
		return (NumFunctions.seqBeforeOrEqual(conn.rcvNext, seqNr)
				&& NumFunctions.seqBefore(seqNr, conn.rcvNext + rcvWnd))
			|| (NumFunctions.seqBeforeOrEqual(conn.rcvNext, seqNr + segLen - 1)
				&& NumFunctions.seqBefore(seqNr + segLen - 1, conn.rcvNext + rcvWnd));
	}

	/**
	 * Process ACK field: update sndUnack, window, release output buffer.
	 */
	private static void handleAck(TCPConnection conn, Packet pkt, int off) {
		int ackNr = getAckNr(pkt, off);

		if (NumFunctions.seqAfter(ackNr, conn.sndUnack)
				&& NumFunctions.seqBeforeOrEqual(ackNr, conn.sndNext)) {
			int ackedBytes = ackNr - conn.sndUnack;

			// Account for SYN consuming a sequence number
			if (conn.synToSend && NumFunctions.seqAfterOrEqual(ackNr, conn.synToSendSeq)) {
				conn.synToSend = false;
				ackedBytes--;  // SYN doesn't count as data byte
			}

			// Account for FIN consuming a sequence number
			if (conn.finToSend && NumFunctions.seqAfterOrEqual(ackNr, conn.finToSendSeq)) {
				ackedBytes--;  // FIN doesn't count as data byte
			}

			if (ackedBytes > 0) {
				conn.oStream.ackData(ackedBytes);
			}
			conn.sndUnack = ackNr;
			conn.sndUnackTime = NumFunctions.now();
			conn.numRetransmissions = 0;
		}

		// Update send window
		conn.sndWindow = getWindow(pkt, off);
		conn.sndWndLastUpdateSeq = getSeqNr(pkt, off);
		conn.sndWndLastUpdateAck = ackNr;
	}

	/**
	 * Process received data: copy to input stream, update rcvNext.
	 */
	private static void processData(TCPConnection conn, Packet pkt, int off, int len) {
		int dataLen = getDataLength(pkt, off, len);
		if (dataLen <= 0) return;

		int dataStart = getDataStart(pkt, off);
		int seqNr = getSeqNr(pkt, off);

		// Simple in-order delivery: only accept if seq matches rcvNext
		if (seqNr != conn.rcvNext) return;

		int bytesWritten = 0;
		for (int i = 0; i < dataLen; i++) {
			int b = pkt.getByte(dataStart + i);
			if (conn.iStream.write(b) < 0) break;
			bytesWritten++;
		}
		conn.rcvNext += bytesWritten;

		// Update receive window
		conn.rcvWindow = conn.iStream.getFreeBufferSpace();
		if (conn.rcvWindow > 0xFFFF) conn.rcvWindow = 0xFFFF;

		// Send ACK for received data
		if (bytesWritten > 0) {
			sendAck(conn);
		}
	}

	/**
	 * Process received FIN: advance rcvNext.
	 */
	private static void processFin(TCPConnection conn) {
		conn.rcvNext++;  // FIN consumes one sequence number
		conn.iStream.close();
	}

	// ===== Send path =====

	/**
	 * Send a TCP segment for a connection. Called from TCPConnection.poll()
	 * and from state handlers.
	 */
	public static void sendSegment(TCPConnection conn) {
		Packet pkt = Packet.alloc();
		if (pkt == null) return;

		int tcpOff = IP.IP_OFF + IP.IP_HEADER_LEN;
		int flags = FLAG_ACK;
		int headerWords = 5;  // 20 bytes minimum

		// SYN
		if (conn.synToSend) {
			flags |= FLAG_SYN;
			headerWords = 6;  // Include MSS option (4 bytes)
		}

		// Build header
		pkt.setShort(tcpOff, conn.localPort);
		pkt.setShort(tcpOff + 2, conn.remotePort);
		pkt.setInt(tcpOff + 4, conn.sndNext);
		pkt.setInt(tcpOff + 8, conn.rcvNext);
		// Data offset + flags set later
		pkt.setShort(tcpOff + 14, conn.rcvWindow & 0xFFFF);
		pkt.setShort(tcpOff + 16, 0);  // checksum placeholder
		pkt.setShort(tcpOff + 18, 0);  // urgent pointer

		// MSS option (in SYN packets)
		if (conn.synToSend) {
			pkt.setByte(tcpOff + 20, 2);   // Kind = MSS
			pkt.setByte(tcpOff + 21, 4);   // Length = 4
			pkt.setShort(tcpOff + 22, NetConfig.TCP_MSS);
		}

		int headerLen = headerWords * 4;
		int dataStart = tcpOff + headerLen;
		int dataLen = 0;

		// Attach data (ESTABLISHED or CLOSE_WAIT — RFC 793 allows sending
		// in CLOSE_WAIT since only the remote side has closed)
		if (!conn.synToSend && (conn.state == TCPConnection.STATE_ESTABLISHED
				|| conn.state == TCPConnection.STATE_CLOSE_WAIT)) {
			int maxData = conn.maxSndSegSize;
			int remainingWindow = conn.sndWindow - (conn.sndNext - conn.sndUnack);
			if (remainingWindow < 0) remainingWindow = 0;
			if (maxData > remainingWindow) maxData = remainingWindow;
			// Zero-window probe: send 1 byte even with window=0
			if (maxData == 0 && !conn.oStream.isNoMoreDataToRead()) {
				maxData = 1;
			}
			if (maxData > Packet.MAX_BYTES - dataStart) {
				maxData = Packet.MAX_BYTES - dataStart;
			}

			for (int i = 0; i < maxData; i++) {
				int b = conn.oStream.read();
				if (b == -1) break;
				pkt.setByte(dataStart + i, b);
				dataLen++;
			}

			if (dataLen > 0) flags |= FLAG_PSH;
		}

		// FIN: send if user requested close and all data has been sent
		if (conn.flushAndClose && conn.oStream.isNoMoreDataToRead() && !conn.synToSend) {
			if (!conn.finToSend) {
				conn.finToSend = true;
				conn.finToSendSeq = conn.sndNext + dataLen;
				if (conn.synToSend) conn.finToSendSeq++;
			}
			flags |= FLAG_FIN;
			if (conn.state == TCPConnection.STATE_ESTABLISHED) {
				conn.setState(TCPConnection.STATE_FIN_WAIT_1);
			} else if (conn.state == TCPConnection.STATE_CLOSE_WAIT) {
				conn.setState(TCPConnection.STATE_LAST_ACK);
			}
		}

		int tcpLen = headerLen + dataLen;

		// Set data offset and flags
		int offFlags = (headerWords << 12) | (flags & 0x3F);
		pkt.setShort(tcpOff + 12, offFlags);

		// Fill IP header fields needed for pseudo-header checksum
		pkt.setInt(IP.IP_OFF + 12, NetConfig.ip);
		pkt.setInt(IP.IP_OFF + 16, conn.remoteIP);

		// Calculate and set TCP checksum
		// Need to temporarily set IP protocol for pseudo-header
		pkt.setByte(IP.IP_OFF + 9, IP.PROTO_TCP);
		int cksum = checksum(pkt, tcpOff, tcpLen);
		pkt.setShort(tcpOff + 16, cksum);

		// Update sequence numbers
		int seqAdvance = dataLen;
		if (conn.synToSend) seqAdvance++;
		if ((flags & FLAG_FIN) != 0) seqAdvance++;
		conn.sndNext += seqAdvance;

		if (conn.sndNext != conn.sndUnack) {
			conn.sndUnackTime = NumFunctions.now();
		}

		// Send via IP
		IP.send(pkt, conn.remoteIP, IP.PROTO_TCP, tcpLen);
		pkt.free();
	}

	/**
	 * Send SYN+ACK for passive open.
	 */
	private static void sendSynAck(TCPConnection conn) {
		sendSegment(conn);
	}

	/**
	 * Send a bare ACK.
	 */
	private static void sendAck(TCPConnection conn) {
		Packet pkt = Packet.alloc();
		if (pkt == null) return;

		int tcpOff = IP.IP_OFF + IP.IP_HEADER_LEN;
		int headerLen = 20;

		pkt.setShort(tcpOff, conn.localPort);
		pkt.setShort(tcpOff + 2, conn.remotePort);
		pkt.setInt(tcpOff + 4, conn.sndNext);
		pkt.setInt(tcpOff + 8, conn.rcvNext);
		int offFlags = (5 << 12) | FLAG_ACK;
		pkt.setShort(tcpOff + 12, offFlags);
		pkt.setShort(tcpOff + 14, conn.rcvWindow & 0xFFFF);
		pkt.setShort(tcpOff + 16, 0);
		pkt.setShort(tcpOff + 18, 0);

		pkt.setInt(IP.IP_OFF + 12, NetConfig.ip);
		pkt.setInt(IP.IP_OFF + 16, conn.remoteIP);
		pkt.setByte(IP.IP_OFF + 9, IP.PROTO_TCP);

		int cksum = checksum(pkt, tcpOff, headerLen);
		pkt.setShort(tcpOff + 16, cksum);

		IP.send(pkt, conn.remoteIP, IP.PROTO_TCP, headerLen);
		pkt.free();
	}

	/**
	 * Send a RST in response to an unexpected segment.
	 */
	private static void sendReset(Packet rxPkt, int off, int len) {
		Packet pkt = Packet.alloc();
		if (pkt == null) return;

		int srcIP = IP.getSrcIp(rxPkt);
		int tcpOff = IP.IP_OFF + IP.IP_HEADER_LEN;
		int headerLen = 20;

		pkt.setShort(tcpOff, getDstPort(rxPkt, off));    // Our port
		pkt.setShort(tcpOff + 2, getSrcPort(rxPkt, off)); // Their port

		int flags;
		if (isACK(rxPkt, off)) {
			// RST with seq = their ACK
			pkt.setInt(tcpOff + 4, getAckNr(rxPkt, off));
			pkt.setInt(tcpOff + 8, 0);
			flags = FLAG_RST;
		} else {
			// RST+ACK with ack = their seq + seglen
			pkt.setInt(tcpOff + 4, 0);
			int segLen = getDataLength(rxPkt, off, len);
			if (isSYN(rxPkt, off)) segLen++;
			if (isFIN(rxPkt, off)) segLen++;
			pkt.setInt(tcpOff + 8, getSeqNr(rxPkt, off) + segLen);
			flags = FLAG_RST | FLAG_ACK;
		}

		int offFlags = (5 << 12) | (flags & 0x3F);
		pkt.setShort(tcpOff + 12, offFlags);
		pkt.setShort(tcpOff + 14, 0);
		pkt.setShort(tcpOff + 16, 0);
		pkt.setShort(tcpOff + 18, 0);

		pkt.setInt(IP.IP_OFF + 12, NetConfig.ip);
		pkt.setInt(IP.IP_OFF + 16, srcIP);
		pkt.setByte(IP.IP_OFF + 9, IP.PROTO_TCP);

		int cksum = checksum(pkt, tcpOff, headerLen);
		pkt.setShort(tcpOff + 16, cksum);

		IP.send(pkt, srcIP, IP.PROTO_TCP, headerLen);
		pkt.free();
	}

	// ===== User API =====

	/**
	 * Open a TCP connection (active open).
	 * @return the connection, or null on failure
	 */
	public static TCPConnection connect(int remoteIP, int remotePort) {
		int localPort = TCPConnection.newLocalPort();
		TCPConnection conn = TCPConnection.newConnection(localPort);
		if (conn == null) return null;

		conn.remoteIP = remoteIP;
		conn.remotePort = remotePort;
		conn.initialSeqNr = getInitialSeqNr();
		conn.sndUnack = conn.initialSeqNr;
		conn.sndNext = conn.initialSeqNr;
		conn.synToSend = true;
		conn.synToSendSeq = conn.sndNext + 1;
		conn.sndUnackTime = NumFunctions.now();

		conn.setState(TCPConnection.STATE_SYN_SENT);

		// Send SYN
		sendSegment(conn);
		return conn;
	}

	/**
	 * Open a listening connection (passive open).
	 * @return the connection, or null on failure
	 */
	public static TCPConnection listen(int localPort) {
		TCPConnection conn = TCPConnection.newConnection(localPort);
		if (conn == null) return null;
		conn.setState(TCPConnection.STATE_LISTEN);
		return conn;
	}

	/**
	 * Set flushAndClose on a connection (used by TCPConnection.close()).
	 */
	public static void flushAndClose(TCPConnection conn) {
		conn.flushAndClose = true;
	}
}
