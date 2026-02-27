/*
  This file is part of JOP, the Java Optimized Processor
    see <http://www.jopdesign.com/>

  Copyright (C) 2005-2008, Martin Schoeberl (martin@jopdesign.com)

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



/**
 *     Incremental mark-compact garbage collection for JOP.
 *     Replaces the semi-space copying collector to recover ~2x usable heap.
 *
 *     Key insight: JOP uses handle indirection for all object references.
 *     Compaction only needs to update handle OFF_PTR fields, not scan
 *     the entire heap for pointer updates.
 *
 *     Algorithm (incremental phases):
 *       Phase 0: IDLE -- no GC activity
 *       Phase 1: ROOT_SCAN (STW) -- scan stacks + statics, push to gray list
 *       Phase 2: MARK (incremental) -- process N gray objects per increment
 *       Phase 3: COMPACT (incremental) -- slide P objects per increment
 *       Back to IDLE when complete
 *
 *     Mark and compact phases are split into bounded increments,
 *     interleaved with mutator execution, to reduce worst-case pause times.
 *     No read barriers needed.
 *
 *     Also contains scope support (unchanged from original) and a full
 *     STW gc() method for fallback / allocation pressure.
 *
 * @author Martin Schoeberl (martin@jopdesign.com)
 *
 */
public class GC {

	static int mem_start;		// read from memory
	// get a effective heap size with fixed handle count
	// for our RT-GC tests
	static int full_heap_size;

	/**
	 * Length of the header when using scopes.
	 * Can be shorter then the GC supporting handle.
	 */
	private static final int HEADER_SIZE = 6;

	/**
	 * Fields in the handle structure.
	 *
	 * WARNING: Don't change the size as long
	 * as we do conservative stack scanning.
	 */
	static final int HANDLE_SIZE = 8;
	/**
	 * Maximum handle count.  Caps the handle table to avoid O(N) GC sweep
	 * time explosion on large memories (256 MB+).  65536 handles = 512 K
	 * words of handle area; sweep takes ~6 ms at 100 MHz.
	 */
	static final int MAX_HANDLES = 65536;

	/**
	 * The handle contains following data:
	 * 0 pointer to the object in the heap or 0 when the handle is free
	 * 1 pointer to the method table or length of an array
	 * 2 mark word: equals toSpace when marked (black), else unmarked
	 * 3 type info: object, primitve array or ref array
	 * 4 pointer to next handle of same type (used or free)
	 * 5 gray list
	 *
	 * !!! be carefule when changing the handle structure, it's
	 * used in System.arraycopy() and probably in jvm.asm!!!
	 */
	public static final int OFF_PTR = 0;
	public static final int OFF_MTAB_ALEN = 1;
	public static final int OFF_SPACE = 2;
	public static final int OFF_TYPE = 3;

	// Scope level shares the to/from pointer
	public static final int OFF_SCOPE_LEVEL = OFF_SPACE;

	// Offset with memory reference. Can we use this field?
	// Does not work for arrays
	public static final int OFF_MEM = 5;


	// size != array length (think about long/double)

	// use array types 4..11 are standard boolean to long
	// our addition:
	// 1 reference
	// 0 a plain object
	public static final int IS_OBJ = 0;
	public static final int IS_REFARR = 1;

	/**
	 * Free and Use list.
	 */
	static final int OFF_NEXT = 4;
	/**
	 * Threading the gray list. End of list is 'special' value -1.
	 * 0 means not in list.
	 */
	static final int OFF_GREY = 5;
	/**
	 * Special end of list marker -1
	 */
	static final int GREY_END = -1;

	static final int TYPICAL_OBJ_SIZE = 5;
	static int handle_cnt;

	/**
	 * Start of the single heap region (after handle area).
	 * Mark-compact uses one contiguous heap instead of two semi-spaces.
	 */
	static int heapStart;
	/**
	 * Total heap size in words.
	 */
	static int heapSize;

	/**
	 * Current mark value. Toggled each GC cycle.
	 * Objects with OFF_SPACE == toSpace are considered marked (black).
	 * Write barriers in JVM.java compare against this value.
	 *
	 * We use non-zero values (1 and 2) to avoid confusion with
	 * the initial zero state of OFF_SPACE.
	 */
	static int toSpace;

	/**
	 * Points past the end of compacted live data (grows upward from heapStart).
	 * After compaction, all live data is in [heapStart, copyPtr).
	 * New allocations happen at the top, from allocPtr downward.
	 * Free space = [copyPtr, allocPtr).
	 */
	static int copyPtr;
	/**
	 * Points to the lowest allocated-but-not-yet-compacted object.
	 * New objects are allocated by decrementing allocPtr.
	 * Free space = [copyPtr, allocPtr).
	 */
	static int allocPtr;

