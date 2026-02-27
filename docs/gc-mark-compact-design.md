# Mark-Compact GC Design for JOP

## 1. Current GC Analysis (Semi-Space Copying Collector)

### 1.1 Memory Layout

The heap is divided into three regions, laid out sequentially in memory:

```
Address 0: [application code + constants]
            ...
mem_start:  [Handle Area     ]   handle_cnt * 8 words
            ...
heapStartA: [Semi-Space A     ]   semi_size words (toSpace initially)
            ...
heapStartB: [Semi-Space B     ]   semi_size words (fromSpace initially)
            ...
mem_size:   (end of memory)
```

`mem_start` is read from memory address 0 (the end of the loaded .jop binary), then
aligned up to an 8-word boundary (required for the conservative handle range check:
`(ref & 0x7) != 0` rejects non-aligned values).

The sizing formula (from the original GC) is:

```
full_heap_size = mem_size - mem_start
handle_cnt     = full_heap_size / (2 * TYPICAL_OBJ_SIZE + HANDLE_SIZE)
               = full_heap_size / (2 * 5 + 8) = full_heap_size / 18
semi_size      = (full_heap_size - handle_cnt * HANDLE_SIZE) / 2
```

Note: The current SpinalHDL GC.java uses shift-based arithmetic to avoid JOP's broken
IDIV: `handle_cnt = full_heap_size >> 4` (divide by 16 instead of 18). This slightly
over-allocates handles relative to heap space.

**Heap utilization problem**: Only one semi-space is usable at any time. With a 64KB
total heap (16K words), each semi-space is roughly 3500 words. The other 3500 words sit
completely idle as fromSpace. This is a 50% waste.

### 1.2 Handle Structure

Each handle is 8 words (HANDLE_SIZE = 8):

| Offset | Name          | Description |
|--------|---------------|-------------|
| 0      | OFF_PTR       | Pointer to actual object data in heap (0 = handle is free) |
| 1      | OFF_MTAB_ALEN | Method table pointer (objects) or array length (arrays) |
| 2      | OFF_SPACE     | Space marker: `toSpace` = BLACK/live, `fromSpace` = WHITE/dead |
| 3      | OFF_TYPE      | Type: IS_OBJ (0), IS_REFARR (1), or array type (4-11) |
| 4      | OFF_NEXT      | Linked list pointer (free list or use list) |
| 5      | OFF_GREY      | Gray list threading (0 = not in list, -1 = GREY_END sentinel) |
| 6-7    | (unused)      | Reserved (handle size must be power of 2 for alignment check) |

**Key insight**: All Java references are handle addresses, never direct object pointers.
Handle indirection means that moving an object requires updating exactly ONE word
(`handle[OFF_PTR]`) -- no need to scan the entire heap for pointer fixups. This is what
makes mark-compact uniquely cheap on JOP.

### 1.3 Handle Lists

Three linked lists thread through `handle[OFF_NEXT]` and `handle[OFF_GREY]`:

- **Free list** (`freeList`): Handles with `OFF_PTR == 0`. Singly linked via `OFF_NEXT`.
  Pop from head on allocation.
- **Use list** (`useList`): All allocated handles. Singly linked via `OFF_NEXT`.
  Rebuilt during sweep.
- **Gray list** (`grayList`): Handles awaiting tracing. Singly linked via `OFF_GREY`.
  `0` = not in gray list, `-1` (GREY_END) = end of list. Push/pop from head.

### 1.4 Allocation (newObject / newArray)

Objects are allocated from the **top** of the current toSpace, growing downward:

```
toSpace:    [copyPtr ->     ...compacted data...    ...free...    <- allocPtr]
```

- `copyPtr` starts at `toSpace` (bottom), grows upward as objects are copied during GC.
- `allocPtr` starts at `toSpace + semi_size` (top), decremented by object size on allocation.
- Free space = `allocPtr - copyPtr`.
- When free space is insufficient or free handles are exhausted, `gc_alloc()` triggers a
  full GC cycle.

Allocation steps:
1. Check `copyPtr + size >= allocPtr` -- if so, trigger GC.
2. Check `freeList == 0` -- if so, trigger GC.
3. Decrement `allocPtr` by `size`.
4. Pop a handle from `freeList`.
5. Push it onto `useList`.
6. Set `handle[OFF_PTR] = allocPtr` (where object data lives).
7. Set `handle[OFF_SPACE] = toSpace` (BLACK -- newly allocated objects are live).
8. Set `handle[OFF_TYPE]` and `handle[OFF_MTAB_ALEN]` appropriately.
9. Return the handle address.

**First allocation special case**: Before `mutex` is created (the very first `new Object()`
in `GC.init()`), allocation runs without synchronization.

### 1.5 GC Cycle

The full stop-the-world GC cycle (`gc()`) is:

```java
Native.wr(1, Const.IO_GC_HALT);  // freeze other cores
grayList = GREY_END;              // discard write barrier entries
flip();
markAndCopy();
sweepHandles();
zapSemi();
Native.wr(0, Const.IO_GC_HALT);  // resume other cores
```

#### 1.5.1 flip()

Swaps toSpace and fromSpace. After flip:
- `toSpace` = the other semi-space (initially empty)
- `fromSpace` = the old semi-space (contains all live objects)
- `copyPtr` = start of new toSpace
- `allocPtr` = end of new toSpace (= copyPtr + semi_size)

