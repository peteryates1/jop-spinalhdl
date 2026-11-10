package test;

import com.jopdesign.sys.*;
import com.jopdesign.net.*;

/**
 * Network stack hardware test — entry point.
 *
 * Build:
 *   cd java/apps/Small
 *   make clean && make all APP_NAME=NetTest EXTRA_SRC=../../net/src
 *
 * Address: DHCP, sending the unit's name as its hostname (Const.ETH_HOSTNAME,
 * from its board instance -- status item 174), so a unit on a LAN whose DHCP
 * server registers names (Pi-hole here) is reached by name. There is no
 * static fallback: with no lease within DHCP_WAIT_MS it prints
 * "DHCP: no lease" and stops. The console prints the leased address.
 *
 * Test from host:
 *   ping jop-xc7a100t-1.lan                  (or the IP= the console printed)
 *   echo "hello" | nc -u -w1 jop-xc7a100t-1.lan 7
 *   echo "hello" | nc -w1 jop-xc7a100t-1.lan 7
 *   fpga/scripts/net_echo_check.py jop-xc7a100t-1.lan  (RST, idle, dead clients)
 */
public class NetTest {

	static int wd = 0;

	/** How long to wait for a lease before giving up. The client
	 * restarts discovery after 4 retries 4 s apart, so this allows one restart. */
	static final int DHCP_WAIT_MS = 30000;

	/** A client that sends nothing for this long is dropped (status item 171):
	 * a peer that vanished without FIN or RST would otherwise hold its pool
	 * slot for the rest of the boot. */
	static final int TCP_IDLE_MS = 30000;

	/** TCP echo clients, one per pool slot. */
	static TCPConnection[] tcpClients = new TCPConnection[NetConfig.MAX_TCP_CONN];

	/** What an echo moves from a client's receive stream to its send stream. */
	static byte[] echoBuf = new byte[4096];

	static char[] hexChars = {'0','1','2','3','4','5','6','7',
	                          '8','9','A','B','C','D','E','F'};

