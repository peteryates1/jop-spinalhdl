package java.lang;

public class RuntimeException extends Throwable {

	public RuntimeException() {
	}
	
	public RuntimeException(String message) {
		super(message);
	}

	public RuntimeException(String message, Throwable cause) {
		super(message);
	}

	public RuntimeException(Throwable cause) {
		super(cause == null ? null : cause.toString());
	}

}
