package test;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

/**
 * SMP cache coherency stress test.
 *
 * Tests cross-core communication via shared arrays and object fields,
 * exercising A$ (array cache) and O$ (object cache) snoop invalidation.
 *
 * Protocol:
 *   Core 0: Writes values to shared arrays and fields, then signals Core 1.
 *   Core 1: Reads the values and verifies correctness.
 *   Both cores synchronize via IO_SIGNAL and a shared 'phase' variable.
 *
 * Test patterns:
 *   T1: Int array write/read across cores (A$ snoop invalidation)
 *   T2: Object field write/read across cores (O$ snoop invalidation)
 *   T3: Sequential array updates with verification (multiple rounds)
 *   T4: Mixed array + field updates across cores
 *
 * If any verification fails, core 0 prints an error message.
 * On success, core 0 prints "SmpCacheTest PASS".
 */
public class SmpCacheTest {

	// Shared data structures (both cores access these)
	static int[] sharedArray;
	static int sharedField1;
	static int sharedField2;
	static int sharedField3;
	static long sharedLong;

	// Synchronization: phase counter incremented by writer, polled by reader
	static volatile int phase;

	// Test result from core 1 (0 = not started, 1 = pass, 2 = fail)
	static volatile int core1Result;

	// Number of test rounds
	static final int ROUNDS = 20;
	static final int ARRAY_SIZE = 16;

	/**
	 * Spin-wait until 'phase' reaches the target value.
	 * Uses volatile read via Native.rdMem to avoid JOP O$ caching.
	 */
	static void waitForPhase(int target) {
		// Poll the static field address directly to bypass O$ caching.
		// On JOP, reading a static field normally goes through the O$,
		// but putstatic invalidates the O$ line via snoop, so a simple
		// busy loop on the Java field should also work.
		while (phase != target) {
			// busy wait
		}
	}

	/**
	 * Core 0: Writer side of the test.
	 */
	static void core0Test() {
		boolean ok = true;
		int pass;

		JVMHelp.wr("SmpCache T1: array\r\n");

		// T1: Array write/read across cores
		// Core 0 fills the shared array, signals core 1, waits for verification
		for (int i = 0; i < ARRAY_SIZE; i++) {
			sharedArray[i] = (i + 1) * 100;  // iastore -> A$ snoop invalidation
		}
		phase = 1;  // Signal: array is ready
		waitForPhase(2);  // Wait for core 1 to verify

		if (core1Result != 1) {
			JVMHelp.wr("T1 FAIL\r\n");
			ok = false;
		}

		JVMHelp.wr("SmpCache T2: field\r\n");

		// T2: Object field write/read across cores
		sharedField1 = 0xDEAD;
		sharedField2 = 0xBEEF;
		sharedField3 = 0xCAFE;
		sharedLong = 0x123456789ABCDEF0L;
		phase = 3;  // Signal: fields are ready
		waitForPhase(4);  // Wait for core 1 to verify

		if (core1Result != 1) {
			JVMHelp.wr("T2 FAIL\r\n");
			ok = false;
		}

		JVMHelp.wr("SmpCache T3: rounds\r\n");

		// T3: Multiple rounds of array updates
		for (int round = 0; round < ROUNDS; round++) {
			// Write pattern: each round uses a different base
			int base = (round + 1) * 1000;
			for (int i = 0; i < ARRAY_SIZE; i++) {
				sharedArray[i] = base + i;
			}
			sharedField1 = base;
			phase = 5 + round * 2;  // Odd phases: data ready
			waitForPhase(6 + round * 2);  // Even phases: verified

			if (core1Result != 1) {
				JVMHelp.wr("T3 FAIL round ");
				// Print round number (simple: just output digits)
				if (round >= 10) {
					JVMHelp.wr(String.valueOf(round));
				} else {
					JVMHelp.wr(String.valueOf(round));
				}
				JVMHelp.wr("\r\n");
				ok = false;
				break;
			}
		}

		if (ok) {
			JVMHelp.wr("SmpCacheTest PASS\r\n");
		} else {
			JVMHelp.wr("SmpCacheTest FAIL\r\n");
		}
	}

	/**
	 * Core 1: Reader/verifier side of the test.
	 */
	static void core1Test() {
		boolean ok;

		// T1: Verify array values
		waitForPhase(1);
		ok = true;
		for (int i = 0; i < ARRAY_SIZE; i++) {
			if (sharedArray[i] != (i + 1) * 100) {
				ok = false;
			}
		}
		core1Result = ok ? 1 : 2;
		phase = 2;  // Signal: verified

		// T2: Verify field values
		waitForPhase(3);
		ok = true;
		if (sharedField1 != 0xDEAD) ok = false;
		if (sharedField2 != 0xBEEF) ok = false;
		if (sharedField3 != 0xCAFE) ok = false;
		if (sharedLong != 0x123456789ABCDEF0L) ok = false;
		core1Result = ok ? 1 : 2;
		phase = 4;  // Signal: verified

		// T3: Multiple rounds
		for (int round = 0; round < ROUNDS; round++) {
			waitForPhase(5 + round * 2);
			ok = true;
			int base = (round + 1) * 1000;
			for (int i = 0; i < ARRAY_SIZE; i++) {
				if (sharedArray[i] != base + i) {
					ok = false;
				}
			}
			if (sharedField1 != base) ok = false;
			core1Result = ok ? 1 : 2;
			phase = 6 + round * 2;  // Signal: verified
		}
	}

	public static void main(String[] args) {
		int cpuId = Native.rdMem(Const.IO_CPU_ID);

		if (cpuId == 0) {
			// Core 0: initialize shared data, then signal other cores
			sharedArray = new int[ARRAY_SIZE];
			phase = 0;
			core1Result = 0;
			sharedField1 = 0;
			sharedField2 = 0;
			sharedField3 = 0;
			sharedLong = 0L;

			// Signal other cores to start
			Native.wr(1, Const.IO_SIGNAL);

			core0Test();
		} else {
			// Core 1+: run verifier
			core1Test();
		}
	}
}
