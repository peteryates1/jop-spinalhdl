package java.lang;

import com.jopdesign.sys.Native;

public class Object {

	public int hashCode() {
		return Native.toInt(this);
	}

	public boolean equals(Object o) {
		return this == o;
	}

	public String toString() {
		return "Object " + hashCode();
	}
}
