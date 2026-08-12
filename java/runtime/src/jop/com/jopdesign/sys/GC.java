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
	/**
	 * Array descriptor: `(dim << 24) | elem`, where elem is a primitive type
	 * code 4..11 or the element class's struct address (always >= 16).
	 *
	 * Only meaningful when OFF_TYPE says this handle is an array. 0 means "not
	 * recorded" — hardware objects built by `JVMHelp.makeHWArray` are two-word
	 * fake handles that never had one, so readers must stay permissive on 0
	 * rather than treating it as a type.
	 *
	 * Uses handle word 6, which was free: HANDLE_SIZE is 8 and only 0-5 were
	 * used. Every handle is 8 words regardless, so this costs no memory. It is
	 * deliberately NOT folded into OFF_TYPE, which stays a small code so the
	 * GC's tracing paths are untouched.
	 */
	public static final int OFF_ELEM = 6;

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
	 * One past the last word of the handle area, precomputed.
	 *
	 * push() and pushYoung() screen every candidate root against the handle
	 * area, and both used to spell that bound `mem_start + handle_cnt*HANDLE_SIZE`
	 * — three static reads (statics live in main memory on JOP) plus an `imul`
	 * bytecode. HANDLE_SIZE is a power of two, but javac emits imul rather than
	 * strength-reducing, and imul defaults to Microcode: a ~775-cycle shift-add
	 * loop, per push, on any board without an ICU multiplier. Note line 429
	 * already wrote the same product as `<< 3` for exactly this reason.
	 */
	static int handleEnd;

	/**
	 * Start of the single heap region (after handle area).
	 * Mark-compact uses one contiguous heap instead of two semi-spaces.
	 */
	public static int heapStart;
	/**
	 * Total heap size in words.
	 */
	public static int heapSize;

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
	/**
	 * Whether minorGc() clears the card table when it finishes. NOT final and
	 * not a compile-time constant, deliberately: a test flips it at run time so
	 * one image can measure both settings. False is always safe — cards simply
	 * accumulate and later minor GCs scan more than they need to.
	 */
	public static boolean cardClearEnabled = true;

	/**
	 * Bumped freely by a mutator; read by minorGc() to check that the world it
	 * claims to stop is actually stopped.
	 *
	 * The card for a cross-generation store is provably marked, and the holder
	 * provably lies in a range scanCards() visits, yet the reference is still
	 * lost — so the remaining suspect is the halt. If a mutator advances between
	 * IO_GC_HALT going high and going low, the collector moved objects and
	 * rewrote handles underneath a running core, which explains a lost reference
	 * and a wild-pointer crash equally well.
	 */
	public static int mutatorTick;
	/** Largest mutator advance seen across a stop-the-world. Must stay 0. */
	public static int haltDeltaMax;

	public static int copyPtr;
	/**
	 * Points to the lowest allocated-but-not-yet-compacted object.
	 * New objects are allocated by decrementing allocPtr.
	 * Free space = [copyPtr, allocPtr).
	 */
	public static int allocPtr;

	static int freeList;
	// TODO: useList is only used for a faster handle sweep
	// do we need it?
	static int useList;
	static int grayList;
	/**
	 * Handles whose data lives in the nursery (generational mode only). Split out
	 * of useList so a minor GC sweeps O(young) instead of O(all live) — walking
	 * tenured handles every minor GC made the pause grow with the tenured live
	 * set, so no nursery size could bound it (measured: on SDR only 59% of swept
	 * handles were young after 66 collections). A handle is on exactly one of
	 * freeList / youngList / useList, so OFF_NEXT is reused as the link.
	 * Promotion moves the handle to useList; majorGc splices the two back
	 * together before the classic collector runs.
	 */
	static int youngList;

	// =========================================================================
	// Generational GC (Stage 2) — see docs/gc/stage2-generational-design.md.
	// Nursery/tenure are two DATA regions (handles never move). New data is
	// bump-allocated from the nursery at the top of the heap; minorGc() copies
	// survivors down into tenure and rewrites OFF_PTR. All gated by
	// USE_GENERATIONAL — when false the layout below degenerates to the classic
	// single-pointer mark-compact heap and none of the generational paths run.
	// =========================================================================
	public static int nurseryBase;      // low bound of the nursery data region (word addr)
	public static int nurseryTop;       // high bound (== end of heap)
	public static int nurseryAllocPtr;  // bump pointer, grows DOWN from nurseryTop
	public static int tenureTop;        // top of tenure allocation (== nurseryBase; == heap end when !gen)

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

	/**
	 * Use the hardware zero-fill DMA (BmbMemoryController ZERO state) instead of
	 * a software word-by-word write loop for zeroing the free region. The DMA
	 * stays resident in the memory controller (no per-word JOP pipeline
	 * round-trip) and gives deterministic burst timing — the dominant GC cost.
	 * Requires the ZERO registers (Const.IO_ZERO_START/END); safe to disable on
	 * builds without them.
	 */
	static final boolean USE_HW_ZERO = true;

	/**
	 * Generational GC (Stage 2). When true, object/array data is bump-allocated
	 * from a nursery at the top of the heap and reclaimed by a stop-the-world
	 * minorGc() (bounded by nursery size); the existing mark-compact runs as the
	 * major collector over the tenure region. Requires the HW card table
	 * (Const.IO_CARD_*) for the inter-generational (tenure->nursery) root scan.
	 * When false the heap is the classic single mark-compact region — the proven
	 * fallback for cores built without a card table. See
	 * docs/gc/stage2-generational-design.md.
	 *
	 * Hardware-validated 2026-08-01 on both XC7A100T (DDR3) and EP4CGX150 (SDR):
	 * JVM DoAll 66/66 plus GcStressTest sustaining ~90k churn rounds fault-free.
	 */
	public static final boolean USE_GENERATIONAL = true;
	/**
	 * Whether generational mode is ACTUALLY in use. Compile-time
	 * `USE_GENERATIONAL` is the master switch; this is it AND'd with "the
	 * hardware has a card table", decided once in {@link #init}.
	 *
	 * Generational GC is unsound without the card-marking barrier. With no card
	 * table `JopCore` drives `cardRdData := 0`, so `IO_CARD_SHIFT` reads 0,
	 * every card read returns 0, and `scanCards` finds nothing — the remembered
	 * set is permanently empty, every tenured->nursery reference is invisible to
	 * the minor collector, and those young objects are collected while still
	 * live. Observed on the CYC5000 before its card table was added: 3 survivors
	 * copied instead of 66, `corrupt 23`, `MAJOR FAIL` — while `DoAll` passed
	 * 66/66 on the same bitstream. The mutator cannot see the damage.
	 *
	 * Hardware never reports a shift below `cardMinShift` (2), so 0 is an
	 * unambiguous "absent" sentinel. Falling back to the classic collector is
	 * always safe: it is a full mark-compact over one contiguous heap and needs
	 * no remembered set.
	 */
	static boolean genActive;
	/** Card size in words when generational is active, else 0. Reported at boot. */
	static int genCardWords;
	/** Debug tracing of generational GC events (compile-time folded away when false). */
	static final boolean GEN_TRACE = false;
	static int genCopyCnt;
	static int genPushCnt;

	/**
	 * Stage 3 pause instrumentation. Costs ~6 reads of the microsecond counter
	 * per collection (nothing next to a multi-ms pause), so it stays on by
	 * default; javac folds it away entirely when false. Times are microseconds
	 * from IO_US_CNT, which is clock-rate independent so results compare
	 * directly across boards running at different frequencies.
	 */
	public static final boolean GC_TIMING = true;
	/** Minor GC: number run, last/worst/total pause (us). */
	public static int gcMinorCount, gcMinorLast, gcMinorMax, gcMinorTotal;
	/** Per-phase split of the LAST minor GC (us). */
	public static int gcTRoots, gcTMark, gcTCopy, gcTZero, gcTCards;
	/** gcTRoots split: stack+static scan vs dirty-card scan. The two scale
	 *  differently — the card scan is O(heap), the root scan is O(roots). */
	public static int gcTRootScan, gcTCardScan;
	/** Per-phase split of the WORST minor GC seen (us) — captured when gcMinorMax moves. */
	public static int gcWRoots, gcWMark, gcWCopy, gcWZero, gcWCards;
	public static int gcWRootScan, gcWCardScan;
	/** Words scanned/promoted by the last minor GC (to correlate pause with work). */
	public static int gcMinorNurseryWords, gcMinorPromotedWords;
	/**
	 * useList entries visited / promoted / reclaimed by the last minor GC. The
	 * sweep is O(entries visited), so gcTCopy/gcSweptHandles is the per-handle
	 * cost that actually sets the pause — measured, not inferred from object size.
	 */
	public static int gcSweptHandles, gcCopiedHandles, gcReclaimedHandles;
	/** Swept-handle count of the worst minor GC. */
	public static int gcWSweptHandles;
	/**
	 * Handles the compactor rejected as having an impossible size/extent, plus
	 * the details of the first one. Non-zero means something put a corrupt or
	 * stale handle on useList — see the BADSZ screen in copyAndSweepYoung.
	 */
	public static int gcBadHandleCnt, gcBadHandle, gcBadHandleSize,
			gcBadHandleType, gcBadHandleAlen, gcBadHandlePtr;
	/**
	 * Same for the minor GC's BADSZ screen, which sees it much earlier. Captures
	 * all 8 handle words of the first offender plus which collection saw it, to
	 * identify where such a handle comes from.
	 */
	public static int gcBadYoungCnt, gcBadYoung, gcBadYoungGc, gcBadYoungSize;
	/**
	 * Diagnostic (off by default, javac folds it away): validate handle metadata
	 * at CREATION time. If this fires the handle was born with an impossible
	 * size; if it never fires but BADSZ does, something corrupted the handle
	 * after allocation. This is how the multianewarray defect below was found —
	 * turn it on again if another mis-typed allocation shows up.
	 */
	static final boolean GC_META_CHECK = false;

	/**
	 * Instrument the address sort: pass count, and the cost of the first and
	 * last pass separately.
	 *
	 * Off for production. `static final false` means javac folds every guarded
	 * block away, so a build without it carries no counters, no IO_US_CNT reads
	 * and no dead statics — same as GC_META_CHECK and GEN_TRACE.
	 *
	 * Why first and last rather than every pass: a bottom-up merge sort relinks
	 * every element exactly once per pass, so steps == n * passes and counting
	 * steps would tell us nothing that passes does not — while costing an
	 * increment in the innermost loop. What is NOT known is whether the cost per
	 * element is uniform across passes. Early passes merge short runs in list
	 * order; late passes merge long runs that are already address-ordered. If
	 * locality is the story those should differ, and timing the two ends is
	 * enough to see it. Three IO_US_CNT reads per sort, so the measurement does
	 * not perturb what it measures.
	 */
	public static final boolean GC_SORT_TRACE = false;
	/** Passes the last sort made, and the microseconds its first/last pass took. */
	public static int gcSortPasses, gcSortT1, gcSortTLast;

	/**
	 * Instrument the mark phase: how the time splits between popping a gray
	 * object and pushing its children, and how many of each there were.
	 *
	 * Off for production, same folding as GC_SORT_TRACE.
	 *
	 * Timing wraps the whole child-push section of one object rather than each
	 * push call — one IO_US_CNT pair per marked object instead of per reference,
	 * so on a 570 ms phase the reads cost single-digit milliseconds. Counts are
	 * accumulated in locals and stored once at the end; a static increment would
	 * be a main-memory access per push and would dominate what it is counting,
	 * which is the mistake that made push() expensive in the first place.
	 */
	public static final boolean GC_MARK_TRACE = false;
	/** Gray objects popped, child refs pushed, and us spent pushing children. */
	public static int gcMarkPops, gcMarkPushes, gcMarkTPush;
	public static int gcOddNewCnt, gcOddNew, gcOddNewType, gcOddNewAlen, gcOddNewSize;
	/** 1 = came from newArrayGen, 0 = newObjectGen; plus the size that was requested. */
	public static int gcOddNewIsArray, gcOddNewReq;
	public static int gcBadYoungW0, gcBadYoungW1, gcBadYoungW2, gcBadYoungW3,
			gcBadYoungW4, gcBadYoungW5, gcBadYoungW6, gcBadYoungW7;
	/** Major GC: number run, last/worst pause (us). */
	public static int gcMajorCount, gcMajorLast, gcMajorMax;
	/** Phase split of the last major GC (us): mark, then compact+sweep. */
	public static int gcMajTMark, gcMajTCompact;
	/**
	 * Inside gcMajTCompact: the address sort, then the slide loop, then the
	 * word-copy alone (us). Three separate suspects for the unexplained
	 * ~36 us/handle compact constant — a merge sort making ~log n scattered
	 * passes over the handle list, the per-handle walk, or the object data
	 * movement itself. Only splitting them tells the three apart, and two
	 * hypotheses about this pause have already been wrong.
	 */
	public static int gcMajTSort, gcMajTSlide, gcMajTCopyWords;
	/** Live handles and words the last major GC compacted. */
	public static int gcMajLiveHandles, gcMajLiveWords;

	/**
	 * Largest single hardware zero-fill request, in words. 1<<20 words = 4 MB,
	 * the size the fill DMA is validated at (ZeroBench).
	 */
	static final int ZERO_CHUNK_WORDS = 1 << 20;

	/** Nursery size cap (words). 1<<20 words = 4 MB. */
	static final int NURSERY_MAX_WORDS = 1 << 20;

	// --- Pause bound -------------------------------------------------------
	// The minor pause is dominated by the sweep, which is O(young HANDLES), not
	// O(nursery bytes) — a nursery full of small objects holds far more handles
	// than the same space full of large ones. So sizing the nursery in bytes
	// cannot bound the pause; capping the young-object COUNT can, and does so
	// regardless of object size. Measured costs are in
	// docs/gc/stage3-followups.md.
	/** Worst-case minor pause we are aiming for, microseconds. */
	static final int MINOR_TARGET_US = 20000;
	/**
	 * Sweep cost per young handle, ns, and the fixed per-collection cost, us
	 * (root scan + mark + card clear). Both are the SLOWEST measured board,
	 * rounded up, because these are compile-time constants shared by every
	 * target. Measured with GcPauseTest on 2026-08-04:
	 *
	 *   board            fixed us   ns/handle   swept   worst pause
	 *   EP4CGX150 SDR        3637        1346    6168      11.94 ms
	 *   XC7A100T DDR3        4920        1567    9687      20.11 ms
	 *   A-E115FB DDR2        8795        1711    9687      25.38 ms
	 *
	 * The earlier values (1600 / 4500) were taken from the DDR3 board alone and
	 * happened to hit the 20 ms target there only because the fixed cost was
	 * over budget and the per-handle cost under, cancelling out. On DDR2 both
	 * errors point the same way and the bound broke by 27%.
	 *
	 * The dominant term is the root scan and it does NOT track clock frequency:
	 * the SDR and DDR3 boards are both 100 MHz yet differ 2.1x (2.211 vs
	 * 4.719 ms). It tracks memory latency — 2.2 / 4.7 / 8.5 ms across
	 * SDR / DDR3 / DDR2 — so expect it to grow again on any slower memory.
	 */
	static final int SWEEP_NS_PER_HANDLE = 1750;
	static final int MINOR_FIXED_US = 8800;
	/**
	 * Young objects allowed before a minor GC is forced. Derived, not tuned:
	 * (target - fixed) / per-handle. Set MINOR_TARGET_US to 0 to disable the cap
	 * and go back to collecting only when the nursery fills.
	 *
	 * Now 6400 (was 9687). Only boards with a heap large enough to be cap-bound
	 * pay for this: the EP4CGX150 sweeps ~6168 handles because its ~6 MB heap
	 * makes the NURSERY the binding constraint, so it is unaffected. The two
	 * large-heap boards collect 1.51x more often in exchange for the bound
	 * actually holding — predicted 11.94 / 14.95 / 19.75 ms.
	 */
	static final int MAX_YOUNG_OBJECTS =
			((MINOR_TARGET_US - MINOR_FIXED_US) * 1000) / SWEEP_NS_PER_HANDLE;
	/** Young objects allocated since the last minor GC. */
	static int youngObjects;

	/**
	 * Start of the compacted tenure region. Was implicitly `heapStart`.
	 *
	 * The major GC evacuates rather than slides: live objects are copied to a
	 * destination that does not overlap any live source, so they can be
	 * processed in whatever order `useList` happens to be in and the O(n log n)
	 * address sort disappears. The destination is not always `heapStart`, so the
	 * region's base has to be tracked. Live tenure is `[tenureBase, copyPtr)`.
	 */
	static int tenureBase;

	/** Total size of everything mark() marked, in words. Sized by mark, used by the compact phase. */
	static int markedWords;
	/**
	 * How many objects mark() marked. With markedWords this gives average object
	 * size, which is what any future evacuate-versus-slide policy needs.
	 *
	 * A size threshold was tried here and **reverted** — it made every
	 * large-object case 325 ms worse. See `chooseEvacDest`.
	 */
	static int markedHandles;


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
		// Default matches what `mem_start + handle_cnt*HANDLE_SIZE` evaluated to
		// with handle_cnt still 0, so the USE_SCOPES path (no handle area, no
		// tracing collector) keeps rejecting every candidate root as before.
		handleEnd = mem_start;

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
			handleEnd = mem_start + handleArea;

			heapStart = mem_start + handleArea;
			heapSize = mem_size - heapStart;

			// Compacted tenure data grows upward from tenureBase (copyPtr).
			tenureBase = heapStart;
			copyPtr = heapStart;

			// Decide the collector BEFORE laying out the heap: the two modes
			// carve it differently.
			//
			// One condition must hold, and its failure is silent: a card table
			// must exist. A zero shift means the core was built without one, so
			// the barrier does not exist at all and generational mode would be
			// unsound — hence the check rather than an assumption.
			//
			// There used to be a second condition, `cpuCnt0 <= 1`. The card table
			// was instantiated PER CORE and snooped that core's own BMB port
			// ahead of the arbiter, so a collecting core scanned only its own
			// table: a tenured->nursery pointer written by another core marked
			// THAT core's table, was invisible here, and the young object it
			// protected was collected while still live.
			//
			// That is fixed in RTL. The card table is now ONE cluster-level
			// resource fed from the memory-side bus (JopCluster.scala), so one
			// shared heap has one shared remembered set. Removing the guard is
			// what makes generational GC available on SMP.
			//
			// The failure and the fix are both demonstrated by
			// java/apps/SmpGcTest, which constructs exactly the cross-core
			// old->young reference the old code lost:
			//   per-core table   minors 31 verified 192 errors 192 -> FAIL
			//   shared table     minors 31 verified 192 errors 0   -> OK
			// on timing-MET bitstreams at both 2 and 4 cores. To reproduce the
			// failure, put `&& cpuCnt0 <= 1` back and rebuild the RTL from
			// before 767178b.
			// RE-GUARDED 2026-08-09, for a DIFFERENT reason than before. The card
			// table half is genuinely fixed and verified (2 cores: SmpGcTest
			// SMPGC OK, DoAll 66/66 with GC: generational). But at 4 cores the
			// collector DEADLOCKS: SmpGcTest's publisher cores freeze
			// permanently mid-allocation — per-core heartbeats stop dead while
			// core 0 keeps running and printing — which is a stop-the-world
			// halt/release fault, not a remembered-set fault. Diagnosis is in
			// current-status item 1.
			//
			// Two cores is validated and four is not, so the guard is set at the
			// validated boundary rather than at 1. Raise it as higher core counts
			// are proven, and delete it once the halt/release path is fixed.
			int cardShift0 = Native.rd(Const.IO_CARD_SHIFT);
			int cpuCnt0 = Native.rdMem(Const.IO_CPUCNT);
			// Tightened from `<= 2` to `<= 1` on 2026-08-11. Two cores was set as
			// the validated boundary on the strength of a SmpGcTest run that
			// never overlapped a minor GC with a cross-generation store: the old
			// test churned only in phase 2, after every publisher had finished.
			// With the overlap the same 2-core configuration LOSES REFERENCES on
			// hardware, deterministically — a young object still referenced from
			// a tenured holder is collected. So 2 cores was never validated
			// against the case the card table exists for. Raise it again only
			// with a run that overlaps the two.
			genActive = USE_GENERATIONAL && cardShift0 != 0 && cpuCnt0 <= 1;
			genCardWords = genActive ? (1 << cardShift0) : 0;

			if (genActive) {
				// Carve a nursery off the top of the heap (copyPtr already at
				// heapStart — empty tenure). New data bump-allocates DOWN from
				// nurseryTop; tenure/promotions grow DOWN from tenureTop.
				carveNursery();
			} else {
				// Classic single contiguous heap: new allocations grow downward
				// from the top (allocPtr); free = [copyPtr, allocPtr).
				allocPtr = heapStart + heapSize;
				nurseryTop = allocPtr;
				nurseryBase = allocPtr;                        // empty nursery
				nurseryAllocPtr = allocPtr;
				tenureTop = allocPtr;
			}

			// Initial mark value - use 1, will toggle to 2, back to 1, etc.
			toSpace = 1;

			freeList = 0;
			useList = 0;
			youngList = 0;
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
			// Say which collector is running. Generational silently degrades to
			// something UNSOUND if the card table is missing, so the mode must
			// be visible at boot rather than inferred from a corrupted heap
			// later — "GC: classic" on a board you expected to be generational
			// is the signal that hasCardTable is missing from its preset.
			if (genActive) {
				JVMHelp.wr("GC: generational, ");
				wrIntG(genCardWords);
				JVMHelp.wr("-word cards\n");
			} else if (USE_GENERATIONAL && cardShift0 != 0) {
				// Card table present, so the reason is the core count. Say so:
				// "no card table" here would send the next person hunting a
				// missing hasCardTable in the preset, which is not the problem.
				JVMHelp.wr("GC: classic (SMP - minor GC loses cross-gen refs, see item 1)\n");
			} else if (USE_GENERATIONAL) {
				JVMHelp.wr("GC: classic (no card table - generational disabled)\n");
			} else {
				JVMHelp.wr("GC: classic\n");
			}
		}
		// allocate the monitor
		mutex = new Object();

		OOMError = new OutOfMemoryError();
	}

	public static Object getMutex() {
		return mutex;
	}

	/**
	 * Push onto the gray list, with the loop-invariant statics already in hand.
	 *
	 * `push()` was 78% of the major GC's mark phase at ~640 cycles per
	 * reference, and the cost was not the three `rdMem` that do real work: it
	 * read SIX statics per call (`mem_start`, `handleEnd`, `mutex`, `toSpace`,
	 * and `grayList` read then written), and statics live in main memory on JOP.
	 * Callers in a loop read the invariant three once and pass them down —
	 * `memStart`/`hEnd` are fixed at init, `markVal` for the whole collection.
	 *
	 * `grayList` is deliberately NOT hoisted. `gc()` can run with `concurrentGc`
	 * set, and the write barrier pushes onto `grayList`, so caching the head
	 * across the mark loop needs an argument about what can run during a
	 * collection that has not been made.
	 *
	 * <p>The monitor WAS the sixth static read, and it is now gone for a
	 * different reason: the caller MUST hold it (see {@link #gc()}). Taking it
	 * here made this the largest source of nested acquisition in the collector —
	 * once per root and once per reference field, so a mark phase acquired and
	 * RELEASED the global lock thousands of times. That lock is not reentrant,
	 * so each release handed the heap to whichever core was waiting, in the
	 * middle of a collection.
	 */
	static void pushFast(int ref, int memStart, int hEnd, int markVal) {
		if (ref == 0) return;
		if (ref<memStart || ref>=hEnd) return;
		if ((ref&0x7)!=0) return;

		// Body inlined rather than calling pushInto. Delegating cost 102 ms
		// of a 591 ms mark phase — a JOP method call is ~142 cycles, more
		// than the three main-memory static reads this hoisting removes, so
		// the first version of this change was a 22% regression. One call
		// per push is the budget; there is no room for a second.
		if (Native.rdMem(ref+OFF_PTR)==0) return;
		if (Native.rdMem(ref+OFF_SPACE)==markVal) return;
		if (Native.rdMem(ref+OFF_GREY)==0) {
			Native.wrMem(grayList, ref+OFF_GREY);
			grayList = ref;
		}
	}

	/**
	 * Add object to the gray list/stack from OUTSIDE a collection.
	 *
	 * <p>The snapshot-at-beginning write barrier is the only such caller, and it
	 * runs on a mutator that does not hold the monitor — so this one still takes
	 * it. Collector-internal call sites use pushFast directly.
	 */
	static void push(int ref) {
		synchronized (mutex) {
			pushFast(ref, mem_start, handleEnd, toSpace);
		}
	}

	/**
	 * Scan all thread stacks atomic.
	 *
	 */
	static void getStackRoots() {
		int i, j, cnt;
		// Monitor held by the caller (mark / startCycle) — see gc().
		int memStart = mem_start, hEnd = handleEnd, markVal = toSpace;
		i = Native.getSP();
		for (j = Const.STACK_OFF; j <= i; ++j) {
			pushFast(Native.rdIntMem(j), memStart, hEnd, markVal);
		}
		// Stacks from the other threads
		cnt = RtThreadImpl.getCnt();
		for (i = 0; i < cnt; ++i) {
			if (i != RtThreadImpl.getActive()) {
				int[] mem = RtThreadImpl.getStack(i);
				if (mem != null) {
					int sp = RtThreadImpl.getSP(i) - Const.STACK_OFF;
					for (j = 0; j <= sp; ++j) {
						pushFast(mem[j], memStart, hEnd, markVal);
					}
				}
			}
		}
		// The other cores' stacks. The classic collector has exactly the same
		// blind spot as the generational one — which is why classic also fails
		// above 2 cores.
		scanOtherCoreRoots(false);
	}

	/**
	 * Scan all static fields
	 *
	 */
	private static void getStaticRoots() {
		int addr = Native.rdMem(addrStaticRefs);
		int cnt = Native.rdMem(addrStaticRefs+1);
		int memStart = mem_start, hEnd = handleEnd, markVal = toSpace;
		for (int i=0; i<cnt; ++i) {
			pushFast(Native.rdMem(addr+i), memStart, hEnd, markVal);
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
		// Loop-invariant for the whole phase: mem_start/handleEnd are fixed at
		// init, toSpace for the duration of a collection. Reading them once here
		// instead of inside every push removes three main-memory accesses per
		// traced reference.
		int memStart = mem_start, hEnd = handleEnd, markVal = toSpace;
		int pops = 0, pushes = 0, pushUs = 0;
		// Total size of everything marked, accumulated here so the compact phase
		// can pick an evacuation destination that provably does not overlap any
		// live object. A separate pass would cost a whole extra walk of useList;
		// mark already has the type and mtab in hand, so this is at most one
		// extra read per object.
		int liveWords = 0, liveHandles = 0;
		int grayLimit = handle_cnt, grayN = 0;   // cycle guard, see gcListOverrun
		for (;;) {

			// pop one object from the gray list (monitor held by gcLocked)
			ref = grayList;
			if (ref==GREY_END) {
				break;
			}
			if (++grayN > grayLimit) { gcListOverrun(WALK_GRAY_MAJOR, grayN, ref); grayList = GREY_END; break; }
			grayList = Native.rdMem(ref+OFF_GREY);
			Native.wrMem(0, ref+OFF_GREY);		// mark as not in list

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
			++liveHandles;

			// push all children

			int mt0 = 0;
			if (GC_MARK_TRACE) { ++pops; mt0 = Native.rd(Const.IO_US_CNT); }

			// get pointer to object
			int addr = Native.rdMem(ref);
			int flags = Native.rdMem(ref+OFF_TYPE);
			if (flags==IS_REFARR) {
				// is an array of references
				int size = Native.rdMem(ref+OFF_MTAB_ALEN);
				liveWords += size;                   // 1 word per element
				for (i=0; i<size; ++i) {
					pushFast(Native.rdMem(addr+i), memStart, hEnd, markVal);
				}
				if (GC_MARK_TRACE) pushes += size;
				// However, multianewarray does probably NOT work
			} else if (flags==IS_OBJ){
				// it's a plain object
				// get pointer to method table
				int mtab = Native.rdMem(ref+OFF_MTAB_ALEN);
				// instance size lives just before the method table
				liveWords += Native.rdMem(mtab-Const.CLASS_HEADR);
				// get real flags
				flags = Native.rdMem(mtab+Const.MTAB2GC_INFO);
				for (i=0; flags!=0; ++i) {
					if ((flags&1)!=0) {
						pushFast(Native.rdMem(addr+i), memStart, hEnd, markVal);
						if (GC_MARK_TRACE) ++pushes;
					}
					flags >>>= 1;
				}
			} else if (flags==7 || flags==11) {
				// long/double array: 2 words per element
				liveWords += Native.rdMem(ref+OFF_MTAB_ALEN) << 1;
			} else {
				// other primitive arrays: 1 word per element
				liveWords += Native.rdMem(ref+OFF_MTAB_ALEN);
			}
			if (GC_MARK_TRACE) pushUs += Native.rd(Const.IO_US_CNT) - mt0;
		}
		markedWords = liveWords;
		markedHandles = liveHandles;
		if (GC_MARK_TRACE) {
			gcMarkPops = pops;
			gcMarkPushes = pushes;
			gcMarkTPush = pushUs;
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

		// push all children — pushFast, not push: markChildren runs inside the
		// collection, which already holds the monitor.
		int memStart = mem_start, hEnd = handleEnd, markVal = toSpace;
		int addr = Native.rdMem(ref);
		int flags = Native.rdMem(ref+OFF_TYPE);
		if (flags==IS_REFARR) {
			// is an array of references
			int size = Native.rdMem(ref+OFF_MTAB_ALEN);
			for (i=0; i<size; ++i) {
				pushFast(Native.rdMem(addr+i), memStart, hEnd, markVal);
			}
		} else if (flags==IS_OBJ) {
			// it's a plain object
			flags = Native.rdMem(ref+OFF_MTAB_ALEN);
			flags = Native.rdMem(flags+Const.MTAB2GC_INFO);
			for (i=0; flags!=0; ++i) {
				if ((flags&1)!=0) {
					pushFast(Native.rdMem(addr+i), memStart, hEnd, markVal);
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

		// Monitor held by the caller (tryGcIncrement / finishCycleNow) — see gc().
		while (count < MARK_STEP) {
			ref = grayList;
			if (ref==GREY_END) {
				return true;  // marking complete
			}
			grayList = Native.rdMem(ref+OFF_GREY);
			Native.wrMem(0, ref+OFF_GREY);

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
	 * Bottom-up merge sort on the singly-linked list: O(n log n) time, O(1)
	 * extra space. This was an insertion sort, justified by "dozens to hundreds"
	 * of objects — but a major GC on the 256 MB board sorts tens of thousands
	 * (the 4 MB nursery holds ~33k), and at O(n^2) with two main-memory reads
	 * per inner step against handles that miss the L2 cache, the collector
	 * appeared to hang. Small heaps completed only because they sort ~30x less.
	 *
	 * @param list head of the linked list to sort
	 * @return head of the sorted list
	 */
	static int sortListByAddress(int list) {
		if (list == 0) return 0;

		// Bottom-up merge sort, relinking through OFF_NEXT (no extra storage).
		// Runs of `width` are merged pairwise until one run remains.
		int width = 1;
		int passes = 0;
		for (;;) {
			int pt0 = 0;
			if (GC_SORT_TRACE) {
				++passes;
				pt0 = Native.rd(Const.IO_US_CNT);
			}
			int p = list;
			int head = 0;
			int tail = 0;
			int merges = 0;

			while (p != 0) {
				++merges;
				// Split off the right-hand run: q = p advanced by `width`,
				// psize = how many the left run actually has (may be short).
				int q = p;
				int psize = 0;
				for (int i = 0; i < width && q != 0; ++i) {
					++psize;
					q = Native.rdMem(q + OFF_NEXT);
				}
				int qsize = width;

				// Merge the two runs by ascending OFF_PTR.
				while (psize > 0 || (qsize > 0 && q != 0)) {
					int e;
					if (psize == 0) {
						e = q; q = Native.rdMem(q + OFF_NEXT); --qsize;
					} else if (qsize == 0 || q == 0) {
						e = p; p = Native.rdMem(p + OFF_NEXT); --psize;
					} else if (Native.rdMem(q + OFF_PTR) < Native.rdMem(p + OFF_PTR)) {
						e = q; q = Native.rdMem(q + OFF_NEXT); --qsize;
					} else {
						e = p; p = Native.rdMem(p + OFF_NEXT); --psize;
					}
					if (tail == 0) head = e; else Native.wrMem(e, tail + OFF_NEXT);
					tail = e;
				}
				p = q;
			}

			Native.wrMem(0, tail + OFF_NEXT);
			if (GC_SORT_TRACE) {
				int dt = Native.rd(Const.IO_US_CNT) - pt0;
				if (passes == 1) gcSortT1 = dt;
				gcSortTLast = dt;        // overwritten each pass; the final
				                         // write is the last pass
			}
			if (merges <= 1) {
				if (GC_SORT_TRACE) gcSortPasses = passes;
				return head;             // one run left: sorted
			}
			list = head;
			width <<= 1;
		}
	}

	/**
	 * Sort the useList in place by ascending OFF_PTR.
	 * Convenience wrapper for STW compactAndSweep().
	 */
	static void sortUseListByAddress() {
		useList = sortListByAddress(useList);
	}

	/**
	 * Pick an evacuation destination that cannot overlap any live object, or
	 * return 0 if none exists and the caller must fall back to sort-and-slide.
	 *
	 * On entry to a major GC every live object lies in one of two regions —
	 * `[tenureBase, copyPtr)` (previously compacted) and `[allocPtr, nurseryTop)`
	 * (promotions, plus the nursery once `spliceYoungIntoUse` has run) — with a
	 * large free gap between them. A destination disjoint from both means
	 * objects can be copied in any order, which is what removes the sort.
	 *
	 * Preference is `heapStart`, which reclaims any creep from previous
	 * collections and keeps live data low. Falling back to the gap makes the
	 * region walk upward; the next collection will usually be able to flip it
	 * back down. That ping-pong is a semi-space flip, except it needs only
	 * `liveWords` of headroom rather than half the heap.
	 *
	 * <p><b>A size threshold was tried here and reverted.</b> Evacuation copies
	 * every live object; sliding skips objects already in position but must sort
	 * first. That suggests a crossover at large object sizes, and
	 * `GcObjSizeTest` appeared to confirm one at ~40 words. But it depends
	 * entirely on which regime you are in, and `chooseEvacDest` cannot tell:
	 *
	 * <pre>
	 *   steady state (stable live set, repeated GCs)
	 *       sliding copy ~ 0 — objects are already where they belong
	 *       GcMajorPauseTest: slide copy 10 ms vs evac 86 ms -> crossover real
	 *   churn (live set rebuilt between collections)
	 *       sliding copy == evacuation copy — everything moves either way
	 *       GcObjSizeTest @200 words: slide 1624.4 ms vs evac 1624.1 -> sliding
	 *       is strictly worse, by the whole 325 ms sort
	 * </pre>
	 *
	 * The threshold assumed the steady state and cost 325 ms in every
	 * large-object row of GcObjSizeTest on the XC7A100T. Deciding correctly
	 * needs to know how far objects are from their slide destination, which is
	 * not derivable from live size and handle count. Left always-evacuate;
	 * `markedHandles` is kept for whatever measures this properly.
	 *
	 * @param liveWords total size to place, from mark()
	 * @return destination start, or 0 if neither region has room
	 */
	static int chooseEvacDest(int liveWords) {
		// Below the current tenure region: disjoint from [tenureBase, copyPtr)
		// by construction, and from [allocPtr, nurseryTop) because allocPtr is
		// above copyPtr which is above tenureBase.
		if (heapStart + liveWords <= tenureBase) {
			return heapStart;
		}
		// Into the free gap: above every previously compacted object, and below
		// every promotion provided it fits under allocPtr.
		if (copyPtr + liveWords <= allocPtr) {
			return copyPtr;
		}
		return 0;
	}

	/**
	 * Compact phase: evacuate all marked (live) objects to a fresh region.
	 *
	 * Walk the use list in WHATEVER ORDER IT IS IN. For each marked handle:
	 *   - copy object data to the destination bump pointer
	 *   - update the handle's OFF_PTR — one word relocates the object, which is
	 *     the whole benefit of JOP's handle indirection
	 * Unmarked handles are freed.
	 *
	 * This used to slide down to heapStart, which forced the list to be sorted
	 * by address first so that a destination never overwrote a not-yet-copied
	 * source. That sort was 59% of the major GC pause (1085 ms of 1849 at 36k
	 * live objects) and is `n * ceil(log2 n) * ~2 us` — measured on two boards
	 * with different memory systems to within 4%, so no cache or memory
	 * improvement would have touched it. Evacuating to a disjoint region removes
	 * the ordering requirement entirely.
	 *
	 * The minor GC has always worked this way: `copyAndSweepYoung` promotes
	 * survivors to a fresh destination in list order and has never sorted
	 * anything. This makes the two collectors consistent rather than inventing a
	 * mechanism.
	 *
	 * After compaction:
	 *   - copyPtr = end of live data, allocPtr = top of heap
	 *   - all live data is contiguous in [tenureBase, copyPtr)
	 */
	static void compactAndSweep() {

		int ref;

		int st0 = 0;
		if (GC_TIMING) st0 = Native.rd(Const.IO_US_CNT);

		int dest = chooseEvacDest(markedWords);
		int compactPtr;

		// NO `synchronized (mutex)` here — see gcLocked(). The caller already
		// holds the monitor, and re-taking it would RELEASE it on the inner
		// monitorexit: JOP's global lock is not reentrant (jvm.asm monitorexit
		// writes io_cpu_id unconditionally; `lockcnt` only gates interrupt
		// re-enable). That let another core into the heap mid-compaction.
		if (dest == 0) {
			// No disjoint region big enough. Fall back to sliding, which
			// needs address order so that dest <= source for every object.
			sortUseListByAddress();
			compactPtr = heapStart;
			tenureBase = heapStart;
		} else {
			compactPtr = dest;
			tenureBase = dest;
		}

		ref = useList;		// get start of the list
		useList = 0;		// new uselist starts empty

		int st1 = 0;
		if (GC_TIMING) {
			st1 = Native.rd(Const.IO_US_CNT);
			gcMajTSort = st1 - st0;
			gcMajTCopyWords = 0;
		}
		int nLiveHandles = 0;
		int copyUs = 0;
		// Hold the list heads in locals across the walk instead of taking the
		// monitor and touching the statics once per handle. compactAndSweep is
		// stop-the-world (the incremental collector uses compactStep instead),
		// so nothing else can be manipulating these lists; the single
		// synchronized region below publishes the result. The per-handle
		// monitorenter/monitorexit was a large part of the major-GC pause.
		int localUse = useList;
		int localFree = freeList;

		int walkLimit = handle_cnt, walkN = 0;   // cycle guard, see gcListOverrun
		while (ref!=0) {
			if (++walkN > walkLimit) { gcListOverrun(WALK_COMPACT, walkN, ref); break; }

			// read next element, as it is destroyed by list operations
			int next = Native.rdMem(ref+OFF_NEXT);

			// a BLACK one (marked)
			if (Native.rdMem(ref+OFF_SPACE)==toSpace) {
				int size = getObjectSize(ref);
				int oldAddr = Native.rdMem(ref+OFF_PTR);

				// A size that cannot fit the heap means a corrupt or stale
				// handle (same condition copyAndSweepYoung screens for). Sliding
				// it would run compactPtr off the end of the heap and destroy
				// everything above it, so drop it instead of compacting it.
				if (size < 0 || size > heapSize || oldAddr < heapStart
						|| oldAddr + size > heapStart + heapSize) {
					if (GC_TIMING) {
						if (gcBadHandleCnt == 0) {
							gcBadHandle = ref;
							gcBadHandleSize = size;
							gcBadHandleType = Native.rdMem(ref+OFF_TYPE);
							gcBadHandleAlen = Native.rdMem(ref+OFF_MTAB_ALEN);
							gcBadHandlePtr = oldAddr;
						}
						++gcBadHandleCnt;
					}
					Native.wrMem(localFree, ref+OFF_NEXT);
					localFree = ref;
					Native.wrMem(0, ref+OFF_PTR);
					ref = next;
					continue;
				}

				// Only move if the new position is different
				if (oldAddr != compactPtr && size > 0) {
					// Copy data to its new position, ascending.
					//
					// When evacuating, source and destination regions are
					// disjoint by construction (see chooseEvacDest), so there is
					// no aliasing at all and the direction does not matter. On
					// the sort-and-slide fallback the old argument still holds:
					// compactPtr <= oldAddr because the list is in ascending
					// address order and compactPtr advances by the sum of sizes
					// of objects below this one, which is <= their address span.
					//
					// Timed separately: this is the one part of the compact
					// phase a hardware block-copy engine could take over (the
					// zero-fill DMA is the precedent), so its share decides
					// whether that is worth building. The two IO_US_CNT reads
					// per moved object cost ~0.2% of the measured phase.
					int ct0 = 0;
					if (GC_TIMING) ct0 = Native.rd(Const.IO_US_CNT);
					for (int i=0; i<size; ++i) {
						Native.wrMem(Native.rdMem(oldAddr+i), compactPtr+i);
					}
					if (GC_TIMING) copyUs += Native.rd(Const.IO_US_CNT) - ct0;
					// Update handle's data pointer
					Native.wrMem(compactPtr, ref+OFF_PTR);
				}

				compactPtr += size;
				if (GC_TIMING) ++nLiveHandles;

				// add to used list
				Native.wrMem(localUse, ref+OFF_NEXT);
				localUse = ref;
			// a WHITE one (unmarked = garbage)
			} else {
				// pointer to former freelist head
				Native.wrMem(localFree, ref+OFF_NEXT);
				localFree = ref;
				// mark handle as free
				Native.wrMem(0, ref+OFF_PTR);
			}
			ref = next;
		}

		if (GC_TIMING) {
			gcMajLiveHandles = nLiveHandles;
			gcMajTCopyWords = copyUs;
			gcMajTSlide = Native.rd(Const.IO_US_CNT) - st1;
		}

		// Update heap pointers (monitor already held by the caller — see above)
		useList = localUse;
		freeList = localFree;
		copyPtr = compactPtr;
		allocPtr = heapStart + heapSize;
	}

	// ================================================================
	// Incremental GC methods
	// ================================================================

	/**
	 * Prepare for incremental compaction.
	 */
	/**
	 * Prepare for incremental compaction.
	 *
	 * <p><b>This is the only remaining caller of `sortListByAddress`, and it is
	 * deliberately frozen.</b> The stop-the-world collector evacuates (see
	 * `chooseEvacDest`) and no longer sorts at all; this path still slides to
	 * `heapStart` and therefore still needs address order. The two have diverged.
	 *
	 * <p>It was not converted because the incremental collector is
	 * <b>unexercised</b> — `concurrentGc` is opt-in through `setConcurrent()`,
	 * nothing in the test suite turns it on, and item 2 records that the one
	 * simulation aimed at this area cannot fail. Evacuation also needs its
	 * destination reserved before the walk starts, which does not obviously
	 * survive being cut into `COMPACT_STEP`-sized pieces with the mutator
	 * allocating in between. Converting it blind would be a change with no way
	 * to tell whether it worked.
	 *
	 * <p>Anyone enabling the incremental collector should expect the major-GC
	 * pause characteristics measured in `major-gc-evacuation.md` <b>not</b> to
	 * apply here: this path still pays `n * ceil(log2 n) * ~2 us` for the sort.
	 */
	static void prepareCompact() {
		// Monitor held by the caller (tryGcIncrement / gc_alloc) — see gc().
		compactList = sortListByAddress(useList);
		useList = 0;
		compactDst = heapStart;
		// tenureBase must follow compactDst or the card scan would look for
		// tenure data in the wrong place after an incremental cycle.
		tenureBase = heapStart;
		newUseList = 0;
	}

	/**
	 * Incremental compact: process up to COMPACT_STEP handles.
	 * @return true when compaction is complete
	 */
	static boolean compactStep() {
		int count = 0;
		int ref;

		// Monitor held by the caller for the whole step — see gc(). Incremental
		// behaviour is unchanged: one call is still one COMPACT_STEP slice, and
		// the monitor is dropped between calls, not within one.
		while (count < COMPACT_STEP) {
			ref = compactList;
			if (ref == 0) {
				return true;
			}
			compactList = Native.rdMem(ref + OFF_NEXT);

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

				Native.wrMem(newUseList, ref + OFF_NEXT);
				newUseList = ref;
			} else {
				Native.wrMem(freeList, ref + OFF_NEXT);
				freeList = ref;
				Native.wrMem(0, ref + OFF_PTR);
			}

			count++;
		}

		return false;
	}

	/**
	 * Finish an incremental GC cycle.
	 */
	static void finishCycle() {
		// Monitor held by the caller — see gc().
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

		zeroMem(copyPtr, allocPtr);

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

		// Called from newObject/newArray AFTER their synchronized block, so the
		// monitor is NOT held here — and startCycle() asserts IO_GC_HALT. A core
		// that halts the others without owning the lock is halted itself the
		// moment another core wins the lock, mid-root-scan. Take the monitor for
		// the increment so the collector is the lock owner (both CmpSync and
		// IHLU exempt the owner from gcHalt) — the invariant gc() documents.
		// One call is still one increment; the monitor is released between them.
		if (gcPhase != PHASE_IDLE || freeSpace < threshold) {
			synchronized (mutex) {
				gcIncrement();
			}
		}
	}

	public static void setConcurrent() {
		concurrentGc = true;
	}
	static void gc_alloc() {
		if (Config.USE_SCOPES) {
			throw OOMError;
		}
		// gcLocked(), not gc(): gc_alloc is only ever reached from inside
		// `synchronized (mutex)` in newObject/newArray, and re-taking the
		// monitor would release it — see gc().
		if (gcPhase != PHASE_IDLE) {
			// Incremental GC in progress -- drain it to completion
			finishCycleNow();
			// If still not enough space, run a full STW cycle
			if (freeList == 0 || (allocPtr - copyPtr) < (heapSize >> 3)) {
				gcLocked();
			}
		} else {
			gcLocked();
		}
	}

	/**
	 * Public entry point: acquire the allocation monitor, then collect.
	 *
	 * <p>THE RULE THIS ENFORCES: the monitor is taken exactly ONCE, at the
	 * outermost GC entry, and IO_GC_HALT is only ever asserted while holding
	 * it. Both halves matter on SMP:
	 *
	 * <ul>
	 * <li>Taking it once, because JOP's global lock is not reentrant. The
	 *     microcode monitorexit (jvm.asm) always writes io_cpu_id; the
	 *     `lockcnt` it keeps only decides when to re-enable interrupts. IHLU
	 *     reference-counts each lock slot and so survives nesting, but CmpSync
	 *     has a single global lock and no counter (Sys.scala clears lockReqReg
	 *     unconditionally on IO_UNLOCK). A nested `synchronized (mutex)` inside
	 *     the collector therefore RELEASED the lock on its inner exit, letting
	 *     another core into the heap in the middle of a compaction.
	 * <li>Holding it while halted, because CmpSync and IHLU both exempt the
	 *     current lock owner from gcHalt. Owning the lock is what keeps the
	 *     collector itself running; a collector that asserts gcHalt without
	 *     owning it gets halted the moment another core wins the lock, and if
	 *     two cores ever reach that state at once they halt each other with no
	 *     way out.
	 * </ul>
	 *
	 * <p>Callers already inside the monitor (allocation, gc_alloc, majorGc)
	 * must call {@link #gcLocked()} directly, NOT this.
	 */
	public static void gc() {
		if (mutex != null) {
			synchronized (mutex) {
				gcLocked();
			}
		} else {
			gcLocked();
		}
	}

	/** Full stop-the-world collection. Caller MUST hold `mutex` — see {@link #gc()}. */
	static void gcLocked() {
		int gt0 = 0;
		if (GC_TIMING) gt0 = Native.rd(Const.IO_US_CNT);
		// Stop-the-world: halt all other cores during GC.
		// This prevents concurrent SDRAM access that could see
		// partially-moved objects during the compaction phase.
		Native.wr(1, Const.IO_GC_HALT);

		// Generational mode keeps nursery handles on youngList, which this
		// collector knows nothing about. Splice them back before doing anything
		// else — gc() is public and reachable straight from System.gc(), not just
		// via majorGc(), and a young handle left off useList would be neither
		// compacted nor reclaimed.
		if (genActive) spliceYoungIntoUse();

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

		int mt0 = 0, mt1 = 0;
		if (GC_TIMING) mt0 = Native.rd(Const.IO_US_CNT);
		mark();
		if (GC_TIMING) mt1 = Native.rd(Const.IO_US_CNT);
		compactAndSweep();
		if (GC_TIMING) {
			gcMajTMark = mt1 - mt0;
			gcMajTCompact = Native.rd(Const.IO_US_CNT) - mt1;
		}

		// Zero the free region for fresh allocations.
		// Replaces zapSemi() from the semi-space collector.
		// Ensures newly allocated objects have zeroed fields
		// (JVM spec: all fields default to 0/null).
		// NOTE: this used to zero the whole free region here ("replaces zapSemi()
		// from the semi-space collector"), so that new objects saw zeroed fields.
		// That is redundant — every allocation path zeroes its own data before
		// handing the object out (allocGen, and both newObject branches plus
		// newArray) — and it made a major GC cost O(heap) rather than O(live).
		// On the 256 MB board it meant zeroing 254 MB per collection, which did
		// not complete: the collector wedged here in both generational and
		// classic mode. Free memory is never scanned, only live objects and
		// roots are, so leaving it dirty is safe.

		// Generational: compaction moved every object and reset allocPtr, so the
		// nursery bounds are now stale — re-establish an empty nursery before
		// anything can allocate again, and drop the cards (every recorded
		// tenure->nursery pointer refers to pre-compaction addresses). Done here
		// rather than only in majorGc() so a direct System.gc() is safe too.
		if (genActive) {
			carveNursery();
			Native.wr(-1, Const.IO_CARD_CLEAR);
		}

		// Invalidate caches after compaction -- object data has moved
		Native.invalidate();

		// Resume other cores
		Native.wr(0, Const.IO_GC_HALT);

		if (GC_TIMING) {
			gcMajorLast = Native.rd(Const.IO_US_CNT) - gt0;
			if (gcMajorLast > gcMajorMax) gcMajorMax = gcMajorLast;
			++gcMajorCount;
			gcMajLiveWords = copyPtr - tenureBase;   // compacted live data
		}
	}

	static int free() {
		return allocPtr-copyPtr;
	}

	/**
	 * Zero the word range [from, to). Uses the hardware zero-fill DMA when
	 * enabled (writing IO_ZERO_END launches it; the next memory access blocks
	 * until the memory controller finishes and returns to IDLE), otherwise a
	 * software word loop.
	 */
	static void zeroMem(int from, int to) {
		if (from >= to) return;
		if (USE_HW_ZERO) {
			// Issue the fill in bounded chunks rather than as one huge request.
			// A whole-heap request (254 MB after a major GC on the 256 MB DDR3
			// board) does not complete correctly, while 4 MB requests are the
			// proven size. Chunking also bounds how long the CPU blocks, which
			// matters for a collector we are trying to make real-time.
			int cur = from;
			while (cur < to) {
				int end = cur + ZERO_CHUNK_WORDS;
				if (end > to || end < cur) end = to;   // clamp, and guard overflow
				Native.wr(cur, Const.IO_ZERO_START);
				Native.wr(end, Const.IO_ZERO_END);     // launch
				// Writing IO_ZERO_END only blocks on the NEXT memory access, and
				// everything between here and the following chunk is I/O, so
				// touch memory to wait for this chunk before launching another.
				Native.rdMem(cur);
				cur = end;
			}
		} else {
			for (int i = from; i < to; ++i) {
				Native.wrMem(0, i);
			}
		}
	}

	// =========================================================================
	// Generational minor GC (Stage 2). Only reached when USE_GENERATIONAL.
	// A young object's data lives at OFF_PTR >= nurseryBase; copying it to tenure
	// rewrites OFF_PTR below nurseryBase, so OFF_PTR alone encodes
	// young / copied / dead(0) — no separate minor mark bit, and the major mark
	// (OFF_SPACE) is untouched. minorGc reuses the gray-list threading (major GC
	// never runs during a stop-the-world minor).
	// See docs/gc/stage2-generational-design.md.
	// =========================================================================

	/** Minimal decimal print for GEN_TRACE. */
	/**
	 * Minimal decimal print for GEN_TRACE — full 32-bit range.
	 *
	 * The previous version started at `v >= 10000` and so emitted only the low
	 * FIVE digits, silently truncating anything larger. On a 1 GB heap that is
	 * every figure it prints: a `[carve ...]` line read as a ~500 KB heap when
	 * the real values were hStart 535,768 / hSize 267,891,496 / nSize 1,048,576.
	 * Everything reconciled once reconstructed, but only after the numbers had
	 * been taken at face value first.
	 *
	 * Allocation-free by design — this runs inside the collector and during
	 * GC.init before the heap is usable, so it cannot use a scratch buffer.
	 */
	// Handle-list walks. A chain of handles cannot be longer than the number of
	// handles that exist, so a walk that exceeds handle_cnt is following a cycle
	// or a corrupt OFF_NEXT. These identify WHICH walk overran.
	static final int WALK_YOUNG_SWEEP = 1;   // copyAndSweepYoung, youngList
	static final int WALK_SPLICE      = 2;   // spliceYoungIntoUse, youngList tail
	static final int WALK_COMPACT     = 3;   // compactAndSweep, useList
	static final int WALK_GRAY_MINOR  = 4;   // minorGc gray drain
	static final int WALK_GRAY_MAJOR  = 5;   // mark() gray drain

	/**
	 * Report a handle-list walk that cannot terminate, instead of spinning in it
	 * forever.
	 *
	 * <p>WHY: the >2-core generational failure now presents as a core that holds
	 * the global lock and never releases it (current-status item 1). Every core
	 * that does not own the lock is halted by CmpSync, so ONE collector stuck in
	 * a `while (ref != 0)` over a corrupt chain freezes the whole cluster, with
	 * no output and nothing to attribute it to — the walks in question are tight
	 * loops of `Native.rdMem`, which is a bytecode, not a call, so there is not
	 * even a stack to look at. Turning that into a printed line is the
	 * difference between a hang and a diagnosis.
	 *
	 * <p>Costs one local increment and compare per iteration on the sweep path;
	 * the limit is hoisted into a local by each caller so it is not a static
	 * read (statics live in main memory on JOP).
	 */
	static void gcListOverrun(int walk, int iters, int ref) {
		JVMHelp.wr("\r\n*** GC LIST OVERRUN walk=");
		wrIntG(walk);
		JVMHelp.wr(" iters=");
		wrIntG(iters);
		JVMHelp.wr(" handles=");
		wrIntG(handle_cnt);
		JVMHelp.wr(" ref=");
		wrIntG(ref);
		JVMHelp.wr(" next=");
		wrIntG(ref == 0 ? 0 : Native.rdMem(ref + OFF_NEXT));
		JVMHelp.wr(" -- cyclic or corrupt list, truncated\r\n");
	}

	static void wrIntG(int v) {
		if (v < 0) {
			JVMHelp.wr('-');
			// -Integer.MIN_VALUE overflows back to itself, so print its
			// magnitude directly instead of negating.
			if (v == -2147483648) { JVMHelp.wr("2147483648"); return; }
			v = -v;
		}
		boolean lead = false;
		for (int div = 1000000000; div > 0; div /= 10) {
			int d = (v / div) % 10;
			if (d != 0 || lead || div == 1) { JVMHelp.wr((char)('0' + d)); lead = true; }
		}
	}

	/** Add a candidate young root to the copy worklist (conservative handle check). */
	static void pushYoung(int ref) {
		if (ref == 0) return;
		if (ref < mem_start || ref >= handleEnd) return;
		if ((ref & 0x7) != 0) return;
		int ptr = Native.rdMem(ref+OFF_PTR);
		if (ptr < nurseryBase) return;            // dead(0), tenured, or already copied
		if (Native.rdMem(ref+OFF_GREY) == 0) {    // not already on the worklist
			Native.wrMem(grayList, ref+OFF_GREY);
			grayList = ref;
			if (GEN_TRACE) genPushCnt++;
		}
	}

	/** Read one root word from another (halted) core. */
	/** Diagnostics: words scanned from other cores, and calls made. */
	public static int otherRootWords;
	public static int otherRootCands;   // words that look like a handle
	public static int lastYoungHandle;  // last young handle actually pushed
	public static int lastScanSp;       // SP read on the most recent scan
	public static int minScanSp = 99999;
	public static int maxScanSp;
	public static int otherRootYoung;   // ...and point into the nursery
	public static int otherRootCalls;

	public static int rootRead(int core, int what, int index) {
		Native.wr((core << 8) | what | (index & 0xff), Const.IO_ROOT_SEL);
		return Native.rd(Const.IO_ROOT_DATA);
	}

	/**
	 * Hand the target core's stack-RAM read port BACK. Mandatory after any
	 * rootRead() on a core that is still RUNNING.
	 *
	 * `rootSel` is a register, and the cluster drives the target's stack-RAM read
	 * address straight from it, with "index != 0" meaning "a debug/GC read is in
	 * progress". Leave it set and every operand fetch on that core returns the
	 * last word scanned instead of the one asked for — local variables silently
	 * read as a constant. That is not hypothetical: a diagnostic in SmpGcTest
	 * scanned 256 words without releasing, and every value the target core
	 * reported afterwards was an artefact of the instrument.
	 *
	 * scanOtherCoreRoots() gets away with a single release at the end only
	 * because the cores it reads are halted for the duration.
	 */
	public static void rootRelease() {
		Native.wr(0, Const.IO_ROOT_SEL);
	}

	/**
	 * Scan every OTHER core's roots.
	 *
	 * A core's stack is private RAM: `Native.rdIntMem` only ever reads the
	 * calling core's, and nothing else in the runtime reached another core's at
	 * all. So a GC here collected objects that were live in a stack slot over
	 * there — proven directly by SmpGcTest's STACKROOT probe, which held a
	 * reference in a local on core 1 across six minor GCs on core 0 and got it
	 * back zeroed.
	 *
	 * Only valid with the other cores HALTED, which is the caller's job: their
	 * stacks must not move while being read. `young` selects the generational
	 * filter (pushYoung) or the classic one (pushFast).
	 *
	 * A and B are read as well as the stack. JOP holds top-of-stack in those two
	 * registers, so a freshly allocated handle sits in `a` before it reaches
	 * RAM; scanning only the stack looks right and still loses it.
	 */
	static void scanOtherCoreRoots(boolean young) {
		otherRootCalls++;
		int me = Native.rdMem(Const.IO_CPU_ID);
		int n = Native.rdMem(Const.IO_CPUCNT);
		int memStart = mem_start, hEnd = handleEnd, markVal = toSpace;
		for (int c = 0; c < n; ++c) {
			if (c == me) continue;
			int sp = rootRead(c, Const.ROOT_WHAT_SP, 0);
			// A wild SP would walk the whole 256-word RAM harmlessly, but cap it
			// anyway: conservative scanning tolerates junk, unbounded loops do not.
			if (sp > STACK_RAM_WORDS) sp = STACK_RAM_WORDS;
			lastScanSp = sp;
			if (sp < minScanSp) minScanSp = sp;
			if (sp > maxScanSp) maxScanSp = sp;
			for (int j = Const.STACK_OFF; j <= sp; ++j) {
				int v = rootRead(c, Const.ROOT_WHAT_STACK, j);
				otherRootWords++;
				if (v != 0 && v >= mem_start && v < handleEnd && (v & 0x7) == 0) {
					otherRootCands++;
					if (Native.rdMem(v + OFF_PTR) >= nurseryBase) {
						otherRootYoung++;
						lastYoungHandle = v;
					}
				}
				if (young) pushYoung(v); else pushFast(v, memStart, hEnd, markVal);
			}
			int a = rootRead(c, Const.ROOT_WHAT_A, 0);
			int b = rootRead(c, Const.ROOT_WHAT_B, 0);
			if (young) { pushYoung(a); pushYoung(b); }
			else { pushFast(a, memStart, hEnd, markVal); pushFast(b, memStart, hEnd, markVal); }
		}
		// RELEASE THE READ PORT. `rootSel` is a register, and the cluster drives
		// the target core's stack-RAM read address straight from it, with
		// "address != 0" meaning "a debug/GC read is in progress". Leaving it set
		// would steal that core's read port FOREVER: every operand fetch on it
		// would return the last word scanned instead of the one asked for. Index
		// 0 is the existing not-reading sentinel, so writing 0 hands the port
		// back.
		Native.wr(0, Const.IO_ROOT_SEL);
	}

	/** Words in a core's stack RAM (ramWidth = 8 → 256). */
	static final int STACK_RAM_WORDS = 256;

	/** Scan all thread stacks + static fields for young roots. */
	static void getYoungRoots() {
		int i, j, cnt;
		i = Native.getSP();
		for (j = Const.STACK_OFF; j <= i; ++j) {
			pushYoung(Native.rdIntMem(j));
		}
		cnt = RtThreadImpl.getCnt();
		for (i = 0; i < cnt; ++i) {
			if (i != RtThreadImpl.getActive()) {
				int[] mem = RtThreadImpl.getStack(i);
				if (mem != null) {
					int sp = RtThreadImpl.getSP(i) - Const.STACK_OFF;
					for (j = 0; j <= sp; ++j) pushYoung(mem[j]);
				}
			}
		}
		int addr = Native.rdMem(addrStaticRefs);
		cnt = Native.rdMem(addrStaticRefs+1);
		for (i = 0; i < cnt; ++i) pushYoung(Native.rdMem(addr+i));
		// The other cores' stacks — invisible to everything above.
		scanOtherCoreRoots(true);
	}

	/**
	 * Scan dirty cards for tenure->nursery pointers (conservative).
	 *
	 * Tenure is TWO used regions with a large free gap between them:
	 *   [tenureBase, copyPtr)   major-GC compacted data, grows up
	 *   [allocPtr,  tenureTop)  promotions, grow down
	 * Nothing is allocated in the gap, so the write barrier can never have
	 * marked a card there, and there would be nothing to trace even if a stale
	 * bit survived — cards are cleared wholesale at the end of every minor GC.
	 *
	 * Scanning the whole span instead was O(heap) and dominated the minor pause
	 * on a large heap: on the 1 GB A-E115FB it walked 4072 card-table words
	 * (7.671 ms, 38% of the pause) over a region that was 99.98% free, to reach
	 * 2 words of real work. Each iteration costs ~141 cycles because it is two
	 * I/O accesses, so this is not something a tighter loop can fix.
	 */
	static void scanCards() {
		scanCardRange(tenureBase, copyPtr);
		scanCardRange(allocPtr, tenureTop);
	}

	/** Scan the dirty cards covering [from, to). */
	static void scanCardRange(int from, int to) {
		if (from >= to) return;
		int cardShift = Native.rd(Const.IO_CARD_SHIFT);
		int baseCard = from >>> cardShift;
		int topCard  = to   >>> cardShift;
		int wStart = baseCard >>> 5;
		int wEnd   = (topCard + 31) >>> 5;        // 32 cards per readable word
		int cardWords = 1 << cardShift;
		for (int w = wStart; w < wEnd; ++w) {
			Native.wr(w, Const.IO_CARD_IDX);
			int bits = Native.rd(Const.IO_CARD_DATA);
			if (bits == 0) continue;
			int card0 = w << 5;
			for (int b = 0; bits != 0; ++b, bits >>>= 1) {
				if ((bits & 1) == 0) continue;
				int card = card0 + b;
				// `> topCard`, not `>=`: when `to` is not card-aligned the last
				// card partially covers the range and must still be scanned.
				// Erring inclusive is safe — pushYoung filters non-handles.
				if (card < baseCard || card > topCard) continue;
				int f = card << cardShift;
				if (f < from) f = from;
				int t = f + cardWords;
				if (t > to) t = to;
				for (int p = f; p < t; ++p) pushYoung(Native.rdMem(p));
			}
		}
	}

	/** Young-survivor mark sentinel (distinct from toSpace 1/2 and 0). Set by the
	 *  mark phase, restored to toSpace when the survivor is copied. */
	static final int YOUNG_SURV = 3;

	/**
	 * Mark a young object live and enqueue its young children. Conservative
	 * roots may be false positives; marking a non-object is harmless (only real
	 * useList handles are copied later). Children are read from the object's
	 * nursery data (src); implausible array lengths are ignored (false positive).
	 */
	static void markYoung(int ref) {
		int src = Native.rdMem(ref+OFF_PTR);
		if (src < nurseryBase) return;                 // tenured / dead / already copied
		if (Native.rdMem(ref+OFF_SPACE) == YOUNG_SURV) return;  // already marked
		Native.wrMem(YOUNG_SURV, ref+OFF_SPACE);
		int type = Native.rdMem(ref+OFF_TYPE);
		if (type == IS_REFARR) {
			int len = Native.rdMem(ref+OFF_MTAB_ALEN);
			if (len > 0 && len <= (nurseryTop - nurseryBase)) {   // sane length only
				for (int i = 0; i < len; ++i) pushYoung(Native.rdMem(src+i));
			}
		} else if (type == IS_OBJ) {
			int gc = Native.rdMem(ref+OFF_MTAB_ALEN);
			gc = Native.rdMem(gc+Const.MTAB2GC_INFO);
			for (int i = 0; gc != 0; ++i, gc >>>= 1) {
				if ((gc & 1) != 0) pushYoung(Native.rdMem(src+i));
			}
		}
	}

	/**
	 * Walk useList (only REAL handles): copy marked young survivors to tenure
	 * (rewrite OFF_PTR, restore OFF_SPACE), and reclaim dead young handles. Using
	 * useList — not the conservative worklist — means getObjectSize is only ever
	 * called on real objects, so false positives can never drive a bogus copy.
	 */
	static void copyAndSweepYoung() {
		int ref = youngList;
		int nCopied = 0, nReclaimed = 0, nSwept = 0;
		// Every entry leaves youngList: survivors move to useList (they are
		// tenured once copied), dead ones go back to freeList. So the list is
		// rebuilt from empty rather than unlinked in place.
		youngList = 0;
		// Hold the two list heads in locals for the duration of the walk. They
		// are static fields, and JOP keeps statics in main memory, so every
		// getstatic/putstatic is a memory access: touching freeList inline cost
		// two of the six accesses the common (dead) path makes per handle.
		// Nothing else can observe them mid-sweep — the collector runs
		// stop-the-world with the other cores halted.
		int localFree = freeList;
		int localUse = useList;
		// Dead handles are already chained together in youngList order, so we do
		// not have to relink each one onto freeList individually — we splice
		// whole RUNS of consecutive dead handles. A run only has to be closed
		// where a survivor interrupts it (survivors get relinked onto useList,
		// so the preceding handle's OFF_NEXT must stop pointing at them). With
		// ~66 survivors in 33k handles that is ~67 writes instead of 33k,
		// removing one of the four memory accesses the dead path made per handle.
		int runStart = 0, runEnd = 0;
		// Cycle guard — see gcListOverrun. Limit hoisted into a local so the
		// per-handle cost is an increment and a compare, not a static read.
		int walkLimit = handle_cnt, walkN = 0;
		while (ref != 0) {
			if (++walkN > walkLimit) { gcListOverrun(WALK_YOUNG_SWEEP, walkN, ref); break; }
			if (GC_TIMING) ++nSwept;
			int next = Native.rdMem(ref+OFF_NEXT);
			// OFF_PTR is only needed to copy a survivor, and survivors are a
			// fraction of a percent here — keep it out of the common dead path,
			// where every avoided main-memory read is ~18% of the per-handle cost.
			if (Native.rdMem(ref+OFF_SPACE) == YOUNG_SURV) {          // survivor -> promote
				// This handle is leaving the dead chain, so terminate the run
				// that precedes it before its OFF_NEXT is repointed.
				if (runStart != 0) {
					Native.wrMem(localFree, runEnd+OFF_NEXT);
					localFree = runStart;
					runStart = 0;
				}
				int ptr = Native.rdMem(ref+OFF_PTR);
				int size = getObjectSize(ref);
				// size == 0 is LEGAL: a class with no instance fields. Such an
				// object occupies no words, so consecutive ones legitimately share
				// a data address — compactAndSweep has always allowed that (it
				// guards the copy with size > 0 and adds 0), which is why the
				// classic collector never flagged one. Rejecting it here left every
				// zero-field object unpromoted and re-linked onto youngList as a
				// permanent zombie holding a stale nursery pointer across
				// zero-and-reuse; one eventually reached the compactor with a
				// garbage method table and a size larger than the whole heap.
				if (size < 0 || size > (nurseryTop - nurseryBase)) {
					// Impossible size for a nursery-allocated object — corrupt/
					// stale handle. Skip (don't drive a runaway copy), but keep it
					// on youngList so it is not lost or double-freed.
					if (GC_TIMING) {
						if (gcBadYoungCnt == 0) {          // capture the first sighting whole
							gcBadYoung = ref;
							gcBadYoungGc = gcMinorCount;
							gcBadYoungSize = size;
							gcBadYoungW0 = Native.rdMem(ref+0);
							gcBadYoungW1 = Native.rdMem(ref+1);
							gcBadYoungW2 = Native.rdMem(ref+2);
							gcBadYoungW3 = Native.rdMem(ref+3);
							gcBadYoungW4 = Native.rdMem(ref+4);
							gcBadYoungW5 = Native.rdMem(ref+5);
							gcBadYoungW6 = Native.rdMem(ref+6);
							gcBadYoungW7 = Native.rdMem(ref+7);
						}
						++gcBadYoungCnt;
					}
					if (GEN_TRACE) { JVMHelp.wr("[BADSZ ref="); wrIntG(ref); JVMHelp.wr(" sz="); wrIntG(size);
						JVMHelp.wr(" ty="); wrIntG(Native.rdMem(ref+OFF_TYPE));
						JVMHelp.wr(" al="); wrIntG(Native.rdMem(ref+OFF_MTAB_ALEN)); JVMHelp.wr("]\n"); }
					Native.wrMem(youngList, ref+OFF_NEXT);
					youngList = ref;
					ref = next;
					continue;
				}
				int dst = allocPtr - size;
				allocPtr = dst;
				for (int i = 0; i < size; ++i) Native.wrMem(Native.rdMem(ptr+i), dst+i);
				Native.wrMem(dst, ref+OFF_PTR);
				Native.wrMem(toSpace, ref+OFF_SPACE);                 // restore major mark
				Native.wrMem(localUse, ref+OFF_NEXT);                 // now tenured
				localUse = ref;
				nCopied++;
			} else {                                                  // dead young -> reclaim
				Native.wrMem(0, ref+OFF_PTR);
				// Extend the current run; its OFF_NEXT already points at the
				// following youngList entry, which is the next dead handle
				// unless a survivor intervenes (handled above).
				if (runStart == 0) runStart = ref;
				runEnd = ref;
				nReclaimed++;
			}
			ref = next;
		}
		if (runStart != 0) {                                          // close the final run
			Native.wrMem(localFree, runEnd+OFF_NEXT);
			localFree = runStart;
		}
		freeList = localFree;
		useList = localUse;
		if (GC_TIMING) { gcSweptHandles = nSwept; gcCopiedHandles = nCopied; gcReclaimedHandles = nReclaimed; }
		if (GEN_TRACE) { JVMHelp.wr("[cs c="); wrIntG(nCopied); JVMHelp.wr(" r="); wrIntG(nReclaimed); JVMHelp.wr("]\n"); }
	}

	/**
	 * Concatenate youngList onto useList and empty it, so the classic collector
	 * sees every live handle. Safe to call when youngList is already empty.
	 */
	static void spliceYoungIntoUse() {
		if (youngList == 0) return;
		int tail = youngList;
		int next = Native.rdMem(tail+OFF_NEXT);
		int walkLimit = handle_cnt, walkN = 0;   // cycle guard, see gcListOverrun
		while (next != 0) {
			if (++walkN > walkLimit) { gcListOverrun(WALK_SPLICE, walkN, tail); break; }
			tail = next; next = Native.rdMem(tail+OFF_NEXT);
		}
		Native.wrMem(useList, tail+OFF_NEXT);
		useList = youngList;
		youngList = 0;
	}

	/** Stop-the-world minor GC: promote live young objects, reclaim the nursery. */
	static void minorGc() {
		// If tenure can't fit the worst case (whole nursery promotes), run a full
		// major GC instead — it compacts everything (incl. the nursery) and
		// re-carves an empty nursery, so there's nothing left to do here.
		int nurseryUsed = nurseryTop - nurseryAllocPtr;
		if (allocPtr - copyPtr < nurseryUsed) {
			majorGc();
			return;
		}
		// STOP THE WORLD. This was missing entirely: every IO_GC_HALT write in
		// this file used to be in startCycle/finishCycleNow/gc — all classic
		// collector paths — so a minor GC moved surviving objects and rewrote
		// their handles with every other core still running. Handle indirection
		// does not save us: a mutator can read OFF_PTR, then read the data
		// after copyAndSweepYoung has moved it, and Native.invalidate() only
		// invalidates the calling core's caches. Asserted AFTER the majorGc
		// fallback above, so that path is left to gcLocked()'s own halt rather
		// than nesting one halt inside another and releasing the world early.
		//
		// Safe to halt here because minorGc is only reached from allocGen,
		// i.e. with `mutex` held — the invariant gc() documents.
		Native.wr(1, Const.IO_GC_HALT);
		// Snapshot the mutator counter INSIDE the halt window.
		int mtAtHalt = mutatorTick;
		if (GEN_TRACE) JVMHelp.wr("[gc");
		int t0 = 0, t1 = 0, t2 = 0, t3 = 0, t4 = 0, t5 = 0, allocBefore = 0;
		if (GC_TIMING) { t0 = Native.rd(Const.IO_US_CNT); allocBefore = allocPtr; }
		// Mark: conservative roots (stack/static) + inter-gen (dirty cards).
		grayList = GREY_END;
		getYoungRoots();
		int t0b = 0;
		if (GC_TIMING) t0b = Native.rd(Const.IO_US_CNT);
		scanCards();
		if (GC_TIMING) t1 = Native.rd(Const.IO_US_CNT);
		// The gray list is bounded by the handle count too: a handle is only
		// pushed when its OFF_GREY is 0 and OFF_GREY is cleared on pop, so a
		// drain longer than that means the grey chain has looped.
		int grayLimit = handle_cnt, grayN = 0;
		while (grayList != GREY_END) {
			if (++grayN > grayLimit) { gcListOverrun(WALK_GRAY_MINOR, grayN, grayList); grayList = GREY_END; break; }
			int ref = grayList;
			grayList = Native.rdMem(ref+OFF_GREY);
			Native.wrMem(0, ref+OFF_GREY);
			markYoung(ref);
		}
		if (GC_TIMING) t2 = Native.rd(Const.IO_US_CNT);
		if (GEN_TRACE) JVMHelp.wr("m]");
		// Copy survivors + reclaim dead, driven by useList (real handles only).
		copyAndSweepYoung();
		if (GC_TIMING) t3 = Native.rd(Const.IO_US_CNT);
		// The nursery is NOT zeroed here. Everything above nurseryAllocPtr is
		// garbage once the survivors are copied out, and allocGen zeroes each
		// object's data before handing it out, so nothing can observe the stale
		// bytes — free memory is never scanned, only live objects and roots.
		// This is the same redundancy that was removed from gc() in 5e0a3a0;
		// it was costing ~5.4 ms of a 73.8 ms pause on DDR3 (~7%).
		if (GC_TIMING) t4 = Native.rd(Const.IO_US_CNT);
		// Gated so ONE binary can test both settings. Whether the SMP loss of a
		// cross-generation reference comes from the barrier never recording the
		// store, or from this clear wiping a mark the scan should have seen, was
		// answered by rebuilding with the line commented out — and that answer
		// is worthless, because a one-line edit moves this layout-sensitive
		// workload enough to change where it dies. A non-final flag cannot be
		// constant-folded, so the test can flip it per round and correlate
		// losses against it with nothing else varying.
		//
		// Leaving cards dirty is always SAFE, only slower: the next minor GC
		// then scans every card ever dirtied, which is conservative.
		if (cardClearEnabled) Native.wr(-1, Const.IO_CARD_CLEAR);  // clear all cards (HW sweep)
		nurseryAllocPtr = nurseryTop;
		youngObjects = 0;
		// Objects moved, so this core's caches are stale, and the other cores
		// must not resume until both the move and the invalidate are done.
		Native.invalidate();
		// Did anything run while the world was supposed to be stopped?
		int mtDelta = mutatorTick - mtAtHalt;
		if (mtDelta > haltDeltaMax) haltDeltaMax = mtDelta;
		Native.wr(0, Const.IO_GC_HALT);       // resume the other cores
		if (GC_TIMING) {
			t5 = Native.rd(Const.IO_US_CNT);
			gcTRoots = t1 - t0;
			gcTRootScan = t0b - t0;
			gcTCardScan = t1 - t0b;
			gcTMark  = t2 - t1;
			gcTCopy  = t3 - t2;
			gcTZero  = t4 - t3;
			gcTCards = t5 - t4;
			gcMinorLast = t5 - t0;
			gcMinorTotal += gcMinorLast;
			if (gcMinorLast > gcMinorMax) {
				gcMinorMax = gcMinorLast;
				gcWRoots = gcTRoots; gcWMark = gcTMark; gcWCopy = gcTCopy;
				gcWZero = gcTZero;   gcWCards = gcTCards;
				gcWRootScan = gcTRootScan; gcWCardScan = gcTCardScan;
				gcWSweptHandles = gcSweptHandles;
			}
			gcMinorNurseryWords = nurseryUsed;
			gcMinorPromotedWords = allocBefore - allocPtr;
			++gcMinorCount;
		}
	}

	/** (Re)establish the nursery at the top of the heap; tenure = [heapStart, tenureTop). */
	static void carveNursery() {
		int top = heapStart + heapSize;
		int nurserySize = heapSize >> 3;                    // ~1/8 of heap
		if (nurserySize > NURSERY_MAX_WORDS) nurserySize = NURSERY_MAX_WORDS;
		if (top - nurserySize <= copyPtr) {                 // not enough room: shrink
			nurserySize = (top - copyPtr) >> 1;
		}
		nurseryTop = top;
		nurseryBase = top - nurserySize;
		nurseryAllocPtr = nurseryTop;
		youngObjects = 0;
		tenureTop = nurseryBase;
		allocPtr = nurseryBase;                             // tenure/promotion alloc top
		Native.wr(heapStart, Const.IO_CARD_TENURE_LO);
		Native.wr(tenureTop, Const.IO_CARD_TENURE_HI);
		if (GEN_TRACE) {
			JVMHelp.wr("[carve hStart="); wrIntG(heapStart);
			JVMHelp.wr(" hSize="); wrIntG(heapSize);
			JVMHelp.wr(" nBase="); wrIntG(nurseryBase);
			JVMHelp.wr(" nSize="); wrIntG(nurserySize);
			JVMHelp.wr(" copy="); wrIntG(copyPtr);
			JVMHelp.wr("]\n");
		}
	}

	/** Full major GC (mark-compact whole heap) then re-carve an empty nursery. */
	static void majorGc() {
		if (GEN_TRACE) JVMHelp.wr("[MAJOR]");
		// gcLocked() splices youngList in, compacts everything, re-carves an
		// empty nursery, clears the cards — and times itself, so a direct
		// System.gc() is measured too, not just collections that arrive here.
		// Locked variant: majorGc is only called from allocGen, which runs
		// inside the allocation monitor.
		gcLocked();
		if (GEN_TRACE) JVMHelp.wr("[MAJOR-DONE]");
	}

	/**
	 * Generational allocation: `size` words of data (nursery, or tenure for
	 * objects larger than the nursery) + a handle. Returns the handle with
	 * OFF_PTR/OFF_SPACE/OFF_GREY set and on the useList; data zeroed. Caller sets
	 * OFF_TYPE and OFF_MTAB_ALEN.
	 */
	static int allocGen(int size) {
		boolean tenure = size > (nurseryTop - nurseryBase);   // bigger than the whole nursery
		// Collect when the nursery is full OR when enough young objects have
		// accumulated to put the sweep over budget — whichever comes first. The
		// second condition is what actually bounds the pause.
		if (!tenure && (nurseryAllocPtr - size < nurseryBase
				|| (MINOR_TARGET_US > 0 && youngObjects >= MAX_YOUNG_OBJECTS))) {
			minorGc();                                        // reclaim nursery (+ dead young handles)
		}
		if (freeList == 0) {                                  // need a handle
			minorGc();
			if (freeList == 0) { majorGc(); if (freeList == 0) throw OOMError; }
		}
		int data;
		if (tenure) {
			if (allocPtr - size < copyPtr) { majorGc(); }
			if (allocPtr - size < copyPtr) throw OOMError;
			allocPtr -= size;
			data = allocPtr;
		} else {
			nurseryAllocPtr -= size;
			data = nurseryAllocPtr;
		}
		for (int i = 0; i < size; ++i) Native.wrMem(0, data + i);
		int ref = freeList;
		freeList = Native.rdMem(ref+OFF_NEXT);
		if (tenure) {                                         // born tenured: minor GC ignores it
			Native.wrMem(useList, ref+OFF_NEXT);
			useList = ref;
		} else {                                              // nursery: only these are swept
			Native.wrMem(youngList, ref+OFF_NEXT);
			youngList = ref;
			++youngObjects;
		}
		Native.wrMem(data, ref);                              // OFF_PTR
		Native.wrMem(toSpace, ref+OFF_SPACE);
		Native.wrMem(0, ref+OFF_GREY);
		// The caller writes the real OFF_TYPE/OFF_MTAB_ALEN after we return, so
		// until then this handle is reachable from youngList with whatever those
		// fields last held. Describe it as an int array of exactly `size` words:
		// getObjectSize then returns the true size for a collection landing in
		// that window, instead of dereferencing a stale method table (which
		// yielded sizes larger than the heap and ran the compactor off the end).
		Native.wrMem(10, ref+OFF_TYPE);                       // T_INT: size == alen
		Native.wrMem(size, ref+OFF_MTAB_ALEN);
		return ref;
	}

	/** Diagnostic: verify a freshly created handle describes a plausible size. */
	static void checkMeta(int ref, int isArray, int reqSize) {
		int size = getObjectSize(ref);
		if (size < 0 || size > (nurseryTop - nurseryBase)) {
			if (gcOddNewCnt == 0) {
				gcOddNew = ref;
				gcOddNewType = Native.rdMem(ref+OFF_TYPE);
				gcOddNewAlen = Native.rdMem(ref+OFF_MTAB_ALEN);
				gcOddNewSize = size;
				gcOddNewIsArray = isArray;
				gcOddNewReq = reqSize;
			}
			++gcOddNewCnt;
		}
	}

	static int newObjectGen(int cons, int size) {
		int ref;
		if (mutex != null) {
			synchronized (mutex) {
				ref = allocGen(size);
				Native.wrMem(IS_OBJ, ref+OFF_TYPE);
				Native.wrMem(cons+Const.CLASS_HEADR, ref+OFF_MTAB_ALEN);
				if (GC_META_CHECK) checkMeta(ref, 0, size);
			}
		} else {
			ref = allocGen(size);
			Native.wrMem(IS_OBJ, ref+OFF_TYPE);
			Native.wrMem(cons+Const.CLASS_HEADR, ref+OFF_MTAB_ALEN);
			if (GC_META_CHECK) checkMeta(ref, 0, size);
		}
		return ref;
	}

	static int newArrayGen(int arrayLength, int type, int size) {
		int ref;
		if (mutex != null) {
			synchronized (mutex) {
				ref = allocGen(size);
				Native.wrMem(type, ref+OFF_TYPE);
				Native.wrMem(arrayLength, ref+OFF_MTAB_ALEN);
				if (GC_META_CHECK) checkMeta(ref, 1, size);
			}
		} else {
			ref = allocGen(size);
			Native.wrMem(type, ref+OFF_TYPE);
			Native.wrMem(arrayLength, ref+OFF_MTAB_ALEN);
			if (GC_META_CHECK) checkMeta(ref, 1, size);
		}
		return ref;
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

		if (USE_GENERATIONAL && genActive) {
			return newObjectGen(cons, size);
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
				// Zero object data (JVM spec: fields default to 0/null)
				for (int i = 0; i < size; i++) {
					Native.wrMem(0, allocPtr + i);
				}
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
			// Zero object data (JVM spec: fields default to 0/null)
			for (int i = 0; i < size; i++) {
				Native.wrMem(0, allocPtr + i);
			}
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

		if (USE_GENERATIONAL && genActive) {
			return newArrayGen(arrayLength, type, size);
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
			// Zero array data (JVM spec: elements default to 0/null)
			for (int i = 0; i < size; i++) {
				Native.wrMem(0, allocPtr + i);
			}
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

	    // In generational mode the live handles are split across two lists, so a
	    // nursery-allocated object is valid but absent from useList.
	    if (genActive && !isValid) {
	      handlePointer = youngList;
	      while(handlePointer != 0)
	      {
	        if(handle == handlePointer)
	        {
	          isValid = true;
	          break;
	        }
	        handlePointer = Native.rdMem(handlePointer+OFF_NEXT);
	      }
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
