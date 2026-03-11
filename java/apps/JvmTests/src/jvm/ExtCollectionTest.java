package jvm;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Stack;
import java.util.Vector;

/**
 * Test extended collections from Phase 5 JDK porting.
 * Covers Vector, Stack, LinkedList, Hashtable, Arrays.sort, Collections.sort.
 */
public class ExtCollectionTest extends TestCase {

	public String toString() {
		return "ExtCollectionTest";
	}

	public boolean test() {
		boolean ok = true;
		if (!vectorBasic()) { ok = false; System.out.print(" T1"); }
		if (!vectorGrow()) { ok = false; System.out.print(" T2"); }
		if (!vectorRemove()) { ok = false; System.out.print(" T3"); }
		if (!stackBasic()) { ok = false; System.out.print(" T4"); }
		if (!linkedListBasic()) { ok = false; System.out.print(" T5"); }
		if (!linkedListAddRemove()) { ok = false; System.out.print(" T6"); }
		if (!hashtableBasic()) { ok = false; System.out.print(" T7"); }
		if (!hashtableEnum()) { ok = false; System.out.print(" T8"); }
		if (!arraysSortInt()) { ok = false; System.out.print(" T9"); }
		if (!arraysSortObject()) { ok = false; System.out.print(" T10"); }
		if (!collectionsSortList()) { ok = false; System.out.print(" T11"); }
		if (!collectionsReverse()) { ok = false; System.out.print(" T12"); }
		if (!collectionsBinarySearch()) { ok = false; System.out.print(" T13"); }
		return ok;
	}

	// --- Vector tests ---

	private boolean vectorBasic() {
		Vector v = new Vector(4);
		if (v.size() != 0) return false;
		v.addElement("a");
		v.addElement("b");
		v.addElement("c");
		if (v.size() != 3) return false;
		if (!"a".equals(v.elementAt(0))) return false;
		if (!"c".equals(v.elementAt(2))) return false;
		if (!v.contains("b")) return false;
		if (v.contains("x")) return false;
		return true;
	}

	private boolean vectorGrow() {
		Vector v = new Vector(2, 2);
		v.addElement("1");
		v.addElement("2");
		v.addElement("3"); // triggers capacity growth
		v.addElement("4");
		v.addElement("5");
		if (v.size() != 5) return false;
		if (!"1".equals(v.elementAt(0))) return false;
		if (!"5".equals(v.elementAt(4))) return false;
		return true;
	}

	private boolean vectorRemove() {
		Vector v = new Vector(4);
		v.addElement("a");
		v.addElement("b");
		v.addElement("c");
		v.removeElementAt(1); // remove "b"
		if (v.size() != 2) return false;
		if (!"a".equals(v.elementAt(0))) return false;
		if (!"c".equals(v.elementAt(1))) return false;
		v.insertElementAt("x", 1);
		if (v.size() != 3) return false;
		if (!"x".equals(v.elementAt(1))) return false;
		return true;
	}

	// --- Stack tests ---

	private boolean stackBasic() {
		Stack s = new Stack();
		if (!s.empty()) return false;
		s.push("first");
		s.push("second");
		s.push("third");
		if (s.size() != 3) return false;
		if (!"third".equals(s.peek())) return false;
		if (!"third".equals(s.pop())) return false;
		if (!"second".equals(s.pop())) return false;
		if (s.size() != 1) return false;
		if (s.search("first") != 1) return false;
		return true;
	}

	// --- LinkedList tests ---

	private boolean linkedListBasic() {
		LinkedList list = new LinkedList();
		list.add("a");
		list.add("b");
		list.add("c");
		if (list.size() != 3) return false;
		if (!"a".equals(list.getFirst())) return false;
		if (!"c".equals(list.getLast())) return false;
		if (!"b".equals(list.get(1))) return false;
		return true;
	}

	private boolean linkedListAddRemove() {
		LinkedList list = new LinkedList();
		list.addFirst("b");
		list.addFirst("a");
		list.addLast("c");
		if (list.size() != 3) return false;
		if (!"a".equals(list.removeFirst())) return false;
		if (!"c".equals(list.removeLast())) return false;
		if (list.size() != 1) return false;
		if (!"b".equals(list.get(0))) return false;
		return true;
	}

	// --- Hashtable tests ---

	private boolean hashtableBasic() {
		Hashtable ht = new Hashtable(4);
		ht.put("k1", "v1");
		ht.put("k2", "v2");
		ht.put("k3", "v3");
		if (ht.size() != 3) return false;
		if (!"v1".equals(ht.get("k1"))) return false;
		if (!"v2".equals(ht.get("k2"))) return false;
		ht.remove("k2");
		if (ht.size() != 2) return false;
		if (ht.get("k2") != null) return false;
		return true;
	}

	private boolean hashtableEnum() {
		Hashtable ht = new Hashtable(4);
		ht.put("a", "1");
		ht.put("b", "2");
		int count = 0;
		Enumeration keys = ht.keys();
		while (keys.hasMoreElements()) {
			keys.nextElement();
			count++;
		}
		if (count != 2) return false;
		// Test empty hashtable enumeration
		Hashtable empty = new Hashtable(4);
		if (empty.keys().hasMoreElements()) return false;
		return true;
	}

	// --- Arrays.sort tests ---

	private boolean arraysSortInt() {
		int[] arr = new int[5];
		arr[0] = 5; arr[1] = 3; arr[2] = 1; arr[3] = 4; arr[4] = 2;
		Arrays.sort(arr);
		if (arr[0] != 1) return false;
		if (arr[1] != 2) return false;
		if (arr[2] != 3) return false;
		if (arr[3] != 4) return false;
		if (arr[4] != 5) return false;
		return true;
	}

	private boolean arraysSortObject() {
		Integer[] arr = new Integer[3];
		arr[0] = new Integer(30);
		arr[1] = new Integer(10);
		arr[2] = new Integer(20);
		Arrays.sort(arr);
		if (arr[0].intValue() != 10) return false;
		if (arr[1].intValue() != 20) return false;
		if (arr[2].intValue() != 30) return false;
		return true;
	}

	// --- Collections algorithm tests ---

	private boolean collectionsSortList() {
		ArrayList list = new ArrayList(4);
		list.add(new Integer(30));
		list.add(new Integer(10));
		list.add(new Integer(20));
		Collections.sort(list);
		if (((Integer) list.get(0)).intValue() != 10) return false;
		if (((Integer) list.get(1)).intValue() != 20) return false;
		if (((Integer) list.get(2)).intValue() != 30) return false;
		return true;
	}

	private boolean collectionsReverse() {
		ArrayList list = new ArrayList(4);
		list.add("a");
		list.add("b");
		list.add("c");
		Collections.reverse(list);
		if (!"c".equals(list.get(0))) return false;
		if (!"b".equals(list.get(1))) return false;
		if (!"a".equals(list.get(2))) return false;
		return true;
	}

	private boolean collectionsBinarySearch() {
		ArrayList list = new ArrayList(4);
		list.add(new Integer(10));
		list.add(new Integer(20));
		list.add(new Integer(30));
		list.add(new Integer(40));
		int idx = Collections.binarySearch(list, new Integer(30));
		if (idx != 2) return false;
		int notFound = Collections.binarySearch(list, new Integer(25));
		if (notFound >= 0) return false; // should be negative (insertion point)
		return true;
	}
}
