package test;

import com.jopdesign.sys.*;

/**
 * Read-only dump of the Ethernet PHYs' registers over MDIO, for every address
 * that answers -- written to find the B50612D's RGMII clock delays before an
 * RGMII 1000 design is chosen (Colorlight i9+, 2026-11-10).
 *
 * The B50612D reports PHY ID 0362:5E6x, Broadcom's BCM54612E. Its RGMII delays
 * live in two shadow registers, read the way Linux's bcm-phy-lib reads them:
 *   0x18 shadow 7 (misc control): write 0x7007, read 0x18.
 *     bit 8 = RXD-to-RXC skew (the PHY delays RXC), bit 7 = RGMII enable.
 *   0x1C shadow 3 (clock alignment): write 0x0C00, read 0x1C.
 *     bit 9 = GTXCLK delay (the PHY delays TXC).
 * Neither select write has its write-enable bit (bit 15) set, so nothing in
 * the PHY changes. Nothing else is written.
 */
public class PhyRegs {

	static char[] hexChars = {'0','1','2','3','4','5','6','7',
	                          '8','9','A','B','C','D','E','F'};

	static void wrStr(String s) {
		JVMHelp.wr(s);
	}

	static void wrHex4(int val) {
		for (int i = 12; i >= 0; i -= 4) JVMHelp.wr(hexChars[(val >>> i) & 0xF]);
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

	static void field(String name, int val) {
		wrStr(" ");
		wrStr(name);
		wrStr("=");
		if (val < 0) wrStr("----"); else wrHex4(val);
	}

	static void onOff(String name, int val, int bit) {
		wrStr("  ");
		wrStr(name);
		if (val < 0) wrStr(" ?");
		else wrStr(((val >>> bit) & 1) != 0 ? " ON" : " off");
		wrStr("\n");
	}

	public static void main(String[] args) {
		wrStr("PhyRegs\n");
		for (int a = 0; a < 32; a++) {
			int id1 = mdioRead(a, 2);
			int id2 = mdioRead(a, 3);
			if (id1 < 0 || id1 == 0xFFFF || id1 == 0) continue;
			wrStr("PHY ");
			JVMHelp.wr((char) ('0' + a / 10));
			JVMHelp.wr((char) ('0' + a % 10));
			field("ID", id1);
			wrHex4(id2);
			field("BMCR", mdioRead(a, 0));
			field("BMSR", mdioRead(a, 1));
			field("ANAR", mdioRead(a, 4));
			field("ANLPAR", mdioRead(a, 5));
			field("CTL1000", mdioRead(a, 9));
			field("STAT1000", mdioRead(a, 10));
			field("AUXSTAT", mdioRead(a, 0x19));
			int misc = mdioWrite(a, 0x18, 0x7007) ? mdioRead(a, 0x18) : -1;
			int clk = mdioWrite(a, 0x1C, 0x0C00) ? mdioRead(a, 0x1C) : -1;
			field("MISC", misc);
			field("CLKALIGN", clk);
			wrStr("\n");
			onOff("RGMII enable (misc bit 7):", misc, 7);
			onOff("RX delay, RXC skew (misc bit 8):", misc, 8);
			onOff("TX delay, GTXCLK (clkalign bit 9):", clk, 9);
		}
		wrStr("PHYREGS DONE\n");
	}
}
