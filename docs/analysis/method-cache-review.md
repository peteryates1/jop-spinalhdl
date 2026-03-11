# Method Cache Review: Implementation vs. Publications

This document reviews the SpinalHDL method cache (`MethodCache.scala`) against
the design proposals in Martin Schoeberl's publications, particularly the PhD
thesis, the `ca4rts` journal paper, the `jtres_cache` conference paper, the
`cache_dac2007` DAC paper, and the Patmos-era papers (`mcpat`, `mcana`,
`spmvsmc`).

Source material: 200 publications archived in `../jop-publications/` with a
full index in `../jop-publications/INDEX.md`.

## Background: What the Papers Proposed

The method cache is JOP's signature architectural contribution — an instruction
cache that stores **complete methods** and only fills on `invoke` and `return`.
All other instructions are guaranteed hits. This eliminates instruction cache
interference with data access and reduces WCET analysis from per-instruction
cache modeling to call-tree analysis.

### Key Design Principles from Publications

**1. Whole-method loading** (thesis, Section 5.8):

> "A complete method is loaded into the cache on both invocation and return.
> This cache fill strategy lumps all cache misses together and is very simple
> to analyze."

**2. Variable-block organization** (thesis, Section 5.8.3):

> "Several cache blocks, all of the size as the largest method, are a waste of
> cache memory. Using smaller block sizes and allowing a method to span over
> several blocks, the blocks become very similar to cache lines. The main
> difference from a conventional cache is that the blocks for a method are all
> loaded at once and need to be consecutive."

**3. FIFO (next-block) replacement** (thesis, Section 5.8.3; `cache_dac2007`):

> "A next pointer indicates the start block for a new function. After loading
> the new function the pointer is incremented by the number of blocks the new
> loaded functions consumes."

LRU was rejected because: "With varying block numbers per method, an LRU
replacement becomes impractical. When the method found to be LRU is smaller
than the loaded method, this new method invalidates two cached methods."

The stack-oriented alternative was rejected because: "if method b() is the
size of one block, all methods can be held in the cache using the next block
policy, but b() and c() would be still exchanged using the stack policy."

**4. Tag-only lookup with data in JBC RAM** (thesis, handbook):

> "The fully loaded method and relative addressing inside a method also result
> in a simpler cache. Tag memory and address translation are not necessary."

The JBC RAM itself serves as the data storage, with the method cache managing
only tags and block allocation.

**5. Default configuration** (tparch, handbook):

> "JOP contains the proposed method cache. The default configuration is 4 KB,
> divided into 16 blocks of 256 Bytes. The replacement strategy is FIFO."

**6. WCET analysis: the "all-fit" approach** (handbook, `wcetana`, `mcana`):

> "Within a loop it is statically analyzed if all methods invoked and the
> invoking method, which contains the loop, fit together in the method cache.
> If this is the case, all methods miss at most once in the loop."

**7. No interference with data access** (thesis, Section 5.8.5):

> "A method cache, with cache fills only on invoke and return, does not
> interfere with data access to the main memory. Data in the main memory is
> accessed with getfield and putfield, instructions that never overlap with
> invoke and return."

**8. Latency hiding** (thesis, handbook):

> "For short methods, the load time of the method on a cache miss, or part of
> it, is hidden by microcode execution."

For `invokestatic`, up to 37 cycles of cache load can be hidden.

## Implementation Overview

The SpinalHDL method cache consists of:

- `spinalhdl/src/main/scala/jop/memory/MethodCache.scala` (155 lines)
  — tag-only lookup with FIFO replacement
- Integration in `BmbMemoryController.scala` (states `BC_CACHE_CHECK`,
  `BC_FILL_R1`, `BC_FILL_LOOP`, `BC_FILL_CMD`)
- JBC RAM as data storage (word write port driven by the memory controller)
- Formal verification in `spinalhdl/src/test/scala/jop/formal/MethodCacheFormal.scala`
- Configuration in `JopCoreConfig.scala` (`jpcWidth=11`, `blockBits=4`)

