/*
  This file is part of JOP, the Java Optimized Processor
    see <http://www.jopdesign.com/>

  Copyright (C) 2001-2008, Martin Schoeberl (martin@jopdesign.com)

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package jbe;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.Native;

/**
 * Platform shim for the JavaBenchEmbedded harness.
 *
 * The upstream version calls System.currentTimeMillis(), which this runtime
 * does not implement. Everything else it needs - System.out.print for int and
 * String, and println() - is present, so this is the only file that had to
 * change to bring the suite up. Kept ASCII-only deliberately: the rest of this
 * tree is UTF-8 and one upstream file (lift/LiftControl.java) was Latin-1, so
 * a single javac -encoding cannot cover everything and mixed encodings here
 * are a trap rather than a convenience.
 *
 * TIME COMES FROM THE MICROSECOND COUNTER, IO_US_CNT, the same source GC.java
 * times its pauses with. Two consequences worth knowing before trusting a
 * number from this suite:
 *
 *  - The counter is driven by a prescaler derived from the preset's clkFreq.
 *    If that does not match the real clock, every result here is wrong by the
 *    same ratio while looking perfectly plausible - which is exactly the trap
 *    the generated PLL and the MIG profile check exist to close. A benchmark
 *    is the one place mis-scaling is invisible, so check the boot banner's
 *    clock before quoting figures.
 *  - It is a 32-bit microsecond counter, so it wraps every ~71 minutes. The
 *    harness measures intervals of ~1 s, so a wrap can corrupt at most a
 *    single measurement rather than the run.
 */
public class LowLevel {

	/**
	 * Clock frequency in Hz, or 0 when unknown. Upstream uses this only for
	 * reporting; the timing path does not need it, because IO_US_CNT is
	 * already in microseconds.
	 */
	public static final int FREQ = 0;

	static boolean init;

	/**
	 * Milliseconds since reset.
	 *
	 * Integer division truncates, so a single interval can be up to 1 ms
	 * short. Execute calibrates against a >= 1000 ms target, keeping that
	 * under 0.1 % - but it is the reason not to shorten that target when a run
	 * feels slow.
	 */
	public static int timeMillis() {
		return Native.rd(Const.IO_US_CNT) / 1000;
	}

	/** Microseconds since reset - finer grain for measuring a single phase. */
	public static int timeMicros() {
		return Native.rd(Const.IO_US_CNT);
	}

	public static void msg(String msg) {
		System.out.print(msg);
		System.out.print(" ");
	}

	public static void msg(int val) {
		System.out.print(val);
		System.out.print(" ");
	}

	public static void msg(String msg, int val) {
		msg(msg);
		msg(val);
	}

	public static void lf() {
		System.out.println();
	}
}
