# Array Cache Review: Implementation vs. Publications

This document reviews the SpinalHDL array cache (`ArrayCache.scala`) against
the design proposals in Martin Schoeberl's publications, particularly the
`dcache_wcet` journal paper ("Data cache organization for accurate timing
analysis", 2013) and the object cache papers (`ocache`, `ocwcet`, `ocwcet_ccpe`,
`troceval`, `profocache`).

Source material: 200 publications archived in `../jop-publications/` with a
detailed analysis in `../jop-publications/array-caching-summary.md`.

## Background: What the Papers Proposed

The publications consistently identify array caching as an unsolved problem in
the JOP architecture. Arrays are excluded from the object cache because:

- **Object fields** exhibit **temporal locality** (same fields accessed repeatedly)
- **Arrays** exhibit **spatial locality** (sequential elements accessed in order)

The object cache uses single-word cache lines with full associativity and LRU
replacement — the opposite of what arrays need. The `dcache_wcet` paper
proposes a dedicated array cache:

> "As access to arrays benefits mainly from spatial locality we propose prefetch
> and write buffers for array accesses. For operations on two arrays (e.g.,
> vector operations) two prefetch buffers are needed."

Key design elements from the paper:

1. **Prefetch buffers** rather than a traditional cache
2. **Array base address as tag** for buffer lookup
3. **Full cache line loads** to exploit spatial locality
4. **Tag stores base address + index of first element** in the line
5. **Array length stored in cache** with hardware bounds checking
6. **Write buffer** with compiler-inserted flush instruction to bound timing

A later passage in `dcache_wcet` confirms an implementation was underway:

> "The array cache loads full cache lines and has additional to the array base
> address also the array index of the first element in the cache line in the
> tag memory. Furthermore, the cache includes also the array length and the
> mandatory array bounds check is performed in the array cache."

However, none of the publications present an evaluation of the array cache.

## Implementation Overview

The SpinalHDL array cache is in:

- `spinalhdl/src/main/scala/jop/memory/ArrayCache.scala` (268 lines)
- `spinalhdl/src/main/scala/jop/memory/CacheSnoopBus.scala` (40 lines)
- Integration in `BmbMemoryController.scala` (states `AC_FILL_CMD`, `AC_FILL_WAIT`)
- Formal verification in `spinalhdl/src/test/scala/jop/formal/ArrayCacheFormal.scala`
- Configuration in `JopMemoryConfig.scala` (`useAcache`, `acacheWayBits`, etc.)

### Hardware Structure

