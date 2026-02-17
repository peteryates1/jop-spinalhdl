# JOP Cache Analysis: What to Add to SpinalHDL

## Caches in the Original JOP

The original JOP VHDL has **five categories** of caching:

| Cache | Files | Where | Already in SpinalHDL? |
|-------|-------|-------|----------------------|
| Method Cache (M$) | `core/cache.vhd` | Inside `mem_sc` | **Partial** — fill works, but no tag lookup (always reloads) |
| Object Cache (O$) | `cache/ocache.vhd` | Inside `mem_sc` | **No** |
| Array Cache (A$) | `cache/acache.vhd` | Not in `mem_sc` (separate, later addition) | **No** |
| Data Cache | `cache/datacache.vhd` | Between `mem_sc` and SimpCon memory | **No** |
| Stack Cache | Stack RAM (256 entries) | `StackStage` | Yes (stack buffer) |

The stack cache is fully implemented. The method cache fill mechanism works but is missing the critical tag-lookup optimization. The three missing caches plus the method cache optimization are analyzed below.

---

## Memory Technology Cost Model

The cache design must be tailored to the memory technology. The key insight: **initial access latency dominates, and burst transfers amortize that cost across multiple words.** Larger cache lines become more valuable as the ratio of initial-latency to per-word-burst-cost increases.

### Measured Access Costs (Current: W9825G6JH6 SDR SDRAM, 100 MHz)

Our `BmbSdramCtrl32` splits each 32-bit access into two 16-bit SDRAM operations. From simulation logs (200k cycles of HelloWorld):

| Operation | Cycles | Count | Notes |
|-----------|--------|-------|-------|
| Simple read (rd/rdc) | 1-6 | 1,421 | Dominated by row-open latency |
| Handle deref (getfield/iaload) | 10-21 | 159 | Handle read + field read, two sequential SDRAM accesses |
| BC fill (method load) | 40-101 | 94 | Sequential word-by-word reads, scales with method size |
| **Average busy duration** | **7.7** | 1,674 | Across all memory operations |

A single 32-bit SDRAM read at 100 MHz with CAS=3:
- **Row open (best case):** CAS(3) × 2 halves + overhead ≈ **6-8 cycles**
- **Row miss:** tRP(2) + tRCD(2) + CAS(3) × 2 halves + overhead ≈ **12-16 cycles**
- **getfield (2 sequential reads):** ≈ **17-33 cycles** total

### Per-Word Access Cost by Memory Technology

| | SDR (BL=1) | SDR (BL=4) | DDR2 (BL=4) | DDR3 (BL=8) |
|---|---|---|---|---|
| **Initial latency** | tRCD+CAS = ~5 cyc | tRCD+CAS = ~5 cyc | ~10 cyc @ 200MHz | ~15 cyc @ 400MHz |
| **Initial latency (wall clock)** | 50 ns | 50 ns | 50 ns | 37.5 ns |
| **Per-word after initial** | ~5 cyc (new cmd) | 1 cyc/16-bit | 0.5 cyc/16-bit | 0.25 cyc/16-bit |
| **32-bit word (2×16-bit)** | ~10 cyc | 2 cyc | 1 cyc | 0.5 cyc |
| **Min burst size** | 1 word (2B) | 4 words (8B) | 4 beats (8B) | 8 beats (16B) |
| **32-bit words per burst** | 1 | 2 | 2 | 4 |
| **Wasted if only need 1 word** | 0% | 50% | 50% | 75% |
| **Cost: 1 word** | ~10 cyc | ~7 cyc | ~11 cyc | ~16 cyc |
| **Cost: 4 words (16B)** | ~40 cyc | ~11 cyc | ~13 cyc | ~16 cyc (1 burst!) |
| **Cost: 8 words (32B)** | ~80 cyc | ~15 cyc | ~15 cyc | ~17 cyc (2 bursts) |

The pattern is clear: **as memory technology advances, the penalty for NOT filling a burst is severe, and the marginal cost of additional words within a burst approaches zero.**

### What This Means for Cache Line Size

