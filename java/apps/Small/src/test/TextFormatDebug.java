package test;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

/**
 * Minimal debug test for DecimalFormat on FPGA.
 * Prints step-by-step to identify where it hangs.
 */
public class TextFormatDebug {
	public static void main(String[] args) {
		System.out.println("TF debug start");

		System.out.print("1 ensureInst...");
		RoundingMode.ensureInstances();
		System.out.println("ok");

		System.out.print("2 new DFS...");
		DecimalFormatSymbols dfs = new DecimalFormatSymbols();
		System.out.println("ok");

		System.out.print("3 new DF...");
		try {
			DecimalFormat df = new DecimalFormat("0", dfs);
			System.out.println("ok");

			System.out.print("4 format(42)...");
			String s = df.format(42);
			System.out.println("ok");

			System.out.print("5 result=");
			System.out.println(s);
		} catch (Throwable t) {
			System.out.print("EXCEPTION: ");
			System.out.println(t.toString());
		}

		System.out.println("TF debug done");
	}
}
