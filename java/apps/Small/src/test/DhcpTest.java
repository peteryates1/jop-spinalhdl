package test;

import com.jopdesign.sys.*;
import com.jopdesign.net.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/**
 * DHCP + DNS network test — entry point.
 *
 * Enables DHCP to obtain IP/gateway/DNS from a DHCP server,
 * then runs UDP+TCP echo on port 7 (same as NetTest).
 * Optionally resolves a hostname via DNS.
 *
 * The TCP echo is written on java.net.ServerSocket / Socket, the only
 * application that exercises them on hardware (NetTest uses the stack's
 * TCPConnection directly). Like NetTest it serves a client in every pool slot,
 * and drops a client silent for TCP_IDLE_MS (status item 171).
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
 *   fpga/scripts/net_echo_check.py <assigned-ip>   (RST, idle, dead clients)
 */
public class DhcpTest {

	static int wd = 0;

	/** A client that sends nothing for this long is dropped: a peer that
	 * vanished without FIN or RST would otherwise hold its slot for good. */
	static final int TCP_IDLE_MS = 30000;

	/** TCP echo clients, one per pool slot, and the byte each could not yet
	 * echo (-1 = none). */
	static Socket[] clients = new Socket[NetConfig.MAX_TCP_CONN];
	static int[] pendingBytes = new int[NetConfig.MAX_TCP_CONN];

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
	 * socket is then closed, which hands the slot back to the pool.
	 */
	static boolean echo(int i) {
		Socket c = clients[i];
		if (c.isClosed()) {
			c.close();
			JVMHelp.wr("TA\n");
			return false;
		}
		if (c.idleTime() > TCP_IDLE_MS) {
			c.abort();
			JVMHelp.wr("TI\n");
			return false;
		}
		TCPInputStream in = c.getInputStream();
		TCPOutputStream out = c.getOutputStream();
		// Retry the pending byte first, then copy input to output
		int pending = pendingBytes[i];
		if (pending >= 0 && out.write(pending) == 0) {
			pending = -1;
		}
		if (pending < 0) {
			int b = in.read();
			while (b != -1) {
				if (out.write(b) != 0) {
					pending = b;
					break;
				}
				b = in.read();
			}
		}
		pendingBytes[i] = pending;
		// Only close when all data has been echoed
		if (pending < 0 && c.isPeerClosed()) {
			c.close();
			JVMHelp.wr("TX\n");
			return false;
		}
		return true;
	}

	public static void main(String[] args) {
		JVMHelp.wr("DhcpTest v2\n");

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
		ServerSocket echoServer = null;
		try {
			echoServer = new ServerSocket(7);
			JVMHelp.wr("TCP:7\n");
		} catch (SocketException e) {
			JVMHelp.wr("TCP fail\n");
		}

		byte[] udpBuf = new byte[1472];
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
			if (echoServer != null) {
				Socket s = echoServer.accept();
				if (s != null) {
					// Every accepted socket holds a pool slot, so a free entry
					// always exists; abort is only a guard.
					int i = 0;
					while (i < clients.length && clients[i] != null) i++;
					if (i < clients.length) {
						clients[i] = s;
						pendingBytes[i] = -1;
						JVMHelp.wr("TC\n");
					} else {
						s.abort();
					}
				}
			}
			for (int i = 0; i < clients.length; i++) {
				if (clients[i] != null && !echo(i)) {
					clients[i] = null;
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