### Hardware Structure

- **16 blocks** (`blockBits=4`), variable-block organization
- **2 KB total** (`jpcWidth=11`, 2^11 = 2048 bytes)
- **32 words per block** (128 bytes)
- **18-bit tags** per block, plus a valid bit per block
- **FIFO replacement** via `nxt` pointer
- **Pre-computed clear mask** for efficient block eviction

### State Machine

- **IDLE**: Ready for lookup
- **S1**: Parallel tag check — hit returns to IDLE (2-cycle path), miss goes to S2
- **S2**: Evict displaced blocks, write new tag, advance `nxt` — returns to IDLE (3-cycle path)

After S1/S2, the memory controller checks `inCache`:
- **Hit**: `BC_CACHE_CHECK` → IDLE (method already in JBC RAM)
- **Miss**: `BC_CACHE_CHECK` → `BC_FILL_R1` → `BC_FILL_LOOP` (fill method from main memory)

## Mapping: Paper Proposals → Implementation

| Paper Proposal | Implementation | Assessment |
|---|---|---|
| Variable-block organization (N blocks, consecutive) | 16 blocks, methods span 1+ blocks, consecutive allocation | Exact match |
| FIFO (next-block) replacement | `nxt` pointer, advances by `nrOfBlks + 1` on miss | Exact match |
| Tag-only lookup, JBC RAM is data | `MethodCache` manages tags only, JBC write driven by memory controller | Exact match |
| Default 4 KB / 16 blocks | Default `jpcWidth=11` (2 KB) / 16 blocks | Smaller than paper's default — see note below |
| Whole method load on invoke/return | `bcRd` triggers lookup + fill; pipeline issues `bcRd` on invoke/return | Correct |
| No data access interference | Method fill uses BMB bus only during BC_FILL states, no overlap with getfield/putfield | Correct |
| Latency hidden by microcode | Fill runs concurrently with pipeline (pipeline stalls only if JBC RAM not yet ready) | Correct |
| Modulo-wrapping cache address | `blockBits`-width unsigned arithmetic wraps naturally | Correct |
| Pre-computed clear mask | `clrVal` register updated every cycle, used in S2 for one-cycle eviction | Correct |

### Note on Cache Size

The papers describe a default of 4 KB / 16 blocks. The SpinalHDL default is
`jpcWidth=11` (2 KB) / 16 blocks (128 bytes per block). This is half the
paper's default. The `jtres_cache` paper shows that even 1 KB / 8 blocks
outperforms fixed-block caches at 2-4 KB, so 2 KB / 16 blocks is a reasonable
embedded configuration. The `jpcWidth` parameter is configurable.

## What the Implementation Does Well

### 1. Valid Bit Bug Fix

The original VHDL used `tag=0` as an "invalid" marker. This causes false hits
when a method happens to reside at address 0, because evicted blocks (whose
tags are zeroed) would match. The SpinalHDL version adds a separate `tagValid`
bit per block (`MethodCache.scala:64-66`), correctly distinguishing invalid
blocks from methods at address 0.

### 2. Pre-Computed Clear Mask

The VHDL computes the clear mask in a separate clocked process each cycle. The
SpinalHDL version matches this with `clrVal` (`MethodCache.scala:89-93`),
ensuring that when S2 fires, the blocks to evict are already identified with
no combinational delay. The wrapping arithmetic `U(j) - nxt` handles the
modulo ring-buffer correctly.

### 3. Clean Separation of Tag Logic and Data Fill

The `MethodCache` component handles only tag lookup and replacement. The
memory controller handles the actual data transfer (BMB reads → JBC writes).
This matches the papers' design where the tag logic is much simpler than a
conventional cache because there's no address translation.

### 4. Burst Mode Support

The memory controller supports both pipelined single-word reads (`burstLen=0`)
and burst reads (`burstLen=4,8`). The papers noted that the method cache is
well-suited to burst memories because whole methods are loaded at once:

> "This is a result of the complete method transfers when a miss occurs and is
> clearly an advantage for main memory systems with high latency." (jtres_cache)

The burst path issues one burst per batch of `burstLen` words, processing
streaming responses until `rsp.last`, then issuing another burst if more words
remain.

### 5. Pipelined Fill (Non-Burst Path)

In `BC_FILL_LOOP`, the controller attempts to issue the next BMB read command
in the same cycle as processing the previous response (`BmbMemoryController.scala:863-871`). This overlaps read latency with JBC write, minimizing fill
duration.

### 6. Formal Verification

Four BMC properties verified with Z3:

1. `rdy` is true iff state is IDLE
2. Valid state transitions only (IDLE→S1→IDLE or IDLE→S1→S2→IDLE)
3. `find` triggers IDLE→S1 transition
4. `inCache` is stable when not in S1 state

## Potential Issues

### 1. find Not Gated by Method Cache Readiness

`BmbMemoryController.scala:609` issues `mcacheFind := True` when `bcRd` fires
in IDLE, then transitions to `BC_CACHE_CHECK`. The `BC_CACHE_CHECK` state
waits for `methodCache.io.rdy` before reading the result. This is correct.

However, `find` is wired directly as a combinational signal
(`MethodCache.scala:115`), and the method cache transitions to S1 on the same
clock edge. If `bcRd` could somehow fire when the method cache is not in IDLE
(e.g., a second `bcRd` while BC_CACHE_CHECK is waiting), the `find` pulse
would be ignored since the method cache is not in IDLE. The memory controller's
state machine prevents this because `bcRd` is only processed in the IDLE state,
so this is safe — but it relies on the controller never issuing `find` outside
IDLE.

### 2. Last-Match-Wins Tag Check

