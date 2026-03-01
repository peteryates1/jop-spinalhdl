package java.net;

import com.jopdesign.net.TCP;
import com.jopdesign.net.TCPConnection;
import com.jopdesign.net.NetLoop;

/**
 * TCP server socket for JOP.
 *
 * Usage:
 *   ServerSocket ss = new ServerSocket(port);
 *   while (true) {
 *       NetLoop.poll();
 *       Socket s = ss.accept();
 *       if (s != null) {
 *           // handle connection
 *       }
 *   }
 *
 * accept() is non-blocking: returns null if no connection is ready.
 * After accept() returns a Socket, a new listen is automatically opened
 * on the same port.
 */
public class ServerSocket {

	private TCPConnection listenConn;
	private int localPort;

	/**
	 * Create a server socket listening on the given port.
	 */
	public ServerSocket(int port) throws SocketException {
		localPort = port;
		listenConn = TCP.listen(port);
		if (listenConn == null) {
			throw new SocketException();
		}
	}

	/**
	 * Non-blocking accept. Returns a Socket if a connection has been
	 * established, or null if still waiting.
	 *
	 * After a successful accept, a new listen is opened automatically.
	 */
	public Socket accept() {
		if (listenConn == null) return null;

		// Check if the listen connection has progressed past LISTEN
		if (listenConn.state == TCPConnection.STATE_ESTABLISHED
				|| listenConn.state == TCPConnection.STATE_CLOSE_WAIT) {
			Socket s = new Socket(listenConn);
			// Open a new listen
			listenConn = TCP.listen(localPort);
			return s;
		}

		// Also check SYN_RCVD — handshake in progress
		if (listenConn.state != TCPConnection.STATE_LISTEN
				&& listenConn.state != TCPConnection.STATE_SYN_RCVD) {
			// Connection went to unexpected state — re-listen
			listenConn = TCP.listen(localPort);
		}

		return null;
	}

	/**
	 * Close the server socket.
	 */
	public void close() {
		if (listenConn != null) {
			TCPConnection.deleteConnection(listenConn);
			listenConn = null;
		}
	}

	/** Get local port. */
	public int getLocalPort() {
		return localPort;
	}
}
