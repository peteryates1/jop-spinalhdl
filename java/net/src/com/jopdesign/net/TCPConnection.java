package com.jopdesign.net;

/**
 * TCP connection state and buffer management.
 *
 * Ported from jtcpip TCPConnection, adapted for JOP:
 * - No synchronized (single-threaded)
 * - No wait/notify
 * - Uses our Packet-based architecture
 */
public class TCPConnection {

	// TCP states (RFC 793)
	public static final int STATE_CLOSED = 0;
	public static final int STATE_LISTEN = 1;
	public static final int STATE_SYN_SENT = 2;
	public static final int STATE_SYN_RCVD = 3;
	public static final int STATE_ESTABLISHED = 4;
	public static final int STATE_FIN_WAIT_1 = 5;
	public static final int STATE_FIN_WAIT_2 = 6;
	public static final int STATE_CLOSE_WAIT = 7;
	public static final int STATE_CLOSING = 8;
	public static final int STATE_LAST_ACK = 9;
	public static final int STATE_TIME_WAIT = 10;

	/** TCP stream buffer sizes. */
	private static final int RCV_BUF_SIZE = 4096;
	private static final int SND_BUF_SIZE = 4096;

	/** Connection pool. */
	private static TCPConnection[] pool = null;

	/** Connection state. */
	public int state;
	public int prevState;

	/** In use flag. */
	public boolean inUse;

	/** Remote host. */
	public int remoteIP;
	public int remotePort;

	/** Local port. */
	public int localPort;

	/** Sequence numbers. */
	public int sndUnack;      // Oldest unacknowledged seq
	public int sndNext;       // Next seq to send
	public int initialSeqNr;  // Our ISS
	public int rcvNext;       // Next expected seq from remote
	public int initialRemoteSeqNr;  // Remote ISS

	/** Windows. */
	public int sndWindow;
	public int rcvWindow;
	public int sndWndLastUpdateSeq;
	public int sndWndLastUpdateAck;

	/** Maximum send segment size (remote MSS). */
	public int maxSndSegSize;

	/** SYN/FIN control flags for sending. */
	public boolean synToSend;
	public int synToSendSeq;
	public boolean finToSend;
	public int finToSendSeq;

	/** User requested close (send remaining data then FIN). */
	public boolean flushAndClose;

	/** Retransmission state. */
	public int sndUnackTime;
	public int timeLastRemoteActivity;
	public int numRetransmissions;

	/** I/O streams. */
	public TCPInputStream iStream;
	public TCPOutputStream oStream;

	public TCPConnection() {
		state = STATE_CLOSED;
		prevState = STATE_CLOSED;
		inUse = false;
		remoteIP = 0;
		remotePort = 0;
		localPort = 0;
		sndUnack = 0;
		sndNext = 0;
		initialSeqNr = 0;
		rcvNext = 0;
		initialRemoteSeqNr = 0;
		sndWindow = 0;
		rcvWindow = NetConfig.TCP_WINDOW;
		sndWndLastUpdateSeq = 0;
		sndWndLastUpdateAck = 0;
		maxSndSegSize = 536;  // RFC 793 default
		synToSend = false;
		synToSendSeq = 0;
		finToSend = false;
		finToSendSeq = 0;
		flushAndClose = false;
		sndUnackTime = 0;
		timeLastRemoteActivity = 0;
		numRetransmissions = 0;
		iStream = new TCPInputStream(RCV_BUF_SIZE);
		oStream = new TCPOutputStream(SND_BUF_SIZE);
	}

	/** Initialize the connection pool. */
	public static void init() {
		pool = new TCPConnection[NetConfig.MAX_TCP_CONN];
		for (int i = 0; i < NetConfig.MAX_TCP_CONN; i++) {
			pool[i] = new TCPConnection();
		}
	}

	/** Get pool entry by index (for NetLoop polling). */
	public static TCPConnection getByIndex(int idx) {
		if (pool == null || idx < 0 || idx >= NetConfig.MAX_TCP_CONN) return null;
		return pool[idx];
	}

