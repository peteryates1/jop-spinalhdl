package test;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

public class PerfTest {
	static final int N = 10000;

	// Static fields prevent constant folding
	static int si1 = 17, si2 = 3;
	static float sf1 = 3.14f, sf2 = 1.5f;

	// Sinks prevent dead code elimination
	static int sinkI;
	static float sinkF;

	static void wrInt(int val) {
		if (val < 0) { JVMHelp.wr('-'); val = -val; }
		if (val >= 10) wrInt(val / 10);
		JVMHelp.wr((char) ('0' + val % 10));
	}

	static void report(String name, int cycles) {
		JVMHelp.wr(name);
		JVMHelp.wr('\t');
		wrInt(cycles / N);
		JVMHelp.wr(" cy/it\n");
	}

	public static void main(String[] args) {
		int t;

		JVMHelp.wr("PerfTest N=");
		wrInt(N);
		JVMHelp.wr("\n\n");

		// ---- Integer ----
		JVMHelp.wr("-- Integer --\n");
		int a = si1, b = si2, r = 0;

		t = Native.rd(Const.IO_CNT);
		for (int i = 0; i < N; i++) r = a + b;
		t = Native.rd(Const.IO_CNT) - t;
		sinkI = r; report("iadd", t);

		t = Native.rd(Const.IO_CNT);
		for (int i = 0; i < N; i++) r = a - b;
		t = Native.rd(Const.IO_CNT) - t;
		sinkI = r; report("isub", t);

		t = Native.rd(Const.IO_CNT);
		for (int i = 0; i < N; i++) r = a * b;
		t = Native.rd(Const.IO_CNT) - t;
		sinkI = r; report("imul", t);

		t = Native.rd(Const.IO_CNT);
		for (int i = 0; i < N; i++) r = a / b;
		t = Native.rd(Const.IO_CNT) - t;
		sinkI = r; report("idiv", t);

		t = Native.rd(Const.IO_CNT);
		for (int i = 0; i < N; i++) r = a % b;
		t = Native.rd(Const.IO_CNT) - t;
		sinkI = r; report("irem", t);

		// ---- Float ----
		JVMHelp.wr("\n-- Float --\n");
		float fa = sf1, fb = sf2, fr = 0;

		t = Native.rd(Const.IO_CNT);
		for (int i = 0; i < N; i++) fr = fa + fb;
		t = Native.rd(Const.IO_CNT) - t;
		sinkF = fr; report("fadd", t);

		t = Native.rd(Const.IO_CNT);
		for (int i = 0; i < N; i++) fr = fa - fb;
		t = Native.rd(Const.IO_CNT) - t;
		sinkF = fr; report("fsub", t);

		t = Native.rd(Const.IO_CNT);
		for (int i = 0; i < N; i++) fr = fa * fb;
		t = Native.rd(Const.IO_CNT) - t;
		sinkF = fr; report("fmul", t);

		t = Native.rd(Const.IO_CNT);
		for (int i = 0; i < N; i++) fr = fa / fb;
		t = Native.rd(Const.IO_CNT) - t;
		sinkF = fr; report("fdiv", t);

		t = Native.rd(Const.IO_CNT);
		for (int i = 0; i < N; i++) fr = -fa;
		t = Native.rd(Const.IO_CNT) - t;
		sinkF = fr; report("fneg", t);

		t = Native.rd(Const.IO_CNT);
		for (int i = 0; i < N; i++) fr = (float) a;
		t = Native.rd(Const.IO_CNT) - t;
		sinkF = fr; report("i2f", t);

		t = Native.rd(Const.IO_CNT);
		for (int i = 0; i < N; i++) r = (int) fa;
		t = Native.rd(Const.IO_CNT) - t;
		sinkI = r; report("f2i", t);

		// fcmpl: fa > fb compiles to fcmpl + ifle
		t = Native.rd(Const.IO_CNT);
		for (int i = 0; i < N; i++) {
			if (fa > fb) r = 1; else r = 0;
		}
		t = Native.rd(Const.IO_CNT) - t;
		sinkI = r; report("fcmp+br", t);

		JVMHelp.wr("\ndone\n");
	}
}