All existing objects are now in fromSpace (their `OFF_SPACE` still points to the old
toSpace value, which is now `fromSpace`). They are therefore all WHITE.

#### 1.5.2 markAndCopy()

This is the heart of the collector. It combines marking and copying into one pass:

**Root scanning:**
1. `getStackRoots()`: Conservatively scans the on-chip stack (current thread: hardware
   stack from `Const.STACK_OFF` to `Native.getSP()`; other threads: their saved `stack[]`
   arrays). Every word is passed to `push()`.
2. `getStaticRoots()`: Reads the static reference table (address and count stored at
   `addrStaticRefs`). Each entry is pushed.

**push(ref):**
1. Range check: `ref < mem_start || ref >= mem_start + handle_cnt * HANDLE_SIZE` -> reject.
2. Alignment check: `(ref & 0x7) != 0` -> reject (not a handle start).
3. Free check: `handle[OFF_PTR] == 0` -> reject (on free list).
4. Black check: `handle[OFF_SPACE] == toSpace` -> skip (already processed).
5. Duplicate check: `handle[OFF_GREY] != 0` -> skip (already on gray list).
6. Thread onto gray list: `handle[OFF_GREY] = grayList; grayList = ref`.

**Tracing loop:**
```
while (grayList != GREY_END):
    ref = pop from gray list
    if already BLACK (OFF_SPACE == toSpace): skip
    if OFF_PTR == 0: skip

    // Push children (make them gray)
    if IS_REFARR: push each element ref
    if IS_OBJ: use GC_INFO bitmask from method table to find reference fields

    // Copy to toSpace
    compute size (from class struct for objects, from OFF_MTAB_ALEN for arrays)
    dest = copyPtr; copyPtr += size
    set OFF_SPACE = toSpace  (BLACK)
    memCopy(dest, addr, 0..size-1)  // copy object data
    handle[OFF_PTR] = dest           // redirect handle
    memCopy(dest, dest, -1)          // stop-bit (disable address translation)
```

**Size computation:**
- Plain object: `size = rdMem(rdMem(handle + OFF_MTAB_ALEN) - CLASS_HEADR)`
  (instance size from class struct)
- Long/double array (type 7 or 11): `size = arrayLength << 1`
- Other array: `size = arrayLength`

**Hardware memCopy**: Each `Native.memCopy(dest, src, pos)` call copies one word from
`src + pos` to `dest + pos` via hardware states CP_SETUP -> CP_READ -> CP_READ_WAIT ->
CP_WRITE. It also sets up address translation so concurrent accesses to
`[src, src+pos)` are redirected to `[dest, dest+pos)`. The final
`Native.memCopy(dest, dest, -1)` (CP_STOP) resets the translation range.

Note: Address translation is disabled in the current implementation (timing violation
at 100MHz). Stop-the-world GC does not need it since all other cores are halted.

#### 1.5.3 sweepHandles()

Walks the old `useList`. For each handle:
- If `OFF_SPACE == toSpace`: handle is BLACK (live) -- add to new `useList`.
- Otherwise: handle is WHITE (dead) -- add to `freeList`, set `OFF_PTR = 0`.

#### 1.5.4 zapSemi()

Zeroes the entire fromSpace (`semi_size` words). This is necessary because newly
allocated objects expect zeroed memory (Java semantics: all fields default to 0/null).
The next GC cycle will flip and use fromSpace as the new toSpace, so it must be clean.

**Cost**: `semi_size` memory writes. For a 64KB heap with ~3500-word semi-spaces, this
is 3500 write operations. At ~10 cycles per write on SDRAM, this is ~35,000 cycles -- a
significant fraction of the total GC pause time.

### 1.6 Write Barriers

JOP uses snapshot-at-beginning (Yuasa) write barriers. Three bytecodes have barriers:

**f_aastore** (reference array store):
```java
synchronized (GC.mutex) {
    int oldVal = Native.arrayLoad(ref, index);
    if (oldVal != 0
        && Native.rdMem(oldVal + OFF_SPACE) != toSpace
        && Native.rdMem(oldVal + OFF_GREY) == 0) {
        Native.wrMem(grayList, oldVal + OFF_GREY);
        grayList = oldVal;
    }
    Native.arrayStore(ref, index, value);
}
```

**f_putfield_ref** (reference field store):
```java
synchronized (GC.mutex) {
    int oldVal = Native.getField(ref, index);
    // same snapshot barrier as above on oldVal
}
Native.putField(ref, index, value);
```

**f_putstatic_ref** (static reference field store):
```java
synchronized (GC.mutex) {
    int oldVal = Native.getStatic(addr);
    // same snapshot barrier as above on oldVal
}
Native.putStatic(val, addr);
```

The barrier reads the OLD value being overwritten. If it is a non-null handle that is
not already BLACK (in toSpace) and not already on the gray list, it is pushed onto the
gray list. This ensures that objects reachable at the start of GC are not lost even if
the mutator overwrites references during concurrent GC. In stop-the-world mode, these
barriers are active but the gray list is discarded before each GC cycle (`grayList =
GREY_END` in `gc()`).

### 1.7 SMP Considerations

- **IO_GC_HALT**: Core running GC writes 1 to `IO_GC_HALT`, which causes CmpSync to
  halt all other cores' pipelines. They are frozen until GC writes 0. The GC core itself
  is not halted.
- **Lock owner exemption**: If another core holds the CmpSync lock when GC halts,
  that core is allowed to finish its critical section (preventing deadlock).