`MethodCache.scala:128-134` — The parallel tag check uses a `for` loop where
later matches override earlier ones ("last match wins"). If the same method tag
appears in multiple blocks (which shouldn't happen with correct FIFO behavior),
the highest-numbered matching block would be selected. With FIFO replacement,
duplicate tags should be impossible because the old entry is cleared before the
new one is written. However, this invariant is not formally verified.

**Recommendation**: Add a formal property asserting that at most one block is
valid with any given tag value at any time.

### 3. nrOfBlks Computed from Live Input

`MethodCache.scala:80` computes `nrOfBlks` directly from `io.bcLen`, which
changes freely while the method cache is in S1 or S2. The value is used in
S2 for both the clear mask (`clrVal`, updated every cycle) and the FIFO
pointer advance (`nxt := nxt + nrOfBlks + 1`).

The `clrVal` register is updated every cycle from the current `nrOfBlks`, so
if `io.bcLen` changes between S1 and S2, `clrVal` in S2 reflects the new
value, not the value at the time of `find`. Similarly, the FIFO advance in S2
uses the current `nrOfBlks`.

In practice, the memory controller holds `bcFillLen` steady in registers
(`BmbMemoryController.scala:192`), so `io.bcLen` doesn't change during the
lookup. But the method cache itself has no protection against input changes
during S1→S2.

**Recommendation**: Register `nrOfBlks` on `find` (or in S1) to make the
method cache self-contained and robust against input changes.

### 4. Default 2 KB May Be Tight for Complex Applications

The papers evaluated benchmarks where 4 KB / 16 blocks was the default, and
noted:

> "For most benchmarks a method cache size in the range between 4 and 16 KB"
> shows good hit rates. (mcpat)

The `spmvsmc` paper showed that WCET analysis pessimism grows when methods
don't all fit:

> "SPM WCET analysis has no pessimism at all. But the M$ WCET analysis may
> involve pessimism for any program that is larger than the local memory size."

With 2 KB and 16 blocks of 128 bytes each, methods larger than 128 bytes span
multiple blocks, consuming the 16-entry namespace quickly. The threshold for
the all-fit analysis becomes tighter.

This is a configuration choice rather than a bug, but worth noting for users
targeting platforms with more BRAM available.

### 5. Formal Verification Gaps

The 4 existing tests verify state machine structure and output stability but
do not verify:

- **Tag uniqueness**: That no two valid blocks share the same tag
- **FIFO pointer correctness**: That `nxt` advances by exactly the right
  amount and wraps correctly
- **Clear mask correctness**: That `clrVal` correctly identifies the blocks
  being overwritten (not too few, not too many)
- **Hit correctness**: That after a miss + fill, a subsequent `find` with the
  same address produces `inCache=True`

**Recommendation**: Add formal properties for:
- At most one valid block per tag value (prevents the last-match-wins issue
  from being observable)
- After S2, the tag at `nxt_old` is valid with the correct value
- After S2, `nxt_new == nxt_old + nrOfBlks + 1`
- Clear mask sets exactly the right blocks invalid

### 6. No Invalidation Support

The method cache has no `inval` input, unlike the array cache and object
cache. The papers don't discuss method cache invalidation since Java bytecode
is immutable — methods don't change at runtime. This is correct for standard
Java execution.

However, if the system needs to support dynamic class loading or method
replacement (e.g., for debugging or hot-patching), there's no way to flush
the method cache short of resetting the processor. This is unlikely to be a
practical concern for the target embedded real-time domain.

## Comparison with Patmos Method Cache (mcpat)

The `mcpat` paper describes a more advanced method cache for the Patmos
processor with features not present in the JOP version:

| Feature | Patmos (mcpat) | JOP (SpinalHDL) |
|---|---|---|
| Variable-size (no block granularity) | Supported | No — uses fixed blocks |
| Compiler function splitting | Yes — splits large functions | Not applicable (Java methods) |
| Dual-issue support (even/odd banks) | Yes | No — single-issue |
| LRU for fixed-block variant | Discussed | Not implemented (FIFO only) |
| Hardware cost (16 entries, 4 KB) | ~1370 LCs (fixed-block) | ~150 LEs (tag registers only) |

The JOP method cache is intentionally simpler because JOP is a stack machine
executing Java bytecode, where methods are typically small (the thesis reports
average method sizes of 50-100 bytes for embedded applications). The Patmos
method cache targets C code with potentially larger functions and dual-issue
pipelines.

## Alignment with WCET Analysis Framework

The implementation supports the "all-fit" analysis from the publications:

1. **FIFO replacement** enables persistence analysis — if all methods in a
   scope fit in N blocks, each is loaded at most once
2. **Deterministic miss timing** — miss cost depends only on method length
   and memory latency, both statically known
3. **No pipeline interaction** — the cache fill is a bus-level operation,
   not entangled with pipeline state
4. **16 blocks** provides reasonable capacity for the all-fit analysis in
   typical embedded Java programs

The `mcana` scope-based analysis directly applies to this implementation:

> "For all variations of the method cache, a memory block is conflict-free if
> the number of distinct code blocks is less than or equal to the associativity
> of the cache and the total size of all the distinct accessed code blocks is
> less than or equal to the size of the method cache."

## Summary

The SpinalHDL method cache is a faithful implementation of the papers' design,
with a meaningful bug fix (valid bit per block) and clean engineering (separate
tag/data paths, burst support, pipelined fill). The architecture exactly
matches the published variable-block FIFO method cache.

The main concerns are:

1. **nrOfBlks from live input** — not registered on `find`, so theoretically
   vulnerable to input changes during S1→S2 (safe in practice due to
   controller holding inputs steady)
2. **Last-match-wins tag check** — correct only if duplicate tags are
   impossible, which is not formally verified
3. **Formal verification gaps** — tag uniqueness, FIFO correctness, clear
   mask correctness, and hit-after-fill not yet covered
4. **2 KB default** — half the paper's 4 KB default, which may limit all-fit
   analysis scope for larger applications
