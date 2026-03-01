package test;

import com.jopdesign.sys.*;
import com.jopdesign.net.*;
import com.jopdesign.fat32.*;
import java.io.IOException;

/**
 * HTTP/1.0 file server for JOP.
 *
 * Serves static files from the FAT32 SD card over TCP port 80.
 * Single-connection, poll-based design matching JOP constraints.
 *
 * Build:
 *   cd java/apps/Small
 *   make clean && make all APP_NAME=HttpServer "EXTRA_SRC=../../net/src ../../fat32/src"
 *
 * Test:
 *   curl http://192.168.0.123/
 *   curl http://192.168.0.123/index.htm
 */
public class HttpServer {

	// --- State machine ---
	static final int ST_IDLE = 0;
	static final int ST_READING_REQUEST = 1;
	static final int ST_SENDING_HEADER = 2;
	static final int ST_SENDING_BODY = 3;
	static final int ST_CLOSING = 4;

	// --- Request line buffer ---
	static final int LINE_BUF_SIZE = 256;
	static byte[] lineBuf = new byte[LINE_BUF_SIZE];
	static int lineLen = 0;
	static boolean headersDone = false;
	// Track \r\n\r\n: count consecutive chars in the sequence
	static int crlfState = 0;

	// --- Response header buffer ---
	static final int HDR_BUF_SIZE = 512;
	static byte[] hdrBuf = new byte[HDR_BUF_SIZE];
	static int hdrLen = 0;
	static int hdrPos = 0;

	// --- Path buffer (extracted from GET line) ---
	static final int PATH_BUF_SIZE = 128;
	static byte[] pathBuf = new byte[PATH_BUF_SIZE];
	static int pathLen = 0;

	// --- File serving state ---
	static Fat32FileSystem fs;
	static Fat32InputStream fileIn;
	static int pendingByte = -1;

	// --- Connection state ---
	static int connState = ST_IDLE;
	static TCPConnection tcpListen;
	static TCPConnection tcpClient;

	// --- Stats ---
	static int requestCount = 0;

	// --- Watchdog ---
	static int wd = 0;

	// --- Pre-built error responses ---
	static byte[] RESP_400;
	static byte[] RESP_404;
	static byte[] RESP_500;

	// --- HTTP fixed strings as byte arrays ---
	static byte[] HTTP_200 = bytes("HTTP/1.0 200 OK\r\n");
	static byte[] HTTP_CT = bytes("Content-Type: ");
	static byte[] HTTP_CL = bytes("\r\nContent-Length: ");
	static byte[] HTTP_CONN_CLOSE = bytes("\r\nConnection: close\r\n\r\n");

	// --- Content types ---
	static byte[] CT_HTML = bytes("text/html");
	static byte[] CT_TEXT = bytes("text/plain");
	static byte[] CT_CSS = bytes("text/css");
	static byte[] CT_JS = bytes("application/javascript");
	static byte[] CT_PNG = bytes("image/png");
	static byte[] CT_JPG = bytes("image/jpeg");
	static byte[] CT_GIF = bytes("image/gif");
	static byte[] CT_ICO = bytes("image/x-icon");
	static byte[] CT_BIN = bytes("application/octet-stream");

	// --- Default index file ---
	static byte[] DEFAULT_INDEX = bytes("index.htm");

	// --- Default HTML content (literal string, no StringBuilder) ---
	static String DEFAULT_HTML = "<html>\n<head><title>JOP HTTP Server</title></head>\n<body>\n<h1>Hello from JOP!</h1>\n<p>HTTP server running on Java Optimized Processor.</p>\n</body>\n</html>\n";

	// --- Hex / debug ---
	static char[] hexChars = {'0','1','2','3','4','5','6','7',
	                          '8','9','A','B','C','D','E','F'};

	/** Convert a string literal to byte[] at startup. */
	static byte[] bytes(String s) {
		byte[] b = new byte[s.length()];
		for (int i = 0; i < s.length(); i++) {
			b[i] = (byte) s.charAt(i);
		}
		return b;
	}

