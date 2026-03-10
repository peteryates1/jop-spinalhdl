package jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

/**
 * Test collections from the ported JDK classes (Phase 2).
 * Exercises ArrayList, HashMap, HashSet, Iterator, and for-each.
 */
public class CollectionTest extends TestCase {

	public String toString() {
		return "CollectionTest";
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && arrayListBasic();
		ok = ok && arrayListGrow();
		ok = ok && arrayListRemove();
		ok = ok && arrayListIterator();
		ok = ok && arrayListAddAll();
		ok = ok && hashMapBasic();
		ok = ok && hashMapOverwrite();
		ok = ok && hashMapGrow();
		ok = ok && hashMapRemove();
		ok = ok && hashMapIterate();
		ok = ok && hashSetBasic();
		ok = ok && forEachLoop();
		return ok;
	}

	// --- ArrayList tests ---

	private boolean arrayListBasic() {
		ArrayList list = new ArrayList(4);
		if (list.size() != 0) return false;
		list.add("a");
		list.add("b");
		list.add("c");
		if (list.size() != 3) return false;
		if (!"a".equals(list.get(0))) return false;
		if (!"b".equals(list.get(1))) return false;
		if (!"c".equals(list.get(2))) return false;
		return true;
	}

	private boolean arrayListGrow() {
		// Start with capacity 2, add more to trigger resize
		ArrayList list = new ArrayList(2);
		list.add("x");
		list.add("y");
		list.add("z"); // triggers ensureCapacity
		list.add("w");
		if (list.size() != 4) return false;
		if (!"x".equals(list.get(0))) return false;
		if (!"w".equals(list.get(3))) return false;
		return true;
	}

	private boolean arrayListRemove() {
		ArrayList list = new ArrayList(4);
		list.add("a");
		list.add("b");
		list.add("c");
		list.remove(1); // remove "b"
		if (list.size() != 2) return false;
		if (!"a".equals(list.get(0))) return false;
		if (!"c".equals(list.get(1))) return false;
		return true;
	}

	private boolean arrayListIterator() {
		ArrayList list = new ArrayList(4);
		list.add("p");
		list.add("q");
		list.add("r");
		int count = 0;
		Iterator it = list.iterator();
		while (it.hasNext()) {
			it.next();
			count++;
		}
		return count == 3;
	}

	private boolean arrayListAddAll() {
		ArrayList a = new ArrayList(4);
		a.add("1");
		a.add("2");
		ArrayList b = new ArrayList(4);
		b.add("3");
		b.add("4");
		a.addAll(b);
		if (a.size() != 4) return false;
		if (!"3".equals(a.get(2))) return false;
		if (!"4".equals(a.get(3))) return false;
		return true;
	}

	// --- HashMap tests ---

	private boolean hashMapBasic() {
		HashMap map = new HashMap(4, 0.75f);
		if (map.size() != 0) return false;
		map.put("key1", "val1");
		map.put("key2", "val2");
		if (map.size() != 2) return false;
		if (!"val1".equals(map.get("key1"))) return false;
		if (!"val2".equals(map.get("key2"))) return false;
		if (map.get("nokey") != null) return false;
		return true;
	}

	private boolean hashMapOverwrite() {
		HashMap map = new HashMap(4, 0.75f);
		map.put("k", "old");
		map.put("k", "new");
		if (map.size() != 1) return false;
		if (!"new".equals(map.get("k"))) return false;
		return true;
	}

	private boolean hashMapGrow() {
		// capacity 4, load factor 0.75 => threshold 3, adding 4+ triggers resize
		HashMap map = new HashMap(4, 0.75f);
		map.put("a", "1");
		map.put("b", "2");
		map.put("c", "3");
		map.put("d", "4"); // triggers resize
		map.put("e", "5");
		if (map.size() != 5) return false;
		if (!"1".equals(map.get("a"))) return false;
		if (!"5".equals(map.get("e"))) return false;
		return true;
	}

	private boolean hashMapRemove() {
		HashMap map = new HashMap(4, 0.75f);
		map.put("x", "1");
		map.put("y", "2");
		map.remove("x");
		if (map.size() != 1) return false;
		if (map.get("x") != null) return false;
		if (!"2".equals(map.get("y"))) return false;
		return true;
	}

	private boolean hashMapIterate() {
		HashMap map = new HashMap(4, 0.75f);
		map.put("a", "1");
		map.put("b", "2");
		map.put("c", "3");
		int count = 0;
		Iterator it = map.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry e = (Map.Entry) it.next();
			if (e.getKey() == null) return false;
			if (e.getValue() == null) return false;
			count++;
		}
		return count == 3;
	}

	// --- HashSet tests ---

	private boolean hashSetBasic() {
		HashSet set = new HashSet(4);
		set.add("a");
		set.add("b");
		set.add("a"); // duplicate
		if (set.size() != 2) return false;
		if (!set.contains("a")) return false;
		if (!set.contains("b")) return false;
		if (set.contains("c")) return false;
		set.remove("a");
		if (set.size() != 1) return false;
		if (set.contains("a")) return false;
		return true;
	}

	// --- For-each loop (uses Iterable + Iterator) ---

	private boolean forEachLoop() {
		ArrayList list = new ArrayList(4);
		list.add("x");
		list.add("y");
		list.add("z");
		int count = 0;
		for (Object o : list) {
			if (o == null) return false;
			count++;
		}
		return count == 3;
	}
}