	static void wrHex(int val) {
		for (int i = 28; i >= 0; i -= 4) {
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

	static void wrIp(int ip) {
		wrInt((ip >>> 24) & 0xFF);
		JVMHelp.wr('.');
		wrInt((ip >>> 16) & 0xFF);
		JVMHelp.wr('.');
		wrInt((ip >>> 8) & 0xFF);
		JVMHelp.wr('.');
		wrInt(ip & 0xFF);
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

	/**
	 * Echo what client i sent. Returns false once the client is gone; its
	 * connection is then closed, which hands the slot back to the pool -- a
	 * connection the stack aborted (RST, retransmits exhausted) stays CLOSED
	 * but allocated until close() is called on it.
	 */
	static boolean echo(int i) {
		TCPConnection c = tcpClients[i];
		if (c.state == TCPConnection.STATE_CLOSED) {
			c.close();
			JVMHelp.wr("TA\n");
			return false;
		}
		if (c.state != TCPConnection.STATE_ESTABLISHED
				&& c.state != TCPConnection.STATE_CLOSE_WAIT) {
			return true;
		}
		if (NumFunctions.now() - c.timeLastRemoteActivity > TCP_IDLE_MS) {
			c.abort();
			c.close();
			JVMHelp.wr("TI\n");
			return false;
		}
		// As much as the send stream has room for, a pass each way: a byte
		// at a time cost a call per byte per stream, ~1000 cycles each
		int n = c.iStream.available();
		int free = c.oStream.getFreeBufferSpace();
		if (n > free) n = free;
		if (n > echoBuf.length) n = echoBuf.length;
		if (n > 0) {
			c.iStream.read(echoBuf, 0, n);
			c.oStream.write(echoBuf, 0, n);
		}
		// Only close when all data has been echoed
		if (c.iStream.available() == 0 && c.state == TCPConnection.STATE_CLOSE_WAIT) {
			c.close();
			JVMHelp.wr("TX\n");
			return false;
		}
		return true;
	}

	public static void main(String[] args) {
		JVMHelp.wr("NetTest v3\n");

		// This unit's MAC: its board instance's, via Const (status item 174)
		JVMHelp.wr("MAC=");
		for (int i = 0; i < 6; i++) {
			if (i > 0) JVMHelp.wr(':');
			int b = NetConfig.getMacByte(i);
			JVMHelp.wr(hexChars[(b >>> 4) & 0xF]);
			JVMHelp.wr(hexChars[b & 0xF]);
		}
		JVMHelp.wr('\n');

		// DHCP only. Must be set before NetLoop.init(), which starts
		// discovery (and zeroes NetConfig.ip).
		NetConfig.useDhcp = true;

		// Initialize networking stack
		JVMHelp.wr("Init...\n");
		boolean linkUp = NetLoop.init();
		if (!linkUp) {
			JVMHelp.wr("Link DOWN\n");
			for (;;) { toggleWd(); delay(500000); }
		}
		JVMHelp.wr("Link UP ");
		wrInt(EthDriver.getLinkSpeed());
		JVMHelp.wr("M\n");

		// Wait for a lease, polling the stack; without one, report it and stop.
		int t0 = NumFunctions.now();
		while (NetConfig.dhcpActive || NetConfig.ip == 0) {
			toggleWd();
			NetLoop.poll();
			if (NumFunctions.now() - t0 > DHCP_WAIT_MS) {
				JVMHelp.wr("DHCP: no lease\n");
				for (;;) { toggleWd(); delay(500000); }
			}
		}
		JVMHelp.wr("IP=");
		wrIp(NetConfig.ip);
		JVMHelp.wr('\n');

		// Open UDP echo server on port 7
		UDPConnection udpEcho = UDPConnection.open(7);
		if (udpEcho == null) {
			JVMHelp.wr("UDP fail\n");
		} else {
			JVMHelp.wr("UDP:7\n");
		}

		// Open TCP echo server on port 7
		TCPConnection tcpListen = TCP.listen(7);
		if (tcpListen == null) {
			JVMHelp.wr("TCP fail\n");
		} else {
			JVMHelp.wr("TCP:7\n");
		}

		byte[] udpBuf = new byte[1472];
		int rxErrors = 0;
		int rxDrops = 0;

		// Send gratuitous ARP to announce ourselves on the LAN
		JVMHelp.wr("GARP\n");
		Arp.sendGratuitous();
		delay(500000);
		Arp.sendGratuitous();
		delay(500000);
		Arp.sendGratuitous();

		JVMHelp.wr("Ready\n");
		int loopCount = 0;

		for (;;) {
			toggleWd();

			// Poll network
			NetLoop.poll();

			// --- UDP echo ---
			if (udpEcho != null && udpEcho.hasData()) {
				int n = udpEcho.receive(udpBuf, 0, udpBuf.length);
				if (n > 0) {
					udpEcho.send(udpEcho.getLastSrcIp(), udpEcho.getLastSrcPort(),
						udpBuf, 0, n);
					JVMHelp.wr("U");
					wrInt(n);
					JVMHelp.wr('\n');
				}
			}

			// --- TCP echo ---
			// Every pool slot can be a client, so one stalled connection does
			// not block the next. The listener is reopened whenever a slot is
			// free; one aborted (RST during the handshake) is released first.
			if (tcpListen != null
					&& tcpListen.state == TCPConnection.STATE_CLOSED) {
				tcpListen.close();
				tcpListen = null;
			}
			if (tcpListen == null) {
				tcpListen = TCP.listen(7);
			}
			if (tcpListen != null
					&& (tcpListen.state == TCPConnection.STATE_ESTABLISHED
					|| tcpListen.state == TCPConnection.STATE_CLOSE_WAIT)) {
				for (int i = 0; i < tcpClients.length; i++) {
					if (tcpClients[i] == null) {
						tcpClients[i] = tcpListen;
						tcpListen = null;
						JVMHelp.wr("TC\n");
						break;
					}
				}
			}
			for (int i = 0; i < tcpClients.length; i++) {
				if (tcpClients[i] != null && !echo(i)) {
					tcpClients[i] = null;
				}
			}

			// Periodic gratuitous ARP (every ~1M loops)
			loopCount++;
			if ((loopCount & 0xFFFFF) == 0) {
				Arp.sendGratuitous();

				// Accumulate MAC RX stats
				int stats = EthDriver.readRxStats();
				rxErrors += stats & 0xFF;
				rxDrops += (stats >>> 8) & 0xFF;

				// Print diagnostics
				JVMHelp.wr("\nRx=");
				wrInt(ICMP.pingRxCount);
				JVMHelp.wr(" Tx=");
				wrInt(ICMP.pingSentCount);
				JVMHelp.wr(" Fl=");
				wrInt(ICMP.pingFailCount);
				JVMHelp.wr(" Er=");
				wrInt(rxErrors);
				JVMHelp.wr(" Dr=");
				wrInt(rxDrops);
				JVMHelp.wr('\n');
			}
		}
	}
}
