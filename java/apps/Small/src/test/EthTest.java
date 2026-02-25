package test;

import com.jopdesign.sys.*;

/**
 * Ethernet diagnostic test v5 — zero-pattern isolation.
 *
 * Tests whether the "every odd word has lower 16 = 0x0000" corruption
 * follows the READ INDEX (I/O read path issue) or the BUFFER ADDRESS
 * (RAM or push-side issue).
 *
 * Method: dump the same frame data twice with different alignments.
 *   Dump A: normal sequential reads (word 0 = buffer addr 0)
 *   Dump B: skip 1 word first, then read (word 0 = buffer addr 1)
 * If zeros follow read index → both show zeros at W1,W3,W5...
 * If zeros follow buffer address → B shows zeros at W0,W2,W4...
 */
public class EthTest {

	static int wd;

	static char[] hexChars = {'0','1','2','3','4','5','6','7',
	                          '8','9','A','B','C','D','E','F'};

	static void wrHex(int val) {
		for (int i = 28; i >= 0; i -= 4) {
			JVMHelp.wr(hexChars[(val >>> i) & 0xF]);
		}
	}

	static void wrHexShort(int val) {
		for (int i = 12; i >= 0; i -= 4) {
			JVMHelp.wr(hexChars[(val >>> i) & 0xF]);
		}
	}

	static void wrInt(int val) {
		if (val < 0) {
			JVMHelp.wr('-');
			val = -val;
		}
		boolean started = false;
		for (int d = 1000000000; d >= 1; d /= 10) {
			int digit = (val / d) % 10;
			if (digit != 0 || started || d == 1) {
				JVMHelp.wr((char)('0' + digit));
				started = true;
			}
		}
	}

	static int le(int b0, int b1, int b2, int b3) {
		return (b0 & 0xFF) | ((b1 & 0xFF) << 8) |
		       ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
	}

	static void toggleWd() {
		wd = ~wd;
		Native.wr(wd, Const.IO_WD);
	}

	static void delay(int n) {
		for (int i = 0; i < n; i++) {
			// busy wait
		}
	}

	static int mdioRead(int phyAddr, int regAddr) {
		Native.wr((regAddr & 0x1F) | ((phyAddr & 0x1F) << 5), Const.IO_MDIO + 2);
		Native.wr(1, Const.IO_MDIO + 0);
		for (int i = 0; i < 100000; i++) {
			if ((Native.rd(Const.IO_MDIO + 0) & 1) == 0) {
				return Native.rd(Const.IO_MDIO + 1) & 0xFFFF;
			}
		}
		return -1;
	}

	static boolean mdioWrite(int phyAddr, int regAddr, int data) {
		Native.wr((regAddr & 0x1F) | ((phyAddr & 0x1F) << 5), Const.IO_MDIO + 2);
		Native.wr(data & 0xFFFF, Const.IO_MDIO + 1);
		Native.wr(3, Const.IO_MDIO + 0);
		for (int i = 0; i < 100000; i++) {
			if ((Native.rd(Const.IO_MDIO + 0) & 1) == 0) {
				return true;
			}
		}
		return false;
	}

	static void sendArp(int targetIp3, int targetIp4) {
		int frameLen = 42;
		int frameBits = frameLen * 8;
		Native.wr(frameBits, Const.IO_ETH + 2);
		Native.wr(le(0xFF,0xFF,0xFF,0xFF), Const.IO_ETH + 2);  // dst[0:3]
		Native.wr(le(0xFF,0xFF,0x02,0x00), Const.IO_ETH + 2);  // dst[4:5]+src[0:1]
		Native.wr(le(0x00,0x00,0x00,0x01), Const.IO_ETH + 2);  // src[2:5]
		Native.wr(le(0x08,0x06,0x00,0x01), Const.IO_ETH + 2);  // EtherType+HTYPE
		Native.wr(le(0x08,0x00,0x06,0x04), Const.IO_ETH + 2);  // PTYPE+HLEN+PLEN
		Native.wr(le(0x00,0x01,0x02,0x00), Const.IO_ETH + 2);  // OPER+SHA[0:1]
		Native.wr(le(0x00,0x00,0x00,0x01), Const.IO_ETH + 2);  // SHA[2:5]
		Native.wr(le(192,168,targetIp3,targetIp4), Const.IO_ETH + 2);  // SPA
		Native.wr(le(0x00,0x00,0x00,0x00), Const.IO_ETH + 2);  // THA[0:3]
		Native.wr(le(0x00,0x00,192,168), Const.IO_ETH + 2);    // THA[4:5]+TPA[0:1]
		Native.wr(le(targetIp3,1,0,0), Const.IO_ETH + 2);      // TPA[2:3]+pad
	}

	/**
	 * Read up to 'max' words from RX stream with status check.
	 * Optionally skip 'skip' words first (to shift alignment).
	 */
	static int rawDump(int max, int skip) {
		// Skip words
		for (int s = 0; s < skip; s++) {
			int st = Native.rd(Const.IO_ETH + 0);
			if ((st & (1 << 5)) == 0) return 0;
			int w = Native.rd(Const.IO_ETH + 3);
			JVMHelp.wr(" skip=");
			wrHex(w);
			JVMHelp.wr("\n");
		}
		// Dump
		int count = 0;
		for (int i = 0; i < max; i++) {
			int st = Native.rd(Const.IO_ETH + 0);
			if ((st & (1 << 5)) == 0) break;

			int word = Native.rd(Const.IO_ETH + 3);
			JVMHelp.wr(" W");
			wrInt(i);
			JVMHelp.wr("=");
			wrHex(word);
			// Mark lower-16 status
			if ((word & 0xFFFF) == 0 && word != 0) {
				JVMHelp.wr(" *LO=0");
			}
			JVMHelp.wr("\n");
			count++;
		}
		return count;
	}

