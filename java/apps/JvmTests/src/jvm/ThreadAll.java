/*
  RT-THREAD BRING-UP, and then the stack-cache question — status item 133.

  WHY THIS STARTS FROM ZERO. `RtThread.startMission()` is called NOWHERE outside
  the runtime, and the only use of `RtThread` in any app is commented out
  (java/apps/JbeBench/src/jbe/lift/Control.java:36,55). So the RT scheduler --
  `Scheduler.run()`, the whole-stack save/restore, `RtThreadImpl`'s swap -- has
  never been executed by anything in this tree. That is the same shape as the
  `lmul_sw` finding: an implementation nothing selects gets no coverage.

  WHAT ITEM 133 NEEDS FROM IT. The context switch copies the ENTIRE stack:

      Scheduler.java:96   i = Native.getSP();
      Scheduler.java:99   Native.int2extMem(Const.STACK_OFF, th.stack, i-Const.STACK_OFF+1);
      Scheduler.java:149  Native.ext2intMem(th.stack, Const.STACK_OFF, i-Const.STACK_OFF+1);

  `int2extMem`/`ext2intMem` address internal memory through AR (`star`, 0x01a,
  asm/src/jvm.asm around :2230 and :2269), and AR reaches no part of the rotation
  controller -- exactly the mechanism `jvm.DeepThrow` has now confirmed
  empirically for `f_athrow`. So a thread whose SP exceeded the 576-word resident
  window should have its upper frames saved as ZEROS and restored as garbage.

  THE CONTROL COMES FIRST, deliberately. If threading does not work at all here,
  a deep-stack failure says nothing about the stack cache -- the same reason every
  sweep in this item carries a no-stimulus arm, and the reason four confident
  mechanisms died before the controls were added. So:

    PHASE 1 (shallow): two periodic threads, stacks far inside the window, each
      incrementing its own counter. If both advance, threading works and the
      context switch is executing. If this fails, the finding is "the RT
      scheduler does not run", which belongs to its own item, not to 133.

    PHASE 2 (deep): one thread recurses past the window before yielding, then
      checks its locals survived the round trip. Only meaningful if phase 1
      passed.

  Progress is printed BEFORE each phase, because this can wedge: a hang must
  yield a number rather than silence.
*/
package jvm;

import joprt.RtThread;
import com.jopdesign.sys.Native;
import com.jopdesign.sys.GC;
import com.jopdesign.sys.Const;

public class ThreadAll {

	/** Advanced by the shallow threads; volatile so the main thread sees them. */
	static volatile int tickA;
	static volatile int tickB;

	/** Phase 2: set by the deep thread, read by main. */
	static volatile int deepSp;
	static volatile int deepLocalsOk;
	static volatile int deepRan;

