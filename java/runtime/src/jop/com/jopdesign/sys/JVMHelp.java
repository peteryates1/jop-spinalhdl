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

package com.jopdesign.sys;

import com.jopdesign.io.IOFactory;
import com.jopdesign.io.SysDevice;


//
//	I don't like to make JVMHelp public, but I need it in java.io.PrintStream
//
// class JVMHelp {
public class JVMHelp {

	// don't use static initializers <clinit> in this class as some
	// methods depend on the order.
	static Runnable ih[][];
	static Runnable dh;
	
	// Cannot be used here as <clinit> of the factory depends on this
	// class for the helper method. Would result in a cyclic dependency
	// of <clinit>.
//	static SysDevice sys = IOFactory.getFactory().getSysDevice();
	static SysDevice sys;
	
	static StackOverflowError SOError;
	static NullPointerException NPExc;
	static ArrayIndexOutOfBoundsException ABExc;
	static ArithmeticException ArithExc;
	static ClassCastException CCExc;
	static IllegalMonitorStateException IMSExc;
	
	static RetryException RetryExc;

	//
	// DON'T change order of first functions!!!
	//	interrupt gets called from jvm.asm
	//

	/**
	 * Dispatch an interrupt to the handler according to the core
	 * and the interrupt number. Interrupt 0 is the scheduler.
	 */
	static void interrupt() {
		
		// the clean way
		// ih[sys.cpuId][sys.intNr].run();
		// a little bit faster
		ih[Native.rd(Const.IO_CPU_ID)][Native.rd(Const.IO_INTNR)].run();
		// Enable interrupts again - we could have invoked a dummy handler
		Native.wr(1, Const.IO_INT_ENA);
//		wr('!');
//		wr('0'+nr);
	}


	public static void nullPoint() {

		throw NPExc;
	}

	// this is used for arrays of longs
	public static void arrayBound() {

		throw ABExc;
	}

	public static void monitorState() {

		throw IMSExc;
	}
	
	static int saved_sp;
	/**
	 * Invoked on a hardware generated exception.
	 */
	static void except() {
		saved_sp = Native.getSP();
		if (Native.rdMem(Const.IO_EXCPT)==Const.EXC_SPOV) {
			// reset stack pointer
			Native.setSP(Const.STACK_OFF);
		}
		// we have more stack available now for the stack overflow
		handleException();
	}
	
	static void noim() {

		int i;
		wr('n');
		wr('i');
		wr(' ');
		i = Native.getSP();					// sp of noim();
		int sp = Native.rdIntMem(i-4);		// sp of calling function
		int pc = Native.rdIntMem(sp-3)-1;	// one to high
		i = Native.rdIntMem(sp);			// mp
wrSmall(i);
wr(' ');
		int start = Native.rdMem(i)>>>10;
wrSmall(start);
wr(' ');
wrByte(pc);
wr(' ');

		int val = Native.rdMem(start+(pc>>2));
		for (i=(pc&0x03); i<3; ++i) val >>= 8;
		val &= 0xff;
		wrByte(val);

Object o = new Object();
synchronized (o) {

		System.out.println();
		System.out.print("JOP: bytecode ");
		System.out.print(val);
		System.out.println(" not implemented");

		trace(sp);

		for (;;);
}
	}

	static void handleException() {
		
		if (Const.USE_RTTM) {
			// abort transaction to avoid invoking f_athrow() twice
			Native.wrMem(Const.TM_ABORTED, Const.MEM_TM_MAGIC);
		}
		
		int i;
		i = Native.rdMem(Const.IO_EXCPT);
		if (i==Const.EXC_SPOV) {
			throw SOError;
		} else if (i==Const.EXC_NP) {
			throw NPExc;
		} else if (i==Const.EXC_AB) {
			throw ABExc;
		} else if (i==Const.EXC_DIVZ) {
			throw ArithExc;
		} else if (i==Const.EXC_ROLLBACK) {
			throw RetryExc;
		} else if (i==Const.EXC_MON) {
			throw IMSExc;
		}

		for (;;);
	}

