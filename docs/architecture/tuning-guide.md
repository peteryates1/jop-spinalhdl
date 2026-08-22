# Tuning guide — the configuration levers, what each buys, what each costs

Every number here was measured on hardware or in `MethodCacheSweepSim`, on the
dates given. Where something is an argument rather than a measurement it says
so. If you change a default, add the measurement — the history of this file is
mostly of plausible reasoning that turned out to be wrong (see
[Things that sound right and are not](#things-that-sound-right-and-are-not)).

**Read the resource you are short of first.** On every Artix-7 build measured so
far the binding constraint is **LUTs**, not BRAM: a 4-core Wukong sits at 90 %
LUT and 32 % BRAM. Levers that trade BRAM for LUTs are therefore nearly free,
and levers that cost LUTs are expensive whatever they buy.

---

## Summary

| lever | costs | buys | measured |
|---|---|---|---|
| `blockBits` (method cache block COUNT) | **LUTs** — one 18-bit comparator per block + priority cascade | the big one: 16 -> 32 blocks takes Kfl from 34.8 % to 0.6 % miss | 2026-08-19 sweep, 2026-08-20 HW |
| `jpcWidth` (method cache SIZE) | **BRAM** only | at a fixed block count, deeper slots: Kfl fill traffic -53.7 % | 2026-08-22 sweep |
| `l2SetCount` (DRAM L2 sets) | **BRAM** (since 2026-08-22; was LUTs) | up to 33 % on data-heavy work, 2.06x at 4 cores | 2026-08-22 HW |
| `l2MshrCount` | LUTs + registers | 4.3x multicore DRAM scaling | 2026-08-17 HW |
| `cpuCnt` | LUTs, linearly | throughput on multicore workloads only | — |
| compute units | LUTs | long/float/double arithmetic in HW | — |

---

## Method cache — `jpcWidth` and `blockBits`

The two parameters are **not** substitutes. They fix different problems, and
the sweep separates them cleanly.

```
total bytes = 2^jpcWidth        blocks = 2^blockBits
block words = 2^(jpcWidth - 2 - blockBits)
```

### Block COUNT is the dominant lever, and it costs LUTs

Only a method's **first block carries a tag**, so the block count caps how many
methods can be resident. This is a fragmentation problem, not a capacity one.

Kfl, 142,395 lookups (`MethodCacheSweepSim`):

| geometry | misses | miss % | words filled |
|---|---|---|---|
| 2 KB, 16 x 32w | 49,569 | 34.8 % | 966,208 |
| 4 KB, 32 x 32w | 895 | **0.6 %** | 15,031 (**-98.4 %**) |
| 8 KB, 64 x 32w | 179 | 0.1 % | 2,400 (-99.8 %) |

UdpIp is even sharper — 23.2 % to 0.1 % for the same 16 -> 32 step — and is
**purely** fragmentation-bound: depth does nothing for it at all (below).

**Cost**: this is the expensive axis. On a 4-core XC7A100T, 16 blocks closes at
WNS +0.112 ns, 32 blocks **violates timing** at -0.147 ns, and 64 blocks does
not place. Single-core there is plenty of room, which is why the shipped default
is 64 blocks.

That violation is not a near miss the tools can absorb. Re-placing 32 blocks with
`place_design -directive Explore` recovered 0.104 ns and still finished at
**-0.043 ns**, at 93.4 % LUT (the same directive was worth 0.125 ns on a 16-block
build, so this is the directive's normal yield, not a bad run). **If a geometry
misses by more than ~0.1 ns at four cores, placement effort will not close it.**

### Slot DEPTH is nearly free, and helps when block count is capped

A method spanning k blocks consumes k tag-carrying slots, so deeper slots mean
fewer slots per method. At a **fixed 16 blocks**, Kfl:

| geometry | misses | miss % | words filled |
|---|---|---|---|
| 2 KB, 16 x 32w | 49,569 | 34.8 % | 966,208 |
| 8 KB, 16 x **128w** | 23,674 | **16.6 %** | 447,374 (**-53.7 %**) |
| 16 KB, 16 x 256w | 23,674 | 16.6 % | 447,374 |
| 32 KB, 16 x 512w | 23,674 | 16.6 % | 447,374 |

**Cost: 32 LUTs.** A 4-core build measured 57,297 LUTs at `11/4` and 57,329 at
`14/4` — 8x the method cache for 32 LUTs, because `blockBits` is unchanged. The
capacity is BRAM, and **fill time does not change**: the fill loop terminates on
`bcFillLen`, the METHOD length, not on the block size
(`BmbMemoryController`, `when(wordsWritten >= bcFillLen)`).

**It saturates at 128-word (512 B) slots** on today's benchmarks, and the reason
is the method-length distribution rather than anything structural:

| app | methods | median | p90 | max |
|---|---|---|---|---|
| JbeBench | 938 | 9 B | 105 B | 882 B |
| DoAll | 2,644 | 15 B | 131 B | 1,368 B |

By 512 B slots essentially every method fits in one slot and consumption cannot
fall below 1. Going deeper is **not wasted, but unmeasurable here** — it is
headroom against code with longer methods than ours, which is a real
possibility and costs only BRAM we are not short of. Deeper slots do increase
internal fragmentation (a 9-byte method in a 1 KB slot), but since the slot
count is what binds and BRAM is spare, that waste buys namespace.

**Rule of thumb**: raise `blockBits` as far as timing allows; then spend spare
BRAM on `jpcWidth`. Do not trade the first for the second.

---

## DRAM L2 — `l2SetCount`

4 ways x 16 B lines, so 512 sets = 32 KB. Default 512.

**Since 2026-08-22 the L2's size costs BRAM and essentially no logic.** A 4-core
build is 62,583 LUTs at 512 sets and 62,592 at 256 — nine apart. Before that
change the valid and PLRU arrays were fabric registers and the same step cost
~9,500 LUTs; that is why older notes describe a steep non-linear cost curve.
**That curve no longer exists**, and shrinking the L2 is no longer a way to make
a design fit.

What it buys depends entirely on the workload:

| workload | 4 KB vs 32 KB L2 |
|---|---|
| `JbeBench` Kfl/UdpIp/Lift, 1 core | **bit-identical** |
| `ScaleL2`, 1 core | 31 % falloff past capacity |
| `ScaleL2`, 2 cores | up to **33 %** |
| `ScaleL2`, 4 cores | up to **2.06x** |

**`JbeBench` cannot see the L2 at all** — it is instruction-fetch bound with a
small data working set. Do not use it to justify an L2 change in either
direction; use `ScaleL2`, which sweeps the working set across the cache.

**What matters is the AGGREGATE working set**, not the per-core one: N cores put
N private working sets through one shared L2, so N cores need roughly N x the
capacity for the same residency. A 4-core build with a 4 KB L2 falls off a 96 %
cliff the moment the aggregate exceeds it.

---

## `l2MshrCount`

Misses allowed in flight. Default 1 (blocking). Raising it was worth **4.3x** on
8-core DRAM throughput (DDR2 682 -> 1613 kacc/s, DDR3 754 -> 1882). Costs
registers per entry — each holds a full cache line — plus a wider
`BmbCacheBridge`. See [nonblocking-cache-mshr-plan.md](nonblocking-cache-mshr-plan.md).

Note `ScaleL2` shows scaling is sub-linear even inside the cache — 661 / 1,199 /
1,474 kacc/s for 1 / 2 / 4 cores — so the shared path saturates between two and
four cores well before DRAM bandwidth binds. That is the MSHR count, not
capacity.

---

## Core count — `cpuCnt`

Linear in LUTs, and it interacts with everything above:

- the method cache is **per core**, so its LUT cost multiplies. The 8 KB/64-block
  default costs **850 LUTs per core**, which is what broke the 4-core Wukong fit
  (item 53) — a single-core win that nobody multiplied by four.
- the L2 is **shared**, so its cost does not multiply, but the pressure on it
  does.

---

## Things that sound right and are not

Each of these was believed, acted on or nearly acted on, and disproved by
measurement. They are here so the next person does not re-derive them.

- **"A bigger cache is a better cache."** Doubling the method cache SIZE at a
  fixed block count moved UdpIp by 0.2 %. Doubling the COUNT at fixed size
  removed 99.5 % of its fill traffic.
- **"`JbeBench` is our throughput benchmark, so use it to size the L2."** It is
  blind to the L2. It would have shown no regression while data-heavy and
  multicore code lost a third of its throughput.
- **"More cores want a smaller L2, because LUTs are tight."** Backwards on both
  halves: more cores want a *larger* L2 (aggregate working set), and since
  2026-08-22 L2 capacity does not cost LUTs anyway.
- **"The L2 is not the lever — it is worth 3-5 %."** That figure compared
  SDR-without-L2 against DDR3-with-L2, so it measured memory technology and
  caching together. Bare DDR3 is much worse than bare SDR; the L2 is what
  rescues it, and it is worth 25-49 %.
- **"Read the hierarchical utilization report to find where the logic went."**
  Only from a **post-route** checkpoint. The post-synthesis one excludes IP
  black boxes (the MIG is ~4,400 LUTs) and is pre-optimisation. A design that
  fails DRC before placement can only ever give you the untrustworthy one.
- **"Verilog line count indicates area."** `LruCacheCore` was 79 % of the
  generated lines and 21 % of the LUTs.

---

## How to measure

| question | tool | runtime |
|---|---|---|
| method cache geometry | `MethodCacheSweepSim` (`MCACHE_ONLY=13/4,14/4`, `MCACHE_JOP=...`) | ~6 min per geometry |
| L2 capacity, any core count | `java/apps/JbeBench/ScaleL2.jop` | one download |
| application throughput | `java/apps/JbeBench/JbeBench.jop` | one download |
| where the logic went | `report_utilization -hierarchical` on `post_route.dcp` | ~1 min |
| method length distribution | `grep -o "code_length = [0-9]*" <app>.jop.txt` | instant |
