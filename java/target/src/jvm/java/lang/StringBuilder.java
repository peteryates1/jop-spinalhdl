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
}