| Memory Tech | Optimal O$ line size | Optimal A$ line size | Why |
|---|---|---|---|
| **SDR, BL=1** (current) | 8 fields (original) | 4 elements (original) | No burst — each word costs the same. Bigger lines waste time filling. |
| **SDR, BL=4** | 8 fields (32B = 4 bursts) | 4 elements (16B = 2 bursts) | Burst helps but doesn't dominate. Original sizes good. |
| **DDR2, BL=4** | 8 fields (32B) | 8 elements (32B) | Increase A$ to match burst economics. O$ already good. |
| **DDR3, BL=8** | 16 fields (64B = 4 bursts) | 8-16 elements (32-64B) | Burst is mandatory — **must** read 16B minimum. Larger lines waste nothing. |

---

## 1. Method Cache (M$) Tag Lookup — Recommended: YES, highest priority

**Source:** `vhdl/core/cache.vhd` (214 lines)

### The Problem

Our SpinalHDL `BmbMemoryController` **always reloads bytecodes from SDRAM on every `bcRd`** (method invoke). The original VHDL has a `mcache` module that checks whether the method is already in the JBC RAM and skips the fill entirely.

This is the single biggest performance issue in SDRAM mode.

### Measured Impact (HelloWorld, SDRAM, 200k cycles)

From simulation analysis of the 19,313-cycle execution window after boot:

| Metric | Value |
|--------|-------|
| Total BC fills | 94 |
| Unique methods loaded | **5** |
| Repeated loads (cacheable) | **89** (95%) |
| Average fill duration | 64 cycles |
| Total BC fill cycles | **6,021** |
| Total busy cycles (all ops) | 12,884 |
| **BC fill as % of all stalls** | **47%** |

The same 3 methods are loaded over and over:
- Method at word 104: loaded **46 times** (67 cycles each)
- Method at word 96: loaded **22 times**
- Method at word 197: loaded **22 times**

With a proper method cache tag lookup, 89 of 94 fills would be hits — **saving ~5,700 cycles**, nearly halving total stall time.

### How VHDL Does It (mcache)

The VHDL `mcache` entity is a **multi-block, fully-associative tag memory with FIFO replacement**:

```
bcRd arrives → bc_cc state (cache check)
  → mcache.find = 1 (start tag lookup)
  → mcache checks all block tags in parallel (1 cycle: s1 state)
  → HIT (in_cache=1): return bcstart address, go to IDLE (0 memory accesses!)
  → MISS (in_cache=0): update tags, proceed to bc_r1 → fill from memory as usual
```

Architecture:
- **2^block_bits blocks** (typically 32 blocks with `block_bits=5`)
- Cache size = **2^jpc_width bytes** (typically 4KB with `jpc_width=12`)
- Each block holds one segment of bytecodes (block_size = cache_size / blocks)
- Methods can span **multiple consecutive blocks** (nr_of_blks = method_length / block_size)
- **FIFO replacement** — `nxt` pointer wraps around, multi-block methods consume consecutive blocks
- Tag memory: 32 entries × 18-bit tags (method address in main memory)
- On miss: allocate blocks starting at `nxt`, clear old tags for consumed blocks, advance `nxt`

Key design: the JBC RAM itself IS the cache data — the `mcache` module only manages the tags. The bytecodes already live in JBC RAM; the tag lookup just determines whether they're still valid.

### What Our SpinalHDL Is Missing

Our `BmbMemoryController` has the BC fill state machine (`BC_READ` → `BC_WAIT` → `BC_WRITE` loop) but **no tag memory at all**. Every `bcRd` unconditionally enters the fill loop.

The `BytecodeFetchStage` has a JBC RAM that gets overwritten on every fill. The bcStart address is always reset to 0.

### Adaptation for Memory Technology

The method cache tag lookup is **pure logic** — no memory accesses, no bus transactions. The hit check takes 1-2 cycles regardless of memory technology. The benefit scales with how expensive a miss (fill) is:

| Memory Tech | Fill cost (avg method) | Hit cost | Speedup per hit |
|---|---|---|---|
| **BRAM** | ~0 (single-cycle BRAM) | 1-2 cycles | Negligible — BRAM fills are cheap |
| **SDR SDRAM (current)** | **64 cycles** | 1-2 cycles | **32-64×** |
| **DDR2** | ~40 cycles (with burst) | 1-2 cycles | **20-40×** |
| **DDR3** | ~30 cycles (with burst) | 1-2 cycles | **15-30×** |

The method cache tag becomes **more important as memory latency increases**. It's essential for any external memory.

### Impact on Cache Line Size / Burst

