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
 * Test from host:
 *   ping 192.168.0.123
 *   echo "hello" | nc -u -w1 192.168.0.123 7
 *   echo "hello" | nc -w1 192.168.0.123 7
 */
public class NetTest {

	static int wd = 0;

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

	public static void main(String[] args) {
		JVMHelp.wr("NetTest v2\n");

		// Configure IP (default: 192.168.0.123)
		JVMHelp.wr("IP=");
		wrIp(NetConfig.ip);
		JVMHelp.wr('\n');

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
		TCPConnection tcpClient = null;
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
			if (tcpListen == null && tcpClient == null) {
				tcpListen = TCP.listen(7);
			}
			if (tcpListen != null && tcpClient == null) {
				if (tcpListen.state == TCPConnection.STATE_ESTABLISHED
						|| tcpListen.state == TCPConnection.STATE_CLOSE_WAIT) {
					tcpClient = tcpListen;
					JVMHelp.wr("TC\n");
					tcpListen = TCP.listen(7);
				}
			}

			if (tcpClient != null) {
				if (tcpClient.state == TCPConnection.STATE_ESTABLISHED
						|| tcpClient.state == TCPConnection.STATE_CLOSE_WAIT) {
					int b = tcpClient.iStream.read();
					while (b != -1) {
						tcpClient.oStream.write(b);
						b = tcpClient.iStream.read();
					}
					if (tcpClient.state == TCPConnection.STATE_CLOSE_WAIT) {
						tcpClient.close();
						JVMHelp.wr("TX\n");
						tcpClient = null;
					}
				} else if (tcpClient.state == TCPConnection.STATE_CLOSED) {
					tcpClient = null;
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
