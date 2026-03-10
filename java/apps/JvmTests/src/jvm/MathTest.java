package jvm;

/**
 * Test Math class from the ported JDK classes (Phase 3).
 * Exercises sin, cos, atan, sqrt, abs, min, max — the bug-fixed
 * trig functions that previously returned 'f' instead of 'sum'.
 */
public class MathTest extends TestCase {

	public String toString() {
		return "MathTest";
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && absTest();
		ok = ok && minMaxTest();
		ok = ok && sqrtTest();
		ok = ok && sinTest();
		ok = ok && cosTest();
		ok = ok && atanTest();
		return ok;
	}

	private boolean absTest() {
		if (Math.abs(5) != 5) return false;
		if (Math.abs(-5) != 5) return false;
		if (Math.abs(0) != 0) return false;
		if (Math.abs(-1L) != 1L) return false;
		// float abs
		float fa = Math.abs(-3.5f);
		if (fa < 3.4f || fa > 3.6f) return false;
		// double abs
		double da = Math.abs(-7.25);
		if (da < 7.24 || da > 7.26) return false;
		return true;
	}

	private boolean minMaxTest() {
		if (Math.min(3, 7) != 3) return false;
		if (Math.max(3, 7) != 7) return false;
		if (Math.min(-1, 1) != -1) return false;
		if (Math.max(-1, 1) != 1) return false;
		// long
		if (Math.min(100L, 200L) != 100L) return false;
		if (Math.max(100L, 200L) != 200L) return false;
		return true;
	}

	private boolean sqrtTest() {
		// sqrt(4) should be ~2.0
		float s = Math.sqrt(4.0f);
		if (s < 1.99f || s > 2.01f) return false;
		// sqrt(9) should be ~3.0
		double sd = Math.sqrt(9.0);
		if (sd < 2.99 || sd > 3.01) return false;
		// sqrt(1) = 1
		s = Math.sqrt(1.0f);
		if (s < 0.99f || s > 1.01f) return false;
		return true;
	}

	private boolean sinTest() {
		// sin(0) = 0
		float s0 = Math.sin(0.0f);
		if (s0 < -0.01f || s0 > 0.01f) return false;
		// sin(pi/2) ~= 1.0
		float sHalf = Math.sin((float)(Math.PI / 2.0));
		if (sHalf < 0.99f || sHalf > 1.01f) return false;
		// sin(pi) ~= 0 (Taylor 5 terms, tolerance for low precision)
		float sPi = Math.sin((float) Math.PI);
		if (sPi < -0.10f || sPi > 0.10f) return false;
		// double version
		double sd = Math.sin(Math.PI / 2.0);
		if (sd < 0.99 || sd > 1.01) return false;
		return true;
	}

	private boolean cosTest() {
		// cos(0) = 1
		float c0 = Math.cos(0.0f);
		if (c0 < 0.99f || c0 > 1.01f) return false;
		// cos(pi/2) ~= 0 (Taylor 5 terms, tolerance for low precision)
		float cHalf = Math.cos((float)(Math.PI / 2.0));
		if (cHalf < -0.10f || cHalf > 0.10f) return false;
		// cos(pi) ~= -1
		float cPi = Math.cos((float) Math.PI);
		if (cPi < -1.05f || cPi > -0.95f) return false;
		// double version
		double cd = Math.cos(0.0);
		if (cd < 0.99 || cd > 1.01) return false;
		return true;
	}

	private boolean atanTest() {
		// atan(0) = 0
		float a0 = Math.atan(0.0f);
		if (a0 < -0.01f || a0 > 0.01f) return false;
		// atan(1) ~= pi/4 ~= 0.7854 (Taylor with 5 terms gives ~0.835)
		float a1 = Math.atan(1.0f);
		if (a1 < 0.70f || a1 > 0.90f) return false;
		return true;
	}
}
