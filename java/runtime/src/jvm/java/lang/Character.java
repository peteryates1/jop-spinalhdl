package java.lang;

public class Character {

	public static final int MAX_RADIX = 36;

	public static final char MAX_VALUE = '\u007F';

	public static final int MIN_RADIX = 2;

	public static final char MIN_VALUE = '\u0000';

	private final char value;
	
	public Character(char value) {
		this.value = value;
	}

	public char charValue() {
		return value;
	}

	public static char toUpperCase(char ch) {
		if (ch >= 'a' && ch <= 'z') return (char)(ch - 32);
		return ch;
	}

	public static char toLowerCase(char ch) {
		if (ch >= 'A' && ch <= 'Z') return (char)(ch + 32);
		return ch;
	}

	public static boolean isLetter(char ch) {
		return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
	}

	public static boolean isDigit(char ch) {
		return ch >= '0' && ch <= '9';
	}
}