	/**
	 * Create interrupt handler and preallocated exceptions 
	 * in a static method instead of <clinit>.
	 * Jikes puts <clinit> as first methods into the table (instead of
	 * interrupt()), javac as last method. 
	 * 
	 * We could use HWO here.
	 */
	static void init() {
		ih = new Runnable[Native.rdMem(Const.IO_CPUCNT)][Const.NUM_INTERRUPTS];
		dh = new DummyHandler();
		for (int core=0; core<Native.rdMem(Const.IO_CPUCNT); ++core) {
			for (int var=0; var<Const.NUM_INTERRUPTS; ++var) {
				JVMHelp.addInterruptHandler(core, var, dh);
			}								
		}	
		
		sys = IOFactory.getFactory().getSysDevice();

		SOError = new StackOverflowError();
		NPExc = new NullPointerException();
		ABExc = new ArrayIndexOutOfBoundsException();
		ArithExc = new ArithmeticException();
		CCExc = new ClassCastException();
		IMSExc = new IllegalMonitorStateException();

		RetryExc = RetryException.instance;

	}


	static void trace(int sp) {

		int fp, mp, vp, pc, addr, loc, args;
		int val;

//		for (int i=0; i<1024; ++i) {
//			wrSmall(i);
//			wrSmall(Native.rdIntMem(i));
//			wr('\n');
//		}
		wr("saved sp=");
		wrSmall(sp);
		wr('\n');

		fp = sp-4;		// first frame point is easy, since last sp points to the end of the frame

		wr("  mp     pc     fp");
		wr('\n');
		

		while (fp>Const.STACK_OFF+5) {
			mp = Native.rdIntMem(fp+4);
			vp = Native.rdIntMem(fp+2);
			pc = Native.rdIntMem(fp+1);
			val = Native.rdMem(mp);
			addr = val>>>10;			// address of callee

			wrSmall(mp);
//			wrSmall(addr);
			wrSmall(pc);
			wrSmall(fp);
			wr('\n');

			val = Native.rdMem(mp+1);	// cp, locals, args
			args = val & 0x1f;
			loc = (val>>>5) & 0x1f;
			fp = vp+args+loc;			// new fp can be calc. with vp and count of local vars
		}
		wr('\n');
	}

	/**
	 * Install a handle in two static fields for a hardware object
	 * @param o a 'real' instance of the HW object for the class reference
	 * @param address IO address of the hardware device
	 * @param idx index of the static fields
	 * @param cp address of constant pool of the factory class
	 * @return reference to the HW object
	 */
	public static Object makeHWObject(Object o, int address, int idx, int cp) {
		int ref = Native.toInt(o);
		int pcl = Native.rdMem(ref+1);
		int p = Native.rdMem(cp-1);
		p = Native.rdMem(p+1);
		p += idx*2;
		Native.wrMem(address, p);
		Native.wrMem(pcl, p+1);
		return Native.toObject(p);
	}

	public static int[] makeHWArray(int len, int address, int idx, int cp) {
		int p = Native.rdMem(cp-1);
		p = Native.rdMem(p+1);
		p += idx*2;
		Native.wrMem(address, p);
		Native.wrMem(len, p+1);
		return Native.toIntArray(p);
	}
	
	/**
	 * Add a Runnable as a first level interrupt handler.
	 * Use the current core.
	 * @param nr interrupt number
	 * @param r Runnable the represents the interrupt handler
	 */
	public static void addInterruptHandler(int nr, Runnable r) {
		addInterruptHandler(Native.rdMem(Const.IO_CPU_ID), nr, r);
	}
	/**
	 * Add a Runnable as first level interrupt handler for an individual core.
	 * @param core
	 * @param nr
	 * @param r
	 */
	public static void addInterruptHandler(int core, int nr, Runnable r) {
		if (nr>=0 && nr<ih[core].length) {
			ih[core][nr] = r;
		}
	}
	/**
	 * Remove the interrupt handler
	 * @param nr interrupt number
	 */
	public static void removeInterruptHandler(int nr) {
		removeInterruptHandler(Native.rdMem(Const.IO_CPU_ID), nr);

	}
	
	/**
	 * Remove the interrupt handler
	 * @param nr interrupt number
	 */
	public static void removeInterruptHandler(int core, int nr) {
		if (nr>=0 && nr<ih[core].length) {
			ih[core][nr] = dh;
		}
	}

