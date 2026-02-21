package java.lang;

import com.jopdesign.sys.Native;

public class Object {

	public int hashCode() {
		return Native.toInt(this);
	}

	public boolean equals(Object that) {
		return this == that;
	}

	public String toString() {
		return "Object " + hashCode();
	}
}
