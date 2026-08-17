# JbeBench — JavaBenchEmbedded on JOP-SpinalHDL

The classic JOP application benchmark suite, ported from
`/srv/git/jop.original/java/target/src/bench/jbe`. Exists to answer
current-status **item 11**: five design decisions were being made by reasoning
rather than measurement.

## Running

```
make -C java/apps/JbeBench
python3 fpga/scripts/download.py -e java/apps/JbeBench/JbeBench.jop <tty> <baud>
```

Entry point is `jbe.DoApp`: **Kfl** (a real embedded control application),
**UdpIp** (a network stack) and **Lift** (a lift controller). Results are
iterations per second.

## Two things to know before quoting a number

**Results scale with the clock, and the clock varies by core count.** 36 MHz on
a 12-core EP4CGX150, 60 at 4 cores, 80 single-core, 91.68 or 100 on Wukong DDR3.
Always quote the clock, and normalise per MHz before comparing configurations —
otherwise a "faster" result may only mean a higher clock.

**The timebase is `IO_US_CNT`, whose prescaler comes from the preset's
`clkFreq`.** If that disagrees with the real clock, every number here is wrong
by the same ratio while looking entirely plausible. A benchmark is the one place
that mis-scaling is invisible, so check the boot banner. This is what the
generated PLL (`DramPllGen`) and the MIG profile check exist to prevent.

## What was changed from upstream

Deliberately as little as possible, so results stay comparable with the
published JOP figures:

- `LowLevel.java` — rewritten. Upstream calls `System.currentTimeMillis()`,
  which this runtime does not implement; it now reads `IO_US_CNT`. Everything
  else it needs (`System.out.print` for int/String, `println()`) was already
  present.
- `LoopKfl`, `LoopLift`, `LoopUdpIp`, `RunBench` — these standalone drivers
  called `System.currentTimeMillis()` directly; repointed at `LowLevel`.
- `lift/LiftControl.java` — converted Latin-1 to UTF-8. Comment bytes only, no
  code change. The rest of this tree is UTF-8, and a single `javac -encoding`
  cannot cover both.
- `DoAll.java` — dropped. It pulls in a `Jitter` class that lives outside this
  package upstream. `DoApp` is the application suite and is what item 11 wants.

The workloads themselves (`kfl/`, `lift/`, `udpip/`, `micro/`) are untouched.

## Baseline results (EP4CGX150, generational GC)

| benchmark | 1 core @ 80 MHz | 12-core bitstream, core 0 @ 36 MHz |
|---|---|---|
| Kfl | 7742 1/s | 4401 1/s |
| UdpIp | 3524 1/s | 1961 1/s |
| Lift | 12681 1/s | 5872 1/s |

### The first thing the numbers say

Normalised per MHz, the LOWER clock does more work per cycle on two of three:

| benchmark | 80 MHz | 36 MHz | per-MHz gain at 36 |
|---|---|---|---|
| Kfl | 96.8 /MHz | 122.3 /MHz | **+26 %** |
| UdpIp | 44.1 /MHz | 54.5 /MHz | **+24 %** |
| Lift | 158.5 /MHz | 163.1 /MHz | +3 % |

That is the signature of **memory-latency-bound** code. SDRAM latency is fixed
in nanoseconds, so it costs fewer CYCLES at a lower clock; a workload that
spends its time waiting on memory therefore looks more efficient per cycle as
the clock drops. Lift, at +3 %, is compute-bound and scales with the clock as
you would expect.

**That inference was half wrong, and the cache A/B below shows why** — see the
correction there before reusing this reasoning.

Two consequences worth carrying into items 5, 24 and 31:

- **Raising the core clock buys less than it appears to** for Kfl and UdpIp.
  That reframes the arbiter trade: a pipelined arbiter costing a cycle per
  access is a much worse deal for memory-bound workloads than a clock-focused
  reading of item 5 would suggest.
