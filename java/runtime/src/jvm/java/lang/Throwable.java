package java.lang;

import java.io.PrintStream;

public class Throwable {

	private final String message;

	public Throwable() {
		message = null;
	}

	public Throwable(String message) {
		this.message = message;
	}

	public String getMessage() {
		return message;
	}

	public void printStackTrace() {
		printStackTrace(System.err);
	}

	public void printStackTrace(PrintStream s) {
//		s.print(stackTraceString());
	}
}