package java.lang;

public class IllegalStateException extends RuntimeException {

	private static final long serialVersionUID = 5083267876733660796L;

	public IllegalStateException() {
	}

	public IllegalStateException(java.lang.String message) {
		super(message);
	}

	public IllegalStateException(java.lang.String message, java.lang.Throwable cause) {
		super(message, cause);
	}

	public IllegalStateException(java.lang.Throwable cause) {
		super(cause);
	}

}
