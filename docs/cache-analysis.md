# JOP Cache Analysis

## Caches in the Original JOP

The original JOP VHDL has **five categories** of caching:

| Cache | Files | Where | SpinalHDL Status |
|-------|-------|-------|-----------------|
| Method Cache (M$) | `core/cache.vhd` | Inside `mem_sc` | **Yes** — 16-block tag-only, FIFO, pipelined fill + burst |
| Object Cache (O$) | `cache/ocache.vhd` | Inside `mem_sc` | **Yes** — 16-entry fully-associative, FIFO, 8 fields |
| Array Cache (A$) | `cache/acache.vhd` | Not in `mem_sc` | **No** — not implemented |
| Data Cache | `cache/datacache.vhd` | Between `mem_sc` and SimpCon | **No** — not implemented |
| Stack Cache | Stack RAM (256 entries) | `StackStage` | **Yes** — stack buffer |

The method cache, object cache, and stack cache are fully implemented. The array cache and data cache are not implemented.

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

### Per-Word Access Cost by Memory Technology

| | SDR (BL=1) | SDR (BL=4) | DDR2 (BL=4) | DDR3 (BL=8) |
|---|---|---|---|---|
| **Initial latency** | tRCD+CAS = ~5 cyc | tRCD+CAS = ~5 cyc | ~10 cyc @ 200MHz | ~15 cyc @ 400MHz |
| **Initial latency (wall clock)** | 50 ns | 50 ns | 50 ns | 37.5 ns |
| **Per-word after initial** | ~5 cyc (new cmd) | 1 cyc/16-bit | 0.5 cyc/16-bit | 0.25 cyc/16-bit |
| **32-bit word (2x16-bit)** | ~10 cyc | 2 cyc | 1 cyc | 0.5 cyc |
| **Min burst size** | 1 word (2B) | 4 words (8B) | 4 beats (8B) | 8 beats (16B) |
| **32-bit words per burst** | 1 | 2 | 2 | 4 |
| **Cost: 1 word** | ~10 cyc | ~7 cyc | ~11 cyc | ~16 cyc |
| **Cost: 4 words (16B)** | ~40 cyc | ~11 cyc | ~13 cyc | ~16 cyc (1 burst!) |
| **Cost: 8 words (32B)** | ~80 cyc | ~15 cyc | ~15 cyc | ~17 cyc (2 bursts) |

---

## 1. Method Cache (M$) -- Implemented

**Source:** `spinalhdl/src/main/scala/jop/memory/MethodCache.scala`

The method cache uses 16-block tag-only lookup with FIFO replacement, matching the VHDL `mcache` entity. On `bcRd`, the memory controller enters `BC_CACHE_CHECK` to check tags. On hit, bytecode fetch resumes immediately from the cached JBC RAM region. On miss, a pipelined fill loads bytecodes from memory with configurable burst length (BL=4 for SDR SDRAM, BL=8 for DDR3).

### Measured Impact (HelloWorld, SDRAM, 200k cycles)

| Metric | Value |
|--------|-------|
| Total BC fills | 94 |
| Unique methods loaded | **5** |
| Repeated loads (cacheable) | **89** (95%) |
| Average fill duration | 64 cycles |
| **BC fill as % of all stalls** | **47%** |

With the method cache, 89 of 94 fills are hits, saving ~5,700 cycles.

---

## 2. Object Cache (O$) -- Implemented

**Source:** `spinalhdl/src/main/scala/jop/memory/ObjectCache.scala`

The object cache is a fully-associative FIFO cache with 16 entries, each storing 8 fields (128 values total). It sits inside `BmbMemoryController`:

- **Getfield hit**: Returns cached data immediately (0 busy cycles, stays in IDLE)
- **Getfield miss**: Normal HANDLE_READ path + cache fill on return
- **Putfield**: Always through state machine, write-through on hit
- **Invalidation**: `stidx`/`cinval` clears all valid bits
- **I/O suppression**: `wasHwo` flag prevents caching HardwareObject fields
- **Cacheable**: Only fields 0-7 (upper fieldIdx bits must be 0)

---

## 3. Array Cache (A$) -- Not Implemented