	static int freeList;
	// TODO: useList is only used for a faster handle sweep
	// do we need it?
	static int useList;
	static int grayList;

	static int addrStaticRefs;

	static Object mutex;

	static boolean concurrentGc;

	static int roots[];

	static OutOfMemoryError OOMError;

	// Memory allocation pointer used before we enter the ImmortalMemory
	static int allocationPointer;

	// =========================================================================
	// Incremental GC state machine
	// =========================================================================

	static final int PHASE_IDLE      = 0;
	static final int PHASE_ROOT_SCAN = 1;
	static final int PHASE_MARK      = 2;
	static final int PHASE_COMPACT   = 3;

	static int gcPhase;

	/** Number of gray objects to process per mark increment. */
	static final int MARK_STEP = 20;

	/** Number of objects to compact per compact increment. */
	static final int COMPACT_STEP = 10;

	// --- Compact phase state ---
	static int compactList;    // sorted snapshot of useList for compaction
	static int compactDst;     // compaction destination pointer
	static int newUseList;     // rebuilt use list during compaction

	static void init(int mem_size, int addr) {
		addrStaticRefs = addr;
		mem_start = Native.rdMem(0);
		// align mem_start to 8 word boundary for the
		// conservative handle check
		mem_start = (mem_start+7)&0xfffffff8;

		if(Config.USE_SCOPES) {
			allocationPointer = mem_start;
			// clean immortal memory
			for (int i=mem_start; i<mem_size; ++i) {
				Native.wrMem(0, i);
			}
			// Create the Scope that represents immortal memory
			RtThreadImpl.initArea = Memory.getImmortal(mem_start, mem_size-1);
		} else {
			full_heap_size = mem_size-mem_start;
			// Use shift for division (JOP IDIV is broken)
			// Mark-compact: no semi-space split, so more heap available
			// handle_cnt = full_heap_size / (HANDLE_SIZE + TYPICAL_OBJ_SIZE)
			// Use /16 approximation (same as before)
			handle_cnt = full_heap_size >> 4;  // /16
			if (handle_cnt > MAX_HANDLES) handle_cnt = MAX_HANDLES;
			int handleArea = handle_cnt << 3;  // handle_cnt * HANDLE_SIZE

			heapStart = mem_start + handleArea;
			heapSize = mem_size - heapStart;

			// Single contiguous heap: [heapStart, heapStart+heapSize)
			// Compacted data grows upward from heapStart (copyPtr)
			// New allocations grow downward from top (allocPtr)
			copyPtr = heapStart;
			allocPtr = heapStart + heapSize;

			// Initial mark value - use 1, will toggle to 2, back to 1, etc.
			toSpace = 1;

			freeList = 0;
			useList = 0;
			grayList = GREY_END;
			// Use incrementing pointer instead of i*HANDLE_SIZE (multiplication broken)
			int ref = mem_start;
			for (int i=0; i<handle_cnt; ++i) {
				// pointer to former freelist head
				Native.wrMem(freeList, ref+OFF_NEXT);
				// mark handle as free
				Native.wrMem(0, ref+OFF_PTR);
				freeList = ref;
				Native.wrMem(0, ref+OFF_GREY);
				Native.wrMem(0, ref+OFF_SPACE);
				ref += HANDLE_SIZE;  // increment by 8 using addition
			}
			concurrentGc = false;
		}
		// allocate the monitor
		mutex = new Object();

		OOMError = new OutOfMemoryError();
	}

	public static Object getMutex() {
		return mutex;
	}

	/**
	 * Add object to the gray list/stack
	 * @param ref
	 */
	static void push(int ref) {

		// Explicit null guard -- prevents hardware NPE when GC's conservative
		// stack scanner passes address 0 to Native.rdMem() during handle checks.
		if (ref == 0) return;

		// Only objects that are referenced by a handle in the
		// handle area are considered for GC.
		// Null pointer and references to static strings are not
		// investigated.
		if (ref<mem_start || ref>=mem_start+handle_cnt*HANDLE_SIZE) {
			return;
		}
		// does the reference point to a handle start?
		// TODO: happens in concurrent
		if ((ref&0x7)!=0) {
//				log("a not aligned handle");
			return;
		}

		synchronized (mutex) {
			// Is this handle on the free list?
			// Is possible when using conservative stack scanning
			if (Native.rdMem(ref+OFF_PTR)==0) {
				// TODO: that happens in concurrent!
//				log("push of a handle with 0 at OFF_PRT!", ref);
				return;
			}

			// Is it already marked (black)?
			if (Native.rdMem(ref+OFF_SPACE)==toSpace) {
//				log("push: already marked");
				return;
			}

			// only objects not already in the gray list
			// are added
			if (Native.rdMem(ref+OFF_GREY)==0) {
				// pointer to former gray list head
				Native.wrMem(grayList, ref+OFF_GREY);
				grayList = ref;
			}
		}
	}

