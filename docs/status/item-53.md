# Item 53 — REGRESSION: the 8 KB method cache default broke 4-core Wukong SMP fit

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 53](../current-status.md#item-53).

---

**Found 2026-08-22 while measuring the L2 under SMP, not by anyone building
SMP** — which is the point. Memory records 4/6/8-core Wukong DDR3 SMP validated
on **2026-08-16**. The 8 KB/64-block method cache became the default on
**2026-08-20** (item 51). Nothing rebuilt an SMP bitstream in between, so the
regression sat undetected for two days.

`wukongSmp(4)` on the XC7A100T (63,400 LUTs), synthesis totals:

| L2 | method cache | LUTs | fits? |
|---|---|---|---|
| 512 sets (default) | 8 KB (default) | 73,252 (115.5 %) | no |
| 64 sets | 8 KB | 63,697 (100.5 %) | **no, by 297** |
| 64 sets | 2 KB (old) | **60,297 (95.1 %)** | yes — built, WNS +0.027 ns |

**The method cache costs 850 LUTs per core** (63,697 -> 60,297 over four cores),
matching the 869/core measured independently on the Alchitry Au. Item 51 recorded
the method cache change as costing "9-31 % of block RAM" and closing timing on
four boards — all true, and all single-core. Nobody multiplied the LUT cost by
the core count.

Note the two levers are independent and BOTH are needed at four cores: dropping
only the L2 still leaves it 297 LUTs over. And the L2 cannot simply be dropped,
because item 50 measures it as worth up to 33 % on data-heavy multicore work.

**LARGELY OVERTAKEN 2026-08-22 by the BRAM change in item 50.** Moving the L2's
valid and PLRU arrays out of fabric freed ~10,500 LUTs, so the arithmetic here
has moved:

| 4-core `wukongSmp` | L2 | method cache | LUTs | outcome |
|---|---|---|---|---|
| before | 512 sets | 8 KB | 73,252 | 15 % over |
| after | 512 sets | 8 KB | 62,583 (98.7 %) | fails slice packing |
| after | 256 sets | 8 KB | 62,592 | same -- L2 size is now free |
| **after** | **512 sets** | **2 KB** | **57,297 (90.4 %)** | **BUILDS, WNS +0.112 ns** |

The method cache is now the ONLY lever -- 850 LUTs/core, unchanged -- but it
buys a different thing than it used to. Before, giving it up bought a 4 KB L2
that still did not fit; now it buys the FULL 32 KB L2, worth up to 2.06x on
data-heavy 4-core work (item 50). And that build uses fewer LUTs and has four
times the slack of the old 4-core-with-4 KB build.

Note the 8 KB method cache still does not fit at 4 cores at any L2 size, since
L2 capacity no longer costs logic. So the decision below is unchanged in shape
and much better in payoff.

**RESOLVED 2026-08-23 — take `15/6`, and the trade was never cores vs cache.**
The 2026-08-22 answer below (`14/4`) is **SUPERSEDED**. It was reached by
treating the method cache as competing with the CORE COUNT, which framed the
question as "how little cache can we accept". The competitor is actually the
COMPUTE UNITS, and they are far more expensive.

CUs are per core, so the single-core sweep in
[`../analysis/wukong-utilization-sweep.md`](../analysis/wukong-utilization-sweep.md)
multiplies. At four cores: DCU ~19,800 LUTs, FCU ~7,150, LCU ~5,800 — against
~3,400 to take the method cache from 16 to 64 blocks. **Dropping one CU buys the
best geometry roughly six times over.** `wukongSmp` inherits `"*" -> "hw"` from
`wukongFull`, so all four were in there.

4-core `wukongSmp`, 512-set L2, all hardware-measured:

| config | blocks | block size | LUTs | BRAM | WNS | Kfl miss | DoAll |
|---|---|---|---|---|---|---|---|
| `14/4`, all CUs — *superseded* | 16 | 256 B | 57,329 (90.4 %) | — | +0.030 ns | 16.6 % | never run |
| `12/5`, all CUs (`Explore`) | 32 | 32 B | 59,228 (93.4 %) | — | **-0.043 ns** | 0.6 % | — |
| `13/6`, `double:java` | 64 | 128 B | 43,487 (68.6 %) | 35.5 (26.3 %) | +0.069 ns | 0.1 % | **66/66** |
| **`15/6`, `double:java`** | **64** | **512 B** | **43,399 (68.5 %)** | 59.5 (44.1 %) | **+0.088 ns** | **0.1 %** | **66/66** |

`15/6` beats the superseded answer on every axis that costs anything: four times
the blocks, four times the depth, 22 points more LUT margin, better timing, and
it is the only one validated on hardware. Depth is free — `13/6` -> `15/6` cost
**-88 LUTs** — so the block size sits at the 512 B saturation point. See
[the tuning guide](../architecture/tuning-guide.md) for the policy this
produced: hold the block size at 512 B, pick the count from the LUT budget.

**What it costs:** double arithmetic runs in Java. There is no double microcode
at all (`bc=double:mc` is refused — item 20), so this is the Java path, which is
the DEFAULT for every preset that does not say `"*" -> "hw"`. DoAll's
`DoubleArith`, `DoubleField`, `MathTest` and `BigMathTest` all pass. Untested:
whether dropping the **LCU** (~5,800) instead would also fit 64 blocks while
keeping doubles — cheaper in LUTs, and possibly the better trade if an
application uses doubles more than longs.

**STILL OPEN — the preset.** `wukongSmp(4)` at defaults is still 62,583 LUTs
(98.7 %) and fails slice packing, so the regression this item was filed about is
live: the preset does not build, and nothing warns until Vivado's placer. The
fix is validated but not applied, because the right THRESHOLD is unknown —
`15/6` is 59.5 BRAM at 4 cores and would be ~119 of 135 at 8 cores before the L2
gets anything, so the geometry has to step down with core count. That is
option 1 below, and this is the first case that actually needs it. Decide it
with the 8/12-core data, not from the 4-core point.

**The 2026-08-22 answer, superseded, kept for the reasoning:** the decision below was
framed as method cache *versus* fit. The geometry sweep splits it into two
independent axes and the trade largely dissolves: `blockBits` (block COUNT) costs
LUTs, `jpcWidth` (SIZE) costs only BRAM, and a 4-core Wukong is at 90 % LUT and
23 % BRAM. So depth is nearly free and count is what binds.

4-core `wukongSmp`, 512-set L2, measured:

| geometry | blocks | LUTs | WNS | Kfl miss |
|---|---|---|---|---|
| `11/4` — 2 KB, 16 x 32w | 16 | 57,297 (90.4 %) | +0.112 ns | 34.8 % |
| **`14/4` — 16 KB, 16 x 256w** | 16 | **57,329 (90.4 %)** | **+0.030 ns** | **16.6 %** |
| `14/4`, `place_design -directive Explore` | 16 | 57,218 (90.3 %) | **+0.155 ns** | 16.6 % |
| `12/5` — 4 KB, 32 x 32w | 32 | — (93.5 %) | **-0.147 ns** | 0.6 % |
| `12/5`, `-directive Explore` | 32 | 59,228 (93.42 %) | **-0.043 ns** | 0.6 % |

**32 blocks does not close at four cores, and the placer cannot rescue it.**
`Explore` is worth ~0.10-0.13 ns on this netlist (it moved `14/4` by 0.125 and
`12/5` by 0.104), and `12/5` still lands 43 ps short at 93.4 % LUT. That is the
expensive half of the trade and it is unaffordable here.

`14/4` is therefore the four-core recommendation: **8x the method cache of the
old default for 32 LUTs**, cutting Kfl fill traffic 53.7 % (966,208 -> 447,374
words) and doubling the timing slack of `11/4`. It does not touch the L2, so the
full 32 KB stays — worth up to 2.06x on data-heavy 4-core work (item 50).

What is given up is real and should be stated: 32 blocks would take Kfl to 0.6 %
miss and UdpIp to 0.1 %, far more than depth buys. Fragmentation remains the
dominant method-cache effect at four cores and remains unaddressed there —
single-core builds have the room and keep 64 blocks.

Two caveats on `Explore`. It is not the repo default (`ExtraTimingOpt` is), so
`14/4`'s shipping margin is +0.030 ns, not +0.155; and a directive that happens
to suit one netlist is not a property of the design. Treat the `Explore` row as
evidence that `12/5` is genuinely out of reach, not as slack to spend.

**Still open:** whether `14/4` should become the multicore preset default, and
whether the per-core-count mechanism below is worth building for one geometry.

Options as originally framed, for the record:

1. **Per-core-count method cache**, the same shape as `l2SetCount` — smaller
   `jpcWidth`/`blockBits` above some core count. Cheap, but item 51 measured the
   8 KB cache as worth +35 % Kfl, so it trades a large single-core win for fit.
2. **Make `LruCacheCore` cheaper** so the L2 stops dominating — the
   `validFlat`/`lruArray` fabric-register arrays in item 50. This is the only
   option that makes a 4-core build with a full L2 possible at all; today it
   needs ~69,850 LUTs and cannot be built. **DONE** — both arrays moved to BRAM
   (item 50), ~10,500 LUTs freed, which is what made the table above possible.
3. **Accept 2 cores as the Wukong DDR3 SMP ceiling** at current defaults, and
   say so, rather than leaving presets that do not build.

**Nothing warns you.** `wukongSmp(n)` elaborates and synthesises happily; the
failure is a Vivado DRC at place time, minutes in. An elaboration-time LUT
estimate is not available, but the `--boards`-style honesty of just documenting
the ceiling costs nothing.
