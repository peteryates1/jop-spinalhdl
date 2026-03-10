package java.lang;

public class IllegalArgumentException extends RuntimeException {

	private static final long serialVersionUID = -6268746629144039879L;

	public IllegalArgumentException() {
	}

	public IllegalArgumentException(java.lang.String message) {
		super(message);
	}

	public IllegalArgumentException(java.lang.String message, java.lang.Throwable cause) {
		super(message, cause);
	}

	public IllegalArgumentException(java.lang.Throwable cause) {
		super(cause);
	}

}