	/**
	 * Scan all thread stacks atomic.
	 *
	 */
	static void getStackRoots() {
		int i, j, cnt;
		synchronized (mutex) {
			i = Native.getSP();
			for (j = Const.STACK_OFF; j <= i; ++j) {
				push(Native.rdIntMem(j));
			}
			// Stacks from the other threads
			cnt = RtThreadImpl.getCnt();
			for (i = 0; i < cnt; ++i) {
				if (i != RtThreadImpl.getActive()) {
					int[] mem = RtThreadImpl.getStack(i);
					if (mem != null) {
						int sp = RtThreadImpl.getSP(i) - Const.STACK_OFF;
						for (j = 0; j <= sp; ++j) {
							push(mem[j]);
						}
					}
				}
			}
		}
	}

	/**
	 * Scan all static fields
	 *
	 */
	private static void getStaticRoots() {
		int addr = Native.rdMem(addrStaticRefs);
		int cnt = Native.rdMem(addrStaticRefs+1);
		for (int i=0; i<cnt; ++i) {
			push(Native.rdMem(addr+i));
		}
	}

	/**
	 * Mark phase: traverse from roots, mark all reachable objects.
	 * Objects are marked by setting OFF_SPACE = toSpace (black).
	 * No copying occurs during this phase.
	 */
	static void mark() {

		int i, ref;

		if (!concurrentGc) {
			getStackRoots();
		}
		getStaticRoots();
		for (;;) {

			// pop one object from the gray list
			synchronized (mutex) {
				ref = grayList;
				if (ref==GREY_END) {
					break;
				}
				grayList = Native.rdMem(ref+OFF_GREY);
				Native.wrMem(0, ref+OFF_GREY);		// mark as not in list
			}

			// already marked
			if (Native.rdMem(ref+OFF_SPACE)==toSpace) {
				continue;
			}

			// there should be no null pointers on the mark stack
			if (Native.rdMem(ref+OFF_PTR)==0) {
				continue;
			}

			// Mark it BLACK
			Native.wrMem(toSpace, ref+OFF_SPACE);

			// push all children

			// get pointer to object
			int addr = Native.rdMem(ref);
			int flags = Native.rdMem(ref+OFF_TYPE);
			if (flags==IS_REFARR) {
				// is an array of references
				int size = Native.rdMem(ref+OFF_MTAB_ALEN);
				for (i=0; i<size; ++i) {
					push(Native.rdMem(addr+i));
				}
				// However, multianewarray does probably NOT work
			} else if (flags==IS_OBJ){
				// it's a plain object
				// get pointer to method table
				flags = Native.rdMem(ref+OFF_MTAB_ALEN);
				// get real flags
				flags = Native.rdMem(flags+Const.MTAB2GC_INFO);
				for (i=0; flags!=0; ++i) {
					if ((flags&1)!=0) {
						push(Native.rdMem(addr+i));
					}
					flags >>>= 1;
				}
			}
		}
	}

	/**
	 * Mark the children of a single gray object.
	 * Extracted from mark() for reuse by markStep().
	 * @param ref handle address of gray object (already popped from gray list)
	 */
	static void markChildren(int ref) {
		int i;

		// already marked
		if (Native.rdMem(ref+OFF_SPACE)==toSpace) {
			return;
		}

		// there should be no null pointers on the mark stack
		if (Native.rdMem(ref+OFF_PTR)==0) {
			return;
		}

		// Mark it BLACK
		Native.wrMem(toSpace, ref+OFF_SPACE);

		// push all children
		int addr = Native.rdMem(ref);
		int flags = Native.rdMem(ref+OFF_TYPE);
		if (flags==IS_REFARR) {
			// is an array of references
			int size = Native.rdMem(ref+OFF_MTAB_ALEN);
			for (i=0; i<size; ++i) {
				push(Native.rdMem(addr+i));
			}
		} else if (flags==IS_OBJ) {
			// it's a plain object
			flags = Native.rdMem(ref+OFF_MTAB_ALEN);
			flags = Native.rdMem(flags+Const.MTAB2GC_INFO);
			for (i=0; flags!=0; ++i) {
				if ((flags&1)!=0) {
					push(Native.rdMem(addr+i));
				}
				flags >>>= 1;
			}
		}
	}

