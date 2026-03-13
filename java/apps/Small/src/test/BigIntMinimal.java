package test;

import java.math.BigInteger;

/**
 * Minimal BigInteger test — just one operation.
 */
public class BigIntMinimal {
	public static void main(String[] args) {
		System.out.println("BigIntMinimal start");
		BigInteger a = BigInteger.valueOf(42);
		System.out.print("a=");
		System.out.println(a.intValue());
		BigInteger b = BigInteger.valueOf(10);
		BigInteger c = a.add(b);
		System.out.print("42+10=");
		System.out.println(c.intValue());
		System.out.println("done");
	}
}
