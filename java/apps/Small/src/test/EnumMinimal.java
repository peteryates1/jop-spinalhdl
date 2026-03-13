package test;

import java.math.RoundingMode;

/**
 * Minimal enum test — does RoundingMode work on JOP?
 */
public class EnumMinimal {
	public static void main(String[] args) {
		System.out.println("EnumMinimal start");
		RoundingMode.ensureInstances();
		RoundingMode rm = RoundingMode.HALF_UP;
		System.out.print("rm=");
		System.out.println(rm.ordinal());
		System.out.println("done");
	}
}
