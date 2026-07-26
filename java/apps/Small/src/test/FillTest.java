package test;

import com.jopdesign.sys.Native;
import com.jopdesign.sys.Const;
import com.jopdesign.sys.JVMHelp;

/**
 * Direct end-to-end check of the block-fill path (controller FILL_REQ -> MemFill
 * -> backend fill) with a known VALID range. Allocates a buffer, dirties it,
 * HW-zeros its backing store via IO_ZERO_START/END, and verifies every word
 * reads back zero. No GC involved.
 *
 * Build:  make -C java/apps/Small APP_NAME=FillTest
 */
public class FillTest {

	static final int N = 512;   // words

	public static void main(String[] args) {
		int[] buf = new int[N];
		int dataPtr = Native.rdMem(Native.toInt(buf));   // handle[OFF_PTR] -> data

		for (int i = 0; i < N; i++) Native.wrMem(0x55555555, dataPtr + i);

		Native.wr(dataPtr,     Const.IO_ZERO_START);
		Native.wr(dataPtr + N, Const.IO_ZERO_END);   // valid range; blocks until done

		int nonzero = 0;
		for (int i = 0; i < N; i++) if (Native.rdMem(dataPtr + i) != 0) nonzero++;

		JVMHelp.wr(nonzero == 0 ? "FILL OK\n" : "FILL FAIL\n");
		// spin so the sim can capture the line
		for (;;) { }
	}
}