	/**
	 * Incremental mark: process up to MARK_STEP gray objects.
	 * @return true when marking is complete (gray list empty)
	 */
	static boolean markStep() {
		int ref;
		int count = 0;

		while (count < MARK_STEP) {
			synchronized (mutex) {
				ref = grayList;
				if (ref==GREY_END) {
					return true;  // marking complete
				}
				grayList = Native.rdMem(ref+OFF_GREY);
				Native.wrMem(0, ref+OFF_GREY);
			}

			markChildren(ref);
			count++;
		}

		return false;  // more work to do
	}

	/**
	 * Get the size of the object/array data for a handle.
	 * @param ref handle address
	 * @return size in words
	 */
	static int getObjectSize(int ref) {
		int type = Native.rdMem(ref+OFF_TYPE);
		if (type==IS_OBJ) {
			// plain object: size is at offset 0 of class struct
			// OFF_MTAB_ALEN points to method table
			// class struct is at mtab - CLASS_HEADR
			int mtab = Native.rdMem(ref+OFF_MTAB_ALEN);
			return Native.rdMem(mtab-Const.CLASS_HEADR);
		} else if (type==7 || type==11) {
			// long or double array: 2 words per element
			return Native.rdMem(ref+OFF_MTAB_ALEN) << 1;
		} else {
			// other arrays (including reference arrays): 1 word per element
			return Native.rdMem(ref+OFF_MTAB_ALEN);
		}
	}

	/**
	 * Sort a handle linked list by ascending OFF_PTR (object data address).
	 * This is CRITICAL for correct compaction: objects must be processed
	 * in address order so that sliding compaction never overwrites
	 * not-yet-copied data.
	 *
	 * Uses insertion sort on a singly-linked list. O(n^2) worst case,
	 * which is acceptable for JOP's typical object counts (dozens to hundreds).
	 *
	 * @param list head of the linked list to sort
	 * @return head of the sorted list
	 */
	static int sortListByAddress(int list) {
		int sorted = 0;
		int curr = list;

		while (curr != 0) {
			int next = Native.rdMem(curr + OFF_NEXT);
			int currAddr = Native.rdMem(curr + OFF_PTR);

			if (sorted == 0 || currAddr <= Native.rdMem(sorted + OFF_PTR)) {
				Native.wrMem(sorted, curr + OFF_NEXT);
				sorted = curr;
			} else {
				int prev = sorted;
				int scan = Native.rdMem(sorted + OFF_NEXT);
				while (scan != 0 && Native.rdMem(scan + OFF_PTR) < currAddr) {
					prev = scan;
					scan = Native.rdMem(scan + OFF_NEXT);
				}
				Native.wrMem(scan, curr + OFF_NEXT);
				Native.wrMem(curr, prev + OFF_NEXT);
			}
			curr = next;
		}

		return sorted;
	}

	/**
	 * Sort the useList in place by ascending OFF_PTR.
	 * Convenience wrapper for STW compactAndSweep().
	 */
	static void sortUseListByAddress() {
		useList = sortListByAddress(useList);
	}

	/**
	 * Compact phase: slide all marked (live) objects down to eliminate gaps.
	 * Walk the use list IN ADDRESS ORDER. For each marked handle:
	 *   - compute new position at compactPtr (grows from heapStart)
	 *   - copy object data to new position (forward copy, safe since dest < source)
	 *   - update handle's OFF_PTR to new position
	 * Unmarked handles are freed.
	 *
	 * After compaction:
	 *   - copyPtr = end of compacted data (next free word from bottom)
	 *   - allocPtr = top of heap (new allocations grow down from here)
	 *   - All live data is contiguous in [heapStart, copyPtr)
	 */
	static void compactAndSweep() {

		int ref;
		int compactPtr = heapStart;

		synchronized (mutex) {
			// CRITICAL: sort by object address before compaction.
			// Without this, sliding compaction can overwrite objects
			// that haven't been copied yet.
			sortUseListByAddress();

			ref = useList;		// get start of the list
			useList = 0;		// new uselist starts empty
		}

		while (ref!=0) {

			// read next element, as it is destroyed by list operations
			int next = Native.rdMem(ref+OFF_NEXT);

			// a BLACK one (marked)
			if (Native.rdMem(ref+OFF_SPACE)==toSpace) {
				int size = getObjectSize(ref);
				int oldAddr = Native.rdMem(ref+OFF_PTR);

				// Only move if the new position is different
				if (oldAddr != compactPtr && size > 0) {
					// Copy data to compacted position (forward copy).
					// Safe because compactPtr <= oldAddr when sorted
					// by ascending address (proven by induction:
					// compactPtr advances by sum of sizes of objects
					// below this one, which <= their address span).
					for (int i=0; i<size; ++i) {
						Native.wrMem(Native.rdMem(oldAddr+i), compactPtr+i);
					}
					// Update handle's data pointer
					Native.wrMem(compactPtr, ref+OFF_PTR);
				}

				compactPtr += size;

				// add to used list
				synchronized (mutex) {
					Native.wrMem(useList, ref+OFF_NEXT);
					useList = ref;
				}
			// a WHITE one (unmarked = garbage)
			} else {
				synchronized (mutex) {
					// pointer to former freelist head
					Native.wrMem(freeList, ref+OFF_NEXT);
					freeList = ref;
					// mark handle as free
					Native.wrMem(0, ref+OFF_PTR);
				}
			}
			ref = next;
		}

		// Update heap pointers
		synchronized (mutex) {
			copyPtr = compactPtr;
			allocPtr = heapStart + heapSize;
		}
	}

