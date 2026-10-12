package test;

import com.jopdesign.sys.*;
import com.jopdesign.net.*;

/**
 * Ethernet RX capture dump (status item 166). Needs a preset whose MAC has
 * `rxCapture` (jop.io.Mac), e.g. ep4cgx150DbV5Eth: a logic analyser on the
 * receive stream as it enters the MAC, one entry per RX clock, around one frame.
 *
 * Build:
 *   cd java/apps/Small
 *   make APP_NAME=EthCapDiag EXTRA_SRC=../../net/src JOP_PRESET=ep4cgx150DbV5Eth
 *
 * The first capture is the next frame (mode 0), a reference; every later one is
 * the next frame the MAC rejects or never commits (mode 1), up to MAX_CAPTURES.
 * Each prints
 *   "C <n> <mode> <status hex> <entries>"
 * then the entries, four hex digits each, 32 to an "E" line, then "C end".
 * Entry bits: 7:0 RXD, 8 RX_DV, 9 RX_ER, 10 last, 11 MAC commit, 12 MAC error,
 * 13 MAC drop. Like EthRxDiag it reads and discards every frame the MAC
 * delivers, prints "S" twice a second, and sends a broadcast keepalive with
 * each S line so the switch keeps this board's MAC in its table.
 */
public class EthCapDiag {

	static final int CAP_CTRL = Const.IO_ETH + 5;   // write: arm, bit 0 = mode; read: status
	static final int CAP_START = Const.IO_ETH + 6;  // read: first entry; write: read pointer
	static final int CAP_DATA = Const.IO_ETH + 7;   // read: entry at the pointer, which advances
	static final int MAX_CAPTURES = 40;

	static char[] hex = {'0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f'};

	static void wrHex(int v, int digits) {
		for (int i = (digits - 1) * 4; i >= 0; i -= 4) {
			JVMHelp.wr(hex[(v >>> i) & 0xF]);
		}
	}

	static void wrInt(int v) {
		if (v >= 10) wrInt(v / 10);
		JVMHelp.wr((char) ('0' + v % 10));
	}

	/** Arm, then wait for done to clear: the arm crosses to the RX clock and back. */
	static void arm(int mode) {
		Native.wr(mode, CAP_CTRL);
		int t0 = Native.rd(Const.IO_US_CNT);
		while ((Native.rd(CAP_CTRL) & 1) != 0 && Native.rd(Const.IO_US_CNT) - t0 < 1000) {
		}
	}

	static void dump(int n, int mode, int status) {
		int len = (status >>> 16) & 0xFFF;
		Native.wr(Native.rd(CAP_START), CAP_START);
		JVMHelp.wr("C ");
		wrInt(n);
		JVMHelp.wr(' ');
		wrInt(mode);
		JVMHelp.wr(' ');
		wrHex(status, 8);
		JVMHelp.wr(' ');
		wrInt(len);
		JVMHelp.wr('\n');
		for (int i = 0; i < len; i++) {
			if ((i & 31) == 0) JVMHelp.wr('E');
			JVMHelp.wr(' ');
			wrHex(Native.rd(CAP_DATA), 4);
			if ((i & 31) == 31 || i == len - 1) JVMHelp.wr('\n');
		}
		JVMHelp.wr("C end\n");
	}

	public static void main(String[] args) {
		JVMHelp.wr("EthCapDiag\n");
		boolean up = EthDriver.init();
		JVMHelp.wr(up ? "Link UP " : "Link DOWN ");
		wrInt(EthDriver.getLinkSpeed());
		JVMHelp.wr("M\n");

		Packet keepalive = new Packet();
		EthDriver.setDstMac(keepalive, EthDriver.BCAST_MAC_HI, EthDriver.BCAST_MAC_LO);
		EthDriver.setSrcMac(keepalive);
		EthDriver.setEtherType(keepalive, 0x88B5);
		keepalive.len = 60;

		int frames = 0;
		int captures = 0;
		int mode = 0;
		boolean armed = true;
		int wd = 0;
		arm(mode);
		for (;;) {
			int t0 = Native.rd(Const.IO_US_CNT);
			while (Native.rd(Const.IO_US_CNT) - t0 < 500000) {
				if ((Native.rd(Const.IO_ETH + 0) & 0x20) != 0) {
					int bits = Native.rd(Const.IO_ETH + 3);
					int words = (bits + 31) >>> 5;
					if (words > 400) words = 400;   // a garbage length must not hang the loop
					for (int i = 0; i < words; i++) {
						Native.rd(Const.IO_ETH + 3);
					}
					frames++;
				}
				if (armed) {
					int status = Native.rd(CAP_CTRL);
					if ((status & 1) != 0) {
						dump(captures, mode, status);
						captures++;
						mode = 1;
						if (captures < MAX_CAPTURES) {
							arm(mode);
						} else {
							armed = false;
						}
					}
				}
			}
			wd = ~wd;
			Native.wr(wd, Const.IO_WD);
			int stats = Native.rd(Const.IO_ETH + 4);
			JVMHelp.wr("S er=");
			wrInt(stats & 0xFF);
			JVMHelp.wr(" dr=");
			wrInt((stats >>> 8) & 0xFF);
			JVMHelp.wr(" frames=");
			wrInt(frames);
			JVMHelp.wr(" captures=");
			wrInt(captures);
			JVMHelp.wr('\n');
			EthDriver.send(keepalive);
		}
	}
}
