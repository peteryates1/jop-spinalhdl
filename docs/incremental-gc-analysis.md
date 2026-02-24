# Incremental and Concurrent GC Analysis for JOP

Analysis of approaches to reduce stop-the-world (STW) pause times for the JOP
Java Optimized Processor. This builds on the existing semi-space copying
collector and the planned mark-compact replacement.

## 1. Current Pause Time Analysis

### 1.1 Current STW Mechanism

The GC is triggered by `gc_alloc()` when allocation fails (free list empty or
insufficient heap space). The `GC.gc()` method halts all other cores via
`IO_GC_HALT`, runs the full collection cycle, then resumes them:

```java
// GC.java — gc() method
Native.wr(1, Const.IO_GC_HALT);   // freeze all other cores
grayList = GREY_END;                // discard stale write barrier entries
flip();                             // swap semi-spaces
markAndCopy();                      // trace + copy all live objects
sweepHandles();                     // reclaim dead handles
zapSemi();                          // zero from-space
Native.wr(0, Const.IO_GC_HALT);   // resume other cores
```

The hardware halt mechanism (`CmpSync` in `jop/io/CmpSync.scala`) freezes
other cores' pipelines at the clock level -- a core with `halted=True` cannot
execute any instructions. The GC core itself is exempt.

### 1.2 Pause Time Components

Each GC pause consists of five phases, with costs proportional to different
heap properties:

| Phase | Operation | Cost Proportional To |
|---|---|---|
| **flip** | Swap space pointers | O(1) -- constant |
| **getStackRoots** | Scan all thread stacks conservatively | O(total_stack_depth) |
| **getStaticRoots** | Scan static reference fields | O(static_ref_count) |
| **markAndCopy** | Trace gray list, copy live objects | O(live_objects * avg_size) |
| **sweepHandles** | Walk use list, free dead handles | O(handle_count) |
| **zapSemi** | Zero entire from-space | O(semi_size) |

The dominant costs are:

1. **markAndCopy**: For each live object, reads handle fields (2-3 memory
   accesses), traces children (1 access per reference field), and copies the
   object word by word via `Native.memCopy()`. Each memCopy call goes through
   the CP_SETUP/CP_READ/CP_READ_WAIT/CP_WRITE/LAST state machine -- about 8-12
   cycles for BRAM, 20-40 cycles for SDRAM per word. A typical 5-word object
   costs ~60-200 cycles to copy on SDRAM.

2. **zapSemi**: Writes zero to every word in from-space. With a 28KB semi-space
   (~7000 words on CYC5000), this is ~7000 SDRAM write cycles. On QMTECH with
   256Mbit SDRAM, semi-space can be up to ~8M words, making this extremely
   costly.

3. **sweepHandles**: Iterates the entire use list. With `handle_cnt = heap/16`,
   a 64KB heap has ~4000 handles, each requiring 2 memory accesses (read
   OFF_SPACE, write OFF_NEXT).

### 1.3 Estimated Pause Duration

For a realistic 64KB heap on CYC5000 (28KB semi-space, ~500 live objects of
average size 5 words):

| Phase | Estimated Cycles | At 100 MHz |
|---|---|---|
| Stack scan (256 entries) | ~5,000 | 50 us |
| Static scan (~50 refs) | ~1,000 | 10 us |
| Mark+copy (500 objects x 5 words) | ~100,000 | 1.0 ms |
| Handle sweep (~2000 handles) | ~20,000 | 200 us |
| Zap semi-space (7000 words) | ~35,000 | 350 us |
| **Total** | **~161,000** | **~1.6 ms** |

For larger SDRAM heaps (e.g., 16MB semi-space on QMTECH), the zap phase alone
would take ~160 ms. The mark+copy phase would scale with live data volume,
potentially reaching 10-100 ms for applications with thousands of live objects.

These are rough estimates -- actual numbers depend heavily on SDRAM access
patterns, CAS latency, and arbiter contention. But they establish that **pause
times are unbounded and proportional to heap size and live data volume**.

## 2. Existing Write Barrier Infrastructure

### 2.1 Barrier Type: Snapshot-at-Beginning (SATB)

JOP implements SATB write barriers in three bytecodes (`JVM.java`):

- **aastore** (`f_aastore`): Reference array store. Reads old value before
  overwrite, pushes old value to gray list if white.
- **putfield_ref** (`f_putfield_ref`): Object reference field store. Same SATB
  logic.