	// ================================================================
	// Incremental GC methods
	// ================================================================

	/**
	 * Prepare for incremental compaction.
	 */
	static void prepareCompact() {
		synchronized (mutex) {
			compactList = sortListByAddress(useList);
			useList = 0;
			compactDst = heapStart;
			newUseList = 0;
		}
	}

	/**
	 * Incremental compact: process up to COMPACT_STEP handles.
	 * @return true when compaction is complete
	 */
	static boolean compactStep() {
		int count = 0;
		int ref;

		while (count < COMPACT_STEP) {
			synchronized (mutex) {
				ref = compactList;
				if (ref == 0) {
					return true;
				}
				compactList = Native.rdMem(ref + OFF_NEXT);
			}

			if (Native.rdMem(ref + OFF_SPACE) == toSpace) {
				int size = getObjectSize(ref);
				int oldAddr = Native.rdMem(ref + OFF_PTR);

				if (oldAddr != compactDst && size > 0) {
					for (int i = 0; i < size; ++i) {
						Native.wrMem(Native.rdMem(oldAddr + i), compactDst + i);
					}
					Native.wrMem(compactDst, ref + OFF_PTR);
				}

				compactDst += size;

				synchronized (mutex) {
					Native.wrMem(newUseList, ref + OFF_NEXT);
					newUseList = ref;
				}
			} else {
				synchronized (mutex) {
					Native.wrMem(freeList, ref + OFF_NEXT);
					freeList = ref;
					Native.wrMem(0, ref + OFF_PTR);
				}
			}

			count++;
		}

		return false;
	}

	/**
	 * Finish an incremental GC cycle.
	 */
	static void finishCycle() {
		synchronized (mutex) {
			if (newUseList == 0) {
				// Nothing compacted
			} else if (useList == 0) {
				useList = newUseList;
			} else {
				int tail = newUseList;
				int tailNext = Native.rdMem(tail + OFF_NEXT);
				while (tailNext != 0) {
					tail = tailNext;
					tailNext = Native.rdMem(tail + OFF_NEXT);
				}
				Native.wrMem(useList, tail + OFF_NEXT);
				useList = newUseList;
			}
			newUseList = 0;
			copyPtr = compactDst;
		}

		for (int i = copyPtr; i < allocPtr; ++i) {
			Native.wrMem(0, i);
		}

		Native.invalidate();
	}

	/**
	 * Start a new incremental GC cycle (STW root scan).
	 */
	static void startCycle() {
		Native.wr(1, Const.IO_GC_HALT);

		grayList = GREY_END;

		if (toSpace == 1) {
			toSpace = 2;
		} else {
			toSpace = 1;
		}

		getStackRoots();
		getStaticRoots();

		Native.wr(0, Const.IO_GC_HALT);

		gcPhase = PHASE_MARK;
	}

	/**
	 * Drain all remaining incremental GC work (STW fallback).
	 */
	static void finishCycleNow() {
		Native.wr(1, Const.IO_GC_HALT);

		if (gcPhase == PHASE_MARK) {
			while (!markStep()) {
			}
			prepareCompact();
			gcPhase = PHASE_COMPACT;
		}

		if (gcPhase == PHASE_COMPACT) {
			while (!compactStep()) {
			}
			finishCycle();
		}

		gcPhase = PHASE_IDLE;

		Native.wr(0, Const.IO_GC_HALT);
	}

	/**
	 * Advance incremental GC state machine by one increment.
	 */
	static void gcIncrement() {
		if (gcPhase == PHASE_IDLE) {
			startCycle();
			return;
		}

		if (gcPhase == PHASE_MARK) {
			if (markStep()) {
				prepareCompact();
				gcPhase = PHASE_COMPACT;
			}
			return;
		}

		if (gcPhase == PHASE_COMPACT) {
			if (compactStep()) {
				finishCycle();
				gcPhase = PHASE_IDLE;
			}
			return;
		}
	}

