package test;

import com.jopdesign.sys.*;
import com.jopdesign.net.*;

/**
 * Ethernet RX diagnostic: what the MAC hands the CPU, raw, below the stack.
 *
 * Build:
 *   cd java/apps/Small
 *   make APP_NAME=EthRxDiag EXTRA_SRC=../../net/src JOP_PRESET=<preset>
 *
 * After EthDriver.init(), for every frame the MAC holds: "F", the first word
 * read (the length in bits) and up to eight data words, as read. Twice a
 * second: "S" with the MAC status (IO_ETH+0: bit 1 TX ready, bit 4 RX flush,
 * bit 5 RX valid), the RX errors and drops since the last line (IO_ETH+4,
 * cleared by the read) and the frame count. Each S line also sends a 60-byte
 * broadcast (EtherType 0x88B5, IEEE local experimental, which other stations
 * ignore) so the switch keeps this board's MAC in its table: an unknown-unicast
 * flood can be rate-limited, and frames sent to the board then vanish before
 * they reach its PHY.
 */
public class EthRxDiag {

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

	public static void main(String[] args) {
		JVMHelp.wr("EthRxDiag\n");
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
		int wd = 0;
		for (;;) {
			int t0 = Native.rd(Const.IO_US_CNT);
			while (Native.rd(Const.IO_US_CNT) - t0 < 500000) {
				if ((Native.rd(Const.IO_ETH + 0) & 0x20) == 0) continue;
				int bits = Native.rd(Const.IO_ETH + 3);
				int words = (bits + 31) >>> 5;
				if (words > 400) words = 400;   // a garbage length must not hang the loop
				frames++;
				boolean show = frames <= 30;
				if (show) {
					JVMHelp.wr("F ");
					wrHex(bits, 8);
				}
				for (int i = 0; i < words; i++) {
					int w = Native.rd(Const.IO_ETH + 3);
					if (show && i < 8) {
						JVMHelp.wr(' ');
						wrHex(w, 8);
					}
				}
				if (show) JVMHelp.wr('\n');
			}
			wd = ~wd;
			Native.wr(wd, Const.IO_WD);
			int stats = Native.rd(Const.IO_ETH + 4);
			JVMHelp.wr("S st=");
			wrHex(Native.rd(Const.IO_ETH + 0), 2);
			JVMHelp.wr(" er=");
			wrInt(stats & 0xFF);
			JVMHelp.wr(" dr=");
			wrInt((stats >>> 8) & 0xFF);
			JVMHelp.wr(" frames=");
			wrInt(frames);
			JVMHelp.wr('\n');
			EthDriver.send(keepalive);
		}
	}
}