- **putstatic_ref** (`f_putstatic_ref`): Static reference field store. Same
  SATB logic.

The barrier logic (from `JVM.java`):

```java
synchronized (GC.mutex) {
    int oldVal = /* read old reference at target location */;
    if (oldVal != 0
        && Native.rdMem(oldVal + GC.OFF_SPACE) != GC.toSpace
        && Native.rdMem(oldVal + GC.OFF_GREY) == 0) {
        Native.wrMem(GC.grayList, oldVal + GC.OFF_GREY);
        GC.grayList = oldVal;
    }
    /* perform actual store */
}
```

This is a software SATB barrier executed as Java code via JVM bytecode
trap. The `synchronized(GC.mutex)` acquires the global CmpSync lock, adding
significant overhead per barrier invocation.

### 2.2 Barrier Overhead

Each SATB barrier requires:
- 1 lock acquire (monitorenter via IO_LOCK)
- 3-4 memory reads (old value, OFF_SPACE, OFF_GREY, possibly data pointer)
- 0-2 memory writes (OFF_GREY, grayList)
- 1 lock release (monitorexit via IO_UNLOCK)

On SDRAM, this totals approximately 40-100 cycles per barrier invocation,
plus the lock latency which is especially costly under SMP contention.

### 2.3 Hardware Barrier Support

There is **no hardware write barrier support** currently. All barriers execute
as Java code invoked via JOP's bytecode-to-method dispatch mechanism. The
bytecodes `aastore`, `putfield_ref`, and `putstatic_ref` are separate opcodes
(0xE1 for putstatic_ref, 0xE3 for putfield_ref) that trap to Java methods
rather than executing in microcode.

The `writeBarrier()` method in `GC.java` is a more general barrier that
checks GC_INFO metadata to determine if a field can hold a reference, but it
appears to be used only by the concurrent GC path (currently disabled).

### 2.4 Barrier Correctness for Concurrent GC

The SATB barriers preserve the snapshot-at-beginning invariant: any reference
that was reachable at the start of a GC cycle will be found by the collector,
because overwritten references are pushed onto the gray list before being
lost. This is exactly what a concurrent mark phase needs -- it ensures that
the mutator cannot cause the collector to miss live objects.

**Key observation**: The SATB barriers are already in place and correct for
concurrent marking. No barrier changes are needed to move from STW to
concurrent mark.

## 3. Handle Indirection Advantage

### 3.1 JOP Handle Architecture

Every object in JOP is accessed through a handle -- an 8-word structure in
the handle area:

```
Handle (8 words):
  [0] OFF_PTR:       Pointer to actual object data in heap
  [1] OFF_MTAB_ALEN: Method table pointer (objects) / array length (arrays)
  [2] OFF_SPACE:     Semi-space marker (toSpace/fromSpace)
  [3] OFF_TYPE:      Type info (IS_OBJ=0, IS_REFARR=1, or primitive type 4-11)
  [4] OFF_NEXT:      Linked list pointer (free/use list)
  [5] OFF_GREY:      Gray list threading (0=not in list, -1=end)
  [6] reserved
  [7] reserved
```

All Java references are handle addresses. The actual object data is at
`handle[OFF_PTR]`. Field access goes through handle dereference:
`getfield(handle, idx)` --> read `handle[0]` (data pointer) --> read
`data_ptr + idx`.

### 3.2 Implication for Object Movement

Because all references are handles, **moving an object only requires updating
one word**: `handle[OFF_PTR]`. No heap scanning is needed to find and update
pointers to the moved object. This is enormously beneficial for compaction:

- **Semi-space copy**: Already exploits this -- `Native.wrMem(dest, ref+OFF_PTR)` atomically redirects all references.
- **Mark-compact**: Compaction can slide objects to eliminate gaps, updating each handle's data pointer. Cost is O(1) per object moved, regardless of how many references point to it.
- **Incremental compact**: Can move one object at a time. Other mutator threads immediately see the new location on their next access because handle dereference always reads `handle[OFF_PTR]`.

### 3.3 Handle Indirection as a Forwarding Pointer

In traditional concurrent GC designs (e.g., ZGC, Shenandoah), forwarding
pointers or colored pointers are used to redirect accesses during concurrent
compaction. JOP's handles **are** forwarding pointers by design:

- **No Brooks pointer needed**: Brooks-style forwarding adds a word before each
  object. JOP's handle already serves this purpose.
