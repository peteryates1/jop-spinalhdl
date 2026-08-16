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