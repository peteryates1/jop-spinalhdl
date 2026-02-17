package java.io;

public abstract class OutputStream {
	/**
	 * This is the default no-argument constructor for this class. This method does
	 * nothing in this class.
	 */
	public OutputStream() {
	}

	/**
	 * This method writes a single byte to the output stream. The byte written is
	 * the low eight bits of the <code>int</code> passed and a argument.
	 * <p>
	 * Subclasses must provide an implementation of this abstract method
	 *
	 * @param b The byte to be written to the output stream, passed as the low eight
	 *          bits of an <code>int</code>
	 *
	 * @exception IOException If an error occurs
	 */
	public abstract void write(int b) throws IOException;

	/**
	 * This method all the writes bytes from the passed array to the output stream.
	 * This method is equivalent to <code>write(b, 0,
	 * buf.length)</code> which is exactly how it is implemented in this class.
	 *
	 * @param b The array of bytes to write
	 *
	 * @exception IOException If an error occurs
	 */
	public void write(byte[] b) throws IOException, NullPointerException {
		write(b, 0, b.length);
	}

	/**
	 * This method writes <code>len</code> bytes from the specified array
	 * <code>b</code> starting at index <code>off</code> into the array.
	 * <p>
	 * This method in this class calls the single byte <code>write()</code> method
	 * in a loop until all bytes have been written. Subclasses should override this
	 * method if possible in order to provide a more efficent implementation.
	 *
	 * @param b   The array of bytes to write from
	 * @param off The index into the array to start writing from
	 * @param len The number of bytes to write
	 * 
	 * @exception IOException If an error occurs
	 */
	public void write(byte[] b, int off, int len) throws IOException, NullPointerException, IndexOutOfBoundsException {
		if (off < 0 || len < 0 || off + len > b.length)
			throw new ArrayIndexOutOfBoundsException();
		for (int i = 0; i < len; ++i)
			write(b[off + i]);
	}

	/**
	 * This method forces any data that may have been buffered to be written to the
	 * underlying output device. Please note that the host environment might perform
	 * its own buffering unbeknowst to Java. In that case, a write made (for
	 * example, to a disk drive) might be cached in OS buffers instead of actually
	 * being written to disk.
	 * <p>
	 * This method in this class does nothing.
	 *
	 * @exception IOException If an error occurs
	 */
	public void flush() throws IOException {
	}

	/**
	 * This method closes the stream. Any internal or native resources associated
	 * with this stream are freed. Any subsequent attempt to access the stream might
	 * throw an exception.
	 * <p>
	 * This method in this class does nothing.
	 *
	 * @exception IOException If an error occurs
	 */
	public void close() throws IOException {
	}
}
