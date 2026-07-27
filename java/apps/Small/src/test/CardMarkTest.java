package test;

import com.jopdesign.sys.Native;
import com.jopdesign.sys.Const;
import com.jopdesign.sys.JVMHelp;

/**
 * End-to-end check of the HW card-marking barrier (Stage 1). Sets a tenure
 * window over an array, clears the card table, writes a few elements in
 * distinct cards, and verifies exactly those cards are dirty (and an unwritten
 * card is clean). No GC involved.
 *
 * Build:  make -C java/apps/Small APP_NAME=CardMarkTest
 */
public class CardMarkTest {

	static final int N = 4096;   // words

	public static void main(String[] args) {
		int[] buf = new int[N];
		int dataPtr = Native.rdMem(Native.toInt(buf));   // handle[OFF_PTR] -> data word addr

		int cardShift = Native.rd(Const.IO_CARD_SHIFT);
		int nWords    = Native.rd(Const.IO_CARD_COUNT);

		// Tenure window = just this array (so only its writes mark).
		Native.wr(dataPtr,     Const.IO_CARD_TENURE_LO);
		Native.wr(dataPtr + N, Const.IO_CARD_TENURE_HI);

		// Clear the whole table, then wait for the HW sweep (nWords cycles).
		Native.wr(-1, Const.IO_CARD_CLEAR);
		int spin = 0;
		for (int i = 0; i < nWords + 64; i++) spin += i;

		// Write three elements, each in a distinct card (one card = 1<<cardShift words).
		int step = 1 << cardShift;
		buf[0]        = 0x11111111;
		buf[step]     = 0x22222222;
		buf[2 * step] = 0x33333333;

		boolean ok = true;
		ok = ok && cardSet(dataPtr,            cardShift);   // card A -> dirty
		ok = ok && cardSet(dataPtr + step,     cardShift);   // card B -> dirty
		ok = ok && cardSet(dataPtr + 2 * step, cardShift);   // card C -> dirty
		ok = ok && !cardSet(dataPtr + 8 * step, cardShift);  // unwritten -> clean

		JVMHelp.wr(ok ? "CARD OK\n" : "CARD FAIL\n");
		JVMHelp.wr("spin=" + spin + "\n");   // keep `spin` live
		for (;;) { }
	}

	// Is the card covering word address `wordAddr` marked dirty?
	static boolean cardSet(int wordAddr, int cardShift) {
		int card = wordAddr >>> cardShift;
		int widx = card >>> 5;
		int bit  = card & 31;
		Native.wr(widx, Const.IO_CARD_IDX);
		int data = Native.rd(Const.IO_CARD_DATA);
		return ((data >>> bit) & 1) != 0;
	}
}