- **Single-core allocation**: Currently only core 0 allocates; other cores don't trigger
  GC. Future work: protect GC trigger with CmpSync lock.


## 2. Mark-Compact Design

### 2.1 Key Insight

JOP's handle indirection makes compaction trivially cheap. In a conventional collector,
compaction requires a full heap scan to fix up every pointer to moved objects. On JOP,
all Java references are handle addresses. Handles never move (they are in a fixed area).
Only the object DATA moves during compaction. To redirect a handle to the new object
location, update `handle[OFF_PTR]` -- one word write per live object.

### 2.2 Memory Layout (Changed)

```
Address 0:  [application code + constants]
            ...
mem_start:  [Handle Area     ]   handle_cnt * 8 words
            ...
heapStart:  [===========SINGLE HEAP==========]   heap_size words
            ...
mem_size:   (end of memory)
```

No more heapStartA/heapStartB. No more semi_size. The entire non-handle region is one
contiguous heap.

**Sizing formula:**
```
full_heap_size = mem_size - mem_start
handle_cnt     = full_heap_size / (TYPICAL_OBJ_SIZE + HANDLE_SIZE)
               = full_heap_size / (5 + 8) = full_heap_size / 13
heap_size      = full_heap_size - handle_cnt * HANDLE_SIZE
```

Compare with semi-space: `full_heap_size / 18` handles and `(remaining) / 2` usable
heap. Mark-compact gets:
- More handles (dividing by 13 vs 18 = ~38% more handles)
- The full remaining space as heap (no halving)

For a concrete example with 16K-word heap (64KB):
- Semi-space: ~888 handles, ~3460-word semi-space = **13,840 bytes** usable
- Mark-compact: ~1260 handles, ~5900-word heap = **23,600 bytes** usable

That is **70% more usable heap** from the same memory.

### 2.3 OFF_SPACE Repurposed as Mark Bit

Currently `OFF_SPACE` holds `toSpace` (= heapStartA or heapStartB) to distinguish live
from dead objects. With mark-compact, there is no toSpace/fromSpace distinction.

**Repurpose**: `OFF_SPACE` becomes a simple mark bit:
- `MARK_LIVE` (nonzero constant, e.g., 1): object is reachable (marked during tracing)
- `MARK_DEAD` (0): object is unreachable or not yet traced

Before each GC cycle, all handles have their mark cleared. During tracing, live handles
are set to `MARK_LIVE`. During sweep, handles still at `MARK_DEAD` are freed.

The write barriers check `OFF_SPACE != toSpace` -- this becomes `OFF_SPACE != MARK_LIVE`.
No functional change to the barrier logic.

### 2.4 Allocation (Changed)

Allocation uses a single bump pointer growing downward from the top of the heap, with a
compaction frontier growing upward from the bottom:

```
heapStart:  [compactPtr ->    ...compacted data...   ...free...    <- allocPtr]
                                                                  heapStart + heap_size
```

- `allocPtr` starts at `heapStart + heap_size` and decrements on each allocation.
- `compactPtr` starts at `heapStart` after GC and records where compacted data ends.
- Free space = `allocPtr - compactPtr`.

This is identical to the current scheme except there is only one region, not two
semi-spaces.

### 2.5 GC Cycle (Changed)

```java
public static void gc() {
    Native.wr(1, Const.IO_GC_HALT);

    // Discard write barrier entries (stop-the-world: roots are complete)
    grayList = GREY_END;

    clearMarks();       // was: flip()
    mark();             // was: markAndCopy()  -- mark only, no copy
    compact();          // NEW: slide compaction
    sweepHandles();     // mostly unchanged
    // zapSemi() is GONE

    Native.wr(0, Const.IO_GC_HALT);
}
```

#### 2.5.1 clearMarks() (Replaces flip())

```java
static void clearMarks() {
    synchronized (mutex) {
        // Clear all marks by walking the use list
        int ref = useList;
        while (ref != 0) {
            Native.wrMem(MARK_DEAD, ref + OFF_SPACE);
            ref = Native.rdMem(ref + OFF_NEXT);
        }
    }
}
```

This is O(live handles). Compare with `flip()` which was O(1) -- just pointer swaps.
The cost of walking the use list is small: one read + one write per live handle, all in
the handle area (sequential memory). With ~1260 handles maximum, this is ~2520 memory
operations -- small compared to the mark and compact phases.

**Alternative**: Clear marks lazily during sweep (check if marked, if not -> garbage).
This saves the clearMarks walk. But it requires that newly allocated objects between GC
cycles have `OFF_SPACE = MARK_LIVE` (which they do -- see allocation changes below). The
mark phase would set MARK_LIVE on reachable handles, and sweepHandles would free any
handle still at MARK_DEAD. This works identically to the current toSpace check.

**Chosen approach**: Use the lazy alternative -- skip clearMarks entirely. Instead:
- Newly allocated objects get `OFF_SPACE = MARK_LIVE` (like current: `toSpace`).
- At the start of GC, we clear all marks to MARK_DEAD (or use a generation counter).
- Actually, the simplest approach is a **flip between two marker values**, preserving
  the existing toSpace/fromSpace logic but without the memory flip.

Let us use this refined approach:

```java
static final int MARK_A = 1;
static final int MARK_B = 2;
static int currentMark = MARK_A;   // replaces toSpace

static void flipMark() {
    // Toggle between MARK_A and MARK_B
    currentMark = (currentMark == MARK_A) ? MARK_B : MARK_A;
}
```

