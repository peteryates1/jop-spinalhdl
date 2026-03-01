package com.jopdesign.net;

/**
 * Circular byte buffer for TCP send data with retransmission support.
 *
 * Has three pointers:
 *   ackWaitPtr → readPtr → writePtr
 *
 * - User writes at writePtr
 * - TCP reads from readPtr to fill outgoing segments
 * - ackWaitPtr marks the oldest unacknowledged data
 * - On retransmit, readPtr is reset to ackWaitPtr
 * - On ACK, ackWaitPtr advances to free buffer space
 *
 * Ported from jtcpip TCPOutputStream, simplified for JOP.
 */
public class TCPOutputStream {

	private final int size;
	private final byte[] buffer;
	private int writePtr;
	private int readPtr;
	private int ackWaitPtr;
	private boolean bufferBlocked;  // writePtr caught up to readPtr
	private boolean bufferFull;     // writePtr caught up to ackWaitPtr
	private boolean closed;

	public TCPOutputStream(int size) {
		this.size = size;
		this.buffer = new byte[size];
		this.writePtr = 0;
		this.readPtr = 0;
		this.ackWaitPtr = 0;
		this.bufferBlocked = false;
		this.bufferFull = false;
		this.closed = false;
	}

	/** Write one byte. Returns 0 on success, -1 if full, -2 if closed. */
	public int write(int b) {
		if (closed) return -2;
		if (bufferFull || bufferBlocked) return -1;
		buffer[writePtr] = (byte) (b & 0xFF);
		writePtr = (writePtr + 1) % size;
		if (writePtr == readPtr) bufferBlocked = true;
		if (writePtr == ackWaitPtr) bufferFull = true;
		return 0;
	}

	/** Write multiple bytes. Returns number of bytes written, or -1 if closed. */
	public int write(byte[] data, int off, int len) {
		if (closed) return -2;
		int count = 0;
		for (int i = 0; i < len; i++) {
			if (bufferFull || bufferBlocked) break;
			buffer[writePtr] = data[off + i];
			writePtr = (writePtr + 1) % size;
			if (writePtr == readPtr) bufferBlocked = true;
			if (writePtr == ackWaitPtr) bufferFull = true;
			count++;
		}
		return count;
	}

	/** Read one byte for TCP to send. Returns byte value, or -1 if no more data. */
	public int read() {
		if (isNoMoreDataToRead()) return -1;
		int out = buffer[readPtr] & 0xFF;
		readPtr = (readPtr + 1) % size;
		bufferBlocked = false;
		return out;
	}

	/** Acknowledge num_bytes of data, freeing buffer space. */
	public void ackData(int numBytes) {
		if (numBytes == 0) return;
		ackWaitPtr = (ackWaitPtr + numBytes) % size;
		bufferFull = false;
		// When bufferBlocked is true, writePtr==readPtr. If ackWaitPtr also
		// reached writePtr, the buffer is completely empty — clear the block
		// so user writes can resume and isNoMoreDataToRead() returns true.
		if (bufferBlocked && ackWaitPtr == writePtr) {
			bufferBlocked = false;
		}
	}

	/** True if there's no new data to read (readPtr == writePtr). */
	public boolean isNoMoreDataToRead() {
		return (writePtr == readPtr) && !bufferBlocked;
	}

	/** True if the entire buffer is empty (no unacknowledged data either). */
	public boolean isBufferEmpty() {
		return (writePtr == ackWaitPtr) && !bufferFull;
	}

	public boolean isBufferFull() {
		return bufferFull;
	}

	/** Return number of free bytes available for user writes. */
	public int getFreeBufferSpace() {
		if (bufferFull) return 0;
		if (writePtr == ackWaitPtr) return size;
		if (writePtr > ackWaitPtr) return size - (writePtr - ackWaitPtr);
		return ackWaitPtr - writePtr;
	}

	/** Set read pointer back to ackWaitPtr for retransmission. */
	public void setPtrForRetransmit() {
		readPtr = ackWaitPtr;
		if (bufferFull) bufferBlocked = true;
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
		ackWaitPtr = 0;
		bufferFull = false;
		bufferBlocked = false;
	}
}
