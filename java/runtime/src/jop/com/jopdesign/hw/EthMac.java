package com.jopdesign.hw;

import com.jopdesign.sys.Const;

/**
 * Hardware object for the Ethernet MAC (BmbEth).
 *
 * Wraps SpinalHDL MacEth with JOP I/O interface. Supports MII (100Mbps)
 * and GMII (1Gbps) modes. Frame data is little-endian byte order within
 * each 32-bit word.
 *
 * Fields map to sequential I/O registers at Const.IO_ETH:
 *   +0  statusControl   R: status  W: control (flush)
 *   +1  txAvailability  R: free words in TX FIFO
 *   +2  txData          W: TX data push (first write = bit length, then words)
 *   +3  rxData          R: RX data pop (first read = bit length, then words)
 *   +4  rxStats         R: error[7:0], drop[15:8] (auto-clear on read)
 */
public final class EthMac extends HardwareObject {

	// Status bits (read)
	public static final int STATUS_TX_FLUSH  = 1 << 0;
	public static final int STATUS_TX_READY  = 1 << 1;
	public static final int STATUS_RX_FLUSH  = 1 << 4;
	public static final int STATUS_RX_VALID  = 1 << 5;

	// Control bits (write)
	public static final int CTRL_TX_FLUSH = 1 << 0;
	public static final int CTRL_RX_FLUSH = 1 << 4;

	// Singleton
	private static EthMac instance = null;

	public static EthMac getInstance() {
		if (instance == null)
			instance = (EthMac) make(new EthMac(), Const.IO_ETH, 0);
		return instance;
	}

	// --- Hardware registers (sequential, volatile) ---

	/** +0: R=status, W=control (flush bits) */
	public volatile int statusControl;
	/** +1: R=TX availability (free words in TX buffer) */
	public volatile int txAvailability;
	/** +2: W=TX data push */
	public volatile int txData;
	/** +3: R=RX data pop (auto-pop on read) */
	public volatile int rxData;
	/** +4: R=RX stats: errors[7:0], drops[15:8] (auto-clear) */
	public volatile int rxStats;

	// --- Convenience methods ---

	/** Return true if a received frame is available. */
	public boolean rxValid() {
		return (statusControl & STATUS_RX_VALID) != 0;
	}

	/** Return true if TX buffer can accept data. */
	public boolean txReady() {
		return (statusControl & STATUS_TX_READY) != 0;
	}

	/** Flush both TX and RX buffers. */
	public void flush() {
		statusControl = CTRL_TX_FLUSH | CTRL_RX_FLUSH;
	}

	/** Release flush (must call after flush). */
	public void unflush() {
		statusControl = 0;
	}

	/** Pack 4 bytes in little-endian order into a 32-bit word. */
	public static int le(int b0, int b1, int b2, int b3) {
		return (b0 & 0xFF) | ((b1 & 0xFF) << 8)
			| ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
	}

	/** Get RX error count from stats word. */
	public static int errors(int stats) {
		return stats & 0xFF;
	}

	/** Get RX drop count from stats word. */
	public static int drops(int stats) {
		return (stats >>> 8) & 0xFF;
	}
}
