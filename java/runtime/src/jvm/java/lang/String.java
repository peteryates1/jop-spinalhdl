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

	public boolean equals(Object anObject) {
		if (this == anObject) return true;
		if (anObject instanceof String) {
			String s = (String) anObject;
			int n = value.length;
			if (n != s.value.length) return false;
			for (int i = 0; i < n; i++) {
				if (value[i] != s.value[i]) return false;
			}
			return true;
		}
		return false;
	}

	public boolean equalsIgnoreCase(String anotherString) {
		if (this == anotherString) return true;
		if (anotherString == null) return false;
		int n = value.length;
		if (n != anotherString.value.length) return false;
		for (int i = 0; i < n; i++) {
			char c1 = value[i];
			char c2 = anotherString.value[i];
			if (c1 != c2) {
				c1 = Character.toUpperCase(c1);
				c2 = Character.toUpperCase(c2);
				if (c1 != c2) return false;
			}
		}
		return true;
	}

	public char[] toCharArray() {
		char[] result = new char[value.length];
		for (int i = 0; i < value.length; i++) {
			result[i] = value[i];
		}
		return result;
	}

	public int indexOf(int ch) {
		return indexOf(ch, 0);
	}

	public int indexOf(int ch, int fromIndex) {
		if (fromIndex < 0) fromIndex = 0;
		for (int i = fromIndex; i < value.length; i++) {
			if (value[i] == ch) return i;
		}
		return -1;
	}

	public boolean startsWith(String prefix) {
		if (prefix.value.length > value.length) return false;
		for (int i = 0; i < prefix.value.length; i++) {
			if (value[i] != prefix.value[i]) return false;
		}
		return true;
	}

	public boolean endsWith(String suffix) {
		int off = value.length - suffix.value.length;
		if (off < 0) return false;
		for (int i = 0; i < suffix.value.length; i++) {
			if (value[off + i] != suffix.value[i]) return false;
		}
		return true;
	}

	public String toUpperCase() {
		char[] buf = new char[value.length];
		for (int i = 0; i < value.length; i++) {
			buf[i] = Character.toUpperCase(value[i]);
		}
		return new String(buf);
	}

	public String toLowerCase() {
		char[] buf = new char[value.length];
		for (int i = 0; i < value.length; i++) {
			buf[i] = Character.toLowerCase(value[i]);
		}
		return new String(buf);
	}

	public String trim() {
		int start = 0;
		int end = value.length;
		while (start < end && value[start] <= ' ') start++;
		while (end > start && value[end - 1] <= ' ') end--;
		if (start == 0 && end == value.length) return this;
		return substring(start, end);
	}

	public int hashCode() {
		int h = 0;
		for (int i = 0; i < value.length; i++) {
			h = 31 * h + value[i];
		}
		return h;
	}

	public String substring(int beginIndex) {
		return substring(beginIndex, value.length);
	}

	public int lastIndexOf(int ch) {
		for (int i = value.length - 1; i >= 0; i--) {
			if (value[i] == ch) return i;
		}
		return -1;
	}
}
