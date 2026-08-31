# Item 31 — The BMB arbiter caps TIMING CLOSURE on both FPGA families (not throughput — see 2026-08-18 note)

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 31](../current-status.md#item-31).

---

**The BMB arbiter caps TIMING CLOSURE on both FPGA families (not throughput — see 2026-08-18 note).**
Measured 2026-08-09 while validating item 1, and worth stating plainly
because it is easy to blame whatever feature was added last:

| build | cores | result |
|---|---|---|
| EP4CGX150 @80 MHz | 2 | MET +0.133 ns |
| EP4CGX150 @80 MHz | 4 | **VIOLATED -2.399 ns** |
| EP4CGX150 @65 MHz | 4 | VIOLATED -0.070 ns |
| EP4CGX150 @60 MHz | 4 | MET +0.302 ns |
| Wukong `wukongSmpMinimal` @100 MHz | 4 | MET +0.192 ns |
| Wukong `wukongSmpMinimal` @100 MHz | 8 | **VIOLATED -0.227 ns** |
| Wukong `wukongDdr3Smp` @100 MHz | 4 | MET +0.081 ns, 55.4 % LUT |
| Wukong `wukongDdr3Smp` @100 MHz | 6 | VIOLATED -0.156 ns, 69.6 % LUT |
| Wukong `wukongDdr3Smp` @100 MHz | 8 | VIOLATED -0.805 ns, 86.9 % LUT |
| Wukong `wukongDdr3Smp` @91.68 MHz | 6 | **MET +0.018 ns**, 68.4 % LUT — validated |
| Wukong `wukongDdr3Smp` @91.68 MHz | 8 | **MET +0.074 ns**, 84.4 % LUT — validated |

Both 91.68 MHz rows are the `Ddr3_366` MIG profile plus the CDC constraint
below; **8 cores runs on hardware**, `SMPGC OK` with `cores 8, publishers 7`,
minors 10 / verified 192 / errors 0, over **16 runs — 4 cold plus a 12-run
back-to-back soak, 0 failures**.

**2026-08-18 — scope correction.** The headline read "what stops SMP scaling",
which is ambiguous and was taken too broadly, including by me. Every row in the
table above is **timing closure**, MET or VIOLATED — none of it measures
throughput. The throughput ceiling was separately shown to be `LruCacheCore`'s
serial miss FSM: fixing that moved eight cores from 1.81x to 4.28x (DDR2) and
1.75x to 4.38x (DDR3) **with the arbiter unchanged**.

A later reading of the post-MSHR numbers — three memory architectures landing
within 4 % of each other — was briefly taken as evidence that the arbiter is now
the shared ceiling. **That is retracted.** Dividing throughput by the work each
memory performs shows three different limits: SDR at its controller's sustained
command rate, the two DRAM paths at the L2's serial hit path (item 39). **There
is no measurement implicating the arbiter in throughput.** It caps frequency
(item 5); that is the claim to work from.


The soak is the one that counts at +0.074 ns. Repeat runs are in this project
to catch INTERMITTENCY, and they have earned it (the Wukong 4-core case was
5/6, and that one failure was real information). But cold repeats re-measure
the same conditions; what threatens a design with 74 ps of margin is
temperature and voltage drift, so the runs were chained with no cooling gap
to let the die heat while under load. Prefer a soak to more cold repeats
whenever the margin is the worry rather than the logic. Utilisation at 8 cores: 53,524 / 63,400
LUT (84.4 %), 41,551 registers (32.8 %), 42.5 BRAM (31.5 %), 0 DSP. LUTs
bind; BRAM and registers have plenty of room.

Note the clock is not the whole story — at 100 MHz the 8-core TNS was -203
over many endpoints, and at 91.68 with the CDC constrained it is 0. Some of
that -0.805 was bogus paths, not congestion.

The `wukongDdr3Smp` rows (2026-08-15) are the generational-capable config —
card table, ICU, both caches — as opposed to `wukongSmpMinimal`, which has
no card table and so cannot run the generational test at all.

**ON THE WUKONG THE SYSTEM CLOCK IS NOT A FREE PLL OUTPUT.** For DDR3,
`Board.scala`'s `SDRAM_DDR3` case returns NO `systemClk` — only `migSysClk`,
`migRefClk` and `ethClk` — and `JopTop.scala:324` clocks the whole cluster
from `ddr3Mig.io.ui_clk`. `mig.prj` sets `TimePeriod 2500` ps (400 MHz
memory) and `PHYRatio 4:1`, so **ui_clk = 400/4 = 100 MHz**. Lowering the
core clock means regenerating the MIG with a longer `TimePeriod`; it is
quantised, not continuous:

| TimePeriod | memory clock | ui_clk (4:1) | covers |
|---|---|---|---|
| 2500 ps | 400 MHz | 100 MHz | 4 cores only (today) |
| 2727 ps | 366.7 MHz | 91.7 MHz | **6 and 8 cores both close** |
| 3000 ps | 333.3 MHz | 83.3 MHz | headroom |
| 3300 ps | 303 MHz | 75.8 MHz | near the DDR3 DLL floor |

Required Fmax is 98.5 MHz at 6 cores and 92.6 at 8, so **one MIG
regeneration at 2727 ps unlocks both** — at the price of 8 % of DDR3
bandwidth. Capacity is not the constraint at either count (69.6 % / 86.9 %),
though 8 cores routes with heavy congestion (2604 nodes with overlaps mid-
route, TNS -203) where 6 does not (TNS -0.628 from a single path).

**AN UNCONSTRAINED CDC WAS HIDING BEHIND THE 2:1 CLOCK RATIO** (fixed
2026-08-16, `wukong_ddr3.xdc`). Dropping ui_clk to 91.65 MHz made the 6-core
build come back **WORSE**, WNS -2.037 against -0.156 at 100 MHz, which looks
impossible for a slower clock. The failing path was not the arbiter:

    Source:      cores_0/uart_1/.../_zz_io_txd_reg   clk_pll_i period=10.911
    Destination: io_uart_txd_buffercc/buffers_0_reg  sys_clk   period=20.000
    Requirement: 0.325 ns

a crossing between ui_clk and the 50 MHz board oscillator. While ui_clk was
exactly 2x sys_clk the edges aligned and the analyser allowed a full period;
at a 1.833 ratio the common period collapses to 0.325 ns. `buffercc` is
SpinalHDL's two-FF synchroniser, so this is a genuine asynchronous crossing
that was never declared -- the Ethernet crossings next to it already are.
Adding `set_clock_groups -asynchronous` for sys_clk/clk_pll_i took it from
-2.037 to **+0.018**.

Worth generalising: a timing result that gets WORSE when you slow the clock
is a synchronous-CDC artefact, not a logic problem. Read the failing path
before touching anything else.

On the EP4CGX150 the worst path is
`cores_1|memCtrl|zeroCur -> [BMB arbiter] -> cores_3|memCtrl|bcFillAddr`,
with **zero CardTable nodes and 16 arbiter nodes** on it — so this is not
the shared card table from item 1, and the 2-core build with identical RTL
closes. On the Wukong at 8 cores the negative slack is spread over many
endpoints (TNS -20.3) rather than one path, which is congestion, not a
single fixable chain.

**Capacity is not the limit on the Wukong** — 8 generational-capable cores
fit in 86.9 % of the XC7A100T's LUTs. It IS the limit on the EP4CGX150 above
12 cores: 16 needs 182,501 of 149,760 LE (122 %). Below that the ceiling is
arbiter timing, and on the Wukong it is arbiter timing plus routing
congestion at 8 cores.

Consequence today: 4-core SMP GC validation runs at 60 MHz on the
EP4CGX150. That is enough to prove correctness but not to measure SMP
scaling honestly — any throughput number taken there is at a handicapped
clock. Fixing this is what unlocks the item 5 question (whether a cycle of
arbiter latency is worth 4+ cores), and it needs a pipelined or
tree-structured arbiter rather than the flat round-robin.

### The measurement gap
