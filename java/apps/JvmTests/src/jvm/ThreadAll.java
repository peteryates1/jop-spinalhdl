/*
  RT-THREAD BRING-UP, and then the stack-cache question — status items 160, 133.

  WHY THIS STARTS FROM ZERO. `RtThread.startMission()` is called NOWHERE outside
  the runtime, and the only use of `RtThread` in any app is commented out
  (java/apps/JbeBench/src/jbe/lift/Control.java:36,55). So the RT scheduler --
  `Scheduler.run()`, the whole-stack save/restore, `RtThreadImpl`'s swap -- has
  never been executed by anything in this tree. That is the same shape as the
  `lmul_sw` finding: an implementation nothing selects gets no coverage.

  WHAT STOPPED IT (item 160). Every thread gets a save area of
  `Const.STACK_SIZE - Const.STACK_OFF` words (RtThreadImpl), and STACK_SIZE was
  a hardcoded 65536 -- the 16-bit VIRTUAL SP range -- so one thread cost
  261,888 bytes. Measured on the 512 KB BRAM sim: `free=234188 need=261888`,
  and construction threw. STACK_SIZE is now the stack SP can actually reach.

  WHAT ITEM 133 NEEDS FROM IT. The context switch copies the ENTIRE stack:

      Scheduler.java   i = Native.getSP();
      Scheduler.java   Native.int2extMem(Const.STACK_OFF, th.stack, i-Const.STACK_OFF+1);
      Scheduler.java   Native.ext2intMem(th.stack, Const.STACK_OFF, i-Const.STACK_OFF+1);

  `int2extMem`/`ext2intMem` address internal memory through AR (`star`), the
  path item 133 found reading 0 and dropping writes outside the resident window
  -- now served from the spill region. A thread deeper than the window is the
  test of that.

  THE CONTROL COMES FIRST, deliberately. If threading does not work at all here,
  a deep-stack failure says nothing about the stack cache. So:

    PHASE 1 (shallow): a periodic thread increments a counter four times. If it
      does, threading works and the context switch is executing.

    PHASE 2 (deep, stack-cache builds only): a second thread recurses past the
      resident window and waits for its next period FROM THE DEEPEST FRAME, so
      the switch must save and restore a stack the cache only partly holds;
      then it checks its locals on the way back up. A build without a cache has
      a 192-word stack and could not reach the depth at all, so it reports the
      phase as skipped rather than as a pass.

  BOTH THREADS ARE CREATED BEFORE `startMission()`. The first version created
  the deep thread afterwards; JOP's mission model sizes the scheduler's thread
  arrays in startMission (`Scheduler.allocArrays`), so a later thread is never
  scheduled.

  Waits are bounded by the microsecond counter, not by loop counts, and
  progress is printed before each step: a hang must yield a number. Once the
  mission runs, the threads mark their own progress on the same line:
  `a` per tick, and for the deep thread `D` (started), `d` (at the bottom,
  about to switch away), `r` (resumed at depth), `R` (back at the top).
*/
package jvm;

import joprt.RtThread;
import com.jopdesign.sys.Native;
import com.jopdesign.sys.GC;
import com.jopdesign.sys.Const;

public class ThreadAll {

	/** 10 ms: short, because a simulation pays for every microsecond. */
	static final int PERIOD_US = 10000;

	/** Advanced by the shallow thread; volatile so main sees it. */
	static volatile int tickA;

	/** Phase 2: set by the deep thread, read by main. */
	static volatile int deepSp;
	static volatile int deepRan;
	/** 0 = not finished, 1 = locals survived, -1 = they did not. */
	static volatile int deepLocalsOk;

	/** Frame shape matched to jvm.DeepRecursion.deepSum — 9.0 slots/frame
	 *  measured, so depth 90 lands near SP 900, past the 639 window. */
	static final int DEEP = 90;