	/**
	 * Proactively trigger incremental GC work during allocation.
	 */
	static void tryGcIncrement() {
		if (mutex == null) return;

		int freeSpace = allocPtr - copyPtr;
		int threshold = heapSize >> 2;  // 25% of heap

		if (gcPhase != PHASE_IDLE) {
			gcIncrement();
		} else if (freeSpace < threshold) {
			gcIncrement();
		}
	}

	public static void setConcurrent() {
		concurrentGc = true;
	}
	static void gc_alloc() {
		if (Config.USE_SCOPES) {
			throw OOMError;
		}
		if (gcPhase != PHASE_IDLE) {
			// Incremental GC in progress -- drain it to completion
			finishCycleNow();
			// If still not enough space, run a full STW cycle
			if (freeList == 0 || (allocPtr - copyPtr) < (heapSize >> 3)) {
				gc();
			}
		} else {
			gc();
		}
	}

	public static void gc() {
		// Stop-the-world: halt all other cores during GC.
		// This prevents concurrent SDRAM access that could see
		// partially-moved objects during the compaction phase.
		Native.wr(1, Const.IO_GC_HALT);

		// For stop-the-world GC, discard write barrier entries.
		// All live objects are found via roots (stack + static refs).
		// The write barrier gray list may contain non-handle values
		// from hardware object creation during clinit.
		if (!concurrentGc) {
			grayList = GREY_END;
		}

		// Toggle mark value: 1 -> 2 -> 1 -> 2 ...
		// After toggle, all existing objects have the old mark value
		// in OFF_SPACE, so they appear unmarked (white).
		if (toSpace == 1) {
			toSpace = 2;
		} else {
			toSpace = 1;
		}

		mark();
		compactAndSweep();

		// Zero the free region for fresh allocations.
		// Replaces zapSemi() from the semi-space collector.
		// Ensures newly allocated objects have zeroed fields
		// (JVM spec: all fields default to 0/null).
		for (int i = copyPtr; i < allocPtr; ++i) {
			Native.wrMem(0, i);
		}

		// Invalidate caches after compaction -- object data has moved
		Native.invalidate();

		// Resume other cores
		Native.wr(0, Const.IO_GC_HALT);
	}

	static int free() {
		return allocPtr-copyPtr;
	}

	/**
	 * Size of scratchpad memory in 32-bit words
	 * @return
	 */
	public static int getScratchpadSize() {
		return Startup.spm_size;
	}

	/**
	 * Allocate a new Object. Invoked from JVM.f_new(cons);
	 * @param cons pointer to class struct
	 * @return address of the handle
	 */
	public static int newObject(int cons) {
		int size = Native.rdMem(cons);			// instance size

		if (Config.USE_SCOPES) {
			// allocate in scope
			int ptr = allocationPointer;
			if(RtThreadImpl.initArea == null)
			{
				allocationPointer += size+HEADER_SIZE;
			}
			else
			{
				Memory sc = null;
				if (RtThreadImpl.mission) {
					Scheduler s = Scheduler.sched[RtThreadImpl.sys.cpuId];
					sc = s.ref[s.active].currentArea;
				}
				else
				{
					sc = RtThreadImpl.initArea;
				}
				if (sc.allocPtr+size+HEADER_SIZE > sc.endLocalPtr) {
					// OOMError.fillInStackTrace();
					throw OOMError;
				}
				ptr = sc.allocPtr;
				sc.allocPtr += size+HEADER_SIZE;

				//Add scope info to pointer of newly created object
				if (Config.ADD_REF_INFO){
					ptr = ptr | (sc.level << 25);
				}

				//Add scope info to object's handler field
				Native.wrMem(sc.level, ptr+OFF_SCOPE_LEVEL);

				// Add scoped memory area info into objects handle
				// TODO: Choose an appropriate field since we also want scope level info in handle
				Native.wrMem( Native.toInt(sc), ptr+OFF_MEM);
			}
			Native.wrMem(ptr+HEADER_SIZE, ptr+OFF_PTR);
			Native.wrMem(cons+Const.CLASS_HEADR, ptr+OFF_MTAB_ALEN);
			Native.wrMem(0, ptr+OFF_TYPE);
			// TODO: memory initialization is needed
			// either on scope creation+exit or in new
			return ptr;
		}

		// that's the stop-the-world GC
		// Note: mutex is null during first allocation, skip sync in that case
		int ref;

		if (mutex != null) {
			synchronized (mutex) {
				if (copyPtr+size >= allocPtr) {
					gc_alloc();
					if (copyPtr+size >= allocPtr) {
						throw OOMError;
					}
				}
				if (freeList==0) {
					gc_alloc();
					if (freeList==0) {
						throw OOMError;
					}
				}
				// Allocate from the upper part
				allocPtr -= size;
				// get one from free list
				ref = freeList;
				freeList = Native.rdMem(ref+OFF_NEXT);
				// and add it to use list
				Native.wrMem(useList, ref+OFF_NEXT);
				useList = ref;
				// pointer to real object, also marks it as non free
				Native.wrMem(allocPtr, ref); // +OFF_PTR
				// mark it as BLACK - means it is in current toSpace
				Native.wrMem(toSpace, ref+OFF_SPACE);
				Native.wrMem(0, ref+OFF_GREY);
				// ref. flags used for array marker
				Native.wrMem(IS_OBJ, ref+OFF_TYPE);
				// pointer to method table in the handle
				Native.wrMem(cons+Const.CLASS_HEADR, ref+OFF_MTAB_ALEN);
			}
		} else {
			// First allocation (creating mutex), no sync needed
			if (copyPtr+size >= allocPtr) {
				JVMHelp.wr("E1\r\n");
				for(;;);
			}
			if (freeList==0) {
				JVMHelp.wr("E2\r\n");
				for(;;);
			}
			allocPtr -= size;
			ref = freeList;
			freeList = Native.rdMem(ref+OFF_NEXT);
			Native.wrMem(useList, ref+OFF_NEXT);
			useList = ref;
			Native.wrMem(allocPtr, ref);
			Native.wrMem(toSpace, ref+OFF_SPACE);
			Native.wrMem(0, ref+OFF_GREY);
			Native.wrMem(IS_OBJ, ref+OFF_TYPE);
			Native.wrMem(cons+Const.CLASS_HEADR, ref+OFF_MTAB_ALEN);
		}

		tryGcIncrement();
		return ref;
	}

