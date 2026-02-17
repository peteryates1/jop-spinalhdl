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
}
