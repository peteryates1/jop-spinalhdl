package java.net;

import com.jopdesign.net.TCP;
import com.jopdesign.net.TCPConnection;
import com.jopdesign.net.TCPInputStream;
import com.jopdesign.net.TCPOutputStream;
import com.jopdesign.net.NetLoop;

/**
 * TCP client socket for JOP.
 *
 * Non-blocking: read() returns -1 when no data is available.
 * For blocking-style, poll in a loop with NetLoop.poll() and watchdog.
 */
public class Socket {

	private TCPConnection conn;

	/** Create from an existing connection (used by ServerSocket.accept()). */
	Socket(TCPConnection conn) {
		this.conn = conn;
	}

	/**
	 * Create a socket and connect to a remote host.
	 * This initiates the TCP handshake (sends SYN).
	 * Connection completes asynchronously — call waitConnected() or
	 * check isConnected() after calling NetLoop.poll().
	 */
	public Socket(InetAddress addr, int port) throws SocketException {
		conn = TCP.connect(addr.getAddress(), port);
		if (conn == null) {
			throw new SocketException();
		}
	}

	/**
	 * Create a socket and connect using string IP.
	 */
	public Socket(String host, int port) throws SocketException {
		InetAddress addr = InetAddress.getByName(host);
		conn = TCP.connect(addr.getAddress(), port);
		if (conn == null) {
			throw new SocketException();
		}
	}

	/**
	 * Get the TCP input stream for reading data.
	 */
	public TCPInputStream getInputStream() {
		if (conn == null) return null;
		return conn.iStream;
	}

	/**
	 * Get the TCP output stream for writing data.
	 */
	public TCPOutputStream getOutputStream() {
		if (conn == null) return null;
		return conn.oStream;
	}

	/**
	 * Check if the connection is established.
	 */
	public boolean isConnected() {
		return conn != null && conn.state == TCPConnection.STATE_ESTABLISHED;
	}

	/**
	 * Check if the connection is closed.
	 */
	public boolean isClosed() {
		return conn == null || conn.state == TCPConnection.STATE_CLOSED;
	}

	/**
	 * Close the socket. Initiates TCP close (FIN).
	 */
	public void close() {
		if (conn != null) {
			conn.close();
			conn = null;
		}
	}

	/**
	 * Get the remote IP address.
	 */
	public InetAddress getInetAddress() {
		if (conn == null) return null;
		return InetAddress.fromInt(conn.remoteIP);
	}

	/**
	 * Get the remote port.
	 */
	public int getPort() {
		if (conn == null) return -1;
		return conn.remotePort;
	}

	/**
	 * Get the local port.
	 */
	public int getLocalPort() {
		if (conn == null) return -1;
		return conn.localPort;
	}

	/** Get underlying connection (for internal use). */
	TCPConnection getConnection() {
		return conn;
	}
}
