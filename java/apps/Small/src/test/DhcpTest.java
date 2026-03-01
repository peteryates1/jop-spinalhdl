package test;

import com.jopdesign.sys.*;
import com.jopdesign.net.*;

/**
 * DHCP + DNS network test — entry point.
 *
 * Enables DHCP to obtain IP/gateway/DNS from a DHCP server,
 * then runs UDP+TCP echo on port 7 (same as NetTest).
 * Optionally resolves a hostname via DNS.
 *
 * Build:
 *   cd java/apps/Small
 *   make clean && make all APP_NAME=DhcpTest EXTRA_SRC=../../net/src
 *
 * DHCP server setup (on test host):
 *   sudo dnsmasq --no-daemon --no-resolv \
 *     --dhcp-range=192.168.0.200,192.168.0.210,60s \
 *     --interface=enp2s0 --bind-interfaces \
 *     --server=8.8.8.8
 *
 * Test from host:
 *   ping <assigned-ip>
 *   echo "hello" | nc -u -w1 <assigned-ip> 7
 *   echo "hello" | nc -w1 <assigned-ip> 7
 */
public class DhcpTest {

	static int wd = 0;

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
		JVMHelp.wr("DhcpTest v1\n");

		// Enable DHCP — must set before NetLoop.init()
		NetConfig.useDhcp = true;

		// Initialize networking stack (starts DHCP discovery)
		JVMHelp.wr("Init...\n");
		boolean linkUp = NetLoop.init();
		if (!linkUp) {
			JVMHelp.wr("Link DOWN\n");
			for (;;) { toggleWd(); delay(500000); }
		}
		JVMHelp.wr("Link UP ");
		wrInt(EthDriver.getLinkSpeed());
		JVMHelp.wr("M\n");

		// Wait for DHCP to complete (with timeout)
		JVMHelp.wr("DHCP wait...\n");
		int dhcpWait = 0;
		while (NetConfig.dhcpActive || NetConfig.ip == 0) {
			toggleWd();
			NetLoop.poll();
			delay(1000);
			dhcpWait++;
			if (dhcpWait > 5000000) {
				JVMHelp.wr("DHCP timeout!\n");
				break;
			}
		}

		if (NetConfig.ip != 0) {
			JVMHelp.wr("IP=");
			wrIp(NetConfig.ip);
			JVMHelp.wr('\n');
		}

		// Test DNS resolve (if DNS server was provided)
		if (NetConfig.dnsServer != 0) {
			JVMHelp.wr("DNS test...\n");
			char[] hostname = {'e','x','a','m','p','l','e','.','c','o','m'};
			int ip = DNS.resolve(hostname, hostname.length);
			if (ip != 0) {
				JVMHelp.wr("DNS: ");
				wrIp(ip);
				JVMHelp.wr('\n');
			} else {
				JVMHelp.wr("DNS: fail\n");
			}
		}

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
		int pendingByte = -1;
		int rxErrors = 0;
		int rxDrops = 0;

		// Send gratuitous ARP
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
					// Retry pending byte first
					if (pendingByte >= 0) {
						if (tcpClient.oStream.write(pendingByte) == 0) {
							pendingByte = -1;
						}
					}
					// Echo: read from iStream, write to oStream
					if (pendingByte < 0) {
						int b = tcpClient.iStream.read();
						while (b != -1) {
							if (tcpClient.oStream.write(b) != 0) {
								pendingByte = b;
								break;
							}
							b = tcpClient.iStream.read();
						}
					}
					// Only close when all data has been echoed
					if (pendingByte < 0
							&& tcpClient.state == TCPConnection.STATE_CLOSE_WAIT) {
						tcpClient.close();
						JVMHelp.wr("TX\n");
						tcpClient = null;
					}
				} else if (tcpClient.state == TCPConnection.STATE_CLOSED) {
					pendingByte = -1;
					tcpClient = null;
				}
			}

			// Periodic diagnostics
			loopCount++;
			if ((loopCount & 0xFFFFF) == 0) {
				Arp.sendGratuitous();

				int stats = EthDriver.readRxStats();
				rxErrors += stats & 0xFF;
				rxDrops += (stats >>> 8) & 0xFF;

				JVMHelp.wr("\nRx=");
				wrInt(ICMP.pingRxCount);
				JVMHelp.wr(" Tx=");
				wrInt(ICMP.pingSentCount);
				JVMHelp.wr(" Er=");
				wrInt(rxErrors);
				JVMHelp.wr(" Dr=");
				wrInt(rxDrops);
				JVMHelp.wr('\n');
			}
		}
	}
}