- New allocations get `OFF_SPACE = currentMark`.
- At GC start, `flipMark()` changes the marker. Now all existing live objects have the
  OLD marker (equivalent to "in fromSpace"). Tracing sets `OFF_SPACE = currentMark`
  (equivalent to "moved to toSpace"). Sweep: anything not `currentMark` is dead.
- This is O(1) and preserves the exact same coloring semantics as the current GC.
- Write barriers: `OFF_SPACE != currentMark` is identical to `OFF_SPACE != toSpace`.

This is the cleanest approach. The variable `toSpace` is simply renamed to `currentMark`
(or kept as `toSpace` since the naming is just convention).

#### 2.5.2 mark() (Replaces markAndCopy() -- Mark Only)

```java
static void mark() {
    int i, ref;

    if (!concurrentGc) {
        getStackRoots();       // unchanged
    }
    getStaticRoots();          // unchanged

    for (;;) {
        // pop one object from the gray list
        synchronized (mutex) {
            ref = grayList;
            if (ref == GREY_END) {
                break;
            }
            grayList = Native.rdMem(ref + OFF_GREY);
            Native.wrMem(0, ref + OFF_GREY);
        }

        // already marked
        if (Native.rdMem(ref + OFF_SPACE) == toSpace) {
            continue;
        }

        // should not happen
        if (Native.rdMem(ref + OFF_PTR) == 0) {
            continue;
        }

        // push all children (IDENTICAL to current code)
        int addr = Native.rdMem(ref);
        int flags = Native.rdMem(ref + OFF_TYPE);
        if (flags == IS_REFARR) {
            int size = Native.rdMem(ref + OFF_MTAB_ALEN);
            for (i = 0; i < size; ++i) {
                push(Native.rdMem(addr + i));
            }
        } else if (flags == IS_OBJ) {
            flags = Native.rdMem(ref + OFF_MTAB_ALEN);
            flags = Native.rdMem(flags + Const.MTAB2GC_INFO);
            for (i = 0; flags != 0; ++i) {
                if ((flags & 1) != 0) {
                    push(Native.rdMem(addr + i));
                }
                flags >>>= 1;
            }
        }

        // Mark it BLACK (no copy!)
        synchronized (mutex) {
            Native.wrMem(toSpace, ref + OFF_SPACE);
        }
    }
}
```

The ONLY change from `markAndCopy()` is removing the copy block (size computation,
memCopy loop, OFF_PTR update, stop-bit). Everything else is identical.

Note: The variable `flags` is reused for GC_INFO in the IS_OBJ branch (overwriting the
original type). The current code has a subtle issue here: after the children-push loop
for IS_OBJ, `flags` holds the shifted-out GC_INFO value (now 0), not IS_OBJ. The size
computation then tests `if (flags == IS_OBJ)` which is `if (0 == 0)` = true, so it
correctly treats it as a plain object. This works by accident. In our mark-only version,
we do not compute size, so this does not matter.

#### 2.5.3 compact() (NEW)

This is the core of the mark-compact change. Walk the use list, sorted by ascending
source address. For each LIVE handle, compute its compacted destination, copy the
object data, and update `handle[OFF_PTR]`.

The key correctness requirement is that objects must be processed in ascending source
address order. This is detailed in section 2.6 below.

### 2.6 Compaction Correctness: Why Sorting By Address IS Required

Consider the heap after several allocation and GC rounds:

```
heapStart: [ObjA(size=3)] [gap] [ObjB(size=2)] [gap] [ObjC(size=4)] [free...]
           addr=100             addr=108              addr=115
```

After compaction:
```
heapStart: [ObjA(size=3)][ObjB(size=2)][ObjC(size=4)][...free...]
           dest=100      dest=103      dest=105
```

If we process in address order (A, B, C):
- Copy A: src=100, dest=100 (no-op, already in place)
- Copy B: src=108, dest=103 (dest < src, safe)
- Copy C: src=115, dest=105 (dest < src, safe)

If we process out of order (C, A, B):
- Copy C: src=115, dest=105 (but wait -- dest=105 was computed assuming A and B come
  first. If we process C first, dest should be 100, not 105.)

The destinations DEPEND on processing order. Pass 1 computes destinations in use-list
order. If we copy in a different order, the destinations are wrong.

So the solution is simple: **compute destinations and copy in the same pass**, processing
handles **sorted by ascending source address**. This guarantees dest <= source and
sequential correctness.

### 2.7 Revised Compact Implementation