	static void wrByte(int i) {

		wr('0'+i/100);
		wr('0'+i/10%10);
		wr('0'+i%10);
		wr(' ');
	}

	static void wrSmall(int i) {

		wr('0'+i/100000%10);
		wr('0'+i/10000%10);
		wr('0'+i/1000%10);
		wr('0'+i/100%10);
		wr('0'+i/10%10);
		wr('0'+i%10);
		wr(' ');
	}


	public static void wr(int c) {
		// busy wait on free tx buffer
		while ((Native.rd(Const.IO_UART_STATUS) & 1) == 0) {
		}
		Native.wr(c, Const.IO_UART_DATA);
	}
	
	public static void wr(String s) {
		int i = s.length();
		for (int j = 0; j < i; ++j) {
			wr(s.charAt(j));
		}
	}

	private static final int MAX_TMP = 32;
	private static int[] tmp;			// a generic buffer

	static void intVal(int val) {

		// tmp is used before clazzinit runs
		if (tmp==null) tmp = new int[MAX_TMP];
		int i;
		if (val<0) {
			wr('-');
			val = -val;
		}
		for (i=0; i<MAX_TMP-1; ++i) {
			tmp[i] = (val%10)+'0';
			val /= 10;
			if (val==0) break;
		}
		for (val=i; val>=0; --val) {
			wr(tmp[val]);
		}
		wr(' ');
	}
	
//	public static void scopeCheck(int ref, int val) {		
//
//		/** val has to be in a longer lived memory region than ref. This means that val can be in: 
//		 * 
//		 *   1. Immortal or Mission memory.
//		 *   2. A scope with a level smaller than the level of ref. (i.e. ref is in a deeper nested
//		 *   	scope than val).
//		 * 
//		 */
//
//		int ref_level; 
//		int val_level; 
//	
//		if (Config.ADD_REF_INFO) {
//			ref_level = (ref & 0x3E000000) >>> 25;
//			val_level = (val & 0x3E000000) >>> 25;
//			
//		} else {
//			ref_level = Native.rdMem(ref + GC.OFF_SCOPE_LEVEL);
//			val_level = Native.rdMem(val + GC.OFF_SCOPE_LEVEL);
//		}
//			
//		if (val_level == 0){
//			if (ref_level != 0) { // ref is in scoped memory
//				GC.log("Illegal Assignment Exception: Shorter lived object references longer lived object!");
//			}
//		} else { //val is in scope
//			if (ref_level != 0){ // ref is in scope
//				if (ref_level > val_level) { // ref is deeper nested than val
//					GC.log("Illegal Assignment Exception: Scope level missmatch");
//				}
//			}
//		}
//	}

	/**
	 * Largest `multianewarray` dimension count accepted.
	 *
	 * The JVM spec allows 255. {@link #multiNew} recurses once per level, and a
	 * runaway nest would overflow JOP's stack part-way through allocating —
	 * leaving a partially built structure behind, which is a far worse failure
	 * than a clean refusal. Real code does not go past 3 or 4.
	 */
	static final int MAX_ARRAY_DIM = 8;

	/**
	 * Build one level of a `multianewarray` nest, recursing into the rest.
	 *
	 * Levels 0..dim-2 are reference arrays; only the innermost carries the
	 * element type. That distinction is the whole defect fixed in `78cc968`,
	 * which typed inner arrays `IS_OBJ` so the collector could neither size them
	 * nor scan their elements — premature collection with no visible fault. Here
	 * it has to hold at every level, not just two.
	 *
	 * `Native.rdMem(arr)` is re-read on every iteration on purpose: allocating
	 * the inner array can trigger a GC, which relocates `arr`'s data. The handle
	 * itself does not move, so holding `arr` across the call is safe, and the
	 * conservative stack scan finds both it and the partially built nest.
	 *
	 * Lives here rather than in JVM.java because that class's method order is
	 * the bytecode dispatch table — see `arrayCastOk`.
	 *
	 * @param level  0 for the outermost dimension
	 * @param dim    total dimensions being allocated
	 * @param sp     stack pointer such that counts are at sp+1 .. sp+dim
	 * @param type   innermost element type code (4..11, or IS_REFARR)
	 */
	static int multiNew(int level, int dim, int sp, int type) {
		int cnt = Native.rdIntMem(sp + 1 + level);
		if (level == dim - 1) {
			// Innermost: this is the level the constant-pool type code describes.
			return JVM.f_newarray(cnt, type);
		}
		// Any level above the innermost holds references, whatever the element
		// type of the nest is. f_anewarray ignores its second argument.
		int arr = JVM.f_anewarray(cnt, 0);
		for (int i = 0; i < cnt; ++i) {
			int inner = multiNew(level + 1, dim, sp, type);
			synchronized (GC.mutex) {
				Native.wrMem(inner, Native.rdMem(arr) + i);
			}
		}
		return arr;
	}