	/**
	 * Drain all pending RX data. Returns number of words drained.
	 */
	static int drainAll() {
		int count = 0;
		for (int i = 0; i < 2048; i++) {
			int st = Native.rd(Const.IO_ETH + 0);
			if ((st & (1 << 5)) == 0) break;
			Native.rd(Const.IO_ETH + 3);
			count++;
		}
		return count;
	}

	static boolean waitRx(int timeout) {
		for (int t = 0; t < timeout; t++) {
			delay(5000);
			if ((t & 0x1FFF) == 0) toggleWd();
			int st = Native.rd(Const.IO_ETH + 0);
			if ((st & (1 << 5)) != 0) return true;
		}
		return false;
	}

	public static void main(String[] args) {

		JVMHelp.wr("Ethernet Test v5\n");

		// --- 1. PHY reset cycle ---
		Native.wr(1, Const.IO_MDIO + 3);
		for (int i = 0; i < 5; i++) { delay(500000); toggleWd(); }
		Native.wr(0, Const.IO_MDIO + 3);
		for (int i = 0; i < 30; i++) { delay(500000); toggleWd(); }

		// --- 2. Scan MDIO bus for PHY ---
		int phyAddr = -1;
		for (int a = 0; a < 32; a++) {
			toggleWd();
			int id1 = mdioRead(a, 2);
			int id2 = mdioRead(a, 3);
			if (id1 == -1 || id2 == -1) continue;
			if (id1 == 0xFFFF || id1 == 0x0000) continue;
			phyAddr = a;
			JVMHelp.wr("PHY=");
			wrInt(a);
			JVMHelp.wr(" ");
			wrHexShort(id1);
			JVMHelp.wr(" ");
			wrHexShort(id2);
			JVMHelp.wr('\n');
			break;
		}
		if (phyAddr == -1) {
			JVMHelp.wr("No PHY!\n");
			for (;;) { toggleWd(); delay(500000); }
		}

		// --- 3. Force 100 Mbps ---
		mdioWrite(phyAddr, 9, 0x0000);
		mdioWrite(phyAddr, 4, 0x0181);
		mdioWrite(phyAddr, 0, 0x1200);

		int status = 0;
		for (int i = 0; i < 200; i++) {
			delay(500000);
			if ((i & 0xF) == 0) toggleWd();
			status = mdioRead(phyAddr, 1);
			if (status == -1) continue;
			if ((status & (1 << 2)) != 0 && (status & (1 << 5)) != 0) break;
		}
		JVMHelp.wr((status & (1 << 2)) != 0 ? "UP\n" : "DOWN\n");
		if ((status & (1 << 2)) == 0) {
			for (;;) { toggleWd(); delay(500000); }
		}
		for (int i = 0; i < 10; i++) { delay(500000); toggleWd(); }

		// --- 4. Flush + unflushed ---
		Native.wr((1 << 0) | (1 << 4), Const.IO_ETH + 0);
		for (int i = 0; i < 5; i++) { delay(500000); toggleWd(); }
		Native.wr(0, Const.IO_ETH + 0);
		for (int i = 0; i < 10; i++) { delay(500000); toggleWd(); }
		int drained = drainAll();
		Native.rd(Const.IO_ETH + 4);  // clear stats

		// =====================================================
		// TEST A: Normal dump (skip=0) — frame 1
		// =====================================================
		JVMHelp.wr("=== TEST A: skip=0 ===\n");
		sendArp(0, 1);
		if (waitRx(30000)) {
			rawDump(20, 0);
		} else {
			JVMHelp.wr("no rx\n");
		}
		drainAll();  // drain rest

		// =====================================================
		// TEST B: Shifted dump (skip=1) — frame 2
		// =====================================================
		JVMHelp.wr("=== TEST B: skip=1 ===\n");
		sendArp(0, 1);
		if (waitRx(30000)) {
			rawDump(20, 1);
		} else {
			JVMHelp.wr("no rx\n");
		}
		drainAll();

		// =====================================================
		// TEST C: Shifted dump (skip=2) — frame 3
		// =====================================================
		JVMHelp.wr("=== TEST C: skip=2 ===\n");
		sendArp(0, 1);
		if (waitRx(30000)) {
			rawDump(20, 2);
		} else {
			JVMHelp.wr("no rx\n");
		}
		drainAll();

		// =====================================================
		// TEST D: Burst read (no status checks) — frame 4
		// =====================================================
		JVMHelp.wr("=== TEST D: burst ===\n");
		sendArp(0, 1);
		if (waitRx(30000)) {
			// Read 20 words WITHOUT checking status between reads
			for (int i = 0; i < 20; i++) {
				int word = Native.rd(Const.IO_ETH + 3);
				JVMHelp.wr(" D");
				wrInt(i);
				JVMHelp.wr("=");
				wrHex(word);
				if ((word & 0xFFFF) == 0 && word != 0) {
					JVMHelp.wr(" *LO=0");
				}
				JVMHelp.wr("\n");
			}
		} else {
			JVMHelp.wr("no rx\n");
		}
		drainAll();

		// --- Final stats ---
		int stats = Native.rd(Const.IO_ETH + 4);
		JVMHelp.wr("err=");
		wrInt(stats & 0xFF);
		JVMHelp.wr(" drop=");
		wrInt((stats >>> 8) & 0xFF);
		JVMHelp.wr('\n');

		JVMHelp.wr("Done\n");
		for (;;) { toggleWd(); delay(500000); }
	}
}
