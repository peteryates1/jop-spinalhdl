# Object Cache Review: Implementation vs. Publications

This document reviews the SpinalHDL object cache (`ObjectCache.scala`) against
the design proposals in Martin Schoeberl's publications, particularly the
`ocache` conference paper (ISORC 2011), the `ocwcet_ccpe` journal paper (CCPE
2012), and the `dcache_wcet` journal paper (Real-Time Systems 2013).

Source material: 200 publications archived in `../jop-publications/` with a
full index in `../jop-publications/INDEX.md`.

## Background: What the Papers Proposed

The object cache is a specialized data cache for heap-allocated objects,
designed for WCET analyzability with unknown addresses. The key insight is that
standard set-associative caches become useless when addresses are unknown at
analysis time — the analysis must merge all lines per way into one, reducing
an n-way set-associative cache to effectively n entries.

### Key Design Principles from Publications

**1. Fully associative, single-set** (`ocwcet_ccpe`, Section 3):

> "The object cache architecture is optimized for WCET analysis instead of
> average case performance. Objects are dynamically allocated and have unknown
> addresses. As unknown addresses are mapped to a single set in the cache
> analysis, having more than one set would not provide any benefits.
> Consequently, the cache contains only a single set — it is a fully
> associative cache."

**2. One object per way, fields indexed within the line** (`ocache`):

> "The cache contains just a single line per way. Instead of mapping blocks
> of the main memory to those lines, whole objects are mapped to cache lines.
> The index into the cache line is the field index."

**3. Handle as tag — zero-cost indirection on hit** (`ocache`):

> "The tag memory contains the pointer to the handle (the Java reference)
> instead of the effective address of the object in the memory. If an access
> is a hit, the cost for the indirection is zero — the address translation
> has already been performed."

This also benefits GC: "The effective address of an object can only be changed
by the garbage collection. Only a cached address needs to be updated or
invalidated after the move. The cached fields are not affected."

**4. Per-field valid bits, word-fill policy** (`ocache`):

> "Only the missed word in the cache line is filled on a miss. To track which
> words of a line contain a valid entry, one valid bit per word is added to
> the tag memory."

The rationale is that object field accesses exhibit temporal locality, not
spatial locality: "field accesses benefit primarily from temporal locality
and only little from spatial locality" (`ocwcet_ccpe`).

**5. FIFO replacement** (`ocache`):

> "The cache lines (tags) are allocated in first-in first-out (FIFO) order.
> FIFO allocation is simpler in hardware than least recently used (LRU)
> order."

The persistence analysis used for WCET makes FIFO equivalent to LRU:
"With the form of persistence analysis we use, it is irrelevant whether the
replacement policy is LRU or FIFO" (`ocwcet_ccpe`).

**6. Write-through, no write-allocate** (`ocache`):

> "To simplify static WCET analysis the cache is organized as write-through
> cache. Write back is harder to analyze statically, as on each possible miss
> an additional write back needs to be accounted for."

For putfield: "If the tag comparison is a miss, a new cache line is allocated
on a getfield, but not on a putfield. The scarce resource of cache ways (tags)
is not spent on a single write, when the object is not yet in the cache."

**7. Objects only — not arrays** (`ocwcet_ccpe`):

> "The object cache is used only for objects and not for arrays. This is
> because arrays tend to be larger than objects, and their access behavior
> rather exposes spatial locality, in contrast to the temporal locality of
> accesses observed for objects."

**8. WCET analysis via symbolic points-to** (`ocwcet_ccpe`, Section 4):

> "The proposed object cache is fully associative, which implies that the
> cache analysis does not need to know the address of the object. If it is
> known that some method operates on a set of k <= n objects, where n is the
> associativity, the analysis can infer that within this method, each access
> to an object field will be a cache miss at most once."

**9. Recommended configuration** (`ocache`, `dcache_wcet`):

> "A configuration of 4 ways and a line size of 16 fields is found to be a
> good tradeoff between performance improvement and resource consumption."
> Under 10% of processor area.

## Implementation Overview

The SpinalHDL object cache consists of:

- `spinalhdl/src/main/scala/jop/memory/ObjectCache.scala` (232 lines)
  — fully associative tag/data cache with per-field valid bits
- Integration in `BmbMemoryController.scala` (getfield hit/miss, putfield
  write-through, HWO bypass, snoop routing)