	// NOTE: this lives here and NOT in JVM.java. JOPizer emits JVM's method
	// table as the bytecode dispatch table ("pointer to first non Object method
	// struct of class JVM"), so handlers are indexed by POSITION. Adding a
	// helper anywhere in that class shifts every bytecode after it; the symptom
	// is a bogus "bytecode NNN not implemented" at boot, which is how this was
	// found.
	/**
	 * checkcast/instanceof where either side is an array.
	 *
	 * Arrays have no method table — `OFF_MTAB_ALEN` holds the array LENGTH — so
	 * the superclass walk in `f_checkcast`/`f_instanceof` would compute
	 * `length - CLASS_HEADR` and chase pointers through arbitrary memory until
	 * it happened to hit 0 or the target. That was not a missing feature, it was
	 * an unbounded read of the address space, and `instanceof` did it silently
	 * on a path that also matches catch clauses.
	 *
	 * Exact: a primitive-array source against a primitive-array target, and an
	 * array cast to a class (an array is an instance of `java.lang.Object` only,
	 * and Object is the one class whose `CLASS_SUPER` is 0 — interfaces store a
	 * negative ID).
	 *
	 * Deliberately unsound, pinned by `ArrayCastTest`:
	 * <ul>
	 * <li>The cp code comes from the last two characters of the class name, so
	 *     `[[I` also yields 10. `f_multianewarray` <i>depends</i> on that — it
	 *     must create the inner `int[]` arrays with type 10, and narrowing it to
	 *     single-dimension names types them `IS_REFARR` and fails at boot. So a
	 *     reference-array source against a primitive code has to be accepted in
	 *     case it is a real `int[][]`, and `(int[]) intArrArr` wrongly succeeds.</li>
	 * <li>A reference-array target (`[Ljava/lang/Foo;`) is encoded 0 with no
	 *     element class, and the handle records only `IS_REFARR`, so any
	 *     reference array matches it.</li>
	 * <li>`(Cloneable) arr` and `(Serializable) arr` are rejected, for the same
	 *     reason — the interface ID is not resolvable without the element class.</li>
	 * </ul>
	 *
	 * All three need the element class recorded, which is the same missing
	 * metadata that limits `f_multianewarray` — current-status item 23.
	 *
	 * @param type the source handle's OFF_TYPE
	 * @param cons the constant-pool value for the target type
	 */
	public static boolean arrayCastOk(int type, int cons) {
		// Target is a primitive array (possibly multi-dimensional — the code is
		// the innermost element type either way).
		if (cons >= 4 && cons <= 11) {
			if (type == cons) return true;
			// Could be a legitimate int[][] against a "[[I" whose code is 10.
			return type == GC.IS_REFARR;
		}
		if (type == GC.IS_OBJ) {
			// Plain object against a non-array target: the caller's normal
			// superclass walk handles that, so reaching here is a screen error.
			return false;
		}
		if (cons == 0) {
			// Target could not be encoded: a reference-array type, or a class
			// outside the application. Undecidable — see above.
			return type == GC.IS_REFARR;
		}
		// Target is a real class or an interface. Only java.lang.Object matches.
		return Native.rdMem(cons+Const.CLASS_SUPER) == 0;
	}
}

class DummyHandler implements Runnable {

	public void run() {
		// do nothing
	}
	
}