```java
/**
 * Sort the use list by ascending OFF_PTR (source address).
 * Uses insertion sort via the OFF_NEXT linked list.
 * O(n^2) worst case, O(n) if nearly sorted.
 */
static void sortUseListByAddress() {
    if (useList == 0) return;

    int sorted = useList;
    int unsorted = Native.rdMem(sorted + OFF_NEXT);
    Native.wrMem(0, sorted + OFF_NEXT);  // sorted list has one element

    while (unsorted != 0) {
        int current = unsorted;
        unsorted = Native.rdMem(unsorted + OFF_NEXT);

        int currentAddr = Native.rdMem(current + OFF_PTR);

        // Find insertion point in sorted list
        if (currentAddr <= Native.rdMem(sorted + OFF_PTR)) {
            // Insert at head
            Native.wrMem(sorted, current + OFF_NEXT);
            sorted = current;
        } else {
            // Find position
            int prev = sorted;
            int scan = Native.rdMem(sorted + OFF_NEXT);
            while (scan != 0 && Native.rdMem(scan + OFF_PTR) < currentAddr) {
                prev = scan;
                scan = Native.rdMem(scan + OFF_NEXT);
            }
            Native.wrMem(scan, current + OFF_NEXT);
            Native.wrMem(current, prev + OFF_NEXT);
        }
    }
    useList = sorted;
}

/**
 * Compute the heap size of the object/array referenced by a handle.
 */
static int computeSize(int ref, int flags) {
    if (flags == IS_OBJ) {
        return Native.rdMem(Native.rdMem(ref + OFF_MTAB_ALEN) - Const.CLASS_HEADR);
    } else if (flags == 7 || flags == 11) {
        // long or double array
        return Native.rdMem(ref + OFF_MTAB_ALEN) << 1;
    } else {
        // other array (including IS_REFARR, boolean, byte, char, short, int, float)
        return Native.rdMem(ref + OFF_MTAB_ALEN);
    }
}

/**
 * Compact all live objects to the bottom of the heap.
 * Precondition: mark() has been called; useList contains all handles;
 * live handles have OFF_SPACE == toSpace.
 */
static void compact() {
    // Sort use list by source address (ascending) for safe in-place compaction
    sortUseListByAddress();

    int dest = heapStart;
    int ref = useList;

    while (ref != 0) {
        if (Native.rdMem(ref + OFF_SPACE) == toSpace) {
            int addr = Native.rdMem(ref + OFF_PTR);
            int flags = Native.rdMem(ref + OFF_TYPE);
            int size = computeSize(ref, flags);

            // Copy object data to compacted location (skip if already in place)
            if (dest != addr && size > 0) {
                for (int i = 0; i < size; ++i) {
                    Native.memCopy(dest, addr, i);
                }
                // Reset address translation (stop-bit)
                Native.memCopy(dest, dest, -1);
            }

            // Update handle to point to new location
            Native.wrMem(dest, ref + OFF_PTR);

            dest += size;
        }

        ref = Native.rdMem(ref + OFF_NEXT);
    }

    // Record compaction frontier
    compactPtr = dest;

    // Zero the free area so new allocations see zeroed memory
    // (Java requires default initialization to 0/null)
    for (int i = compactPtr; i < allocPtr; ++i) {
        Native.wrMem(0, i);
    }
}
```

### 2.8 Complete Zeroing Cost Analysis

The zeroing cost replaces `zapSemi()`:
- **zapSemi**: Always zeroes the entire fromSpace = `semi_size` words.
- **New zeroing**: Zeroes `allocPtr - compactPtr` words = the free region.

If the heap is 50% full, the free region is ~50% of heap_size. Since heap_size is roughly
2x semi_size, the new zeroing is about the same as zapSemi. But if the heap is mostly
full (say 80%), the free region is only 20% of heap_size = ~40% of old zapSemi size.
And the heap_size is 2x larger, so the 20% is 0.2 * 2 * semi_size = 0.4 * semi_size.

In practice, the zeroing cost is similar to zapSemi. But the benefit is that we can USE
all that memory, not waste half of it.

**Optimization**: Zero only the region that was previously occupied by objects that got
compacted (moved down). The region above `allocPtr` is already zero from the previous
zeroing pass. The region between `compactPtr` and the OLD `compactPtr` may already be
zero. We could track the previous compactPtr and only zero the new gap. But this
optimization is minor and can be deferred.

**Alternative -- zero on allocation**: Instead of zeroing the free region after GC,
zero each object's memory at allocation time. This spreads the cost and avoids the
bulk zeroing pass. The cost per allocation is the object size. This is similar to what
the JVM spec requires anyway. However, this changes the allocation path (currently just
a pointer bump) and adds latency to every `new`. Defer this optimization.


## 3. Complete Method-by-Method Changes

### 3.1 Fields (Changed)

```java
// REMOVED:
//   static int heapStartA, heapStartB;
//   static boolean useA;
//   static int fromSpace;
//   static int semi_size;

// RENAMED/REPURPOSED:
//   toSpace -> still called toSpace (or currentMark), flip logic changes
//   copyPtr -> compactPtr (frontier after compaction)
//   allocPtr -> unchanged (allocation frontier, top of heap, decrements)

// NEW:
static int heapStart;    // start of the single heap region
static int heapSize;     // size of the single heap region in words
```

### 3.2 init() (Changed)

```java
static void init(int mem_size, int addr) {
    addrStaticRefs = addr;
    mem_start = Native.rdMem(0);
    mem_start = (mem_start + 7) & 0xfffffff8;   // align to 8-word boundary

    if (Config.USE_SCOPES) {
        // ... unchanged scope code ...
    } else {
        full_heap_size = mem_size - mem_start;
        // Divide by (TYPICAL_OBJ_SIZE + HANDLE_SIZE) = 13
        // Use shift approximation: /16 (conservative, fewer handles)
        handle_cnt = full_heap_size >> 4;
        if (handle_cnt > MAX_HANDLES) handle_cnt = MAX_HANDLES;
        int handleWords = handle_cnt << 3;    // handle_cnt * HANDLE_SIZE
        heapSize = full_heap_size - handleWords;
        heapStart = mem_start + handleWords;

        // Single heap: allocate from top down, compact to bottom
        compactPtr = heapStart;
        allocPtr = heapStart + heapSize;
        toSpace = 1;           // initial mark value (nonzero)
        // No fromSpace needed

        freeList = 0;
        useList = 0;
        grayList = GREY_END;

        int ref = mem_start;
        for (int i = 0; i < handle_cnt; ++i) {
            Native.wrMem(freeList, ref + OFF_NEXT);
            Native.wrMem(0, ref + OFF_PTR);
            freeList = ref;
            Native.wrMem(0, ref + OFF_GREY);
            Native.wrMem(0, ref + OFF_SPACE);
            ref += HANDLE_SIZE;
        }

        concurrentGc = false;
    }
    mutex = new Object();
    OOMError = new OutOfMemoryError();
}
```