The method cache doesn't have "lines" in the traditional sense — it caches entire methods. But the fill operation benefits enormously from burst transfers:

| Fill strategy | SDR BL=1 (current) | SDR BL=4 | DDR3 BL=8 |
|---|---|---|---|
| 8-word method fill | 16 cmds × ~6 = ~96 cyc | 4 bursts × ~8 = ~32 cyc | 1 burst = ~16 cyc |
| 32-word method fill | 64 cmds × ~6 = ~384 cyc | 16 bursts × ~8 = ~128 cyc | 4 bursts = ~20 cyc |

Burst + tag lookup together: the tag check eliminates 95% of fills, and burst makes the remaining 5% 3× faster. Combined improvement for BC fill: **~60× fewer stall cycles**.

### Integration Plan for SpinalHDL

Two independent improvements that can be done separately:

**Step 1: Tag lookup (highest priority)**

1. **New file: `spinalhdl/src/main/scala/jop/memory/MethodCacheTag.scala`**
   - Fully-associative tag memory matching VHDL `mcache`
   - Configurable: `blockBits` (number of blocks), `tagWidth` (address bits)
   - Interface: `find` trigger, `bcAddr`/`bcLen` input, `inCache`/`bcStart` output

2. **Modify `BmbMemoryController.scala`**:
   - On `bcRd`: trigger tag lookup instead of immediately entering `BC_READ`
   - New state `BC_CHECK`: wait for tag result (1 cycle)
   - **Hit**: set `bcStartReg` from tag output, return to IDLE
   - **Miss**: proceed to `BC_READ` fill loop as before, update tags on completion
   - bcStart output must now reflect the block offset within JBC RAM (not always 0)

3. **Modify `BytecodeFetchStage.scala`**:
   - JBC RAM must be large enough for multiple methods (currently only sized for one)
   - bcStart from memory controller selects which region of JBC RAM to read from

**Step 2: Burst fill (lower priority, benefits the 5% miss case)**

- Modify `BmbMemoryController` BC fill to issue burst reads
- Requires burst-capable SDRAM controller (see Section 5)
- Each burst fills 2-4 words per command instead of 1

### Resource Estimate

| Component | Logic Elements | Block RAM |
|-----------|---------------|-----------|
| Tag memory (32 × 18-bit) | ~200 LEs | 0 (registers) |
| Tag comparators (32-way parallel) | ~300 LEs | 0 |
| JBC RAM increase (2KB → 4KB) | 0 | 2 M9K blocks |
| **Total** | **~500 LEs** | **2 M9K** |

**Estimated complexity: ~150 lines SpinalHDL** for the tag module + ~50 lines modifications to BmbMemoryController.

---

## 2. Object Cache (O$) — Recommended: YES

**Source:** `vhdl/cache/ocache.vhd` (446 lines)

### What It Does

Caches object field values to avoid the full handle-dereference + memory-read sequence on repeated `getfield` access. Without it, every `getfield` requires:

1. Read handle → get data pointer (1 memory access, ~8-16 cycles on SDRAM)
2. Read data_ptr + field_index → get field value (1 memory access, ~8-16 cycles)
3. Total: **~17-33 cycles stall** (measured average: 17 cycles for handle-deref)

With the object cache, a cache hit returns the field value in **zero memory accesses** — the pipeline sees no stall at all.

### Architecture (VHDL Original)

- **Fully-associative**, 16 entries (configurable via `OCACHE_WAY_BITS=4`)
- **FIFO replacement** — simple, deterministic, WCET-analyzable
- Each entry caches one object handle with **8 fields** per line (`OCACHE_INDEX_BITS=3`)
- Per-field valid bits (can cache field 0 and field 3 without field 1 and 2)
- Total storage: 16 lines × 8 fields × 32 bits = **512 bytes** of data + 16 × 23-bit tags
- Tag comparison against all 16 entries in parallel (combinational)

### How It Integrates (in VHDL `mem_sc`)

The object cache sits **inside the memory controller**, not on the bus:

```
getfield arrives
  → O$ tag check (combinational, same cycle)
  → HIT: return cached value immediately, skip state machine entirely
  → MISS: run normal HANDLE_READ → HANDLE_WAIT → HANDLE_CALC → HANDLE_ACCESS
          then update O$ with the fetched value on return to IDLE
```