- **No read barrier needed for relocation**: Once `handle[OFF_PTR]` is updated,
  all subsequent accesses through that handle automatically go to the new
  location. No load-side barrier is needed.
- **Atomic relocation**: Updating a single 32-bit word (`handle[OFF_PTR]`)
  atomically redirects all access. On JOP, 32-bit memory writes are atomic at
  the SDRAM controller level.

### 3.4 Address Translation Hardware

The memory controller already has an address translation mechanism
(`translateAddr` in `BmbMemoryController.scala`) designed for concurrent GC:

```scala
def translateAddr(addr: UInt): UInt = {
    val inRange = addr >= baseReg && addr < posReg
    Mux(inRange, addr + offsetReg, addr)
}
```

This redirects accesses in `[baseReg, posReg)` by adding `offsetReg` --
ensuring that during object copying, accesses to partially-copied objects see
the new location. Currently unused (STW GC doesn't need it), but it is wired
and available for concurrent GC. The comment notes a timing violation at
100 MHz, which would need to be addressed.

## 4. Real-Time GC Approaches

### 4.1 Incremental Mark

**Concept**: Break the mark phase into small bounded steps interleaved with
mutator execution. Each step processes a fixed number of objects from the gray
list, then yields to the mutator.

**How it works on JOP**:
1. GC.gc() does flip() + getStackRoots() (must be atomic -- STW for this phase)
2. Instead of running markAndCopy() to completion, process N gray list entries
   per GC increment
3. Return to mutator; continue marking on next allocation or timer interrupt
4. SATB barriers ensure newly-created references don't escape the collector

**Bounding mark-step time**: Each gray object requires: 1 handle read (data
pointer), 1 type read, and scanning up to K reference fields. If K_max is
bounded (e.g., max 32 reference fields per class, determined at compile time),
then processing one gray object takes at most `(2 + K_max) * SDRAM_latency`
cycles. Processing N objects per step gives a bound of
`N * (2 + K_max) * SDRAM_latency`.

**Tri-color state already in place**: The handle fields directly encode
tri-color:
- White: `OFF_SPACE != toSpace && OFF_GREY == 0`
- Gray: `OFF_GREY != 0` (on gray list)
- Black: `OFF_SPACE == toSpace`

**Challenges**:
- Root scanning must be atomic (conservative stack scan cannot be interleaved
  with mutator -- stack contents change)
- The "flip" must happen atomically, which means new allocations during
  incremental mark need to go into to-space (already the case: `newObject`
  marks as toSpace)

### 4.2 Incremental Compact (with Mark-Compact)

**Concept**: After marking is complete, move objects one at a time (or a small
batch), interleaved with mutator execution.

**How it works on JOP**:
1. Mark phase identifies live objects and their new locations
2. For each object to move:
   a. Copy data to new location
   b. Update `handle[OFF_PTR]` to point to new location
   c. Yield to mutator
3. Mutator immediately sees new location via handle indirection

**Handle indirection makes this straightforward**:
- Moving one object is O(object_size) -- just copy data and update one word
- No need to scan heap for pointers to the moved object
- The mutator sees the updated location atomically

**Handling references to objects being moved**:
- During the copy of object X, if another thread reads X through its handle,
  the handle still points to the old location (data is still valid there)
- After updating `handle[OFF_PTR]`, all new accesses go to the new location
- The old data remains valid until the space is reclaimed (which happens only
  after all objects are moved)
- For mark-compact with sliding compaction, objects move toward lower
  addresses, so the still-valid old copy doesn't conflict with already-moved
  objects

**Key simplification**: Because JOP's handle indirection means the mutator
never holds raw data pointers (all access goes through handle dereference),
there is no need for memory protection or read barriers during incremental
compaction.

### 4.3 Concurrent Mark (Dedicated GC Core)

**Concept**: Dedicate one of JOP's cores to GC. The GC core runs the mark
phase continuously while other cores execute the application.

**How it works on JOP**:
1. GC core runs a mark loop: pop gray objects, trace children, push new grays
2. Application cores continue executing, with SATB barriers maintaining the
   snapshot invariant
3. When gray list is empty, marking is complete
4. Brief STW pause for root re-scan (stack roots may have changed) and
   termination check
5. Compact or sweep phase follows

**Synchronization requirements**:
- **SATB barriers**: Already implemented in `aastore`, `putfield_ref`,
  `putstatic_ref`. These push overwritten references to the gray list, which
  the GC core will eventually process.
- **Gray list synchronization**: `push()` and the pop in `markAndCopy()` both
  use `synchronized(mutex)` -- this acquires the global CmpSync lock, which is
  correct but expensive. A lock-free gray list (using CAS or a per-core local
  list with periodic merging) would reduce contention.
- **Memory ordering**: JOP has no out-of-order execution or store buffers. All
  memory writes are immediately visible to other cores via SDRAM (after cache
  coherency -- handled by snoop invalidation). No memory fence instructions
  are needed.

**Advantages with 16 cores**: Dedicating 1 of 16 cores to GC loses only 6.25%
of compute capacity. On smaller core counts (2-4), the cost is higher (25-50%).

**Challenge -- root scanning**: The GC core cannot scan other cores' stacks
while they are running (conservative scanning would see inconsistent stack
frames). Options:
- Brief STW pause for root scan only (milliseconds, not dependent on live data)
- Handshake protocol: each core scans its own stack at a safe point and pushes
  roots to a shared list
- Hardware-assisted stack scanning at safe points (e.g., on method
  entry/return)

### 4.4 Concurrent Compact (Dedicated GC Core)

**Concept**: GC core compacts the heap while other cores continue running.

**How it works on JOP**:
1. After concurrent mark completes, compute compaction plan (new addresses)
2. For each live object (in address order for sliding compaction):
   a. Copy data to new location
   b. Atomically update `handle[OFF_PTR]`
3. Application cores see updates via handle dereference -- no read barrier
   needed

**Brooks forwarding vs. handle indirection**: Traditional concurrent compaction
(Shenandoah GC) uses Brooks forwarding pointers -- an extra word before each
object that either points to itself (not moved) or to the new location (moved).
Every object access checks the forwarding pointer first.

JOP's handles provide the same functionality **without any per-access overhead
for the mutator**:
- Handle dereference is already part of every field access (getfield, putfield,
  iaload, iastore) -- it is not an extra step
- The hardware memory controller performs the dereference automatically in the
  HANDLE_READ/HANDLE_WAIT states
- Updating `handle[OFF_PTR]` is a single atomic 32-bit write

**Address translation for partially-copied objects**: The `translateAddr`
hardware (baseReg, posReg, offsetReg) in BmbMemoryController can redirect
accesses to partially-copied objects. If core X is copying object Y's data
from old_addr to new_addr, and core Z accesses old_addr, the translation
redirects to new_addr. However:
- This only covers one object at a time (single translation window)
- The timing violation at 100 MHz needs to be resolved
- An alternative: update `handle[OFF_PTR]` only after the full copy is
  complete, ensuring the old data is valid throughout the copy window

### 4.5 Time-Triggered GC

**Concept**: GC work is scheduled in proportion to allocation. Each allocation
triggers a small amount of GC work, ensuring that collection keeps pace with
allocation.

**How it works on JOP**:
1. On each `newObject()` / `newArray()`, perform K units of GC work (e.g.,
   mark N objects, copy M words)
2. The ratio K/allocation_size is chosen so that GC completes before the heap
   is exhausted
3. Alternatively, use the hardware timer interrupt to trigger GC work at fixed
   intervals

**Integration with JOP's timer interrupts**:
- JOP has hardware timer interrupts (BmbSys prescaler + timer compare)
- A timer-triggered GC increment could execute in the interrupt handler
- The interrupt handler already runs `JVMHelp.interrupt()` which dispatches
  to registered Runnable handlers
- GC work in the interrupt handler would have bounded duration (process N
  gray objects)

**Real-time schedulability**: Time-triggered GC is the most naturally
compatible with rate-monotonic or EDF scheduling:
- Each GC increment has a worst-case execution time (WCET) that can be
  statically analyzed
- GC increments can be treated as a periodic task with known period and WCET
- The WCET bound comes from bounding the number of objects processed per
  increment and the maximum cost per object

**Challenge -- allocation rate**: If allocation spikes (e.g., creating many
small objects in a tight loop), the GC may not keep up. A minimum-free-memory
threshold can trigger more aggressive collection (larger K) when the heap is
nearly full.

## 5. Approach Comparison Matrix

| Criterion | Incremental Mark | Incremental Compact | Concurrent Mark | Concurrent Compact | Time-Triggered |
|---|---|---|---|---|---|
| **Pause time reduction** | Good -- only root scan is STW | Good -- only root scan is STW | Very good -- brief STW for re-scan | Best -- minimal STW | Good -- bounded increments |
| **Worst-case pause** | O(stack_depth) for root scan | O(stack_depth) for root scan | O(stack_depth) for root re-scan | O(stack_depth) for root re-scan | O(N * max_obj_cost) per increment |
| **Throughput overhead** | Low -- same total work | Low -- same total work | Medium -- loses 1 core | Medium -- loses 1 core | Low -- same total work |
| **Barrier overhead** | SATB (existing) | SATB (existing) | SATB (existing) | SATB + handle update | SATB (existing) |
| **Implementation complexity** | Low | Medium | Medium-High | High | Low-Medium |
| **Hardware changes** | None required | None required | None required | translateAddr fix | None required |
| **SMP requirement** | No (single core OK) | No (single core OK) | Yes (needs spare core) | Yes (needs spare core) | No (single core OK) |
| **Compatibility with mark-compact** | Natural fit | Natural fit | Natural fit | Natural fit | Natural fit |
| **WCET analyzable** | Yes (bounded step size) | Yes (bounded step size) | Difficult (depends on timing) | Difficult | Yes (by design) |

## 6. Recommended Approach

### 6.1 Primary Recommendation: Incremental Mark + Incremental Compact

The best fit for JOP's architecture is an **incremental mark-compact
collector** that breaks both the mark and compact phases into bounded steps,
interleaved with mutator execution.

**Rationale**:

1. **No extra core required**: Works on single-core and SMP configurations.
   A concurrent GC dedicating one of two cores is a 50% throughput loss.
   Incremental GC on one core has much lower overhead.

2. **WCET analyzable**: Each increment processes a bounded number of objects,
   with a known worst-case cost per object. This is essential for JOP's
   hard real-time use case.

3. **Existing barriers sufficient**: SATB barriers in aastore/putfield_ref/
   putstatic_ref already maintain the snapshot invariant. No new barriers
   needed.

4. **Handle indirection eliminates read barriers**: Unlike concurrent compaction
   in traditional VMs (which need load-side barriers or colored pointers),
   JOP's handles mean the mutator automatically sees updated object locations.
   No hardware or software read barrier is needed.

5. **No hardware changes required**: The incremental approach works entirely
   in software. Hardware improvements (section 7) can further reduce pause
   times but are not mandatory.

6. **Natural evolution from STW**: The code structure of `GC.gc()` can be
   incrementalized by splitting the mark loop and adding a compact phase.
   The flip/sweep/zap structure changes but the core algorithms are similar.

### 6.2 Secondary Recommendation: Concurrent Mark (for 8+ core SMP)

For configurations with 8+ cores, a **concurrent mark** phase running on a
dedicated GC core becomes attractive because the throughput cost (1/8 = 12.5%
or less) is acceptable and the pause reduction is more aggressive than
incremental approaches.

The concurrent mark can be combined with an incremental compact phase: the
GC core marks concurrently, then a brief STW pause verifies termination and
kicks off incremental compaction that runs on the application core(s).

## 7. Detailed Design Sketch: Incremental Mark-Compact

### 7.1 GC Phases

The collector operates in five phases. Only Phase 1 requires a brief STW
pause. All others are incremental.

**Phase 0: Idle**
- No GC activity
- Allocation triggers transition to Phase 1 when heap usage exceeds threshold

**Phase 1: Root Scan (STW)**
- `IO_GC_HALT = 1` (freeze other cores)
- Scan all thread stacks (conservative) -- push handles to gray list
- Scan static references -- push to gray list
- `IO_GC_HALT = 0` (resume other cores)
- Transition to Phase 2
- **Pause bound**: O(total_stack_depth + static_ref_count)

**Phase 2: Incremental Mark**
- On each allocation (or timer interrupt), process N gray objects:
  1. Pop from gray list
  2. Read handle: data pointer, type, GC info
  3. For each reference field: check if target is white, push to gray if so
  4. Mark object black (set `OFF_SPACE = toSpace`)
- SATB barriers ensure new reference stores push overwritten values to gray list
- When gray list is empty: transition to Phase 3
- **Increment bound**: N * (handle_read_cost + K_max * field_scan_cost)

**Phase 3: Compute Compaction Plan**
- Walk the use list, recording each object's new address in a compaction
  plan. For mark-compact, the plan is deterministic: live objects slide
  toward the beginning of the heap, in address order.
- Can be done incrementally (M handles per step).
- With handle indirection, the "plan" is simply computing `new_addr` for
  each handle -- no forwarding pointer table needed.

**Phase 4: Incremental Compact**
- For each live object (in address order):
  1. Copy data from old location to new location (word by word via memCopy
     or direct BMB writes)
  2. Update `handle[OFF_PTR]` to new location (single atomic write)
  3. Yield after P objects
- Other cores see the update immediately via handle dereference
- **Increment bound**: P * max_object_size * memcpy_cost

**Phase 5: Handle Sweep + Finalize**
- Walk use list, free handles of unmarked (dead) objects
- Reset allocation pointer to end of compacted region
- Transition to Phase 0
- Can be done incrementally (Q handles per step)

### 7.2 Semi-Space Elimination

Mark-compact eliminates the semi-space split, doubling usable heap. The heap
layout becomes:

```
[handle_area] [compact_heap ............... free_space]
```

Objects are compacted toward the beginning of the heap. New allocations come
from the free space at the end (bump pointer).

The `zapSemi()` phase disappears entirely -- mark-compact doesn't need to
zero a from-space.

### 7.3 Write Barrier Changes

For incremental mark-compact, the SATB barriers need one modification:

**During Phase 2 (incremental mark)**, the existing SATB barriers are correct
as-is. They push overwritten references to the gray list, ensuring the
concurrent mutator cannot hide live objects from the collector.

**During Phase 4 (incremental compact)**, no additional barriers are needed
because:
- Object movement updates `handle[OFF_PTR]` atomically
- The mutator accesses objects through handles, which always point to valid data
- The old copy remains valid until the entire compact phase completes (the old
  region is not reused until Phase 5)

**One change needed**: The color encoding in `OFF_SPACE` needs to support
three states instead of two (semi-space addresses). Options:
- Use a counter (GC epoch) instead of heap addresses for toSpace/fromSpace
- Use dedicated mark bits in a separate bitmap (more efficient for scanning)

### 7.4 Allocation During Collection

Objects allocated during any GC phase must be considered live (not collected
in the current cycle). The current code already handles this: `newObject()`
marks new objects as toSpace (black).

For mark-compact, new objects are allocated in free space beyond the compaction
frontier. They are implicitly live because they are reachable (the mutator just
created them) and will not be moved in the current cycle (they are already in
the compacted region or beyond it).

### 7.5 Gray List Thread Safety

The current `push()` / pop in `markAndCopy()` use `synchronized(mutex)` --
the global CmpSync lock. This is correct but adds lock latency.

For incremental operation (no dedicated GC core), the gray list is only
accessed by the allocating/collecting core and by SATB barriers on the same
core. In single-core mode, no synchronization is needed (interrupts are
disabled during GC increments).

For SMP with concurrent mark on a dedicated core, the gray list needs
efficient synchronization. Options:
- Per-core local gray buffers, periodically transferred to the GC core's list
  under the lock
- Lock-free FIFO using the handle's OFF_GREY field as a linked list with
  atomic compare-and-swap (JOP doesn't have CAS -- would need hardware support)
- SATB barriers write to a per-core buffer; GC core drains buffers periodically

### 7.6 Conservative Stack Scanning Constraint

JOP uses **conservative stack scanning** -- every value on the stack that looks
like it could be a handle address is treated as a root. This means:

- Stack scanning cannot be done concurrently (the stack changes as the
  mutator runs)
- Stack scanning must be atomic (STW or at a safe point)
- Objects referenced only from the stack cannot be moved while the stack
  reference exists (the conservative scanner doesn't know which stack values
  are actual references)

**Impact on incremental compact**: Objects found during root scan may be
referenced by raw stack values (not through handles). If we move such an
object, the stack value still points to the old handle address, which is
fine -- the handle is not moved, only the data behind it. The conservative
scanner only finds handle addresses (aligned to 8 bytes, within the handle
area), and handles themselves are never moved. So conservative scanning is
fully compatible with mark-compact.

## 8. Hardware Support Proposals

### 8.1 Hardware Write Barrier (High Impact, Medium Effort)

**Current cost**: Each SATB barrier is ~40-100 cycles of Java code execution
(method call, synchronized block, memory reads, conditional write).

**Proposed hardware**: A dedicated state in BmbMemoryController that performs
the SATB check for putfield_ref and putstatic_ref:

```
On putfield_ref trigger:
  1. Read old value at handle[OFF_PTR] + field_index
  2. Check: old_value != 0 && handle[old_value + OFF_SPACE] != toSpace
  3. If so: atomically add old_value to gray list (via hardware FIFO or
     linked list operation)
  4. Write new value
```

This could reduce barrier cost from ~40-100 cycles to ~10-15 cycles (a few
SDRAM reads + conditional write). The hardware gray list could be a small FIFO
buffer that software drains periodically.

**Implementation**: Add new states to BmbMemoryController state machine
(PUTREF_READ_OLD, PUTREF_CHECK, PUTREF_PUSH_GRAY, PUTREF_WRITE_NEW). The
`putref` signal already exists in MemCtrlInput but is currently unused
(identified in implementation-notes.md as "intentionally unused").

**Register**: A `toSpaceReg` I/O register readable/writable by software, used
by the hardware barrier to compare against OFF_SPACE. A `grayHeadReg` for the
hardware gray list head pointer.

### 8.2 Hardware Gray List (Medium Impact, Low Effort)

**Current cost**: Gray list push/pop uses `synchronized(mutex)` for thread
safety, adding CmpSync lock latency.

**Proposed hardware**: A small FIFO buffer (16-32 entries) in each core's
memory controller that accumulates gray list entries. Software drains the
FIFO during GC increments. Hardware barriers write to the FIFO; software
reads from it.

**Implementation**: Add a `grayFifo` (16-entry, 24-bit address) to
BmbMemoryController. New I/O register to read FIFO head, check empty/full.
Write barrier hardware pushes to FIFO instead of manipulating the linked list.

### 8.3 DMA-like Object Copy Engine (Medium Impact, Medium Effort)

**Current cost**: Object copy via `Native.memCopy()` is one word per
microcode call, going through CP_SETUP/CP_READ/CP_READ_WAIT/CP_WRITE/LAST
-- about 10-20 SDRAM cycles per word.

**Proposed hardware**: A block copy engine that accepts (src, dst, length)
and copies autonomously, using burst SDRAM transfers for efficiency.

**Implementation**: New state machine in BmbMemoryController (or a separate
DMA component on the BMB bus). Triggered by writing src/dst/len to I/O
registers. Uses burst reads (4-8 words) and burst writes for pipeline
efficiency. Signals completion via an I/O status register or interrupt.

**Benefit**: Could achieve ~2 cycles per word throughput (vs. ~15 cycles
for the current word-at-a-time memCopy), reducing compact phase duration by
~7x.

### 8.4 Hardware Mark Bit Array (Low Impact, Low Effort)

**Current cost**: Mark state is stored in handle fields (OFF_SPACE,
OFF_GREY) -- each check requires an SDRAM read.

**Proposed hardware**: A dedicated BRAM-based bit array (1 bit per handle).
With 4000 handles, this is only 500 bytes of on-chip RAM. Marking an object
is a single-cycle BRAM write; checking mark status is a single-cycle BRAM
read.

**Benefit**: Eliminates SDRAM reads for mark status checks during barrier
evaluation and during the sweep phase. Mark checking drops from ~15 cycles
(SDRAM) to 1 cycle (BRAM).

### 8.5 Impact Summary

| Hardware Addition | Pause Reduction | Throughput Gain | FPGA Cost | Priority |
|---|---|---|---|---|
| Hardware write barrier | Low (barrier isn't in pause) | High (faster barriers) | ~200 LE | High |
| Hardware gray FIFO | Low | Medium (no lock contention) | ~100 LE | Medium |
| DMA copy engine | High (faster compact) | Medium | ~300 LE | High |
| BRAM mark bits | Medium (faster sweep) | Medium (faster mark) | ~500 bytes BRAM | Medium |

## 9. Migration Path

### Step 1: STW Mark-Compact (prerequisite, currently planned)

Replace semi-space copying with mark-compact:
- Eliminates semi-space waste (2x heap efficiency)
- Eliminates zapSemi phase
- Same STW model as current collector
- Establishes the mark/compact/sweep phase structure

### Step 2: Incremental Mark

Split the mark phase into bounded increments:
- Modify `markAndCopy()` to process N objects per call, saving/restoring
  gray list state
- Add GC phase tracking (IDLE / ROOT_SCAN / MARK / COMPACT / SWEEP)
- Trigger increments from `newObject()` / `newArray()` or timer interrupt
- Root scan remains STW (brief pause)
- Compact remains STW (initially)

### Step 3: Incremental Compact

Split the compact phase into bounded increments:
- Process P objects per increment (copy data + update handle pointer)
- Track compaction progress (which object to move next)
- Free space management for allocation during compaction

### Step 4: Hardware Write Barrier (optional optimization)

- Implement SATB barrier in BmbMemoryController hardware
- Reduces per-barrier overhead from ~100 cycles to ~15 cycles
- Software falls back to slow path for edge cases

### Step 5: DMA Copy Engine (optional optimization)

- Implement burst-based block copy in hardware
- Reduces compact phase increment duration
- Enables shorter worst-case pause bounds

### Step 6: Concurrent Mark on Dedicated Core (optional, 8+ cores)

- GC core runs mark loop continuously
- Per-core gray buffers replace synchronized gray list
- Brief STW only for root re-scan at mark termination

## 10. Real-Time Bounds

### 10.1 Achievable Worst-Case Pause Time

With incremental mark-compact (Steps 1-3), the only mandatory STW pause is
the root scan (Phase 1).

**Root scan bound**:
- Stack depth: 256 entries per core (Const.STACK_SIZE)
- Each entry: 1 SDRAM read + handle range check + possible push
- Per entry cost: ~20 cycles (SDRAM read + comparison)
- N cores: `N * 256 * 20` cycles
- Static refs: ~50 entries, ~1000 cycles
- **Single core at 100 MHz**: `256 * 20 / 100M = ~51 us`
- **16 cores at 80 MHz**: `16 * 256 * 20 / 80M = ~1.0 ms`

**Incremental mark bound** (per increment, N=10 objects):
- Per object: handle read (~15 cycles) + type check (~15 cycles) + scan up
  to 8 reference fields (8 * 15 = 120 cycles) + mark write (~15 cycles)
- Per increment: `10 * 165 = 1650 cycles = ~16.5 us at 100 MHz`

**Incremental compact bound** (per increment, P=5 objects, avg 5 words):
- Per object: copy 5 words (~75 cycles SDRAM) + update handle (~15 cycles)
- Per increment: `5 * 90 = 450 cycles = ~4.5 us at 100 MHz`
- With DMA engine: `5 * 25 = 125 cycles = ~1.25 us`

### 10.2 Guaranteed Pause Times

| Scenario | Worst-Case STW Pause |
|---|---|
| Single core, 100 MHz | ~51 us (root scan only) |
| 4 cores, 100 MHz | ~200 us (root scan only) |
| 16 cores, 80 MHz | ~1.0 ms (root scan only) |
| All other GC work | Incremental, bounded per step |

These bounds assume all GC work except root scanning is incremental. The
incremental step sizes (N, P) are tunable parameters that trade pause time
against GC throughput (larger steps = fewer interruptions = higher throughput,
but longer increments).

### 10.3 Minimum Mutator Utilization (MMU)

MMU measures the fraction of time available to the mutator in any time window.
With incremental GC:

- GC increment of ~17 us every ~100 us (allocation-triggered) gives
  MMU(100 us) = 83%
- Timer-triggered at 1 ms intervals with 17 us increments gives
  MMU(1 ms) = 98.3%
- STW root scan of 51 us occurs once per GC cycle (every few hundred ms
  to seconds) -- amortized impact is negligible

For hard real-time tasks with periods >= 1 ms, the GC can be scheduled as a
low-priority background task that yields to real-time tasks, achieving near-100%
MMU for the real-time workload.

## 11. Summary

JOP's handle-based architecture provides a natural foundation for incremental
and concurrent garbage collection. The key advantages -- handle indirection
eliminates read barriers, SATB write barriers are already in place, and no
virtual memory is needed -- make the incremental mark-compact approach
particularly well-suited.

The recommended migration path is:
1. STW mark-compact (eliminates semi-space waste)
2. Incremental mark (bounded mark steps)
3. Incremental compact (bounded compaction steps)
4. Optional hardware acceleration (write barriers, DMA copy)

With this approach, worst-case STW pauses can be reduced from milliseconds
(current semi-space) to ~50-200 microseconds (root scan only), with all other
GC work performed in bounded increments of ~5-20 microseconds.
