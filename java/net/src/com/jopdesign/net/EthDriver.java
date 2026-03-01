package com.jopdesign.net;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.Native;
/**
 * Ethernet link layer driver for BmbEth MAC hardware.
 *
 * Handles PHY initialization via MDIO, and provides send/receive of raw
 * Ethernet frames using the EthMac I/O registers.
 */
public class EthDriver {

	/** Ethernet header length in bytes. */
	public static final int ETH_HEADER = 14;

	/** EtherType: IPv4 */
	public static final int ETHERTYPE_IP = 0x0800;

	/** EtherType: ARP */
	public static final int ETHERTYPE_ARP = 0x0806;

	/** Broadcast MAC high 16 bits. */
	public static final int BCAST_MAC_HI = 0xFFFF;

	/** Broadcast MAC low 32 bits. */
	public static final int BCAST_MAC_LO = 0xFFFFFFFF;

	/** PHY address discovered by MDIO scan. */
	private static int phyAddr = -1;

	/** Watchdog toggle state. */
	private static int wd = 0;

	private static void toggleWd() {
		wd = ~wd;
		Native.wr(wd, Const.IO_WD);
	}

	private static void delay(int n) {
		for (int i = 0; i < n; i++) {
			// busy wait
		}
	}

	// --- MDIO ---

	private static int mdioRead(int phy, int reg) {
		Native.wr((reg & 0x1F) | ((phy & 0x1F) << 5), Const.IO_MDIO + 2);
		Native.wr(1, Const.IO_MDIO + 0);
		for (int i = 0; i < 100000; i++) {
			if ((Native.rd(Const.IO_MDIO + 0) & 1) == 0) {
				return Native.rd(Const.IO_MDIO + 1) & 0xFFFF;
			}
		}
		return -1;
	}

