package test;

import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;
import java.text.DecimalFormat;

/**
 * Minimal reproducer for the TextFormatTest failure that item 136's fix causes.
 *
 * Prints a marker before every step, so the last marker on the UART names the
 * operation that dies. Runs in ~2 minutes against JopJvmTestsBramSim's 13, which
 * is the whole point: five hypotheses were tested at 13 minutes each.
 *
 * Build:  make -C java tools && make -C java runtime \
 *         && make -C java/apps/Small APP_NAME=StrLitRepro APP_PKG=test
 * Run:    sbt "Test/runMain jop.system.StrLitReproSim"
 */
public class StrLitRepro {

	static void mark(String s) { JVMHelp.wr(s); JVMHelp.wr("\r\n"); }

	public static void main(String[] args) {

		mark("A start");


		// --- plain literal operations, expected fine ---------------------
		String lit = "42";
		mark("B length=" + lit.length());
		char c = lit.charAt(0);
		mark("C charAt ok");
		if (lit.equals("42")) mark("D equals-literal ok");

		// --- a heap String, the thing item 136 is about ------------------
		char[] ca = new char[2];
		ca[0] = '4'; ca[1] = '2';
		String heap = new String(ca);
		mark("E heap built");
		if (heap.equals(lit)) mark("F heap.equals(lit) TRUE");
		else                  mark("F heap.equals(lit) FALSE");
		Object o = heap;
		if (o instanceof String) mark("G heap instanceof ok");
		o = lit;
		if (o instanceof String) mark("H literal instanceof TRUE");
		else                     mark("H literal instanceof FALSE");

		// --- what TextFormatTest actually does, step by step -------------
		String empty = "";
		mark("E1 empty.length=" + empty.length());
		StringBuffer eb = new StringBuffer();
		eb.append(empty);
		mark("E2 append(empty) ok");

		int la = Native.toInt("");
		mark("W1 lit@" + la + " w=" + Native.rdMem(la) + "," + Native.rdMem(la+1)
		     + "," + Native.rdMem(la+2) + "," + Native.rdMem(la+3)
		     + "," + Native.rdMem(la+4) + "," + Native.rdMem(la+5)
		     + "," + Native.rdMem(la+6));
		mark("I before new DecimalFormat");
		DecimalFormat df = new DecimalFormat("0");
		mark("J DecimalFormat constructed");
		mark("W2 lit@" + la + " w=" + Native.rdMem(la) + "," + Native.rdMem(la+1)
		     + "," + Native.rdMem(la+2) + "," + Native.rdMem(la+3)
		     + "," + Native.rdMem(la+4) + "," + Native.rdMem(la+5)
		     + "," + Native.rdMem(la+6));
		mark("W3 \"\".length()=" + "".length());
		String s = df.format(42);
		mark("K format returned");
		mark("L formatted=" + s);
		if (s.length() == 2) mark("M length ok");
		if (s.charAt(0) == '4') mark("N charAt ok");

		mark("O grouping pattern");
		DecimalFormat df2 = new DecimalFormat("#,##0");
		mark("P constructed");
		String g = df2.format(1234567);
		mark("Q grouped=" + g);

		mark("Z DONE");
		for (;;);
	}
}