	/**
	 * Allocate a new connection on a given port.
	 * Returns null if pool full or port has a listener already.
	 */
	public static TCPConnection newConnection(int port) {
		if (pool == null) return null;

		// Check for existing listener on same port
		for (int i = 0; i < NetConfig.MAX_TCP_CONN; i++) {
			if (pool[i].inUse && pool[i].localPort == port
					&& pool[i].state == STATE_LISTEN) {
				return null;
			}
		}
		// Find free slot
		for (int i = 0; i < NetConfig.MAX_TCP_CONN; i++) {
			if (!pool[i].inUse) {
				cleanUp(pool[i]);
				pool[i].inUse = true;
				pool[i].localPort = port;
				return pool[i];
			}
		}
		// No free slot — recycle oldest TIME_WAIT connection
		int oldestIdx = -1;
		int oldestAge = 0;
		for (int i = 0; i < NetConfig.MAX_TCP_CONN; i++) {
			if (pool[i].state == STATE_TIME_WAIT) {
				int age = NumFunctions.now() - pool[i].timeLastRemoteActivity;
				if (age > oldestAge) {
					oldestAge = age;
					oldestIdx = i;
				}
			}
		}
		if (oldestIdx >= 0) {
			cleanUp(pool[oldestIdx]);
			pool[oldestIdx].inUse = true;
			pool[oldestIdx].localPort = port;
			return pool[oldestIdx];
		}
		return null;
	}

	/**
	 * Find a connection matching the given tuple.
	 * Prefers exact match over LISTEN match.
	 */
	public static TCPConnection getConnection(int dstPort, int srcIP, int srcPort) {
		if (pool == null) return null;

		TCPConnection listener = null;
		for (int i = 0; i < NetConfig.MAX_TCP_CONN; i++) {
			if (!pool[i].inUse) continue;
			if (pool[i].localPort != dstPort) continue;

			if (pool[i].state == STATE_LISTEN) {
				listener = pool[i];
			} else if (pool[i].remoteIP == srcIP && pool[i].remotePort == srcPort) {
				return pool[i];  // Exact match
			}
		}
		return listener;  // May be null
	}

	/** Release a connection back to the pool. */
	public static void deleteConnection(TCPConnection conn) {
		if (conn == null) return;
		conn.oStream.close();
		conn.iStream.close();
		conn.state = STATE_CLOSED;
		conn.inUse = false;
	}

	/** Reset connection fields to defaults. */
	private static void cleanUp(TCPConnection conn) {
		conn.remoteIP = 0;
		conn.remotePort = 0;
		conn.localPort = 0;
		conn.state = STATE_CLOSED;
		conn.prevState = STATE_CLOSED;
		conn.sndUnack = 0;
		conn.sndNext = 0;
		conn.sndWindow = 0;
		conn.initialSeqNr = 0;
		conn.initialRemoteSeqNr = 0;
		conn.rcvNext = 0;
		conn.rcvWindow = NetConfig.TCP_WINDOW;
		conn.maxSndSegSize = 536;
		conn.flushAndClose = false;
		conn.synToSend = false;
		conn.synToSendSeq = 0;
		conn.finToSend = false;
		conn.finToSendSeq = 0;
		conn.sndUnackTime = NumFunctions.now();
		conn.timeLastRemoteActivity = NumFunctions.now();
		conn.numRetransmissions = 0;
		conn.oStream.reOpen();
		conn.iStream.reOpen();
	}

	/**
	 * Set state with tracking of previous state.
	 */
	public void setState(int newState) {
		prevState = state;
		state = newState;
	}

	/**
	 * Generate a random unused local port (iterative, not recursive).
	 */
	public static int newLocalPort() {
		int port = 49152 + (NumFunctions.now() & 0x3FFF);
		for (int attempt = 0; attempt < 100; attempt++) {
			boolean used = false;
			if (pool != null) {
				for (int i = 0; i < NetConfig.MAX_TCP_CONN; i++) {
					if (pool[i].inUse && pool[i].localPort == port) {
						used = true;
						break;
					}
				}
			}
			if (!used) return port;
			port = 49152 + ((port + 7919) & 0x3FFF);
		}
		return port;
	}

	/**
	 * Check if retransmission is needed and prepare for it.
	 * Returns false if connection should be aborted (too many retransmits).
	 */
	public boolean checkAndPrepareRetransmission() {
		// Nothing to retransmit if nothing is unacknowledged
		if (sndNext == sndUnack) return true;

		int timeout = NetConfig.TCP_RETRANSMIT_TIMEOUT;
		int now = NumFunctions.now();

		if (state == STATE_SYN_SENT) {
			timeout *= 3;  // SYN retransmit uses longer timeout
		}

		// Check if timeout expired
		if (NumFunctions.isBetweenOrEqualSmaller(sndUnackTime, sndUnackTime + timeout, now)) {
			return true;  // Not yet timed out
		}

		// Check retransmit limit
		if (numRetransmissions >= NetConfig.TCP_MAX_RETRANSMITS) {
			return false;
		}

		// Check total retransmit time limit (60 seconds)
		if (!NumFunctions.isBetweenOrEqualSmaller(timeLastRemoteActivity,
				timeLastRemoteActivity + 60000, now)) {
			return false;  // Give up
		}

		// Perform retransmission
		sndNext = sndUnack;
		if (!oStream.isNoMoreDataToRead()) {
			oStream.setPtrForRetransmit();
		}
		numRetransmissions++;
		sndUnackTime = now;
		return true;
	}