	private static boolean mdioWrite(int phy, int reg, int data) {
		Native.wr((reg & 0x1F) | ((phy & 0x1F) << 5), Const.IO_MDIO + 2);
		Native.wr(data & 0xFFFF, Const.IO_MDIO + 1);
		Native.wr(3, Const.IO_MDIO + 0);
		for (int i = 0; i < 100000; i++) {
			if ((Native.rd(Const.IO_MDIO + 0) & 1) == 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Initialize the Ethernet hardware: PHY reset, MDIO scan, autonegotiate,
	 * flush MAC buffers. Returns true if link is up.
	 */
	public static boolean init() {
		// PHY hardware reset via MDIO reset pin
		Native.wr(1, Const.IO_MDIO + 3);
		for (int i = 0; i < 5; i++) { delay(500000); toggleWd(); }
		Native.wr(0, Const.IO_MDIO + 3);
		for (int i = 0; i < 30; i++) { delay(500000); toggleWd(); }

		// Scan MDIO bus for PHY
		phyAddr = -1;
		for (int a = 0; a < 32; a++) {
			toggleWd();
			int id1 = mdioRead(a, 2);
			int id2 = mdioRead(a, 3);
			if (id1 == -1 || id2 == -1) continue;
			if (id1 == 0xFFFF || id1 == 0x0000) continue;
			phyAddr = a;
			break;
		}
		if (phyAddr == -1) {
			return false;
		}

		// Enable 1 Gbps (GMII) autonegotiation
		mdioWrite(phyAddr, 9, 0x0200);    // Advertise 1000BASE-T FD
		mdioWrite(phyAddr, 4, 0x01E1);    // Advertise 10/100M all modes
		mdioWrite(phyAddr, 0, 0x1200);    // Auto-negotiate restart

		// Wait for link up + autoneg complete
		int status = 0;
		for (int i = 0; i < 200; i++) {
			delay(500000);
			if ((i & 0xF) == 0) toggleWd();
			status = mdioRead(phyAddr, 1);
			if (status == -1) continue;
			if ((status & (1 << 2)) != 0 && (status & (1 << 5)) != 0) break;
		}
		boolean linkUp = (status & (1 << 2)) != 0;

		// Post-link stabilization delay
		for (int i = 0; i < 10; i++) { delay(500000); toggleWd(); }

		// Flush and release MAC
		Native.wr((1 << 0) | (1 << 4), Const.IO_ETH + 0);
		for (int i = 0; i < 5; i++) { delay(500000); toggleWd(); }
		Native.wr(0, Const.IO_ETH + 0);
		for (int i = 0; i < 10; i++) { delay(500000); toggleWd(); }

		// Drain any stale RX data
		for (int i = 0; i < 2048; i++) {
			if ((Native.rd(Const.IO_ETH + 0) & (1 << 5)) == 0) break;
			Native.rd(Const.IO_ETH + 3);
		}
		// Clear stats
		Native.rd(Const.IO_ETH + 4);

		return linkUp;
	}

	/**
	 * Read PHY-specific status register (RTL8211EG reg 17).
	 * Returns negotiated speed: 1000, 100, or 10. Returns -1 on error.
	 */
	public static int getLinkSpeed() {
		if (phyAddr == -1) return -1;
		int reg17 = mdioRead(phyAddr, 17);
		if (reg17 == -1) return -1;
		int speed = (reg17 >>> 14) & 3;
		if (speed == 2) return 1000;
		if (speed == 1) return 100;
		return 10;
	}

	/**
	 * Send an Ethernet frame. The packet must have the full Ethernet header
	 * (dst MAC, src MAC, EtherType) already set at bytes 0-13.
	 *
	 * @param pkt the packet with frame data
	 */
	public static void send(Packet pkt) {
		int byteLen = pkt.len;
		// Minimum Ethernet frame = 60 bytes (excl. FCS)
		if (byteLen < 60) byteLen = 60;
		int bitLen = byteLen * 8;
		int wordCount = (byteLen + 3) / 4;

		// Write bit length, then data words
		// Wait for TX ready (bit 1 of status) before each write
		waitTxReady();
		Native.wr(bitLen, Const.IO_ETH + 2);
		for (int i = 0; i < wordCount; i++) {
			waitTxReady();
			Native.wr(pkt.buf[i], Const.IO_ETH + 2);
		}
	}

	/** Wait for TX holding register to be free before writing. */
	private static void waitTxReady() {
		while ((Native.rd(Const.IO_ETH + 0) & (1 << 1)) == 0) {
			// busy wait
		}
	}

	/**
	 * Try to receive an Ethernet frame. Returns true if a frame was received,
	 * false if no frame is available.
	 *
	 * @param pkt the packet to receive into (buf and len will be set)
	 * @return true if a frame was received
	 */
	public static boolean receive(Packet pkt) {
		// Check RX valid
		if ((Native.rd(Const.IO_ETH + 0) & (1 << 5)) == 0) {
			return false;
		}
		// First read = bit length
		int bitLen = Native.rd(Const.IO_ETH + 3);
		int byteLen = (bitLen + 7) / 8;
		int origWordCount = (byteLen + 3) / 4;
		if (byteLen > Packet.MAX_BYTES) byteLen = Packet.MAX_BYTES;
		int wordCount = (byteLen + 3) / 4;

		pkt.len = byteLen;
		for (int i = 0; i < wordCount; i++) {
			pkt.buf[i] = Native.rd(Const.IO_ETH + 3);
		}

		// Drain remaining words if frame was truncated
		for (int i = wordCount; i < origWordCount; i++) {
			Native.rd(Const.IO_ETH + 3);
		}

		return true;
	}

	/**
	 * Set the Ethernet source MAC in a packet's header from our config.
	 * Bytes 6-11 = our MAC.
	 */
	public static void setSrcMac(Packet pkt) {
		for (int i = 0; i < 6; i++) {
			pkt.setByte(6 + i, NetConfig.getMacByte(i));
		}
	}

	/**
	 * Set the Ethernet destination MAC in a packet's header.
	 * @param macHi high 16 bits of MAC (bytes 0-1)
	 * @param macLo low 32 bits of MAC (bytes 2-5)
	 */
	public static void setDstMac(Packet pkt, int macHi, int macLo) {
		pkt.setByte(0, (macHi >>> 8) & 0xFF);
		pkt.setByte(1, macHi & 0xFF);
		pkt.setByte(2, (macLo >>> 24) & 0xFF);
		pkt.setByte(3, (macLo >>> 16) & 0xFF);
		pkt.setByte(4, (macLo >>> 8) & 0xFF);
		pkt.setByte(5, macLo & 0xFF);
	}

	/**
	 * Set the EtherType field in a packet.
	 */
	public static void setEtherType(Packet pkt, int etherType) {
		pkt.setShort(12, etherType);
	}

	/**
	 * Get the EtherType from a received packet.
	 */
	public static int getEtherType(Packet pkt) {
		return pkt.getShort(12);
	}

	/**
	 * Get source MAC high 16 bits from a received packet.
	 */
	public static int getSrcMacHi(Packet pkt) {
		return (pkt.getByte(6) << 8) | pkt.getByte(7);
	}

	/**
	 * Read and clear MAC RX stats register.
	 * Returns: bits [7:0]=errors, [15:8]=drops since last read.
	 */
	public static int readRxStats() {
		return Native.rd(Const.IO_ETH + 4);
	}

	/**
	 * Get source MAC low 32 bits from a received packet.
	 */
	public static int getSrcMacLo(Packet pkt) {
		return (pkt.getByte(8) << 24) | (pkt.getByte(9) << 16)
			| (pkt.getByte(10) << 8) | pkt.getByte(11);
	}

	/**
	 * Check if a received packet's destination MAC matches ours or is broadcast.
	 */
	public static boolean isForUs(Packet pkt) {
		// Check broadcast
		if (pkt.getByte(0) == 0xFF && pkt.getByte(1) == 0xFF
			&& pkt.getByte(2) == 0xFF && pkt.getByte(3) == 0xFF
			&& pkt.getByte(4) == 0xFF && pkt.getByte(5) == 0xFF) {
			return true;
		}
		// Check our MAC
		for (int i = 0; i < 6; i++) {
			if (pkt.getByte(i) != NetConfig.getMacByte(i)) {
				return false;
			}
		}
		return true;
	}
}