	/** Frame shape matched to jvm.DeepRecursion.deepSum — 9.0 slots/frame
	 *  measured, so depth 90 lands near SP 916, past the 639 window. */
	static int descend(int n) {
		int local1 = n;
		int local2 = n * 2;
		if (n <= 0) {
			deepSp = Native.getSP();
			// YIELD FROM THE DEEPEST FRAME. This is the whole point: the save
			// must copy a stack that is partly non-resident, and the restore must
			// put it back.
			RtThread.currentRtThread().waitForNextPeriod();
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

		// ---- PHASE 1: does threading work at all? ----
		System.out.print("phase1 shallow");

		// MEASURE THE HEAP FIRST. My earlier claim that phase 1 died of
		// out-of-memory from TWO threads was wrong -- one thread fails
		// identically, at the same sp=185. But the allocation is genuinely
		// enormous: RtThreadImpl:170 is
		// `new int[Const.STACK_SIZE-Const.STACK_OFF]`, and STACK_SIZE is a
		// HARDCODED 65536 in ConstGenerator:216 (the 16-bit virtual SP range,
		// identical for every config, cache or not), so each thread wants 65,472
		// ints = 262 KB. Whether that fits is a number, not an opinion, so print
		// it rather than reason about it.
		System.out.print(" free=");
		System.out.print(GC.freeMemory());
		System.out.print(" need=");
		System.out.print((Const.STACK_SIZE - Const.STACK_OFF) * 4);

		// AND LOCALISE THE FAULT: construction, or startMission?
		try {
			new RtThread(10, 20000) {
				public void run() {
					for (int i = 0; i < 4; ++i) {
						tickA++;
						waitForNextPeriod();
					}
				}
			};
			System.out.print(" constructed");
		} catch (Throwable t) {
			System.out.print(" CONSTRUCT-THREW");
			System.out.println("");
			System.out.println("ThreadAll INCONCLUSIVE (thread construction failed)");
			return;
		}

		// ONE THREAD, NOT TWO — and the reason is a finding in itself.
		// RtThreadImpl:170 is `stack = new int[Const.STACK_SIZE-Const.STACK_OFF]`,
		// and STACK_SIZE is 65536 (the VIRTUAL SP range, not the physical stack),
		// so EVERY RtThread allocates 65,472 ints = 262 KB for its save area
		// however little stack it uses. Two threads is 524 KB against this sim's
		// 512 KB, which is why the first version of this test died with a
		// no-name uncaught exception before printing a single tick. tickB is left
		// in place but unused so the shape of the original test is still visible.
		try {
			RtThread.startMission();
			System.out.print(" mission-returned");
		} catch (Throwable t) {
			System.out.print(" MISSION-THREW");
			System.out.println("");
			System.out.println("ThreadAll INCONCLUSIVE (startMission failed)");
			return;
		}

		// Bounded wait, not a spin: a hang must yield a number. ~2M iterations
		// is comfortably longer than four 20 ms periods in simulation terms, and
		// the loop exits early once both threads have run.
		int spins = 0;
		for (int o = 0; o < 400 && tickA < 4; ++o) {
			for (int i = 0; i < 20000 && tickA < 4; ++i) { }
			spins = o;
		}

		System.out.print(" tickA=");
		System.out.print(tickA);
		System.out.print(" tickB=");
		System.out.print(tickB);
		System.out.print(" spins=");
		System.out.print(spins);

		if (tickA < 4) {
			System.out.println(" PHASE1 FAILED -- the RT scheduler does not run here.");
			System.out.println("ThreadAll INCONCLUSIVE (threading itself is the finding, not the stack cache)");
			return;
		}
		System.out.println(" phase1 ok");

		// ---- PHASE 2: a deep stack across a context switch ----
		System.out.print("phase2 deep");

		new RtThread(8, 20000) {
			public void run() {
				int r = descend(90);
				deepLocalsOk = (r == 90 * 91 / 2) ? 1 : 0;
			}
		};

		// The new thread needs the mission restarted; if that is not supported
		// the counters simply stay zero and the bounded wait reports it.
		int spins2 = 0;
		for (int o = 0; o < 400 && deepRan == 0; ++o) {
			for (int i = 0; i < 20000 && deepRan == 0; ++i) { }
			spins2 = o;
		}

		System.out.print(" deepRan=");
		System.out.print(deepRan);
		System.out.print(" sp=");
		System.out.print(deepSp);
		System.out.print(" localsOk=");
		System.out.print(deepLocalsOk);
		System.out.print(" spins=");
		System.out.print(spins2);

		if (deepRan == 0) {
			System.out.println(" PHASE2 did not complete");
			System.out.println("ThreadAll INCONCLUSIVE");
		} else if (deepSp <= 639) {
			System.out.println(" PHASE2 never left the window -- depth too small to test residency");
			System.out.println("ThreadAll INCONCLUSIVE");
		} else if (deepLocalsOk == 1) {
			System.out.println(" phase2 ok");
			System.out.println("ThreadAll OK (deep stack survived a context switch)");
		} else {
			System.out.println(" PHASE2 FAILED -- locals did not survive");
			System.out.println("ThreadAll FAIL (context switch lost a non-resident stack)");
		}
	}
}
