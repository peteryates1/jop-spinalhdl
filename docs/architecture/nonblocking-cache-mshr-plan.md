# Non-blocking L2: MSHR plan for `LruCacheCore`

**Status**: implemented and verified in simulation (2026-08-17). Off by default
(`JopMemoryConfig.l2MshrCount = 1`); **no hardware measurement yet**. See
[What was built](#what-was-built) and [What is left](#what-is-left).

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

## What is left

1. **Hardware.** Nothing here has been on a board. Build
   `ae115fbDdr2SmpMshr <cores> <mshrs>` against the `ae115fbDdr2Smp` baseline and
   run `JbeScale` on both — the two presets differ in exactly `l2MshrCount`.
   **Watch WNS on the first fit**, not at the end: the MSHR adds logic to the
   `cmdFifo` command path, which is already where the 4- and 8-core critical
   paths terminate. The 8-core case cannot be timing-clean on this board at any
   legal DDR2 clock; `JbeScale`'s `CHECK` makes a corner-violating bitstream
   acceptable for measurement but not for shipping.
2. **`CacheToMigAdapter` reads** are serial (see the correction above), so the
   DDR3 boards need that fixed before `mshrCount > 1` is worth setting there.
3. **Secondary-hit merging** — currently a request to a line already being
   filled is replayed, not attached. Pure throughput, no correctness impact.
4. **Register cost** is real: each MSHR holds a whole cache line of write data
   (256 bits on DDR2) plus tag and dirty words. Four entries is roughly 1.5 k
   flip-flops on the A-E115FB. Worth checking against the fit report.

## Two sims that were already broken

Neither is in the CI matrix, which is why neither was noticed.

- **`JopSmpDdr3NCoreHelloWorldSim`** declared `debugCacheState` as 3 bits and
  assigned `LruCacheCore.io.debugState` (4 bits) to it without a resize, so it
  had not elaborated since the cache grew past 8 states. Fixed here, because it
  is the SMP-through-the-cache coverage this change wants.
- **`JopSmallGcCacheSim`** loads `java/apps/Small/HelloWorld.jop` but its pass
  criteria look for `GcStressTest` output (`"GC test start"`, `"R80 f="`), so it
  runs 40 M cycles printing "Hello World!" and then fails. **Left alone** — it
  needs a Java rebuild decision (which app should it load?), not an RTL fix.