Key signals:
- `chk_gf`: Triggers tag lookup on `getfield` (not on stidx-preceded native access)
- `wr_gf`: Updates cache on getfield miss (when returning to IDLE from gf4 state)
- `chk_pf`: Checks putfield for write-update (no write-allocate)
- `wr_pf`: Updates cache on putfield hit (write-through)
- `inval`: Invalidates entire cache on `stidx` or `cinval` (native field access, GC)
- `read_ocache`: MUX select — when hit, `mem_out.dout` comes from O$ instead of memory

### Adaptation for Memory Technology

The O$ is **technology-independent in its basic form** — it eliminates memory accesses entirely on hits. On misses, the question is whether to fill additional fields speculatively:

| Strategy | SDR (BL=1) | DDR3 (BL=8) |
|----------|-----------|-------------|
| **Fill single field** (current VHDL) | Good — only pay for what you need | Wastes 75% of each burst |
| **Fill full line (8 fields)** on miss | 8 extra reads (expensive!) | 2 bursts — marginal cost ~2 cycles |
| **Fill burst-aligned chunk** | N/A | Fill 4 fields per burst = perfect fit |

**Recommendation:** Make the fill strategy configurable:
- **SDR:** Fill only the requested field (matches VHDL). Per-field valid bits handle this.
- **DDR2/DDR3:** Fill a burst-sized chunk (4 words for DDR3). The burst data arrives "for free" since we've already paid the initial latency. Use per-field valid bits to track what's filled.

The line size (8 fields) is already well-suited because most Java objects have <8 instance fields. For DDR3, could increase to 16 fields to match two 8-beat bursts = 64 bytes, but that risks over-fetching for small objects. Better to keep 8 and fill via 2 bursts on miss.

### Integration Plan for SpinalHDL

Add the object cache **inside `BmbMemoryController`**, matching the VHDL architecture:

1. **New file: `spinalhdl/src/main/scala/jop/memory/ObjectCache.scala`**
   - `ObjectCacheTag`: Fully-associative tag memory (16 entries, FIFO replacement)
   - `ObjectCache`: Tag + data RAM, hit/miss detection, field-level valid bits
   - Parameterized: `wayBits`, `indexBits`, `fillStrategy` (single-field vs burst-fill)

2. **Modify `BmbMemoryController.scala`**:
   - On `getfield` (non-stidx): check O$ tag combinationally
   - **Hit**: return cached data immediately via `rdDataReg`, stay in IDLE (no state machine entry)
   - **Miss**: proceed to `HANDLE_READ` as before; on return to IDLE, write fetched data into O$
   - On `putfield` hit: update O$ data (write-through, no write-allocate)
   - On `stidx` or `cinval`: invalidate entire cache

3. **Configuration**: Add `useObjectCache: Boolean = true` and cache parameters to `JopMemoryConfig`

**Estimated complexity: ~200 lines SpinalHDL** (tag + data RAM + integration)

---

## 3. Array Cache (A$) — Recommended: YES (lower priority than O$)

**Source:** `vhdl/cache/acache.vhd` (438 lines)

### What It Does

Caches array element values to avoid handle-dereference on repeated `iaload`/`iastore`. Same principle as the object cache but for array access patterns (loops iterating over arrays).

### Architecture (VHDL Original)

- **Fully-associative**, 16 entries (`ACACHE_WAY_BITS=4`)
- **FIFO replacement**
- Each entry caches one array handle with **4 elements** per line (`ACACHE_FIELD_BITS=2`)
- Single valid bit per entire line (not per-element like O$)
- Tags include both handle address AND upper index bits (so different regions of the same array map to different lines)
- Total storage: 16 lines × 4 elements × 32 bits = **256 bytes** of data + tags
- On miss, fills the entire 4-element line sequentially (incrementing `idx_reg`)

### Adaptation for Memory Technology

The A$ line fill is where memory technology matters most. Unlike O$ (which fills one field), A$ fills an entire line on miss:

| | SDR (BL=1) | SDR (BL=4) | DDR2 (BL=4) | DDR3 (BL=8) |
|---|---|---|---|---|
| **4-elem line fill** | 4 × ~10 = 40 cyc | 2 bursts = ~14 cyc | 2 bursts = ~22 cyc | 1 burst = ~16 cyc |
| **8-elem line fill** | 8 × ~10 = 80 cyc | 4 bursts = ~22 cyc | 4 bursts = ~26 cyc | 2 bursts = ~17 cyc |
| **Spatial locality benefit** | Low (high fill cost) | Medium | Medium | **High** (cheap fill) |