### 3.3 flip() -> flipMark() (Changed)

```java
static void flipMark() {
    synchronized (mutex) {
        // Toggle mark value. All existing live objects have the old mark.
        // New tracing will set the new mark on reachable objects.
        if (toSpace == 1) {
            toSpace = 2;
        } else {
            toSpace = 1;
        }
        // Reset compaction frontier
        compactPtr = heapStart;
        // allocPtr stays where it is (top of free area)
    }
}
```

Note: `toSpace` is now just a mark generation counter (1 or 2), not a memory address.
All comparisons `OFF_SPACE == toSpace` and `OFF_SPACE != toSpace` work identically.

### 3.4 gc() (Changed)

```java
public static void gc() {
    Native.wr(1, Const.IO_GC_HALT);

    if (!concurrentGc) {
        grayList = GREY_END;
    }

    flipMark();         // toggle mark value (O(1), was: flip())
    mark();             // trace reachable objects (was: markAndCopy())
    compact();          // slide compaction (NEW)
    sweepHandles();     // unchanged
    // zapSemi() is GONE -- zeroing is done in compact()

    Native.wr(0, Const.IO_GC_HALT);
}
```

### 3.5 newObject() / newArray() (Minimal Changes)

The allocation logic is almost identical. Only the free-space check changes:

```java
// OLD: if (copyPtr + size >= allocPtr)
// NEW: if (compactPtr + size >= allocPtr)
//   (functionally identical -- compactPtr replaces copyPtr)
```

The `toSpace` assignment (`Native.wrMem(toSpace, ref + OFF_SPACE)`) works unchanged
since `toSpace` is still the "current live mark."

### 3.6 free() / freeMemory() / totalMemory() (Changed)

```java
static int free() {
    return allocPtr - compactPtr;    // was: allocPtr - copyPtr
}

public static int totalMemory() {
    return heapSize * 4;    // was: semi_size * 4
}
```

### 3.7 push() (Unchanged)

The push logic is identical. The key checks are:
- Range: `ref < mem_start || ref >= mem_start + handle_cnt * HANDLE_SIZE`
- Alignment: `(ref & 0x7) != 0`
- Free: `OFF_PTR == 0`
- Black: `OFF_SPACE == toSpace` (works with mark values 1/2)
- Duplicate: `OFF_GREY != 0`

### 3.8 getStackRoots() / getStaticRoots() (Unchanged)

Conservative stack scanning and static reference scanning are completely unchanged.

### 3.9 sweepHandles() (Unchanged)

The sweep logic is identical:
- `OFF_SPACE == toSpace` -> live, add to new useList
- Otherwise -> dead, add to freeList, clear OFF_PTR

### 3.10 Write Barriers (Unchanged)

All three write barriers (f_aastore, f_putfield_ref, f_putstatic_ref) check
`OFF_SPACE != toSpace`. Since `toSpace` is still a valid marker (just 1 or 2 instead
of a heap address), the barriers work identically.

### 3.11 writeBarrier() (Unchanged)

The `writeBarrier()` method in GC.java also checks `OFF_SPACE != GC.toSpace`. No change
needed.


## 4. What Stays the Same

- **Handle structure**: All 8 fields, same offsets, same size.
- **Handle free/use lists**: Same linked list mechanism.
- **Gray list**: Same threading through OFF_GREY.
- **Conservative stack scanning**: Identical.
- **Static root scanning**: Identical.
- **Write barriers**: Identical (snapshot-at-beginning on OFF_SPACE).
- **IO_GC_HALT**: Same SMP halt mechanism.
- **Mark/trace phase**: Essentially unchanged (same tri-color logic, same child scanning).
- **sweepHandles()**: Identical.
- **push()**: Identical.
- **Allocation**: Same bump-pointer-down, same handle pop from free list.
- **Hardware memCopy**: Same usage pattern (word-by-word copy + stop-bit).
- **All other bytecodes**: No changes to any bytecode implementations.
- **Microcode**: No changes.
- **Hardware (SpinalHDL)**: No changes at all.


## 5. What Changes

| Component | Old (Semi-Space) | New (Mark-Compact) |
|-----------|------------------|--------------------|
| Memory layout | 2 semi-spaces | 1 heap |
| Usable heap | ~50% of total | ~100% of total |
| `flip()` | Swap heapStartA/B | Toggle mark (1/2) |
| `markAndCopy()` | Trace + copy | Trace only (`mark()`) |
| Copy phase | Interleaved with mark | Separate `compact()` |
| `compact()` | N/A | Sort by addr + slide |
| `zapSemi()` | Zero entire fromSpace | Zero free region in `compact()` |
| `heapStartA/B` | Two addresses | One `heapStart` |
| `semi_size` | Half of heap | N/A (`heapSize` = full) |
| `toSpace` | Heap address | Mark value (1 or 2) |
| `fromSpace` | Heap address | N/A (implicit: not `toSpace`) |
| `copyPtr` | Copy destination | `compactPtr` (compaction frontier) |