**Source (VHDL):** `vhdl/cache/acache.vhd` (438 lines)

### What It Does

Caches array element values to avoid handle-dereference on repeated `iaload`/`iastore`. Same principle as the object cache but for array access patterns (loops iterating over arrays).

### Architecture (VHDL Original)

- **Fully-associative**, 16 entries (`ACACHE_WAY_BITS=4`)
- **FIFO replacement**
- Each entry caches one array handle with **4 elements** per line (`ACACHE_FIELD_BITS=2`)
- Single valid bit per entire line (not per-element like O$)
- Tags include both handle address AND upper index bits (so different regions of the same array map to different lines)
- Total storage: 16 lines x 4 elements x 32 bits = **256 bytes** of data + tags

### Adaptation for Memory Technology

| | SDR (BL=1) | SDR (BL=4) | DDR2 (BL=4) | DDR3 (BL=8) |
|---|---|---|---|---|
| **4-elem line fill** | 4 x ~10 = 40 cyc | 2 bursts = ~14 cyc | 2 bursts = ~22 cyc | 1 burst = ~16 cyc |
| **8-elem line fill** | 8 x ~10 = 80 cyc | 4 bursts = ~22 cyc | 4 bursts = ~26 cyc | 2 bursts = ~17 cyc |
| **Spatial locality benefit** | Low (high fill cost) | Medium | Medium | **High** (cheap fill) |

---

## 4. Data Cache -- Not Implemented (Not Planned)

**Source (VHDL):** `vhdl/cache/datacache.vhd` + `directmapped.vhd` + `fifo.vhd` + `lru.vhd`

A general-purpose SimpCon-level cache that sits between the memory controller and main memory. Not planned for SpinalHDL because:

1. The O$ and A$ are more targeted and effective at the semantic level
2. BMB bus supports cache-aware controllers natively (e.g., `BmbCacheBridge` for DDR3)
3. O$ + A$ cover the main use cases (getfield/putfield and iaload/iastore)

---

## 5. Burst Transfers -- Implemented

**Source:** `BmbMemoryController.scala` (BC fill states), `BmbSdramCtrl32.scala`

Burst transfers are implemented via `JopMemoryConfig(burstLen=N)`:
- **burstLen=0**: Single-word reads (BRAM)
- **burstLen=4**: SDR SDRAM (4-word burst)
- **burstLen=8**: DDR3 (8-word burst)

The BC fill uses pipelined states: `BC_FILL_R1` issues burst cmd, `BC_FILL_LOOP` processes beats via `rsp.last`. `BmbSdramCtrl32` is burst-aware (2*N SDRAM cmds per burst).

---

## Summary: What Remains

### Not Yet Implemented

| What | Effort | Benefit |
|------|--------|---------|
| **Array Cache (A$)** | ~250 lines | Faster iaload/iastore in loops |
| **Null pointer detection** | ~30 lines | Hardware NPE (infrastructure exists, checks disabled) |
| **Array bounds checking** | ~30 lines | Hardware AIOOBE |

### Completed

| What | When |
|------|------|
| Method cache tags (16-block FIFO) | Done |
| Object cache (16x8 fields, FIFO) | Done |
| Burst BC fill (pipelined, configurable) | Done |
| Hardware memCopy (GC) | Done |

## Resource Estimates (Cyclone IV GX)

| Cache | Logic Elements | Block RAM bits | BRAM blocks (M9K) |
|-------|---------------|----------------|-------------------|
| A$ (16x4 elements) | ~350 LEs | 8,192 (256B data) | 1 |
| Tags | ~150 LEs | 0 (registers) | 0 |
| **Total (A$ only)** | **~500 LEs** | **8,192 bits** | **1** |

The EP4CGX150 has 149,760 LEs and 720 M9K blocks -- these would use <1% of resources.

## WCET Considerations

Both O$ and A$ use **FIFO replacement**, which is important for JOP's real-time guarantees:
- FIFO is fully predictable -- you know exactly which line will be replaced
- Cache analysis can determine worst-case miss counts statically
- This is why LRU is available in the data cache but O$/A$ use FIFO specifically
- Burst fill makes WCET analysis slightly more complex (fill time depends on burst length) but is still fully deterministic