- `CacheSnoopBus.scala` — per-field snoop invalidation for multi-core
- Formal verification in `ObjectCacheFormal.scala`
- Configuration in `JopMemoryConfig.scala`

### Hardware Structure

- **16 entries** (`wayBits=4`), fully associative with FIFO replacement
- **8 fields per entry** (`indexBits=3`)
- **128 words** data RAM (16 entries x 8 fields)
- **24-bit handle tags** in registers, parallel comparison
- **8-bit valid vector** per entry (one bit per field)
- **Cacheable check**: fields 0-7 only (`fieldIdx[7:3]` must be zero)

### Access Paths

- **getfield hit**: combinational in IDLE, 0 busy cycles, data on next cycle
- **getfield miss**: HANDLE_READ → HANDLE_WAIT → HANDLE_CALC → HANDLE_ACCESS
  → HANDLE_DATA_WAIT → fill cache + return data (~5 cycles + memory latency)
- **putfield**: PF_WAIT → HANDLE_READ → ... → HANDLE_DATA_WAIT → write-through
  if tag was hit, no write-allocate on miss
- **HWO bypass**: if handle's data pointer is an I/O address, cache updates
  are skipped (`wasHwo` flag)

## Mapping: Paper Proposals → Implementation

| Paper Proposal | Implementation | Assessment |
|---|---|---|
| Fully associative, one object per way | 16 entries, each caches one object | Exact match |
| Handle address as tag | 24-bit handle in registers, parallel comparison | Exact match |
| Per-field valid bits, word fill | 8-bit valid vector per entry, single field filled on miss | Exact match |
| FIFO replacement | `nxt` pointer, advances by 1 on getfield miss | Exact match |
| Write-through on putfield | `wrPf` gated with `hitTagReg`, writes to RAM + main memory | Exact match |
| No write-allocate | `chkPf` uses `lineEnc` (hit line), `incNxtReg` always False for putfield | Exact match |
| Objects only, not arrays | Controller routes getfield/putfield to OC, iaload/iastore to AC | Exact match |
| Fields > line size uncached | `cacheable` check: `fieldIdx[7:3] === 0` | Exact match |
| 4 ways, 16 fields recommended | 16 ways, 8 fields | Different — see note |
| 14% speedup (4-way, 16-field) | Not evaluated | N/A |

### Note on Configuration

The papers recommend 4 ways / 16 fields. The SpinalHDL default is 16 ways /
8 fields (same total data: 128 words). This trades field coverage for higher
associativity. The `dcache_wcet` resource table shows that 16 ways / 8 fields
costs 745 LCs vs. 273 LCs for 4 ways / 16 fields. The higher associativity
benefits the persistence analysis (more distinct objects fit before eviction),
while 8 fields covers most embedded Java objects (the papers report median
object sizes of 3-5 fields for typical benchmarks). Both parameters are
configurable.

## What the Implementation Does Well

### 1. Per-Field Snoop Invalidation

The publications discuss CMP coherence in broad terms: "the cache can be held
consistent by invalidating the cache on monitorenter" (`troceval`). The
SpinalHDL implementation goes further — on a remote core's putfield, only the
specific field's valid bit is cleared, not the entire line
(`ObjectCache.scala:223-231`):

```scala
when(io.snoopValid && tag(i) === io.snoopHandle) {
  v(snoopIdx) := False  // Only this field, other fields remain valid
}
```

This is more precise than whole-line invalidation and preserves cached fields
that weren't written by the remote core. The snoop bus distinguishes putfield
(object) from iastore (array) via the `isArray` flag.

### 2. HWO (Hardware Object) Bypass

Hardware objects have fields mapped to I/O addresses. Caching I/O reads would
return stale data. The controller detects HWO during handle dereference
(`BmbMemoryController.scala`, HANDLE_WAIT state) by checking if the data
pointer is in I/O address space, then sets `wasHwo` to suppress cache updates
in HANDLE_DATA_WAIT. This is not discussed in the publications but is
essential for correctness with JOP's hardware object abstraction.

### 3. Valid Bit Clearing on New Allocation

When a getfield miss allocates a new entry (`incNxtReg=True`), all valid bits
for that entry are cleared before setting the new field's bit
(`ObjectCache.scala:200-204`):