- **16 entries** (`wayBits=4`), fully associative with FIFO replacement
- **4 elements per line** (`fieldBits=2`), 16 bytes per line
- **256 bytes data** in synchronous RAM + tag registers
- **Two-part tag**: handle address (24 bits) + upper index bits (22 bits)
- **Single valid bit per line** (unlike the object cache's per-field valid bits)
- **Snoop invalidation** for multi-core coherency

### Access Paths

- **iaload hit**: combinational detection in IDLE, 0 busy cycles, data available
  next cycle from registered RAM read
- **iaload miss**: handle dereference → `AC_FILL_CMD` → `AC_FILL_WAIT` (burst
  read of 4 elements) → back to IDLE
- **iastore hit**: write-through update of cached data during `HANDLE_DATA_WAIT`
- **iastore miss**: normal store path, no cache allocation

## Mapping: Paper Proposals → Implementation

| Paper Proposal | Implementation | Assessment |
|---|---|---|
| Prefetch buffers | Fully-associative cache with FIFO, aligned 4-element fills | Similar effect — full line loads exploit spatial locality |
| Two buffers for dual-array ops | 16 entries, so up to 16 array regions cached | Significantly exceeds the proposal |
| Base address as tag | Handle + upper index bits as two-part tag | Correct, and improved — allows multiple regions of the same array |
| Full cache line loads | 4-element aligned group fill on miss | Matches |
| Write buffer with flush | Write-through on iastore hit | Simpler approach, avoids the timing dependency the paper warned about |
| Bounds check in cache | Done separately in memory controller (`HANDLE_BOUND_READ/WAIT`) | Different location, functionally equivalent |
| Array length in cache | Not stored — fetched from handle during bounds check | Saves tag storage, reasonable tradeoff |

## What the Implementation Does Well

### 1. Two VHDL Bug Fixes

The original `acache.vhd` had two bugs, both fixed in SpinalHDL:

**Bug 1: Index slice boundary** — VHDL uses `FIELD_CNT` (4) instead of
`FIELD_BITS` (2) for the `idx_upper` slice, making the tag index coarser than
intended. Different array regions that should map to different cache lines
instead collide.

**Bug 2: FIFO pointer advancement** — VHDL advances `nxt` on every `wrIal`
pulse during a fill (4 times per miss), exhausting the 16-entry cache in just
4 misses instead of 16. The SpinalHDL version uses `incNxtReg` to advance
exactly once per miss.

### 2. Snoop Coherency

The publications identified cache coherence as a requirement for heap-allocated
data (`dcache_seus`: "Data on the heap and in the static area is shared by all
threads"). The implementation adds:

- Per-core snoop broadcast on iastore completion (`CacheSnoopBus`)
- Selective invalidation of matching handle + upper index region
- **Snoop-during-fill protection**: if a remote iastore invalidates the line
  currently being filled, the `snoopDuringFill` flag prevents subsequent
  `wrIal` pulses from re-validating it with potentially stale data

This is not present in the original VHDL and addresses a real multi-core
correctness issue.

### 3. FIFO Replacement for WCET

The `ca4rts` paper explicitly recommended FIFO over LRU:

> "LRU is difficult to calculate in hardware and only possible for very small
> sets. Replacement of the oldest block gives an approximation of LRU."

FIFO is fully predictable for persistence analysis — the WCET tool can bound
how many distinct array regions are accessed in a scope and determine whether
the cache is large enough to hold them all.

### 4. Clean Bytecode Separation

The papers noted that JVM bytecodes make it trivial to route array vs. object
accesses to different caches. The implementation does this cleanly: `chkIal`
fires on `iaload`, `chkIas` on `iastore`, completely independent of the object
cache's `getfield`/`putfield` path.

### 5. Formal Verification

Ten BMC properties verified with Z3:

1. Hit implies valid + tag match for some line
2. FIFO pointer advances by exactly 1 per miss
3. Snoop invalidates matching line
4. Snoop preserves non-matching lines
5. `wrIal` with `snoopDuringFill` does not update cache
6. Fill index auto-increments on `wrIal`
7. Tag+index uniqueness: at most one entry per (handle, tagIdx) pair
8. Fill sets valid bit for filled line
9. No write-allocate: `chkIas` does not set `incNxtReg`
10. Invalidation clears all valid bits and resets nxt

## Potential Issues

### 1. lineEnc Uses hitTagVec Instead of hitVec

`ArrayCache.scala:123-134` — The line encoder originally computed the selected
line from `hitTagVec` (handle-only match, ignoring upper index bits) rather than
`hitVec` (full match including upper index). If two cache lines held different
regions of the same array (same handle, different `tagIdx`), `lineEnc` would OR
their line numbers together, producing a corrupted line index.

The `lineEnc` result is used for the **data RAM read address** on a hit, so a
corrupted line index would return wrong data.

**Status**: RESOLVED — `lineEnc` now uses `hitVec` (full handle + index match)
instead of `hitTagVec`. Additionally, `hitTagReg` (which gates iastore
write-through) now uses `hitVec.orR` instead of `hitTagVec.orR`. Formal
property added: "tag+index uniqueness" verifies at most one entry per
(handle, tagIdx) pair under protocol constraints (BMC 10 steps).

### 2. chkIal Not Gated by Controller State

`BmbMemoryController.scala` originally wired `chkIal` as:

```scala
ac.io.chkIal := io.memIn.iaload  // Combinational check in IDLE
```

This fired every cycle that `iaload` was asserted on the memory interface,
regardless of the controller's state. The `chkIal` path resets `idxReg` to 0
and latches `lineReg`/`incNxtReg`. If `iaload` could pulse while the controller
is in `AC_FILL_WAIT` (e.g., the pipeline re-issues), it would corrupt the
in-flight fill state.

**Status**: RESOLVED — `chkIal` now gated by `state === State.IDLE`:

```scala
ac.io.chkIal := io.memIn.iaload && (state === State.IDLE)
```

### 3. stidx Invalidates the Entire Array Cache

`BmbMemoryController.scala` originally wired:

```scala
ac.io.inval := io.memIn.stidx || io.memIn.cinval
```

Every `stidx` (stack index change, i.e., method call/return) flushed the entire
array cache. Stack index changes don't affect heap-allocated array data — this
was inherited from VHDL behavior where `stidx` conservatively invalidates all
caches.

This could significantly reduce hit rates in code with frequent method calls,
especially in the benchmarks the papers identified as array-dominated (Matrix,
crypto, jPapaBench, UdpIp).

**Status**: RESOLVED — stidx invalidation is now configurable via
`JopMemoryConfig.acacheInvalOnStidx` (default `true` for backward compat /
WCET safety). Setting `false` disables A$ flush on method invoke/return,
improving hit rates for array-heavy code. Array data is heap-allocated and
handle+index tags remain valid across method calls. `cinval` still provides
explicit cache control.

### 4. Formal Verification Gaps

**Status**: RESOLVED — expanded from 5 tests to 10 tests:

1. Hit implies valid + tag match for some line — *existing*
2. FIFO pointer advances by exactly 1 per miss — *existing*
3. Snoop invalidates matching line — *existing*
4. **Snoop preserves non-matching lines** — non-matching entries unchanged
5. `wrIal` with `snoopDuringFill` does not update cache — *existing*
6. Fill index auto-increments on `wrIal` — *existing*
7. **Tag+index uniqueness**: at most one entry per (handle, tagIdx) pair
   (protocol-constrained BMC, 10 steps)
8. **Fill sets valid bit** for filled line
9. **No write-allocate**: `chkIas` does not set `incNxtReg`
10. **Invalidation completeness**: after `inval`, all valid bits cleared and
    nxt reset

Remaining unverified (future work):
- **Data integrity**: That a filled element reads back correctly on hit
  (requires multi-cycle protocol tracking with data path)
- **iastore write-through**: That `wrIas` data is readable on next `chkIal`
  (requires data path verification)

### 5. No WCET Tool Integration

The papers emphasized that array cache analysis requires persistence analysis
rather than hit/miss classification, because array indices are loop-dependent
and generally unknown at analysis time. The FIFO replacement policy enables
this analysis, but there's no evidence of WCET tool integration for the array
cache.

This is not a hardware issue but a tooling gap — the cache is designed for
analyzability but the analysis tooling may not yet exploit it.

## Summary

The SpinalHDL array cache is a solid realization of the papers' proposals, with
meaningful improvements over both the original design sketches and the VHDL
implementation:

- 16 entries vs. the proposed 2 buffers
- Two-part tags enabling multiple regions per array
- Snoop coherency for multi-core correctness
- Two VHDL bug fixes
- Formal verification of core invariants

All four identified issues have been addressed:

1. **lineEnc / hitTagVec** — `lineEnc` now uses `hitVec` (full match). Formal
   tag+index uniqueness property added and verified (BMC 10 steps).
2. **chkIal gated by state** — now gated by `state === State.IDLE`
3. **stidx invalidation** — now configurable via `acacheInvalOnStidx` (default
   true for WCET safety)
4. **Formal verification** — expanded from 5 to 10 tests covering tag+index
   uniqueness, fill correctness, snoop preservation, no write-allocate, and
   invalidation completeness

Remaining concern:

- **putfield chkIas timing** — uses `lineEnc` from combinational hit in
  IAST_WAIT. Correct but depends on handle override being the last assignment.
