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
 * closing them later (current-status item 26, the missing element class) is a
 * visible change rather than a silent one.
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

		// --- UNSOUND BY DESIGN, pinned so a future fix is visible ---
		// The cp code for "[[I" is 10, the same as "[I", because
		// f_multianewarray needs the innermost element type. So a reference
		// array against a primitive code has to be accepted in case it is a
		// real int[][]. (int[]) intArrArr therefore succeeds when it should
		// throw. Likewise a reference-array TARGET is encoded 0 with no element
		// class, so any reference array matches it. When item 26 records the
		// element class both become false and these lines should be updated
		// deliberately rather than discovered.
		obj = ints2;
		check("int[][] as int[] (unsound)", castToIntArr(obj), true);
		check("int[][] as int[][]        ", castToIntArr2(obj), true);

		JVMHelp.wr("fails ");
		if (fails == 0) {
			JVMHelp.wr("0\nARRAYCAST OK\n");
		} else {
			JVMHelp.wr("some\nARRAYCAST FAIL\n");
		}
		JVMHelp.wr("ArrayCastTest done\n");
	}
}