	/** Build a complete HTTP error response as a byte[]. */
	static byte[] buildErrorResponse(int code, String reason, String body) {
		// "HTTP/1.0 CODE REASON\r\nContent-Type: text/html\r\nConnection: close\r\n\r\nBODY"
		// Manually build into a temporary oversized buffer, then copy
		byte[] tmp = new byte[256];
		int pos = 0;
		pos = appendStr(tmp, pos, "HTTP/1.0 ");
		pos = appendInt(tmp, pos, code);
		pos = appendByte(tmp, pos, (byte) ' ');
		pos = appendStr(tmp, pos, reason);
		pos = appendStr(tmp, pos, "\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n<h1>");
		pos = appendInt(tmp, pos, code);
		pos = appendByte(tmp, pos, (byte) ' ');
		pos = appendStr(tmp, pos, reason);
		pos = appendStr(tmp, pos, "</h1><p>");
		pos = appendStr(tmp, pos, body);
		pos = appendStr(tmp, pos, "</p>\r\n");
		byte[] result = new byte[pos];
		for (int i = 0; i < pos; i++) result[i] = tmp[i];
		return result;
	}

	static int appendStr(byte[] buf, int pos, String s) {
		for (int i = 0; i < s.length() && pos < buf.length; i++) {
			buf[pos++] = (byte) s.charAt(i);
		}
		return pos;
	}

	static int appendBytes(byte[] buf, int pos, byte[] src) {
		for (int i = 0; i < src.length && pos < buf.length; i++) {
			buf[pos++] = src[i];
		}
		return pos;
	}

	static int appendByte(byte[] buf, int pos, byte b) {
		if (pos < buf.length) buf[pos++] = b;
		return pos;
	}

	static int appendInt(byte[] buf, int pos, int val) {
		if (val < 0) {
			pos = appendByte(buf, pos, (byte) '-');
			val = -val;
		}
		if (val == 0) {
			return appendByte(buf, pos, (byte) '0');
		}
		// Find the leading digit's power of 10
		int d = 1;
		int tmp = val;
		while (tmp >= 10) { d *= 10; tmp /= 10; }
		while (d >= 1) {
			int digit = (val / d) % 10;
			pos = appendByte(buf, pos, (byte) ('0' + digit));
			d /= 10;
		}
		return pos;
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
		for (int i = 0; i < n; i++) { }
	}

	static void halt(String msg) {
		JVMHelp.wr(msg);
		JVMHelp.wr('\n');
		for (;;) { toggleWd(); delay(500000); }
	}

	/** Check for index.htm on SD card; create with default content if missing. */
	static void ensureIndexFile() {
		DirEntry entry = fs.findFile(fs.getRootCluster(), "INDEX.HTM");
		if (entry != null) {
			JVMHelp.wr("index.htm exists\n");
			return;
		}
		JVMHelp.wr("Creating index.htm...\n");
		try {
			entry = fs.createFile(fs.getRootCluster(), "INDEX.HTM");
			if (entry == null) {
				JVMHelp.wr("createFile failed\n");
				return;
			}
			Fat32OutputStream out = fs.openFileForWrite(entry);
			for (int i = 0; i < DEFAULT_HTML.length(); i++) {
				out.write(DEFAULT_HTML.charAt(i));
			}
			out.close();
			JVMHelp.wr("index.htm created\n");
		} catch (IOException e) {
			JVMHelp.wr("index.htm write err\n");
		}
	}

	// ==================== Main ====================

