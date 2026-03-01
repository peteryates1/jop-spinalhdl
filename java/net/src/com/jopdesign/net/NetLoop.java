package com.jopdesign.net;

import com.jopdesign.sys.JVMHelp;

/**
 * Main network processing loop.
 *
 * Must be called periodically from the application main loop.
 * Handles: receiving frames, dispatching to protocol handlers,
 * polling TCP connections for sends/retransmits, ARP ticks.
 *
 * Usage:
 *   NetLoop.init();
 *   while (true) {
 *       NetLoop.poll();
 *       // application work...
 *       toggleWd();
 *   }
 */
public class NetLoop {

	/** Hex chars for debug output. */
	private static char[] hex = {'0','1','2','3','4','5','6','7',
	                              '8','9','A','B','C','D','E','F'};

	/** Print a byte as 2 hex digits. */
	static void wrHex8(int v) {
		JVMHelp.wr(hex[(v >>> 4) & 0xF]);
		JVMHelp.wr(hex[v & 0xF]);
	}

	/** Print a word as 8 hex digits. */
	static void wrHex32(int v) {
		for (int i = 28; i >= 0; i -= 4) {
			JVMHelp.wr(hex[(v >>> i) & 0xF]);
		}
	}

	/** Shared receive packet. */
	private static Packet rxPkt = null;

	/** Round-robin index for TCP connection polling. */
	private static int tcpPollIdx = 0;

	/**
	 * Initialize the networking stack.
	 * Must be called before poll(). Returns true if PHY link is up.
	 */
	public static boolean init() {
		// Initialize packet pool
		Packet.initPool();

		// Allocate permanent RX packet
		rxPkt = Packet.alloc();

		// Initialize sub-modules
		Arp.init();
		UDPConnection.init();
		TCPConnection.init();

		// Initialize PHY and MAC
		return EthDriver.init();
	}

	/**
	 * One iteration of the network loop.
	 * Call this frequently from the main application loop.
	 */
	public static void poll() {
		// --- Receive ---
		if (rxPkt != null && EthDriver.receive(rxPkt)) {
			// Check if frame is for us
			if (EthDriver.isForUs(rxPkt)) {
				int etherType = EthDriver.getEtherType(rxPkt);
				if (etherType == EthDriver.ETHERTYPE_ARP) {
					Arp.process(rxPkt);
				} else if (etherType == EthDriver.ETHERTYPE_IP) {
					IP.receive(rxPkt);
				}
			}
		}

		// --- Poll one TCP connection per iteration (round-robin) ---
		if (NetConfig.MAX_TCP_CONN > 0) {
			TCPConnection conn = getPoolEntry(tcpPollIdx);
			if (conn != null && conn.inUse) {
				conn.poll();
			}
			tcpPollIdx = (tcpPollIdx + 1) % NetConfig.MAX_TCP_CONN;
		}

		// --- ARP tick ---
		Arp.tick();
	}

	/**
	 * Access TCP connection pool entry by index.
	 * Package-private to avoid exposing pool directly.
	 */
	private static TCPConnection getPoolEntry(int idx) {
		// Access via TCPConnection's getConnection would require different API.
		// Instead, we use a simple approach: TCPConnection.init() creates the pool,
		// and we poll by attempting to match.
		// For simplicity, we add a pool accessor to TCPConnection.
		return TCPConnection.getByIndex(idx);
	}
}
