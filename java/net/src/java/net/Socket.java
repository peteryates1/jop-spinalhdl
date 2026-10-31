package java.net;

import com.jopdesign.net.TCP;
import com.jopdesign.net.TCPConnection;
import com.jopdesign.net.TCPInputStream;
import com.jopdesign.net.TCPOutputStream;
import com.jopdesign.net.NetLoop;
import com.jopdesign.net.NumFunctions;

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
	 * Check if the connection is closed -- by close(), or by the stack
	 * aborting it (an RST, or retransmits exhausted). Call close() even then:
	 * an aborted connection keeps its pool slot until close() releases it.
	 */
	public boolean isClosed() {
		return conn == null || conn.state == TCPConnection.STATE_CLOSED;
	}

	/**
	 * Check if the peer has closed its side (FIN received, CLOSE_WAIT): no
	 * more data will arrive, but what was received can still be read and
	 * replies written before close().
	 */
	public boolean isPeerClosed() {
		return conn != null && conn.state == TCPConnection.STATE_CLOSE_WAIT;
	}

	/**
	 * Milliseconds since the peer last sent a segment. The stack never times
	 * out an idle connection with nothing unacknowledged, so a server uses
	 * this to drop a peer that vanished without FIN or RST.
	 */
	public int idleTime() {
		if (conn == null) return 0;
		return NumFunctions.now() - conn.timeLastRemoteActivity;
	}

	/**
	 * Drop the connection at once and release its pool slot, without the
	 * FIN exchange -- for a peer that is gone.
	 */
	public void abort() {
		if (conn != null) {
			conn.abort();
			conn.close();
			conn = null;
		}
	}

	/**
	 * Close the socket. Initiates TCP close (FIN); on a connection that is
	 * already closed it releases the pool slot.
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
