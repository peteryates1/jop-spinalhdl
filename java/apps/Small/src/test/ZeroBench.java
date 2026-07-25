package test;

import com.jopdesign.sys.Native;
import com.jopdesign.sys.Const;

/**
 * Stage-0 hardware zero-fill DMA microbenchmark.
 *
 * Allocates a large int[] and times zeroing its backing store two ways on the
 * same bitstream:
 *   - HW: the BmbMemoryController zero-fill DMA (write IO_ZERO_START/END)
 *   - SW: the classic word-by-word Native.wrMem(0, ...) loop (what GC did)
 * Verifies the HW path actually zeroed the region (correctness on real DDR3),
 * then reports both times (microseconds) and the speedup.
 *
 * Build:  make -C java/apps/Small APP_NAME=ZeroBench
 */
public class ZeroBench {

	// OFF_PTR = 0: a JOP object/array reference is a handle whose word 0 is the
	// data pointer. Array elements start at dataPtr[0].
	static final int N = 1024 * 1024;   // 1M words = 4 MB

	public static void main(String[] args) {
		int[] buf = new int[N];
		int handle = Native.toInt(buf);
		int dataPtr = Native.rdMem(handle);   // handle[OFF_PTR] -> data address

		// --- HW zero-fill DMA ---
		// Dirty the region first so a successful zero is observable.
		for (int i = 0; i < N; i++) Native.wrMem(0x55555555, dataPtr + i);

		int t0 = Native.rd(Const.IO_US_CNT);
		Native.wr(dataPtr,     Const.IO_ZERO_START);
		Native.wr(dataPtr + N, Const.IO_ZERO_END);   // launch; blocks until done
		int t1 = Native.rd(Const.IO_US_CNT);

		// Correctness: every word must now read back 0.
		int nonzero = 0;
		for (int i = 0; i < N; i++) if (Native.rdMem(dataPtr + i) != 0) nonzero++;

		// --- SW zero loop (re-dirty first) ---
		for (int i = 0; i < N; i++) Native.wrMem(0x55555555, dataPtr + i);
		int t2 = Native.rd(Const.IO_US_CNT);
		for (int i = 0; i < N; i++) Native.wrMem(0, dataPtr + i);
		int t3 = Native.rd(Const.IO_US_CNT);

		int hwUs = t1 - t0;
		int swUs = t3 - t2;

		System.out.print("ZeroBench words=");
		System.out.print(N);
		System.out.print(" HW=");
		System.out.print(hwUs);
		System.out.print("us SW=");
		System.out.print(swUs);
		System.out.print("us nonzeroAfterHW=");
		System.out.println(nonzero);

		if (hwUs > 0) {
			System.out.print("speedup(SW/HW x100)=");
			System.out.println((swUs * 100) / hwUs);
		}
		System.out.println(nonzero == 0 ? "HW ZERO OK" : "HW ZERO FAILED");
	}
}
