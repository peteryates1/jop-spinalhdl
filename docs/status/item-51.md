# Item 51 — ~~The method cache is capped at 2 KB~~ — FIXED. Default is now 8 KB/64 blocks: **+35 % Kfl, +27.7 % UdpIp**, validated on FOUR BOARDS

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 51](../current-status.md#item-51).

---

**Why this matters.** Item 50 measured where stall time goes on real memory:
bytecode fill is **62.8 % of Kfl's stall and 52.9 % of UdpIp's**, and stall is
~52 % of all cycles. So the method cache owns roughly **a third of every cycle
Kfl executes** (0.522 x 0.628 = 32.8 %; UdpIp 27.5 %; Lift 0.9 %). It is the
largest single line item in the whole profile, and item 50 also showed that a
32 KB L2 in front of DRAM takes only 3 % off it — the fix has to be in the
method cache itself, not behind it.

#### What is actually built

> **Read this table as of the start of the investigation.** The capacity and
> organisation rows were superseded on 2026-08-20 — the default is now 8 KB /
> 64 blocks. See [DEFAULT CHANGED 2026-08-20](#default-changed-2026-08-20--8-kb--64-x-32w-validated-on-four-boards)
> further down this item. Every other row still holds.

`jop.memory.MethodCache`, a port of the VHDL `mcache`:

| | |
|---|---|
| capacity | **2 KB** (`jpcWidth = 11`), and JBC RAM *is* the data store |
| organisation | 16 blocks (`blockBits = 4`) x 32 words = 128 B per block |
| associativity | **fully associative**, one 18-bit tag + valid bit per block |
| allocation | variable size — a method takes `ceil(len/32)` **consecutive** blocks |
| replacement | FIFO, `nxt := nxt + nrOfBlks + 1` |
| lookup cost | hit 2 cycles, miss 3 cycles |

#### How it knows what is in the cache

On a `find` pulse, state `S1` compares `bcAddr` against **all 16 tags in
parallel**, generated as a chain of `when(tagValid(i) && tag(i) === useAddr)`
assignments — a priority cascade, last match wins.

Two details matter more than they look:

1. **Only the FIRST block of a method carries a valid tag.** `S2` clears the
  tags of every block the new method covers, then writes the tag at `nxt`
  alone. So a 4-block method consumes 4 tag slots and uses 1. The tag array is
  sparse, and the number of *comparators* is tied to the number of *blocks*,
  not to the number of resident *methods*.
2. **`clrVal` is recomputed every single cycle** — 16 subtract-and-compare
  units running continuously to produce a mask that is only consumed in `S2`.
  Harmless at 16 blocks; not harmless at 64.

#### Why the compare caps the size

The geometry is fixed by

```
blockWordBits = jpcWidth - 2 - blockBits
```

Grow `jpcWidth` with `blockBits` fixed and you grow the **block size**, not the
block count: 8 KB with 16 blocks means 128-word blocks, and since the smallest
method still burns a whole block, internal fragmentation gets worse — a 4-word
method would waste 124 words. To grow capacity *and* keep blocks small you must
grow `blockBits`, and that is one more 18-bit comparator per block, feeding a
priority cascade. **That is the real cap, and it is why 2 KB has stood.**

There is a second, quieter cap: `nrOfBlks` is `bcLen[9:blockWordBits]` resized
to `blockBits`, so the largest cacheable method is exactly the whole cache —
512 words at today's geometry. Growing the cache raises that limit too.

#### The way out: the compare does not need to be combinational

`mcacheFind` is asserted **once per `bcRd`** — method invoke and return only.
During execution `jpc` indexes JBC RAM directly and there is **no tag check on
the fetch path at all**. That is the whole point of JOP's method cache, and it
is preserved no matter how wide the tag array gets.

So the lookup is amortised over an entire method execution, and it already
costs 2-3 cycles against a fill that costs **dozens to hundreds**. Splitting a
64-tag compare into 4 pipelined groups of 16 adds ~3 cycles to a lookup and
nothing to `fmax`. **Associativity and clock frequency are only coupled because
the compare is currently done in one cycle, and it does not have to be.**

Options, cheapest first:

1. **Pipeline the tag compare.** Enables everything below. No change to the
  fetch path, no change to the WCET story.
2. **Decouple tags from blocks.** Keep a small method-descriptor table (tag,
  start block, length) sized to the number of methods you want resident, and
  let the block allocator stay FIFO and unconstrained. 128 small blocks for low
  fragmentation with only 32 comparators.
3. **Two-stage compare.** Narrow hashed tag (8 bit) in parallel to filter, then
  one full 18-bit verify on the candidate. Cuts comparator width and cascade
  depth.
4. **Set-associative descriptors.** Hash the method address to a 4-way set —
  4 comparators regardless of capacity. Works only in combination with (2):
  constraining *block* placement fights variable-size contiguous allocation.
5. **Fix `clrVal`** to compute only in `S1`/`S2` before it scales with blocks.
6. **Revisit FIFO.** It evicts a hot small method simply because a large one
  swept past. Costs more as the cache grows.

#### Concrete first step

`JopCoreConfig` line 365 has

```scala
require(jpcWidth == 11, "JPC width must be 11 bits (2KB cache)")
```

`jpcWidth` is otherwise cleanly parameterised — `BytecodeFetchStage` derives
`jbcDepth = 1 << jpcWidth` and every register from `config.jpcWidth` — so this
is a conservative guard, not a hard dependency. Removing it and sweeping
capacity/`blockBits` in simulation costs no RTL work and directly attacks the
largest category in the profile. BRAM is not the constraint on these parts.

**Do this before designing anything**, because it answers the question the
design depends on: how much of the 33 % is capacity misses (fix with size) and
how much is fragmentation (fix with block count) or conflict (fix with
replacement). Item 37's transaction counts and item 50's stall shares both say
"method cache" but neither distinguishes those three.

#### SWEPT 2026-08-19 — it is FRAGMENTATION first, and the win is ~99 %

`MethodCacheSweepSim` counts misses at `MethodCache.io.inCache` rather than
inferring them from stall, so the result is a property of the geometry and not
of the backend. Lookup counts are identical across every row (same program), so
the columns are directly comparable.

**Kfl** — 142,395 lookups:

| geometry | misses | miss % | words filled |
|---|---|---|---|
| 2 KB, 16 x 32w — **today** | 49,569 | **34.8 %** | 966,208 |
| 2 KB, 32 x 16w | 19,213 | 13.5 % | -62.1 % |
| 2 KB, 64 x 8w | 13,475 | 9.5 % | -73.3 % |
| 4 KB, 16 x 64w | 23,685 | 16.6 % | -53.7 % |
| **4 KB, 32 x 32w** | 895 | **0.6 %** | **-98.4 %** |
| 4 KB, 64 x 16w | 192 | 0.1 % | -99.7 % |
| 8 KB, 64 x 32w | 179 | 0.1 % | -99.8 % |
| 16 KB, 128 x 32w | 149 | 0.1 % | -99.8 % |

**UdpIp** — 80,460 lookups, and the sharpest result in the table:

| geometry | misses | miss % | words filled |
|---|---|---|---|
| 2 KB, 16 x 32w — today | 18,636 | **23.2 %** | 335,501 |
| 4 KB, 16 x 64w — *2x size, same block COUNT* | 18,591 | **23.1 %** | **-0.2 %** |
| 2 KB, 32 x 16w — *same size, 2x block COUNT* | 116 | **0.1 %** | **-99.5 %** |
| 16 KB, 128 x 32w | 34 | 0.0 % | -99.9 % |

**Doubling the cache bought nothing; doubling the block COUNT at the same 2 KB
removed 99.5 % of the fill traffic.** UdpIp is almost purely
fragmentation-bound — the 128-byte block was the whole problem, and adding
capacity in bigger blocks just wasted more of it. Kfl needs both
(fragmentation alone 34.8 -> 13.5 %; capacity at constant block size
34.8 -> 0.6 %). **Lift was never affected** — 0.3 % at today's geometry,
matching item 50's finding that bytecode fill is 2.7 % of its stall.

**The knee is at 4 KB / 16-word blocks.** By 8 KB the miss count is ~149 for
Kfl against 142,395 lookups, i.e. each distinct method is loaded about once:
that is the compulsory floor, and 16 KB does not improve on it. There is no
reason to go past 8 KB.

**Scale of the prize.** Bytecode fill is ~33 % of all Kfl cycles and ~27 % of
UdpIp's (item 50). Cutting fill traffic by 99 % should recover most of that —
**an order of magnitude more than the 3-5 % the L2 was worth**, from a config
parameter rather than new microarchitecture. Needs hardware confirmation: this
sweep counts misses and words, and the stall those convert into has a
per-miss component as well as a per-word one.

**What it costs.** 4 KB doubles the JBC RAM (BRAM is not scarce on these
parts). 64 blocks means 64 tags x 18 bits and 64 comparators in the priority
cascade — which is exactly the `fmax` risk this item opened with, and exactly
why the compare should be pipelined. It fires once per invoke, not per fetch.

#### A latent truncation the 2 KB pin was hiding

The three largest geometries elaborated cleanly, booted, and then died —
`Small boot | GC init | GC done | CI | Uncaught exception`. Root cause:

```scala
case class MemCtrlOutput() { val bcStart = UInt(12 bits) }        // hardcoded
bcStartReg := (methodCache.io.bcStart ## U(0, 2 bits)).asUInt.resized
```

`bcStart` is the method's BYTE address inside JBC RAM, so it has to span the
whole cache. Hardcoded at 12 bits it spans exactly 4096 bytes, and `.resized`
**truncates silently** beyond that — no elaboration error, a clean boot, then
wrong jump targets the moment a method lands above 4 KB. Fixed by deriving the
width from `jpcWidth`; a no-op at the default, confirmed by the 2 KB row
reproducing bit-for-bit after the change.

Worth remembering as a pattern: **a pinned parameter hides every bug that only
appears when it changes.** The `require` did not just block the experiment, it
concealed the reason the experiment would have failed.

#### HARDWARE-VALIDATED 2026-08-19 — A-E115FB DDR2, +34.4 % on Kfl

Two bitstreams differing ONLY in method cache geometry, same board, same
binary, same 75 MHz:

| | 2 KB 16x32w (today) | 4 KB 32x32w | |
|---|---|---|---|
| **Kfl** | 9,367 /s | **12,593 /s** | **+34.4 %** |
| **UdpIp** | 4,316 /s | **5,512 /s** | **+27.7 %** |
| **Lift** | 13,931 /s | 13,943 /s | +0.1 % |

| | stall % | bytecode fill |
|---|---|---|
| Kfl 2 KB | 52.2 % | 62.8 % |
| **Kfl 4 KB** | **28.2 %** | **7.3 %** |
| UdpIp 2 KB | 51.9 % | 52.9 % |
| **UdpIp 4 KB** | **32.2 %** | **3.6 %** |
| Lift (both) | 30.0 % | 3.0 % |

**Two independent checks that this is the real mechanism.** The baseline
reproduces item 50's recorded A-E115FB row exactly — 9,367 /s against 9,362,
and 52.2/62.8/15.6/16.6/5.1 identical. And converting shares to ABSOLUTE cycles
per iteration, every other category is unchanged to the cycle: `idle/direct`
652 -> 652, statics 694 -> 692, indirection 213 -> 213. Only bytecode fill
moved, **2,625 -> 123 cycles per iteration**. The shares only look different
because the stall they are a share OF shrank.

Lift being flat is the control: item 50 put its bytecode fill at 3 % of stall,
the sweep put its miss rate at 0.3 %, and the hardware moved it 0.1 %.

**Timing closes at +0.446 ns** (Slow 1200mV 100C), 30 % of the part, 994,656
memory bits. So 32 comparators are not an `fmax` problem at 75 MHz and the
pipelined-compare work is NOT needed to bank this result — it is needed only to
go further (64 blocks, or 8 KB).

**Unexplained residual, recorded rather than glossed.** Stall fell 2,500
cycles/iteration but total cycles fell 2,051, so non-stall cycles rose ~450
(12 %). The counters here cannot say why. It does not affect the conclusion —
the throughput gain is measured directly — but it is not understood.

**This reorders the remaining work.** With bytecode fill gone, Kfl's largest
stall category is now **statics at 41.2 %** (692 cycles/iteration) followed by
`idle/direct` at 38.8 % (652). Those are exactly the "statics in on-chip RAM"
and "write buffer" levers below, which were third-order behind the method cache
and are now first and second.

#### DEFAULT CHANGED 2026-08-20 — 8 KB / 64 x 32w, validated on FOUR BOARDS

`JopCoreConfig` now defaults to `jpcWidth = 13, blockBits = 6`. Measured with
`DoAppPerf` at the new default:

| system | Kfl | UdpIp | Lift |
|---|---|---|---|
| Colorlight i5 — SDR 40 MHz (ECP5) | 7,098 | 3,098 | 7,861 |
| CYC5000 — SDR 80 MHz (Cyclone V) | 11,497 | 4,899 | 12,992 |
| A-E115FB — DDR2 75 MHz (Cyclone IV) | 12,641 | 5,512 | 13,931 |
| Wukong SDR — 100 MHz (Artix-7) | 16,318 | 6,995 | 18,034 |
| Wukong DDR3 — 100 MHz (Artix-7) | 16,855 | 7,347 | 18,586 |

**Against a matched 2 KB baseline on the same board and binary:**

| system | Kfl | UdpIp | Lift |
|---|---|---|---|
| A-E115FB DDR2 | 9,367 -> 12,641 **+35.0 %** | 4,316 -> 5,512 **+27.7 %** | **0 %** |
| Wukong SDR | 12,020 -> 16,318 **+35.8 %** | 5,479 -> 6,995 **+27.7 %** | -0.1 % |
| Wukong DDR3 | 12,487 -> 16,855 **+35.0 %** | 5,756 -> 7,347 **+27.6 %** | **0 %** |
| Colorlight i5 | 5,591 -> 7,098 **+26.9 %** | 2,560 -> 3,098 **+21.0 %** | **0 %** |
| CYC5000 SDR | 8,126 -> 11,497 **+41.5 %** | 3,693 -> 4,899 **+32.7 %** | **0 %** |

**All FOUR boards gain, Lift flat on every one** — the control behaving exactly
as item 50 and the sweep predicted. The three DRAM-ish systems agree to a tenth
of a percent (+35.0 / +35.8 / +35.0 % on Kfl), which is stronger than any single
measurement; the two SDR outliers move in the direction item 50 predicts.

**The spread tracks how memory-bound each board already was.** Item 50's stall
shares were CYC5000 61.4 %, A-E115FB 52.2 %, i5 46.4 % — and the Kfl gains rank
identically: CYC5000 **+41.5 %**, A-E115FB/Wukong **+35 %**, i5 **+26.9 %**. The
more of its time a board was losing to memory, the more removing method-cache
misses returns. That the ordering is preserved across three fabrics is a
consistency check on both measurements at once.

**The i5 gains less (+26.9 %) and that is consistent, not anomalous.** Item 50
put it at the LOWEST stall share of any board (46.4 % vs the CYC5000's 61.4 %):
its 32-bit SDRAM at 40 MHz costs fewer CYCLES per access, because DRAM timings
are fixed in nanoseconds and a slow clock buys more of them. Less memory-bound
to begin with, so less to recover.

**Timing closes everywhere, on three vendors and three toolchains:**

| board | timing | resources |
|---|---|---|
| A-E115FB (Quartus) | **+0.446 ns** — identical at 16/32/64/128 blocks | 35,976 LE (30 %) |
| CYC5000 (Quartus) | **+0.533 ns** setup, +0.390 hold | 4,060 ALM (43 %), memory **9 %** |
| Colorlight i5 (yosys/nextpnr) | **PASS — 53.95 MHz vs 40 target** | DP16KD 15/56 (26 %) |
| Wukong dual (Vivado) | -0.364 ns, its PRE-EXISTING `sdram_DQ` path | LUT 49.9 %, BRAM 31 % |

The method cache is nowhere near critical on any fabric, at any geometry tested
up to 128 blocks. Block memory at 9-31 % is what the change actually spends.

**8 KB/64 over 4 KB/32 is only +0.4 %** (A-E115FB 12,641 vs 12,593) — the
benchmarks are at the compulsory floor by 4 KB. The reason to default to 8 KB is
headroom for code with more resident methods than these three have, since block
COUNT caps residency; it is not something these benchmarks can show.

**The pin had frozen assumptions into 29 places.** Nine in the design (item 51
above) and TWENTY more in the test tree, all `Seq.fill(2048)`/`padTo(2048)` for
jbcInit — caught only because `JopJvmTestsBramSim` was run after the default
change (132 ok / 0 fail now, up from 126, as more tests fit the cycle budget).
Two traps worth remembering while fixing them: a `val` referencing a LATER `val`
in a Component body reads as null, so declaration ORDER had to be checked and
eight files were handled individually; and a bulk rewrite wrongly edited
`BytecodeFetchStageTest`, which legitimately pins its own geometry.

`NonDefaultGeometryElabTest` (`jop.config.*`, so CI runs it already) now probes
11/4 and 14/7 — either side of the new default. It was verified to FAIL when one
of the nine fixes was reverted, so it is a real guard.

#### CLOSED 2026-08-23 — both design options it left open were resolved by measurement, and neither needed building

This item opened by arguing that the tag compare caps the cache size, and listed
six ways to break that cap. Two of them were live after the default change. Both
are now closed, and **neither required any RTL**.

**Option 1, pipeline the tag compare — NOT NEEDED.** The premise was that 64
comparators in a priority cascade would cost `fmax`. Measured, they do not: the
default change closed timing on **four boards across three toolchains**, and on
the A-E115FB the slack was *identical at 16, 32, 64 and 128 blocks* (+0.446 ns).
The compare is nowhere near critical on any fabric tested. Pipelining it would
buy nothing that is currently paid for.

**Option 5, `clrVal` recomputed every cycle — MEASURED, NOT WORTH FIXING.** The
concern was stated as "harmless at 16 blocks; not harmless at 64", and the
default moved to 64, so it looked due. It is wrong at the threshold it names.

`MethodCacheVerilog` emits the component alone; out-of-context synthesis
(xc7a100t-2) against a serial-clear variant that walks the same block range one
per cycle, reusing the write decoder S2 already needs:

| geometry | blocks | parallel mask | serial | saving |
|---|---|---|---|---|
| `11/4` | 16 | 484 | 473 | 11 (2.3 %) |
| `12/5` | 32 | 982 | 951 | 31 (3.2 %) |
| **`13/6` — default** | 64 | **1,919** | 1,854 | **65 (3.4 %)** |
| `14/7` | 128 | 4,421 | 3,643 | **778 (17.6 %)** |

**`clrVal` is harmless at 64 after all** — 65 LUTs of 1,919, or 260 across four
cores in a build with ~6,000 spare. Vivado collapses the mask into carry chains
(128 CARRY4 at 64 blocks) rather than building `blocks` independent
subtract-and-compares, which is what the estimate-by-inspection missed. It only
becomes real at **128 blocks**, and the sweep above says nothing should go there:
64 blocks already reaches the compulsory floor (Kfl 179 misses, 128 blocks 149).

So the serial variant was written, measured, and **reverted**. It is strictly
smaller, but it lengthens every miss by `nrOfBlks` cycles and breaks three
`MethodCacheFormal` properties that encode "S2 is a one-cycle state" — those
would have to be rewritten to assert the new timing rather than relaxed. That is
real risk against 0.45 % of a build.

Two things worth keeping from it. **`MethodCacheVerilog` stays** — an
out-of-context synthesis of one component takes seconds against thirty minutes
for a full build, and it is the right tool for any future geometry question.
OOC totals are not build totals (no surrounding logic to pack against, no IP,
unconstrained boundary); they compare variants of one component, which is all
they were used for here. And **the gating instinct was itself wrong**: wrapping
the update in `when(state === S1)` would only add a clock enable — the
arithmetic still synthesises, so it would have saved toggling, not area.

**What is left in this item is not method-cache work.** With bytecode fill gone,
Kfl's top two stall categories are statics (41.2 %) and `idle/direct` (38.8 %),
which are now [item 54](#item-54) and [item 55](#item-55). Item 51 is closed.

#### Other levers on stall time, from the same measurements

- **Write buffer** — now [item 55](#item-55). `WRITE_WAIT` is *busy until `rsp.valid`*, and
  putfield/iastore/putstatic are unconditionally write-through — **the core
  stalls waiting for a write whose result it never uses**. Those cycles are
  inside `idle/direct`, 16-42 % of stall. Needs read-after-write forwarding and
  an SMP coherence story.
- **Statics in on-chip RAM** — now [item 54](#item-54). `statics` is a uniform **7-18 % of stall on every
  board and every workload** and no cache touches it. The region is small and
  known at link time; putting it in BRAM deletes the category. SMP needs a
  shared arbitrated port.
- **Method inlining in JOPizer.** A *software* lever on the dominant category:
  fewer, larger methods means fewer fills. Zero RTL risk, and it compounds with
  a bigger cache rather than competing.
- **Early restart on fill** — begin executing when the first block lands and
  prefetch the rest. Fill delivers 4 B/cycle while execution consumes ~1 B per
  1-3 cycles, so the fill outruns the consumer 4-12x and will nearly always
  stay ahead. Implementation is a loaded-watermark register plus one comparator
  on `jpc` (stall if `jpc >= watermark`) — far cheaper than a tag check per
  fetch. **WCET survives**: the worst case is still a full method load before
  useful progress, so today's bound remains a valid upper bound.
- **Handle-translation cache** for Lift's 57 % indirection. `ArrayCache` caches
  array *elements*; the dependent step is handle -> address. A small handle TLB
  collapses two round trips into one, and it is the only structural fix for
  indirection-bound code.
- **Hardware multithreading** — switch threads while a method loads. The only
  option that helps a dependent handle chase, which no prefetch can. But it
  needs duplicated `jpc`/`vp`/`sp` and stack state (item 14 is unfinished
  here), it complicates WCET badly, and throughput is already available from
  cores — 12 validated on the EP4CGX150. Lowest priority unless single-thread
  latency on indirection-bound code is specifically the goal.

**Suggested order.** Grounded in measurement: (1) remove the `require` and
sweep the method cache in simulation, (2) statics in BRAM, (3) write buffer.
Then hypotheses: (4) pipelined compare and whatever the sweep says the geometry
should be, (5) early restart, (6) L2 burst path (item 39) or method-fill bypass
— decide with an L2 hit-rate measurement split by requester, (7) handle cache,
(8) SMT. Everything from (4) on should be simulated before RTL; item 50 gave us
both the harness and the hardware counters to check the answer against.
