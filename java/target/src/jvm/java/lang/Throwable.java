
package java.lang;

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
}