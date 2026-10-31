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
		// A handshake the stack aborted (RST during SYN_RCVD) leaves the
		// listener CLOSED but holding its pool slot: release it. Then listen
		// again; that fails while the pool is full, so retry on every call
		// rather than giving up on the port (status item 171).
		if (listenConn != null && listenConn.state == TCPConnection.STATE_CLOSED) {
			listenConn.close();
			listenConn = null;
		}
		if (listenConn == null) {
			listenConn = TCP.listen(localPort);
			if (listenConn == null) return null;
		}

		// Check if the listen connection has progressed past LISTEN
		if (listenConn.state == TCPConnection.STATE_ESTABLISHED
				|| listenConn.state == TCPConnection.STATE_CLOSE_WAIT) {
			Socket s = new Socket(listenConn);
			// Open a new listen (null while the pool is full; retried above)
			listenConn = TCP.listen(localPort);
			return s;
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