	public static void main(String[] args) {
		JVMHelp.wr("HTTP Server v1\n");

		// --- Init SD card ---
		JVMHelp.wr("SD init...\n");
		SdNativeBlockDevice sd = new SdNativeBlockDevice();
		if (!sd.init()) {
			halt("SD init FAIL");
		}
		JVMHelp.wr("SD OK\n");
		toggleWd();

		// --- Mount FAT32 ---
		JVMHelp.wr("FAT32 mount...\n");
		fs = new Fat32FileSystem(sd);
		if (!fs.mount(0)) {
			halt("Mount FAIL");
		}
		JVMHelp.wr("Mounted\n");
		toggleWd();

		// --- Ensure index.htm exists on SD card ---
		ensureIndexFile();
		toggleWd();

		// --- Build error responses ---
		RESP_400 = buildErrorResponse(400, "Bad Request", "Malformed HTTP request.");
		RESP_404 = buildErrorResponse(404, "Not Found", "The requested file was not found.");
		RESP_500 = buildErrorResponse(500, "Internal Server Error", "Error reading file.");

		// --- Init networking ---
		JVMHelp.wr("Net init...\n");
		boolean linkUp = NetLoop.init();
		if (!linkUp) {
			halt("Link DOWN");
		}
		JVMHelp.wr("Link UP ");
		wrInt(EthDriver.getLinkSpeed());
		JVMHelp.wr("M\nIP=");
		wrIp(NetConfig.ip);
		JVMHelp.wr('\n');
		toggleWd();

		// Gratuitous ARP
		Arp.sendGratuitous();
		delay(500000);
		Arp.sendGratuitous();

		// --- Listen on port 80 ---
		tcpListen = TCP.listen(80);
		if (tcpListen == null) {
			halt("TCP listen FAIL");
		}
		JVMHelp.wr("Listening :80\n");

		connState = ST_IDLE;
		tcpClient = null;
		fileIn = null;
		int loopCount = 0;

		// ==================== Main loop ====================
		for (;;) {
			toggleWd();
			NetLoop.poll();

			// --- Accept new connections ---
			if (tcpClient == null && tcpListen != null) {
				if (tcpListen.state == TCPConnection.STATE_ESTABLISHED
						|| tcpListen.state == TCPConnection.STATE_CLOSE_WAIT) {
					tcpClient = tcpListen;
					connState = ST_READING_REQUEST;
					lineLen = 0;
					headersDone = false;
					crlfState = 0;
					pendingByte = -1;
					hdrPos = 0;
					hdrLen = 0;
					pathLen = 0;
					fileIn = null;
					JVMHelp.wr("C\n");
					// Open new listener for next connection
					tcpListen = TCP.listen(80);
				}
			}

			// Re-create listener if it was consumed and no client active
			if (tcpListen == null && tcpClient == null) {
				tcpListen = TCP.listen(80);
			}

			// --- Drive connection state machine ---
			if (tcpClient != null) {
				// Check for unexpected close
				if (tcpClient.state == TCPConnection.STATE_CLOSED) {
					cleanupConnection();
					continue;
				}

				switch (connState) {
					case ST_READING_REQUEST:
						doReadRequest();
						break;
					case ST_SENDING_HEADER:
						doSendHeader();
						break;
					case ST_SENDING_BODY:
						doSendBody();
						break;
					case ST_CLOSING:
						doClose();
						break;
				}
			}

			// Periodic gratuitous ARP
			loopCount++;
			if ((loopCount & 0xFFFFF) == 0) {
				Arp.sendGratuitous();
			}
		}
	}

	// ==================== State handlers ====================

	/** ST_READING_REQUEST: read bytes until we find the blank line. */
	static void doReadRequest() {
		if (tcpClient.state != TCPConnection.STATE_ESTABLISHED
				&& tcpClient.state != TCPConnection.STATE_CLOSE_WAIT) {
			return;
		}

		int b = tcpClient.iStream.read();
		while (b != -1) {
			// Track \r\n\r\n sequence
			if (crlfState == 0 && b == '\r') crlfState = 1;
			else if (crlfState == 1 && b == '\n') crlfState = 2;
			else if (crlfState == 2 && b == '\r') crlfState = 3;
			else if (crlfState == 3 && b == '\n') {
				headersDone = true;
				break;
			}
			else crlfState = (b == '\r') ? 1 : 0;

			// Accumulate only the first line (up to first \n)
			if (!headersDone && lineLen < LINE_BUF_SIZE && b != '\r' && b != '\n') {
				if (lineLen == 0 || lineBuf[lineLen - 1] != '\n') {
					lineBuf[lineLen++] = (byte) b;
				}
			}
			// After first \n in lineBuf, stop accumulating
			// (we only need "GET /path HTTP/1.x")

			b = tcpClient.iStream.read();
		}

		if (!headersDone) {
			// Check if remote closed before we got full headers
			if (tcpClient.state == TCPConnection.STATE_CLOSE_WAIT) {
				// Treat whatever we have as the request
				headersDone = true;
			} else {
				return; // Wait for more data
			}
		}

		// --- Parse request line ---
		if (!parseRequestLine()) {
			sendError(RESP_400);
			return;
		}

		// --- Resolve path to file ---
		requestCount++;
		JVMHelp.wr('#');
		wrInt(requestCount);
		JVMHelp.wr(' ');
		for (int i = 0; i < pathLen; i++) {
			JVMHelp.wr((char) (pathBuf[i] & 0xFF));
		}
		JVMHelp.wr('\n');

		serveFile();
	}

