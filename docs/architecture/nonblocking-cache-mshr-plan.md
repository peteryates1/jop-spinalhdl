# Non-blocking L2: MSHR plan for `LruCacheCore`

**Status**: implemented and **measured on hardware** (2026-08-17). Off by
default (`JopMemoryConfig.l2MshrCount = 1`). The eight-core DDR2 ceiling moved
from **682 to 1613 kacc/s, 2.37x** — see [Hardware results](#hardware-results).

## Why

The DRAM multicore scaling ceiling is `LruCacheCore`'s serial miss handling. It
is a single flat FSM that accepts a request **only in `IDLE`**:

```
IDLE → TAG_COMPARE → CHECK_HIT → (ISSUE_EVICT → WAIT_EVICT_RSP →)
       ISSUE_REFILL → WAIT_REFILL_RSP → IDLE
```

No MSHR, no overlap: every miss holds the FSM for a full DRAM round trip, behind
a 4-deep `cmdFifo`. Measured with `JbeScale` (stride walk that defeats the 32 KB
L2, so nearly every access misses):

| cores | DDR2 @75 | DDR3 @91.68 | SDR @100 (no L2) |
|---|---|---|---|
| 1 | 1.00× | 1.00× | 1.00× |
| 4 | 1.75× | 1.70× | **3.37×** |
| 8 | **1.81×** | **1.75×** | **4.45×** |

Two completely different DRAM backends give the same curve within 3-4 %, while
SDR — which has no `LruCacheCore` — keeps scaling. See `java/apps/JbeBench/README.md`.

**What the 1.81× actually is.** It is not memory concurrency. With one core,
throughput is `1/(compute + miss_latency)`; adding cores fills the compute gaps
until the memory system is continuously busy, giving `1/miss_latency`. That is
the one-outstanding-miss ceiling, and 1.81× implies compute ≈ 0.8 × latency.

## De-risking already done

`Ddr2ConcurrencyProbe` (`f608cf0`) drives `CacheToDdr2Adapter` directly against a
behavioural ALTMEMPHY local interface, sweeping latency so the answer does not
rest on one guessed number:

| modelled latency | k=1 | k=2 | k=4 | k=8 | gain |
|---|---|---|---|---|---|
| 10 | 15.00 | 7.50 | 3.76 | 1.89 | 7.93× |
| 20 | 25.00 | 12.50 | 6.26 | 3.14 | 7.96× |
| 40 | 45.00 | 22.50 | 11.26 | 5.64 | 7.98× |
| 80 | 85.00 | 42.50 | 21.26 | 10.64 | 7.99× |

`cycles/req ≈ (latency + 5)/k`. **The backend pipelines nearly perfectly** up to
`rspDepth = 8`; at k=8/latency=10 the floor becomes the command *issue* rate.

Bandwidth headroom: 682 kacc/s × 64 B per access (32 B refill + 32 B eviction)
is ~44 MB/s against the 1.2 GB/s the DDR2 exerciser measured — **~3.6 %**.

## What is already fine (verified, do not change)

- **`CacheToDdr2Adapter`.** Already supports `rspDepth = 8` outstanding reads and
  exposes `debugOutstanding`. One response per command, in order, including
  writes.

  **CORRECTION (2026-08-17):** the claim that `CacheToMigAdapter` "likewise
  pipelines" was wrong, and it matters. It pipelines *writes* — issued from
  `IDLE` at one per cycle — but **serialises reads**: `IDLE → ISSUE_READ →
  WAIT_READ → IDLE`, latching a single `activeCmd` and waiting for
  `app_rd_data_valid` before looking at the next command
  (`CacheToMigAdapter.scala:105-144`). So on the DDR3/MIG boards an MSHR file
  buys nothing until that adapter is made multi-outstanding too. DDR2 is the
  platform where this pays off today, which is also where the plan's target
  board is. Nothing was broken by the assumption — the DDR3 path is simply left
  at `mshrCount = 1`.
- **`BmbArbiter`.** A thin wrapper: `StreamArbiter` on cmd appending the chosen
  input to `source`, and a **purely combinational** rsp demux
  (`memory_rspSel = io_output_rsp_payload_fragment_source`). No FIFO, no pending
  counter, and `cmd_ready` is not gated on outstanding responses. Multiple
  outstanding across cores, out of order, already works.
- **Per-source ordering is free.** `BmbMemoryController` stalls in `READ_WAIT`
  *and* `WRITE_WAIT` until the response arrives, so each core has **exactly one**
  outstanding BMB transaction. BMB only requires in-order responses per source,
  so cross-core out-of-order completion is legal and **no reorder buffer is
  needed**.

## What must change

### 1. `CacheFrontend` — add a request `id`

`CacheReq`/`CacheRsp` carry no identifier, so out-of-order responses cannot be
matched. Width = `log2Up(mshrCount)`; default 0 keeps existing single-outstanding
users working.

### 2. `BmbCacheBridge` — N outstanding

Today it allows **one**: a single `pendingRsp` with a single
`pendingSource`/`pendingContext`/`pendingLaneSelect`, and while `pendingRsp` is
set it takes the `otherwise` branch and never asserts `io.bmb.cmd.ready`
(`BmbCacheBridge.scala:211`). **An MSHR in the cache alone would therefore buy
nothing** — the cache would never see two concurrent requests.

Replace those three registers with a table indexed by `id`, allocate a free id
per accepted command, and look the entry up on response. The burst-read path is
inherently sequential; leave it alone. `BmbCacheBridgeFormal` needs updating.

**Testable on its own**: N outstanding through the bridge against a trivially
fast cache stub should show N-way overlap. Do this before the MSHR.

### 3. `LruCacheCore` — the MSHR file

- N entries, each holding the set/way being filled plus, per waiter, the
  requester `id`, word offset, read/write, and write data/mask.
- **Order queue for `memRsp`.** Responses arrive in issue order and *evictions
  also consume one*, so the queue must record both kinds. Do NOT assume reads
  only — that is precisely the `AlteraSdramAdapter` bug (`ef36d99`), where
  locally-made write responses overtook outstanding reads matched by order.
- **Set-conflict hazard**: two outstanding misses to the same index could select
  the same victim way. Simplest correct policy is to stall a request whose index
  matches an in-flight MSHR.
- **Secondary hits**: a request to a line already being filled must attach to
  that MSHR rather than issue a second fill.
- **BRAM port arbitration**: `bramReadAddr` is a single port shared by lookup and
  fill; a new lookup must not collide with a fill write to the same address.
- Return to `IDLE` after `ISSUE_REFILL` instead of waiting.

## Expected gain, and what binds first

A non-blocking miss still *occupies* the FSM ~6 cycles (`IDLE`, `TAG_COMPARE`,
`CHECK_HIT`, `ISSUE_EVICT`, `ISSUE_REFILL`, fill-write). At the ~110-cycle
hardware miss latency, `latency/occupancy ≈ 18`, so **the MSHR count binds
first** and the win is about `min(k, 18)`.

Projection for 8-core DDR2: **2.7-5.5 Macc/s** against today's 682 k, which would
put a DRAM path ahead of SDR's 2764 k instead of 4× behind. A projection, not a
promise — the 8× probe figure is an upper bound (no bank conflicts, refresh or
read/write turnaround, `local_ready` held high).

## Risks

- **Timing.** The 4/8-core critical path already terminates at
  `lruCacheCore|cmdFifo|logic_ram` — all N cores' address decode fanning into
  that one command port. An MSHR adds logic to exactly that path, so it may make
  Fmax worse before it makes throughput better. Watch WNS from the **first**
  build, not at the end.
- The A-E115FB cannot give a timing-clean 8-core build at any legal DDR2 clock
  (`mem_if_clk_mhz` 150.0 at half rate ⇒ system = memory/2, DDR2 floor ~125 MHz
  ⇒ 62.5 MHz, against ≤61.01 MHz needed). Hardware validation of the 8-core case
  will be on a corner-violating bitstream; `JbeScale`'s `CHECK` makes that
  acceptable for measurement but it is not a shippable configuration.
- Three formally-verified components are in the blast radius:
  `LruCacheCoreFormal`, `BmbCacheBridgeFormal`, `CacheToMigAdapterFormal`, plus
  `LruCacheCoreUnitSim`, `CacheDdr2EvictSim`, `JopSmpCacheStressSim`,
  `JopDcuCacheSim`.
- `CacheDdr2EvictSim`'s trap is worth re-reading first: its first version checked
  `frontend.req.ready` and reported PASS while deadlocked, because the cache has a
  4-deep input FIFO that keeps accepting after the pipeline behind it stopped.
  **Measure completions, not acceptances** — doubly true once misses overlap.

## Hardware results

A-E115FB, EP4CE115 + 1 GB DDR2, 75 MHz, `jbe.Scale` (private 64 KB stride walk
per core, so nearly every access misses). **`CHECK 1645838336` in every run
below, including the baselines** — every configuration computes bit-identical
results, which is what makes the timing caveat further down tolerable.

| cores | MSHRs | RTL | kacc/s | ratio vs 1 core |
|---|---|---|---|---|
| 1 | — | old | 377 | 1.00x |
| 4 | — | old | 661 | 1.75x |
| 8 | — | old | **682** (reconfirmed on the bench today) | 1.81x |
| 4 | 1 | new | 618 (618/618/618) | 1.64x |
| 4 | 4 | new | **937** median (936/937/937/963/1007) | 2.48x |
| 8 | 4 | new | **1613** median (1554/1613/1652) | **4.28x** |

**The serialisation ceiling is gone.** Eight-core DDR2 was 1.81x a single core
and is now 4.28x — SDR, which has no `LruCacheCore` at all, gets 4.45x. Four
cores with MSHRs (937) beat the old EIGHT-core figure (682) outright.

Three things to be honest about:

- **The restructure costs ~6.5 % when the overlap is switched off.** At
  `mshrCount = 1` the new non-blocking FSM gives 618 against the old blocking
  one's 661: the funnel through `IDLE` plus `RSP_FILL` spends about two more
  cycles per miss, and with one MSHR there is no overlap to pay for it. Against
  the configuration that actually shipped, the four-core gain is 937/661 =
  **1.42x**, not 1.51x.
- **The projection in this document was too optimistic.** It guessed 2.7-5.5
  Macc/s for eight cores; the answer is 1.6. What it got right was the shape —
  the ceiling was the serial miss FSM, and removing it moves the curve.
- **Run-to-run variance appears only with MSHRs.** The baseline is perfectly
  repeatable (618 three times); the MSHR builds spread 936-1007 at four cores
  and 1554-1652 at eight. That is expected: once misses overlap, throughput
  depends on the relative phase of the cores' request streams, which varies with
  boot timing. Quote a median and the spread, not a single run.

### Cost, and the timing risk that did not materialise

| build | logic elements | Fmax (Slow 1200mV 100C) | WNS at 75 MHz |
|---|---|---|---|
| 4-core, 1 MSHR | 66,958 (58 %) | 67.48 MHz | -1.486 ns |
| 4-core, 4 MSHRs | 70,366 (61 %) | 67.31 MHz | -1.524 ns |
| 8-core, 4 MSHRs | 106,405 (93 %) | ~62 MHz | -3.056 ns |

The plan's headline risk was that the MSHR would land on the critical path,
which already terminated at `lruCacheCore|cmdFifo|logic_ram`. **It did not.**
The worst path is the same one in both four-core builds —
`BmbMemoryController|Equal3 -> LruCacheCore|cmdFifo|logic_ram`, N cores' address
decode fanning into one command port — and adding four MSHRs moved it by
**0.038 ns, 0.25 % of Fmax**, for +5.1 % logic. None of the new registers or
comparators appear in the failing paths.

**These builds do not close timing**, at four cores or eight — but neither did
the baselines they are being compared against. The numbers in
`java/apps/JbeBench/README.md` for the original blocking builds are **-1.997 ns
at four cores and -3.059 ns at eight**, at the same corner. So against the
configurations that produced 661 and 682:

| | old (blocking) | new (4 MSHRs) |
|---|---|---|
| 4-core WNS | -1.997 ns | **-1.524 ns** |
| 8-core WNS | -3.059 ns | **-3.056 ns** |

The non-blocking rewrite is *better* at four cores and a wash at eight. The
worry that an MSHR would make Fmax worse before it made throughput better was
reasonable and turned out to be unfounded.

`jbe.Scale`'s `CHECK` is what makes measuring on a violating bitstream
defensible — bit-identical across every build and every repeat — and the corner
analysis in the benchmark README explains why: Quartus reports Slow/100 C, the
bench runs at room temperature, and the same path measures +1.398 ns at the fast
corner. Treat all three as measurement vehicles, not shippable bitstreams.

### Correctness on hardware

`DoAll` **66/66** on both the 4-core and 8-core MSHR bitstreams, no failures and
no unexpected exceptions.

## What was built

Three commits, in the plan's order. Every number below is simulation.

### 1. `CacheFrontend` request id

`CacheReq`/`CacheRsp` take an optional `idWidth`. At 0 the field is **absent
entirely**, so both backend adapters — strictly in-order, single-tagged — keep
exactly the bundle they had, and `memCmd`/`memRsp` stay id-less however wide the
frontend tag gets. `idValue`/`driveId` are the accessors that also compile at 0.

### 2. `BmbCacheBridge` — N outstanding

The single `pendingRsp`/`pendingSource`/`pendingContext`/`pendingLaneSelect`
became a slot table indexed by id. Response return moved **out** of the
command-acceptance tree, because returning a response and accepting the next
command in the same cycle is the entire point. The burst-read path is still
sequential and may now only start once the single-beat slots have drained: it
matches responses to beats by counting, not by id.

`BmbCacheBridgeOutstandingSim`, against a perfectly-pipelined cache stub with
20-30 cycle random latency (so completions genuinely reorder), 8 BMB sources:

| slots | cycles/req | speedup |
|---|---|---|
| 1 | 27.21 | 1.00× |
| 2 | 13.66 | 1.99× |
| 4 | 6.73 | 4.05× |
| 8 | 3.65 | 7.46× |

### 3. `LruCacheCore` — the MSHR file

The miss FSM no longer waits on memory: a miss allocates an MSHR, issues the
eviction and refill, and returns to `IDLE`. `WAIT_EVICT_RSP` and
`WAIT_REFILL_RSP` are gone, replaced by one `RSP_FILL` state that applies a
returning line and answers its waiter.

**Everything funnels through `IDLE`, response handling included.** That is what
made it tractable: a lookup is only ever in flight in `TAG_COMPARE`/`CHECK_HIT`,
and `RSP_FILL` is only entered from `IDLE` (or from a stalled `ISSUE_*`, where
the lookup has already finished), so a fill can never corrupt a tag comparison
that is mid-air. The whole `lookupStale` hazard class the plan implied simply
does not arise.

Design decisions worth recording:

- **One in-flight miss per set**, enforced by replaying any request — hit or
  miss — whose index matches a live MSHR. This is the plan's "simplest correct
  policy", and it does more than avoid duplicate victim selection: a *write*
  that hits the way an outstanding fill is about to overwrite would otherwise be
  silently lost. It also keeps each MSHR's saved tag/dirty words from going
  stale, and it makes **secondary-hit merging an optimisation rather than a
  correctness requirement** — so that bullet was deliberately not implemented.
- **Evictions are fire-and-forget.** An eviction and its own refill are
  different addresses (the tags differ, or it would not have been a miss), so
  nothing has to wait. The old `WAIT_EVICT_RSP` was an artifact of the serial
  FSM, not an ordering requirement: a later request for the *evicted* address
  still depends on the backend not floating a read past a queued write to the
  same address, but that was equally true before, when a write response only
  meant the controller had accepted it.
- **Ids are the licence to reorder.** At `idWidth = 0` the cache refuses a new
  request while any memory work is outstanding, so every existing untagged
  master keeps one-at-a-time, in-order responses — bit-for-bit its old contract.
  `mshrCount > 1` is rejected at elaboration without an id.
- **Order queue records evictions as well as refills**, since both consume a
  response and nothing in the payload distinguishes them. `CHECK_HIT` reserves
  two slots before committing to a miss, so `ISSUE_*` can never block on a full
  queue — which would wedge the FSM out of the only state that drains it.
- A stalled `ISSUE_*` can divert to `RSP_FILL` and resume, so a backend whose
  queue is full cannot deadlock against a cache that will not drain it.

`LruCacheCoreMshrSim`, all-miss line walk, backend latency 40 cycles pipelined:

| MSHRs | cycles/req | speedup |
|---|---|---|
| 1 | 48.01 | 1.00× |
| 2 | 24.06 | 2.00× |
| 4 | 12.09 | 3.97× |
| 8 | 6.72 | 7.14× |

The 6.72 floor at 8 is the FSM occupancy the plan predicted (~6 cycles per
miss), so `min(k, latency/occupancy)` is confirmed as the model: at 40 cycles of
latency the knee is around k ≈ 7, and 8 duly returns 7.14× rather than 8×.

Two more phases in the same sim: mixed reads and partial/full-line writes with
lines partitioned per id (9.76 cycles/req, write-back through eviction verified
under overlap), and every id aimed at **one set** with different tags, which is
exactly what the design refuses to serve concurrently — 36.72 cycles/req, i.e.
it serialises as intended, with no lost or duplicated response.

### Two bugs found on the way

- **`memRsp.ready` must not depend on `memRsp.valid`.** The first `RSP_FILL`
  drove ready inside `when(memRsp.valid)`. The old refill path drove it
  unconditionally within its wait state, so nothing downstream had ever had to
  cope with a combinational valid→ready path; a one-cycle-wide observer in
  `LruCacheCoreUnitSim` missed the handshake entirely and the sim hung.
- **`LruCacheCoreUnitSim`'s memory model held exactly one outstanding command.**
  Enough while the cache blocked in `WAIT_EVICT_RSP`; once the eviction and its
  refill are issued back to back the model silently dropped the first and the
  cache waited forever for a response that had been overwritten. Now a queue.

Both are the same shape as the trap the plan already flagged: a testbench that
was only ever correct because the DUT was serial.

## Verification status

| | |
|---|---|
| `BmbCacheBridgeFormal` | 8/8 — full slot table blocks, a slot is freed only by a response carrying its id, a burst never overlaps single-beat work, plus one test pinning `outstanding = 1` |
| `LruCacheCoreFormal` | 12/12 — every issued command is recorded in the order queue and no response is retired without its entry; `ISSUE_*` never stalls on the queue; an MSHR is freed only by the refill naming it; **no two live MSHRs share a set**; untagged frontends take one request at a time; responsive memory and erroring memory both drain |
| `LruCacheCoreUnitSim` | 7/7 at 32, 128 and 256-bit lines |
| `CacheDdr2EvictSim` | 200 line writes through 184 evictions, 0 mismatches, backend fill retired |
| `LruCacheCoreTest`, `CacheWidthElabTest`, `Ddr3WidthElabTest` | pass; elaboration now covers 1/2/4/8 MSHRs |
| `JopDcuCacheSim` | 59/59 JVM tests through the cache path at `mshrCount` **1, 4 and 8** — pass the count as an argument. The whole suite through a non-blocking cache is the broadest correctness check available: real access patterns, hits served under outstanding misses, refills completing out of order |
| `JopSmpDdr3NCoreHelloWorldSim` | 2 cores on DDR3 (harness needed a fix first — see below) |
| `JopDdr3FillSim` | block fill end-to-end |

A formal property deliberately *not* asserted: `memRsp.valid` implies
`orderFifo.pop.valid`. It is false, and harmlessly so — the queue has
push-to-pop latency, so a response can arrive a cycle or two before its entry
surfaces. The machine waits in `IDLE` (still serving hits) rather than in
`RSP_FILL`, which also means a response with no entry at all can never wedge it.

## The bug the hardware run would have hit

Found while preparing the A-E115FB build, and worth stating separately because
the plan's own risk list pointed straight at it without anyone noticing it
applied to live code.

`CacheToDdr2Adapter` acknowledges a write **locally**, the cycle the controller
accepts it — ALTMEMPHY returns nothing for a write, so the adapter has to
manufacture the response `LruCacheCore` requires. Read data comes back tens of
cycles later. With one command in flight that asymmetry is invisible, which is
why it sat there through every previous test.

Once misses overlap it corrupts data. An eviction issued after an earlier refill
has its acknowledgement ready first; `LruCacheCore` matches responses to
commands **by order**; so the earlier miss is filled with whatever happened to
be on `local_rdata`. Not a hang — a wrong cache line. Precisely the
`AlteraSdramAdapter` shape (`ef36d99`).

Fixed by re-serialising adapter responses into command order (an order queue for
what each command was, a data queue for returned reads, a write at the head
answering immediately and a read at the head holding later acks behind it).

**Why the existing tests all passed:**

| test | why it missed this |
|---|---|
| `CacheToDdr2AdapterSim` | issued all writes, then all reads — never both in flight |
| `LruCacheCoreMshrSim` | backend model gives reads and writes the SAME latency, so its responses are always in command order |
| `CacheDdr2EvictSim` | drives the cache one request at a time |

Both gaps are now closed: an interleaved phase in `CacheToDdr2AdapterSim`, and a
new `CacheDdr2MshrSim` that composes the non-blocking cache with the **real**
adapter against a controller model that reproduces the asymmetry. Against the
old adapter the latter gives **0 errors at 1 MSHR and 120 wrong reads at 2** —
the cleanest possible statement of why one-outstanding hid it. With the fix, 0
errors and 1.00 / 1.96 / 3.36× at 1 / 2 / 4 MSHRs through the full DDR2 path.

**Generalisation worth carrying:** a backend model with uniform latency cannot
find response-ordering bugs. If the real thing answers some commands early,
model that, or the ordering logic is untested.

## DDR3: the adapter was the limit, and the fix is free in fabric

`CacheToMigAdapter` serialised reads (`IDLE -> ISSUE_READ -> WAIT_READ`, one
`activeCmd` at a time) and pipelined only writes, so on the DDR3 boards the
cache offered concurrency the adapter refused. Rewritten with no state machine,
bounded by `maxOutstanding` (= 2 x mshrCount, an eviction and a refill per
miss), and re-serialising responses into command order for exactly the reason
`CacheToDdr2Adapter` had to — a locally-manufactured write acknowledgement must
not overtake an earlier read. The old one was safe only by accident: a read
blocked the issue path, so a write could never be in flight beside one.

`CacheMigMshrSim` (real cache, real adapter, behavioural MIG UI with STRICT
ordering and an unstallable one-cycle read pulse) decomposes the result instead
of just reporting it:

| MSHRs | cycles/req | speedup |
|---|---|---|
| 1 | 26.51 | 1.00x |
| 2 | 13.51 | 1.96x |
| 4 | 8.13 | **3.26x** |
| *control:* 4 MSHRs, 1-deep adapter | 19.27 | 1.38x |

**The cache restructure alone is worth 1.38x on a serial backend** — hits served
under misses, evictions no longer waiting — **and the adapter carries the
remaining 2.37x.** That control case is a permanent part of the sim, so the
claim stays measured rather than remembered.

### Hardware: Wukong XC7A100T, ui_clk 91.676 MHz

`jbe.Scale`, `CHECK 1645838336` in every run — the same value the DDR2 board
produces, so both memory systems compute bit-identically.

| cores | MSHRs | kacc/s | ratio vs 1 core (430) |
|---|---|---|---|
| 4 | — | 733 (published, old RTL) | 1.70x |
| 8 | — | 754 (published, old RTL) | 1.75x |
| 4 | 1 | 692 | 1.61x |
| 8 | 1 | 701 | 1.63x |
| 4 | 4 | **1380** | **3.21x** |
| 8 | 4 | **1882** (1836 / 1882 / 1915) | **4.38x** |

**Eight cores: 701 -> 1882, 2.68x against the same RTL and 2.50x against what
shipped.** `DoAll` passes 66/66 on both the 4- and 8-core MSHR bitstreams.

The blocking baseline shows the ceiling directly: going 4 -> 8 cores buys
692 -> 701, i.e. **1.3 %**. With MSHRs the same step buys 1380 -> 1882, **36 %**.

### All three memory architectures now scale the same

That is the result worth keeping, more than any single number:

| memory | clock | 8-core kacc/s | ratio vs 1 core |
|---|---|---|---|
| SDR (no L2 at all) | 100 MHz | 2764 | 4.45x |
| DDR3 + MSHR | 91.68 MHz | 1882 | **4.38x** |
| DDR2 + MSHR | 75 MHz | 1613 | **4.28x** |

Before this work the two DRAM paths sat at 1.75x and 1.81x while SDR reached
4.45x, and that gap was the whole reason for the investigation. The three
architectures now agree within 4 %, having started 2.5x apart. Absolute rates
still differ (SDR is ~27.6 kacc/s per MHz against DDR3's 20.5) but the SHAPE of
the curve no longer depends on which memory is attached — which says the
remaining limit is something all three share, not anything about DRAM. The
single BMB command port and its arbiter are the obvious next suspects, and the
fact that the critical path also terminates there is not a coincidence.

### Fabric cost, measured across four build pairs

| build | | WNS | Slice LUTs | Slice Registers |
|---|---|---|---|---|
| 4-core Ddr3_400 | 1 MSHR | +0.120 ns | 39,245 | 27,147 |
| 4-core Ddr3_400 | 4 MSHRs | +0.363 ns | 38,872 | 27,957 |
| 4-core Ddr3_366 | 1 MSHR | +0.375 ns | 39,257 | 27,147 |
| 4-core Ddr3_366 | 4 MSHRs | +0.111 ns | 38,888 | 27,957 |
| 8-core Ddr3_366 | 1 MSHR | -0.501 ns | 58,135 (91.7 %) | 41,514 |
| 8-core Ddr3_366 | 4 MSHRs | -0.465 ns | 57,856 (91.3 %) | 42,172 |

**Area is consistently in the MSHR's favour: -373, -369 and -279 LUTs** across
the three pairs, for +810 / +658 registers. Deleting the adapter's state machine
and the cache's two `WAIT_*` states removes more control logic than the MSHR
file adds, and the MSHR's cost lands in flip-flops, where there is headroom
(33 %) rather than in LUTs, where at eight cores there is not (91 %).

**Do not read a timing improvement into this.** The WNS difference between
configurations changes sign between the two 4-core profiles (+0.243 favouring
the MSHR at 400, -0.264 favouring the baseline at 366): fitter noise. At four
cores everything closes; at eight NEITHER configuration closes (-0.501 and
-0.465 ns, 73 and 254 failing endpoints), which is why `jbe.Scale`'s `CHECK` is
the acceptance criterion there, exactly as on DDR2.

## What is left

1. **The next limit is shared, not memory-specific.** All three memory
   architectures now land within 4 % of each other on the scaling ratio, so
   whatever caps them at ~4.3x is common to all three. The single BMB command
   port and its arbiter are the candidates, and the critical path terminates
   there too. That is the next investigation, and it is now a well-posed one.
2. **Timing at eight cores.** Neither core count closes at 75 MHz, MSHRs or not. That is a
   pre-existing property of the `BmbMemoryController -> cmdFifo` path, and it is
   what to attack next if these configurations are ever to ship — pipelining
   that command port would also raise the achievable clock. The 8-core build is
   additionally at 93 % logic.
3. **The remaining gap to linear.** Eight cores now give 4.28x, not 8x. With the
   miss FSM no longer the limit, the next candidates are the single BMB command
   port, the 4-MSHR cap (the DDR2 adapter's `rspDepth = 8` allows no more, since
   each miss can need an eviction and a refill), and DRAM bank/refresh effects.
   Worth re-running `Ddr2ConcurrencyProbe` against the measured numbers before
   guessing.
4. **Secondary-hit merging** — a request to a line already being filled is
   replayed, not attached. Pure throughput, no correctness impact.
5. **Register cost** is real: each MSHR holds a whole cache line of write data
   (256 bits on DDR2) plus tag and dirty words. Measured at +3,408 LE for four
   entries on the 4-core build.
