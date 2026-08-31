# Item 50 — ~~Memory-stall profile measured on real memory, on hardware — DONE~~

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 50](../current-status.md#item-50).

---

**DONE 2026-08-19.** The BRAM profile in item 37 was never going to transfer,
and it did not. Measured with the `IO_PERFCNT` counters on three boards
covering two memory technologies, two FPGA vendors and three toolchains, plus
an SDR simulation as a cross-check.

| board | bench | stall % | bytecode fill | idle/direct | statics | indirection |
|---|---|---|---|---|---|---|
| A-E115FB **DDR2** 75 MHz | Kfl | 52.2 % | **62.8 %** | 15.6 % | 16.6 % | 5.1 % |
| | UdpIp | 51.9 % | 53.0 % | 19.0 % | 7.8 % | 17.1 % |
| | Lift | 30.0 % | 3.1 % | 29.5 % | 6.8 % | **60.7 %** |
| CYC5000 SDR 80 MHz | Kfl | 61.4 % | 56.8 % | 20.9 % | 18.1 % | 4.4 % |
| | UdpIp | 60.8 % | 46.6 % | 25.2 % | 9.4 % | 15.6 % |
| | Lift | 38.9 % | 2.2 % | 42.1 % | 8.0 % | 48.0 % |
| Colorlight i5 SDR 40 MHz | Kfl | 46.4 % | 61.5 % | 16.1 % | 17.1 % | 5.5 % |
| | UdpIp | 46.0 % | 50.3 % | 19.8 % | 8.6 % | 18.2 % |
| | Lift | 26.0 % | 4.0 % | 29.7 % | 7.6 % | 59.2 % |

(indirection = handle deref + element + bounds check)

**Simulation validated.** `DoAppMemTimeSdrSim` predicted Kfl on SDR at bytecode
58.7 %, idle/direct 18.4 %, statics 17.7 %, indirection 5.2 % — inside the
spread of two boards with different FPGA families, clocks and bus widths. The
simulated stall share (53.9 %) also sits between the measured 46.4 % and
61.4 %. The method can be trusted where hardware is unavailable.

**Three conclusions that hold on every board:**

1. **The workload decides, not the memory technology.** Kfl and UdpIp are
  method-cache bound; Lift is indirection bound at 48-61 % with bytecode fill
  at 2-4 %. The DDR2 board looks far more like the SDR boards than Lift looks
  like Kfl. Optimising for "the memory system" is the wrong frame; optimising
  for a workload is the right one.
2. **`idle/direct` is real and was invisible on BRAM.** 16-42 % of stall,
  against **0 %** on BRAM where `READ_WAIT`/`WRITE_WAIT` complete next cycle.
  Item 37's "strike it from the attack surface" was a BRAM artefact.
3. **Statics are a uniform 7-18 %** that no cache touches, largest on Kfl.

**Ranking by weighted stall across all three benchmarks and boards:** bytecode
fill first, indirection second, idle/direct third, statics fourth — but the
ordering INVERTS between Kfl and Lift, so the only honest recommendation is to
pick the target from the workload that matters.

**Wukong DDR3 added 2026-08-19**, once its UART was retargeted from the
untappable CH340N pins to the J11 header:

| board | bench | stall % | bytecode fill | idle/direct | statics | indirection |
|---|---|---|---|---|---|---|
| Wukong **DDR3** 91.7 MHz | Kfl | 52.2 % | 62.8 % | 15.6 % | 16.6 % | 5.1 % |
| | UdpIp | 51.9 % | 53.0 % | 19.0 % | 7.8 % | 17.1 % |
| | Lift | 30.0 % | 3.1 % | 29.5 % | 6.8 % | 60.7 % |

**DDR2 and DDR3 are INDISTINGUISHABLE per cycle — all 15 numbers match to
0.1 %.** Not a copy-paste: the captures are different files with different
rates, and the raw cycle counts differ by 0.002 %.

| | DDR2 75 MHz | DDR3 91.7 MHz | ratio |
|---|---|---|---|
| Kfl | 9,362 /s | 11,361 /s | 1.213 |
| UdpIp | 4,316 /s | 5,234 /s | 1.213 |
| Lift | 13,943 /s | 16,916 /s | 1.213 |
| clock | 75 MHz | 91.676 MHz | **1.222** |

**DDR3's entire advantage over DDR2 is clock frequency**; per cycle it is
marginally worse (0.993x). The reason is structural, not coincidence: both DRAM
paths are `BmbCacheBridge -> LruCacheCore -> adapter` and differ only in the
adapter, so the core sees the same 32 KB L2 and the DRAM generation behind it is
nearly invisible. `createSdr` has **no L2 at all**, which is why the two SDR
boards look different from the DRAM pair and from each other.

That reframes the DRAM work: the L2 is what the core experiences, so an L2
improvement (item 39, the 3-cycle serial hit path) should move both DRAM boards
identically, while a DRAM-side change should move neither much.

**Cost of measuring.** Eleven 32-bit counters, off by default. The first
A-E115FB build FAILED timing at **-0.654 ns** (+0.510 without them);
registering the category decode one cycle before the increment recovered it to
**+0.911 ns**, better than the baseline. The counts are aggregates so a uniform
one-cycle shift is invisible.

**The dual-system run — DONE 2026-08-19. A 32 KB L2 is worth 3-5 %.**

`wukongDualIndependent` puts an SDR system and a DDR3 system on one die with a
UART each, profiled **simultaneously**: same silicon, same 100 MHz, same
ambient, same binary, one bitstream. That removes the confound that makes
CYC5000 (61.4 % stall) versus i5 (46.4 %) hard to read, and it is the only
measurement here where "L2 or no L2" is the sole variable — `createSdr` has no
L2, the DDR3 path is `BmbCacheBridge -> LruCacheCore(32 KB) -> adapter`.

| half | bench | stall % | bytecode fill | idle/direct | statics | indirection |
|---|---|---|---|---|---|---|
| **SDR, no L2** 100 MHz | Kfl | 54.1 % | 60.2 % | 17.6 % | 16.9 % | 5.4 % |
| | UdpIp | 54.3 % | 49.8 % | 21.3 % | 8.6 % | 17.1 % |
| | Lift | 32.1 % | 2.7 % | 32.6 % | 7.9 % | 56.7 % |
| **DDR3, 32 KB L2** 100 MHz | Kfl | 52.2 % | 62.8 % | 15.6 % | 16.6 % | 5.1 % |
| | UdpIp | 51.9 % | 52.9 % | 19.0 % | 7.8 % | 17.1 % |
| | Lift | 30.0 % | 3.0 % | 29.5 % | 6.8 % | 60.7 % |

**Throughput at identical clock:** Kfl 12,020 -> 12,487 (**+3.9 %**), UdpIp
5,479 -> 5,756 (**+5.1 %**), Lift 18,044 -> 18,586 (**+3.0 %**).

**Noise floor 0.1 %**, from two full repeat runs: the DDR3 half is
*bit-identical* run to run and the SDR half moves under 0.1 %. The effect is
~40x the noise. (The asymmetry is itself informative — the SDR half's jitter is
refresh landing differently against the workload, while the L2 keeps the DDR3
half off the DRAM often enough to be deterministic.)

**Where the L2 actually helps — per benchmark ITERATION, DDR3 vs SDR:**

| bench | bytecode fill | idle/direct | statics | indirection | stall | total |
|---|---|---|---|---|---|---|
| Kfl | -3.1 % | **-17.5 %** | -8.8 % | -12.1 % | -7.1 % | -3.7 % |
| UdpIp | -3.2 % | **-18.9 %** | -16.9 % | -9.1 % | -8.9 % | -4.8 % |
| Lift | +0.0 % | **-17.8 %** | **-22.1 %** | -2.7 % | -9.1 % | -2.9 % |

**The L2 helps least exactly where each workload hurts most.** Kfl and UdpIp
are method-cache bound — bytecode fill is 60 % and 50 % of their stall — and
the L2 takes only 3 % off it. Lift is indirection bound at 57 % of stall, and
the L2 takes 2.7 % off that. What the L2 *does* reliably fix is `idle/direct`,
a uniform -18 % on all three, and statics; but those are the smaller
categories, so the end-to-end result is 3-5 %.

That is the sharpest version of the conclusion this whole item has been
circling: **the L2 AS BUILT is not the lever.** It buys about as much as a 4 %
clock bump.

**Read the scope of that claim carefully.** 3-5 % is *this* 32 KB L2 versus *no*
L2. It bounds what the current implementation contributes; it does NOT bound
what a better one could, and the gap to ideal memory is still the 34-55 % of
item 38.

**Sharpened 2026-08-22 on the Alchitry Au: the L2's size buys nothing ON THIS
BENCHMARK.** (Read the `ScaleL2` section below before acting on that — on
data-heavy and multicore work the size is worth up to 33 %.) The
3-5 % above is existence-vs-absence, measured across two boards. The Au allows
the cleaner test — one board, one binary, one parameter (`l2SetCount`) — because
the XC7A35T forced the question: the preset does not fit the part with the
default 512-set L2.

| sets | L2 | LUTs | util | WNS | Kfl | UdpIp | Lift |
|---|---|---|---|---|---|---|---|
| 64 | 4 KB | 9,211 | 44 % | +0.477 | 16,855 | 7,353 | 18,586 |
| 256 | 16 KB | 12,975 | 62 % | +0.146 | 16,855 | 7,353 | 18,575 |
| 512 | 32 KB | 18,384 | over | — | does not fit (needs 22,554 of 20,800) |

**Kfl and UdpIp are bit-identical at 4x the cache**, and Lift is 0.06 % *lower*
— noise. Two independently built bitstreams agreeing to the digit is not a
result you get by luck.

**And the L2's EXISTENCE is worth far more than 3-5 %.** Dropping to a 2-set
(128 B) L2 — 8 lines total, the practical floor, and a fair proxy for no cache:

| sets | L2 | Kfl | UdpIp | Lift |
|---|---|---|---|---|
| 2 | 128 B | 12,064 | 4,937 | 14,813 |
| 64 | 4 KB | **16,855** (+39.7 %) | **7,353** (+48.9 %) | **18,586** (+25.5 %) |

**This does NOT contradict the 3-5 % above — it reinterprets it.** The two
measure different things:

- the dual-system run compared **SDR with no L2** against **DDR3 with a 32 KB
  L2**. Memory technology and caching move together, so the +3.9 % is the
  combination, not the cache.
- this compares **DDR3 with 128 B** against **DDR3 with 4 KB**. Same memory,
  same board, same binary, one parameter.

The reconciliation is that **bare DDR3 is much worse than bare SDR**, and the
L2 is what rescues it. The numbers line up: the dual-system SDR-no-L2 half did
Kfl 12,020, and DDR3 with an effectively absent L2 does 12,064. DDR3+L2 only
just beating SDR-no-L2 never meant the cache was weak; it meant DDR3's raw
latency is bad enough to need one.

(The 12,487 vs 16,855 gap between the two DDR3 figures is not a discrepancy
either — the dual-system run predates the 8 KB/64-block method cache default of
2026-08-20. 12,487 x 1.35 = 16,858.)

So the claim that survives is narrower and sharper: **an L2 in front of DRAM is
essential and cheap — 4 KB of it. Everything past 4 KB is what buys nothing.**
The earlier framing, "the L2 AS BUILT is not the lever", was drawn from a
comparison that could not isolate it, and is withdrawn. What remains true is
that making the DRAM *behind* the L2 faster is spent — that part was measured
directly (DDR2 vs DDR3 per cycle, 0.993x).

That size result is consistent with the bytecode-fill mechanism above — fill is
a sequential burst whose cost is the 3-cycle hit path (item 39), not capacity,
and a burst does not care how many sets sit behind it.

**The cost side is what makes this matter.** At 512 sets `LruCacheCore` is
**12,348 LUTs — more than twice the entire JOP core (5,576)** — measured
post-route on `wukongAuMatch`, a Wukong preset built to mirror `auSerial` field
for field so the two arms differ only in the board:

| block | LUTs | share |
|---|---|---|
| `lruCacheCore_1` | 12,348 | 54 % |
| `jopCluster_1` (whole CPU) | 5,576 | 24 % |
| `migBlackBox` (Xilinx MIG) | 4,385 | 19 % |

The cost is sharply **non-linear**: 64->256 sets costs 3,764 LUTs, 256->512
another 5,409. Most of the L2's logic is in its last doubling, and the same
~9,500-LUT step appears at every core count (2-core 34,335 -> 43,884; 4-core
63,697 -> 73,252).

**What scales is NOT the FIFOs.** An earlier revision of this item blamed
`rspFifo` (3,966 LUTs) and `orderFifo` (2,440) and called it the
`readAsync`-becomes-distributed-RAM signature from
[../analysis/artix7-distram-optimization.md](../analysis/artix7-distram-optimization.md).
That was wrong: `orderFifo` is **2 deep x 2 bits** (`mshrIdxWidth =
log2Up(mshrCount) max 1`) in every build and cannot be either of those numbers.
Those figures are Vivado's hierarchical report charging merged parent logic to a
child instance, and they move with `setCount` — which a fixed-size FIFO cannot.

The real driver is in `LruCacheCore.scala:136,138`:

```scala
val validFlat = Vec(Reg(Bool()) init (False), setCount * wayCount)
val lruArray  = if (plruBits > 0) Vec(Reg(Bits(plruBits bits)) init (0), setCount) else null
```

**Register arrays indexed by set**, while the tags, data and dirty bits are
proper `Mem`/`readSync` in BRAM — which is exactly why RAMB36 stays at 10 while
LUTs and flops explode. The arithmetic confirms it: 64->512 sets predicts
(512-64) x (4 + 3) = 3,136 more flops, and 3,326 were measured, 94 % accounted
for. Indexing 512 registers needs a wide read mux and a 512-way write decoder
with per-register enables; that is where the LUTs go.

**DONE 2026-08-22 — both arrays moved to BRAM, and the L2's LUT cost is now
flat in capacity.** `lruMem` (PLRU, needs no reset) and `validMem` (valid bits,
cleared by a new INIT state that walks every set before IDLE). Measured on
`wukongAuMatch` at 512 sets:

| | original | +PLRU | **+valid** |
|---|---|---|---|
| total LUTs | 22,849 (36.0 %) | 20,558 (32.4 %) | **12,384 (19.5 %)** |
| `lruCacheCore` | 12,348 | 10,060 | **1,793** |
| flip-flops | 14,921 | 13,574 | 11,158 |
| BRAM tiles | 17.0 | 17.5 | 18.0 |
| WNS | +0.422 | +0.305 | +0.246 |

**`lruCacheCore` fell 85 %** for one extra BRAM tile. The valid bits were worth
8,174 LUTs against PLRU's 2,291 — the opposite of the "easy half is the big
half" guess, and predictable in hindsight: valid is read PER WAY during tag
compare while PLRU is one indexed read, so its access logic was always wider.

**The non-linear cost curve is gone.** "64->256 sets costs 3,764 LUTs, 256->512
another 5,409" described the register-array implementation, not cache capacity.
At 4 cores the design is now 62,583 LUTs at 512 sets and 62,592 at 256 — **nine
apart**. L2 capacity costs BRAM and essentially no logic, so shrinking the L2 is
no longer a lever for fit.

**Settled: do NOT remove the L2.** The question was whether a board this tight
should drop it entirely. It should not — it is worth 25-49 % for ~3,300 LUTs at
64 sets, which is the best throughput-per-LUT in the design. A bypass path
would be the wrong thing to build.

#### `ScaleL2` — the L2's size DOES matter, on data-heavy and multicore work

Everything above is `JbeBench` (Kfl/UdpIp/Lift), which is **instruction-fetch
bound with a small data working set** and therefore structurally blind to the
L2 — which is why 4 KB and 32 KB came out bit-identical on it. Acting on that
alone would have been a mistake, and an earlier revision of this item proposed
exactly that (lower the default to 64 sets everywhere).

`java/apps/JbeBench/src/jbe/ScaleL2.java` (new, 2026-08-22) sweeps the per-core
private working set across the L2's capacity, holding total work constant. It
reuses `Scale`'s multicore harness — cores parked on `IO_CPU_ID`, released by
`IO_SIGNAL` — with a phase barrier so every core is on the same size while it is
timed. It exists because the obvious probe, "run Kfl on N cores", is blocked:
every JBE workload is built on static state (Kfl's `BBSys` alone has 52
statics), so N cores mutate one state machine. `Scale`'s own header records that
the first attempt at that never terminated on 4 cores.

**2 cores, same design, only `l2SetCount` differs** (aggregate kacc/s):

| per core | aggregate set | 512 sets (32 KB) | 64 sets (4 KB) | ratio |
|---|---|---|---|---|
| 1 KB | 2 KB | 1,199 | 1,196 | 1.00x |
| 4 KB | 8 KB | **1,128** | **860** | **1.31x** |
| 16 KB | 32 KB | **1,146** | **864** | **1.33x** |
| 64 KB | 128 KB | 940 | 798 | 1.18x |

Identical where both caches hold the aggregate set, and the large cache ahead by
a third once it does not. Note the benefit **decays rather than vanishing** past
capacity — at 128 KB, four times the larger cache, 32 KB is still 18 % ahead,
because a strided walk keeps catching more hits in a bigger cache.

**What counts is the AGGREGATE working set, not the per-core one.** At 4 cores
with a 4 KB L2 the cliff is far worse — 1,474 kacc/s at 1 KB/core (4 KB
aggregate, exactly the cache) collapsing to **752** at every larger size, a 96 %
drop. So N cores need roughly N x the L2 to keep the same per-core residency.

**Scaling is sub-linear even entirely inside the cache**: 661 (1 core) ->
1,199 (2 cores, 1.81x) -> 1,474 (4 cores, 2.23x). Most of the 2x is recovered
going to two cores and almost nothing after, so the shared path saturates
between two and four cores, well before DRAM bandwidth binds. These builds run
`l2MshrCount = 1`, which serialises misses — see items 40/42.

**Conclusion: the 512-set default stays.** It is worth up to 33 % on data-heavy
multicore work and costs nothing on real code. The Au keeps `l2SetCount = 64`
because it cannot fit otherwise, and its real-code throughput is unaffected — but
that is a board-specific concession, not a new default.

**The core-count-dependent default is no longer needed** — it was a workaround
for a cost that has now been removed. It used to be that more cores wanted more
L2 while leaving less LUT budget for it, and a 4-core build at 512 sets needed
~69,850 LUTs of 63,400. After the BRAM change a 4-core build with the FULL
32 KB L2 fits in **57,297 LUTs (90.4 %), WNS +0.112 ns** — fewer LUTs and four
times the slack of the old 4-core build that could only manage 4 KB (58,550,
+0.027 ns). Measured on hardware, that is worth up to **2.06x**:

| per core | aggregate | 4 KB L2 | 32 KB L2 | |
|---|---|---|---|---|
| 1 KB | 4 KB | 1,474 | 1,562 | +6 % |
| 4 KB | 16 KB | 752 | **1,548** | **+106 %** |
| 16 KB | 64 KB | 752 | **1,064** | +41 % |
| 64 KB | 256 KB | 752 | 752 | — |

So keep one default (512 sets) at every core count.

**Caveat on `ScaleL2`**: it is a data probe, not an application. `docs/` is
emphatic that JbeScale-derived numbers must be checked against DoApp before
being acted on, and the same applies here. It answers "does L2 capacity matter
to the memory system under N cores", not "how much faster is real code" — for
which the answer above is still "not at all".

**`l2SetCount = 1` does not elaborate**: `Vec address width mismatch —
lruArray : Vec of 1 elements, Address width : 1`, because `log2Up(1) = 0` while
the index is still generated 1 bit wide. A degenerate-case bug in
`LruCacheCore`, found by pushing the parameter to its edge. 2 sets is the
practical floor. The failure names a `Vec` rather than the parameter that caused
it, so at minimum this wants a `require` with a readable message.

In fact the shape of the data points the other way for **item 39** (the 3-cycle
serial hit path). Bytecode fill is a SEQUENTIAL BURST: the first word misses the
L2, the rest should be cheap hits. It improved by only 3 %. If an L2 hit costs 3
cycles, those hits are no faster than SDR page-mode reads — which would explain
why a 32 KB cache backed by DDR3 bandwidth buys almost nothing on the category
that owns 60 % of Kfl's stall. Bytecode fill is ~33 % of ALL Kfl cycles, so a
hit path that actually beat page mode could be worth well more than 3-5 %.

That is a HYPOTHESIS this measurement is consistent with, not a result. It is
also cheap to test in simulation before committing to RTL. An earlier draft of
this item said item 39 "should be judged against that ceiling" — that was
wrong; 3-5 % is not a ceiling on item 39, it is a measurement of the thing item
39 proposes to fix.

What the 3-5 % DOES rule out is buying a win by making the DRAM behind the L2
faster or newer: that lever is spent.

**Why comparing per CYCLE is legitimate on the MIG path.** Raising the DDR3
half from 91.676 to 100 MHz changed its per-MHz throughput by +0.8 % and its
stall profile not at all (Kfl cycles 254,340,917 -> 254,341,927, 0.0004 %). The
MIG locks the memory clock to `ui_clk` at 4:1, so the whole DRAM subsystem
scales with the core and cycle-denominated latency is invariant. This is also
why DDR2 at 75 MHz and DDR3 at 91.7 MHz looked identical per cycle — that was
not a coincidence, it is structural.

**Two methodology bugs found here, both silent.** (1) The preset compared two
memories with two *different cores*: the DDR3 half had `useDspMul = true,
bytecodes = "*" -> "hw"`, the SDR half took the defaults (`imul` a ~35-cycle
microcode shift-add, `idiv`/`irem` ~1300-cycle Java calls). Stall-category
shares are memory-side ratios and barely move with bytecode implementation, so
the contaminated run reproduced the previously recorded DDR3 row exactly and
looked entirely self-consistent; only throughput was wrong. It showed up in the
data exactly once, as UdpIp's SDR bytecode-fill share moving 46.7 -> 49.8 %
when `idiv` stopped being a Java method and left the method-cache working set.
(2) `hasPerfCounters` was reachable only via the `perf` CLI switch, which `make
dual-generate` does not pass — so the documented build produced a bitstream
whose counters all read 0, an hour of synthesis before anyone could notice.
Both are now fixed in the preset itself.

**Clock verified by measurement, not inference.** Sweeping the host baud
against the FPGA's `0xAA` boot stream (the Pico CDC applies host line coding to
its hardware UART) put both halves' clean window at 1.91-2.12 Mbaud, centre
~2.02 M. The stale-MIG hypothesis — `ui_clk` still 91.676 MHz, which would put
the window at 1.834 M — is excluded outright. Worth keeping as a habit: the
Wukong's `ui_clk` comes from the INSTALLED MIG IP, and
`vivado/ip/mig_7series_0/mig_7series_0/mig.prj` disagreed with the preset for
some time (2727 vs 2500 ps). Building RTL against a stale IP declares one clock
to a design running another, and an 8 % baud error reads as a dead board.

**Known limitation.** `hasRuntimeReset` is `!isMultiSystem`, so the dual preset
is the one config that CANNOT be reset over UART — repeat runs need a
reprogram. That is backwards: this is the preset most likely to be run many
times in a row.

Raw captures: `docs/measurements/perfcnt/`, reduced by
`fpga/scripts/perfcnt_report.py`. Timing on the dual build is VIOLATED at
**-0.362 ns**, all 16 endpoints the SDR `sdram_DQ` tristate enable — one
register fanning out to 16 IOBs, which `IOB TRUE` cannot pack. Slow/100C
corner; the SDR half verified a 65 KB download by XOR checksum and produced
repeatable results on every run.
