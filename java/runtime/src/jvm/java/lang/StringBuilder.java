package java.lang;

public class StringBuilder {
	int count;
	char[] value;
	private static final int DEFAULT_CAPACITY = 16;

	public StringBuilder() {
		this(DEFAULT_CAPACITY);
	}

	public StringBuilder(int capacity) {
		value = new char[capacity];
	}

	public StringBuilder(String str) {
		count = str.length();
		value = new char[count + DEFAULT_CAPACITY];
		str.getChars(0, count, value, 0);
	}

	public void ensureCapacity(int minimumCapacity) {
		if (minimumCapacity > value.length) {
			int max = value.length * 2 + 2;
			minimumCapacity = (minimumCapacity < max ? max : minimumCapacity);
			char[] nb = new char[minimumCapacity];
			System.arraycopy(value, 0, nb, 0, count);
			value = nb;
		}
	}

	public StringBuilder append(String str) {
		if (str == null)
			str = "null";
		int len = str.length();
		ensureCapacity(count + len);
		str.getChars(0, len, value, count);
		count += len;
		return this;
	}

	public StringBuilder append(int inum) {
		return append(String.valueOf(inum));
	}

	public StringBuilder append(char c) {
		ensureCapacity(count + 1);
		value[count++] = c;
		return this;
	}

	public StringBuilder append(char[] str) {
		return append(str, 0, str.length);
	}

	public StringBuilder append(char[] str, int offset, int len) {
		ensureCapacity(count + len);
		for (int i = 0; i < len; i++) {
			value[count + i] = str[offset + i];
		}
		count += len;
		return this;
	}

	public int length() {
		return count;
	}

	public char charAt(int index) {
		if (index < 0 || index >= count)
			throw new StringIndexOutOfBoundsException();
		return value[index];
	}

	public void setCharAt(int index, char ch) {
		if (index < 0 || index >= count)
			throw new StringIndexOutOfBoundsException();
		value[index] = ch;
	}

	public String toString() {
		return new String(value, 0, count);
	}

	public void setLength(int newLength) {
		if (newLength < 0) throw new StringIndexOutOfBoundsException();
		ensureCapacity(newLength);
		if (newLength > count) {
			for (int i = count; i < newLength; i++) {
				value[i] = '\0';
			}
		}
		count = newLength;
	}

	public StringBuilder delete(int start, int end) {
		if (start < 0 || start > count || start > end)
			throw new StringIndexOutOfBoundsException();
		if (end > count) end = count;
		int len = end - start;
		if (len > 0) {
			for (int i = start; i < count - len; i++) {
				value[i] = value[i + len];
			}
			count -= len;
		}
		return this;
	}
}