- **Kfl and UdpIp are the workloads to use when judging the caches** (do they
  remove the stalls?), and Lift is the one to use when judging compute changes
  such as the double bytecodes in item 20.

Caveat on the comparison: the two builds differ in core count as well as clock
(1 vs 12, the other 11 idle). Idle cores issue no bus traffic and arbiter
latency in cycles is unchanged, so the clock is the dominant term — but a clean
single-variable measurement would rebuild one core at 36 MHz.

## Cache A/B — do the caches earn their area? (item 11)

`ep4cgx150NoCache` is `ep4cgx150Serial` with `useOcache` and `useAcache` off and
nothing else changed. Single core at 80 MHz, so the caches are the only variable.

| benchmark | with caches | no caches | gain from caches |
|---|---|---|---|
| Kfl | 7742 1/s | 7386 1/s | **+4.8 %** |
| UdpIp | 3524 1/s | 2761 1/s | **+27.6 %** |
| Lift | 12681 1/s | 6400 1/s | **+98.1 %** |

Area: **8,784 LE with, 6,386 without** — the caches cost **2,398 LE**, +37.6 % on
a cacheless core (and corroborating the ~2,213 LE/core figure item 11 quotes).

**Verdict: they earn it.** +37.6 % area for +98 % on Lift and +28 % on UdpIp is a
trade nothing else in the core comes close to. Kfl at +4.8 % is the one case
where it is marginal.

### The prediction this refuted

From the clock-scaling data above I expected Lift — which scaled almost linearly
with clock, +3 % per MHz — to be compute-bound and barely move without caches,
and Kfl/UdpIp to suffer most. **Exactly the opposite happened.**

The resolution is that clock-scaling measures whether a workload is memory-bound
**in its current configuration**, not whether it is memory-INTENSIVE. Lift is
memory-intensive but its working set fits the caches, so with caches it rarely
reaches SDRAM and therefore scales with the clock like compute-bound code.
Remove the caches and it goes to SDRAM constantly and halves. Kfl and UdpIp have
working sets the caches serve less well, so they stay partly memory-bound even
with caches — which is why they showed the per-MHz gain at 36 MHz AND gain less
from the caches.

Worth keeping, because the same mistake is easy to repeat: **"scales with the
clock" means "not currently waiting on memory", not "does not touch memory".**
Only an A/B against the hardware feature itself distinguishes the two.

## Memory scaling curve — does adding cores actually help? (items 5, 11, 31)

`jbe.Scale` runs a **private** 64 KB working set on every core simultaneously
(stride-walked, read-modify-write) and sums the throughput. EP4CGX150, SDR
SDRAM, **clock held at 36 MHz for every point** so core count is the only
variable.

| cores | aggregate | speedup | efficiency | per core |
|---|---|---|---|---|
| 1 | 188 kacc/s | 1.00x | 100 % | 188 |
| 4 | 605 kacc/s | **3.22x** | 80 % | 151 |
| 8 | 635 kacc/s | 3.38x | 42 % | 79 |
| 12 | 664 kacc/s | 3.53x | 29 % | 55 |

**The shared memory path saturates at about 4 cores.** Going 4 -> 8 buys 5 %.
Going 8 -> 12 buys another 5 %. The 12-core build delivers **3.5x** a single
core, not 12x, and per-core throughput falls almost exactly in proportion to
core count beyond 4 — the signature of a fully serialised resource.

### What this settles

- **Do not pipeline the arbiter to raise the clock.** Item 5 framed the trade as
  "is a cycle of arbiter latency worth 4+ cores". On this workload the question
  does not arise: past 4 cores the limit is memory SERVICE RATE, not the clock,
  so buying clock at the cost of a cycle per access would make things worse.
- **4 cores is the sweet spot for memory-bound work on SDR.** 3.22x at 80 %
  efficiency, and it needs only 60 MHz rather than 36, so it wins on both axes.
  Today's 8- and 12-core builds are a correctness achievement, not a throughput
  one.
