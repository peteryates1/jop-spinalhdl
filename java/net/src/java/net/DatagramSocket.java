package java.net;

import com.jopdesign.net.UDPConnection;

/**
 * UDP socket for JOP networking.
 *
 * Non-blocking: receive() returns immediately with -1 length if no data.
 * For blocking-style use, poll in a loop with watchdog toggling.
 */
public class DatagramSocket {

	private UDPConnection conn;
	private int localPort;

	/**
	 * Create and bind a DatagramSocket on a specific port.
	 */
	public DatagramSocket(int port) throws SocketException {
		conn = UDPConnection.open(port);
		if (conn == null) {
			throw new SocketException();
		}
		localPort = port;
	}

	/**
	 * Create a DatagramSocket on an ephemeral port.
	 */
	public DatagramSocket() throws SocketException {
		int port = UDPConnection.newLocalPort();
		conn = UDPConnection.open(port);
		if (conn == null) {
			throw new SocketException();
		}
		localPort = port;
	}

	/**
	 * Send a datagram packet.
	 */
	public void send(DatagramPacket p) throws SocketException {
		if (conn == null) throw new SocketException();
		InetAddress addr = p.getAddress();
		if (addr == null) throw new SocketException();
		conn.send(addr.getAddress(), p.getPort(),
			p.getData(), p.getOffset(), p.getLength());
	}

	/**
	 * Receive a datagram packet (non-blocking).
	 * Sets the packet's length to the received data length.
	 * If no data is available, sets length to 0 and returns immediately.
	 */
	public void receive(DatagramPacket p) {
		if (conn == null) {
			p.setLength(0);
			return;
		}
		int n = conn.receive(p.getData(), p.getOffset(), p.getLength());
		if (n < 0) {
			p.setLength(0);
		} else {
			p.setLength(n);
			p.setAddress(InetAddress.fromInt(conn.getLastSrcIp()));
			p.setPort(conn.getLastSrcPort());
		}
	}

	/** Close the socket. */
	public void close() {
		if (conn != null) {
			conn.close();
			conn = null;
		}
	}

	/** Get local port. */
	public int getLocalPort() {
		return localPort;
	}

	/** Check if data is available for reading. */
	public boolean hasData() {
		return conn != null && conn.hasData();
	}
}