```scala
when(incNxtReg) {
  v := B(0, fieldCnt bits)  // Clear all, then set the new field
}
v(indexReg(indexBits - 1 downto 0)) := True
```

This prevents stale fields from a previously-cached object from appearing valid
under the new tag.

### 4. Last-Assignment-Wins Priority

Snoop invalidation is placed after `updateCache` and `inval` in the source,
ensuring that SpinalHDL's "last assignment wins" semantics give snoop priority.
If a local fill and a remote invalidation happen on the same cycle for the same
field, the snoop wins and the field stays invalid. This is the correct priority
for coherence.

### 5. Combinational Hit on getfield

The `hit` output is purely combinational — available in the same cycle that
`chkGf` fires. This enables the 0-busy-cycle fast path in the memory
controller's IDLE state, matching the VHDL design. The data output (`dout`) is
registered with 1-cycle latency, which is acceptable because the pipeline can
consume it on the next cycle.

## Potential Issues

### 1. lineEnc Uses hitTagVec (Same Issue as Array Cache)

`ObjectCache.scala:109-120` — The line encoder computes the selected line from
`hitTagVec` (handle-only match) rather than `hitVec` (handle + specific field
valid). This is the OR-based priority-free encoder ported from the VHDL.

For the object cache, this is **less concerning** than for the array cache
because each handle should appear at most once in the tag array (unlike the
array cache, where multiple regions of the same array can be cached). FIFO
replacement allocates a new entry only when no existing tag matches, so
duplicate handles should be impossible.

However, the `lineEnc` result is used for the RAM read address
(`ObjectCache.scala:164`), and a corrupted `lineEnc` would read from the wrong
entry. If a handle somehow appeared in two entries (e.g., due to a race), the
OR-encoding would silently produce a wrong line index.

**Recommendation**: Add a formal property asserting at most one entry is valid
(has any valid bit set) per handle value.

### 2. chkGf Not Gated by State (Same Pattern as Array Cache)

`BmbMemoryController.scala` wires:

```scala
oc.io.chkGf := io.memIn.getfield && !wasStidx
```

This fires combinationally whenever `getfield` appears on the memory interface,
regardless of the controller's state. The `chkGf` path resets `lineReg`,
`incNxtReg`, and `hitTagReg`. If `getfield` could pulse while the controller
is in HANDLE_DATA_WAIT (completing a prior miss), it would corrupt the
registered state needed for the fill.

In practice, the pipeline only issues `getfield` when the memory controller is
idle (the busy signal gates new operations). But the cache itself has no
protection.

**Recommendation**: Gate with `state === State.IDLE` for defense in depth.

### 3. stidx Invalidates the Entire Object Cache

`BmbMemoryController.scala`:

```scala
oc.io.inval := io.memIn.stidx || io.memIn.cinval
```

Every `stidx` (stack index change on method invoke/return) flushes the entire
object cache. The publications discuss scope-based invalidation in the context
of the JMM (Java Memory Model):

> "The cache can be held consistent by flushing the cache on monitorenter and
> access to volatile fields." (`troceval`)

`stidx` invalidation is more conservative than JMM requires. The scope-based
WCET analysis may legitimately need invalidation at method boundaries (to bound
the persistence region), but this makes the cache less effective for code with
frequent method calls to small helper methods that operate on the same objects.

The papers report that 15% tighter WCET estimates were achieved with improved
cache analysis on 8-core CMP, suggesting that unnecessary invalidation has a
measurable impact.

**Recommendation**: Consider whether `stidx` invalidation is required for
correctness or only for WCET analysis simplification. If only for analysis,
it could be made optional or restricted to specific scope boundaries.

### 4. putfield chkPf Timing

The putfield path goes through PF_WAIT before HANDLE_READ. In PF_WAIT, the
controller overrides the cache's handle input and fires `chkPf`:

```scala
objectCache.get.io.handle := addrReg  // Override default (which uses aoutAddr)
objectCache.get.io.chkPf := True
```

This works because PF_WAIT captures `addrReg` from the prior cycle and
`bcopd` has settled. However, the default `handle` wiring uses `aoutAddr`
(NOS from the stack), which changes freely. The override in PF_WAIT is
necessary because by that point the stack has moved on. This is correct but
fragile — it depends on the PF_WAIT override being the last assignment.

### 5. Formal Verification Gaps