**Key takeaway: larger A$ lines become drastically cheaper with burst-capable memory.**

For SDR without burst (our current setup), the original 4-element lines are reasonable — filling 4 words costs ~40 cycles, which is only worthwhile if you'll hit at least 2 of the 4 elements later.

For DDR3, an 8-element line fill costs almost the same as a 4-element fill (both fit in 1-2 bursts). The spatial locality benefit doubles for free.

**Recommendation:**
- **SDR (current):** 4 elements per line (match VHDL). Each miss fills the full line via 4 sequential reads.
- **DDR2:** 4 or 8 elements, depending on burst efficiency
- **DDR3:** 8 elements per line (32 bytes = 2 bursts). Fill is nearly free after initial latency.

### Integration (similar to O$)

Would sit inside `BmbMemoryController`:
- On `iaload`: check A$ tag → hit returns immediately, miss runs state machine then fills cache line
- On `iastore` hit: update cached value (write-through)
- On `cinval`: invalidate

**Estimated complexity: ~250 lines SpinalHDL** (slightly more complex due to multi-element fill)

---

## 4. Data Cache — Recommended: NO (for now), revisit for DDR3

**Source:** `vhdl/cache/datacache.vhd` + `directmapped.vhd` + `fifo.vhd` + `lru.vhd`

### What It Does

A general-purpose SimpCon-level cache that sits **between the memory controller and main memory** (SDRAM). It caches arbitrary memory reads/writes using a configurable cache implementation:
- `direct_mapped`: Standard direct-mapped cache
- `direct_mapped_const`: For read-only data (class constants)
- `full_assoc` (LRU): For getfield/putfield/iaload/iastore data
- `bypass`: Pass-through (no caching)

The memory controller selects which cache type to use per-access via the `sc_mem_out.cache` signal.

### Why NOT Add It (Yet)

1. **The O$ and A$ are more targeted and effective.** They cache at the semantic level (object fields, array elements) with knowledge of JOP's handle-based addressing. The data cache is a traditional address-based cache that doesn't understand handles.

2. **Complexity is high.** The data cache is a full cache subsystem with multiple implementations, MUX logic, and coherency concerns. The `datacache.vhd` alone is 170 lines, plus each implementation is 200-400 lines.

3. **BMB already handles this differently.** In the SpinalHDL design, the BMB bus can support cache-aware controllers (e.g., `BmbSdramCtrl` with burst support). Adding burst transfers at the BMB level would be more natural than a SimpCon-level data cache.

4. **O$ + A$ cover the main use cases.** The data cache in VHDL is primarily used for getfield/putfield (routed through `full_assoc`) and getstatic/putstatic (routed through `direct_mapped`). With O$ and A$ handling the first case, only getstatic/putstatic remain uncached — and those are much less frequent.

### When It Would Make Sense

For **DDR3**, the economics change:
- Every read returns 16 bytes minimum — you're fetching 4 words whether you want them or not
- A simple line buffer (cache the last DDR3 burst) could be nearly free and would help sequential access patterns
- A proper write-back data cache with burst fills could significantly improve throughput for `getstatic`/`putstatic` and other non-handle memory accesses

But this should be a **modern BMB-level cache** designed around burst transactions, not a port of the SimpCon-level `datacache.vhd`.

---

## 5. Burst Transfers — Critical enabling technology

Before or alongside cache implementation, enabling **SDRAM burst mode** would improve ALL memory operations, not just cached ones.

### Current Situation

SpinalHDL's `SdramCtrl` configures the SDRAM mode register with **BL=1** (single-beat). Every word requires a separate command. Our `BmbSdramCtrl32` makes this worse by splitting each 32-bit access into two 16-bit commands.

The W9825G6JH6 supports burst lengths of 1, 2, 4, 8, and full page.

### Impact on Existing Operations