## 6. Edge Cases and Risks

### 6.1 Compaction Sort Cost

Insertion sort on the use list is O(n^2) worst case. With ~1000 live handles, this is
up to ~500K comparisons. Each comparison involves `Native.rdMem()` (handle area read),
which is fast (BRAM-like for handle area). Estimated cost: ~5M cycles worst case on
SDRAM. For stop-the-world GC, this is acceptable but noticeable (~50ms at 100MHz).

**Mitigation**: After the first GC, most objects are already in address order (compaction
places them sequentially). Insertion sort on a nearly-sorted list is O(n). Only newly
allocated objects (at the top of heap) are out of order. In practice, the sort cost should
be close to O(n) per GC cycle.

### 6.2 Hardware memCopy Address Translation

The current GC uses `Native.memCopy(dest, addr, i)` for copying object data. This sets
up address translation so concurrent reads to the old address are redirected to the new
address. In stop-the-world mode, address translation is unnecessary (all other cores are
halted).

Mark-compact uses the same memCopy loop. No change needed. The stop-bit
(`memCopy(dest, dest, -1)`) is still called after each object copy to reset the
translation state. This is necessary even in stop-the-world mode because the translation
range is cumulative -- it would grow unboundedly without reset.

### 6.3 Handle OFF_GREY During Compaction

The compact phase temporarily uses `OFF_GREY` (in the two-pass variant described in
section 2.6) to store destination addresses. In the final single-pass design (with sorted
use list), OFF_GREY is NOT used during compaction. However, it must be cleared for all
live handles before the next GC cycle so that `push()` can detect "not in gray list"
correctly. The `mark()` phase already clears `OFF_GREY` when popping from the gray list
(`Native.wrMem(0, ref + OFF_GREY)`). After mark() completes, all handles have
`OFF_GREY == 0` or `OFF_GREY == GREY_END` (only the gray list head, which is popped).
So this is safe.

### 6.4 Zero-Size Objects

The `computeSize()` function may return 0 for objects with no instance fields. The copy
loop handles this correctly (`for (i=0; i<0; ++i)` does nothing). The handle still gets
its `OFF_PTR` updated to the compacted address even for zero-size objects, which is
correct (multiple zero-size objects may share the same destination address).

### 6.5 Objects Allocated During GC (Concurrent Mode)

In stop-the-world mode, no allocation happens during GC (all other cores are halted,
and the GC core is running GC). If concurrent GC were ever enabled, newly allocated
objects would have `OFF_SPACE == toSpace` (the new mark), so they would be treated as
BLACK and not freed by sweep. The compact phase would need to handle them carefully
(they are in the allocation region at the top of heap, above `allocPtr`). For now, this
is not a concern since concurrent GC is never used.

### 6.6 IDIV Avoidance

JOP's hardware integer division is broken (see MEMORY.md). The current GC uses shift
operations instead of division. The mark-compact GC uses the same approach:
- `handle_cnt = full_heap_size >> 4` (divide by 16), capped at `MAX_HANDLES = 65536`
- No division in `computeSize()`, `compact()`, or `mark()`.
- The sort uses only comparisons and pointer manipulations.

### 6.9 MAX_HANDLES Cap (Large Memory)

For memories >= 64MB, the uncapped formula `handle_cnt = full_heap_size >> 4` creates
an impractically large handle table:

| Memory Size | Uncapped handle_cnt | Handle Area | Sweep Time (100MHz) |
|-------------|--------------------:|------------:|--------------------:|
| 8MB         | 131,072             | 4MB (50%)   | ~10ms               |
| 64MB        | 1,048,576           | 32MB (50%)  | ~84ms               |
| 256MB       | 4,194,304           | 128MB (50%) | ~335ms              |
| 1GB         | 16,777,216          | 512MB (50%) | ~1.3s               |

With `MAX_HANDLES = 65536`, the handle area is capped at 512K words (2MB), and sweep
takes ~6ms regardless of memory size. The heap gets the rest of the memory:

| Memory Size | handle_cnt | Handle Area | Heap Available |
|-------------|----------:|------------:|---------------:|
| 8MB         | 65,536    | 2MB (25%)   | 6MB            |
| 256MB       | 65,536    | 2MB (0.8%)  | 254MB          |
| 1GB         | 65,536    | 2MB (0.2%)  | 1022MB         |

65536 handles is sufficient for typical JOP workloads. Handle exhaustion causes
`OutOfMemoryError` even with free heap space — the application would need to allocate
65536+ simultaneous live objects to exhaust the pool.

### 6.7 First Allocation (Before mutex)

The first allocation (`mutex = new Object()` in `init()`) happens before `mutex` exists.
The current code has a special non-synchronized path for this. The mark-compact changes
do not affect this path -- it still just bumps `allocPtr` and pops from `freeList`.

### 6.8 Cache Coherency (SMP)

After compaction, all live objects are at new addresses. The Array Cache (A$) and Object
Cache (O$) may contain stale entries pointing to old addresses. Since GC runs under
`IO_GC_HALT` (all other cores frozen), only the GC core's caches are active.

After GC completes and `IO_GC_HALT` is cleared, other cores resume. Their caches may
contain pre-GC data. This is the same situation as the current semi-space GC: after
copying, object data is at new addresses, but caches may hold stale data.