	static int descend(int n) {
		int local1 = n;
		int local2 = n * 2;
		if (n <= 0) {
			deepSp = Native.getSP();
			System.out.print("d");
			// YIELD FROM THE DEEPEST FRAME. This is the whole point: the save
			// must copy a stack that is partly non-resident, and the restore must
			// put it back.
			RtThread.currentRtThread().waitForNextPeriod();
			System.out.print("r");
			deepRan = 1;
			return 0;
		}
		int r = descend(n - 1);
		if (local1 != n) return -1;
		if (local2 != n * 2) return -1;
		return r + n;
	}

	public static void main(String[] args) {

		System.out.println("ThreadAll start");

		// Phase 2 needs a stack that can hold ~900 words: only a stack cache
		// has one. Decided from the build's own constants, not guessed.
		boolean deep = Const.STACK_CACHE != 0;

		// MEASURE THE HEAP FIRST. Each RtThread, and main's own entry made by
		// startMission, gets a save area of STACK_SIZE - STACK_OFF words.
		int saveBytes = (Const.STACK_SIZE - Const.STACK_OFF) * 4;
		int threads = (deep ? 2 : 1) + 1;
		System.out.print("free=");
		System.out.print(GC.freeMemory());
		System.out.print(" need=");
		System.out.print(saveBytes * threads);
		System.out.print(" (");
		System.out.print(threads);
		System.out.print(" x ");
		System.out.print(saveBytes);
		System.out.println(")");

		try {
			new RtThread(10, PERIOD_US) {
				public void run() {
					for (int i = 0; i < 4; ++i) {
						tickA++;
						System.out.print("a");
						waitForNextPeriod();
					}
				}
			};
			if (deep) {
				new RtThread(9, PERIOD_US) {
					public void run() {
						System.out.print("D");
						int r = descend(DEEP);
						deepLocalsOk = (r == DEEP * (DEEP + 1) / 2) ? 1 : -1;
						System.out.print("R");
					}
				};
			}
			System.out.println("constructed");
		} catch (Throwable t) {
			System.out.println("CONSTRUCT-THREW");
			System.out.println("ThreadAll INCONCLUSIVE (thread construction failed)");
			return;
		}

		System.out.println("startMission");
		try {
			RtThread.startMission();
		} catch (Throwable t) {
			System.out.println("MISSION-THREW");
			System.out.println("ThreadAll INCONCLUSIVE (startMission failed)");
			return;
		}
		System.out.println("mission running");

		// Bounded wait: 500 ms is fifty periods.
		int t0 = Native.rd(Const.IO_US_CNT);
		while (Native.rd(Const.IO_US_CNT) - t0 < 500000) {
			if (tickA >= 4 && (!deep || deepLocalsOk != 0)) break;
		}

		System.out.print("phase1 tickA=");
		System.out.print(tickA);
		boolean p1 = tickA >= 4;
		System.out.println(p1 ? " ok" : " FAILED");

		boolean p2 = true;
		if (!deep) {
			System.out.print("phase2 skipped: no stack cache in this build (STACK_SIZE ");
			System.out.print(Const.STACK_SIZE);
			System.out.println(")");
		} else {
			System.out.print("phase2 deepRan=");
			System.out.print(deepRan);
			System.out.print(" sp=");
			System.out.print(deepSp);
			System.out.print(" localsOk=");
			System.out.print(deepLocalsOk);
			if (deepRan == 0 || deepLocalsOk == 0) {
				System.out.println(" did not complete");
				p2 = false;
			} else if (deepSp <= 639) {
				System.out.println(" never left the window -- proves nothing");
				p2 = false;
			} else if (deepLocalsOk == 1) {
				System.out.println(" ok");
			} else {
				System.out.println(" FAILED -- locals did not survive the switch");
				p2 = false;
			}
		}

		if (!p1) {
			System.out.println("ThreadAll FAIL (the RT scheduler does not run)");
		} else if (!p2) {
			System.out.println("ThreadAll FAIL (a deep stack did not survive a context switch)");
		} else {
			System.out.println("ThreadAll OK");
		}
	}
}
