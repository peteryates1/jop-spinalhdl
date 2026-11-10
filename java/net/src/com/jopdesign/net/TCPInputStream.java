package com.jopdesign.net;

/**
 * Circular byte buffer for TCP receive data.
 *
 * Ported from jtcpip TCPInputStream, simplified for JOP (no synchronized,
 * no wait/notify).
 */
public class TCPInputStream {

	private final int size;
	private final byte[] buffer;
	private int writePtr;
	private int readPtr;
	private boolean bufferFull;
	private boolean closed;

	public TCPInputStream(int size) {
		this.size = size;
		this.buffer = new byte[size];
		this.writePtr = 0;
		this.readPtr = 0;
		this.bufferFull = false;
		this.closed = false;
	}

	/** Write one byte into the buffer. Returns 0 on success, -1 if full, -2 if closed. */
	public int write(int b) {
		if (closed) return -2;
		if (bufferFull) return -1;
		buffer[writePtr] = (byte) (b & 0xFF);
		if (++writePtr == size) writePtr = 0;
		if (writePtr == readPtr) bufferFull = true;
		return 0;
	}

	/**
	 * Append bytes [off, off + len) of a received packet, as many as fit, in
	 * one call (see Packet's bulk paths). Returns how many it took: 0 when
	 * the buffer is full or closed.
	 */
	public int write(Packet pkt, int off, int len) {
		if (closed) return 0;
		int n = getFreeBufferSpace();
		if (n > len) n = len;
		if (n <= 0) return 0;
		int[] src = pkt.buf;
		byte[] dst = buffer;
		int wp = writePtr;
		int sz = size;
		int end = off + n;
		for (int i = off; i < end; i++) {
			dst[wp] = (byte) (src[i >> 2] >>> ((i & 3) << 3));
			if (++wp == sz) wp = 0;
		}
		writePtr = wp;
		if (wp == readPtr) bufferFull = true;
		return n;
	}

	/** Read one byte from the buffer. Returns byte value (0-255), or -1 if empty/closed. */
	public int read() {
		if (isBufferEmpty()) {
			return -1;
		}
		int out = buffer[readPtr] & 0xFF;
		if (++readPtr == size) readPtr = 0;
		bufferFull = false;
		return out;
	}

	/** Read up to len bytes into buf. Returns number of bytes read, or -1 if empty/closed. */
	public int read(byte[] buf, int off, int len) {
		int n = available();
		if (n > len) n = len;
		if (n <= 0) return -1;
		byte[] src = buffer;
		int rp = readPtr;
		int sz = size;
		int end = off + n;
		for (int i = off; i < end; i++) {
			buf[i] = src[rp];
			if (++rp == sz) rp = 0;
		}
		readPtr = rp;
		bufferFull = false;
		return n;
	}

	/** Return number of bytes available for reading. */
	public int available() {
		if (bufferFull) return size;
		if (writePtr >= readPtr) return writePtr - readPtr;
		return size - readPtr + writePtr;
	}

	/** Return number of free bytes in the buffer. */
	public int getFreeBufferSpace() {
		if (bufferFull) return 0;
		if (writePtr == readPtr) return size;
		if (writePtr > readPtr) return size - (writePtr - readPtr);
		return readPtr - writePtr;
	}

	public boolean isBufferEmpty() {
		return (writePtr == readPtr) && !bufferFull;
	}

	public boolean isBufferFull() {
		return bufferFull;
	}

	public void close() {
		closed = true;
	}

	public boolean isClosed() {
		return closed;
	}

	/** Reopen the stream, resetting all pointers. */
	public void reOpen() {
		if (!closed) return;
		closed = false;
		writePtr = 0;
		readPtr = 0;
		bufferFull = false;
	}
}
