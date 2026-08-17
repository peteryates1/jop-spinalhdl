# Non-blocking L2: MSHR plan for `LruCacheCore`

**Status**: de-risking complete, implementation not started (2026-08-17).

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

- **Both adapters.** `CacheToDdr2Adapter` already supports `rspDepth = 8`
  outstanding reads and exposes `debugOutstanding`; `CacheToMigAdapter` likewise
  pipelines. One response per command, in order, including writes.
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