The 2 existing tests verify cacheable bounds only:

1. No hit when field index out of range (uncacheable)
2. Hit implies cacheable field index

Not verified:

- **Tag uniqueness**: At most one entry per handle (prevents lineEnc corruption)
- **Valid bit integrity**: After `wrGf`, the specific field's valid bit is set
- **Hit correctness**: After a getfield fill, a subsequent `chkGf` to the same
  handle + field produces `hit=True`
- **Putfield write-through**: After `wrPf`, a subsequent `chkGf` to the same
  handle + field returns the written value
- **Snoop correctness**: After snoop invalidation, the specific field's valid
  bit is cleared while others remain
- **No write-allocate**: `wrPf` with tag miss does not allocate a new entry
- **FIFO pointer**: Advances by exactly 1 per getfield miss, not on putfield
- **Invalidation**: After `inval`, all valid bits are zero (mentioned in the
  test list but not present in the 2 actual tests)

**Recommendation**: Add formal properties covering at least tag uniqueness,
fill correctness (hit-after-fill), and snoop selective invalidation.

### 6. 8 Fields May Be Tight for Some Objects

With `indexBits=3`, only fields 0-7 are cacheable. Objects with more than 8
fields will have their higher-indexed fields bypass the cache entirely. The
papers recommend 16 fields and note:

> "The object layout may be optimized such that heavily accessed fields are
> located at lower indices." (`ocwcet_ccpe`)

This is a compiler/linker optimization that the hardware relies on. Without
it, frequently-accessed fields at index 8+ will always miss.

## Comparison with dcache_wcet Hardware Evaluation

The `dcache_wcet` paper provides hardware cost data for different
configurations on a Cyclone FPGA:

| Ways | Fields | Logic (LC) | Memory (bits) |
|------|--------|------------|---------------|
| 4 | 16 | 273 | 2048 |
| 16 | 8 | 745 | 4096 |
| 16 | 16 | 960 | 8192 |

The SpinalHDL default (16 ways, 8 fields) uses roughly 3x the logic of the
recommended configuration (4 ways, 16 fields) for the same total data capacity.
The tradeoff is higher associativity for the persistence analysis, which
benefits applications with many distinct objects accessed in a scope.

The paper also compares LRU vs FIFO hardware costs:

> "The implementation of a FIFO replacement strategy avoids the change of all
> tag memories on each read. Therefore, the resource consumption is less than
> for an LRU cache and the maximum frequency is higher."
> LRU 64-way: 2553 LC, 57 MHz. FIFO 64-way: 1872 LC, 94 MHz.

The FIFO choice in the implementation is validated by this data.

## Alignment with WCET Analysis Framework

The implementation supports the scope-based persistence analysis from
`ocwcet_ccpe`:

1. **Fully associative** — analysis doesn't need object addresses, only
   symbolic references
2. **FIFO replacement** — equivalent to LRU for persistence analysis
3. **Per-field valid bits** — each field miss counted individually, first-miss
   analysis per field per scope
4. **No write-allocate** — putfield misses don't consume ways, simplifying the
   "how many objects fit" analysis
5. **16 entries** — provides generous associativity for the all-fit criterion

The `ocwcet_ccpe` analysis directly applies:

> "A particularly simple criterion for persistency in an object cache with
> associativity n is that at most n distinct objects are accessed within one
> scope."

With n=16, most embedded Java methods will satisfy this criterion.

## Summary

The SpinalHDL object cache faithfully implements the publications' design with
meaningful additions for multi-core operation (per-field snoop invalidation,
HWO bypass). The architecture matches every design decision from the papers:
fully associative, handle-as-tag, per-field valid bits, word fill, FIFO
replacement, write-through, no write-allocate.

The main concerns are:

1. **lineEnc using hitTagVec** — same OR-encoder pattern as array cache, safe
   only if tag uniqueness holds (not formally verified)
2. **chkGf not gated by state** — relies on controller never issuing getfield
   outside IDLE
3. **stidx invalidation** — more conservative than JMM requires, reduces hit
   rates on method-call-heavy code
4. **Formal verification** — only 2 tests covering cacheable bounds; tag
   uniqueness, fill correctness, snoop correctness, and FIFO behavior not
   verified
5. **8 fields default** — half the paper's recommended 16, relies on compiler
   placing hot fields at low indices