	public static int newArray(int size, int type) {
		if (size < 0) {
			throw new NegativeArraySizeException();
		}

		int arrayLength = size;

		// long or double array
		if((type==11)||(type==7)) size <<= 1;
		// reference array type is 1 (our convention)

		if (Config.USE_SCOPES) {
			// allocate in scope
			int ptr = allocationPointer;
			if(RtThreadImpl.initArea == null)
			{
				allocationPointer += size+HEADER_SIZE;
			}
			else
			{
				Memory sc = null;
				if (RtThreadImpl.mission) {
					Scheduler s = Scheduler.sched[RtThreadImpl.sys.cpuId];
					sc = s.ref[s.active].currentArea;
				}
				else
				{
					sc = RtThreadImpl.initArea;
				}
				if (sc.allocPtr+size+HEADER_SIZE > sc.endLocalPtr) {
					// OOMError.fillInStackTrace();
					throw OOMError;
				}
				ptr = sc.allocPtr;
				sc.allocPtr += size+HEADER_SIZE;

				//Add scope info to pointer of newly created array
				if (Config.ADD_REF_INFO){
					ptr = ptr | (sc.level << 25);
				}

				//Add scope info to array's handler field
				Native.wrMem(sc.level, ptr+OFF_SCOPE_LEVEL);

				// Add scoped memory area info into array handle
				// TODO: Choose an appropriate field since we also want scope level info in handle
				// TODO: Does not work in arrays
				 Native.wrMem( Native.toInt(sc), ptr+OFF_MEM);
			}
			Native.wrMem(ptr+HEADER_SIZE, ptr+OFF_PTR);
			Native.wrMem(arrayLength, ptr+OFF_MTAB_ALEN);
			Native.wrMem(type, ptr+OFF_TYPE); // Array type
			return ptr;
		}

		synchronized (mutex) {
			if (copyPtr+size >= allocPtr) {
				if (Config.USE_SCOPES) {
					throw OOMError;
				} else {
					gc_alloc();
				}
				if (copyPtr+size >= allocPtr) {
					throw OOMError;
				}
			}
		}
		synchronized (mutex) {
			if (freeList==0) {
				if (Config.USE_SCOPES) {
					throw OOMError;
				} else {
					gc_alloc();
					if (freeList==0) {
						throw OOMError;
					}
				}
			}
		}

		int ref;
		synchronized (mutex) {
			// we allocate from the upper part
			allocPtr -= size;
			// get one from free list
			ref = freeList;
	//		if ((ref&0x07)!=0) {
	//			log("getHandle problem");
	//		}
	//		if (Native.rdMem(ref+OFF_PTR)!=0) {
	//			log("getHandle not free");
	//		}
			freeList = Native.rdMem(ref+OFF_NEXT);
			// and add it to use list
			Native.wrMem(useList, ref+OFF_NEXT);
			useList = ref;
			// pointer to real object, also marks it as non free
			Native.wrMem(allocPtr, ref); // +OFF_PTR
			// mark it as BLACK - means it is in current toSpace
			Native.wrMem(toSpace, ref+OFF_SPACE);
			// TODO: should not be necessary - now just for sure
			Native.wrMem(0, ref+OFF_GREY);
			// ref. flags used for array marker
			Native.wrMem(type, ref+OFF_TYPE);
			// array length in the handle
			Native.wrMem(arrayLength, ref+OFF_MTAB_ALEN);
		}
		tryGcIncrement();
		return ref;

	}


