package test;

import com.jopdesign.sys.*;

/**
 * checkcast and instanceof with an array on either side.
 *
 * Before the fix these did not throw a clean exception — `f_checkcast` and
 * `f_instanceof` read `OFF_MTAB_ALEN` as a method-table pointer, but for an
 * array that word is the LENGTH, so the superclass walk computed
 * `length - CLASS_HEADR` and chased pointers through arbitrary memory. The
 * observed symptom was a bogus "bytecode 255 not implemented" from somewhere
 * else entirely, which is what an unbounded wild read looks like from the
 * outside. `instanceof` did it silently, and that path is also how catch
 * clauses are matched.
 *
 * What is exact after the fix, and what deliberately is not, is documented on
 * `JVM.arrayCastOk`. This test pins both — including the unsound cases, so that
 * Item 26 has since recorded the element class in GC.OFF_ELEM, so the
 * reference-array and dimensionality cases below are now exact.
 */
public class ArrayCastTest {

	static Object obj;          // kept in a static so nothing is folded away
	static int fails;

	static void check(String what, boolean got, boolean want) {
		JVMHelp.wr(what);
		if (got == want) {
			JVMHelp.wr(" ok\n");
		} else {
			JVMHelp.wr(" FAIL\n");
			++fails;
		}
	}

	/** Does `(int[]) o` succeed? */
	static boolean castToIntArr(Object o) {
		try {
			int[] a = (int[]) o;
			return a != null || o == null;
		} catch (ClassCastException e) {
			return false;
		}
	}

	/** Does `(byte[]) o` succeed? */
	static boolean castToByteArr(Object o) {
		try {
			byte[] a = (byte[]) o;
			return a != null || o == null;
		} catch (ClassCastException e) {
			return false;
		}
	}

	/** Does `(int[][]) o` succeed? */
	static boolean castToIntArr2(Object o) {
		try {
			int[][] a = (int[][]) o;
			return a != null || o == null;
		} catch (ClassCastException e) {
			return false;
		}
	}

	static class Base { int a; }
	static class Derived extends Base { int b; }

	/** Does `(Base[]) o` succeed? */
	static boolean castToBaseArr(Object o) {
		try {
			Base[] a = (Base[]) o;
			return a != null || o == null;
		} catch (ClassCastException e) {
			return false;
		}
	}

	/** Does `(Derived[]) o` succeed? */
	static boolean castToDerivedArr(Object o) {
		try {
			Derived[] a = (Derived[]) o;
			return a != null || o == null;
		} catch (ClassCastException e) {
			return false;
		}
	}

	/** Does `(StringBuffer) o` succeed? A class target with an array source. */
	static boolean castToClass(Object o) {
		try {
			StringBuffer s = (StringBuffer) o;
			return s != null || o == null;
		} catch (ClassCastException e) {
			return false;
		}
	}

	public static void main(String[] args) {

		JVMHelp.wr("ArrayCastTest start\n");

		int[] ints = new int[4];
		byte[] bytes = new byte[4];
		int[][] ints2 = new int[3][];
		ints2[0] = new int[2];
		StringBuffer sb = new StringBuffer("x");

		// --- primitive array targets: exact both ways ---
		obj = ints;
		check("int[] as int[]      ", castToIntArr(obj), true);
		check("int[] instanceof    ", obj instanceof int[], true);
		check("int[] as byte[]     ", castToByteArr(obj), false);
		check("int[] inst byte[]   ", obj instanceof byte[], false);

		obj = bytes;
		check("byte[] as byte[]    ", castToByteArr(obj), true);
		check("byte[] as int[]     ", castToIntArr(obj), false);

		// --- plain object against an array target: exact ---
		obj = sb;
		check("object as int[]     ", castToIntArr(obj), false);
		check("object inst int[]   ", obj instanceof int[], false);

		// --- array against a class target: exact, only Object matches ---
		obj = ints;
		check("int[] as StringBuf  ", castToClass(obj), false);
		check("int[] inst StringBuf", obj instanceof StringBuffer, false);

		// --- null is always castable, never an instanceof ---
		obj = null;
		check("null as int[]       ", castToIntArr(obj), true);
		check("null inst int[]     ", obj instanceof int[], false);

		// --- the case that started this: reading a payload back out ---
		obj = ints2;
		int[] inner = ints2[0];
		inner[1] = 7;
		check("int[][] elem works  ", ints2[0][1] == 7, true);

		// --- reference-array identity and covariance (item 26) ---
		// These are what the element class in GC.OFF_ELEM buys. Before it,
		// every one of them silently succeeded.
		Base[] bases = new Base[3];
		Derived[] derived = new Derived[3];
		derived[0] = new Derived();

		obj = derived;
		check("Derived[] as Derived[]", castToDerivedArr(obj), true);
		check("Derived[] as Base[]   ", castToBaseArr(obj), true);   // covariant
		obj = bases;
		check("Base[] as Base[]      ", castToBaseArr(obj), true);
		check("Base[] as Derived[]   ", castToDerivedArr(obj), false); // NOT covariant
		check("Base[] inst Derived[] ", obj instanceof Derived[], false);
		check("Base[] as int[]       ", castToIntArr(obj), false);
		obj = ints;
		check("int[] as Base[]       ", castToBaseArr(obj), false);

		// --- dimensionality is now distinguished ---
		obj = ints2;
		check("int[][] as int[][]    ", castToIntArr2(obj), true);
		check("int[][] as int[]      ", castToIntArr(obj), false);
		obj = ints;
		check("int[] as int[][]      ", castToIntArr2(obj), false);

		JVMHelp.wr("fails ");
		if (fails == 0) {
			JVMHelp.wr("0\nARRAYCAST OK\n");
		} else {
			JVMHelp.wr("some\nARRAYCAST FAIL\n");
		}
		JVMHelp.wr("ArrayCastTest done\n");
	}
}
