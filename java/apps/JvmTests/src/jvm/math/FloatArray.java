package jvm.math;

import jvm.TestCase;

/**
 * Test faload/fastore bytecodes with float arrays.
 */
public class FloatArray extends TestCase {

	public String toString() {
		return "FloatArray";
	}

	public boolean test() {

		boolean ok = true;

		float[] fa = new float[4];
		float v1 = 1.5F;
		float v2 = -2.5F;
		float v3 = 0.0F;
		float v4 = 100.25F;

		fa[0] = v1;
		fa[1] = v2;
		fa[2] = v3;
		fa[3] = v4;

		ok = ok && (fa[0] == v1);
		ok = ok && (fa[1] == v2);
		ok = ok && (fa[2] == v3);
		ok = ok && (fa[3] == v4);

		// Test overwrite
		float v5 = 42.0F;
		fa[0] = v5;
		ok = ok && (fa[0] == v5);
		ok = ok && (fa[1] == v2); // unchanged

		// Test arraylength
		ok = ok && (fa.length == 4);

		return ok;
	}
}