- **The next lever is the memory path, not the core count** — wider SDRAM, a
  shared L2, or per-core banking. That reframes items 5 and 31 from "make the
  arbiter faster" to "give the arbiter more to arbitrate".

### Caveats, stated because they bound the claim

- This is a **pure memory probe**, deliberately: every JBE workload is
  single-core code built on static state (Kfl's `BBSys` alone has 52 statics),
  so running one on N cores gives N cores mutating one state machine, not N
  instances. The first version of this harness did that and the 4-core run
  never terminated — a bug here, not a hardware finding.
- Real applications mix compute with memory, so they will scale BETTER than this
  curve. It is the pessimistic bound: a workload that does nothing but touch
  memory. Kfl's own mix would sit somewhere above it.
- SDR only so far. The DDR3 path has an `LruCacheCore` L2 in front of MIG and
  may saturate very differently — that measurement is still to do.

## DDR3 scales no better — the saturation is not the SDR controller

Wukong XC7A100T, DDR3 through `LruCacheCore` (32 KB L2) and MIG, `Ddr3_366`
profile so ui_clk is held at 91.68 MHz for every point.

| cores | SDR @36 MHz | DDR3 @91.68 MHz |
|---|---|---|
| 4 | 605 kacc/s | 733 kacc/s |
| 8 | 635 kacc/s (+5 %) | 754 kacc/s (**+3 %**) |

**DDR3 saturates at least as hard as SDR.** Doubling from 4 to 8 cores buys 3 %,
against SDR's 5 %. A 32 KB write-back L2 and several times the raw bandwidth do
not change the shape of the curve at all.

That is the useful part. It says the ceiling is **not** the SDR controller being
slow, and not raw bandwidth — otherwise DDR3 would have moved the knee. What
both paths share is a single arbiter funnelling every core into one memory port,
and a working set deliberately larger than any cache. So the bottleneck is
**serialisation at the shared port**, and it is a property of the topology
rather than of either memory technology.

Which sharpens the conclusion from the SDR curve: more cores past ~4 need a
**wider or split memory path** — more ports, banking, per-core slices — not a
faster controller and not a faster arbiter. Buying a bigger memory technology is
the option the data specifically rules out.

Note DDR3 at 91.68 MHz does only ~1.2x the SDR figure at 36 MHz despite 2.5x the
clock, which is consistent with the same story: both are limited by the port,
not the core.

### The complete DDR3 curve, and what the two together say

| cores | SDR @36 MHz | DDR3 @91.68 MHz |
|---|---|---|
| 1 | 188 (1.00x) | 430 (1.00x) |
| 2 | — | 612 (1.42x) |
| 4 | 605 (3.22x) | 733 (1.70x) |
| 8 | 635 (3.38x) | 754 (1.75x) |
| 12 | 664 (3.53x) | — |

**DDR3 buys per-core speed and loses scaling.** One DDR3 core does 430 kacc/s
against SDR's 188 — but DDR3 is already at 57 % of its ceiling with ONE core and
81 % with two, where SDR needs four cores to reach 91 % of its own. The DDR3 knee
is between 1 and 2 cores; the SDR knee is around 4.

**And the ceilings are nearly the same: 754 vs 664, just 14 % apart**, despite
DDR3 running at 2.5x the clock with far more raw bandwidth and a 32 KB L2 in
front of it.

Per MHz makes it starker:

| | SDR @36 | DDR3 @91.68 |
|---|---|---|
| 1 core | 5.22 kacc/s/MHz | 4.69 |
| 8 cores | 17.64 kacc/s/MHz | 8.22 |

**Per cycle, SDR is not worse — it is better.** DDR3's single-core advantage is
almost entirely its clock, and at 8 cores SDR delivers more than twice the work
per cycle. That is only explicable if both are limited by something neither
technology changes.

I concluded from this that the limit was the **single shared port**, common to
both paths — and that scaling needed more ports rather than faster memory.

**THAT WAS WRONG. See the same-board comparison below, which refutes it.** The
two ceilings looked alike only because the SDR curve came from a different,
slower board. Running both memory systems on ONE board separates them
completely.

### Measurement notes

- The 1-core DDR3 point needs the NON-SMP flow. `wukongDdr3Smp 1` emits
  `JopDdr3WukongTop`, not `JopSmpDdr3WukongTop`, so `ddr3-smp-build` silently
  reuses a stale SMP Verilog — which is how the first attempt produced a
  bitstream whose post-route WNS was byte-identical to the 8-core build. Use
  `make ddr3-build` for one core. Same trap as `ep4cgx150Smp 1`.
- **The UART is lossy at 91.68 MHz** and it has now bitten twice: one run
  printed `AGGREATE`, another `AGREGATE` and `kacc/G`. The board is forced to
  2.0372 Mbaud (`clkFreq / (baud x 5)` has no nice solution at this clock) and
  the host cannot generate that rate exactly. The NUMBERS were fine both times —
  2-core read `306 + 306 = 612` consistently — but scraping scripts reported
  false failures. Match loosely, and read the raw log before believing a run
  failed.

## SDR vs DDR3 on ONE board — this refutes the section above

Same Wukong XC7A100T, same fabric, same benchmark; only the memory path differs.
SDR at 100 MHz (a free clk_wiz output, and an exact 2 Mbaud), DDR3 at its
ui_clk-imposed 91.68 MHz.

| cores | SDR @100 MHz | DDR3 @91.68 MHz | SDR / DDR3 |
|---|---|---|---|
| 1 | 621 (1.00x) | 430 (1.00x) | 1.44x |
| 4 | **2090** (3.37x) | 733 (1.70x) | **2.85x** |
| 8 | **2764** (4.45x) | 754 (1.75x) | **3.67x** |

**SDR is not merely competitive — it is 3.7x faster at 8 cores, and it keeps
scaling where DDR3 stops.** SDR reaches 4.45x on eight cores; DDR3 manages 1.75x
and is done by two.

Per MHz at 8 cores settles which curve is the outlier:

| | kacc/s/MHz |
|---|---|
| Wukong SDR | **27.6** |
| EP4CGX150 SDR | 17.6 |
| Wukong DDR3 | 8.2 |

The two SDR figures are the same order; DDR3 is a third of them. **DDR3 is the
outlier, not the norm** — so there is no universal "shared port" ceiling.

### The corrected conclusion

- The EP4CGX150 plateau at 664 was a **clock** limit (36 MHz), not a port limit.
  The same SDR controller at 100 MHz on Wukong reaches 2764.
- **The DDR3 path is the bottleneck**, and specifically ours: MIG plus
  `LruCacheCore` plus `CacheToMigAdapter`. It saturates with barely two cores.
  Raw DDR3 bandwidth is not the constraint — the path we built on top of it is.
- **Do not add ports or banking to the SDR path.** It scales 4.45x on eight
  cores; there is no problem there to solve.
- The DDR3 path is worth investigating on its own terms: a stride walk defeats a
  32 KB L2, so every access pays full MIG latency, and the adapter may be
  serialising more than it needs to. That is now a concrete, bounded question.

### Why the earlier reading went wrong, and what to take from it

Comparing EP4CGX150-SDR against Wukong-DDR3 varied board, fabric, clock AND
memory system together, then attributed the result to the one variable I was
interested in. Two ceilings landing within 14 % looked like strong evidence for
a common cause; it was a coincidence of two unrelated limits. **A same-board A/B
was the cheap experiment that separated them, and it inverted the answer** —
the same lesson as the cache A/B earlier, which also refuted a prediction drawn
from indirect evidence.
