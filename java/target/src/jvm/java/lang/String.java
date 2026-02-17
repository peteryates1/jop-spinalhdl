package java.lang;

public class String implements CharSequence {
	
	public static String valueOf(int i) {
		return Integer.toString(i, 10);
	}
	
	final char[] value;
	
	public String() {
		value = "".value;
	}
	
	/**
	 * Creates a new String using the character sequence of the char array.
	 * Subsequent changes to data do not affect the String.
	 * 
	 * @param data
	 *            char array to copy
	 * @throws NullPointerException
	 *             if data is null
	 */
	// public String(char[] data) {
	// this(data, 0, data.length);
	// }
	public String(char[] ca) {
		int count = ca.length;
		value = new char[count];
		for (int i = 0; i < count; ++i)
			value[i] = ca[i];
	}
	
	public String(char[] data, int offset, int count)
			throws IndexOutOfBoundsException {

		if (offset < 0)
			throw new StringIndexOutOfBoundsException();
		if (count < 0)
			throw new StringIndexOutOfBoundsException();
		// equivalent to: offset + count < 0 || offset + count > data.length
		if (data.length - offset < count)
			throw new StringIndexOutOfBoundsException();
		value = new char[count];
		// VMSystem.arraycopy(data, offset, value, 0, count);
		//System.out.println("dbg: String constructor");
		// TODO: System.arraycopy produces stack overflow
		for (int i = 0; i < count; i++) {
			value[i] = data[i + offset];
		}
		// System.arraycopy(data, offset, value, 0, count);

	}
	
	public char charAt(int index) {

		if (index < 0 || index >= value.length)
			throw new StringIndexOutOfBoundsException(index);

		return value[index];
	}
	
	public byte[] getBytes() {
		// XXX - Throw an error here?
		// For now, default to the 'safe' encoding.
		byte[] bytes = new byte[value.length];
		for (int i = 0; i < value.length; i++) {
			if (value[i] <= 0xFF) {
				bytes[i] = (byte) value[i];
			} else {
				bytes[i] = (byte) '?';
			}
		}
		return bytes;

	}
	
	public int length() {
		return value.length;
	}
	
	public CharSequence subSequence(int begin, int end) {
		return substring(begin, end);
	}
	
	public String substring(int beginIndex, int endIndex) {
		if (beginIndex < 0 || endIndex > value.length || beginIndex > endIndex)
			throw new StringIndexOutOfBoundsException();
		if (beginIndex == 0 && endIndex == value.length)
			return this;
		int len = endIndex - beginIndex;
		return new String(value, beginIndex, len);
	}
	
	public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin) {
		if (srcBegin < 0 || srcBegin > srcEnd || srcEnd > value.length)
			throw new StringIndexOutOfBoundsException();
		for (int i = 0; i < srcEnd - srcBegin; ++i)
			dst[dstBegin + i] = value[srcBegin + i];
	}
	
	public String toString() {
		return this;
	}
	
	public static String valueOf(long l) {
		return Long.toString(l);
	}
}
