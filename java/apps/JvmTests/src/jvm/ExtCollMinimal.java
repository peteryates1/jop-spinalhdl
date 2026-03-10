package jvm;

import java.util.Vector;
import java.util.Stack;
import java.util.LinkedList;
import java.util.Hashtable;
import java.util.Enumeration;

/**
 * Minimal Phase 5 test — no sorting (avoids Arrays/Collections overhead).
 * Tests Vector, Stack, LinkedList, Hashtable basics.
 */
public class ExtCollMinimal extends TestCase {

	public String toString() {
		return "ExtCollMin";
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && vectorBasic();
		ok = ok && stackBasic();
		ok = ok && linkedListBasic();
		ok = ok && hashtableBasic();
		return ok;
	}

	private boolean vectorBasic() {
		Vector v = new Vector(4);
		v.addElement("a");
		v.addElement("b");
		v.addElement("c");
		if (v.size() != 3) return false;
		if (!"a".equals(v.elementAt(0))) return false;
		if (!"c".equals(v.elementAt(2))) return false;
		v.removeElementAt(1);
		if (v.size() != 2) return false;
		return true;
	}

	private boolean stackBasic() {
		Stack s = new Stack();
		s.push("first");
		s.push("second");
		if (!"second".equals(s.pop())) return false;
		if (s.size() != 1) return false;
		return true;
	}

	private boolean linkedListBasic() {
		LinkedList list = new LinkedList();
		list.add("a");
		list.add("b");
		list.add("c");
		if (list.size() != 3) return false;
		if (!"a".equals(list.getFirst())) return false;
		if (!"c".equals(list.getLast())) return false;
		if (!"a".equals(list.removeFirst())) return false;
		if (list.size() != 2) return false;
		return true;
	}

	private boolean hashtableBasic() {
		Hashtable ht = new Hashtable(4);
		ht.put("k1", "v1");
		ht.put("k2", "v2");
		if (ht.size() != 2) return false;
		if (!"v1".equals(ht.get("k1"))) return false;
		ht.remove("k1");
		if (ht.size() != 1) return false;
		// Test empty enumeration
		Hashtable empty = new Hashtable(4);
		if (empty.keys().hasMoreElements()) return false;
		return true;
	}
}
