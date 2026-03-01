package java.net;

/**
 * UDP datagram packet buffer for JOP.
 */
public class DatagramPacket {

	private byte[] buf;
	private int offset;
	private int length;
	private InetAddress address;
	private int port;

	/**
	 * Create a DatagramPacket for receiving.
	 */
	public DatagramPacket(byte[] buf, int length) {
		this.buf = buf;
		this.offset = 0;
		this.length = length;
		this.address = null;
		this.port = -1;
	}

	/**
	 * Create a DatagramPacket for sending.
	 */
	public DatagramPacket(byte[] buf, int length, InetAddress address, int port) {
		this.buf = buf;
		this.offset = 0;
		this.length = length;
		this.address = address;
		this.port = port;
	}

	/**
	 * Create a DatagramPacket for sending with offset.
	 */
	public DatagramPacket(byte[] buf, int offset, int length, InetAddress address, int port) {
		this.buf = buf;
		this.offset = offset;
		this.length = length;
		this.address = address;
		this.port = port;
	}

	public byte[] getData() { return buf; }
	public int getOffset() { return offset; }
	public int getLength() { return length; }
	public InetAddress getAddress() { return address; }
	public int getPort() { return port; }

	public void setData(byte[] buf) { this.buf = buf; this.offset = 0; this.length = buf.length; }
	public void setLength(int length) { this.length = length; }
	public void setAddress(InetAddress address) { this.address = address; }
	public void setPort(int port) { this.port = port; }
}
