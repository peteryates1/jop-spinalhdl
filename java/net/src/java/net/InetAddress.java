package java.net;

/**
 * Minimal InetAddress implementation for JOP.
 *
 * Stores IP address as a 32-bit int in network byte order (big-endian).
 * Only supports IPv4 dotted-decimal parsing (no DNS).
 */
public class InetAddress {

	private int address;

	private InetAddress(int addr) {
		address = addr;
	}

	/**
	 * Parse a dotted-decimal IP address string.
	 * @param host e.g. "192.168.0.1"
	 * @return the InetAddress
	 * @throws SocketException if the string is not a valid IP
	 */
	public static InetAddress getByName(String host) throws SocketException {
		int addr = parseIp(host);
		if (addr == -1) {
			throw new SocketException();
		}
		return new InetAddress(addr);
	}

	/**
	 * Create an InetAddress from a raw 32-bit IP.
	 */
	public static InetAddress fromInt(int addr) {
		return new InetAddress(addr);
	}

	/** Get the raw 32-bit IP address. */
	public int getAddress() {
		return address;
	}

	/**
	 * Parse dotted-decimal IP string to int.
	 * Returns -1 on parse error.
	 */
	private static int parseIp(String s) {
		int len = s.length();
		int octet = 0;
		int octetCount = 0;
		int result = 0;
		boolean hasDigit = false;

		for (int i = 0; i <= len; i++) {
			char c;
			if (i < len) {
				c = s.charAt(i);
			} else {
				c = '.';  // virtual dot at end
			}

			if (c >= '0' && c <= '9') {
				octet = octet * 10 + (c - '0');
				if (octet > 255) return -1;
				hasDigit = true;
			} else if (c == '.') {
				if (!hasDigit) return -1;
				result = (result << 8) | octet;
				octet = 0;
				octetCount++;
				hasDigit = false;
			} else {
				return -1;
			}
		}
		if (octetCount != 4) return -1;
		return result;
	}
}