| Operation | Current (BL=1) | With BL=4 | Improvement |
|-----------|----------------|-----------|-------------|
| Simple 32-bit read | 2 cmds × ~6 cyc = ~12 cyc | 1 cmd + 4 beats = ~8 cyc | 1.5× |
| getfield (2 reads) | ~24-33 cyc | ~16-20 cyc | 1.5-1.7× |
| BC fill (32 words) | 64 cmds × ~6 = ~384 cyc | 16 bursts × ~8 = ~128 cyc | **3×** |
| A$ line fill (4 words) | 8 cmds × ~6 = ~48 cyc | 2 bursts × ~8 = ~16 cyc | **3×** |

### Impact on Cache Design

With burst mode enabled:
- **O$ miss fill** could fetch multiple fields in one burst, filling cache line faster
- **A$ miss fill** benefits enormously — 4-element line fills in 2 bursts instead of 8 commands
- **BC fill** (method cache loading) would see the biggest absolute improvement

### Recommendation

**Enable SDRAM burst mode (BL=4) as a prerequisite for cache work.** This requires modifying `BmbSdramCtrl32` to issue burst reads/writes instead of individual commands. The burst data naturally maps to cache line fills.

For DDR2/DDR3, burst is mandatory (minimum BL=4 for DDR2, BL=8 for DDR3), so this work has to happen anyway for those targets.

---

## Summary: Implementation Roadmap by Memory Target

### SDR SDRAM (current W9825G6JH6)

| Priority | What | Cycles saved | Effort |
|----------|------|-------------|--------|
| 1 | **Method Cache tags (M$)** — 32-block tag lookup, skip fill on hit | **~5,700 cyc** (47% of all stalls eliminated) | ~200 lines |
| 2 | **Object Cache (O$)** — 16 entries × 8 fields, single-field fill | ~17-33 per getfield | ~200 lines |
| 3 | **Burst mode (BL=4)** — modify BmbSdramCtrl32 | ~3× faster fills (BC, A$) | ~150 lines |
| 4 | **Array Cache (A$)** — 16 entries × 4 elements, full-line fill | ~17-33 per iaload | ~250 lines |

### DDR2 SDRAM (future boards)

| Priority | What | Notes |
|----------|------|-------|
| 1 | **M$ tags** — same as SDR, even more critical with higher latency | Tag hit eliminates ~40-cycle fills |
| 2 | **O$** — 16×8 fields, burst-fill on miss (BL=4 → 2 fields per burst) | Burst fill is mandatory (DDR2 BL=4 minimum) |
| 3 | **A$** — 16×8 elements (doubled from SDR), burst-fill | Larger line justified by cheap burst fill |
| 4 | **BC fill burst optimization** | Method load 3× faster on miss |

### DDR3 SDRAM (Artix-7 / Alchitry Au)

| Priority | What | Notes |
|----------|------|-------|
| 1 | **M$ tags** — essential, fills cost ~30 cycles even with burst | Eliminates 95% of fills |
| 2 | **O$** — 16×16 fields, burst-fill (BL=8 → 4 fields per burst) | DDR3 BL=8 delivers 16B per burst — fill 4 fields at once |
| 3 | **A$** — 16×8 elements, burst-fill | 32B line = 2 DDR3 bursts, fills nearly instantly |
| 4 | **Line buffer / simple data cache** | Cache last DDR3 burst — often satisfies next read for free |
| 5 | **BC fill burst optimization** | Method load ~5× faster on miss |

## Resource Estimates (Cyclone IV GX)

| Cache | Logic Elements | Block RAM bits | BRAM blocks (M9K) |
|-------|---------------|----------------|-------------------|
| O$ (16×8 fields) | ~400 LEs | 16,384 (512B data) | 2 |
| A$ (16×4 elements) | ~350 LEs | 8,192 (256B data) | 1 |
| Tags (both) | ~300 LEs | 0 (registers) | 0 |
| **Total** | **~1,050 LEs** | **24,576 bits** | **3** |

The EP4CGX150 has 149,760 LEs and 720 M9K blocks — these caches would use <1% of resources. For DDR3 with larger lines, double the BRAM estimate — still trivial.

## WCET Considerations

Both O$ and A$ use **FIFO replacement**, which is important for JOP's real-time guarantees:
- FIFO is fully predictable — you know exactly which line will be replaced
- Cache analysis can determine worst-case miss counts statically
- This is why LRU is available in the data cache but O$/A$ use FIFO specifically
- The SpinalHDL implementation should preserve FIFO replacement for WCET compatibility
- Burst fill makes WCET analysis slightly more complex (fill time depends on burst length) but is still fully deterministic