	/**
	 * Parse "GET /path HTTP/1.x" from lineBuf.
	 * Extracts path into pathBuf/pathLen.
	 * Returns false if malformed.
	 */
	static boolean parseRequestLine() {
		// Must start with "GET "
		if (lineLen < 5) return false;
		if (lineBuf[0] != 'G' || lineBuf[1] != 'E' || lineBuf[2] != 'T'
				|| lineBuf[3] != ' ') {
			return false;
		}

		// Find start of path (after "GET ")
		int pathStart = 4;
		if (pathStart >= lineLen || lineBuf[pathStart] != '/') {
			return false;
		}

		// Find end of path (next space or end)
		int pathEnd = pathStart;
		while (pathEnd < lineLen && lineBuf[pathEnd] != ' ') {
			pathEnd++;
		}

		// Strip leading '/'
		int srcStart = pathStart + 1;
		int srcLen = pathEnd - srcStart;

		// Default to index.htm for root
		if (srcLen == 0) {
			for (int i = 0; i < DEFAULT_INDEX.length; i++) {
				pathBuf[i] = DEFAULT_INDEX[i];
			}
			pathLen = DEFAULT_INDEX.length;
		} else {
			if (srcLen > PATH_BUF_SIZE) srcLen = PATH_BUF_SIZE;
			for (int i = 0; i < srcLen; i++) {
				pathBuf[i] = lineBuf[srcStart + i];
			}
			pathLen = srcLen;
		}
		return true;
	}

	/**
	 * Resolve pathBuf to a FAT32 file and begin serving.
	 * Handles subdirectories by splitting on '/'.
	 */
	static void serveFile() {
		int cluster = fs.getRootCluster();

		// Walk path components
		int start = 0;
		while (start < pathLen) {
			// Find next '/'
			int end = start;
			while (end < pathLen && pathBuf[end] != '/') end++;

			// Extract component name
			int compLen = end - start;
			if (compLen == 0) {
				start = end + 1;
				continue;
			}
			char[] name = new char[compLen];
			for (int i = 0; i < compLen; i++) {
				name[i] = (char) (pathBuf[start + i] & 0xFF);
			}
			String nameStr = new String(name);

			DirEntry entry = fs.findFile(cluster, nameStr);
			if (entry == null) {
				sendError(RESP_404);
				return;
			}

			if (end < pathLen) {
				// More path components follow — this must be a directory
				if (!entry.isDirectory()) {
					sendError(RESP_404);
					return;
				}
				cluster = entry.getStartCluster();
				start = end + 1;
			} else {
				// Last component — must be a regular file
				if (entry.isDirectory()) {
					sendError(RESP_404);
					return;
				}

				// Open file and build response header
				try {
					fileIn = fs.openFile(entry);
				} catch (IOException e) {
					sendError(RESP_500);
					return;
				}

				buildResponseHeader(entry.fileSize, nameStr);
				connState = ST_SENDING_HEADER;
				return;
			}
		}

		// Path ended with '/' or was empty after stripping — should not reach here
		// since we default to index.htm, but handle gracefully
		sendError(RESP_404);
	}