In the current code, `Native.memCopy()` does NOT invalidate caches. The stop-bit
(`memCopy(dest, dest, -1)`) resets address translation but does not flush caches. This
means the current GC also has this potential issue.

**Assessment**: Since `IO_GC_HALT` freezes all other cores, they cannot access SDRAM during
GC. After resuming, any cache access goes through the normal cache miss path if the
cached data is invalidated by snoop. Since putfield/iastore from the GC core (during
memCopy) DON'T fire snoops (memCopy uses raw wrMem, not putfield), other cores' caches
are NOT invalidated.

**Risk**: Same as current GC. If this were a problem, it would already be seen with the
semi-space collector. The fact that the current GC works (9800+ GC rounds on FPGA) suggests
this is not an issue in practice. The likely explanation: other cores' caches are populated
AFTER GC (using post-GC addresses from updated handles), so they never hold stale pre-GC
data.

However, we should add a `Native.invalidate()` call after compaction as a safety measure:

```java
static void compact() {
    // ... compaction logic ...

    // Invalidate caches after moving objects
    Native.invalidate();
}
```

### 6.9 Static Field Addresses

Static fields store handle addresses (not data pointers). Compaction changes
`handle[OFF_PTR]` but not the handle address itself. Therefore static fields remain valid
after compaction. This is the key benefit of handle indirection.


## 7. Migration Path

### 7.1 Phase 1: Minimal Change, BRAM Test

1. Modify `GC.java` with the mark-compact changes.
2. Test with `JopCoreBramSim` (Smallest HelloWorld -- no GC triggered).
3. Test with `JopSmallGcBramSim` (Small HelloWorld -- GC triggered every ~12 rounds).
4. Run `JopJvmTestsBramSim` (49 JVM tests).

BRAM simulation is the fastest feedback loop (~10 seconds per run).

### 7.2 Phase 2: SDRAM Simulation

5. Test with `JopCoreWithSdramSim` (Smallest HelloWorld, SDRAM).
6. Test with `JopSmallGcSdramSim` (Small HelloWorld, SDRAM, GC triggered).

### 7.3 Phase 3: SMP Simulation

7. Test with `JopSmpNCoreHelloWorldSim` (2-core BRAM, NCoreHelloWorld with GC).
8. Test with `JopSmpSdramNCoreHelloWorldSim` (4-core SDRAM).

### 7.4 Phase 4: FPGA Hardware

9. QMTECH SDRAM single-core (serial boot, GC test).
10. QMTECH SDRAM SMP (2-core, 4-core with GC).
11. CYC5000 SDRAM (single-core + SMP with GC).

### 7.5 Regression: What to Watch For

- **Silent data corruption**: Objects at wrong addresses after compaction. Compare handle
  OFF_PTR values before and after GC. Add diagnostic prints.
- **Handle corruption**: Use list getting corrupted during sort. Add a use-list integrity
  check (walk and verify all handles are in range, aligned, and have nonzero OFF_PTR).
- **Zeroing issues**: Newly allocated objects seeing nonzero fields. The free-region
  zeroing must cover the entire gap.
- **Stack corruption**: Conservative scanner rejecting valid handles due to mark value
  change. Since toSpace is now 1 or 2 (small numbers), stack values like 1 or 2 could
  be falsely identified as "already BLACK" and skipped by push(). This is NOT a problem:
  push() first checks the range (`ref < mem_start`), and 1 and 2 are well below
  `mem_start` (which is typically ~600+). So small integer values are rejected before the
  toSpace check.

### 7.6 Diagnostic Instrumentation

Add temporary diagnostic output (guarded by a flag) to verify correct operation:

```java
static boolean gcDebug = false;

public static void gc() {
    if (gcDebug) {
        log("GC start, free=", free());
        log("  useList=", useList);
        log("  compactPtr=", compactPtr);
        log("  allocPtr=", allocPtr);
    }
    // ... GC cycle ...
    if (gcDebug) {
        log("GC end, free=", free());
        log("  compactPtr=", compactPtr);
        log("  allocPtr=", allocPtr);
    }
}
```


## 8. Performance Comparison

| Metric | Semi-Space | Mark-Compact |
|--------|-----------|--------------|
| Usable heap | semi_size | heap_size (~2x) |
| GC pause (mark) | O(live) | O(live) -- same |
| GC pause (copy/compact) | O(live_data) | O(live_data + sort) |
| GC pause (zap/zero) | O(semi_size) | O(free_size) |
| Total pause | mark + copy + zap | mark + sort + compact + zero |
| Allocation cost | O(1) bump pointer | O(1) bump pointer -- same |
| Fragmentation | None (copying) | None (compaction) |

The mark-compact GC has a slightly higher pause time due to the sort step. But:
- The sort is O(n) for nearly-sorted lists (common case after first GC).
- The doubled heap size means GC is triggered half as often.
- The elimination of zapSemi saves ~3500 writes per GC cycle.
- Net: fewer GC pauses, each slightly longer, with much more usable memory.


## 9. Summary of All File Changes

Only ONE file changes: `java/runtime/src/jop/com/jopdesign/sys/GC.java`

**No changes to**:
- `JVM.java` (write barriers reference `GC.toSpace` which still exists)
- `Native.java` (native methods unchanged)
- `Startup.java` (calls `GC.init()` with same signature)
- `Const.java` (no new constants needed)
- Any SpinalHDL hardware files
- Any microcode files
- Any other Java runtime files

This is a pure Java-level change with zero hardware impact.
