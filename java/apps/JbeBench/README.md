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
