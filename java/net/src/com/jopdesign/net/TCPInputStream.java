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
		writePtr = (writePtr + 1) % size;
		if (writePtr == readPtr) bufferFull = true;
		return 0;
	}

	/** Read one byte from the buffer. Returns byte value (0-255), or -1 if empty/closed. */
	public int read() {
		if (isBufferEmpty()) {
			if (closed) return -1;
			return -1;
		}
		int out = buffer[readPtr] & 0xFF;
		readPtr = (readPtr + 1) % size;
		bufferFull = false;
		return out;
	}

	/** Read up to len bytes into buf. Returns number of bytes read, or -1 if empty/closed. */
	public int read(byte[] buf, int off, int len) {
		if (isBufferEmpty()) {
			if (closed) return -1;
			return -1;
		}
		int count = 0;
		while (count < len && !isBufferEmpty()) {
			buf[off + count] = (byte) (buffer[readPtr] & 0xFF);
			readPtr = (readPtr + 1) % size;
			bufferFull = false;
			count++;
		}
		return count > 0 ? count : -1;
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