	/**
	 * Check if there's data or control to send.
	 */
	public boolean needsSend() {
		if (state == STATE_LISTEN || state == STATE_CLOSED) return false;
		if (synToSend) return true;
		if (finToSend) return true;
		if (!oStream.isNoMoreDataToRead()) return true;
		return false;
	}

	/**
	 * Called periodically by NetLoop. Handles timeouts and triggers sends.
	 * Returns true if nothing to send or send succeeded,
	 * false if allocation failed (try again later).
	 */
	public boolean poll() {
		if (state == STATE_LISTEN || state == STATE_CLOSED) return true;

		// Handle timeout-based state cleanup
		int now = NumFunctions.now();

		if (state == STATE_TIME_WAIT) {
			if (!NumFunctions.isBetweenOrEqualBigger(timeLastRemoteActivity,
					timeLastRemoteActivity + 2000, now)) {
				deleteConnection(this);
			}
			return true;
		}

		if (state == STATE_SYN_SENT && flushAndClose) {
			deleteConnection(this);
			return true;
		}

		// Keep rcvWindow fresh so outgoing segments carry current window
		rcvWindow = iStream.getFreeBufferSpace();
		if (rcvWindow > 0xFFFF) rcvWindow = 0xFFFF;

		// Reopen window when enough space for at least one full segment (SWS avoidance)
		boolean sendSomething = false;
		if (rcvWindow == 0) {
			if (iStream.getFreeBufferSpace() >= NetConfig.TCP_MSS) {
				rcvWindow = iStream.getFreeBufferSpace();
				if (rcvWindow > 0xFFFF) rcvWindow = 0xFFFF;
				sndUnackTime = now;
				sendSomething = true;
			}
		}

		// Check retransmission
		if (!checkAndPrepareRetransmission()) {
			// Too many retransmits — abort
			abort();
			return true;
		}

		// LAST_ACK: allow retransmission (handled above), but timeout after 60s
		if (state == STATE_LAST_ACK) {
			if (!NumFunctions.isBetweenOrEqualBigger(timeLastRemoteActivity,
					timeLastRemoteActivity + 60000, now)) {
				deleteConnection(this);
				return true;
			}
			// Fall through to FIN guard and send logic for retransmission
		}

		// SYN_RCVD / SYN_SENT: the initial SYN (or SYN-ACK) was already sent.
		// Don't re-send unless retransmission was triggered (sndNext reset to
		// sndUnack). Without this guard, every poll() call re-sends the SYN and
		// advances sndNext, corrupting all subsequent sequence numbers.
		if ((state == STATE_SYN_RCVD || state == STATE_SYN_SENT)
				&& sndNext != sndUnack) {
			return true;
		}

		// FIN_WAIT_1 / CLOSING / LAST_ACK: the FIN was already sent.
		// Don't re-send unless retransmission was triggered (sndNext reset
		// to sndUnack). Same logic as the SYN guard above.
		if ((state == STATE_FIN_WAIT_1 || state == STATE_CLOSING
				|| state == STATE_LAST_ACK)
				&& sndNext != sndUnack) {
			return true;
		}

		// Check if anything to send
		if (oStream.isNoMoreDataToRead() && !sendSomething
				&& !flushAndClose && !synToSend && !finToSend) {
			// Check retransmit timer still active
			if (sndNext == sndUnack) return true;
			// There's unacked data but nothing new to send — let retransmit handle it
			return true;
		}

		// Send a segment
		TCP.sendSegment(this);
		return true;
	}

	/**
	 * Abort the connection — close streams and go to CLOSED.
	 */
	public void abort() {
		oStream.close();
		iStream.close();
		setState(STATE_CLOSED);
	}

	/**
	 * User-initiated close.
	 */
	public void close() {
		if (state == STATE_CLOSED) {
			deleteConnection(this);
			return;
		}
		oStream.close();
		iStream.close();
		flushAndClose = true;
		poll();
	}
}