	/**
	 * @return
	 */
	public static int freeMemory() {
		return free()*4;
	}

	/**
	 * @return
	 */
	public static int totalMemory() {
		return heapSize*4;
	}

	/**
	 * Check if a given value is a valid handle.
	 *
	 * This method traverse the list of handles (in use) to check
	 * if the handle provided belong to the list.
	 *
	 * It does *not* check the free handle list.
	 *
	 * One detail: the result may state that a handle to a
	 * (still unknown garbage) object is valid, in case
	 * the object is not reachable but still present
	 * on the use list.
	 * This happens in case the object becomes unreachable
	 * during execution, but GC has not reclaimed it yet.
	 * Anyway, it's still a valid object handle.
	 *
	 * @param handle the value to be checked.
	 * @return
	 */
	public static final boolean isValidObjectHandle(int handle)
	{
	  boolean isValid;
	  int handlePointer;

	  // assume it's not valid and try to show otherwise
	  isValid = false;

	  // synchronize on the GC lock
	  synchronized (mutex) {
		// start on the first element of the list
	    handlePointer = useList;

	    // traverse the list until the element is found or the list is over
	    while(handlePointer != 0)
	    {
	      if(handle == handlePointer)
	      {
	    	// found it! hence, it's a valid handle. Stop the search.
	    	isValid = true;
	    	break;
	      }

	      // not found yet. Let's go to the next element and try again.
	      handlePointer = Native.rdMem(handlePointer+OFF_NEXT);
	    }
	  }

	  return isValid;
	}

  /**
   * Write barrier for an object field. May be used with regular objects
   * and reference arrays.
   *
   * @param handle the object handle
   * @param index the field index
   */
  public static final void writeBarrier(int handle, int index)
  {
    boolean shouldExecuteBarrier = false;
    int gcInfo;

//    log("WriteBarrier: snapshot-at-beginning.");

    if (handle == 0)
    {
      throw new NullPointerException();
    }

    synchronized (GC.mutex)
    {
      // ignore objects with size zero (is this correct?)
      if(Native.rdMem(handle) == 0)
      {
//        log("ignore objects with size zero");
        return;
      }

      // get information on the object type.
      int type = Native.rdMem(handle + GC.OFF_TYPE);

      // if it's an object or reference array, execute the barrier
      if(type == GC.IS_REFARR)
      {
//        log("Reference array.");
        shouldExecuteBarrier = true;
      }

      if(type == GC.IS_OBJ)
      {
//        log("Regular object.");
        // get the object GC info from the class structure.
        gcInfo = Native.rdMem(handle + GC.OFF_MTAB_ALEN) + Const.MTAB2GC_INFO;
        gcInfo = Native.rdMem(gcInfo);

//        log("GCInfo field: ", gcInfo);

        // if the correct bit is set for the field, it may hold a reference.
        // then, execute the write barrier.
        if((gcInfo & (0x01 << index)) != 0)
        {
//          log("Field can hold a reference. Execute barrier!");
          shouldExecuteBarrier = true;
        }
      }

      // execute the write barrier, if necessary.
      if(shouldExecuteBarrier)
      {
        // handle indirection
        handle = Native.rdMem(handle);
        // snapshot-at-beginning barrier
        int oldVal = Native.rdMem(handle+index);

//        log("Old val:       ", oldVal);
//        if(oldVal != 0)
//        {
//          log("Current space: ", Native.rdMem(oldVal+GC.OFF_SPACE));
//        }
//        else
//        {
//          log("Current space: NULL object.");
//        }
//        log("toSpace:       ", GC.toSpace);

        if (oldVal!=0 && Native.rdMem(oldVal+GC.OFF_SPACE)!=GC.toSpace) {
//          log("Executing write barrier for old handle: ", handle);
          GC.push(oldVal);
        }
      }
//      else
//      {
//        log("Should not execute the barrier.");
//      }
    }
  }

/************************************************************************************************/


	static void log(String s, int i) {
		JVMHelp.wr(s);
		JVMHelp.wr(" ");
		JVMHelp.wrSmall(i);
		JVMHelp.wr("\n");
	}
	static void log(String s) {
		JVMHelp.wr(s);
		JVMHelp.wr("\n");
	}

	public int newObj2(int ref){
		return newObject(ref);
	}

}
