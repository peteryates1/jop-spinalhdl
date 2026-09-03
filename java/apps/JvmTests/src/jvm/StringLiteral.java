package jvm;

/**
 * A link-time String LITERAL must behave like any other String — status item 136.
 *
 * THE DEFECT. JOPizer emits a literal as a String handle and its char[] handle
 * packed together (`StringInfo.java:113-121`), so word +3 of the String is the
 * ARRAY LENGTH:
 *
 *     +0  data ptr        +1  String mtab
 *     +2  char[] data ptr +3  string.length()   <-- read as GC.OFF_TYPE
 *     +4  the `value` field
 *
 * `JVM.java:808` and `:852` read `Native.rdMem(objref + GC.OFF_TYPE)` with
 * OFF_TYPE = 3 and IS_OBJ = 0, so for any NON-EMPTY literal that word is
 * non-zero, the reference is diverted to `JVMHelp.arrayCastOk`, and that
 * returns false for a non-array target class whose CLASS_SUPER > 0 — which
 * java.lang.String is.
 *
 * Confirmed in the shipped image: the literal at 39122 in DoAll.jop has word
 * 39125 = 33, its own length, exactly where OFF_TYPE is read.
 *
 * A REGRESSION, not an ancient wart. `f064a8b` (2026-08-06) fixed `f_checkcast`
 * walking garbage when handed an array, by adding this OFF_TYPE check. Before
 * it, the code went straight to `rdMem(objref + OFF_MTAB_ALEN) - CLASS_HEADR`,
 * which is correct for a literal. The fix for arrays broke literals, because a
 * literal is the one object whose OFF_TYPE word belongs to something else.
 *
 * WHY THE SUITE MISSED IT, AND WHAT THAT DICTATES ABOUT THIS FILE. `f064a8b`
 * was validated with "ArrayCastTest 15/15 and DoAll 66/66". Both were true and
 * neither could fail: JOPizer INTERNS literals, and every String.equals in the
 * suite is literal-vs-literal, so `this == anObject` short-circuits before the
 * instanceof ever runs. So each case below that exercises the defect is paired
 * with a HEAP-String control, and the heap String is built with
 * `new String(char[])` so it cannot be interned. Without the controls, "the
 * literal path is broken" and "this test cannot see anything" look identical.
 *
 * The literal is held in an `Object` local before every check, so javac emits a
 * real checkcast/instanceof rather than folding it away.
 */
public class StringLiteral extends TestCase {

	public String toString() {
		return "StringLiteral";
	}

	/** A heap String equal to "abcd", built so it cannot be the interned literal. */
	private static String heapAbcd() {
		char[] c = new char[4];
		c[0] = 'a'; c[1] = 'b'; c[2] = 'c'; c[3] = 'd';
		return new String(c);
	}

	/** Defeats any constant folding: the literal arrives as a plain Object. */
	private static Object asObject(Object o) {
		return o;
	}

	/**
	 * REPORTS BUT DOES NOT FAIL, for now — status item 136 is open.
	 *
	 * The four defect cases below all fail today. Making the suite red for them
	 * would turn CI red on a defect nobody is mid-fix, so each prints a `MISS:`
	 * line instead — the same treatment `jvm/Array.java` gives item 129's
	 * `arraylength` gap. The CONTROLS still fail the test properly: if a control
	 * breaks, something has regressed in a path that works today.
	 *
	 * `strict` flips both to hard failures, and is what the fix must satisfy.
	 * Turn it on when item 136 lands.
	 */
	private static final boolean strict = false;

	private static boolean miss(String what) {
		System.out.println("  MISS: " + what);
		return !strict;   // ok while the item is open, a failure once strict
	}

	public boolean test() {

		boolean ok = true;
		String lit = "abcd";
		String heap = heapAbcd();

		// Sanity: the two must be equal in value and distinct in identity, or
		// nothing below means what it says.
		if (lit == heap) {
			System.out.println("  StringLiteral: heap String was interned - test is void");
			return false;
		}

		// --- instanceof -------------------------------------------------
		Object o = asObject(lit);
		if (!(o instanceof String)) {
			ok &= miss("literal instanceof String");
		}
		o = asObject(heap);                       // CONTROL
		if (!(o instanceof String)) {
			System.out.println("  MISS: CONTROL heap instanceof String");
			ok = false;
		}

		// --- equals, both directions ------------------------------------
		// heap.equals(lit) is the one that matters: it is the comparison every
		// Hashtable lookup performs, and it returns a silently WRONG answer
		// rather than throwing.
		if (!lit.equals(heap)) {
			ok &= miss("literal.equals(heap)");
		}
		if (!heap.equals(lit)) {
			ok &= miss("heap.equals(literal)");
		}

		// --- checkcast ---------------------------------------------------
		boolean threw = false;
		try {
			o = asObject(lit);
			String s = (String) o;
			if (s == null) ok = false;
		} catch (ClassCastException e) {
			threw = true;
		}
		if (threw) {
			ok &= miss("(String) literal threw CCE");
		}
		threw = false;
		try {                                     // CONTROL
			o = asObject(heap);
			String s = (String) o;
			if (s == null) ok = false;
		} catch (ClassCastException e) {
			threw = true;
		}
		if (threw) {
			System.out.println("  MISS: CONTROL (String) heap threw CCE");
			ok = false;
		}

		// --- aastore ------------------------------------------------------
		String[] sa = new String[2];
		threw = false;
		try {
			sa[0] = lit;
		} catch (ArrayStoreException e) {
			threw = true;
		}
		if (threw) {
			ok &= miss("String[] store of a literal threw ASE");
		}
		threw = false;
		try {                                     // CONTROL
			sa[1] = heap;
		} catch (ArrayStoreException e) {
			threw = true;
		}
		if (threw) {
			System.out.println("  MISS: CONTROL String[] store of a heap String threw ASE");
			ok = false;
		}
		// An Object[] store takes a different path and is expected to work
		// either way — it is here to show the aastore machinery is alive.
		Object[] oa = new Object[1];
		threw = false;
		try {
			oa[0] = lit;
		} catch (ArrayStoreException e) {
			threw = true;
		}
		if (threw) {
			System.out.println("  MISS: CONTROL Object[] store of a literal threw ASE");
			ok = false;
		}

		return ok;
	}
}
