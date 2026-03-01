package com.jopdesign.net;

/**
 * UDP connection pool.
 *
 * Each connection is bound to a local port and optionally to a remote
 * address/port. Received datagrams are stored in a single-slot buffer
 * (latest wins).
 */
public class UDPConnection {

	/** Connection pool. */
	private static UDPConnection[] pool = null;

	/** Local port. */
	public int localPort;

	/** True if connection is in use. */
	public boolean inUse;

	// Receive buffer (single datagram)
	private byte[] rxBuf;
	private int rxLen;
	private int rxSrcIp;
	private int rxSrcPort;
	private boolean rxReady;

	/** Maximum UDP payload size. */
	public static final int MAX_DATA = 1472;

	public UDPConnection() {
		localPort = 0;
		inUse = false;
		rxBuf = new byte[MAX_DATA];
		rxLen = 0;
		rxSrcIp = 0;
		rxSrcPort = 0;
		rxReady = false;
	}

	/** Initialize the connection pool. */
	public static void init() {
		pool = new UDPConnection[NetConfig.MAX_UDP_CONN];
		for (int i = 0; i < NetConfig.MAX_UDP_CONN; i++) {
			pool[i] = new UDPConnection();
		}
	}

	/**
	 * Open a UDP connection on a local port.
	 * @return the connection, or null if pool full or port in use
	 */
	public static UDPConnection open(int port) {
		if (pool == null) return null;
		// Check for duplicate
		for (int i = 0; i < NetConfig.MAX_UDP_CONN; i++) {
			if (pool[i].inUse && pool[i].localPort == port) {
				return null;
			}
		}
		// Find free slot
		for (int i = 0; i < NetConfig.MAX_UDP_CONN; i++) {
			if (!pool[i].inUse) {
				pool[i].inUse = true;
				pool[i].localPort = port;
				pool[i].rxReady = false;
				pool[i].rxLen = 0;
				return pool[i];
			}
		}
		return null;
	}

	/** Close this connection. */
	public void close() {
		inUse = false;
		rxReady = false;
	}

	/** Find a connection by destination port. */
	public static UDPConnection findByPort(int port) {
		if (pool == null) return null;
		for (int i = 0; i < NetConfig.MAX_UDP_CONN; i++) {
			if (pool[i].inUse && pool[i].localPort == port) {
				return pool[i];
			}
		}
		return null;
	}

	/**
	 * Called by UDP layer when a datagram arrives for this connection.
	 */
	public void receiveData(Packet pkt, int dataOff, int dataLen, int srcIp, int srcPort) {
		if (dataLen > MAX_DATA) dataLen = MAX_DATA;
		pkt.copyTo(dataOff, rxBuf, 0, dataLen);
		rxLen = dataLen;
		rxSrcIp = srcIp;
		rxSrcPort = srcPort;
		rxReady = true;
	}

	/**
	 * Poll for received data. Returns the number of bytes received,
	 * or -1 if no data is available.
	 *
	 * @param buf destination buffer
	 * @param off offset into buf
	 * @param maxLen maximum bytes to copy
	 * @return bytes copied, or -1 if no data
	 */
	public int receive(byte[] buf, int off, int maxLen) {
		if (!rxReady) return -1;
		int copyLen = rxLen;
		if (copyLen > maxLen) copyLen = maxLen;
		for (int i = 0; i < copyLen; i++) {
			buf[off + i] = rxBuf[i];
		}
		rxReady = false;
		return copyLen;
	}

	/**
	 * Send a UDP datagram from this connection.
	 */
	public boolean send(int destIp, int dstPort, byte[] data, int off, int len) {
		return UDP.send(destIp, localPort, dstPort, data, off, len);
	}

	/** Get source IP of last received datagram. */
	public int getLastSrcIp() {
		return rxSrcIp;
	}

	/** Get source port of last received datagram. */
	public int getLastSrcPort() {
		return rxSrcPort;
	}

	/** Check if received data is available. */
	public boolean hasData() {
		return rxReady;
	}

	/**
	 * Generate a random unused local port.
	 */
	public static int newLocalPort() {
		// Simple LCG for random port in range 49152-65535
		int port = 49152 + (NumFunctions.now() & 0x3FFF);
		for (int attempt = 0; attempt < 100; attempt++) {
			boolean used = false;
			if (pool != null) {
				for (int i = 0; i < NetConfig.MAX_UDP_CONN; i++) {
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
}
