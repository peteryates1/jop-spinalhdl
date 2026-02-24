package jvm;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.Native;

/**
 * Regression test for bug #12: readArrayCache/readObjectCache persistence.
 *
 * Bug: When the memory controller processes an I/O read in IDLE state,
 * the readArrayCache/readObjectCache MUX overrides persist because no
 * state change clears them. A subsequent memory read (getfield/iaload)
 * could return stale cache output data instead of fresh memory data.
 *
 * Test pattern: write to a field, read an I/O port (timer counter),
 * then read the field back and verify the field value is correct.
 * This exercises the IDLE -> I/O read -> IDLE -> getfield sequence.
 *
 * Also tests the array cache variant: write to an array element,
 * read an I/O port, then read the array element back.
 */
public class CachePersistence extends TestCase {

	int field1;
	int field2;
	int field3;

	public String toString() {
		return "CachePersist";
	}

	public boolean test() {
		boolean ok = true;

		// Part 1: Object cache persistence after I/O read
		field1 = 111;
		field2 = 222;
		field3 = 333;

		// Read I/O (timer counter) - this exercises the I/O read path in IDLE
		int cnt1 = Native.rd(Const.IO_CNT);

		// Now read fields - should get correct values, not stale cache data
		ok = ok && (field1 == 111);
		ok = ok && (field2 == 222);
		ok = ok && (field3 == 333);

		// Change fields, do I/O read, verify again
		field1 = 444;
		int cnt2 = Native.rd(Const.IO_US_CNT);
		ok = ok && (field1 == 444);
		ok = ok && (field2 == 222);

		// Multiple I/O reads interleaved with field reads
		field2 = 555;
		int cnt3 = Native.rd(Const.IO_CNT);
		ok = ok && (field2 == 555);
		int cnt4 = Native.rd(Const.IO_CNT);
		ok = ok && (field1 == 444);
		int cnt5 = Native.rd(Const.IO_CNT);
		ok = ok && (field3 == 333);

		// Part 2: Array cache persistence after I/O read
		int[] arr = new int[4];
		arr[0] = 1000;
		arr[1] = 2000;
		arr[2] = 3000;
		arr[3] = 4000;

		// I/O read to potentially corrupt array cache state
		int cnt6 = Native.rd(Const.IO_CNT);

		// Read array elements - should get correct values
		ok = ok && (arr[0] == 1000);
		ok = ok && (arr[1] == 2000);
		ok = ok && (arr[2] == 3000);
		ok = ok && (arr[3] == 4000);

		// Modify, I/O read, verify
		arr[0] = 9999;
		int cnt7 = Native.rd(Const.IO_US_CNT);
		ok = ok && (arr[0] == 9999);
		ok = ok && (arr[1] == 2000);

		// Part 3: Mixed object field + array + I/O reads
		field1 = 777;
		arr[2] = 8888;
		int cnt8 = Native.rd(Const.IO_CNT);
		ok = ok && (field1 == 777);
		ok = ok && (arr[2] == 8888);

		// Use the cnt values to prevent dead code elimination
		// (just reference them; the actual values don't matter)
		if (cnt1 == cnt2 && cnt2 == cnt3 && cnt3 == cnt4
			&& cnt4 == cnt5 && cnt5 == cnt6 && cnt6 == cnt7
			&& cnt7 == cnt8 && cnt8 == -99999) {
			// Extremely unlikely - just ensures cnt vars aren't optimized away
			ok = false;
		}

		return ok;
	}
}
