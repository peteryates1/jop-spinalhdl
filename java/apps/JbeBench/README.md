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

## First result

12-core EP4CGX150 bitstream, core 0, 36 MHz, generational GC:

```
Kfl    4401 1/s
UdpIp  1961 1/s
Lift   5872 1/s
```