	/** Build a 200 OK response header into hdrBuf. */
	static void buildResponseHeader(int fileSize, String fileName) {
		int pos = 0;
		pos = appendBytes(hdrBuf, pos, HTTP_200);
		pos = appendBytes(hdrBuf, pos, HTTP_CT);
		pos = appendBytes(hdrBuf, pos, getContentType(fileName));
		pos = appendBytes(hdrBuf, pos, HTTP_CL);
		pos = appendInt(hdrBuf, pos, fileSize);
		pos = appendBytes(hdrBuf, pos, HTTP_CONN_CLOSE);
		hdrLen = pos;
		hdrPos = 0;
	}

	/** Get content type based on file extension. */
	static byte[] getContentType(String name) {
		// Convert to lowercase for comparison
		String lower = name.toLowerCase();
		if (lower.endsWith(".htm") || lower.endsWith(".html")) return CT_HTML;
		if (lower.endsWith(".txt")) return CT_TEXT;
		if (lower.endsWith(".css")) return CT_CSS;
		if (lower.endsWith(".js")) return CT_JS;
		if (lower.endsWith(".png")) return CT_PNG;
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return CT_JPG;
		if (lower.endsWith(".gif")) return CT_GIF;
		if (lower.endsWith(".ico")) return CT_ICO;
		return CT_BIN;
	}

	/** Send a pre-built error response. */
	static void sendError(byte[] errorResp) {
		// Copy error response into header buffer
		hdrLen = errorResp.length;
		if (hdrLen > HDR_BUF_SIZE) hdrLen = HDR_BUF_SIZE;
		for (int i = 0; i < hdrLen; i++) {
			hdrBuf[i] = errorResp[i];
		}
		hdrPos = 0;
		fileIn = null;
		connState = ST_SENDING_HEADER;
	}

	/** ST_SENDING_HEADER: write header bytes to TCP. */
	static void doSendHeader() {
		if (tcpClient.state != TCPConnection.STATE_ESTABLISHED
				&& tcpClient.state != TCPConnection.STATE_CLOSE_WAIT) {
			cleanupConnection();
			return;
		}

		while (hdrPos < hdrLen) {
			int rc = tcpClient.oStream.write(hdrBuf[hdrPos] & 0xFF);
			if (rc != 0) {
				return; // Backpressure — retry next poll
			}
			hdrPos++;
		}

		// Header fully sent
		if (fileIn != null) {
			connState = ST_SENDING_BODY;
			pendingByte = -1;
		} else {
			// Error response (no body file) — go to closing
			connState = ST_CLOSING;
		}
	}

	/** ST_SENDING_BODY: stream file data to TCP. */
	static void doSendBody() {
		if (tcpClient.state != TCPConnection.STATE_ESTABLISHED
				&& tcpClient.state != TCPConnection.STATE_CLOSE_WAIT) {
			cleanupConnection();
			return;
		}

		// Retry pending byte first
		if (pendingByte >= 0) {
			int rc = tcpClient.oStream.write(pendingByte);
			if (rc != 0) return; // Still backpressured
			pendingByte = -1;
		}

		// Read from file and write to TCP
		try {
			for (int i = 0; i < 256; i++) {
				int b = fileIn.read();
				if (b == -1) {
					// File complete
					fileIn.close();
					fileIn = null;
					connState = ST_CLOSING;
					return;
				}
				int rc = tcpClient.oStream.write(b);
				if (rc != 0) {
					pendingByte = b;
					return; // Backpressure
				}
			}
		} catch (IOException e) {
			JVMHelp.wr("IO err\n");
			if (fileIn != null) {
				fileIn.close();
				fileIn = null;
			}
			connState = ST_CLOSING;
		}
	}

	/** ST_CLOSING: close the TCP connection. */
	static void doClose() {
		if (fileIn != null) {
			fileIn.close();
			fileIn = null;
		}
		if (tcpClient != null) {
			tcpClient.close();
			JVMHelp.wr("D\n");
			tcpClient = null;
		}
		connState = ST_IDLE;
	}

	/** Clean up on unexpected connection close. */
	static void cleanupConnection() {
		if (fileIn != null) {
			fileIn.close();
			fileIn = null;
		}
		tcpClient = null;
		connState = ST_IDLE;
		pendingByte = -1;
	}
}
