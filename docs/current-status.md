# Where we are — 2026-08-18

> **2026-08-17/18 — the DRAM multicore scaling ceiling is gone.** `LruCacheCore`
> was a blocking miss FSM and both DRAM paths stalled at ~1.8x on eight cores
> against SDR's 4.45x. It now has an MSHR file, and both memory adapters had to
> stop serialising with it. Measured on hardware, both boards:
>
> | board | 8-core before | after | ratio |
> |---|---|---|---|
> | A-E115FB DDR2 | 682 kacc/s | **1613** | 1.81x -> **4.28x** |
> | Wukong DDR3 | 754 kacc/s | **1882** | 1.75x -> **4.38x** |
>
> **Off by default** (`JopMemoryConfig.l2MshrCount = 1`) — no shipped
> configuration has changed; the MSHR presets are opt-in
> (`ae115fbDdr2SmpMshr`, `wukongDdr3SmpMshr`). Neither 8-core build closes
> timing, so those are measurement vehicles, not shippable bitstreams.
>
> Full record, including two response-ordering bugs it exposed in the memory
> adapters and the analysis of what limits things now:
> [architecture/nonblocking-cache-mshr-plan.md](architecture/nonblocking-cache-mshr-plan.md).
> Benchmark tables: [../java/apps/JbeBench/README.md](../java/apps/JbeBench/README.md).
>
> **Memory work is worth doing on real code, and the method cache is the
> target.** Real applications lose **34-55 % of throughput to memory latency**
> (Kfl 53.8 %, UdpIp 54.8 %, Lift 34.0 % — item 38), and **62 % of DoApp's
> memory transactions are bytecode fill** while all array traffic is 4.2 %
> (item 37). The BMB arbiter caps FREQUENCY, not throughput, and is a worse
> trade at these numbers — see the retraction in items 5 and 31.
>
> **And a general-purpose L2 in front of DRAM is NOT the lever** — measured on
> one die, two memory systems, same clock, run simultaneously: a 32 KB L2 is
> worth **3-5 %**, and it cannot touch the method cache or handle indirection,
> which between them own 57-63 % of every stall profile ([item 50](#item-50)).
>
> Two optimisations that looked worth multiples on `JbeScale` are worth 2.8 %
> and nothing on real code. **Do not act on any `JbeScale`-derived number**
> without checking it against DoApp first; that benchmark is a pessimal data
> probe and real code here is instruction-fetch bound.

Resumption notes covering the GC work, the A-E115FB DDR2 bring-up, SMP, and the
board/probe setup. Written to be read cold.

Detail lives in:
- [gc/stage3-followups.md](gc/stage3-followups.md) — GC Stage 3 history: what each
  change bought, the corrections along the way, and the small leftovers.
  **This file is authoritative for pause numbers and tuning constants**, not that
  one — it kept duplicate tables through Stage 3 and they drifted within two days
- [boards/ae115fb-ddr2-bringup.md](boards/ae115fb-ddr2-bringup.md) — DDR2, including everything that went wrong
- [bugs-and-issues.md](bugs-and-issues.md) — the defects fixed along the way
- [gc/copy-phase-redesign.md](gc/copy-phase-redesign.md) — the remaining 79-82% of the minor pause
- [gc/major-gc-evacuation.md](gc/major-gc-evacuation.md) — design note: drop the address sort from the major GC
- [architecture/tuning-guide.md](architecture/tuning-guide.md) — **the configuration levers**: what each of `blockBits`, `jpcWidth`, `l2SetCount`, `l2MshrCount` and `cpuCnt` buys, what each costs, which resource binds, and a list of the plausible-sounding conclusions that measurement disproved
- [architecture/software-cost-model.md](architecture/software-cost-model.md) — what operations cost on JOP, measured (method call ~142 cycles, static read ~22, microcode imul ~775)

---

## 1. Outstanding now — in priority order

One line each. **This section is the ground truth for what is open** —
outstanding items from other documents belong here, not only there.
Full reasoning and journals are in [section 3](#3-item-detail-and-journals);
closed items are in [section 2](#2-all-items--summary).

The ordering is a proposal, not a decree — it puts measurement that unblocks
a decision above the work it would unblock, and CI trust above everything,
on the grounds that a flaky baseline makes every other number arguable.

CI flakiness is **RESOLVED as of 2026-08-18**, and no longer heads this list.
**#30 and #29 were one bug** — Verilator randomising the ~405 registers in
this design that have no reset, seeded per run — now zeroed by
`--x-initial 0` (`jop.utils.JopSimDefaults`). **#32 is not the same story**:
its pin is retired because the failure no longer reproduces at HEAD even with
randomisation on, but its cause was never established, so it stays open,
rescoped. #46 turned out to have been fixed on 2026-08-15 before anyone looked
at it; #47 (a push cancelling the nightly) is fixed here. #45 was the residue —
the missing resets themselves — but a five-seed sweep found no offender among
the registers, so it is now a single named defect rather than a 405-register
audit, and has moved down accordingly.

1. **[#54](#item-54)** — Statics are Kfl's largest stall category (41 %) and no cache touches them. Count the accesses before designing anything
2. **[#55](#item-55)** — The core stalls on writes whose result it never uses — `idle/direct`, 39 % of Kfl stall. Needs read-after-write forwarding and an SMP story
3. **[#37](#item-37)** — The method cache dominates real memory traffic — 62 % of DoApp's BMB transactions, and [50](#item-50) confirms it in TIME on real memory: bytecode fill is 47-63 % of stall on Kfl and UdpIp
4. **[#64](#item-64)** — `GcStressTest` loses **0.42 bytes/round**, at the same rate on two boards and two memory systems. Deterministic, so it is a defect, not drift — and three candidate causes need ONE measurement to separate
5. **[#4](#item-4)** — Copy phase — 79-82% of the minor pause and the dominant remaining term
6. **[#39](#item-39)** — The L2 hit path is serial — 3 cycles per hit, 58-61 % of the DRAM access interval. **[50](#item-50) raises the priority of this**: bytecode fill is a sequential burst and improved only 3 % with a 32 KB L2 in front of DDR3, which is what a 3-cycle hit would predict
7. **[#44](#item-44)** — The compute floor C is per-configuration; re-measure it before trusting any per-operation cost
8. **[#45](#item-45)** — ONE unidentified register is read before it is written; the other ~401 look benign
9. **[#32](#item-32)** — UART corruption on seed 871203250 — no longer reachable, pin removed; cause never found
10. **[#5](#item-5)** — The BMB arbiter sets the clock ceiling — FREQUENCY, not core count
11. **[#31](#item-31)** — The BMB arbiter caps TIMING CLOSURE on both FPGA families (not throughput — see 2026-08-18 note)
12. **[#41](#item-41)** — Neither 8-core DRAM build closes timing, MSHRs or not
13. **[#3](#item-3)** — Sixteen presets still run classic GC. Safe but slow
14. **[#53](#item-53)** — 4-core Wukong takes `15/6` + `double:java` (64 blocks, DoAll 66/66, 68.5 % LUT). **The preset still does not build at defaults** — threshold needs the 8/12-core data
15. **[#52](#item-52)** — The Java tools hold hand-copied duplicates of the hardware config. Generate them from the preset instead
16. **[#17](#item-17)** — `needs*Compute` predicates understate compute-unit reachability
17. **[#18](#item-18)** — Software/microcode fallback coverage is uneven — 18 of 32 configurables
18. **[#19](#item-19)** — Write the missing `_sw` microcode handlers
19. **[#20](#item-20)** — Decide whether the double group gets microcode at all
20. **[#27](#item-27)** — The `aastore` type check's cost was never measured
21. **[#12](#item-12)** — `LongComputeUnitConfig` has no enable flag for its base 64-bit ALU
22. **[#7](#item-7)** — Root-scan floor: 2.2 / 4.7 / 8.5 ms across SDR / DDR3 / DDR2
23. **[#8](#item-8)** — XC7A100T timing margin is +0.001 ns — one bad run in seven
24. **[#14](#item-14)** — Stack cache SDRAM integration — 3-bank rotation verified in BRAM, needs per-core regions
25. **[#40](#item-40)** — A leaner MSHR entry — each holds a full cache line of write data a read miss never uses
26. **[#42](#item-42)** — Secondary-hit merging is not implemented — a request to a line being filled replays
27. **[#21](#item-21)** — Colorlight i5 is EBR-bound in BRAM-only builds, not logic-bound
28. **[#11](#item-11)** — Application benchmark exists (`java/apps/JbeBench`) — remaining questions it should answer
29. **[#9](#item-9)** — Pico USB-Blaster needs a level shifter (74LVC8T245 or 2x 74LVC2T45)
30. **[#10](#item-10)** — pico-usb-blaster protocol bug — low-level shift works, Quartus handshake does not
31. **[#13](#item-13)** — `java/apps/Small` `make clean` deletes `HelloWorld.jop`
32. **[#56](#item-56)** — WBNI: derive the hardware config from the application. **JOPizer static profile DONE**; the remaining bulk is a measurement FRAMEWORK (preferably Java) across the hardware set
33. **[#57](#item-57)** — The XDC/QSF generators exist and nothing uses them; constraints are hand-written and have drifted from the config
34. **[#58](#item-58)** — `source` inside an XDC is silently ignored — SDRAM IOB packing and Ethernet GMII constraints have never been applied
35. ~~**[#59](#item-59)**~~ — WITHDRAWN: the i5 passes at 49.40 MHz; a post-placement estimate was read instead of the final post-routing figure
36. **[#60](#item-60)** — Everything generated belongs under `build/<config>/`. Three FPGA flows and the whole Java/JOP tree converted and verified; 48 flows and `asm/` to go
37. ~~**[#61](#item-61)**~~ — FIXED 2026-08-24: no app in `apps/Small` could be built from clean; every `.jop` there was an unreproducible stale artifact
38. **[#63](#item-63)** — One unexplained Wukong SDR startup crash in six runs; not reproduced, cause unknown
39. **[#62](#item-62)** — `JopFloatCuBramSim` reads a `floatcu` microcode variant that has never been generated, so it has never run

## 2. All items — summary


Scannable index; the numbered sections below carry the reasoning. Each entry
says what is **verified** versus **asserted**, because several things in this
project have looked fine while being wrong.

**Item numbers are stable IDs, not reading order**, and they are written as
`- **N.**` bullets rather than a Markdown ordered list **on purpose**. An
ordered list renumbers from its first marker when rendered, so a source that
read `12. 13. 14. 15. 16. 23.` displayed as `12..17` — item 23 was invisible and
there were two 17s on the page, one of them fictional. Every `item N` reference
in these docs was wrong for anyone reading rendered Markdown. Keep the bullet
form.

IDs are assigned on creation and then grouped by topic, so the page runs 1-16,
24, 25, ... 23, 26, 27, ... 17-22 ... 21. They are referenced from `bugs-and-issues.md`,
the GC design notes and a good many commit messages, so renumbering would
silently invalidate all of that. Use this to find one:

| # | section | # | section | # | section |
|---|---|---|---|---|---|
| 1-3, 34 | Blocking / correctness | 11 | The measurement gap | 21 | Boards |
| 4-7, 24, 25, 37-40, 42, 50, 51, 53-55 | Performance | 12-16, 23, 26, 27, 52, 56, 57 | Smaller | 17-20, 22, 28 | Compute units |
| 8-10, 31, 32, 33, 35, 58 | Hardware / infrastructure | 29, 30, 36 | Smaller (CI flakiness) | | |

**Closed 2026-08-15/16**, the SMP push: **1** (generational GC on SMP — root
cause was `AlteraSdramAdapter`, not the collector), **2** (`JopIhluGcBramSim`
could not fail), **34** (its SDRAM row), **35** (`AlteraSdramAdapter` had no
simulation coverage), **36** (formal-property CI timeout). Still open and
sharpened by that work: **5**/**31** (the arbiter now sets the CLOCK at each core
count rather than capping the count), **3** (presets lacking `hasCardTable`),
**11** (no application benchmark — still gates the arbiter decision).

### Blocking / correctness


### Open

- **[32](#item-32)** — UART corruption on seed 871203250 — no longer reachable at HEAD, CI pin REMOVED; cause never found
- **[3](#item-3)** — Sixteen presets still run classic GC. Safe but slow
- **[54](#item-54)** — Statics are Kfl's largest stall category (41 %) and no cache touches them
- **[55](#item-55)** — The core stalls on writes whose result it never uses — `idle/direct`, 39 % of Kfl stall
- **[56](#item-56)** — WBNI: derive the hardware config from the application. JOPizer static profile done; the measurement framework is the bulk
- **[57](#item-57)** — The XDC/QSF generators exist and nothing uses them — constraints hand-written, drifted from the config
- **[58](#item-58)** — `source` inside an XDC is silently ignored by Vivado — four shared constraint files never applied
- **[60](#item-60)** — Everything generated belongs under `build/<config>/` — three FPGA flows and the Java/JOP tree done and verified, 48 flows and `asm/` to go
- ~~**[61](#item-61)**~~ — FIXED 2026-08-24 — no app in `apps/Small` built from clean; the runtime is now bulk-compiled and the app named
- **[62](#item-62)** — `JopFloatCuBramSim` reads a microcode variant that does not exist, so it has never run
- **[63](#item-63)** — One Wukong SDR startup crash in six runs, not reproduced — recorded so a second sighting is not treated as the first
- **[64](#item-64)** — `GcStressTest` free memory declines monotonically at 0.42 B/round, identically on the i5 and the EP4CGX150
- **[65](#item-65)** — Both SD exercisers fail on hardware — `ACMD41` times out. NOT the build-tree conversion: identical at the old clock
- ~~**[66](#item-66)**~~ — The EP4CGX150's Ethernet/VGA/SD was lost in migration `8641942` — **preset written back 2026-08-25, pin-identical to the historical project, 15,270 LE, all clocks MET.** Found a dead `"eth"` vs `"ethernet"` predicate that had silently dropped every `set_clock_groups`
- **[77](#item-77)** — the EP4CGX150 SDRAM Makefile is converted: 701 → 195 lines, and ~150 of those lines were flows DEAD since March
- **[76](#item-76)** — the 4-core BRAM SMP stall no longer reproduces, and timing was tested as the cause and REFUTED
- **[75](#item-75)** — `ep4cgx150HwMath` generates byte-identical RTL to `ep4cgx150Serial` — a preset that expresses nothing, with a test that passes trivially
- **[74](#item-74)** — item 69's scope was too narrow: `"float" -> "hw"` hits the `frem` trap too, not just `"*" -> "hw"` — and `frem` is absent from the bytecode table
- **[73](#item-73)** — `ep4cgx150DbVgaDma` misses by −1.011 ns on SDRAM command-FIFO arbitration between the core and the VGA DMA — OPEN
- **[72](#item-72)** — `JopTopVerilog` gave every FPGA build the SERIAL microcode regardless of the config's boot mode — FIXED
- **[71](#item-71)** — All three EP4CGX150 **BRAM** presets missed timing at 80 MHz; the BRAM read-data path will not close there — FIXED by reclocking, `hw_verify` now refuses violated bitstreams
- **[70](#item-70)** — UART baud is stated in THREE places that disagree — preset override, a 2 Mbaud default, and 12 Makefile constants. Pick one rate and derive the rest
- **[69](#item-69)** — `bytecodes = "*" -> "hw"` forces hardware for `frem`, which has NO hardware implementation anywhere. `DoAll` dies at `FloatTest` on every `*=hw` preset
- **[68](#item-68)** — EP4CGX150 Ethernet: link comes up at 1 Gbps but NO packets move. DHCP times out against a server that IS on that switch
- **[67](#item-67)** — `ep4cgx150DbFull` runs with `useStackCache = false`; the original had it true. Revisit once stack-cache SDRAM integration lands
- **[4](#item-4)** — Copy phase — 79-82% of the minor pause and the dominant remaining term
- **[5](#item-5)** — The BMB arbiter sets the clock ceiling — FREQUENCY, not core count
- **[7](#item-7)** — Root-scan floor: 2.2 / 4.7 / 8.5 ms across SDR / DDR3 / DDR2
- **[8](#item-8)** — XC7A100T timing margin is +0.001 ns — one bad run in seven
- **[9](#item-9)** — Pico USB-Blaster needs a level shifter (74LVC8T245 or 2x 74LVC2T45)
- **[10](#item-10)** — pico-usb-blaster protocol bug — low-level shift works, Quartus handshake does not
- **[31](#item-31)** — The BMB arbiter caps TIMING CLOSURE on both FPGA families (not throughput — see 2026-08-18 note)
- **[11](#item-11)** — Application benchmark exists (`java/apps/JbeBench`) — remaining questions it should answer
- **[29](#item-29)** — ~~`BytecodeFetchStage: JumpTable integration` is flaky in CI~~ — **FIXED** (X-state)
- **[30](#item-30)** — ~~`JopJvmTestsBramSim` — the CI baseline job — intermittently dies~~ — **FIXED** (X-state)
- **[45](#item-45)** — ONE unidentified register is read before it is written; the other ~401 look benign
- **[49](#item-49)** — ~~The UART divided the clock by an integer, so the baud was only right on lucky clocks~~ — **FIXED** (`UartBaudTick`)
- **[48](#item-48)** — ~~No runtime reset: the FPGA had to be reprogrammed before every download~~ — **DONE** (UART escape + button)
- **[46](#item-46)** — ~~`formal-verification` fails intermittently~~ — **ALREADY FIXED** 2026-08-15 (`6bce639b`, formal timeout 300→900 s)
- **[47](#item-47)** — ~~A push cancelled the nightly scheduled CI run~~ — **FIXED** (concurrency group)
- **[12](#item-12)** — `LongComputeUnitConfig` has no enable flag for its base 64-bit ALU
- **[13](#item-13)** — `java/apps/Small` `make clean` deletes `HelloWorld.jop`
- **[14](#item-14)** — Stack cache SDRAM integration — 3-bank rotation verified in BRAM, needs per-core regions
- **[27](#item-27)** — The `aastore` type check's cost was never measured
- **[17](#item-17)** — `needs*Compute` predicates understate compute-unit reachability
- **[18](#item-18)** — Software/microcode fallback coverage is uneven — 18 of 32 configurables
- **[19](#item-19)** — Write the missing `_sw` microcode handlers
- **[20](#item-20)** — Decide whether the double group gets microcode at all
- **[21](#item-21)** — Colorlight i5 is EBR-bound in BRAM-only builds, not logic-bound
- **[37](#item-37)** — The method cache dominates real memory traffic — 62 % of DoApp's BMB transactions
- ~~**[51](#item-51)**~~ — Method cache — **CLOSED 2026-08-23. DEFAULT 8 KB/64 blocks, +35 % Kfl / +27.7 % UdpIp on four boards** (DDR2, SDR, DDR3; Quartus/Vivado/nextpnr). Fragmentation, not capacity. Both design options it left open were measured away; the remainder became [54](#item-54) and [55](#item-55)
- ~~**[38](#item-38)**~~ — ANSWERED: stall share is 34-55 % — Measure DoApp's memory-stall fraction — decides between items 37, 39 and 5/31. Where that 34-55 % GOES was then measured on hardware in [50](#item-50)
- **[39](#item-39)** — The L2 hit path is serial — 3 cycles per hit, 58-61 % of the DRAM access interval
- **[40](#item-40)** — A leaner MSHR entry — each holds a full cache line of write data a read miss never uses
- **[41](#item-41)** — Neither 8-core DRAM build closes timing, MSHRs or not
- **[44](#item-44)** — The compute floor C is per-configuration; single-core latency decompositions need C re-measured
- **[42](#item-42)** — Secondary-hit merging is not implemented — a request to a line being filled replays

### Closed

- ~~**[50](#item-50)**~~ — Memory-stall profile on real memory, on hardware — DONE. Four boards, plus the dual-system run: the L2 as built is worth 3-5 %
- ~~**[43](#item-43)**~~ — Colorlight i5 SDRAM "regression" — FALSE ALARM, my own documented trap
- ~~**[1](#item-1)**~~ — Generational GC is unsound on SMP — RESOLVED (2026-08-15
- ~~**[2](#item-2)**~~ — `JopIhluGcBramSim` cannot fail — CLOSED 2026-08-16
- ~~**[34](#item-34)**~~ — 4-CORE STATUS after the fetch-stall fixes — the SDRAM row is
- ~~**[33](#item-33)**~~ — `AlteraLpm.createRam` discarded the debug stack-RAM address
- ~~**[35](#item-35)**~~ — `AlteraSdramAdapter` has NO simulation coverage — DONE
- ~~**[6](#item-6)**~~ — Major GC constant unexplained — LARGELY FIXED 2026-08-06, 2.6-3.2x
- ~~**[24](#item-24)**~~ — The evacuation trade is untested at larger object sizes
- ~~**[25](#item-25)**~~ — Two loose ends from the GC work — DONE 2026-08-06
- ~~**[36](#item-36)**~~ — The `stall freezes jpc, jinstr and the dispatch address` formal
- ~~**[15](#item-15)**~~ — `GcPauseTest` on the Wukong boards — never run — DONE
- ~~**[16](#item-16)**~~ — Colorlight i5 SDRAM ("stage 2" of that board's bring-up — unrelated to
- ~~**[23](#item-23)**~~ — `f_multianewarray` handles exactly 2 dimensions — FIXED
- ~~**[26](#item-26)**~~ — Reference arrays carry no element class — FIXED
- ~~**[28](#item-28)**~~ — `DoAll` dies at `CollectionTest` on the Wukong — FIXED
- ~~**[22](#item-22)**~~ — Five `_sw` handlers exist but do not work — RESOLVED. It was two
- **[52](#item-52)** — The Java tools duplicate the hardware config by hand — three stale copies found while documenting item 51
- **[53](#item-53)** — The 8 KB method cache default broke 4-core Wukong SMP fit — resolved to `15/6` + `double:java` (hardware-validated); the preset itself is still unfixed

## 3. Item detail and journals

Stable IDs, grouped as they were created. Every entry that had a journal
keeps it verbatim.

<a id="item-1"></a>

### Item 1 — ~~Generational GC is unsound on SMP — RESOLVED (2026-08-15~~

~~**Generational GC is unsound on SMP**~~ — **RESOLVED (2026-08-15,
`ef36d99`); the core-count guard is GONE as of 2026-08-16.** The long-standing
>2-core failure was **not a GC bug and not a card-table bug**. It was two
response-path defects in `AlteraSdramAdapter` that corrupted reads to zero
(Avalon `readdatavalid` is a pulse and was dropped whenever the consumer
stalled; locally-generated write responses, which hardcode `data := 0`, could
overtake outstanding reads, which the consumer matches BY ORDER). It surfaced
as an array-bounds exception on a valid index, which killed a publisher
thread and wedged the cluster. Full narrative and every retraction along the
way in the item-1 section below, entries (b1)-(b10).

**Verified on EP4CGX150 SDRAM:**

| cores | clock | SmpGcTest generational | DoAll |
|---|---|---|---|
| 1 | 60 MHz | — | 66/66 |
| 4 | 60 MHz | `SMPGC OK`, minors 10 / verified 192 / errors 0, 3/3 | 66/66 |
| 8 | 50 MHz | `SMPGC OK`, minors 10 / verified 192 / errors 0, 3/3 | 66/66 generational AND classic |

plus `JopJvmTestsBramSim` 132 ok. 8 cores fits in **77,145 LE (52 %)**, so
the device is not the limit.

**WHAT THE DoAll COLUMN DOES AND DOES NOT MEAN.** Nothing in `java/apps/JvmTests`
writes `IO_SIGNAL`, so on a multi-core bitstream cores 1..N-1 stay parked in
the microcode `cpux_loop` and **DoAll runs entirely on core 0**. It is a
regression test that the larger, slower build still executes the JVM
correctly with N cores instantiated and the arbiter widened — worth having,
and it caught nothing but would have caught plenty — but it exercises no
cross-core coherency, no lock contention, no arbiter contention and no SMP
GC. **`SmpGcTest` is the only test in the tree that actually runs all the
cores** (`cores 8, publishers 7`), which is why every core-count claim above
rests on it and why item 2's "JopIhluGcBramSim cannot fail" matters.

**AN 8-CORE BUILD NEEDS THE PLL AT 50 MHz.** `dram_pll.vhd` ships at 6/5
(60 MHz), which suits up to 4 cores; 8 misses it by **-1.199 ns** and the
failing path is precisely what item 31 describes — `cores_N|memCtrl|addrReg`
through the arbiter to `cores_0|memCtrl|state`, 18.185 ns of data delay,
Fmax ~56 MHz. At 50 MHz it closes with **+0.463 ns setup / +0.234 hold**.
**The PLL now follows the preset automatically** (2026-08-15): `JopTopVerilog`
generates `fpga/qmtech-ep4cgx150-sdram/generated/dram_pll.vhd` from
`clkFreq`, so `ep4cgx150Smp 8 50` emits a 1/1 PLL with no hand edit, and it
reports the baud the board will actually manage. See `DramPllGen`.

**The per-core probe banks are limited to 4 cores** (`JopCluster.hasProbeBanks`).
The root port's target field is 4 bits, cores take 0..cpuCnt-1, and the banks
live at `8 + core` and `12 + core` — which stops fitting once the cores need
0..7, where `12 + core` runs off the end and wraps onto another core's slot.
Above 4 cores the banks are omitted rather than shipped silently wrong, and
SmpGcTest checks the same bound before reading them. Restoring them for a
wider cluster means widening `rootSel`, which is a `Sys` change.

**DDR3 CONFIRMED at 4 cores (2026-08-15).** Wukong XC7A100T, new preset
`wukongDdr3Smp(4)`, 100 MHz, post-route WNS **+0.081 ns** / WHS +0.065:
`GC: generational, 512-word cards`, `SMPGC OK`, `minors 10 / verified 192 /
errors 0`, **5 of 6 runs**. This matters as an independent check as well as a
coverage tick — DDR3 goes MIG -> `CacheToMigAdapter` and never touches
`AlteraSdramAdapter`, so it confirms the fix's claimed scope.

The one failure was the FIRST run after programming: the card size printed as
`!!` instead of `512` and the run then hung. Every run after a fresh reprogram
was clean. That has the shape of the item 32 UART corruption plus the known
"reprogram immediately before each download" rule rather than anything in the
collector, but it is one data point and is recorded rather than dismissed.

Note `wukongDdr3Smp` derives from `wukongDdr3`, NOT `wukongSmpMinimal`. The
latter looks like the obvious base and is wrong twice: it replaces coreConfig
with a bare `JopCoreConfig()`, so `hasCardTable` goes false, `GC.init` falls
back to classic, and a generational test on it measures nothing.

**Still asserted, not verified:** the XC7A100T DB V5 board (only Wukong was
run); DDR3 above 4 cores. The cluster-level card table the original entry
proposed turned out **not** to be needed: the per-core tables are fine, and
`SMPGC OK` means the cross-generation references genuinely survived, so the
workload did exercise it.

**EP4CGX150 CORE-COUNT CEILING, all measured 2026-08-15, not extrapolated.**

> **THESE NUMBERS ARE AT THE OLD 2 KB / 16-BLOCK METHOD CACHE.** The default
> became 8 KB / 64 blocks on 2026-08-20 (`0293415`), four days after this table
> was measured, and **12 cores does not route at the new default** — see the
> note below the table before rebuilding anything here.


| cores | LE | of device | timing | status |
|---|---|---|---|---|
| 1 | 8,784 | 6 % | 60-80 MHz | validated |
| 4 | 42,769 | 29 % | 60 MHz, +0.944 | validated, generational |
| 8 | 77,145 | 52 % | 50 MHz, +0.463 | validated, generational |
| 12 | 118,085 | 79 % | 36 MHz, +3.084 | **validated, generational** |
| 16 | **182,501** | **122 %** | — | **DOES NOT FIT** |

**12 CORES DOES NOT ROUTE AT THE CURRENT DEFAULT (2026-08-23).** Rebuilt at
8 KB / 64 blocks, 36 MHz, and it **fits but cannot be routed**:

```
Info (170196): Router estimated peak interconnect usage is 94% of the available
               device resources in the region X47_Y46 to X58_Y56
Info (170131): Fitter routing phase terminated due to predicted failure from
               regional routing congestion
Warning (16618): Fitter routing phase terminated due to routing congestion.
```

Placement SUCCEEDS in 12m34s — the area is fine, roughly 86 % LE against the
79 % in the table — and then routing dies on **regional** interconnect
congestion, retries, and dies again. Killed after 4h20m elapsed / 18h27m CPU
across three attempts. **Do not wait for this build: it is not slow, it is
failing, and the failure is visible in the log within the first routing pass.**

This is [item 53](#item-53) on a second board, with a different failure mode:
the Wukong hit slice packing at 98.7 % LUT, this hits routing congestion at
86 % LE. Both are the same cause — the method cache is per core, so ~850
LUTs/core multiplies — and both went undetected because no SMP build was made
after the default changed. The A-E115FB and CYC5000 SMP builds have the same
exposure and have not been rebuilt either.

**`14/5` ROUTES AND CLOSES, same day.** 12 cores at 16 KB / 32 blocks / 512 B:

| 12-core, 36 MHz | `11/4` (2026-08-15) | `13/6` | **`14/5`** |
|---|---|---|---|
| LE | 118,085 (79 %) | fits, ~86 % | **126,601 (85 %)** |
| routing | ok | **fails, 94 % congestion** | **ok, no termination** |
| setup slack, Slow 100C | +3.084 ns | — | **+2.792 ns**, TNS 0.000 |
| memory bits | — | — | 2,520,704 (38 %) |

Zero failing or unconstrained paths. **Halving the block count is what fixed
it** — peak regional interconnect usage fell 94 % -> 84 % — which is the
expected lever, since `blockBits` is the axis that costs comparators and the
failure was congestion rather than area.

So the answer at the top of the core-count range: **64 blocks does not route at
12 cores; 32 blocks does**, for ~8,500 LE and 0.29 ns against the old 16-block
default, and it buys the count that takes Kfl from 34.8 % to 0.6 % miss.

This is the data [item 53](#item-53) was missing for its per-core-count
threshold, and it confirms `14/5` as the floor: at 12 cores it is not a
compromise, it is the largest geometry that routes, and it keeps the 512 B
block size the sweep identified.

**HARDWARE-VALIDATED the same evening**: `SmpGcTest` on the 12-core `14/5`
bitstream returns `cores 12, publishers 11`, `minors 10 verified 192 errors 0`,
**`SMPGC OK`**, `JVM exit!`. So the geometry that routes also runs, on all
twelve cores, with the generational collector.

**CLOCK — 36 MHz, as the preset says. A wrong-file scare, recorded because the
decoy is still there.** The STA clock table is the authority:

```
clk_in       Base       20.000 ns  50.0 MHz
pll1|clk[1]  Generated  27.777 ns  36.0 MHz   Divide by 25, Multiply by 18
```

`DramPllGen` did its job: `jop_smp_sdram.qsf` includes
**`generated/dram_pll.vhd`**, regenerated by this build with `18/25`.

**The trap:** a stale `fpga/qmtech-ep4cgx150-sdram/dram_pll.vhd` is still
TRACKED at the board root, dated 2026-08-15, carrying `clk1 = x6/5` = 60 MHz.
Nothing builds it — but the Makefile still lists it as a prerequisite on three
targets, so it looks live. Reading it produced a confident and wrong conclusion
that the board ran at 60 MHz and the preset's `clkMhz` was ignored. It is a
decoy and should be deleted, or the Makefile prerequisites pointed at
`generated/`.

**Second wrong inference, worth knowing:** the first download failed at
1.8 Mbaud and succeeded at 2 Mbaud, which looked like proof of a clock
mismatch. It is not. This UART uses a FRACTIONAL accumulator
(`UartBaudTick`: `inc = round(2^24 x baud x samples / clkFreq)`), not an
integer divider, so **any** baud is reachable from any clock and 2 Mbaud is
exact from 36 MHz. The older rule of thumb that "the baud must divide
clkFreq/5 exactly" describes a divider this design no longer uses — it is why
1.8 Mbaud was tried first, and it was the wrong prediction.

**Not yet done:** 8 and 10 cores, where `13/6` may well route; and confirming
whether the 2026-08-15 baseline row was also really 60 MHz, which its own PLL
file suggests.

12 cores validated 2026-08-15: PLL 18/25 = 36 MHz, `SMPGC OK` with `minors 10
/ verified 192 / errors 0` on **4/4 runs**, `cores 12, publishers 11`, and
DoAll **66/66** generational. Per-core cost is ~11.3k LE. Fmax at 12 cores is
~40 MHz, so 40 (4/5) may also close and would keep 2 Mbaud — untested.

**~~THE BAUD RATE FOLLOWS THE CLOCK~~ — OBSOLETE since 2026-08-18, and it
misled someone on 2026-08-23.** This used to say that `UartCtrl` computes
`clockDivider = round(clkFreq / baud / rxSamplePerBit) - 1`, so an exact baud
needed `clkFreq / (baud * 5)` to be an integer — making 36 MHz transmit at
1.8 Mbaud rather than 2, and requiring `download.py ... 1800000`.

**That divider is gone.** `28d8d06` replaced it with a FRACTIONAL accumulator
(`jop.io.UartBaudTick`):

```
inc = round(2^24 * baudRate * samplesPerBit / clkFreq)
```

so **any baud is reachable from any clock**, and 2 Mbaud is exact from 36 MHz.
A 12-core build at 36 MHz consoles at **2000000**, verified 2026-08-23 by a
15,609-word download completing with a valid checksum.

**Following the obsolete rule cost an hour.** `download.py ... 1800000` failed
with "FPGA not responding", which was then read as evidence of a clock
mismatch, which led to a stale `dram_pll.vhd` and a wrong conclusion that the
board ran at 60 MHz. The board was fine and had always been at 36 MHz.

The diagnostic advice below it remains right and is what eventually settled it:
listen raw and sweep the baud until the ready byte reads back as `0xAA`; at the
wrong rate it decodes as a steady wrong value, which tells you the FPGA is
alive and only the rate is wrong.

Item 5's "area allows ~12 cores at 73 %" was based on a 4-core figure of
38,372 LE and is optimistic against today's builds — 12 costs 79 %. Shedding
the ~33k LE that 16 needs means trimming the object and array caches, which
are in the SMP coherency path, so it would change the thing under test rather
than just the size. Trimming CUs would not be enough and would not touch the
real cost, which is per-core memory-controller and cache logic.

**The failing test now exists and the bug is reproduced on hardware**
(2026-08-09). `java/apps/SmpGcTest` — core 1 stores a nursery object into a
TENURED holder, core 0 churns until a minor GC is observed, then re-checks
every magic word. On the EP4CGX150 SMP (2 cores, Ihlu):

| | boot line | result |
|---|---|---|
| guard in place | `GC: classic (SMP - per-core card tables…)` | `minors 0 verified 192 errors 0` → **INCONCLUSIVE** |
| guard removed | `GC: generational, 16-word cards` | `minors 31 verified 192 errors 192` → **FAIL** |

Every one of the 192 cross-core references was lost, and the magic reads back
as unrelated data — the young objects were collected while live and their
space reused. The one-line experiment is in `GC.java:552`: drop
`&& cpuCnt0 <= 1` from `genActive`. **The guard is restored**; that
configuration is unsound and must not be shipped.

The RTL fix landed in 767178b (one cluster-level table fed from the
memory-side bus) with timing closed in 2389e40, and **the guard was removed
in cd75352** — generational GC is now available on SMP.

**Validation sweep (2026-08-09), guard off by default:**

| check | result |
|---|---|
| `JopGenGcBramSim` — single core, no regression | **PASS**, `GC: generational, 4-word cards` |
| EP4CGX150 **2 cores** @80 MHz (MET +0.133) — `SmpGcTest` | **SMPGC OK**, `minors 31 verified 192 errors 0` |
| EP4CGX150 **2 cores** @80 MHz — `DoAll` | **66/66**, `GC: generational` |
| EP4CGX150 **4 cores** @60 MHz (MET +0.302) — `SmpGcTest` | **HANGS after tenuring — see below** |

**The 4-core hang is a SEPARATE, REAL bug — a stop-the-world halt that never
releases — and the guard is back, set at `cpuCnt <= 2`.**

Investigated by instrumenting the test: core 0's wait is now bounded and
prints a per-core heartbeat, so a hang says *who* stopped. It does not read
as a lost write:

```
STALL round 1 phase 1 publishRound 1  pub[1]=1 pub[2]=2 pub[3]=2
                                     live=117, 2283077, 2328250
```

Core 1's heartbeat is frozen dead at 117 mid-`publish()` — the allocating
path — while cores 2 and 3 spin millions of iterations. It has stopped
executing, not merely failed to publish a value.

My first hypothesis (`pubRound[]` losing a write to A$ snoop granularity)
was **wrong**, and so was the second. Making core 0 allocate inside its wait
loop — to give a GC initiated elsewhere a safepoint — did not help; it moved
the symptom, freezing **all three** publishers (heartbeats identical across
successive reports) while core 0 carried on running and printing. Cores
halted and never released is a stop-the-world halt/release fault, which is a
different mechanism from the remembered set entirely.

Note the earlier 4-core pass was luck, not a regression since: the only app
change between them returns `true` in this configuration, and the RTL and
timing were identical. Allocation volume decides which core trips the
nursery, and three publishers make it far more likely to be one of them.

**What is validated**: card table correctness (2 cores, `SmpGcTest` SMPGC OK
and `DoAll` 66/66 with generational active) and single-core no-regression.
**What is not**: anything above 2 cores. The guard is therefore set at the
validated boundary rather than at 1, and the boot line says which reason
applies — `SMP >2 cores - generational deadlocks` rather than the old, and
now wrong, "no card table", which would send the next reader hunting a
missing `hasCardTable` in the preset.

**IHLU is exonerated — the fault is in the shared stop-the-world path.**
Rebuilding the same 4-core config with `useCmpSync = true` (a single global
lock, IHLU not instantiated) **hangs in exactly the same place**, after
`minors after tenuring 6`. It is in fact a harder hang: with IHLU core 0 kept
running and printed its STALL diagnostics, whereas with CmpSync core 0 is
frozen too and prints nothing.

That was the point of the swap, and it rules out the obvious suspect: the
IHLU drain that exempts lock owners from `gcHalt`. Whatever fails is common
to both locking schemes, so look at `gcHalt` itself — the halt request,
acknowledgement and release — rather than at either lock.

`ep4cgx150Smp` now takes a `cmpSync` flag for exactly this bisection:
`sbt "runMain jop.system.JopTopVerilog ep4cgx150Smp 4 60 cmpsync"`.

Useful side observation: the CmpSync build closes timing at **+0.810 ns**
against IHLU's +0.302 at the same 4 cores and 60 MHz, so IHLU is costing
~0.5 ns. That is a separate lead for item 31.

**Code reading then found the likely cause — `minorGc()` never halts anyone.**
Every write of `IO_GC_HALT` in `GC.java` is in `startCycle()`,
`finishCycleNow()` or the public `gc()` — all classic/incremental paths.
**Neither `minorGc()` nor `majorGc()` asserts it.** So the generational
collector relocates objects and rewrites handles while every other core keeps
running, which is unsound on SMP independently of anything to do with cards.
That the guard's stated reason was only the remembered set is why this went
unnoticed: the guard was hiding two faults, and fixing the card table exposed
the second.

**CORRECTION to the deadlock shape given in 9cfbd65.** That commit proposed
"a core asserts `gcHalt` while another holds `mutex`, halting the very core
that must release it, then blocks on that lock". **That cannot happen** — the
hardware already handles it. Both lock units exempt the current lock owner
from `gcHalt` (`CmpSync.scala:137-147`, `Ihlu.scala:371`), and CmpSync's
comment says so explicitly: *"Lock owner is NEVER halted — must complete
critical section to avoid deadlock (e.g., GC core sets gcHalt while another
core holds the lock; owner must release first)."* The hypothesis was wrong in
its detail. The real mechanism is the mirror image of it: not the collector
*blocking* on the lock, but the collector *releasing* it mid-collection.

**The global lock is not reentrant, and the collector nests it.** In
`asm/src/jvm.asm`, `monitorexit` unconditionally writes `io_cpu_id`
(= `IO_UNLOCK`); the `lockcnt` it maintains decides only when to re-enable
interrupts, never whether to release. `Sys.scala:302` clears `lockReqReg`
unconditionally on that write. IHLU survives this because it reference-counts
each lock slot (`count(s)`); **CmpSync has one global lock and no counter at
all**, so an inner `monitorexit` drops the lock outright while the outer
critical section is still running.

The collector is entered from inside `synchronized (mutex)`
(`newObjectGen`/`newArrayGen` -> `allocGen` -> `majorGc` -> `gc`) and then
took the monitor *again* in eight places. `pushFast` was by far the worst: it
is called once per root and once per reference field, so a mark phase
acquired and released the global lock **thousands of times**, and every
release handed the heap to whichever core was waiting — in the middle of a
collection. `compactAndSweep`, `getStackRoots`, the two gray-list pops,
`prepareCompact`, `compactStep` and `finishCycle` did the same, less often.

That explains the symptom far better than a deadlock does. The observed
4-core failure was never a clean mutual halt; it is a corrupted heap.

`copyAndSweepYoung` even documents the invariant it did not have: *"Nothing
else can observe them mid-sweep — the collector runs stop-the-world with the
other cores halted."* For a minor GC that comment was simply false.

Two cores are unaffected in practice but are **not proven safe by this
analysis** — the same windows exist there, just far less likely to be hit.
Treat the validated 2-core result as empirical, not as a correctness
argument.

**Reproduced in simulation, which the hardware-only loop could not do.**
`JopGcHaltDeadlockSim` (new) runs `SmpGcTest` on a 4-core CmpSync BRAM
cluster with a cluster card table, and dumps `CmpSync.state`/`lockedId` plus
every core's `gcHaltReg`/`lockReqReg` when it wedges. First run, guard
lifted: generational genuinely active (`GC: generational, 4-word cards`,
`minors after tenuring 197`), and then core 0 died with an **uncaught
exception** at ~52M cycles — moments after `Native.wr(1, IO_SIGNAL)` released
the three publishers, i.e. as soon as anything else ran concurrently.

Note what the probe did *not* find: **no core was ever halted and no core
ever asserted `gcHalt`** at any sample. So the failure is not a stop-the-world
halt that never releases, which is how items above describe it. It is
memory corruption from a collection that ran concurrently with mutators.

**The fix** — one rule, applied throughout `GC.java`: *the allocation monitor
is acquired exactly once, at the outermost GC entry, and `IO_GC_HALT` is only
ever asserted while holding it.*

- `gc()` split into a public entry that takes the monitor and `gcLocked()`
 that assumes it; `gc_alloc()` and `majorGc()` call `gcLocked()`.
- Every nested `synchronized (mutex)` inside the collector removed, with the
 precondition documented at each site. `pushFast` is now collector-internal
 and lock-free; `push()` keeps the monitor for its one mutator caller, the
 snapshot-at-beginning write barrier.
- `tryGcIncrement()` takes the monitor around `gcIncrement()`. It is called
 from `newObject`/`newArray` *after* their synchronized block, so
 `startCycle()` was asserting `gcHalt` while owning no lock — the one place
 that really could be halted mid-root-scan by another core winning the lock.
- **`minorGc()` now stops the world**, which it never did. It asserts
 `IO_GC_HALT` after the `majorGc` fallback check (so the two halts do not
 nest and release early) and clears it after `Native.invalidate()`.

Holding the monitor across a whole collection also keeps interrupts disabled
for its duration (`monitorenter` disables them). That is a behaviour change
for the classic path and a deliberate one: a thread switch during `mark()`,
which scans other threads' stacks, was previously possible.

Side benefit: removing the per-push `monitorenter`/`monitorexit` should make
the mark phase measurably faster — `push()` was already documented as 78% of
the major GC's mark phase. That is also a candidate explanation for the open
*"major GC = 2.2 s @36k live, 20-25x the minor sweep, unexplained"* item in
*Performance* below: the mark phase was paying a lock round-trip per
reference. **Not yet measured — do not treat it as the answer.**

**RESULT: the crash is fixed, a freeze is not.** Same 4-core probe, rebuilt:

| | broken | fixed |
|---|---|---|
| reaches `minors after tenuring` | 197 @ ~52M | 196 @ ~55M |
| immediately after releasing publishers | **uncaught exception, `JVM exit!`** | runs on |
| by 200M cycles | dead | **frozen, no crash** |

So the concurrent-collection defect was real and is closed — but something
else stops the run, at ~55.8M cycles. With the probe reading the RIGHT
signals (see below), the end state is unambiguous:

```
CmpSync: state=LOCKED lockedId=1
core 0: pc=01b6 syncHalt=true  memBusy=false gcHalt=false lockReq=false
core 1: pc=02c3 syncHalt=false memBusy=false gcHalt=false lockReq=true   <- RUNNING
core 2: pc=01df syncHalt=true  memBusy=false gcHalt=false lockReq=false
core 3: pc=02a8 syncHalt=true  memBusy=false gcHalt=false lockReq=false
```

**Core 1 is not stalled — it is running, holding the global lock, and never
releasing it.** Its PC advances through the `goto` handler (0x2c1-0x2c4) with
`jpc` confined to 0x049a-0x049d: a tight loop of a few bytecodes containing
no method call (a call would swing `jpc` across the callee). The other three
have `lockReq=false` — they never asked for the lock. CmpSync halts *every*
non-owner whenever anyone holds it (`CmpSync.scala:141-147`), so they are
bystanders frozen by a lock they do not want, which is the design, not a
fault.

So the question is not "why does the owner stall" — it does not stall — but
**"why does it never release"**.

**The corrupt-handle-list hypothesis is REFUTED.** The probe now reads the
heap straight out of `ram.ram` and walks the chains itself, so the failing
binary is untouched. At the wedge:

```
youngList  head=0 (empty)
useList    head=0x0057e8  length=81   (terminates)
freeList   head=0x005368  length=1143 (terminates)
```

No cycle, no runaway. The bounded-walk guards added to `GC.java` are still
worth having — a corrupt list must never be able to wedge a whole cluster
silently — but they were never going to catch this, and in fact **could not
be used to look for it**: see the layout note below.

**What the wedge actually is.** Reading `SmpGcTest`'s and `GC`'s own statics
out of RAM at the freeze:

```
SmpGcTest: phase=2 publishRound=0 pubRound=[0,1,1,1] liveTick=[0,63,51,42]
GC: gcPhase=0 grayList=0xffffffff minors=196 youngObjects=0 toSpace=2
   copyPtr=0x005ba4 allocPtr=0x007b37 nursery=[0x007b37..0x008000] alloc=0x008000
```

Every one of those is IDENTICAL in every later snapshot. So:

- The collector is **idle and consistent** — a minor GC completed cleanly
 (`gcPhase=IDLE`, gray list empty, nursery reset, `youngObjects=0`), ~8000
 words of tenure are free and 1143 handles are on the free list. It is not
 stuck mid-phase and it is not out of memory.
- Core 1 is **not in the publisher loop**: that loop increments
 `liveTick[1]` every iteration and `liveTick` never changes, while core 1's
 PC keeps advancing.
- The test is genuinely deadlocked, not merely slow: all three publishers
 finished round 0, core 0 reached `phase=2` and must run to start round 1 —
 and core 0 is `syncHalt=true`, halted by the lock core 1 holds.

So core 1 holds the global lock and executes a loop that **writes no GC
static and no app static**.

**The bytecode cache names it.** Dumped from `jbcRamWord` at the wedge (again
without touching the binary), around core 1's `jpc`:

```
0x0499: 194 0xc2  monitorenter
0x049a: 167 0xa7  goto            <-- core 1 sits here / 0x049b / 0x049d
0x049b:   0 0x00  nop
0x049c:   0 0x00  nop
0x049d:  76 0x4c  astore_1        <-- handler entry
0x049e:  42 0x2a  aload_0
0x049f: 195 0xc3  monitorexit
0x04a0:  43 0x2b  aload_1
0x04a1: 191 0xbf  athrow
```

**ROOT CAUSE: `Startup.exit()` spins forever while holding a monitor.**

```java
public static void exit() {
   for (;RtThreadImpl.mission;) { RtThreadImpl.sleepMs(1000); }
   JVMHelp.wr("\r\nJVM exit!\r\n");
   synchronized (stack) {
       for (;;) ;              // <-- infinite loop INSIDE a monitor
   }
}
```

On one core that is a harmless way to park the CPU. Under CmpSync
`synchronized` takes the **global** lock and `CmpSync.scala:141-147` halts
every non-owner while it is held, so **any core that reaches `exit()` freezes
the whole cluster permanently**. The `goto` at cache 0x049a has operand bytes
`00 00` — branch offset ZERO, the `for(;;)` self-loop — and `jpc` only ever
samples 0x049a/0x049b, that goto and its operand.

The method was identified with `JopBytecodeLocate`, which loads the image
through `JopFileLoader` (what the simulator itself uses) and reports the
enclosing method for a byte pattern. The 9-byte sequence occurs exactly ONCE
in the image, in `Startup.exit()` at words 4095..4106.

**An earlier reading of this same dump was wrong and is retracted.** It said
"an exception inside `synchronized (mutex)` whose handler never completes"
(commit 7ab1019). The `astore_1; aload_0; monitorexit; aload_1; athrow` bytes
are just the any-catch handler javac emits for EVERY synchronized block; the
core never enters them, it is parked on the `goto` three bytes earlier. That
mistake came from a hand-written .jop parse whose word index was not the
memory address — it produced 12354 words against a header of 13175 and named
`Startup.version()`, which contains no synchronized block at all. Always
calibrate the mapping against a method whose bytecode is known:
`publisher()` must decode as `iconst_0; istore_1` then
`iaload; iconst_1; iadd; iastore`.

**FIXED** (`Startup.exit()` now does `Native.wr(0, Const.IO_INT_ENA)` then a
bare `for (;;) ;` — same intent, no lock). Re-running the 4-core probe proves
the fix bit: the cluster no longer freezes silently, and UART output that was
previously impossible now appears after `minors after tenuring 198` —
`ni 000000  000013  103  200`, the column layout of `JVMHelp.trace()`.

**A SECOND wedge is behind it, and it is the SAME BUG in two more places.**
`ni 000000  000013  103  200` is not `trace()`'s column layout, it is
`JVMHelp.noim()`'s own preamble, character for character: `wr('n'); wr('i');
wr(' ')` then `wrSmall(mp); wrSmall(start); wrByte(pc); wrByte(val)`. So an
**unimplemented bytecode was executed** — and `noim()` ends with

```java
Object o = new Object();
synchronized (o) {
   System.out.println(); ... trace(sp);
   for (;;);              // <-- infinite loop INSIDE a monitor, again
}
```

which is `Startup.exit()`'s bug verbatim, and worse: it allocates first, so a
machine that has just executed a wild bytecode re-enters the collector before
it parks. Grepping the runtime for `for(;;)`/`while(true)` within 25 lines of
a `synchronized` found exactly these two.

A third instance is in `JVM.f_athrow()`. It takes `Native.lock(0)` — the
global lock — on entry, and the uncaught-exception path deliberately never
releases it: *"No need to unlock if we're about to crash anyway"*. True on
one core; on SMP it stops every other core for the length of the report and
then forever, because `System.exit()` parks. One core's crash became four.

Two unbounded frame walks feed those paths: `JVMHelp.trace()`'s
`fp = vp+args+loc` chain and `f_athrow`'s unwind. Neither forced `fp` to
decrease, and both are only ever reached when the frames are already corrupt
— `noim()` printed **`mp=0`**, so they were being handed precisely the input
that spins them forever, with the global lock held. That is the "core 0 keeps
executing while holding the lock" behaviour, and it explains why the earlier
reading saw an integer-formatting loop: `wrSmall()` is called once per frame
of a walk that never ends.

**Regression sweep of these three runtime changes** (2026-08-10). The
`f_athrow` one matters most, because it touches the path every hardware
exception takes:

| check | result |
|---|---|
| `JopGenGcBramSim` — 1 core, generational | **PASS**, `GC: generational, 4-word cards` |
| `JopSmallGcBramSim` — 1 core, classic | **PASS**, 1 GC cycle in 14.1M |
| `JopJvmTestsBramSim` — the suite that fires HW exceptions on purpose | **PASS**, 132 `ok`, zero failures, normal `JVM exit!` |

**All three FIXED**: `noim()` and the uncaught path in `f_athrow()` now
report without holding a lock (`Native.wr(0, Const.IO_INT_ENA)` /
`Native.unlock(0)`), and both walks are bounded — `MAX_TRACE_FRAMES = 64`
plus a "frames must descend" check, which prints a truncation note instead
of hanging. `Native.unlock(0)` from a non-owner is safe: `CmpSync` releases
only when the **owner's** `req` drops (`CmpSync.scala:108-119`), so it cannot
disturb another core's lock.

**THE CORRUPTION ITSELF IS NOW CAUGHT AT SOURCE.** `JopGcHaltDeadlockSim`
grew a **write watchpoint** on the arbiter output — the same snoop point the
cluster card table uses, and the one bus that carries `source`, so a store is
attributed to a core without instrumenting the binary. It flags any store
that puts a `SmpGcTest` static outside its legal range. Two fired, 13 cycles
apart, both from core 0:

```
WRITE cycle=56614043 src=0 word=294 (cpuCnt)     data=0x00000004 (4)       jpc=0x070a
*** OUT-OF-RANGE *** publishers := -12484 — must be 3
WRITE cycle=56614059 src=0 word=295 (publishers) data=0xffffcf3c (-12484)  jpc=0x0712
*** OUT-OF-RANGE *** phase := 6 — the application only ever assigns 0..3
WRITE cycle=56614072 src=0 word=292 (phase)      data=0x00000006 (6)       jpc=0x071a
```

**Core 0 is executing `SmpGcTest.main()` — for the second time.** It ran it
once at cycle 248,508; its last legitimate act was `phase=2` at 56,100,122,
inside `core0()`'s round loop. At 56,614,021 it arrives at jpc 0x0700 after a
~95-cycle gap (a bytecode cache fill, i.e. an invoke) and runs main() from
its first bytecode.

The identification is not a guess. JOP's linker patches get/putstatic
operands to **absolute static addresses** (`jvm.asm`: *"put/getstatic support
in mmu (bc operand as address)"*, and `putstatic: stps opd` takes the address
from the operand), so the operand bytes in the cache dump can be read
straight off:

| jpc | bytes | operand | word | field |
|---|---|---|---|---|
| 0x0707 | `b3 01 26` | 0x0126 | 294 | `cpuCnt` |
| 0x070a | `b2 01 26` | 0x0126 | 294 | `cpuCnt` |
| 0x070f | `b3 01 27` | 0x0127 | 295 | `publishers` |
| 0x0717 | `b3 01 24` | 0x0124 | 292 | `phase` |
| 0x071b | `b3 01 25` | 0x0125 | 293 | `publishRound` |

Those are exactly `main()`'s statics in exactly `main()`'s order, and they
match the link file independently. The `ifne` at 0x0713 falls through, which
is `cpuId == 0` — core 0, as `source` says.

**The addresses are right and the DATA is wrong.** That split matters because
on JOP the two come from different places, and both are visible in the RTL:
`BmbMemoryController.scala:630` takes the address from `io.bcopd` (the
bytecode operand) and `:626` captures the data as `valueReg := io.aout`
(TOS) on the cycle the `putstatic` signal fires.

- `bipush -5; jopsys_rd; putstatic cpuCnt` -> **4, correct**.
- `getstatic cpuCnt; iconst_1; isub; putstatic publishers` -> **-12484**,
 implying the `getstatic` returned -12483 for a word this same core had
 written as 4 sixteen cycles earlier.
- `iconst_0; putstatic phase` -> **6**.

So `phase=6` is NOT a stray pointer scribbling over the statics, which is
what it looked like for two days. The address decoding is provably intact;
what arrives as data is not what the bytecode says. Everything after it —
`phase=6`, `publishRound=6`, the unimplemented bytecode, `noim()` parking
under the lock — is downstream. Core 0 holds the global lock
(`state=LOCKED lockedId=0`) throughout.

**Do not over-read the last bullet.** "Corrupt operand stack" is the obvious
story and it does not survive contact with `iconst_0`, which loads TOS with a
literal one instruction earlier — no stack pointer, however wrong, changes
what that puts in `A`. Two possibilities remain and they are very different
in consequence:

1. the core is not executing the bytecode this cache dump shows (the dump is
  taken at the same cycle and disassembles as valid `main()`, but that is
  consistency, not proof); or
2. `valueReg` is not capturing the `io.aout` that belongs to the store —
  note `memBusy=true` at both stores, and the capture at `:626` is not
  obviously qualified against a stalled pipeline.

(2) would be an RTL bug rather than a software one, and it should be
disqualified first precisely because it *cannot* be the whole story: the same
path executes correctly millions of times per run and on one core. Establish
which of the two it is by reading `A`/`B` and `io.aout` directly at the store
— the probe already has the write watchpoint to trigger on.

**WHAT PUT CORE 0 THERE: AN EXCEPTION STORM ON ALL FOUR CORES, 437k CYCLES
EARLIER.** Logging the hardware exception strobe (`cores(i).sys.io.exc`) for
the whole run — cheap, and it reaches back further than any ring buffer —
moved the origin a long way upstream of everything above:

| | cycle |
|---|---|
| core 0 sets `phase=2`, last legitimate act | 56,100,122 |
| **first hardware exception (core 1)** | **56,176,845** |
| ...200 more, every ~700-1400 cycles, all four cores | |
| core 0 executing `main()`, `phase := 6` | 56,614,043 |

Distribution over the logged window: core 1 x111 (all at jpc 0x0083), core 2
x39 (0x0683), core 0 x28 (0x0163), core 3 x22 (four sites). Each core is
**re-throwing from the same bytecode over and over** — one per loop
iteration, which is why the interval is so regular. Nothing before 56.176M
throws at all, and `SmpGcTest` contains no `throw` and no `try`.

So the ordering is settled: the storm comes FIRST, the wild control flow and
the bad stores are consequences of it, and `phase=6` is the last link in the
chain rather than the first. Every previous reading of this bug started from
the wrong end.

**THE EXCEPTION TYPE RULES OUT STACK OVERFLOW — and the fault is in the
INVOKE/BYTECODE-FETCH PATH, not in the heap at all.** Reading
`sys.excTypeReg` at each strobe:

```
EXC cycle=56176845 core=1 type=3 AB(array bounds)  jpc=0x0084 A=0x0000020b sp=155
EXC cycle=56177828 core=1 type=2 NP(null pointer)  jpc=0x0083 A=0x000000a0 sp=157
EXC cycle=56179249 core=1 type=2 NP(null pointer)  jpc=0x0083 A=0x000000a0 sp=157   (and on, identical)
```

One array-bounds, then null-pointer forever at a **fixed `jpc`, fixed `A`,
fixed `sp`** — a core re-faulting on the same bytecode. So `setSP` is not
involved and the earlier `sp=55` reading has some other cause; do not build
on it.

What the 16k-sample trace shows immediately BEFORE that first exception is
the actual fault:

```
56175990  0x0428 -> 0x0000  (gap 70, invoke + cache fill)   sp=150
56176031  0x0016 -> 0x0428  (gap 21, return)                sp=147     <- fine
56176187  0x0428 -> 0x0000  (gap 70, invoke + cache fill)   sp=150     <- same site, same target
...  ~450 bytes executed straight through, no control transfer at all ...
56176639..56176707  jpc 0x01c1 -> 0x0205, ONE BYTE PER CYCLE, pc frozen at 0x01e0
56176813  0x020b -> 0x0080  (gap 94, invoke + cache fill)
56176845  EXCEPTION
```

Core 1 invoked a method, **ran straight off the end of it**, nop-slid ~68
bytes through zero-filled bytecode cache (`pc` pinned at one microinstruction
while `jpc` increments every cycle is exactly a run of `nop`), hit bytes that
decoded as an invoke, and started faulting. The **same call site invoked the
same target 197 cycles earlier and returned normally** from `jpc 0x0016`.

That reframes the whole item. This is not heap corruption, and it never was:
the handle lists were intact, the collector was idle and consistent, and the
statics only went wrong 437k cycles later. **A method invocation went to the
wrong place, or its bytecode cache fill delivered the wrong bytes.**

**ANSWERED: THE INVOKE USES A NULL METHOD POINTER.** Logging every bytecode
cache fill (`bcRdCaptureReg` latches TOS at `bcRd`,
`BmbMemoryController.scala:649`, packing `start = val >>> 10`,
`len = val & 0x3ff`) and resolving `start` against the link file's method
table gives the last fill before the storm, unambiguously:

```
cycle 56176152  start=  6688 len=  6  raw=0x00688006  com.jopdesign.sys.JVM.f_i2b(I)I
cycle 56176778  start=     0 len=  0  raw=0x00000000  (before first method)
                                                      ^^^ NULL METHOD POINTER
```

`raw = 0` means the invoke asked to fill a method at address 0 of length 0.
Nothing is loaded, so the core executes **zero-filled cache**, nop-slides
(that is the `pc` pinned at one microinstruction while `jpc` increments every
cycle), runs into whatever the cache still held, and starts faulting 67
cycles later. That is the entire mechanism.

**`noim()` printed the same null pointer, from the software side, and nobody
noticed for two days.** The fill list shows core 1 was already inside
`JVMHelp.noim()` at cycle 56,172,657 — *before* the exception storm — with
its whole print path visible (`PrintStream.println`, `String.getBytes`,
`GC.newArray`/`newArrayGen`/`allocGen` from the `new Object()`). And the UART
line reads `ni 000000  000013  103  200`, whose **first column is
`wrSmall(mp)` = 0**. `mp = 0` was the null method pointer all along.

So the chain, end to end, and every link measured:

1. an invoke reads a method pointer of **0**;
2. the cache fill loads nothing; the core executes zeros, nop-slides, and
  hits an unimplemented bytecode;
3. `JVMHelp.noim()` reports it (printing `mp=0`) and then — in the binary
  under test — allocates and parks forever holding the global lock;
4. inside that print/allocate path a **second** null-pointer invoke happens
  (56,176,778), starting the AB-then-NP exception storm on all four cores;
5. 437k cycles later core 0 resumes into `main()` on a wrecked stack, writes
  `phase := 6`, and the cluster is frozen.

`phase=6` — where this investigation started — is step 5 of 5.

### The board's MHz labels straddle a real PLL change — read this before comparing runs

`dram_pll.vhd` **hardwires** the system clock: 50 MHz in, times
`clk1_multiply_by` over `clk1_divide_by`. The preset's MHz argument does NOT
set the clock. It only feeds the SDC constraint and the UART baud divider
(`Uart(baudRate, clkFreq)`).

| commit | PLL | actual clock |
|---|---|---|
| `3405d75` | x8/5 | 80 MHz |
| **`7be77d0`** — *"4-core hang ... re-guard at 2 cores"* | **x6/5** | **60 MHz** |

So the commit that re-guarded at 2 cores also dropped the board from 80 to
60 MHz. **"2 cores @80 MHz" and "4 cores @60 MHz" in the tables above are not
the same hardware**, and comparing results across `7be77d0` compares two
different clocks.

Two practical consequences, both learned the hard way today:

- Building with an MHz argument that disagrees with the PLL **breaks the
 UART** — the divider is computed from the declared frequency, so an 80 MHz
 build on a 60 MHz PLL runs the link 25% slow and the downloader reports
 *"FPGA not responding (no ready signal)"*. That is a build mismatch, not a
 dead board.
- Restoring the PLL to x8/5 and building 2 cores at 80 MHz reproduces the
 documented **+0.133 ns** slack exactly, so that configuration is
 identifiable. But it no longer downloads reliably — two attempts streamed
 all 13479 words and then stalled at checksum verification. +0.133 ns is
 very tight, which is presumably why the clock was lowered in the first
 place.

### HARDWARE VERDICT (2026-08-10) — the bug is real, and it is NOT the collector

The EP4CGX150 was rebuilt at **4 cores / 60 MHz**, closing timing at
**+0.302 ns** — the identical figure recorded for the 2026-08-09 hang, so it
is the same configuration, not a lookalike. Three runs on that one bitstream:

| run | result |
|---|---|
| `NCoreHelloWorld`, 4 cores | **WORKS** — boots and prints indefinitely |
| `SmpGcTest`, 4 cores, **generational** (guard lifted) | fails at `minors after tenuring 6`, then `ni 006400  527810  011  200` |
| `SmpGcTest`, 4 cores, **classic** (guard in place) | **HANGS SILENTLY** after `minors after tenuring 0`, 150 s, nothing |

Four things follow, and hardware has no X-state so none of them is an
artifact:

1. **The board, the 4-core build and the modified runtime are all sound** —
  `NCoreHelloWorld` is the control that says so.
2. **`JVMHelp.noim()` fires on real hardware.** The wild-execution ->
  unimplemented-bytecode chain that the simulator found is genuine. `mp` here
  is 6400, not 0, and `start` decodes to 527810 — an address far outside a
  52 KB image — so it is the same shape of fault, a method pointer leading
  nowhere.
3. **It reproduces at exactly the documented failure point**,
  `minors after tenuring 6`, which is the number the 2026-08-09 CmpSync run
  printed before going quiet.
4. **THE CLASSIC COLLECTOR HANGS TOO.** Same bitstream, same app, same cores;
  only `genActive` differs. So the fault is **not generational**, and the
  `cpuCnt <= 2` guard has been guarding the wrong thing. Note this
  contradicts the previously recorded "4-core classic completes clean" —
  that claim does not reproduce and should not be relied on.

**Core-count bisect, all at 60 MHz on the current RTL.** This is the result
that should change how the item is framed:

| cores | collector | runtime | result |
|---|---|---|---|
| 4 | generational | HEAD | `minors 6` -> `ni ...` (noim) |
| 4 | classic | HEAD | silent hang |
| 3 | generational | HEAD | silent hang |
| 3 | classic | HEAD | silent hang |
| 2 | generational | HEAD | silent hang |
| 2 | generational | pre-`d8d93f8` | `STALL round 2` |
| 2 | generational | pre-`6cd91bd` | `STALL round 2` |
| 4 | `NCoreHelloWorld` | HEAD | **WORKS** |

**`SmpGcTest` fails at TWO cores as well**, so there is no >2-core boundary.
Reverting the three lock-park fixes still fails, and reverting the GC monitor
restructuring (`6cd91bd`) still fails — both land on the same
`STALL round 2`. So neither change caused this.

### THERE IS NO PRODUCT REGRESSION — the TEST changed, and the new one is harder

Rebuilding at 80 MHz (both PLL clocks, see above) and varying one thing at a
time settles it. All 2 cores @80 MHz, all closing at the same +0.133 ns:

| RTL | runtime | app | result |
|---|---|---|---|
| `fbf3d42` | `fbf3d42` | `fbf3d42` | **`minors 31 verified 192 errors 0`, `SMPGC OK`** |
| **HEAD** | **HEAD** | `fbf3d42` | **`minors 31 verified 192 errors 0`, `SMPGC OK`** |
| HEAD | HEAD | HEAD | `Uncaught exception:` |
| HEAD | pre-`6cd91bd` | HEAD | `ni 010619 000010 002 255` (noim) |

Row 1 reproduces the documented sweep **exactly**, numbers and all, so that
validation was real. Row 2 is the important one: **HEAD's RTL and runtime pass
the original test**, so nothing in the product regressed — including the three
lock-park fixes, which are in that build.

The variable is `SmpGcTest.java` itself. `7be77d0` changed it (+46 lines) at
the same time as the PLL and the guard, and among the additions is

```java
if (!all) { Object y = new Young(); if (y == null) return; }
```

— **core 0 now allocates inside its wait loop**, added to give a GC initiated
elsewhere a safepoint. That turns the test from "core 0 waits quietly while
publishers allocate" into "every core allocates concurrently, with cross-core
stores in flight". It is a strictly better test, and it fails at **two** cores
where the old one passes.

**So the item should be reframed again.** This is not "generational GC breaks
above 2 cores". It is: *concurrent allocation on several cores, with
cross-generation stores, corrupts execution* — and it reproduces at **2 cores
@80 MHz**, which is the cheapest and best-validated configuration on the
board. The `cpuCnt <= 2` guard is not shipping a knowingly broken
configuration for the original workload, but it does not protect against this
either.

**Use 2 cores @80 MHz to chase this**, not 4 cores @60: same fault, half the
cores, the clock the board is actually validated at, and a one-second run.

### AT 2 CORES THE FAULT *IS* GENERATIONAL — matched pair on one bitstream

Everything below is 2 cores @80 MHz on the same bitstream, same app source,
same allocation load. The only difference in the last two rows is `genActive`.

| app | collector | result |
|---|---|---|
| HEAD | generational | ❌ `Uncaught exception:` |
| HEAD, no alloc in core 0's wait loop (variant A) | generational | ❌ `STALL round 1`, core 1 frozen at `live=190` |
| HEAD, no `liveTick[id]++` (variant B) | generational | ❌ `STALL round 1`, and core 0 reads its OWN statics back as `phase 0 publishRound 0` at round 1 |
| churn allocates in classic too | **classic** | ✅ **`minors 0 verified 192 errors 0`**, all 8 rounds |
| churn allocates in classic too | **generational** | ❌ `STALL round 1`, core 1 frozen at `live=44` |

The last two rows are the experiment that matters. `churnUntilMinor` normally
short-circuits for classic (`if (!generational()) return 0;`), so a plain
classic run barely allocates and proves nothing — that confound is why the
4-core "classic hangs too" result was over-read. Removing the short-circuit
makes classic allocate exactly as hard, and **classic then completes cleanly
while generational fails**.

**So this correction is needed: "not a collector bug" was wrong at 2 cores.**
That claim came from 4-core classic hanging, which was measured with the
short-circuit in place. The consistent reading of all the hardware data is
two distinct faults:

- **generational, >=2 cores** — the one reproduced here, collector-specific;
- **something more general, >=3 cores** — classic hangs at 3 and 4 cores even
 with almost no allocation, and passes at 2.

Variants A and B both still fail, so **no single line of the test is the
trigger** — which also means the reproduction is robust to editing the app.
That lifts the constraint that dominated the simulator work: `SmpGcTest.java`
*can* be instrumented here without the failure evaporating.

**Sharpest signature to chase next**: core 1 stops executing inside
`publish()` — its `liveTick` heartbeat freezes at a fixed value (190, 44)
while core 0 keeps running — i.e. a publisher dies while a minor GC is in
progress on the other core.

Reproduction recipe (the PLL edit is required; HEAD ships 60 MHz for 4-core):

```
# dram_pll.vhd: set BOTH clk1_multiply_by and clk2_multiply_by to 8  (x8/5 = 80 MHz)
sbt "runMain jop.system.JopTopVerilog ep4cgx150Smp 2 80"
make -C fpga/qmtech-ep4cgx150-sdram build-smp program-smp
make -C java runtime && make -C java/apps/SmpGcTest clean && make -C java/apps/SmpGcTest
python3 fpga/scripts/download.py -e java/apps/SmpGcTest/SmpGcTest.jop /dev/ttyUSB0 2000000
```

Rebuild the app every time — a stale `.jop` silently passes, which cost a run
here (the binary was 4 KB smaller than the source implied).

**`make program-smp` SILENTLY REBUILDS the bitstream.** `program-smp` depends
on `$(SOF_SMP_FILE)`, so touching `dram_pll.vhd` — including reverting it with
`git checkout` — makes the `.sof` stale and the next *program* step rebuilds
it. That produced an 80 MHz design on a 60 MHz PLL: the board came up, sent
its ready byte at exactly 3/4 rate, and `download.py` reported
*"FPGA not responding"*. Diagnosed by sweeping the host baud — `0xb4` at
2 Mbaud became a clean `0xAA` at 1.5 Mbaud, and 1.5/2.0 = 60/80 named the
mismatch immediately. **Check the reported slack after any program step**:
+0.133 ns means 80 MHz, +2.36 ns means 60.

### THE ORIGINAL BUG IS BACK, AND IT IS A LOST CROSS-GENERATION REFERENCE

Instrumenting the test (per-statement `pubStep`/`pubSlot` markers, and reading
the magic through the handle instead of casting) gives a **deterministic**
2-core failure — byte-identical across three runs:

```
LOST slot 0 round 0 magic 3 want 1515847680
STALL round 1 phase 1 publishRound 1 pub[1]=1 live=101, step[1]=10 slot=23 holder=23
```

`magic 3` where `0x5A5A0000` was written means the young object **was
collected while still referenced from a tenured holder** — exactly the fault
`SmpGcTest` was written to catch, and item 1's original premise, happening at
2 cores *with the cluster-level card table in place*.

A second run confirmed it from another direction: an uncaught
`ClassCastException`, with a bounded stack trace naming the frames via the
link file's `-mtab` entries —

```
JVM.f_checkcast  <-  test.SmpGcTest.core0  <-  main  <-  Startup.boot
```

— the `(Young) o` in the verify loop, throwing because the holder's reference
now points at reused space that is no longer a `Young`. (That trace printing
cleanly and ending in `JVM exit!` instead of freezing the cluster is
`4df8edd` + `d8d93f8` working on hardware.)

**Mechanism, and it explains why the OLD test passed.** In the old test core 0
churned only during `phase == 2`, after every publisher had finished, so
minor GCs and cross-generation stores were **serialised**. The new test
allocates in the wait loop during `phase == 1`, so a minor GC on core 0 now
runs **while core 1 is storing a nursery object into a tenured holder**. And
the variants line up with that reading:

| | overlap? | lost refs? |
|---|---|---|
| old test | no | none — `SMPGC OK` |
| variant A (no wait-loop alloc) | no | **none** — freeze only |
| HEAD | yes | **LOST slot 0** |

So: **a minor GC concurrent with a cross-generation store loses the
reference.** The card mark and the collector's scan/clear of the card table
are not safe against each other, even though `minorGc()` asserts
`IO_GC_HALT` — which suggests the halt does not take effect before the
window that matters. That is the thing to look at next: the ordering of
card-table clear, card scan, and `gcHalt` taking effect.

And it decomposes the item into two faults, each with its own experiment:

1. **lost cross-generation reference** — needs the overlap; use HEAD's test.
2. **the freeze** — survives *without* the overlap (variant A), so it is
  independent and still unexplained.

Caveat: the failure mode moves with code layout (which statement it dies on,
whether it reaches verification), as it has all along. The *kind* of failure
is stable; the exact line is not. Do not bisect on the symptom.

### Card-clear experiment: SUGGESTIVE, not conclusive — and why

Commenting out `Native.wr(-1, Const.IO_CARD_CLEAR)` in `minorGc()` (cards then
accumulate, so every later minor GC scans everything ever dirtied) removed the
loss: round 0 verified with **no `LOST` line**, where the same test with the
clear in place reported `LOST slot 0` deterministically. Read at face value
that says the barrier *does* record core 1's store and the scan-then-clear
ordering loses it.

**Do not bank it yet.** The two builds are not the same binary, and this
workload is layout-sensitive: restoring the clear and adding the card-bit
probe produced a build that froze at **round 0** (`step[1]=3`, core 1 inside
`publish()` before its store, `holder=null`), so it never reached
verification and could not confirm the other half of the pair. One-line edits
move where it dies.

**The freeze now blocks measuring the loss**, so it is no longer the lesser of
the two faults — it has to be dealt with first or in parallel.

**What would make the card-clear result airtight**: A/B *inside one binary*.
Add a non-final `public static boolean cardClearEnabled` to `GC`, gate the
clear on it, and have the test flip it per round. One image, one run, losses
correlated against the flag — no layout variable at all. That is the next
thing to build, and it is worth doing properly because the answer decides
whether the fix is in the collector's ordering or in the hardware barrier.

### A cache-invalidate-on-halt-release fix exists in the history — see `a31b2cc`

Built, verified, and then **reverted deliberately**. If cross-core cache
staleness comes up again, take the implementation from that commit rather
than rewriting it; the reasoning and the traps are in its message.

**What it did.** `ObjectCache` snoops remote *putfield* traffic only
(`BmbMemoryController:517-519`, keyed on handle + field index), while the
collector moves objects with raw writes (`copyAndSweepYoung`:
`Native.wrMem(dst, ref+OFF_PTR)`). Relocating an object therefore generates
**no snoop at all**, so a core resuming from `gcHalt` can hold pre-move
state. `Native.invalidate()` at the end of `minorGc` cannot help — it is
core-local and the cores that need it are halted. The fix drove `cinval` for
the whole duration of a stop-the-world.

**Why it was reverted:**

- It cost **0.233 ns** of timing margin (2 cores @80 MHz: +0.413 -> +0.180),
 on a design where 4 cores at 80 MHz already misses by -2.399.
- It fixed nothing observable — `STACKROOT` was unchanged, with the identical
 `magic 11818962`.
- The path is disabled anyway while the guard sits at `cpuCnt <= 1`.
- It was the sixth confident-but-unmeasured hypothesis of that session, after
 "corrupt operand stack", "stack overflow", "leaking halt", "the read-port
 steal" and "stale mutator caches" had each been killed by measurement.
 Landing unfalsifiable RTL cuts against the only method that has worked here.

**Two implementation details worth not rediscovering**, both in `a31b2cc`:
hold `cinval` for the *duration* of the halt rather than pulsing on the
falling edge (valid bits clear on a clock edge, so a core resuming in the
same cycle could get one stale read in first); and **register** the
cross-core signal, because feeding it combinationally into the invalidate
fanout costs 1.5 ns and fails timing outright (-1.407).

**Re-apply it when there is a test that demonstrates the staleness.** That
"invalidating changed nothing" is not proof the hole is absent — only that
`STACKROOT` does not exercise it.

### RETRACTED: "the collector is correct, the cores disagree" (`63c1ed5`)

That finding, and the `magic 11818962` it rested on, were an **artefact of the
probe**. Retired 2026-08-12.

`GC.rootRead()` sets `rootSel` and does not clear it. The cluster drives the
TARGET core's stack-RAM read address straight from that register, with
"index != 0" meaning "a debug read is in progress", so leaving it set steals
the target's read port — every operand fetch returns the last word scanned.
`scanOtherCoreRoots()` documents this and releases the port at the end; the
`dump:` block added to SmpGcTest scanned 256 words and **never released**,
while core 1 was still RUNNING. Everything core 1 reported after that point
was the instrument, not the machine.

The signature was unmistakable in hindsight: three DIFFERENT addresses
(`h+OFF_PTR`, `h+OFF_SPACE`, `h+OFF_TYPE`) all returned the same 4194304
(`0x400000`), and dereferencing it returned the contents of address 0.

Standing check: `git show 415293e` has **zero** `rootRead` calls, so the ROOT
CAUSE finding below predates the leak and is unaffected. Only the later
coherence story was wrong — which is also why `a31b2cc` fixed nothing when it
invalidated caches: there was nothing stale.

`GC.rootRelease()` now names the obligation, and both call sites use it.

### THE REAL REMAINING FAULT: A WILD `stcp` CORRUPTS `GC.handle_cnt`

Caught by watchpoint 2026-08-12, deterministic on both seeds:

```
WRITE cycle=5327112 src=1 word=55 (GC.handle_cnt) data=0x0 pc=0x0684 jpc=0x0fec A=0x0a B=0x15
WRITE cycle=5327304 src=1 word=55 (GC.handle_cnt) data=0x4 pc=0x0684 jpc=0x0fec A=0x0a B=0xffffffd3
```

`handle_cnt` is assigned once in `GC.init` and never reassigned, yet it goes
**1117 -> 0 -> 4**. `src=1` is CORE 1 — the publisher, not the collector.

`pc=0x0681` is `jopsys_memcpy` (bytecode 0xE8) per `JumpTableData.scala`, and
the calibration is exact: counting active microcode words from that label
(`stcp`, `pop`, `wait`, `wait`, `pop nxt`) puts `jopsys_nop` at 0x686, which is
where the table says it is. `jopsys_memcpy` is not a stub — upstream JOP
deliberately implements it as a single `stcp`, the hardware GC copy engine
(`BmbMemoryController:724`, "At stcp: TOS = pos, NOS = src").

**Nothing in the Java tree calls `Native.memCopy`.** So 0xE8 should never
execute; that it does means core 1 is running GARBAGE AS BYTECODE, and 0xE8
fires the copy engine with whatever is on the stack.

Why this is quietly fatal: every handle-list walk hoists `handle_cnt` as its
cycle-guard limit, so a value of 4 makes `gcListOverrun` TRUNCATE valid chains.
The reported overruns are healthy lists being cut short — `walk=1 iters=5
handles=4 ref=22448 next=22456`, one HANDLE_SIZE apart. Truncated walks then
drop live objects, and core 1 freezes.

**So the GC is downstream of everything.** The remaining bug is a control-flow
derailment on core 1, not a collector fault.

### THE HEAD OF THE CHAIN: AN ARRAY-BOUNDS EXCEPTION ON A VALID INDEX

Established 2026-08-12 with a 24-sample trailing window on the jpc ring.
Everything above is downstream of this:

```
4733632  *** EXCEPTION #1 on core 1: AB(array bounds) ***
4733634  pc=0x00a7  (sys_exc region, jvm.asm:550)   A=0x12345678  sp=90
4733640  jpc=0x0b74  METHOD ESCAPE, 2548 bytes outside [0x0180, 0x025c)
4733705  NULL METHOD POINTER, fill [0x0700, 0x0700) — len 0
5327112  aliased 0xE8 -> jopsys_memcpy -> stcp -> GC.handle_cnt 1117 -> 0 -> 4
```

Two ORDERING CORRECTIONS to earlier commits in this series, both of which
named a later link as the head:
- `41cbbf8` called the null method pointer the first event. It is not; it is
 ~70 cycles downstream of the exception.
- `a9a5bf7` called the poison read the first event. It is not; it is 2 cycles
 downstream, and `pc=0x00a7` shows the core was already in `sys_exc`. The
 poison in `A` is handler state, not a cause.

**The exception is on a VALID index.** Core 1 is in
`test.SmpGcTest.publisher(I)V` (confirmed by pattern match, one hit: `MATCH at
byte 0x0059e6 = word 5753, words 5728..5783`) executing the heartbeat
`liveTick[id] = liveTick[id] + 1` — bytecodes `0xe0 aconst_null 0x97 iload_0
iaload iconst_1 iadd iastore`. `liveTick` is `int[cpuCnt]` = `int[2]` and
`id` is 1. At the exception `liveTick=[0,13824]`, so that exact statement had
already run 13824 times.

State at the exception rules out the obvious candidates:
- **No GC in flight** — `gcPhase=0`, `gcHalt` asserted by 0 cores, `halted=0/2`
- **Heap metadata intact** — `handle_cnt=1117`, not yet corrupted
- **Not the lock** — `CmpSync state=IDLE`

So the hardware bounds check read an array length of <= 1 for an `int[2]` that
had worked 13824 times. Prime suspect is the length read itself: `ArrayCache`
is a 16-entry FIFO with SMP snoop-invalidate, and core 0 is allocating hard
throughout. NEXT: watch the length operand the bounds check actually uses,
against `H[OFF_MTAB_ALEN]` in RAM, and report the first disagreement.

### RETRACTED: "the AB fires on a VALID index" — the probe supplied the index

The index was NOT valid. Reading `handleIndex` out of the hardware at the
exception (rather than reasoning from `liveTick=[0,13824]`) gives:

```
AB DETAIL core 1: handle=0x005c30 (23600)  index=3430008
                 RAM[handle+1] (length) = 2   RAM[handle+0] (ptr) = 31567
```

The handle is CORRECT — length 2 is exactly `liveTick`'s. The index is
3430008 = **0x345678**, i.e. `0x12345678` truncated to the index width. The
bounds check was behaving perfectly; the index operand was poison.

**The probe manufactured it.** `SmpGcTest`'s `rootport:` block called
`GC.rootRead(1, ROOT_WHAT_STACK, 64)` while core 1 was RUNNING. That parks
`rootSel` on core 1's stack word 64, whose contents are `0x12345678` (the
mem_ram.dat fill pattern — printed in the same line as `w64 305419896`). Core
1's concurrent `iload_0` therefore returned `0x12345678` as `id`. Every number
matches an independently observed value.

This is the THIRD instrument-induced fault in this investigation, all from the
same root — `rootRead` leaving `rootSel` set:
1. the `dump:` block manufacturing `magic 11818962` (retracted above)
2. `bcDump` returning -1 and printing an EMPTY dump at the one moment worth
  seeing
3. `rootport:` manufacturing this AB
Releasing AFTER a block is NOT sufficient — the theft spans the whole block.
Both blocks are now behind `PROBE_RUNNING_CORE`, default FALSE.
`scanOtherCoreRoots()` is unaffected: its targets are halted.

Also corrected: the poison in `a`/`b` at the strobe was NOT the aborted
`iaload` refilling registers. The poison arrived first, through the stolen
port, and CAUSED the AB.

With the probes gated off (2 cores, seed 70704150):
```
NULL METHOD POINTER   2 -> 0        OUT-OF-RANGE STORE  0
handle_cnt            constant 1123 (no corruption)
STACKROOT             OK, PTR-AGREE
R0 clear=ON  minors 1 lost 0  haltLeak 0     <- the test now RUNS ROUNDS
```

### FIXED 2026-08-13 — three signals must freeze, and now they do

`BytecodeFetchStage`: `when(io.stall) { jbcAddr := jpc }` as the HIGHEST
priority arm of the read-address mux, above `jmp` and `jfetch`.
`JopPipeline`: `bcfetch.io.stall := fetch.io.frozen`. Both are required —
either alone desynchronises.

Verified in the order cheapest-first, which is the point:

1. **Boundary trace** — `jpaddr` holds at 0x02c5 for all 20 frozen cycles,
  alongside pc/jpc/jinstr. It previously slid to 0x0228 on the second cycle.
2. **Reproducer** — `EXC=0 ESCAPE=0 NULLMP=0 WILD=0`, handle_cnt constant, and
  SmpGcTest runs **R0..R5 all `lost 0`** at 2 cores. It derailed at ~4.9M
  every previous run.
3. **Regressions** — JvmTests 132, McFallback 132 (pinned seed), DcuCache
  59/59, formal **122** (was 121; the new property is the +1).

**A formal property now covers this**, in `BytecodeFetchStageFormal`:
during a sustained stall, `jpc`, `jinstr` and `jpaddr` are all stable. It has
TEETH — verified to FAIL with the fix disabled and pass with it, which matters
because this repo has form for tests that cannot fail (items 2 and 29).

Three exclusions the solver forced, each a real fact about the design:
a cache FILL (`jbcWrEn`) legitimately rewrites RAM under the read; `jpaddr`
needs ONE cycle to settle because entering a stall moves `jbcAddr` from
`jpc+1` back to `jpc` and the JBC RAM is synchronous; and `exc`/`irq`
legitimately redirect dispatch to `sysExcAddr`/`sysIntAddr`.

**Why this was invisible for so long:** every one of the ~12 tests in
`BytecodeFetchStageTest` sets `io.stall = false`, and the formal harness drove
`stall` with `anyseq` but asserted nothing about it. The stall path had NO
behavioural coverage, which is exactly how a fix that skipped a dispatch
passed 132/132/59/121.

### The history: the naive fix was wrong — see `4ba87fc`, reverted

Built, regression-clean, and REVERTED because it traded one derailment for a
subtler one. Take the analysis from here; do not re-apply the patch.

**The bug is real.** `jfetch`/`jopdfetch` are ROM bits carried in IR;
`FetchStage` freezes on `(pcwait && bsy)` and HOLDS IR, so they stay asserted
for every cycle of the stall, while `bcfetch.io.stall` saw only
`stackRotBusy`. Measured: `iastore` ends `wait; wait; nop nxt`
(ROM 0x2f9/0x2fa = 0x101, 0x2fb = 0x900 with bit 11 `jfetch` set); frozen at
0x2fb, jpc walked 0x024d -> 0x025d one byte per cycle through the method end
at 0x025c. 127 frozen-at-0x2fb samples.

**The naive fix** — expose the freeze as `fetch.io.frozen` and use it for
`bcfetch.io.stall` — kills the walk (127 -> 0 samples) and passes everything:
JvmTests 132, McFallback 132, DcuCache 59/59, formal 121/121.

**But it misaligns the bytecode stream on resume.** A NEW escape appears,
only in runs with the fix:

```
without fix   cycle 4896159  jpc=0x0264   (the walk)
with fix      cycle 4896564  jpc=0x0c00   (a one-cycle jump)
```

Traced: `0x01ee` holds 0xE0 = `getstatic_ref` (handler 0x2C5), a THREE-byte
instruction. On resume the core dispatched pc=0x0228 — the handler for 0x01,
the byte at `0x01ef` — so `getstatic_ref` was never dispatched at all. It then
executed `0x99` at `0x01f0`, that instruction's second operand byte, as `ifeq`
(pc=0x2B9), which branched on a bogus 16-bit offset 0x1A10 truncated to 0xA10:

```
jpc_br 0x1F0 + 0xA10 = 0xC00   == observed
```

The correct stream is `getstatic_ref pubStep; iload_0; bipush 10; iastore`,
i.e. `pubStep[id] = 10`. So an instruction's dispatch was skipped and its
operands ran as opcodes — an OFF-BY-ONE across the freeze/resume boundary.

Why the regressions did not catch it: they never hit this boundary. Passing
132/132/59/121 says the suites do not exercise it, not that the change is
sound — the same trap that made `lmul_sw` look fine for years (item 29).

**SOLVED 2026-08-12 by an A/B trace of the boundary — THREE signals must
freeze and the reverted fix froze two.** Run the sim with a trace window
(`argv 5/6/7` = from, to, core) to reproduce either side.

WITHOUT the fix, frozen at 0x02fb (`nop nxt`, jfetch=1), a bytecode is
CONSUMED EVERY CYCLE — `jinstr` latches 0x4f, 0xe0, 0x01, 0x99, 0x1a while pc
is stuck and none of them execute:

```
cy=4896042 pc=0x02fb jpc=0x01ef jinstr=0xe0 jpaddr=0x0228 frz=1
cy=4896043 pc=0x02fb jpc=0x01f0 jinstr=0x01 jpaddr=0x02b9 frz=1
```

The control case is in the same trace: frozen at 0x02fa (a `wait`, jfetch=0),
cycles 4896033-4896039, NOTHING advances. The damage is specific to freezing
on an instruction whose jfetch bit is set.

WITH the fix, `jpc` and `jinstr` hold correctly — but `jpaddr` slips and stays
slipped:

```
cy=4896041 pc=0x02fb jpc=0x01ee jinstr=0x4f jpaddr=0x02c5 frz=1
cy=4896042 pc=0x02fb jpc=0x01ee jinstr=0x4f jpaddr=0x0228 frz=1   <- and stays
```

0x02c5 is `getstatic_ref`'s handler, correct for the 0xE0 at jpc=0x01ee;
0x0228 is the handler for 0x01, the byte at 0x01ef. The cause is the read
address mux, which `io.stall` does NOT gate:

```scala
}.elsewhen(io.jfetch || io.jopdfetch) {
 jbcAddr := (jpc + 1)(config.jpcWidth - 1 downto 0)   // prefetch next
}
```

`jfetch` is held through the freeze, so `jbcAddr` advances to jpc+1, the RAM
returns the NEXT byte and `jpaddr` recomputes to its handler. On release
`pcMux := io.jpaddr` sends pc to 0x0228 — `getstatic_ref` skipped, its operand
executed as an opcode.

**So the fix must hold `jpc`, `jinstr` AND `jbcAddr`.** `io.stall` already
covers the first two (`when(!io.stall)` at :195/:239 and
`when(!io.stall && io.jfetch)` at :260). The missing piece is gating the
`jbcAddr` mux so the read stays on the pending byte during a stall.

Watch the 1-cycle `readSync` latency when doing it: `jpaddr` reflects
`jbcAddr` from the previous cycle, so a naive hold still lets one wrong value
through on the first frozen cycle. Re-run the same A/B window to confirm
`jpaddr` holds at 0x02c5 for the whole freeze.

### STILL OPEN: core 1 streams jpc past the method end from `iastore`

Remaining head of chain, with the probes off. Five consecutive escapes, then
ABs on GARBAGE HANDLES (105, 114, 172 — far below heapStart ~23784), and at
the first AB `jpc=0x0853`, already outside the cache. So the ABs are again
downstream; the escape is the head.

```
4896136..4896152  pc=0x02fb CONSTANT, jpc 0x024d -> 0x025d, one byte per cycle
4896159           METHOD ESCAPE jpc=0x0264, method [0x0180, 0x025c)
```

`pc=0x02fb` is the LAST microcode word of the `iastore` handler (`iastore`
starts at 0x2f6, `iaload` at 0x2fc per JumpTableData). A fixed microcode
address with `jpc` advancing every cycle is operand fetching that does not
stop: `jpc` increments on `jfetch || jopdfetch`, so something is holding one
of those asserted at the end of an array store, and `jpc` walks straight
through the method end.

NOT a stop-the-world artefact: at the first AB, `gcHalt` is asserted by 0
cores and `halted=0/2`.

### BUG 2, SOLVED: `sys_exc` CORRECTS jpc THROUGH THE OPERAND STACK

A per-cycle trace armed by the exception strobe pins this to one instruction.
`sys_exc` (jvm.asm:550) starts by undoing the fetch increment:

```
ldjpc; ldi 1; sub; stjpc     // intended: jpc := jpc - 1
```

Trace of core 1, cycle by cycle (`sys_exc` occupies 0x00a7..0x00b0 — ten
microcode words, then `jmp invoke` to 0x010d, which matches the listing
exactly):

```
4733632 pc=0x02ff jpc=0x01eb A=0x12345678 B=0x12345678 sp=90  <- strobe; a/b ALREADY poison
4733637 pc=0x00aa jpc=0x01ec A=0x000001ec B=0x00005c30 sp=93  <- ldjpc pushed jpc
4733638 pc=0x00ab jpc=0x01ec A=0x12345678 B=0x000001ec sp=92  <- TOS is POISON, not 1
4733639 pc=0x00ac jpc=0x01ec A=0xedcbab74 B=0x12345678 sp=91  <- sub
4733640 pc=0x00ad jpc=0x0b74 A=0x12345678 B=0x12345678 sp=92  <- stjpc
```

The arithmetic is exact:

```
0x1ec - 0x12345678 = 0xedcbab74     (0xedcbab74 & 0xfff) = 0xb74
observed jpc                         = 0x0b74     MATCH
```

So the subtraction that should have been `jpc - 1` used the POISON as its
operand, and `stjpc` wrote the low 12 bits of the result. That is the whole
derailment — not a fill fault, not a branch-target fault.

**Why the operand was poison.** `a`/`b` are already `0x12345678` at the strobe,
BEFORE `sys_exc` runs. The AB fires during the `iaload` of `liveTick[id]`: the
operands have been popped, the load never delivers, and the top-of-stack
registers refill from stack RAM slots that were never written. `sp` also rises
90->93 across the entry, one push more than `sys_exc`'s two, so `sp` and `a`/`b`
are inconsistent with each other. `sys_exc` then does arithmetic on that.

**The defect is the design, not a stray value:** a hardware exception is taken
mid-bytecode, so the operand stack is NOT in a defined state, yet `sys_exc`'s
very first action computes the resume address through it. Any poison in `a`
becomes a wild `jpc`.

Fix directions, in order of preference:
1. Do not route the jpc correction through the operand stack. The faulting jpc
  is already known to the hardware — present it in a register the handler can
  read directly, or have `bcfetch` not apply the increment when an exception
  is strobed, removing the need to undo it.
2. Failing that, make the entry establish a known stack state before arithmetic.
3. Independently: `stjpc` silently accepts a value with bit 11 set. The RTL
  comment at BytecodeFetchStage:134 says the extra bit exists "to detect
  overflow", but NOTHING tests it — see the note above. Acting on it would
  have turned this into a trap instead of 600k cycles of corruption.

Note this is a SINGLE-CORE bug in an SMP-looking dress: nothing about it needs
two cores, which is why every GC and coherence hypothesis failed to explain it.

### ROOT CAUSE: A GC ON ONE CORE CANNOT SEE ANOTHER CORE'S STACK

```
STACKROOT minors 6 magic 0 LOST (other core's stack is NOT scanned)
```

Core 1 held a live object in a **local variable only** — so the reference
existed nowhere but core 1's own stack RAM — while core 0 ran 6 minor GCs.
The object was collected. Its magic read back **0**: freed and zeroed.

**This is a design hole, not a race.** Both root scanners have the same shape:

```java
i = Native.getSP();
for (j = Const.STACK_OFF; j <= i; ++j) pushYoung(Native.rdIntMem(j));   // THIS core
cnt = RtThreadImpl.getCnt();                                            // other THREADS
```

`Native.rdIntMem` reads **core-private** internal RAM, so it can only ever
reach the collecting core's stack. `RtThreadImpl` covers other *threads*,
whose stacks are saved into heap arrays — not other **cores**, which are
running and hold their roots in hardware. Nothing anywhere in the runtime
scans another core's stack: `getYoungRoots()` (generational) and
`getStackRoots()` (classic) are the only two stack scanners, and both are
core-local. **So this affects the classic collector too**, which is why
classic hangs at 3 and 4 cores.

It explains every observation, including the ones that refuted the earlier
hypotheses:

- **the lost reference** — `publish()` does `Young y = new Young(); ... ;
 holders[slot].ref = y;`. Between those two statements `y` exists only in
 core 1's stack. A minor GC on core 0 in that window collects it, and core 1
 then stores an already-dead reference into the holder. The observed freeze
 at `step[1]=3` — core 1 halted between the magic write and the store — is
 precisely that window.
- **why the card was marked and the holder was in a scanned range** — both
 true and both irrelevant: the object died *before* the store, so there was
 nothing for the card to protect.
- **why the stop-the-world holding made no difference** — halting core 1 is
 exactly the problem. It is frozen mid-`publish()` holding the only
 reference, and the collector cannot see it.
- **why the overlap is required** — the old test only collected in phase 2,
 when publishers held no live young objects.
- **the wild-pointer crashes** (`noim`, AB, NP) — the same mechanism applied
 to any other reference a core holds while another core collects.

The test's own hazard note had it exactly backwards: *"If core 1 leaves the
Young reference in a stack slot, it stays reachable as a root and survives
regardless of the card table."* On SMP the opposite is true — a stack slot on
another core is the one place a reference is **not** safe.

**FIXED, and verified 2026-08-12.** `scanOtherCoreRoots()` (GC.java) reads
every other core's SP, stack RAM and A/B top-of-stack registers over
`IO_ROOT_SEL`/`IO_ROOT_DATA`, and is wired into BOTH scanners — `getYoungRoots()`
(generational) and `getStackRoots()` (classic). STACKROOT now reports:

```
STACKROOT minors 6 magic 1515851775 OK (other core's stack IS scanned)
core1 view:  ptr 31301 space 1 type 0 raw 1515851775 field 1515851775
core0 after: ptr 31301 space 1 type 0 raw 1515851775 PTR-AGREE
```

Identical on seeds 70704150 and 424242, so this is not seed luck. The object
survives and both cores agree on every word.

A halt ACKNOWLEDGEMENT is still worth adding — `gcHalt` is fire-and-forget and
the halt check measured a 2-cycle stop latency, so the collector samples a
peer's SP and stack RAM while that peer may still be executing. A stale SP
silently truncates the scan. That is a correctness precondition for the root
snapshot, not a tidiness issue.

The probe is committed as part of `SmpGcTest` (`STACKROOT` line) and is
deterministic, layout-independent, and takes about a second — unlike the main
workload it does not depend on hitting a window by luck. Use it as the
regression test for any fix.

### HALT CHECK RESULT: the stop-the-world HOLDS. Hypothesis refuted.

Measured in simulation (zero perturbation — it only compares signals the
probe already samples: while any core asserts `gcHaltReg`, does another
core's `pc` advance?). 2 cores, 50M-90M cycles:

```
HALT CHECK: 128 stop-the-world windows observed, 1 of them had another core still executing.
HALTLEAK window 85842797..85876686 (33889 cy) asserted by core 0: core 1 advanced 2 cycles
```

**127 of 128 windows are perfectly clean.** The one exception leaked **2
cycles** — halt latency, a core taking a couple of cycles to stall after
`IO_GC_HALT` goes high — and by a core that did **not** hold the lock, so the
lock-owner exemption never even fired.

Three things make this a refutation rather than a lead:

1. **The fault precedes the leak by 28 million cycles.** First exception at
  cycle **57,232,985**; the only leak at **85,842,797**. Whatever breaks core
  1 had already broken it long before any halt leaked.
2. **A round with 102 minor GCs lost nothing**: `R0 clear=ON minors 102
  lost 0`. If a 2-cycle leak were sufficient, 102 collections would have
  shown it.
3. The leak is 2 cycles out of a 33,889-cycle window — 0.006% of one
  stop-the-world.

So the unifying "the collector runs while a mutator does" story is **wrong**,
and the halt is not the mechanism for either fault. Worth fixing the 2-cycle
latency on general principle (assert `gcHalt`, then wait for acknowledgement
before touching the heap), but it is not this bug.

**What is now eliminated**, each by direct measurement rather than argument:

| candidate | verdict |
|---|---|
| per-core card table / mark never recorded | **refuted** — `card 1` after core 1's store |
| holder outside a scanned range | **refuted** — inside `[allocPtr, tenureTop)` |
| stop-the-world leaks | **refuted** — 127/128 clean, leak postdates the fault |

That leaves the scan -> mark -> copy pipeline itself: `pushYoung`'s filtering,
the young-survivor marking, or `copyAndSweepYoung`. Those are now the only
places left for the lost reference, and they are ordinary single-threaded code
that can be read and unit-tested rather than raced against.

**And the simulator now reproduces the FREEZE at 2 cores** — exceptions start
on core 1 at cycle 57.2M, immediately after the publishers are released, same
AB-then-NP signature as the 4-core run, plus two null-method-pointer fills.
That is a much cheaper vehicle than the 4-core route and it has full
visibility. Use **hardware for the lost reference** (which the sim's 128 KB
heap does not reproduce) and **the 2-core sim for the freeze**.

### Superseded: the mark IS set, and the holder IS scanned

The single-binary A/B is built (`GC.cardClearEnabled`, non-final so it cannot
be folded; the test flips it per round and reports `R<n> clear=ON/OFF minors N
lost N`). It has not yet produced a two-arm result, because the freeze kills
the run before both arms execute. But the probe that runs alongside it
answered two questions outright:

```
probe: h0d 1902227 card 1 copyPtr 538184 allocPtr 1901895 tenureTop 1902281
R0 clear=ON  minors 3 lost 0
```

- **`card 1`** — the card covering holder[0]'s data word IS MARKED after core
 1's cross-generation store. **The hardware write barrier records another
 core's write correctly.** That kills the "per-core card table" framing that
 item 1 opened with; the cluster-level table (767178b) does its job.
- **`h0d 1902227` lies inside `[allocPtr 1901895, tenureTop 1902281)`**, one
 of the two ranges `scanCards()` visits. So the holder is reachable by the
 scan.

Neither a missing mark nor an unscanned region. That leaves the **stop-the-
world halt** as the remaining suspect: if core 1 advances at all between
`IO_GC_HALT` going high and going low, the collector moved objects and
rewrote handles underneath a running core — which explains a lost reference
and a wild-pointer crash (`noim`, `bytecode 202 not implemented`) equally
well, and would unify the two faults into one.

**Measure the halt in SIMULATION, not on hardware.** Every attempt to
instrument core 1's hot loop made the failure arrive *sooner* — adding a
`GC.mutatorTick` bump to the publisher took it from "fails in round 1" to
"dies before round 0 finishes". That is itself evidence (more cross-core
traffic, more race) but it makes hardware instrumentation self-defeating for
this question. The simulator can watch `cores(i).sys.io.halted` and
`gcHaltReg` directly with **zero perturbation** — the check is simply: while
any core asserts `gcHaltReg`, does another core's `pc` advance? The probe
already samples all of those signals.

The runtime-side half is committed and inert: `GC.mutatorTick` /
`GC.haltDeltaMax`, with `minorGc()` snapshotting the counter inside the halt
window and recording the largest advance. Nothing bumps `mutatorTick` by
default, so it costs one static read per minor GC and shifts nothing. Wire it
up from a mutator only if hardware measurement becomes worthwhile again.

Also useful when doing it: `scanCards()` visits only `[tenureBase, copyPtr)`
and `[allocPtr, tenureTop)`, treating the middle as free. Confirm the holders
actually lie inside a scanned range before concluding anything about marks —
an object in the gap is never reached however dirty its card is. The probe for
this is written (`cardBit()` plus a `copyPtr`/`allocPtr`/`tenureTop` dump at
`phase = 2`); it just needs a build that survives to round 0's verification.

The generational run's behaviour has also **changed for the better**: it used
to hang emitting nothing, and now reports before dying. That is the three
lock-park fixes (`4df8edd`, `d8d93f8`) doing their job — `noim()` no longer
freezes the cluster under the global lock, so the machine can tell you what
went wrong. The fixes did not make the underlying fault go away, and were
never going to; they made it observable.

**And the simulation failure is REAL.** Re-running the 4-core `SmpGcTest`
probe under `--x-initial 0` reproduces it unchanged: same first exception at
cycle **56,176,845**, same core 1, same `AB` then `NP` storm, same two null
fills. So the retraction below applies **only** to the `NCoreHelloWorld`
experiment. Everything measured on the `SmpGcTest` route — the exception
storm, the wild execution, `noim()`, the wrecked-stack stores — stands, and is
now corroborated on hardware.

> **RETRACTION (2026-08-10, later the same day).** The `NCoreHelloWorld`
> evidence in the next few paragraphs is a **simulation artifact** and does
> not support the conclusion drawn from it. Read the *Uninitialised
> registers* section below before believing any of it. The 4-core
> `SmpGcTest` failure that started this item is a separate question and is
> being re-tested under a corrected simulator setup. Left in place rather
> than deleted because the correction is the useful part.

**IT IS NOT A GC BUG AT ALL, AND IT REPRODUCES IN 3M CYCLES.** Retargeting
the probe at `java/apps/Small/NCoreHelloWorld.jop` — a program that does
nothing but start N cores and toggle a watchdog — reproduces the null fill,
with the boot line reading `GC: classic (SMP - per-core card tables,
generational disabled)`. **The generational collector is switched off in that
run.** The bracket is sharp:

| cores | null fills | GC |
|---|---|---|
| 2 | 0 | classic (disabled) |
| 3 | 0 | classic (disabled) |
| **4** | **2, both on core 3** | classic (disabled) |

```
sbt "Test/runMain jop.system.JopGcHaltDeadlockSim 4 0 70704150 java/apps/Small/NCoreHelloWorld.jop 3000000"
```

Under a minute, no GC, no allocation, no card table, deterministic with the
pinned seed — against 45 minutes for the `SmpGcTest` route. **Use this.**

The 2- and 3-core runs are the control this investigation needed and never
had: a `start=0` fill is *not* normal wake-up behaviour, it appears at
exactly four cores. So "generational GC deadlocks above 2 cores" has been the
wrong title for this item throughout — the generational collector was a
passenger, and what it did was allocate hard enough to make a 4-core SMP
wake-up fault show up as heap-shaped symptoms 56M cycles downstream.

### Uninitialised registers make this simulator unreliable — read this first

**The 4-core `NCoreHelloWorld` failure is caused by Verilator randomising
registers that have no reset. It is not a hardware bug, and the conclusions
drawn from it above are withdrawn.**

Three independent results, each cheap to re-run:

| experiment | result |
|---|---|
| 4 cores, seed 70704150 | **DEAD** — no UART at all, core 0 never boots |
| 4 cores, seeds 1 / 2 / 3 | **BOOTS** |
| 4 cores, seed 70704150, `--x-initial 0` | **BOOTS** |

`--x-initial 0` starts every register at zero, which is what an FPGA does at
power-up. With it, the failure disappears. Without it, whether the machine
boots at all depends on the seed.

Worse, it depends on **observation**: adding `simPublic` to the BMB response
signals — which cannot change logic, only which registers survive pruning and
therefore how the seeded randomisation lands — turned a 4-core run that
booted and failed later at 208k cycles into one that is dead from cycle 78.
Two runs of the same RTL and the same seed, differing only in what was being
watched.

`grep -rE "= *Reg(Next)? *\(" spinalhdl/src/main/scala/jop/ | grep -v init(`
counts **~405** registers with no `init`. That is the raw material.

**Consequences, in order of importance:**

1. **Add `--x-initial 0` to the sims** (`SimConfig.addSimulatorFlag`). It
  matches FPGA power-up and removes a large class of false failures. Every
  sim in this project is exposed to this, not just this probe.
2. **A sim failure that moves when you add a probe is X-state, not a bug.**
  That test costs one run and would have saved most of today.
3. Pin the seed (done) — but pinning alone is not enough, because the netlist
  changes under you as instrumentation is added.
4. Registers that genuinely need a defined reset should get one. Randomised
  state that can prevent boot is worth fixing on its own merits, even though
  the FPGA masks it.

What this does **not** settle: the original 4-core `SmpGcTest` failure
reproduced under four *different* random seeds, which is far more robust than
anything here, and the EP4CGX150 4-core hang is on real hardware where X-state
does not exist. Both may still be real. Everything downstream of the
null-fill reading — the wake-up narrative, "not a GC bug" — needs redoing
under `--x-initial 0` before it can be trusted.

**THE NULL FILL HAPPENS AT CORE WAKE-UP.** Triggering a dump on the *first*
`start=0` fill of the whole run moved the origin earlier again, and onto a
different core:

```
56,063,443  core 0 writes phase=1                (publishers released)
56,063,499  core 3 FILL start=0 len=0            <-- first null fill in the run
56,063,544  core 3 EXC NP  jpc=0x0003 sp=219
56,063,582  core 3 FILL start=0 len=0
56,063,651  core 3 EXC AB  jpc=0x0004 sp=118
56,063,745  core 1 writes cpuCnt=4 \  cores 1 and 2 start main() normally
56,063,746  core 2 writes cpuCnt=4 /  CORE 3 NEVER DOES, EVER
56,063,817+ core 3 NP storm, forever
```

Core 3 fails **in its first few bytecodes**, at `jpc=0x0003`, and never
reaches `main()`'s first two putstatics — there is no `src=3` store anywhere
in the run. Cores 1 and 2, released by the same signal on the same cycle,
start correctly.

So the target is much narrower than "somewhere in a 56M-cycle workload": it
is the **SMP wake-up path**, where three cores leave `Startup`'s boot-wait
loop simultaneously, all miss in their bytecode caches at once, and all hit
the BMB arbiter together. One of them reads a method pointer of 0.

**AND THE SIMULATION IS NOT REPRODUCIBLE RUN TO RUN — this invalidates a
documented inference.** `doSim` without an explicit seed picks a new one
every invocation; four consecutive runs of the *same binary* used seeds
748489979, 617838352, 370588204 and 70704150. The failure is robust (every
run wedges) but its details move: core 1 dying mid-workload at 56.18M in one
run, core 3 dying at wake-up at 56.06M in another.

That means the note above — *"adding a counter changed the code size ... and
the freeze stopped happening: five consecutive clean runs. Verilator is
deterministic, so that is not luck, it is a different binary"* — was drawn
with an uncontrolled variable. Verilator is deterministic **for a fixed
seed**, and the seed was not fixed. The layout sensitivity may well be real,
but it has not been shown, and five clean runs is a much weaker result than
it looked. The probe now pins the seed (`doSim(seed = simSeed)`, default
70704150, overridable as argv[3]); re-establish that claim with the seed held
before relying on it.

A concrete place to look while doing so: `BytecodeFetchStage.scala:157-173`
carries a hand-built read/write collision bypass whose own comment says
*"During BC fill, the last write can coincide with the bytecode fetch read at
the dispatch moment (**timing depends on memory latency**)"*. `doBypass`
compares a one-cycle-registered write address against a registered read
address, and four-core BMB arbitration changes exactly the variable that
comment names. That is a lead, **not a finding** — the null pointer is in the
value fed to the fill, so the method-pointer *load* is the first suspect and
the fetch bypass the second.

**Probe hardening after a self-inflicted false alarm**: every static address
is now read from `<app>.jop.link.txt` at startup instead of being hardcoded.
Fixing `exit()` grew the runtime and shifted `SmpGcTest.phase` 287 -> 292,
so the hardcoded probe reported `phase=0` with null arrays and looked exactly
like catastrophic heap corruption. It was reading the wrong words. The file
already carried a comment warning that these move on relink; that was not
enough, so it is now mechanical.

Historical note: `exit()` must not hold a lock while parking. `Startup` has
three other park loops (lines 127, 286, 416) that do NOT take a monitor —
only this one does, and the `synchronized (stack)` serves no purpose since
nothing is ever released. Also worth auditing: `JVM.except()` ends an
uncaught exception with `System.exit(1)`, so on SMP any uncaught exception on
any core wedges the cluster by this same path rather than stopping one core.

**Do not instrument `GC.java` to chase this.** Adding a per-iteration counter
to the list walks changed the runtime's code size, which moved the heap
start, which changed the allocation pattern — `minors after tenuring` went
196 to 198 — and the freeze stopped happening: five consecutive clean runs.
The same sensitivity broke `JopSmallGcBramSim` (R80 vs R81) on the same day.
Observing from the simulator costs nothing and cannot do this, so prefer it
regardless.

**But the "Verilator is deterministic, so that is not luck" half of this
argument does not hold as written** — see the seed note below. `doSim` was
picking a fresh seed every run, so those five clean runs were not five
samples of one deterministic system. The layout sensitivity is plausible and
the R80/R81 case is independent of seeding, but the five-run result needs
re-running with the seed pinned before it means what it says.

Because of that, the failing image is **kept aside** at `spinalhdl/repro/`
(`SmpGcTest.jop` + `.jop.link.txt`, gitignored like every other build
output). It is the build with the `cpuCnt0 <= 2` guard at `GC.java:574`
lifted to `<= 99`, and it prints `minors after tenuring 198`. A run that
prints anything else is a different binary and its result is void. Point the
probe at that copy rather than rebuilding when the runtime has moved on.

**Whether this freeze predates the fix is UNKNOWN**: the broken build died at
52M, before this point, so it was never observable. Plausibly the next bug in
line rather than one introduced here — but that is an assumption.

**Two instrumentation faults, both fixed, both worth remembering.** The first
probe read `cluster.io.halted`, which `JopCluster.scala:617` wires from
`debugHalted` — the DEBUG halt, always false here — and so printed
"halted=0/4" through a total freeze, which is what produced the wrong "stalled
owner" reading. The pipeline actually stalls on an OR of five terms
(`JopCore.scala:294`) and the probe now samples all five. Second, the stall
detector required *every* core to be stable at once; core 3 creeps through a
software-`imul` loop forever, so a three-of-four freeze never tripped it. It
is now per core.

**Regression sweep of the fix** (it touches every collector path, so the
already-validated configurations matter more than the 4-core one):

| check | result |
|---|---|
| 1 core, generational — `JopGenGcBramSim` | **PASS** |
| 1 core, classic — `JopSmallGcBramSim` | **PASS**, 1 GC cycle |
| 2 cores, generational — 4-core probe run at 2 | **healthy**: no freeze, no core halted, no lock held |
| 4 cores | crash fixed; freeze remains |

The 2-core run does not finish `SmpGcTest`'s eight publish rounds inside 75M
cycles, but nothing is stuck — both cores run, `syncHalt=false` on both. That
is the same simulation-speed limit already recorded against
`JopIhluGcBramSim`, not a hang.

**Do not read this as "generational SMP works now".** One real defect is
closed with a reproduction and a fix; the guard stays at `cpuCnt <= 2` until
a 4-core run completes and reports `errors 0`.

**Two sims in this area could not fail, and a third could fail for the wrong
reason.** All three were found by running them properly during this work:

- `JopSmallGcBramSim` asserted on `"GC test start"` — printed only by
 `Small/src/test/GcStressTest.java` — while loading `HelloWorld.jop`, whose
 source prints `"Hello World!"`. It passed only because a stale
 `HelloWorld.jop`, built from some other source at some point and not
 reproducible from the tree, happened to be on disk; `make clean` in that
 directory destroyed it and the mismatch surfaced. Now loads
 `GcStressTest.jop`.
- The same sim then stopped on the literal `"R80 f="`, and the collector
 fires at exactly R80, so the pass criterion was ONE ROUND WIDE. HEAD
 reaches `R79 f=1180` and cannot satisfy round 80; a slightly larger runtime
 reaches `R79 f=1308`, satisfies round 80 with 28 words to spare, and
 collects at R81 — outside the window. Any GC.java edit that moves the heap
 start by a word could flip this either way regardless of whether the
 collector works. Window widened to R95.
- `make -C java/apps/Small clean` leaves the directory UNBUILDABLE:
 `MissingClassError: java.lang.Throwable`. The Makefile compiles only its
 own entry point with the runtime on `-sourcepath`, so `build/classes`
 normally accumulates runtime classes across builds of the several apps that
 share that directory. Stage them with
 `cp -rn ../../runtime/classes/* build/classes/` before `make jop`.

Together with item 2 (`JopIhluGcBramSim` loading a single-core app) that is
four instances of one shape in this area: **the test cannot fail for the
reason it exists**.

<a id="item-2"></a>

### Item 2 — ~~`JopIhluGcBramSim` cannot fail — CLOSED 2026-08-16~~

~~**`JopIhluGcBramSim` cannot fail**~~ — **CLOSED 2026-08-16.** It loaded
`java/apps/Small/HelloWorld.jop`, a single-core app, so core 1 parked in the
boot-wait loop and IHLU was never exercised — verified at the time by running
to 49M cycles with core 1 never moving.

It now loads `SmpGcTest.jop`, the multi-core allocating workload item 1
needed, and has three ways to fail rather than none:

- the payload not reaching `SmpGcTest done`;
- `SMPGC FAIL`, i.e. cross-core references lost;
- **`verified == 0`** — the specific anti-vacuous guard. `verified N` counts
 holders core 0 checked *after another core stored into them*, so a non-zero
 N is proof the second core executed. This is the check the old payload
 could never have satisfied.

**Result:** `PASS: 2 cores, verified=192, 10 minor GCs — IHLU and shared card
table exercised`, 20.2M cycles. Both cores run, `SMPGC OK`, minors 10 /
verified 192 / errors 0. Generational is active at 2 cores now that the guard
is `<= 12`, so this exercises the shared card table as well as IHLU — the
entry's original "INCONCLUSIVE while the SMP guard is on" caveat no longer
applies.

**Demonstrated failing, not assumed.** Pointed back at `HelloWorld.jop` with a
short cycle cap it exits non-zero with `FAIL: SmpGcTest did not run to
completion`. A test that has never failed has not been shown to be able to —
the same discipline item 35 sets, and it is worth the two minutes.

Also fixed while here: the verdict used `findFirstMatchIn` for the minor-GC
count and so picked up `STACKROOT minors 6`, a mid-run probe, reporting 6
where the run's own summary says 10. A verdict that under-reports what it
exercised is a small lie in the one place that has to be trustworthy.

<a id="item-34"></a>

### Item 34 — ~~4-CORE STATUS after the fetch-stall fixes — the SDRAM row is~~

~~**4-CORE STATUS after the fetch-stall fixes**~~ — **the SDRAM row is
SOLVED (2026-08-16); one BRAM-sim row remains open.** Kept because the table
below is the clean statement of which combinations were tried, and because
two of its entries were retracted in ways worth not repeating.

**The `SmpGcTest / Ihlu / SDRAM hardware / STALL` row was the
`AlteraSdramAdapter` bug** (`ef36d99`) -- Avalon read data dropped when the
consumer stalled, and write responses overtaking outstanding reads and
answering them with 0. Nothing to do with Ihlu, the lock, or 4 cores as such;
the core count only decided how often `rsp.ready` dropped. Full account in
item 1. Generational SMP now runs at up to 12 cores.

**Still open from this entry:** `JopSmpNCoreHelloWorldSim` with **CmpSync** at
4 cores, where C1 never toggles. That is a BRAM sim, so the SDRAM fix does not
touch it, and the Ihlu equivalent passes. Small, concrete and unexplained.

The original 2026-08-13 text follows.

| test | lock | memory | 4 cores |
|---|---|---|---|
| SmpGcTest | Ihlu | BRAM sim | **PASS** — 8 rounds, 192 verified, 0 errors |
| `JopIhluNCoreHelloWorldSim` | Ihlu | BRAM sim | **PASS** — 89 lock ops, C0-C3 all toggle |
| `JopSmpNCoreHelloWorldSim` | **CmpSync** | BRAM sim | **FAIL** — C1 never toggles |
| SmpGcTest | Ihlu | **SDRAM hardware** | **STALL** — core 2 starves |

**(a) RETRACTED — this is NOT a global-lock failure.** `JopSmpNCoreHelloWorldSim 4`
does report `Per-core WD toggles: C0=1 C1=0 C2=1 C3=1` — core 1 never starts —
while `JopIhluNCoreHelloWorldSim 4` passes. But BOTH use Ihlu:
`JopCoreConfig.useCmpSync` defaults to false and NEITHER harness overrides it.
The claim that the global lock was implicated came from reading the
`JopIhluSim` header comment instead of checking the config, and is withdrawn.

The harnesses differ in CONFIGURATION, not locking. `JopIhluTestHarness`
builds an explicit `harnessCfg` with `hasCardTable = true`;
`JopSmpTestHarness` has none, so `IO_CARD_SHIFT` reads 0 and GC.init falls
back to the classic collector — the UART says exactly that:
`GC: classic (no card table - generational disabled)`. So the comparison was
generational-Ihlu against classic-Ihlu, and the cause of the core-1 no-start
is NOT yet isolated. Give `JopSmpTestHarness` the same explicit config before
drawing anything from it.

Reproducer, still valid as a FAILURE:
`sbt "Test/runMain jop.system.JopSmpNCoreHelloWorldSim 4"`.

**(b) NARROWED 2026-08-13 — the SDRAM PATH is implicated, silicon is not.**
A BRAM build on the SAME board isolates it:

| config | 4 cores + SmpGcTest |
|---|---|
| BRAM sim | PASS |
| SDRAM sim (`JopSmpSdramNCoreHelloWorldSim 4 250000000 <SmpGcTest.jop>`) | PASS |
| **BRAM hardware** (`ep4cgx150BramSmp 4 60`) | **PASS** — SMPGC OK, 192 verified, 0 errors |
| **SDRAM hardware, 2 CORES** (`ep4cgx150Smp 2 60`) | **PASS** — SMPGC OK, 192 verified, 0 errors |
| SDRAM hardware, 4 cores (`ep4cgx150Smp 4 60`) | **STALL** — core 2 starves |

**So the stall needs BOTH the SDRAM path AND 4 cores — it is
CONTENTION-DEPENDENT.** 2 cores on the same memory, same clock, same app and
same guard passes cleanly (+2.468 ns slack, so timing is not a factor), and 4
cores on BRAM passes too. Only four masters against the SDRAM controller
fails.

Note the BMB arbiter is already ROUND-ROBIN (`lowerFirstPriority = false`,
JopCluster:385), so this is not naive fixed-priority starvation — do not
start there. Look instead at what can hold the bus across arbitration
decisions with four masters: burst behaviour in `BmbSdramCtrl32` (the 32->16
bridge), refresh colliding with a loaded queue, or a request being dropped
rather than deferred.

The BRAM board build passes at only **+0.050 ns** setup slack, which makes the
result strong rather than weak: marginal timing produces failures, not
successes. Quartus synthesis and the real device are common to both hardware
rows, so what differs is the SDRAM controller, refresh, and the physical
device under 4-core contention. Next look there, NOT at the cores.

**(b2) MEASURED ON HARDWARE 2026-08-13 — NOT bus starvation.** Per-core
counters at the arbiter inputs (req/gnt/busy, read back through the root port,
`tgt >= 8` selects the counter bank; dumped by SmpGcTest at STALL):

```
STALL live=40294532,191,307987  pub[1]=1 pub[2]=0 pub[3]=0
 bus[0] req 1577182941  gnt 440891195  busy 1136293713
 bus[1] req         -1  gnt 445742537  busy         -1   (saturated)
 bus[2] req      76125  gnt      3881  busy      72244
 bus[3] req   35409712  gnt   3085714  busy   32323998
```

`req` counts CYCLES with a request outstanding, so a core blocked on the bus
climbs without bound — core 1 does exactly that (saturated) while running
fine. The stalled core 2 shows **76k** request-cycles against core 3's 35M and
core 0's 1.5G: four to five orders of magnitude LESS traffic, not more
waiting. `req = busy + gnt` holds exactly (76125 = 72244 + 3881), so the
counters are self-consistent.

**So the core stopped ASKING. Arbiter and SDRAM-controller starvation are
ruled out, and the bus is a red herring** — do not start there. The SDRAM
correlation is real but indirect: something about that configuration wedges a
core in a path that issues no memory traffic. Next suspects are the Ihlu lock
(a core waiting on a monitor issues nothing) and the exception path.

Note this run's stall differs in detail from the earlier bit-identical pair —
pub[3] is now 0 too and core 3 lags — because the RTL changed when the
counters were added. It is the same class of failure, not the same instance.

**(b10) SOLVED — two bugs in `AlteraSdramAdapter`, and 4-core generational GC
now passes on hardware.** The adapter bridges the Altera SDRAM controller's
Avalon-MM interface to `SdramCtrlBus`. Two defects, both on the response path:

1. **Avalon read data was dropped when the consumer stalled.**
  `readdatavalid` is a PULSE — the data is on `avs_readdata` for one cycle and
  cannot be held (`avs_waitrequest` backpressures commands only). It was wired
  straight to `io.bus.rsp`, a Stream whose consumer does deassert `ready`:
  `BmbSdramCtrl32` drops `rsp.ready` for a high half whenever its assembly
  pipe is occupied. When they coincided the data was presented, not accepted,
  and lost.
2. **Write responses could overtake outstanding reads.** A write response is
  manufactured locally and available immediately; a read response waits for
  SDRAM. The adapter emitted whichever was ready. Since the consumer matches
  responses to commands BY ORDER, a write issued after a read could answer
  that read — with `data := 0`, which the write branch hardcodes.

That is where the zero came from. Both are fixed: read data is captured into a
FIFO on the cycle Avalon offers it, commands are refused unless there is room
to hold every in-flight result, and an `orderFifo` releases responses strictly
in command order.

**Why it took so long, worth internalising:** because a substitute response
still came back, commands and responses stayed BALANCED, so every "did the
response stream slip a beat?" check said no (b8's `bmbOut`). Only the data was
wrong, and only ever to zero. And it needs sustained back-to-back traffic for
`rsp.ready` to drop at all, which is why 2 cores never showed it.

**This file had NO simulation coverage on any board that uses it** — the Altera
controller is a BlackBox Verilator cannot build, so every sim substitutes
`SdramCtrlNoCke`, a proper Stream that honours `ready`. That is why matching
the harness to the board (0da41f1) still did not reproduce it, and it is the
real lesson here: the component that failed was the one no test could reach.

Results on EP4CGX150 SDRAM:
- SmpGcTest, 4 cores, GENERATIONAL: `SMPGC OK`, `minors 10 verified 192
 errors 0`, 3/3 runs. This case has never passed before.
- DoAll 66/66 on the 4-core bitstream and on the single-core one.
- `rawLenBad 0 aLenBad 0 exc 0` on every core, where it was `exc 1..4` before.

**THE GUARD IS REMOVED.** It went 1 -> 2 -> 4 -> 8 -> 12 while the failure was
unexplained, and by the end it had stopped meaning anything: no board in the
tree can build past 12 (16 cores needs 182,501 of the EP4CGX150's 149,760 LE),
so it was unreachable, and a number implies "13+ is known bad" when the truth
is "untested". `genActive` is now just `USE_GENERATIONAL && cardShift0 != 0`.

**The real ceiling moved to where it can be checked.** `JopCluster` now
requires `cpuCnt <= 16`, because the cross-core root port's target field is
4 bits (`Sys.rootSel(11 downto 8)`). Past 16 that field ALIASES — a collector
asking for core 16's stack reads core 0's, silently, handing the GC another
core's roots and collecting live objects. That is the failure class the guard
was nervous about, stated precisely and enforced at elaboration instead of
guessed at runtime. Verified: 16 cores elaborates, 17 refuses with the reason.
Raising it means widening `rootSel` and the root mux. 8 and 16
cores are untested, as are the DDR3 boards, so it stays a number rather than a
removal. Everything below is the investigation that led here, kept because
several entries are retractions worth not repeating.

**(b9) A PLAIN `rdMem` GETS IT WRONG TOO — so the array path is exonerated.**
Each publisher now reads the SAME length word two ways every iteration:
`Native.rdMem(handle+1)` (the plain memory-read state machine) and
`liveTick.length` (the handle/array state machine). Both are wrong sometimes:

```
 core[1] ... exc 4 type 3 bmbOut 0 rawLenBad 2 aLenBad 1
 core[2] ... exc 0 type 0 bmbOut 1 rawLenBad 0 aLenBad 1
 core[3] ... exc 0 type 0 bmbOut 0 rawLenBad 0 aLenBad 1
```

`rawLenBad` counts a plain `rdMem` of that word returning something other than
4, and it GROWS (1 -> 2 across rounds on core 1). The bounds check is not
special; it is simply where a bad read gets noticed, because it is the only
read whose result is checked. So the fault is BELOW `BmbMemoryController` —
in the arbiter, `BmbSdramCtrl32`, or the SDRAM controller.

Note the rate: a handful of events against millions of iterations. Any theory
has to explain something that rare, which argues for a narrow timing window
rather than a structural mistake in the state machines.

Also worth keeping in mind when reading `abLen 0`: the heap is mostly zeros,
so a read that goes to the WRONG ADDRESS returns 0 just as readily as one that
returns wrong data. "Always exactly 0" does not by itself distinguish the two.

**(b8) THE FAULT IS A READ THAT RETURNS 0 FOR AN ARRAY LENGTH OF 4.** The
bounds-check operands are now latched in hardware at the first EXC_AB per core
and reported every round:

```
arrays: liveTick 539528 pubStep 539520 ... holders 539488 len 4
 core[1] abIdx 1 abLen 0 abHdl 539528 nowLen 4 nowPtr 2096867 exc 4 type 3 bmbOut 1
 core[2] abIdx 2 abLen 0 abHdl 539520 nowLen 4 nowPtr 2096863 exc 1 type 3 bmbOut 0
 core[3] abIdx 3 abLen 0 abHdl 539520 nowLen 4 nowPtr 2096863 exc 2 type 3 bmbOut 1
```

Read it line by line, because each column closes off a hypothesis:

- `abIdx` is always the core's OWN id, so the index is valid. The faulting
 statement is `liveTick[id] = liveTick[id] + 1`, the first statement of the
 publisher loop, executed millions of times and faulting a handful.
- `abHdl` resolves to `liveTick` or `pubStep` — real arrays, not a stray
 handle. So the handle the pipeline supplied was right.
- `nowLen 4` — the length word read back from a working core is correct. Memory
 was never wrong; **the read was**.
- `bmbOut` (BMB commands issued minus responses received, per core) sits at 0
 or 1 and never grows, so the response stream has NOT slipped a beat. A
 persistent off-by-one is ruled out.
- `abLen` is always exactly **0**, never an arbitrary value. That rules out
 plain mis-delivery of another master's data, which would land arbitrary
 bits. Zero is what a WRITE response carries (no data), what a reset register
 holds, and what `rsp.data ## lowHalfData` produces when the low half was
 never captured — 4 is `0x0000_0004`, so losing the LOW half alone gives
 exactly 0.

That points at `BmbSdramCtrl32`'s 32<-16 reassembly rather than at routing:
`lowHalfData` is a SINGLE register shared by every in-flight transaction, and
`pipeData := rsp.data ## lowHalfData`. Reading the command side did not find a
sequence that loses it — command halves are issued as an atomic pair, the fill
path tags its responses `isFill` and is excluded, and bursts hold
`io.bmb.cmd.ready` low — so the next step is a waveform, not more reading.
Reproduce in `JopSmpSdramNCoreHelloWorldSim 4 <cycles> SmpGcTest.jop`, which
already tracks the board output for output, and stop the sim on `abFire`.

**(b7) THE WEDGE IS AN UNCAUGHT ARRAY-BOUNDS EXCEPTION KILLING A PUBLISHER.**
The hardware exception latch (see b6) reported, at a 4-core stall:

```
bus[0] ... pc 727 jpc 1171 exc 0 excAt pc    0 jpc   0 type 0
bus[1] ... pc 951 jpc 1305 exc 1 excAt pc 1008 jpc 494 type 3   <- wedged
bus[2] ... pc 951 jpc 1817 exc 1 excAt pc 1008 jpc 494 type 3
bus[3] ... pc 952 jpc 1818 exc 1 excAt pc 1008 jpc 501 type 3
```

Type 3 is `Const.EXC_AB`, array bounds. Microcode pc 1008 is the hardware
bounds check (`BmbMemoryController` `HANDLE_BOUND_WAIT`, which compares
`handleIndex` against the array length it reads back over BMB). Core 0, which
stays healthy, takes none.

`JVMHelp.handleException()` turns EXC_AB into a throw of the preallocated
`ABExc`. Nothing in `publisher()` catches it, so it unwinds out of `main()`
and the core PARKS. **That is the entire wedge.** It explains every earlier
null result at once: the core stops issuing bus requests because it is dead,
not starved (b2); the lock-manager halt counter cannot discriminate because
being dead and being halted look identical from there (b3); and if the throw
lands inside the allocator's `synchronized (mutex)` the global lock is never
released, which is precisely the "core holds the lock and never releases it"
that item 1 has described since 2026-08-09.

**PROVEN BY MAKING IT SURVIVABLE.** `publisher()`'s loop body is now wrapped
in `try { ... } catch (Throwable)` which counts the fault and retries. With
that one change and NOTHING else, the 4-core SDRAM run completes all 8 rounds
and reaches `JVM exit!` instead of wedging. Every index in that loop is a
constant or a core id, so a retry of the identical access succeeding means
**the bounds check itself was wrong** — a spurious fault, not a program bug.

Next: the length it compares against arrives as `io.bmb.rsp.fragment.data`.
Under 4 masters that response has to be routed back by `source` through
`BmbArbiter` and `BmbSdramCtrl32`'s 32<-16 reassembly (`pipeSource` is a
1-deep register). A response delivered to the wrong core would give a valid
index a wrong length — and would equally explain (b5)'s cyclic handle list,
since the collector builds those lists out of raw `rdMem` results. Check the
`source`/`context` path end to end before anything else.

Corroborating, from the same run: core 0 read `holders[13].ref` as
`-1465206102` through a getfield while the identical word read raw out of
memory was `0` (`rawRef=0`). A cached read returning a value that is in
neither the old nor the new state of that word is a bad read, not staleness.

**(b6) The wedge is NOT generational.** Running the same 4-core bitstream with
the guard back at `cpuCnt <= 2` — so `GC.init` selects the CLASSIC collector
and reports `minors 0`, no minor GC anywhere — stalls too:
`STALL round 1 ... pub[3]=1 live=27541351,33607907,1560`. Item 1 has framed
this as a generational-GC bug throughout; it is not. It is under the GC, and
the generational guard neither causes nor prevents it. (An earlier 240 s run
that reached R1 cleanly was simply too short — do not read a passing prefix as
a pass.)

**(b5) A HANDLE LIST GOES CYCLIC — the first hard corruption caught in the act.**
With the pc/jpc/exc counter bank in, one 4-core SDRAM hardware run printed:

```
*** GC LIST OVERRUN walk=1 iters=65537 handles=65536 ref=533840 next=533848
```

`walk=1` is `WALK_YOUNG_SWEEP` — `copyAndSweepYoung` walking `youngList`. The
list cannot legitimately be that long: `handle_cnt` is 65536 (`MAX_HANDLES`),
and a minor GC is forced at `MAX_YOUNG_OBJECTS` (a few thousand), so 65537
steps means the chain closes on itself. **Every push onto these lists is
serialised by `mutex`, so a loop can only mean one handle entered the list
twice.** This is the first evidence that the >2-core failure is heap-structure
corruption and not (only) a lost lock or a starved bus — and it is exactly the
"infinite handle-list walk" that item 1 has been guessing at since 2026-08-09,
now printed instead of hung.

`gcListOverrun` was extended to name the mechanism rather than just report the
overrun: after >`handle_cnt` steps the walk is necessarily standing INSIDE the
loop, so walking on from `ref` until it returns gives the loop length exactly.
Length 1 or 2 => the same handle popped from `freeList` twice; a long loop =>
a list head restored over a newer one. It also reports whether `ref` is STILL
on `freeList` (`onFree`), which separates a third case: reclaimed without
being unlinked.

**(b4) The wedge is DETERMINISTIC again — 6/6 runs, same point.** With that
build every run dies immediately after

```
scan calls 8 words 738 cands 24 young 1
 lastYoung 487712 probeHandle 487712 MATCH spMin 64 spMax 135
```

and before round 0's `probe: h0d` line — i.e. inside the publisher wait loop
or a minor GC triggered from it. Core 0 itself is wedged, so **no software
probe can fire**: the STALL dump needs core 0 to reach 2M spins and it never
does. That kills the "read the counters from Java" approach for this
manifestation and is why (b5) came from a guard inside the collector instead.

Note the determinism moved AGAIN with the code change (b3 saw it vary), which
is the standing layout sensitivity, not a new fact. What is new: the 4-core
SDRAM Verilator harness now **tracks the board exactly** — same output text,
same `nurseryBase 1902429`, cores released at the same point — so
`JopSmpSdramNCoreHelloWorldSim 4 <cycles> java/apps/SmpGcTest/SmpGcTest.jop`
is a working bridge with full pc/jpc/halted visibility, at ~20k cycles/s.

**(b3) The lock-manager halt counter does NOT discriminate — null result.**
Slot 3 counts cycles with `Sys.io.halted` (syncIn.halted: Ihlu/CmpSync plus
gcHalt). Measured:

```
STALL live=102731,69,40421628
 bus[0] req 1589725286 gnt 442929483 busy 1146797891 halt  16411259
 bus[1] req   59645450 gnt   3876185 busy   55769265 halt 250058075
 bus[2] req      45727 gnt      2391 busy      43336 halt 250050098
 bus[3] req         -1 gnt 444574624 busy         -1 halt 250060879
```

Cores 1, 2 and 3 are all within 0.004% of each other (~250.05M) while core 3
RUNS FINE (40.4M heartbeats) and core 2 is wedged (69). A signal identical on
a healthy and a wedged core cannot explain the difference, so "blocked in the
lock manager" is NOT the answer and this counter should not be re-run
expecting one. `Sys.io.halted` looks to be asserted for nearly the whole run
on every non-boot core, so it is dominated by something common.

ALSO: which core stalls now VARIES between runs (live=102731,69,40421628 here
against 411990,80,40236098 before). Adding the counters shifted the timing
enough to move it, so the earlier bit-identical determinism was a property of
that bitstream, not of the bug. Do not rely on it.

Still true and still unexplained: the wedged core issues almost no memory
traffic (45k request-cycles against core 0's 1.59G).

**(b1) The stall was DETERMINISTIC before instrumentation.** Two runs bit-identical:
`live=411990,80,40236098`, handle 487432, ptr 1902014, same slots and steps.
Core 2 stops after exactly 80 loop iterations at `step=10` (loop top), never
entering `publish()`. Determinism rules out a race, and rules out X-state
(simulation-only). What DOES work on silicon at 4 cores: generational GC with
16-word cards, tenuring, and `STACKROOT ... OK` with `PTR-AGREE` — the cores
agree word for word on a 2 MB SDRAM heap.
The discriminator is BRAM-sim-passes vs SDRAM-hardware-stalls, so
`JopSmpSdramNCoreHelloWorldSim` (4 cores, SDRAM model) is the bridge; it
hardcodes NCoreHelloWorld, so pointing it at SmpGcTest is the targeted version.

**THREE SMP HARNESSES WERE BROKEN**, which is why none of this had been seen:
- `JopSmpBramSim` did not ELABORATE — `val pc = out Vec(UInt(11 bits), ...)`
 against a 12-bit `JopCoreConfig.pcWidth` (the 4K microcode ROM). Fixed here
 to derive from the config. `compile` does not elaborate and this sim is not
 in CI, so it failed silently since the ROM widened.
- `JopSmpBramSim` ALSO hardcodes `cpuCnt = 2`, so a core-count argument is
 silently ignored, and it waits for `"GC test start"` while loading
 single-core `HelloWorld.jop` — output that app never produces. It cannot
 pass. Same defect as item 2.
- `JopIhluSim` has no object of that name; the runnable ones are
 `JopIhluNCoreHelloWorldSim` and `JopIhluGcBramSim`.

**The `java/apps/Small` build only works via ACCUMULATED STATE.** `PreLinker`
needs the full transitive closure of runtime classes, but javac compiles only
what the app references, so `build/classes` is populated by whatever earlier
builds happened to leave. `rm -rf build` breaks it for everyone: the linker
then fails on `java/lang/Throwable.class`, then `java/io/PrintStream.class`,
and so on. Recovery is to compile the whole runtime tree once:
`javac -sourcepath "src:../../runtime/src/{jop,jvm,jdk}" -d build/classes
$(find ../../runtime/src -name '*.java') src/test/<App>.java`.
Also beware `make clean` here — `JOP_OUT` derives from `APP_NAME`, so it
deletes `HelloWorld.jop` (item 13).

<a id="item-33"></a>

### Item 33 — ~~`AlteraLpm.createRam` discarded the debug stack-RAM address~~

~~**`AlteraLpm.createRam` discarded the debug stack-RAM address**~~ —
**FIXED `8ef6aa9`, HARDWARE-VERIFIED 2026-08-12.** The debug read port returned
the wrong word on every Altera build, which is why the cross-core root scan
read `cands 0`. The fix steals the RAM's read port when a debug read is in
flight, so the risk was breaking NORMAL stack reads — and **no simulation
covers this**: every sim uses `MemoryStyle.Generic`.
EP4CGX150 single-core, fresh build, timing met (+2.445 ns setup, TNS 0.000):
**DoAll 66/66, `JVM exit!`**, checksum 0x88e4f517. That also puts `89da8fb`
(the `sys_exc` fix) on silicon for the first time — DoAll fires real hardware
exceptions (`Except`, `HwExceptionTest`, `NullPointer`, `DivZero`,
`AthrowTest`) and all pass.
**STILL NOT COVERED: debug READS themselves.** A plain boot never drives
`debugRamAddr`, so this is "no regression from the fix", not "debug reads
work". Closing that needs the DebugController or a 2-core build, where
`scanOtherCoreRoots` exercises the port via `rootRead`.
Two procedure notes, both of which cost attempts here:
~~**download at 1.5 Mbaud, not 2**~~ — **NO LONGER NEEDED (2026-08-15).** That
was the preset/PLL mismatch: `ep4cgx150Serial` declared 80 MHz while the
shared `dram_pll.vhd` was hardwired to 60, so the console ran at 2e6 x 60/80.
The PLL is now generated from the preset, an `ep4cgx150Serial` build really
does run at 80 MHz, and 2 Mbaud is correct. Still true: **reprogram
immediately before each download** — the ready handshake is consumed once and the board
then waits for data, so a download run standalone times out on `0xAA`.
When probing the port by hand, listen for **>500 ms**: that is the ready-byte
period, and a shorter window reads zero bytes and looks like a dead board.

<a id="item-35"></a>

### Item 35 — ~~`AlteraSdramAdapter` has NO simulation coverage — DONE~~

~~**`AlteraSdramAdapter` has NO simulation coverage**~~ — **DONE
2026-08-15**, `spinalhdl/src/test/scala/jop/memory/AlteraSdramAdapterTest.scala`
+ `src/test/resources/altera_sdram_tri_controller_stub.v`. Picked up by the
existing `simulation-tests` CI job, which already runs `jop.memory.*`.

The blocker was that `altera_sdram_tri_controller` is a BlackBox with no
Verilog body, so Verilator cannot build any design containing it. Solved with
a behavioural Avalon stub attached via `addRTLPath` **in test scope only** —
the production adapter is untouched. The stub models the Avalon contract, not
SDRAM: `readdatavalid` as a one-cycle pulse with no back-pressure,
`waitrequest` stalling commands unpredictably, in-order variable read
latency. It drives `avs_readdata` to X between pulses on purpose, so a
consumer that samples a cycle late fails loudly instead of reading a stale
value that happens to be right.

Four tests, and the three that matter were **confirmed to fail against the
pre-`ef36d99` adapter** before being kept:

| test | on the broken adapter |
|---|---|
| read data survives a stalling consumer | `WRONG READ DATA ctx=0 @0x0: expected 0xa5a5, got 0xa5f1` |
| a write response never answers a read | `MISPAIRED: expected ctx=2 (read), got ctx=3` (the write) |
| a read returns the last value written | `MISPAIRED: expected ctx=15, got ctx=16` |
| back-to-back, never-stalling (control) | **passes** — kept deliberately |

The control passing on the broken adapter is the point: it is the shape the
adapter was developed against, and it is why this went unnoticed.

**Two things worth carrying to the next test of this kind.** First, bug 1
presents as wrong DATA with a correct response COUNT and correct context
order — the old adapter popped its context FIFO on `rsp.ready` while taking
data from a pulse already gone, so contexts marched in order while data slid.
Any check built on counting responses, or on outstanding-transaction
arithmetic, sails straight past it; only content-and-pairing catches it.

Second, the first version of this file **passed against the broken adapter**,
because the producer loop ended with `if (failure.isDefined) return` — a
non-local return that left the method before `failure.foreach(fail)`, so a
detected fault was swallowed. It looked like four healthy green tests. That is
exactly the failure mode this item was written to prevent, and it was caught
only by running against the old file first. **Do that step; it is not
ceremony.**

<a id="item-32"></a>

### Item 32 — UART data corruption on seed 871203250 — no longer reachable; CI pin REMOVED

**RESCOPED 2026-08-18 — the CI seed pin is removed, but this is not "fixed".**
The failure no longer reproduces at HEAD, and nobody knows why.

| run | seed | X-state | ok | corrupt |
|---|---|---|---|---|
| D | **871203250** (the documented bad seed) | **random** | 132 | 0 |
| E | **871203250** | zeroed | 132 | 0 |
| F | 284409762 (CI's old pin) | zeroed | 132 | 0 |
| G | −1337 | zeroed | 132 | 0 |
| H | 20260818 | zeroed | 132 | 0 |

Run **D** is the one that matters: the documented bad seed, with randomisation
still on, now passes. So the pin was guarding against something that no longer
happens, and `--x-initial 0` makes the seed irrelevant to these sims anyway —
there is no other source of run-to-run variation in them, so pinning one seed
is now meaningless. Pin removed; `matrix.seed` still works if a future seed
misbehaves.

**Do not read this as a fix.** The corruption was real when measured (`ok=0
corrupt=61`, confirmed pre-existing by an A/B at `f65b05b`). What changed
since is the netlist — the MSHR work moved a lot of the memory path — and a
seed names an initial state only relative to a fixed netlist, so 871203250
simply does not select the same state any more. The item stays open as
**observed once, not currently reachable**, which is weaker than understood.

The claim below that this is "not X-state" is **unsupported**: its reasoning
("it reproduces from the seed alone") is the same backwards inference
corrected in items 29 and 30, and the A/B that would have settled it is
inconclusive because the control (run D) failed to reproduce the bug.

**Original analysis, retained:**

**UART data corruption on seed 871203250 — CI seed now PINNED around it.**
`JopJvmTestsMcFallbackSim` fails with every UART character corrupted, bits 1
and 3 cleared: `"ArrayTest2 ok"` prints as `"Adteaequpep ea"` and `"failed!"`
as `"daaded!"` (`o`->`e`, `k`->`a` diff 0x0A; `f`->`d` 0x02; `i`->`a`,
`l`->`d` 0x08). The suite reaches `JVM exit!` but every result line is
mangled, so the CI check reports "no results found" rather than a test
failure. A DATA-path fault, not control flow.
**Verified pre-existing, not caused by the `sys_exc` fix**: an A/B on the same
seed gives `ok=0 corrupt=61` both at HEAD and with `BytecodeFetchStage.scala`
+ `asm/src/jvm.asm` reverted to `f65b05b`. Random seeds pass 132 ok either
way; CI simply drew this seed for the first time on `3f173e4`.
**Not X-state** — it reproduces from the seed alone, so `--x-initial 0` would
mask a real bug rather than stabilise a flaky test. Do not add it here.
CI pins `JOP_SIM_SEED=284409762` for this job only (`.github/workflows/ci.yml`,
`matrix.seed`), which keeps the job honest about REGRESSIONS while this is
open. **The pin is not a fix and must come out once this is understood.**
Reproduce:
`JOP_SIM_SEED=871203250 sbt "Test/runMain jop.system.JopJvmTestsMcFallbackSim"`
Only seen in the microcode-fallback config so far; the baseline and
all-compute-unit jobs passed on the same commit.

<a id="item-3"></a>

### Item 3 — Sixteen presets still run classic GC. Safe but slow

**Sixteen presets still run classic GC.** Safe but slow. The cause is a
missing `hasCardTable` in each preset, NOT the old SMP guard -- that is gone
(item 1), so `GC.init` now selects generational on any preset that has a card
table, at any core count. `hasCardTable` is one line each and the boot line
confirms it took effect.
~~The Wukong presets are elaboration-verified only~~ — **confirmed on
hardware 2026-08-07**: a Wukong was attached for the first time and
`wukongFull` boots `GC: generational, 512-word cards`. The sixteen other
presets remain unverified.

### Performance

<a id="item-4"></a>

### Item 4 — Copy phase — 79-82% of the minor pause and the dominant remaining term

**Copy phase — 79-82% of the minor pause** and the dominant remaining term.
Latency-bound, not clock-bound: 132 cycles/handle at 75 MHz against 162 at
100 MHz. The handle table is 2 MB against a 32 KB cache and a handle is
exactly one 256-bit line, so ~6400 compulsory misses to find ~66 survivors.
Plan in [gc/copy-phase-redesign.md](gc/copy-phase-redesign.md). The 5-8x
estimate is **asserted from transaction counts, not measured**.

<a id="item-5"></a>

### Item 5 — The BMB arbiter sets the clock ceiling — FREQUENCY, not core count

**The BMB arbiter sets the clock ceiling at every core count** (headline
corrected 2026-08-16 -- it read "caps SMP at 2 cores @ 100 MHz", which is no
longer true: 12 cores run on the EP4CGX150 and 8 on Wukong DDR3, each at its
own clock). What the arbiter costs is FREQUENCY, not core count: 60 MHz at 4,
50 at 8, 36 at 12 on the EP4CGX150; 100 MHz at 4 and 91.68 at 6-8 on Wukong.
The measurements below stand and the path is unchanged. Path is
`coreX zeroCur -> arbiter -> coreY memCtrl state machine`, widening with core
count. Measured on EP4CGX150: 1 core 7,870 LE (~107 MHz), 2 cores 19,439 LE
(+0.270 ns at 100 MHz), 4 cores 38,372 LE (**65.3 MHz**). Area allows ~12
cores at 73% with full caches; BRAM never binds (~52% at 16). Pipelining the
arbiter costs a cycle on every memory access — see item 11 before committing.

**2026-08-18.** Still true, and still about FREQUENCY. Worth separating from the
throughput question now that the latter has been measured: the MSHR work found
the DRAM *throughput* ceiling was `LruCacheCore`'s serial miss FSM, not the
arbiter, and removing it took eight-core DDR2 from 1.81x to 4.28x and DDR3 from
1.75x to 4.38x with the arbiter untouched. So this item is a clock-closure
problem, and pipelining the arbiter buys Fmax at the cost of one cycle on every
memory access — which item 38 should price before anyone commits.


<a id="item-6"></a>

### Item 6 — ~~Major GC constant unexplained — LARGELY FIXED 2026-08-06, 2.6-3.2x~~

~~**Major GC constant unexplained**~~ — **LARGELY FIXED 2026-08-06, 2.6-3.2x.**
At 36k live objects, from three changes: an `imul` in `push()` (bug 29),
hoisting `push()`'s loop-invariant statics, and replacing sliding compaction
with **evacuation**, which removes the O(n log n) address sort entirely.

| board | before | after |
|---|---:|---:|
| EP4CGX150 SDR 100 MHz | 2214.9 ms | **859.1 ms** |
| CYC5000 SDR 80 MHz | — | **846.4 ms** |
| XC7A100T DDR3 100 MHz | 2214.9 ms | **689.8 ms** * |

\* the only figure still measured with `GC_SORT_TRACE`/`GC_MARK_TRACE` on,
so it is ~6 ms pessimistic; not re-run since item 25 turned them off.
The CYC5000 beating the EP4CGX150 on a slower clock is the latency-bound
behaviour showing through. `GcPauseTest`'s explicit `GC.gc()` went
161 -> 12.4 ms. Minor GC unchanged (1344 / 1315 ns/handle).
Detail: [gc/major-gc-evacuation.md](gc/major-gc-evacuation.md).

**Mark is now ~64% of what remains.** The one lever left is inlining `push`
into `mark`'s two loops to save its ~142-cycle call — worth ~102 ms of a
542 ms mark, against duplicating GC logic in the most safety-critical loop
in the collector. Not obviously worth it.

<a id="item-24"></a>

### Item 24 — ~~The evacuation trade is untested at larger object sizes~~

~~**The evacuation trade is untested at larger object sizes**~~ —
**MEASURED 2026-08-06, and the obvious fix was wrong.** `GcObjSizeTest`
holds handles fixed at 12024 and varies payload size: mark stays flat at
99.4 ms and copy is linear in live words at **0.673 µs/word**, so the
predicted ~38-word crossover measured at ~43 (XC7A100T) / ~40 (EP4CGX150) —
the one prediction all day that held.

**The size threshold that followed from it made every large-object row
325 ms worse and was reverted.** Sliding copies exactly as much as
evacuating under churn (1624.4 ms vs 1624.1 at 200 words), so it is strictly
worse by the whole sort. The crossover only exists in the *steady state*,
where a stable live set leaves objects already in position
(`GcMajorPauseTest`: slide copy 10 ms vs evacuation's 86). Deciding needs to
know how far objects are from their slide destination, which is not
derivable from live size and handle count. Left always-evacuate, both
regimes recorded in `chooseEvacDest`'s contract.

**So evacuation is not a strict improvement**: much better under churn,
worse for large objects in the steady state. An application benchmark
(item 11) is what would say which regime real code sits in.

**Validated on a third memory system (CYC5000, Cyclone V SDR, 80 MHz,
2026-08-07)**, which is the one board whose *clock* differs and whose 8 MB
heap forces the sort-and-slide fallback deliberately rather than by
accident. Major pause **846.4 ms** at 36k live (against 859.1 on the
100 MHz EP4CGX150 — slower clock, faster collection, consistent with the
pause being latency-bound). `GcObjSizeTest`: evacuation at 2/10/40 words,
**fallback engaging at 100 words** (`sort_ms` 299.4), and the 200-word case
cleanly refused as too large for the heap. `corrupt 0`, `OBJSIZE OK`.
`GcPauseTest`: minor worst **9.292 ms**, sweep 1315 ns/handle, MAJOR OK,
retained 64/64, born-bad 0.

Note `GcObjSizeTest`'s `passes` column reads 0 regardless now — it is under
`GC_SORT_TRACE`, which item 25 switched off. Read `sort_ms` to tell which
strategy ran.

<a id="item-25"></a>

### Item 25 — ~~Two loose ends from the GC work — DONE 2026-08-06~~

~~**Two loose ends from the GC work**~~ — **DONE 2026-08-06.**
`GC_SORT_TRACE` and `GC_MARK_TRACE` now default `false`; having them on cost
6.4 ms of the 865.6 ms pause, which matches the estimate made when they were
added. `prepareCompact` is now documented as **deliberately frozen** rather
than merely untouched — it is the last caller of `sortListByAddress`, still
slides to `heapStart`, and was not converted because the incremental
collector is unexercised (item 2) and evacuation needs its destination
reserved before the walk starts, which does not obviously survive being cut
into `COMPACT_STEP` pieces. Its comment warns that the pause figures in
`major-gc-evacuation.md` do not apply to that path.

<a id="item-7"></a>

### Item 7 — Root-scan floor: 2.2 / 4.7 / 8.5 ms across SDR / DDR3 / DDR2

**Root-scan floor: 2.2 / 4.7 / 8.5 ms** across SDR / DDR3 / DDR2. Tracks
memory latency, not clock (the SDR and DDR3 boards are both 100 MHz yet
differ 2.1x), so it will grow again on slower memory.

### Hardware / infrastructure

<a id="item-8"></a>

### Item 8 — XC7A100T timing margin is +0.001 ns — one bad run in seven

**XC7A100T timing margin is +0.001 ns**, with one bad run in seven during
regression testing. A regression platform with no margin manufactures false
failures. Re-implement for margin.

<a id="item-9"></a>

### Item 9 — Pico USB-Blaster needs a level shifter (74LVC8T245 or 2x 74LVC2T45)

**Pico USB-Blaster needs a level shifter** — 74LVC8T245 (or 2x 74LVC2T45)
with `VCCB` from JTAG header pin 4. No firmware change can fix it: the clone
drives a fixed 3.3 V into a 2.5 V bank and reads 2.5 V against an RP2040
V_IH of ~2.15 V. Unblocks having both Altera boards connected at once. The
pull-up fix and `jtag_pintest.c` are **uncommitted** in `~/workspaces/pico-usb-blaster`.

<a id="item-10"></a>

### Item 10 — pico-usb-blaster protocol bug — low-level shift works, Quartus handshake does not

**pico-usb-blaster protocol bug** — low-level shift works (IDCODE reads
correctly), so the fault is in byte-shift-mode or response framing. Lower
priority now the level shifter is understood as the real blocker.

<a id="item-31"></a>

### Item 31 — The BMB arbiter caps TIMING CLOSURE on both FPGA families (not throughput — see 2026-08-18 note)

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

<a id="item-11"></a>

### Item 11 — Application benchmark exists (`java/apps/JbeBench`) — remaining questions it should answer

**Application benchmark exists (`java/apps/JbeBench`) — remaining questions it should answer
one of the five decisions is settled, four remain.**

JavaBenchEmbedded ported from the original JOP tree — Kfl, UdpIp, Lift, via
`jbe.DoApp`. Baseline, EP4CGX150 single core @80 MHz: Kfl 7742, UdpIp 3524,
Lift 12681 iterations/s.

**SETTLED — the caches earn their area.** `ep4cgx150NoCache` A/B, single
variable:

| | with caches | no caches | gain |
|---|---|---|---|
| Kfl | 7742 | 7386 | +4.8 % |
| UdpIp | 3524 | 2761 | +27.6 % |
| Lift | 12681 | 6400 | **+98.1 %** |
| area | 8,784 LE | 6,386 LE | caches cost **2,398 LE**, +37.6 % |

+37.6 % area for +98 % / +28 % is a trade nothing else in the core
approaches. Kfl at +4.8 % is the marginal case.

**A METHOD NOTE THAT COST A WRONG PREDICTION.** Clock-scaling (running the
same build at 36 vs 80 MHz) shows whether a workload is memory-bound *in its
current configuration*, NOT whether it is memory-intensive. Lift scaled
almost linearly with clock and I read that as compute-bound — then it nearly
halved without caches, because its working set FITS the caches and so it
rarely reaches SDRAM. Only an A/B against the feature itself separates the
two. Full account in the JbeBench README.

**Still open, and what each now needs:**
- item 5/31, arbiter latency vs core count — needs slice B, a parallel
  throughput harness. Note the finding above reframes it: Kfl and UdpIp do
  ~25 % more work per MHz at 36 MHz than 80, so a pipelined arbiter costing
  a cycle per access is a worse deal for memory-bound work than a
  clock-focused reading suggests.
- item 4, whether the copy redesign helps real workloads — JbeBench can
  answer it now; needs an allocation-profile report alongside throughput.
- item 20, whether the double bytecodes deserve microcode — needs the
  `DoMicro`/`DoKernel` drivers, which already compile and just need a second
  `.jop` with a different main class.
- item 24, churn vs steady-state regime — needs the same allocation profile
  as item 4.

*Original entry:* **There is no application benchmark, and four decisions rest on it:**
whether a cycle of arbiter latency is worth 4+ cores (item 5); whether the
caches (2,213 LE/core, 33% of a core) earn their area; whether the copy
redesign helps real workloads (item 4); and whether the double bytecodes are
used enough to deserve microcode at all (item 20); and — added 2026-08-07 —
whether real workloads sit in the churn or steady-state regime, which is
what decides evacuate-versus-slide (item 24). Currently all five are
reasoned rather than measured. Probably the highest-leverage thing to build next —
and items 1 and 2 need a multi-core allocating application anyway, so the
first slice of this is already on the critical path (see *Coupling*).

### Smaller

<a id="item-36"></a>

### Item 36 — ~~The `stall freezes jpc, jinstr and the dispatch address` formal~~

~~**The `stall freezes jpc, jinstr and the dispatch address` formal
property timed out in CI**~~ — **FIXED 2026-08-15**, timeout 300 -> 900 s.
Not a regression and not a counterexample: SymbiYosys was killed at the wall
having reached BMC step 5, reported as `*** FAILED *** (5 minutes, 2
seconds)` + `java.lang.Exception: SymbiYosys failure`. The property takes
**2m47s locally** and CI's runner is roughly twice as slow, so it failed or
passed according to how busy the runner was — including on commits touching
no RTL at all (`fb3e8b6` is Java and docs only, and failed; `0da41f1` has
the same RTL and passed). Worth recognising the signature: a formal
"failure" with no counterexample and a duration equal to the timeout is a
timeout.

Tried and rejected: pinning `jbcWrAddr`/`jbcWrData`, which under
`assume(!jbcWrEn)` cannot affect anything and so looked like 19 free bits
per step the BMC need not carry. It made the property **slower**, 2m47s ->
3m42s — extra assumes change the solver's search and here for the worse.
The note is left in the source so it is not re-attempted.

This property is worth its runtime: it is the one that pins the freeze
invariant behind the three cascading fetch bugs, and no whole-system sim
catches that class.

<a id="item-29"></a>

### Item 29 — ~~`BytecodeFetchStage: JumpTable integration` is flaky in CI — FIXED~~

**FIXED 2026-08-18 — Verilator X-state. Same root cause as items 30 and 32.**
The analysis below was correct in every particular and stopped one step short:
"randomised post-reset state" *is* the answer, and the fix is to stop
randomising. `TestVectorUtils.simWave` now applies `--x-initial 0`, so every
unit test built on it starts from zero, as an FPGA does.

Closed A/B on the original failing seed, local Verilator 5.032:

| X-state | seed 360571106 | result |
|---|---|---|
| randomised (old behaviour) | 360571106 | **FAILS** — `1868 did not equal 550`, byte-identical to CI |
| zeroed, `--x-initial 0` (new default) | 360571106 | **PASSES** |

The seed is now **pinned in the test as a regression guard**, not removed: it
is a known-adversarial seed, so if anyone drops the flag this test is the
alarm. Check the guard still bites with
`JOP_SIM_XINIT=random sbt 'testOnly jop.pipeline.BytecodeFetchStageTest -- -z "JumpTable integration"'`.

The register that actually powers up dirty is still worth an `init()` — see
the note under item 30 on why zeroing is a floor, not a ceiling.

**Original analysis, retained:**

**`BytecodeFetchStage: JumpTable integration` is flaky in CI
failure is seed-dependent.** It broke the 2026-08-08 push and a rerun of the
*same commit* passed, so it is not a regression. Reproduce on demand:

```scala
// BytecodeFetchStageTest.scala:161
bcfSimConfig.compile(createDut(jbcData)).doSim(seed = 360571106) { dut =>
```

gives the CI failure exactly — `1868 did not equal 550, NOP should map to
0x226, got 0x74C`. `0x74C` is `entries[0xEC]`, and `0xEC` is an *undefined*
bytecode (the highest address in the table, the not-implemented handler), so
the DUT sampled an undefined bytecode rather than byte 0/1/2 of the test ROM
— randomised post-reset state, not a mis-timed read. Adding a settle cycle
does **not** fix it (tried; identical failure), so the cause is upstream in
what the JBC RAM or `jpc` hold after reset.

Ruled out, each of which looks plausible until checked: stale generated
artifacts (`asm/generated/` is gitignored and CI rebuilds it, but
regenerating locally with CI's exact `make && make serial && make
flash-altera` gives **byte-identical** output); parallel test collisions
(`build.sbt` sets `Test / parallelExecution := false`).

<a id="item-30"></a>

### Item 30 — ~~`JopJvmTestsBramSim` — the CI baseline job — intermittently dies — FIXED~~

**FIXED 2026-08-18 — Verilator X-state, the same cause as items 29 and 32.**

**The proof is an A/B that needed no new instrumentation** — the fingerprints
added on 2026-08-09 for exactly this purpose. Commit `caa8abbb` ran twice:

| run | seed | outcome |
|---|---|---|
| [31915456539](https://github.com/peteryates1/jop-spinalhdl/actions/runs/31915456539) | **−748081925** | hangs, **0** results |
| [31925196175](https://github.com/peteryates1/jop-spinalhdl/actions/runs/31925196175) | **564015666** | **132 ok**, 0 failed |

All five input fingerprints byte-identical across the two
(`DoAll.jop 3448530a…`, `mem_rom.dat a73153b1…`, `mem_ram.dat a6e8044f…`,
`JumpTableData.scala 76808793…`, `Const.java e0897fb1…`), same runner image,
same Verilator. **The seed was the only variable in the entire pipeline, so it
was necessarily the cause** — and the only thing a seed controls in a
SpinalSim run is the power-up value of the ~405 registers in this design that
have no reset.

**Two corrections to the analysis below.**

*First, the hang is not `E1`.* The 2026-08-15 failure printed `GC done` and
`CI` and then went silent for 59.6M cycles. `Startup.java` prints `CI` right
before `clazzinit()` and `OK` right after, so **it hung inside static-
initialiser execution** — well past `GC.init`, which `E1` never leaves. Same
"no results found" verdict, different place. That is what X-state looks like:
the failure point moves with the seed, so cataloguing where it stops was never
going to converge.

*Second, and this is the one that cost the week:* **"CI's failing seed passes
locally" was not evidence.** A seed names an initial state only relative to a
fixed netlist **and a fixed simulator build**. CI installs Verilator **5.020**
from the noble apt archive; the workstation runs **5.032** from Debian. The
same integer therefore selects a completely different power-up state on each,
so replaying seed 405669157 locally never reproduced CI's run, and the
ten-seed local sweep that "came back healthy on all ten" was testing something
else entirely. CI now prints `verilator --version` beside the fingerprints so
this is visible in every log.

**The fix.** `jop.utils.JopSimDefaults` centralises the defence and the three
jvm-suite sims plus `TestVectorUtils.simWave` (hence every unit test) now use
it. `JOP_SIM_XINIT=random` restores the old behaviour for deliberately hunting
missing resets. `JopDcuCacheSim` also gained seed support — it had **none**,
drawing a fresh seed per run and never printing it, so its five CI failures
were unreplayable by construction.

Also fixed: the CI step that echoes the seed matched `with seed [0-9]+`, which
silently drops a minus sign. Seeds are signed and the failing one was
negative, so it printed `748081925` — a *different* seed. Anyone who replayed
it was reproducing the wrong run.

**Verification.** Local, Verilator 5.032, CI-identical `DoAll.jop` rebuild
(`846080c4…`, JDK 8.0.492 target / JDK 17.0.19 tools):

| run | seed | X-state | result |
|---|---|---|---|
| A | **−748081925** (CI's failing seed) | zeroed | **132 ok**, 0 failed |
| B | 564015666 | zeroed | **132 ok**, 0 failed |
| C | 1 | zeroed | **132 ok**, 0 failed |

Plus the whole unit suite CI runs — `jop.core.* jop.io.* jop.pipeline.*
jop.memory.* jop.ddr3.* jop.config.* jop.sim.*` — **464 succeeded, 0 failed**
with the flag, so zeroing X-state broke nothing that was passing.

Confirmed in CI on `34976b0`: the log now carries
`Verilator 5.020 2024-01-01 rev (Debian 5.020-1)` (against 5.032 locally —
exactly the mismatch that invalidated the replays) and
`Sim X-state: zeroed`. That run drew seed **−1478500386** — negative, first
time out — so the seed-sign bug would have misreported it immediately had it
not been fixed in the same commit.

**This is a floor, not a ceiling.** `--x-initial 0` makes the simulator agree
with an FPGA at power-up; it does not make those ~405 registers correct.
Registers that genuinely need a defined reset should still get one, and
`JOP_SIM_XINIT=random` is how to go looking. What changed is that CI is now a
regression detector rather than a random number generator.

**Original analysis, retained:**

**`JopJvmTestsBramSim` — the CI baseline job — intermittently dies
`E1` — the GC runs out of heap on its first allocation.** Broke the
2026-08-09 scheduled run; a rerun of the *same commit* passed, so it is not a
regression. Whole JVM output on a bad run:

```
Small boot
GC init...
GC: classic (no card table - generational disabled)
E1
```

then 60,000,000 cycles of silence. `E1` is `GC.java:2134` — the first
allocation (creating the mutex) finds `copyPtr+size >= allocPtr` and hits a
deliberate `for(;;)`. So a bad run reports "no results found", not a test
failure. Both good and bad runs execute the full 60M cycles; the difference
is that the program hangs, not that it runs out of budget.

**Ruled out — every one of these looks like the answer until measured:**

- *The DCU change* (the only functional RTL change in the window): the sim
  passes locally on that exact RTL.
- *A config change shifting I/O addresses*: regenerating `Const.java` with
  CI's own command produces a **byte-identical** file.
- *`DoAll.jop` outgrowing the 512 KB BRAM*: CI logs `ls -l DoAll.jop` on
  every run — **2,926,493 bytes on both** the passing and failing runs.
- *Seed dependence* (as in item 29): running locally with CI's failing seed
  `405669157` passes. Failing seed 405669157, passing seeds 42187758 and
  1370482694. **Strengthened 2026-08-09**: a ten-seed sweep against the
  **CI-identical** `DoAll.jop` (`f388b4ca…`) — including CI's failing seed —
  came back healthy on all ten. So image *and* seed together are not
  sufficient to reproduce; whatever differs really is environmental.
  `JOP_SIM_SEED` makes replaying any future failing seed a one-liner.

**Do not be misled by the `Elaboration failed (2 errors)` /
`UNASSIGNED REGISTER (.../icu/resultReg)` messages in the log.** They appear
*identically in passing runs* — SpinalHDL restarts with a scala trace and
continues. They are long-standing noise and cost real time here.

**Correction (2026-08-09): "passes locally" above was not a clean
exoneration — CI and a local build produce DIFFERENT `DoAll.jop` images.**
The first fingerprints (added the same day) showed CI's `DoAll.jop` at
`f388b4ca…` against a local `2f5d046c…`, while `mem_rom.dat`,
`mem_ram.dat`, `JumpTableData.scala` and `Const.java` all matched exactly.
**Cause: TWO JVMs shape the image, and both differed from CI.** Resolved
2026-08-09 — local now reproduces CI's `DoAll.jop` **byte for byte**
(`f388b4ca…`).

| | sets | was local | is CI |
|---|---|---|---|
| `TARGET_JDK_HOME` (target `javac`) | image **size** | JDK 6 | **JDK 8** |
| `JAVA` (runs JOPizer/PreLinker) | image **layout** | JDK 21 | **JDK 17** |

The size difference is the target `javac` alone (JDK 8's image is 4645 bytes
/ ~116 words larger). With JDK 8 the size matched CI exactly while the bytes
still differed — that residual was the *tools* JVM, not the target one.
Hypotheses tested and killed on the way: the JDK 8 **patch** level (1.8.0_202
and 8u492 produce identical output), and source-file ordering from `find`
(reversing it produces a byte-identical `.jop`; the toolchain normalises it).

Both JDKs are now pinned and installed at `/opt/jdk8u492-b09` and
`/opt/jdk-17.0.19+10`, matching CI's Temurin 8.0.492 / 17.0.19. The
Makefiles default `TARGET_JDK_HOME` to the former. `JAVA ?= java` is
deliberately left alone — hardcoding a path there would break CI, which gets
its 17 from `setup-java`. For a CI-identical build:

```sh
JAVA_HOME=/opt/jdk-17.0.19+10 PATH=/opt/jdk-17.0.19+10/bin:$PATH make ...
```

Builds are deterministic **within** an environment: two consecutive local
builds are byte-identical, so this was never per-build randomness.

**The JDK 8 toolchain is validated on hardware across all five attached
boards, three FPGA vendors and three toolchains** (2026-08-09) — every app
image was rebuilt by the switch, so this is a re-validation of the whole
fleet, not a spot check:

| board | config | result |
|---|---|---|
| Wukong (Artix-7) | `wukongDdr3Fcu` — DDR3 | **66/66** |
| Wukong | `wukongSdram` — SDR | **66/66** |
| Wukong | `wukongSmp2` — 2-core | `SmpCacheTest` **PASS** + DoAll **66/66** |
| Wukong | `wukongDualIndependent` — DDR3 cluster | **66/66** |
| Wukong | `wukongDualIndependent` — SDR cluster | **66/66** |
| EP4CGX150 (Cyclone IV, Quartus) | `jop_sdram` | **66/66** |
| XC7A100T + DB V5 (Vivado) | DDR3 | **66/66** |
| Colorlight i5 (ECP5, yosys/nextpnr) | SDRAM | **66/66** |
| CYC5000 (Cyclone V, Quartus) | `jop_cyc5000` SDRAM | **66/66** |

Plus `JopJvmTestsBramSim` 66/66 in simulation. The i5, CYC5000 and Wukong
SDR runs all report the same download checksum (`0x695472d1`), confirming
the boards ran an identical image.

The CYC5000 needed a rebuild first: its `.sof` had vanished even though the
2026-08-07 build **succeeded** (`Flow Status: Successful - Fri Aug 7
08:15:11`) and every report from that run survived. It was not staleness —
neither make nor Quartus deletes a target for being out of date — and not
`make clean` or `git clean`, both of which would have taken the reports too
(all of `output_files/` is gitignored). Something removed only that one
file; the cause could not be established from what was on disk. Rebuilt with
`make -C fpga/cyc5000-sdram all`, timing met (worst slack +0.383 ns).

That last point matters for diagnosing this item. If CI's `DoAll.jop` hash
ever differs between two runs of the *same commit*, then CI is running a
different binary each time and that is the whole explanation — no
environmental theory needed. The fingerprints now recorded on every run make
that a one-line comparison; it could not be checked for the 2026-08-09
failure because only `ls -l` sizes existed then, and they were equal.

The 4645-byte difference is far too small to cause `E1` by itself: the
baseline sim has ~58,000 words of heap headroom.

<a id="item-45"></a>

### Item 45 — ONE unidentified register is read before it is written; the other ~401 look benign

**RESCOPED 2026-08-19 after the sweep. The "~405 registers" framing is not what
the evidence supports, and two of my own diagnoses of it were wrong.**

Across five seeds x 471 tests with X-state randomised, the register class
produced **zero** failures. The one reproducible symptom is
`BytecodeFetchStage: JumpTable integration` reading `entries[0xEC]` — an
undefined bytecode — instead of NOP's `entries[0x00]`.

What that symptom is NOT, both checked rather than assumed:

- **Not `jpc`.** `BytecodeFetchStage.scala:122` — `Reg(...) init(0)`. It is not
  in the 402 at all. Item 29 named it as a suspect; it is exonerated.
- **Not an uninitialised memory.** The JBC RAM is a `Mem`, and the grep behind
  the 402 count matches `Reg(` only, so I proposed the real exposure was
  uninitialised `Mem` contents. Wrong: `BytecodeFetchStageTest.createDut` pads
  the test bytecode to the full 2048 bytes and passes it as `jbcInit`, so that
  memory is fully initialised at elaboration.

**ROOT CAUSE FOUND 2026-08-19, and it is not a register.** Instrumenting the
failing cycle (`simPublic` on the whole path) shows the JBC RAM reading back
garbage with the write port held inactive and no write ever issued:

| X-state | `jbcWordDataRaw` | bytecode | `jpaddr` |
|---|---|---|---|
| zeroed | `0x00a76000` — the `init()` contents | `0x00` (NOP) | `0x226` ✅ |
| random | `0xe03e8376` — garbage | `0x76` | `0x74c` ❌ |

**Verilator's randomising x-initial discards `Mem` initialisation.** The
`jbcRamWord.init(...)` is simply thrown away. So the "undefined bytecode" is a
random RAM word, and the index is `0x76`, not the `0xEC` recorded since item 29.

**No FPGA behaves this way.** Block RAM contents come from the bitstream and
survive any reset, soft or otherwise. So this failure is a SIMULATOR ARTEFACT,
not a hardware hazard — which is why six boards reset cleanly while the sweep
kept flagging this test.

**Consequences, and they matter beyond this item:**

1. **The register class now has ZERO demonstrated offenders.** The single
  failure across five seeds was this artefact. The other ~401 are unimplicated
  by any evidence collected so far.
2. **`JOP_SIM_XINIT=random` is NOT a faithful model of a soft reset.** A soft
  reset leaves registers holding stale values but leaves initialised memories
  intact. Randomised x-initial additionally destroys memory init, which cannot
  happen in hardware, so the sweep OVER-REPORTS. Any future use of it must
  discount memory-init failures.
3. The pinned seed in `BytecodeFetchStageTest` is still worth keeping as an
  alarm for `--x-initial 0` being removed, but its comment described the wrong
  mechanism and has been corrected.

**Five wrong diagnoses preceded this**, all killed by experiment rather than
argument, and recorded so the path is not walked again: `jpc` (has `init(0)`);
uninitialised `Mem` contents (the test pads to all 2048 bytes); `jbcByteSelect`
(adding `init` changed nothing); floating `jbcWrEn/Addr/Data` inputs (driving
them changed nothing); driving those inputs *before* `forkStimulus` (also
nothing). A sixth near-miss: the first "control" run used plain `SimConfig`
rather than `JopSimDefaults.config`, so neither arm had the flag and both
showed garbage — a comparison that controlled nothing.

**Right next step, and it is small.** Run the failing case with a waveform and
trace what feeds the JumpTable index:

```sh
SIM_WAVE=1 JOP_SIM_XINIT=random sbt 'testOnly jop.pipeline.BytecodeFetchStageTest -- -z "JumpTable integration"'
```

Then add `init()` to that one register — not to 402 of them, which would cost
fabric for no demonstrated benefit.

**What would change this verdict.** The sweep covered the UNIT suite only. The
long system sims were not swept, and item 30's `clazzinit()` hang lived exactly
there. Sweeping `JopJvmTestsBramSim` under randomised X-state is the test that
would either find more offenders or justify closing this item.

**Why it still matters at all, given six boards reset cleanly.** Configuration
zeroes every flip-flop; the runtime reset (item 48) does not. Empirically the
boot path never reads one of these, on any board — but that is one path, and CI
can no longer see the class since `--x-initial 0` became the default.


**Opened 2026-08-18, as the residue of items 29/30/32.** `--x-initial 0` makes
the simulator agree with an FPGA at power-up, which is what stopped CI being a
random number generator. It does not make the design correct: a register whose
value matters before anything writes it is a real defect, and the FPGA merely
masks it by happening to power up at zero.

```sh
grep -rE "= *Reg(Next)? *\(" spinalhdl/src/main/scala/jop/ | grep -v init(
```

counts **~405**. Not all need a reset — most are written before they are read,
and adding `init()` to a deep pipeline register costs fabric for nothing. The
ones that matter are those an X-state run can demonstrably reach, and there is
now a cheap way to find them:

```sh
JOP_SIM_XINIT=random sbt "testOnly jop.core.* jop.io.* jop.pipeline.* jop.memory.*"
```

Two are already named by the closed items: whatever the JBC RAM / `jpc` hold
after reset (item 29 — the test read an *undefined* bytecode 0xEC), and
whatever `clazzinit()` walks into on the baseline sim (item 30).

**Do not treat a green CI as evidence this is done.** CI now zeroes X-state by
construction, so it can no longer see this class at all. That is the correct
trade — a regression detector should not be a fuzzer — but it does mean this
item needs deliberate sweeps, not observation.

**First sweep run, 2026-08-19: no NEW offenders.** Five seeds (1, 20260818,
-748081925, 360571106, 99991) of the full unit suite under
`JOP_SIM_XINIT=random`, 471 tests each:

| result | reading |
|---|---|
| `BytecodeFetchStage: JumpTable integration` fails on all five | the KNOWN item 29 offender (JBC RAM / `jpc`). Its seed is pinned at 360571106, so this is **one** data point repeated five times, not five |
| `CacheMigResetSim` "early release" failed on one seed | a fault in that TEST, not the RTL — see below. Now skipped under random X-state |
| everything else | 470/471 pass on every seed |

So the demonstrated-offender count is still **one**. That is real evidence the
other ~401 are written before they are read on the paths the unit suite covers,
and it is NOT proof: the suite is not the whole design, and the long system sims
were not swept.

**Two process faults from the first attempt, both worth keeping.**

*The probe hung for 8.4 hours and nobody noticed.* `CacheMigResetSim` used
`waitSamplingWhere(req.ready)` with no bound. Under randomised X-state that wait
never completed: one `doSim` burned 30,096 s of CPU at 100 % while the parent
sbt sat at 0 %, and the sweep never got past its first seed. A hang teaches
nothing; a bounded wait that fails names the signal. `acceptOrFail` now bounds
all three call sites and the sweep script has `timeout 1500` per seed. Same
lesson as the `pgrep` waiters and the `SWEEPDONE` waiters — **anything that
waits needs a bound** — arriving three times in one session in three disguises.

*A negative-result test cannot run on a random baseline.* The "early release
corrupts" case asserts that something goes WRONG, which needs a deterministic
starting state; under random X-state the early release sometimes lands clean
(seed 20260818) and the test then reports a failure saying nothing about the
hold length — a false positive fed straight into the sweep this item depends on.
It now `assume`s zeroed X-state and is skipped otherwise.

**Also corrected:** the hang was initially blamed on `LruCacheCore` wedging
under random X-state. With the waits bounded, `req.ready` asserts normally on
every seed tested and no accept-timeout is ever recorded, so that explanation is
unsupported. The original hang has not been reproduced and its cause is not
established.

<a id="item-47"></a>

### Item 47 — ~~A push cancelled the nightly scheduled CI run — FIXED~~

**FIXED 2026-08-18.** `concurrency.group` was
`${{ github.workflow }}-${{ github.ref }}`. On `refs/heads/main` a push and the
nightly `schedule` share that group, and `cancel-in-progress: true` then lets a
push **kill an in-progress scheduled run** — which is what happened to
[32096717541](https://github.com/peteryates1/jop-spinalhdl/actions/runs/32096717541)
at 03:47 on 2026-08-18 (1 of 13 scheduled runs in the sample).

That is the expensive one to lose: several jobs are schedule-only precisely
because they are 60M-cycle Verilator runs too slow for every push, so a
cancelled nightly is a silent hole in exactly the coverage the comment above
`jvm-suite-sims` exists to protect. It reports as `cancelled`, not `failure`,
so nothing draws attention to it.

Adding `-${{ github.event_name }}` to the group separates them. Pushes still
cancel each other, which was the original intent.

<a id="item-49"></a>

### Item 49 — ~~The UART divided the clock by an integer, so the baud was only right on lucky clocks — FIXED~~

**FIXED 2026-08-18.** `UartCtrl.setClockDivider` computes
`round(clkFreq / baud / samplesPerBit) - 1` and ticks on an integer count, so
the achievable rate is `clkFreq / (N x 5)` and nothing else. On this project's
clocks that is exact by luck, not design:

| board | clk | want | N | integer divider gives | error |
|---|---|---|---|---|---|
| EP4CGX150 SDR | 80.000 MHz | 2 M | 8 | 2,000,000 | exact |
| XC7A100T DB V5 | 100.000 MHz | 2 M | 10 | 2,000,000 | exact |
| Colorlight i5 | 40.000 MHz | 1 M | 8 | 1,000,000 | exact |
| A-E115FB DDR2 | 75.000 MHz | 1 M | 15 | 1,000,000 | exact |
| **A-E115FB @ 2 M** | 75.000 MHz | 2 M | 7 | 2,142,857 | **+7.14 %** |
| **Wukong Ddr3_366** | 91.676 MHz | 2 M | 9 | 2,037,244 | **+1.86 %** |

The two inexact rows are exactly the two documented gotchas: the A-E115FB's
"baud must divide 75 MHz / 5 exactly, 2 M does NOT", and the Wukong's
`DDR3_UART_BAUD = 2037000`. No integer can fix either -- 2 Mbaud from
91.676 MHz needs 45.838 clocks per bit.

**`jop.io.UartBaudTick`** replaces the divider with a phase accumulator (an
NCO): add `round(2^24 x baud x samplesPerBit / clkFreq)` each clock, tick on
the carry. `JopUartCtrl` wraps the stock `UartCtrlTx`/`UartCtrlRx` with it, and
both `jop.io.Uart` and `UartResetEscape` use it -- from one place, so the reset
escape can never decode at a different rate from the UART the host is tuned to.
Cost is an adder and 24 flip-flops.

Measured in simulation, in clock cycles so the testbench clock cannot flatter
it: Wukong **45.8379 cycles/bit against an ideal 45.8380 (-0.0002 %)** where
the divider was +1.86 %; A-E115FB at 2 M **37.5002 vs 37.5000 (+0.0006 %)**
where the divider was +7.14 %; EP4CGX150 unchanged at exactly 40.0000.

**Hardware.** EP4CGX150 download OK with an unchanged checksum and **8/8**
reset-and-redownload; Wukong downloads at a plain **2000000** instead of
2037000, **6/6** reset-and-redownload, `CARD OK`. Quartus +0.756 ns, Vivado
MET at WNS +0.109 ns.

**PROVEN ON HARDWARE 2026-08-18, both directions.** The A-E115FB now runs at
**2 Mbaud** — download OK, 6/6 then 4/4 reset-and-redownload, Quartus +0.762 ns,
download time roughly halved. The phase increment is `0x222222`, exactly
`round(2^24 x 2M x 5 / 75 MHz)`.

And the counterfactual was measured rather than argued. A CONTROL bitstream was
built with the preset at **2,142,857** — the rate the old integer divider
produced — leaving everything else identical:

| host rate | result against the control board |
|---|---|
| 2,000,000 | download fails, "no ready signal" |
| 2,142,857 (nominal) | garbage `\xda\xd2\xca` |
| 2.1 M / 2.2 M / 2.4 M / 1 M | garbage |

The board was demonstrably alive throughout, transmitting on the ~0.5 s ready
cadence. **No rate the CH340 can produce decodes it.** So 2 Mbaud really was
unreachable here before the fractional generator.

**The reason is stronger than this document used to claim.** The old note said
"+7.14 %, far outside UART tolerance", which is true but incomplete: even
*asking* the host for 2,142,857 does not help, because the CH340's divisors are
quantised and it cannot generate that rate either. There was no host rate that
matched. Tolerance is the second reason, not the first.

**A measurement trap worth keeping.** The first attempt at this control was
invalid and looked like a refutation: reading the 2 Mbaud board at a *requested*
2,142,857 returned 27/27 clean lines, which appears to disprove the intolerance
claim. It does not — the CH340 silently ignored the request and stayed at
2,000,000, so a perfectly matched link was being measured. Worse, opening it at
that rate wedged the bridge and produced a spurious 0/6 on the following reset
loop, which reprogramming cleared. Put the awkward rate on the FPGA side, where
the fractional generator makes it exact, and keep the host on a standard rate it
can actually synthesise.

**Do not oversell this.** On the Wukong it is TIDINESS, not a repair: 1.86 % is
inside 8N1 tolerance, both 2000000 and 2037000 decode cleanly against the new
bitstream, and the old pairing worked. What it buys there is a round number and
one less per-board constant. The functional win is the A-E115FB, where +7.14 %
is outside tolerance and 2 Mbaud was simply unavailable -- and that board is
**not attached, so that row is simulation only**. Its preset still asks for
1 Mbaud; raising it to 2 M would halve download time and should be tried when
the board is next connected.

Bitstreams built before 2026-08-18 still need 2037000 on the Wukong;
`run_bench`, the Makefile and the board doc all say so.

<a id="item-48"></a>

### Item 48 — ~~No runtime reset: the FPGA had to be reprogrammed before every download — DONE~~

**DONE 2026-08-18, hardware-verified on the EP4CGX150.** Programmed once, then
`HelloWorld -> reset -> CardMarkTest (CARD OK) -> HelloWorld`, with JTAG
untouched after the initial configuration.

**What the problem actually was.** Not the missing resets (item 45) -- the
missing reset SOURCE. `ResetGenerator`'s `res_cnt` is `resetKind = BOOT`, so it
initialises when the FPGA is CONFIGURED and never again, and `pll.io.areset` is
tied `False`. Reprogramming was not a habit, it was the only reset in the
design. The Xilinx boards were the accident: they already carry a `resetn` port
into the clock wizard, which is why only Altera and Lattice felt the pain.

**Two sources, because they fail differently.** `UartResetEscape` needs the
serial link alive; the button needs a human present.

| | trigger | pin cost | boards |
|---|---|---|---|
| UART escape | BREAK then `'R'` | **none** -- taps `ser_rxd` | every SDR/BRAM board |
| Button | active-low, 10 ms debounce | one input | boards mapping a SWITCH `"reset"` (EP4CGX150 `sw1`, PIN_AD24) |

Both live in the `resetKind = BOOT` domain, outside the reset they drive.

**Why a break and not a magic byte.** A break is a framing violation, so it
cannot be forged by data: at 8N1 the longest low run a valid frame can hold is
9 bit-times against a 13 bit-time threshold. `UartResetEscapeSim` proves it by
sending all 256 byte values, and back-to-back `0x00`, without a trigger. The
confirming byte then covers what a break alone does not -- a floating line is
an infinite break, bridges glitch on open/close, and a mismatched baud can read
as one.

**`pyserial.send_break()` does not work on a CP2102N.** The ioctl is accepted
and nothing reaches the wire: the FPGA's own `UartCtrl` discards TX while a
break is asserted, and a 2-second break produced no suppression at all. So
`download.py` generates the break out of ORDINARY DATA -- drop the host baud,
send `0x00`, restore it. At 1/16th baud that is 144 bit-times of low. Every
bridge can do it, because it is just a byte.

**Two of my own bugs worth recording, both of which passed simulation:**

1. The confirmation window was sized in BIT-TIMES (64, = 32 us at 2 Mbaud).
  The host cannot possibly meet that -- `send_break()` returns, then another
  syscall, then a USB frame: milliseconds. Simulation passed because the
  testbench sent the byte immediately. It is now 100 ms of wall clock, and
  `UartResetEscapeSim` has a "byte arriving LATE" case that fails without it.
2. `hasRuntimeReset` was declared BEFORE the `sys`/`isDdr3` vals it reads. A
  Scala `val` referencing a later `val` silently gets its default, so the
  feature would have been dead everywhere with no error.

**I predicted item 45 would gate this. It did not.** Bare reset from a running
application: **10/10** into the bootloader. Reset-and-redownload: **8/8**. The
intermittent hangs I saw first were my own test harness -- a 5 ms settle
between the `0x00` and restoring the baud, too short for the byte to clear the
USB buffer, so it went out truncated. At 10 ms it is reliable. The ~405
unreset registers remain a real hazard in principle and item 45 stands, but on
this board with these applications they do not stop a clean reboot.

**DDR3 done too (Wukong, same day).** It needed a different reset, not a
wider one: `sys_rst` stays tied to PLL lock so the **MIG keeps its
calibration**, and what resets is everything in the `ui_clk` domain -- core, L2
cache and `CacheToMigAdapter`, all built inside `mainArea`. Measured on the
Wukong at `Ddr3_366`: **8/8** reset-and-redownload cycles, `CardMarkTest`
running `CARD OK` afterwards, Vivado timing MET at **WNS +0.696 ns**.

**Why that is safe with the MIG still running underneath.** The MIG does not
know a reset happened, `app_rd_data_valid` cannot be back-pressured, and this
path matches responses **by position** -- the shape of both the DDR2 write-ack
bug and the AlteraSdramAdapter corruption, each of which produced wrong data
rather than a hang. Two properties make it work:

1. *Writes cannot be stranded.* `CacheToMigAdapter` gates on
  `app_rdy && (!headIsWrite || app_wdf_rdy)` and drives `app_en`,
  `app_wdf_wren` and `app_wdf_end` in the **same cycle**, so the MIG is never
  left waiting for a write burst.
2. *Reads in flight are dropped.* While the adapter is in reset its FIFOs stay
  empty, so late beats are not captured, and the hold outlasts any MIG read
  latency.

`CacheMigResetSim` tests both, and pins the second: an A/B differing **only**
in hold length shows a long hold returns correct data while a one-cycle hold
corrupts. `ResetGenerator.Ddr3ResetCycles` is therefore load-bearing, and that
test is the alarm if anyone shortens it.

**Honest limit on the evidence.** Time-to-ready after a UART reset was
0.20-0.60 s against 0.60 s after configuration, but those are multiples of the
boot loader's ~0.2 s `0xAA` retry cadence, so the measurement is too coarse to
independently prove calibration survived. The real evidence is structural and
checked in the generated Verilog: `sys_rst = !clkWizBlackBox_locked`, untouched
by the runtime reset.

**DDR2 done too (A-E115FB, 2026-08-18): 6/6.** The pattern transferred, with
one difference that matters: that domain is **ASYNC and active LOW**, not SYNC
active HIGH, so the hold is inverted and ANDed rather than ORed. The
controller's `global_reset_n` and `soft_reset_n` stay on the outer reset, so the
ALTMEMPHY keeps its calibration; the generated Verilog reads
`(reset_phy_clk_n && local_init_done) && !(hold != 0)`. Quartus 18.1 timing met
at **+0.510 ns**. The safety argument is the same as DDR3's and holds for the
same reason: `CacheToDdr2Adapter` covers command and write data with ONE
`local_ready` and samples `local_wdata` alongside `local_write_req`, so a write
cannot be left half-issued.

`Ddr3ResetCycles` is now `DramResetCycles`, shared by both DRAM paths -- 4096
cycles is ~45 us at 91.7 MHz and ~55 us at 75 MHz.

The i5 gets the UART escape but no button, because no user-button pin is
documented for that board. The Xilinx boards get no `reset_n` either: they
already carry a `resetn` into the clock wizard, which is a FULL reset
(recalibration and all) and so complements the fast core-only UART path.

**Verified on every attached board, across all three vendors and toolchains:**

| board | fabric / toolchain | memory | UART | reset-and-redownload |
|---|---|---|---|---|
| EP4CGX150 | Cyclone IV GX, Quartus | SDR | 2 M | **8/8** (+ button on SW1) |
| Wukong XC7A100T | Artix-7, Vivado | DDR3/MIG | 2 M | **6/6** (core-only) |
| Colorlight i5 | ECP5, yosys/nextpnr | SDR | 1 M | **4/4** |
| CYC5000 | Cyclone V, Quartus | SDR | 2 M | **4/4** |
| A-E115FB | Cyclone IV E, Quartus 18.1 | **DDR2** | 1 M | **6/6** (core-only) |
| XC7A100T + DB V5 | Artix-7, Vivado | DDR3/MIG | 2 M | **8/8 + 6/6** (core-only) |

That is every board, every vendor, every toolchain and every memory technology
in the project.

**One anomaly on the DB V5, recorded rather than smoothed over.** Between the
first and second runs the board went quiet: a download reported OK, produced no
output, and the next attempt got "no ready signal"; an earlier readback also
showed three corrupted bytes (`512` as `\x15\x11\x10`). Reprogramming cleared
it, after which 8/8 and then 6/6 ran clean with state verified at every step.
**The cause was not isolated.** It is plausibly the Pico's CDC bridge rather
than the FPGA -- that board's UART is a USB-CDC bridge on the dirtyJtag probe,
not a hardware UART -- but nothing here separates the two, so do not assume it.
Note also this build's timing is +0.010 ns (item 8: that board runs marginal),
which is a second candidate and equally unproven.

The i5 and CYC5000 needed no new code -- they are single-system SDR boards, so
they picked up the escape from the generic path. Both were nonetheless rebuilt
and run, because "it elaborates" is not "it works": i5 43.66 MHz PASS at 40,
CYC5000 +0.957 ns.

Usage: `make -C fpga/qmtech-ep4cgx150-sdram redownload JOP_FILE=<app>.jop`,
or `download.py -r <app>` / `-R` for reset-only. Neither the i5 nor the CYC5000
maps a SWITCH `"reset"` pin, so both are UART-only -- no `reset_n` port, no pin
cost.

<a id="item-46"></a>

### Item 46 — ~~`formal-verification` fails intermittently — ALREADY FIXED (2026-08-15)~~

**Filed and closed the same day, 2026-08-18 — the fix predates the
investigation.** Recorded because the failure pattern is still visible in the
CI history and will otherwise be re-discovered.

`formal-verification` was the only failing job in **5** of the 20 CI failures
in the 200 runs to 2026-08-18 (31883019262, 31877585619, 31695840571,
31691895916, 31530822513), signature:

```
##   0:02:51  Checking assertions in step 5..
- formal_stall freezes jpc, jinstr and the dispatch address *** FAILED *** (5 minutes, 2 seconds)
  java.lang.Exception: SymbiYosys failure
```

No counterexample and no trace: the run died, the proof did not fail. It was
the SymbiYosys wall clock, then **300 s**. `6bce639b` (2026-08-15 12:54)
raised it to **900 s** with the reasoning written into
`BytecodeFetchStageFormal.scala` — *"a formal timeout should mean 'this
property has become intractable', not 'the runner was busy'."*

**The last formal-only failure was 2026-08-15 11:49, 65 minutes BEFORE that
commit, and there has been none since.** Confirmed independently here: the
property passes locally in **2 min 58 s** against a 900 s budget, and CI's
runner is roughly half the speed — so the headroom is real.

Lesson worth keeping: a `*** FAILED ***` whose only detail is a duration and a
backend exception is a resource limit, not a disproof. Check the configured
budget against the elapsed time **before** filing it as a bug — this item was
opened on the guess "timeout", corrected to "cause unknown" for lack of
evidence, and then closed on finding the budget in the source. Only the last
step involved reading the code.

<a id="item-47"></a>

### Item 47 — ~~A push cancelled the nightly scheduled CI run — FIXED~~

**FIXED 2026-08-18.** `concurrency.group` was
`${{ github.workflow }}-${{ github.ref }}`. On `refs/heads/main` a push and the
nightly `schedule` share that group, and `cancel-in-progress: true` then lets a
push **kill an in-progress scheduled run** — which is what happened to
[32096717541](https://github.com/peteryates1/jop-spinalhdl/actions/runs/32096717541)
at 03:47 on 2026-08-18 (1 of 13 scheduled runs in the sample).

That is the expensive one to lose: several jobs are schedule-only precisely
because they are 60M-cycle Verilator runs too slow for every push, so a
cancelled nightly is a silent hole in exactly the coverage the comment above
`jvm-suite-sims` exists to protect. It reports as `cancelled`, not `failure`,
so nothing draws attention to it.

Adding `-${{ github.event_name }}` to the group separates them. Pushes still
cancel each other, which was the original intent.

<a id="item-46"></a>

### Item 46 — `formal-verification` fails intermittently — a proof times out, unrelated to X-state

**Opened 2026-08-18.** Separated out from the CI-flakiness work because it is
*not* the same cause. Of 20 CI failures in the 200 runs to 2026-08-18,
`formal-verification` was the only failing job in **5** of them
(31883019262, 31877585619, 31695840571, 31691895916, 31530822513), on four
different days.

The signature is a bare backend failure, not a counterexample:

```
##   0:02:51  Checking assertions in step 5..
- formal_stall freezes jpc, jinstr and the dispatch address *** FAILED *** (5 minutes, 2 seconds)
  java.lang.Exception: SymbiYosys failure
```

The property is `withBMC(6)` on `BytecodeFetchStage`, which contains the
2048-entry JBC RAM; solver is z3. It got to step 5 of 6 and then SymbiYosys
exited non-zero **with no counterexample and no trace** — a disproof prints
one. So the proof did not fail, the run did.

**What is NOT established:** *why* it exited. Timeout, OOM on a 16 GB runner,
and a solver error all look like this in the log GitHub retains, and the
`engine_0` directory that would say dies with the runner. Do not write
"timeout" down as the cause until someone has looked — that guess is the same
species of unverified inference that kept items 29/30 open for a week.

Next, in order of cheapness: reproduce locally and time it; if it is slow but
passes, the runner's variable CPU is the difference and the budget needs
headroom; capture `simWorkspace/BytecodeFetchStageFormal/` as a CI artifact on
failure so the next occurrence is diagnosable at all. Until then, read a
`formal-verification`-only failure with suspicion.


<a id="item-12"></a>

### Item 12 — `LongComputeUnitConfig` has no enable flag for its base 64-bit ALU

**`LongComputeUnitConfig` has no enable flag** for its base 64-bit ALU
(`ladd/lsub/lneg/lcmp`), unlike `FloatComputeUnitConfig.withAdd`. Worked
around at the `ComputeUnitTop` level (conditional instantiation), but the
config asymmetry remains and would bite anyone relying on the `with*` flags
alone.

<a id="item-13"></a>

### Item 13 — `java/apps/Small` `make clean` deletes `HelloWorld.jop`

**`java/apps/Small` `make clean` deletes `HelloWorld.jop`** — `JOP_OUT`
derives from `APP_NAME`, which defaults to HelloWorld. Cost a sim failure
and nearly a wrong SMP result. Build HelloWorld last, or `rm -rf build`.

<a id="item-14"></a>

### Item 14 — Stack cache SDRAM integration — 3-bank rotation verified in BRAM, needs per-core regions

**Stack cache SDRAM integration** — pre-existing; 3-bank rotation verified
in BRAM simulation, needs per-core stack regions on SDRAM.

<a id="item-15"></a>

### Item 15 — ~~`GcPauseTest` on the Wukong boards — never run — DONE~~

~~**`GcPauseTest` on the Wukong boards** — never run~~ — **DONE
2026-08-07.** Wukong XC7A100T + DDR3, `wukongFull` at 100 MHz: minor pause
**worst 11.840 ms / mean 11.813** over 63 collections, sweep 1624
ns/handle, copy **87%** of the pause (the other boards are 79-82%), major
`MAJOR OK` with retained 64/64 and `corrupt 0`, free 262 MB.
`GcMajorPauseTest` at 36k live: **681.2 ms**, the best of the four boards
measured — sort never runs. (The CYC5000 was measured too — see item 24.)

<a id="item-16"></a>

### Item 16 — ~~Colorlight i5 SDRAM ("stage 2" of that board's bring-up — unrelated to~~

~~**Colorlight i5 SDRAM ("stage 2" of that board's bring-up — unrelated to
the GC stages elsewhere in this document)**~~ — **DONE** (`a7fdf93`). 8 MB working on
hardware, DoAll 66/66 at 1 Mbaud. `BmbSdramCtrlWide` added for the 32-bit
part; `MemoryControllerFactory.createSdr` selects on `layout.dataWidth`.
Remaining i5 work is ordinary: raise the clock above 40 MHz, and try SMP now
that block RAM is only 21% used. See `docs/boards/colorlight-i5-bringup.md`.

<a id="item-23"></a>

### Item 23 — ~~`f_multianewarray` handles exactly 2 dimensions — FIXED~~

~~**`f_multianewarray` handles exactly 2 dimensions**~~ — **FIXED
2026-08-06.** It was hardcoded to `dim == 2` and printed "dimensions not
supported" for anything else, so `new int[a][b][c]` was an unimplemented
trap. `JVMHelp.multiNew` now builds any nest up to `MAX_ARRAY_DIM = 8` by
recursing one level at a time. The spec allows 255, but a runaway nest would
overflow the stack part-way through allocating and leave a half-built
structure, which is worse than a clean refusal.

The part that mattered was never the loop, it was the **GC metadata**: only
the innermost level carries the element type and every level above it is a
reference array. Getting that wrong at two levels was `78cc968` — inner
arrays typed `IS_OBJ`, collector unable to size them or scan their elements,
premature collection with no visible fault. `MultiDimTest` checks 3-D and
4-D primitive, 3-D reference and the degenerate zero-length shapes **after**
30k rounds of churn and two full mark-compacts, because DoAll's `MultiArray`
passed throughout that defect and reading values back proves nothing.
10/10 on EP4CGX150 and XC7A100T, `MultiArrayGcTest` OK, DoAll 66/66.

What it did **not** close is the missing element class — now **item 26**,
so it is not buried inside a finished item.

<a id="item-26"></a>

### Item 26 — ~~Reference arrays carry no element class — FIXED~~

~~**Reference arrays carry no element class**~~ — **FIXED
2026-08-07.** Arrays now carry a descriptor `(dim << 24) | elem` in handle
word **`GC.OFF_ELEM = 6`**, and JOPizer emits the same encoding for array
constant-pool entries. `elem` is a primitive code 4..11 or the element
class's struct address (always >= 16, so they never collide). This is the
information HotSpot keeps in an `ObjArrayKlass` — `_element_klass` plus
`_dimension` — so `checkcast`/`instanceof` are now the ordinary check:
equal dimensions, then an element subtype walk, with covariance falling out.

**Costs no memory.** `HANDLE_SIZE` is 8 and only 0-5 were used, so word 6
was already allocated. `OFF_TYPE` stays a small code, so the GC's tracing
paths are untouched — that was the constraint that mattered.

`anewarray` turned out to already *receive* the component type and discard
it (`// ignore cons ... should be different for the GC!!!`). Because a plain
class address has a zero dim field, `desc = cons + (1 << 24)` promotes
either a class or an existing descriptor by exactly one dimension, which
makes `new Foo[n]` and `new int[n][]` the same line of code.

Now exact, all previously wrong: `(Derived[]) x` where x is `Base[]`
**rejected** (was accepted), `(Base[]) derivedArray` accepted (covariance),
`int[][]` distinguished from `int[]` in both directions. `ArrayCastTest`
23/23 on **EP4CGX150, XC7A100T and Colorlight i5**; `MultiDimTest` 10/10 on
all three; DoAll 66/66, `MultiArrayGcTest` OK, `GcStressTest` 240k+ rounds
clean.

**The three follow-ups are done too (2026-08-07).**
- `f_aastore` now performs the covariant store check and throws
  `ArrayStoreException` (a class JOP's JDK subset did not have — likely part
  of why this was never implemented). The common case is inlined: a 1-D
  reference array whose element class is exactly the value's class, three
  reads and no call, because a helper call is ~142 cycles on a hot path.
- `(Cloneable) arr` and `(Serializable) arr` now succeed. Arrays have no
  interface table, so JOPizer emits the two class-info addresses into the
  special-pointer block and `JVMHelp.init` reads them — the equivalent of
  HotSpot's `ArrayKlass` declaring those interfaces.
- The **WCET bound is unchanged at `@WCA loop <= 5`**, and the earlier
  concern was wrong: the array path and the object path in `f_checkcast`
  are mutually exclusive, so it is still one walk. The new element walk in
  `classAssignable` is annotated accordingly.

`ArrayCastTest` is now **36 checks**, passing on EP4CGX150, XC7A100T and
Colorlight i5. DoAll 66/66, `MultiDimTest` OK, `GcStressTest` 240k+ clean.

<a id="item-27"></a>

### Item 27 — The `aastore` type check's cost was never measured

**The `aastore` type check's cost was never measured.** Item 26 added
a covariant store check to `f_aastore`, which every reference-array store
goes through. The common case is inlined — three reads and no call, chosen
because a helper call is ~142 cycles — but "chosen because" is reasoning,
not measurement, and this document's record on that is four wrong out of
five. Nothing in the suite times array stores, so the check could be costing
a few percent or a third and nobody would know. Needs a store-heavy
microbenchmark, or the item 11 application benchmark, before the design is
called settled.

<a id="item-28"></a>

### Item 28 — ~~`DoAll` dies at `CollectionTest` on the Wukong — FIXED~~

~~**`DoAll` dies at `CollectionTest` on the Wukong**~~ — **FIXED
2026-08-08. Three real hardware defects, `wukongFull` now DoAll 66/66** with
every compute unit in hardware (was 59/66 with a crash).

| # | defect | symptom |
|---|---|---|
| 1 | **FCU compare**: a lone zero operand fell through to the exponent compare | `0.75f <= 0` TRUE |
| 2 | **DCU compare**: identical defect in the sibling unit | same, for double |
| 3 | **DCU divider**: dropped its last quotient bit | `Math.sqrt(9.0)` = 3.345 |

**1 and 2 — the compare.** `unpackFloat`/`unpackDouble` flush zero to
`exp := 0`, which is the *unbiased* exponent of 1.0. Only both-operands-zero
was special-cased, so a lone zero was compared as if it were ~1.0 and every
magnitude below 1.0 came out "less than zero". `HashMap`'s constructor is
`if (loadFactor <= 0 …) throw`, so with an FCU present **every `HashMap`
construction threw** on the default `0.75f` — and the throw concatenates a
float into its message, so control vanished into float-to-string. That is
why `CollectionTest`, which contains no float at all, died: silently
standalone, as `bytecode 255 not implemented` under `DoAll`. Fixed by
deciding on the sign of the non-zero operand.

**3 — the divider.** `DIV_ITER` read `val q = divQuotient` at the final
count; that is a register, so it returned the pre-update value and lost the
last quotient bit. `resMant`'s leading 1 landed at bit 53 instead of 54 and
`ROUND` read a zero as the hidden bit — packing `1.1010…` for `1.0101…`, so
`1.0/3.0` gave 0.416667. Only quotients with dividend < divisor are
affected, which is why `div_normal` (7/2, 12/4) never caught it.

**Why it stayed latent for months.** Every one of these hides behind the
values the tests happened to use: `fcmp_zeros` only compared zero *with*
zero, all other compare cases use 1.0/2.0 where exponent ≥ 0 gives the right
answer, and both divide cases are exact with dividend > divisor. The FCU was
signed off at "52/52 BRAM JVM tests" on a suite predating `CollectionTest`.

Guards added, each **verified to fail on the unfixed RTL** rather than
merely passing: `fcmp_one_operand_zero`, `dcmp_one_operand_zero`,
`div_inexact` (both units). 145/145 in `jop.core`.

On-target reproducers kept: `FcuBugTest` (the exact operations `HashMap`
performs, integers only) and `MathBugTest` (`MathTest`'s 21 checks reported
individually, because `MathTest` chains them with `&&` and reports only
"failed!"). `OneTest` runs a single `TestCase` from a cold start.

**A fourth suspicion was wrong and is worth recording**: the FCU divider has
the same `val q = divQuotient` shape, so it looked like the same bug. Patching
it broke `7.0/2.0`, which had been correct. Reverted — its iteration
structure differs and it never had the defect. `div_inexact` passes there
unmodified and now stands as proof.

**All six DDR3 Wukong presets re-verified against the final RTL**
(2026-08-08), rather than leaving intermediate-state results lying around —
`wukongDdr3AllCu`'s only previous record was a *failure* from before any fix:

| preset | LUTs | WNS | DoAll |
|---|---:|---:|---|
| `wukongDdr3` (baseline) | 17515 | +0.360 | **66/66** |
| `wukongDdr3DspMul` | 17850 | +0.263 | **66/66** |
| `wukongDdr3Lcu` | 18474 | +0.207 | **66/66** |
| `wukongDdr3Fcu` | 18870 | +0.029 | **66/66** |
| `wukongNoDcu` | 20161 | +0.033 | **66/66** |
| `wukongDdr3AllCu` | 24497 | +0.008 | **66/66** |
| `wukongFull` | 25624 | +0.121 | **66/66** |

`wukongDdr3Lcu` passing means the **LCU is clean** — the three defects were
confined to the FCU and DCU.

`wukongDdr3Fcu` was added on 2026-08-08: it had **never been built or run**,
despite being the preset that isolates the FCU (`wukongDdr3 + float -> hw`)
and therefore the most direct check on the compare fix in item 28. It was
missed because the sweep was assembled from the presets that already had
bitstreams, so the one preset with no history was the one that got skipped. Builds were staggered against tests, so each
Vivado run overlapped the previous bitstream's ~4-minute DoAll; bitstreams
are stashed per preset because every build writes the same output path and
would otherwise clobber the one under test.

**The SDR-on-Artix trio now runs too** (2026-08-08) — first time JOP has
used the Wukong's SDRAM. All three pass `DoAll` 66/66, but two do not close
timing at 100 MHz:

| preset | LUTs | WNS | DoAll |
|---|---:|---:|---|
| `wukongSdram` | 4963 | +0.318 | **66/66** |
| `wukongSdrAllCu` | 11801 | **-0.061** | 66/66 *(timing violated)* |
| `wukongSdrFull` | 13232 | **-0.774** | 66/66 *(timing violated)* |

A passing `DoAll` on a violated bitstream proves nothing — it can misbehave
arbitrarily and the failure would be intermittent. Both need a seed sweep or
a lower clock before they mean anything. Note the all-CU configs sit on the
edge on both memories: DDR3 AllCu closed at **+0.008 ns**.

Two build-flow defects were fixed to get here, neither about the design:

- **`clk_wiz_0` is generated by BOTH flows into the same IP directory.**
  `create_sdram_clk_wiz.tcl` and `create_ddr3_clk_wiz.tcl` emit the same
  module name, and only the SDR one has the phase-shifted `CLKOUT2` that
  `JopSdramWukongTop` wires to `sdram_clk`. Whichever flow ran last owns the
  IP, so switching without regenerating fails synthesis with *"named port
  connection 'clk_100_shift' does not exist"*. The SDR build now regenerates
  its own clk_wiz first.

  **FIXED PROPERLY 2026-08-17 — the clock wizards are named for their
  FUNCTION**: `sdr_clk`, `ddr3_clk`, `bram_clk`. Regenerating first was a
  workaround that cost ~105 s on every flow switch and still left one live
  IP; the three variants now occupy three directories and coexist, so
  switching memory type regenerates nothing. It was in fact a THREE-way
  collision — the BRAM flow emitted `clk_wiz_0` too, with only `clk_100`.
  `Board.scala` derives the instance name from `memType` rather than from
  the index in `systems`, so the dual build no longer depends on SDR
  happening to sit at index 1. Two things fell out: `create_sdram_clk_wiz_1.tcl`
  was config-identical to `create_sdram_clk_wiz.tcl` (the dual's SDR clock
  was raised 80→100 MHz and the two were never collapsed) and is deleted;
  `build_sdram_exerciser_80mhz.tcl` is deleted too — its 80 MHz wizard no
  longer existed, so pointing it at `sdr_clk` would have silently built a
  100 MHz bitstream from RTL named `_80mhz`. It is recoverable from `6b31502`
  if the 80 MHz exerciser is ever wanted again; rebuilding it needs a
  `sdr_clk_80` variant of `create_sdram_clk_wiz.tcl`. Its hand-modified
  `SdramExerciserWukongTop_80mhz.v` was never tracked (it lived in the
  gitignored `spinalhdl/generated/`), so that part is gone for good — it was
  a hand-edit of generated output, which is why it should never have been the
  only copy of anything.

  Note the derived clock names in XDC follow the **IP module** name, not the
  RTL instance name (which is `clkWizBlackBox`) — hence `clk_125_clk_wiz_0`
  became `clk_125_ddr3_clk`, and the dual's `clk_100_clk_wiz_1` became
  `clk_100_sdr_clk`. Getting this wrong is silent: `set_clock_groups` on a
  clock that matches nothing simply does not constrain, which is how the
  −2.037 ns CDC regression hid in the first place.
- **`wukongSdrFull` could not generate a bitstream at all.** Ethernet and SD
  pin constraints existed only in `wukong_ddr3.xdc`, so 32 of 77 ports had no
  LOC/IOSTANDARD and `write_bitstream` refused (DRC NSTD-1 / UCIO-1). Moved
  to `wukong_peripherals.xdc` and read by the SDR flow. `wukong_ddr3.xdc`
  keeps its copy — removing constraints that six verified DDR3 configs
  depend on was not worth the risk — so the two are **duplicated and must be
  kept in sync**; collapsing that is a follow-up.

**The dual, SMP and BRAM presets now run too** (2026-08-08) — every Wukong
preset that can be built has been on hardware:

| preset | WNS | test |
|---|---:|---|
| `wukongDualIndependent` | -0.365 | **both clusters `DoAll` 66/66 concurrently** |
| `wukongBram` | — | Hello World from the built-in BRAM image |
| `wukongSmpMinimal 2` | +0.500 | `SmpCacheTest` PASS |
| `wukongSmp 2` | +0.318 | `SmpCacheTest` PASS + `DoAll` 66/66 |
| `wukongFullSmp 2` | +0.285 | `SmpCacheTest` PASS + `DoAll` 66/66 |

`SmpCacheTest` is the meaningful SMP test — `NCoreHelloWorld` only prints
from `cpuID==0`, so it cannot distinguish a working second core from a dead
one. Its `.jop` had never been built; `make -C java/apps/SmpCacheTest`.

The dual needed its SDR cluster moved from 80 to 100 MHz — see
`docs/architecture/dual-subsystem-design.md`, "Phase 2 Resolved", which also
records what that was *not* (IOB packing, the `set_max_delay` violation, the
`sdram_clk` phase shift), each disproved by measurement.

`wukongBram` could not generate a bitstream at all: `wukong_jop_bram.xdc`
constrained a port named `clk_in`, but the generated top's ports are `clk`
and `resetn`. The stale name matched nothing, so both reached implementation
unconstrained and DRC refused (NSTD-1 / UCIO-1) — the same failure mode as
`wukongSdrFull` above, from a different cause. This is now the third build
killed by unconstrained ports; a pre-implementation check that every top-level
port has a LOC would have caught all three.

**Two presets cannot be tested, and should be fixed or deleted:**

- **`wukongDual`** — differs from `wukongDualIndependent` only by
  `interconnect = Some(InterconnectConfig(...))` and
  `monitors = Seq(WatchdogConfig(...))`, and **neither field is read by any
  RTL** (Phase 3 message queues are still "Future"). It also has no `case` in
  `JopTopVerilog`, so it cannot be generated. Building it would produce the
  same hardware as `wukongDualIndependent` under a name implying otherwise.
- **`wukongDualSmp` is misleadingly named** — its `case` maps to
  `wukongDualIndependentSmp`, the *no*-interconnect variant. Neither name
  reaches the interconnect design.
- **`wukongBramFull`** and **`auMinimal`** — no `case` in `JopTopVerilog`, so
  unreachable. Unlike `wukongDual` these look like plain omissions rather
  than unimplemented features.

**Every other preset has now been on hardware, or has no board attached.**
Sweeping `JopConfig`'s definitions against `JopTopVerilog`'s cases is the
cheap way to find this class of gap — it is what surfaced the three
unreachable presets above. Note that all 40 presets *are* covered at config
level by `JumpTableResolutionTest`, including the unreachable ones, so a
green test suite does not imply a preset can be generated, let alone run.
The presets with no board attached are `auSerial`, `max1000Sdram` and
`ep4ce6Sdram` (`minimum` and `simulation` are not board targets).

### Compute units and bytecode implementation

**Implementation coverage, measured 2026-08-05.** Every configurable bytecode
crossed with every implementation it may legally take, against the configs that
some passing DoAll simulation actually selects:

| implementation | covered | gaps |
|---|---|---|
| **Java** | **32 / 32** | none — the default-config sims select every Java handler |
| **Hardware** | **32 / 32** | none — `JopDcuCacheSim` runs `"*" -> "hw"` |
| **Microcode** | **12 / 12** | none — the four dead float handlers were deleted and `lmul` was a config defect (item 22) |

So every implementation that exists is now selected by a passing simulation,
with no gaps in any of the three columns.

**The caveat that matters**: this measures which handlers a config *selects*,
not which the workload *executes*. DoAll passing with `lushr = mc` proves the
build is sound; it does not prove DoAll contains an `lushr`. Closing that would
need per-bytecode execution counters in the simulation — worth doing before
trusting the table as true coverage rather than as a configuration matrix.

<a id="item-17"></a>

### Item 17 — `needs*Compute` predicates understate compute-unit reachability

**`needs*Compute` predicates understate compute-unit reachability.**
`621aac7` used them to skip instantiating unused CUs and regressed the JVM
suite 66/66 -> 56/66; reverted in `eda6de7`. The area win is real (~474 LE
per core, ~5,700 across 12 cores) and worth recovering, but needs a
predicate that asks *"can any dispatch path reach this unit"* rather than
*"is any bytecode set to Hardware"*. Two known ways the current ones
understate it:
- `needs*Compute` is `isHw(...)`, i.e. `impl == Hardware` only. A bytecode
  set to `Microcode` that reaches a CU is invisible. The `lmul` require at
  `JopCoreConfig.scala:353` documents exactly that (`lmul_sw` drives the ICU
  via `sthw`), so the predicates were known incomplete *before* `621aac7`
  relied on them.
- ~~`JumpTable.useAlt` fails open~~ — **fixed** (`useAlt` now throws). It had
  kept the `_hw` (CU) handler when a bytecode was set to `Microcode` with no
  `_sw` alternate. `BytecodeConfig.validate` was supposed to catch that via
  the `NoMicrocode` constraint, but only 12 of the 19 bytecodes lacking a
  `_sw` were marked — `idiv`, `irem`, `fneg`, `i2f`, `f2i`, `fcmpl` and
  `fcmpg` were `JavaOk`, so `mc` passed validation and then silently
  dispatched to a compute unit. (`fneg` turned out to be the opposite case:
  pure microcode already, with no `_hw` variant at all. It needed the
  missing `fneg_sw` label, not a restriction — now fixed, so 18 remain.) Both layers are now correct and
  `JumpTableResolutionTest` pins them against each other.

**The exact dispatch path is still unexplained** — on paper the default
config reaches no CU at all, so removing them should be free. Reproduce with
`JopJvmTestsBramSim` (default config, no board involved); it fails in ~15 min.
Do not re-land the optimisation without that sim passing 66/66.

<a id="item-18"></a>

### Item 18 — Software/microcode fallback coverage is uneven — 18 of 32 configurables

**Software/microcode fallback coverage is uneven** — 18 of 32 configurable
bytecodes have no `_sw` microcode handler, so their only non-hardware path
is the Java trap. Per-operation cycle costs already exist in
`docs/architecture/compute-unit-design.md` (ICU/FCU/LCU/DCU tables); what
follows is the coverage summary.

| group | has `_sw` | no `_sw` (Java trap only) |
|---|---|---|
| int | imul | idiv, irem |
| long | ladd, lsub, lmul, lneg, lshl, lshr, lushr, lcmp | — |
| float | fneg, fcmpl, fcmpg | fadd, fsub, fmul, fdiv, i2f, f2i |
| double | — | all 12 (dadd, dsub, dmul, ddiv, i2d, d2i, l2d, d2l, f2d, d2f, dcmpl, dcmpg) |

All 20 without one default to `Java`, which the jump table turns into
`invokestatic`, and all 20 are now marked `NoMicrocode` so asking for `mc`
is rejected rather than silently dispatched to a compute unit (item 17).

**The silent-misconfiguration part of this is closed**: `useAlt` throws, the
constraint table matches the ROM, and the four bogus float alternates are
gone. What remains is purely how much microcode to write — items 19 and 20.

**Long is fully covered, float has three of nine, double none.**

<a id="item-19"></a>

### Item 19 — Write the missing `_sw` microcode handlers

**Write the missing `_sw` microcode handlers.** Goal: a microcode fallback
for *all or most* configurable bytecodes, so that any board can trade area
for cycles without dropping to the Java trap.

**Coverage vehicle: `ep4cgx150McFallback`** — selects 11 of the 12 `_sw`
handlers and is the regression build for this item.

Eleven, not twelve, because **`imul_sw` and `lmul_sw` are mutually
exclusive** and no single build can run both. `imul_sw` is a self-contained
shift-add loop that touches no compute unit, and it is selected by
`imul = mc`. `lmul_sw` computes its partial products on the ICU's
`imul`/`imul_wide`, and that multiplier is only built when `imul = hw`
(`IntegerComputeUnitConfig.withMul` is `needsIntMul`). So `imul = mc` gives
`imul_sw` but breaks `lmul_sw`, and `imul = hw` fixes `lmul_sw` but stops
selecting `imul_sw`. This preset takes `imul = hw` and covers `lmul_sw`.
`imul_sw` is covered instead by every default-config sim, since `imul`
defaults to `Microcode` — so the *set* of tests covers all 12 even though no
single one does. It paid for itself on its
first run by surfacing the `lmul` configuration defect (item 22). Run it with
`make -C fpga/qmtech-ep4cgx150-sdram full-mc-fallback`, or in simulation via
`JopJvmTestsMcFallbackSim` (nightly in CI). **New handlers must be added to
it**, or they get no coverage at all: JOP's defaults select very few `_sw`
handlers, which is how `lmul` went years without anything executing it.
Six of the eight gaps outside the double group are small; the double group
is item 20.

Per-operation cycle costs for the alternatives are already tabulated in
`docs/architecture/compute-unit-design.md`.

~~`fneg`~~ — **done**, and it cost nothing: its default handler was already
pure microcode (`ldi 0x80000000; xor`), it simply lacked the `fneg_sw` label
that `useAlt` looks for. Two labels on one address, ROM byte-identical.

~~tier 1 (`fcmpl`, `fcmpg`)~~ — **done**. `fcmpl_sw`/`fcmpg_sw`, 97 ROM
words for the pair, sharing one body that differs only in the NaN result.
**No new `ldi` constants**: the serial pool is the binding one at 30 of 32
and `ldi` is a hard 5-bit field, while the ROM had ~2000 words free, so
0x7FFFFFFF and 0x7F800000 are derived from constants already present
(`-1 >>> 1`, `(255 << 24) >>> 1`) rather than added. Verified against IEEE
semantics over 1152 cases (NaN, ±0, ±Inf, denormals, non-canonical NaN
payloads), by `JopJvmTestsMcFcmpSim` (DoAll 66/66 in simulation with both set
to `mc`), and **on hardware** — `colorlightI5Sdram` now selects them, so the
i5 runs DoAll 66/66 with the microcode actually executing. That preset
change is the point: a build left on the default Java path never executes
these handlers, so "DoAll passed on hardware" would otherwise have said
nothing about them.

The i5 is a natural home for that because it has no FCU, so its alternative
was the ~600-cycle SoftFloat32 trap against ~30 cycles of microcode. It is
**not** the only one: `ep4cgx150McFallback` selects the same handlers plus
every other working `_sw`, and passes DoAll 66/66 on EP4CGX150 hardware
(worst-case setup slack +0.970 ns). Two boards, so unplugging either does
not lose the coverage.

| tier | bytecodes | effort | why |
|---|---|---|---|
| 2 | `i2f`, `f2i` | moderate | normalise/denormalise: count leading zeros, shift, assemble exponent |
| 3 | `idiv`, `irem` | moderate | restoring division loop. Lowest value of the three: the ICU already does it in ~36 cycles and the Java trap in ~1300, so this only pays for a board that wants neither |

Each one is done when: the `_sw` handler exists in `asm/src/jvm.asm`, its
`BytecodeEntry` constraint moves `NoMicrocode` -> `JavaOk`, the coverage
expectation in `JumpTableResolutionTest` is updated (it is deliberately
pinned so this cannot pass unnoticed), and DoAll passes 66/66 with that
bytecode set to `mc` — not merely with the default config, which would not
execute the new handler at all.

20a. **`lmul` in microcode on a board with no multiplier — a gap, but not
worth closing.** Raised because `imul = mc, lmul = mc` is rejected, which
looks like a hole in "a microcode fallback for all or most bytecodes".

It is a gap in the *matrix*, not in *capability*. The zero-multiplier
configuration is the **default** — `imul` defaults to `Microcode` (a
self-contained shift-add loop needing no CU) and `lmul` to `Java` — and it
passes DoAll 66/66 in `JopJvmTestsBramSim`. So a board with no ICU
multiplier already has a working `lmul`.

Closing the gap means a CU-free `lmul_sw` built from three shift-add
products. `imul_sw` alone is ~775 cycles for one 32x32, so three partial
products is **~2300+ cycles against the Java trap's ~1200** — the microcode
version would be roughly twice as slow as what it replaced. That inverts the
usual argument for microcode fallbacks, which exists because the Java trap is
normally 20-100x worse.

Caveat on the comparison: the ~1200 figure is from
`compute-unit-design.md` and Java `f_lmul` computes its partial products with
the `imul` *bytecode*, so its real cost tracks whatever `imul` is set to. It
has not been measured with `imul = mc`. The direction is clear enough to not
act on, but the number is not load-bearing — measure before revisiting.

<a id="item-20"></a>

### Item 20 — Decide whether the double group gets microcode at all

**Decide whether the double group gets microcode at all** — measure before
committing. All 12 (`dadd`, `dsub`, `dmul`, `ddiv`, `i2d`, `d2i`, `l2d`,
`d2l`, `f2d`, `d2f`, `dcmpl`, `dcmpg`) currently reach only SoftFloat64 at
~3000-5000 cycles, against ~14 cycles on the DCU. Microcode would land
somewhere between, but it is a large piece of work for a group most JOP
applications use rarely.

**ROM budget is not the constraint for items 19 or 20's smaller tiers.**
The ROM is 4096 words (`pcWidth = 12`) and the largest variant, serial,
uses 2055 — so ~2040 words are free, and `pcWidth` can go to 16 if it ever
is the constraint. For scale: all 13 existing `_sw` handlers together are
176 words, the largest being `lsub_sw` at 38. Tier 1-3 would add perhaps
150-250. A full software double group is the only thing on this list large
enough to make ROM size worth checking again — the DCU's 12 dispatch stubs
are 90 words, but real SoftFloat64-equivalent microcode is a different
order.

Unlike item 19 this is a genuine question, not a task: the honest answer may
be that double stays Java-trap-or-DCU. Worth deferring until there is an
application benchmark (item 11) that shows whether double is on any hot
path. `dcmpl`/`dcmpg` are the exception — they are as cheap as their float
counterparts in tier 1 and could be done with them.

<a id="item-22"></a>

### Item 22 — ~~Five `_sw` handlers exist but do not work — RESOLVED. It was two~~

~~**Five `_sw` handlers exist but do not work**~~ — **RESOLVED**. It was two
different faults, and only one was in microcode.

**`lmul_sw` was never broken.** It needs the ICU's *multiplier*, and
`IntegerComputeUnitConfig.withMul` is `needsIntMul`, i.e. `imul == Hardware`
specifically. The `require` guarding it checked `needsIntegerCompute`
(`isHw("imul","idiv","irem")`), which `idiv = hw` satisfies on its own — so
`idiv = hw, imul = mc, lmul = mc` passed validation and then built an ICU
with **no multiplier at all**. `sthw 3` had nothing to dispatch to and lmul
returned garbage: 6 DoAll failures, the float and double ones because
SoftFloat32/64 call lmul for mantissa multiplication.

With `imul = hw` the same handler passes DoAll 66/66. The `require` now
checks `needsIntMul` and says why `idiv/irem = hw` is not sufficient.

Worth recording how this looked from outside: a handler that had never been
executed, documented as broken, produced exactly the failure signature of a
broken handler — and was fine. The evidence that it was "broken" and the
evidence that it was "fixed" were both inference rather than measurement.

**`fadd_sw`/`fsub_sw`/`fmul_sw`/`fdiv_sw` were genuinely dead** — I/O
handlers for the BmbFpu peripheral, writing to 0xF0-0xF3 which stopped
decoding when that peripheral was removed. Deleted, along with their
`fpu_*` address constants. Those four bytecodes are now `NoMicrocode`, which
is true rather than merely enforced.

Net: `altEntries` goes 16 -> 12, and all 12 are real. "Has an alternate" and
"has a *working* alternate" mean the same thing again, so
`JumpTableResolutionTest`'s `noSw == noMc` invariant is now sufficient as
well as necessary.

### Boards

<a id="item-21"></a>

### Item 21 — Colorlight i5 is EBR-bound in BRAM-only builds, not logic-bound

**Colorlight i5 is EBR-bound in BRAM-only builds, not logic-bound** — with
64 KB of on-chip main memory it sat at 71% block RAM against 30% of LUTs.
Moving main memory to SDRAM inverts that to **21% block RAM / 42% LUTs**, so
SMP and extra compute units are now worth trying; they were not possible
while a single core consumed 71% of the EBR, which is why the board went to
SDRAM before anything else. It is also the only board on the open-source
toolchain, so it is the natural place to notice yosys/nextpnr-specific
breakage before it reaches the vendor flows.

### Coupling — read before sequencing any of this

**Items 1, 2 and 11 share one missing artefact: a multi-core application that
allocates.** They look independent and are not.

- Item 1 (shared card table) is ~1-2 days, and the *test* is the bulk of it. The
bug is "a young object reachable only from a tenured object written by another
core is collected while live". Demonstrating that needs two cores allocating
and storing cross-generation references — i.e. exactly such an application.
- Item 2 is vacuous today *because* no such application exists:
`JopIhluGcBramSim` falls back to a single-core app, so core 1 never boots.
- Item 11 needs the same thing as its first slice, before it grows into a
benchmark that can answer the arbiter and cache questions.

So build the application once and it serves all three: it makes item 2's test
meaningful, gives item 1 something that can fail before the RTL changes, and is
the beginning of item 11. Doing them in the other order means writing a
throwaway harness twice.

**Third coupling**: item 20 (does double deserve microcode?) is not answerable
without item 11 either. It is a cost/benefit question about a group of bytecodes
whose usage frequency nobody here has measured, and writing ~12 handlers to find
out is the expensive way round. Item 19's three tiers are *not* coupled to it —
they are small enough to be worth doing on their own merits.

**Second coupling, weaker**: items 4 and 6 may be the same defect. The copy
phase's problem is placement — the handle table is far larger than the cache and
a handle is exactly one cache line. `compactAndSweep` walks `useList` the same
way, so the unexplained major-GC constant may have the same cause. Check that
before treating them as separate projects.

---

**Confirmed with a number, 2026-08-18.** The SDRAM stage uses **12/56 DP16KD
(21 %)**; the BRAM stage, for only **64 KB** of main memory, uses **40/56
(71 %)** with 16 blocks free. So the BRAM stage cannot be grown enough to hold
an application benchmark — `JbeBench.jop` alone is 64 KB of code before any
heap — which is why the i5 has to run its benchmarks over SDRAM.

<a id="item-37"></a>

### Item 37 — The method cache dominates real memory traffic — 62 % of DoApp's BMB transactions

**The method cache dominates real memory traffic.** `DoAppAcacheSweepSim`
attributes every BMB transaction to the memory-controller state that issued
it, over Kfl + UdpIp + Lift: **bytecode fill 62.3 %**, direct access 18.5 %,
statics 15.0 %, and ALL array traffic 4.2 %. If real-application memory cost
is worth attacking, it is here.

**MEASURED IN TIME 2026-08-19 (`DoAppMemTimeSim`), and the headline above is
wrong in two ways.** `memBusy` is what stalls the pipeline, so counting the
cycles it is high and attributing each to `debugMemState` converts transactions
into time. Every state is named, so nothing is unattributed.

**First correction: the 62 % was Kfl alone, not "Kfl + UdpIp + Lift".**
`DoAppAcacheSweepSim` samples a 12 M-cycle window at the default 100 MHz, and
DoApp calibrates each benchmark to one simulated second — so the window never
reaches UdpIp or Lift. Dropping the declared clock to 5 MHz shrinks the
calibration 20x and fits all three.

**Second correction: the mix is completely different per benchmark.**

| stall share | Kfl | UdpIp | Lift |
|---|---|---|---|
| **bytecode fill** | **65.9 %** | 45.1 % | **14.6 %** |
| handle deref + element + bounds | 8.6 % | 35.9 % | **78.1 %** |
| statics | 25.5 % | 13.5 % | 7.2 % |
| idle/direct | **0 %** | **0 %** | **0 %** |
| stalled cycles | 13.1 % | 11.2 % | 8.1 % |

Weighted over all three, bytecode fill is **47.7 %** of stall cycles, handle and
array indirection **33.0 %**, statics **17.7 %**.

**So the method cache is the right target for Kfl, half the story for UdpIp,
and the wrong target for Lift**, where handle dereference and array element
access dominate at 78 %. Optimising it would have looked like a 62 % win and
delivered nothing on a third of the suite.

**Three things worth keeping:**

1. *Direct access is free.* 18-38 % of transactions issue from `IDLE`, and
  `READ_WAIT`/`WRITE_WAIT` are explicitly not-busy states (matching VHDL
  rd1/wr1) — the core does not wait. **Zero** stall cycles on every benchmark.
  Strike it from the attack surface.
2. *Lift's few bytecode fills are enormous:* 2,595 transactions for 151,485
  stall cycles, **58.4 cycles each**, against 1.40 for Kfl. Rare misses on large
  methods, not a steady stream. A bigger cache and a faster fill are different
  fixes; Lift needs the former, Kfl the latter.
3. *Statics cost exactly 2 cycles every time* on all three — one in
  `GS_READ`/`PS_WRITE`, one in `LAST`. A predictable, uniform 17.7 % that no
  cache currently touches.

**MEASURED ON SDR 2026-08-19 (`DoAppMemTimeSdrSim`), and the BRAM caveat was
justified — one conclusion above does NOT survive.** Same instrumentation
(`MemProfile`), real W9825G6JH6 timings at a real 80 MHz, Kfl:

| Kfl | BRAM | **SDR 80 MHz** |
|---|---|---|
| stalled cycles | 13.1 % | **53.9 %** |
| bytecode fill | 65.9 % | 58.7 % |
| **idle/direct** | **0 %** | **18.4 %** |
| statics | 25.5 % | 17.7 % |
| handle + element + bounds | 8.6 % | 5.2 % |
| cyc/txn (bytecode fill) | 1.40 | 8.68 |

**The harness is validated:** 53.9 % independently reproduces item 38's 53.8 %
for Kfl, measured a completely different way (BRAM-vs-SDR throughput ratio
there, cycle attribution here).

**"Direct access is free" was a BRAM artefact.** It costs 0 % of stall on BRAM
because `READ_WAIT`/`WRITE_WAIT` complete next cycle, and 18.4 % on SDR where a
read is ~8.5 cycles. Striking it from the attack surface, as recorded above,
would have been wrong on every real board.

The clock cannot be scaled down for an SDR run the way it can for BRAM:
`clockFreqHz` feeds both the controller's ns-to-cycle conversion and DoApp's
one-second calibration, so lowering it makes memory artificially cheap. Kfl
alone therefore takes 272 M cycles; UdpIp and Lift need either a much longer
run or the hardware counters (item 50), which is the better route now they
exist.

<a id="item-50"></a>

### Item 50 — ~~Memory-stall profile measured on real memory, on hardware — DONE~~

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

<a id="item-51"></a>

### Item 51 — ~~The method cache is capped at 2 KB~~ — FIXED. Default is now 8 KB/64 blocks: **+35 % Kfl, +27.7 % UdpIp**, validated on FOUR BOARDS

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

<a id="item-38"></a>

### Item 38 — ~~Measure DoApp's memory-stall fraction~~ — **ANSWERED 2026-08-18: 34-55 %**

`DoAppBramSim` runs the same binary against BRAM and compares per MHz with the
EP4CGX150 SDR hardware figures:

| benchmark | BRAM /MHz | SDR @80 /MHz | **stall share** |
|---|---|---|---|
| Kfl | 209.6 | 96.8 | **53.8 %** |
| UdpIp | 97.4 | 44.0 | **54.8 %** |
| Lift | 240.2 | 158.6 | **34.0 %** |

**Real applications lose a third to a half of their throughput to memory
latency.** So memory-system work IS worth doing on real code — which was the
open question — and this is the ceiling on all of it: roughly **2x on Kfl and
UdpIp, 1.5x on Lift**, if memory latency were eliminated entirely.

Internally consistent with what was already known: Lift has the LOWEST stall
share, matching the cache A/B where Lift gained most from the object/array
caches (its working set fits them), while Kfl and UdpIp stay partly
memory-bound even with caches and showed the per-MHz gain at 36 MHz.

**Method note worth reusing.** DoApp calibrates each benchmark to one *simulated*
second, which at a declared 100 MHz is 100 M cycles apiece — the first attempt
ran out at 400 M cycles without finishing Kfl. The harness now takes a declared
`clkMhz`, and lowering it to 5 shrinks the calibration target proportionally
**with no effect on the per-MHz result**: reported rate is `N x clkFreq /
cycles`, so `rate / (clkFreq/1e6)` is `N x 1e6 / cycles` — iterations per
million cycles, with `clkFreq` cancelled.

**Caveats.** BRAM is not zero-latency (single-cycle accept, next-cycle
response), so the true stall share is slightly higher. The figures are for the
EP4CGX150 SDR path; a DRAM board with an L2 will differ.

<a id="item-39"></a>

### Item 39 — The L2 hit path is serial — 3 cycles per hit, 58-61 % of the DRAM access interval

**`LruCacheCore` serves every request through one FSM, hit or miss.**
`IDLE -> TAG_COMPARE -> CHECK_HIT` is 3 cycles for a read hit, 4 with
`WRITE_HIT`. On `jbe.Scale` that floor is 28 cycles/access with no misses at
all, against 48.7 measured on DDR3 and 46.5 on DDR2 — **58 % and 61 % of the
whole interval is the cache servicing hits one at a time.**

The MSHR work made MISSES concurrent; hits were left strictly serial. Worth
at most 48.7 -> 28 on that benchmark, and gated by item 38 for real code.

<a id="item-40"></a>

### Item 40 — A leaner MSHR entry — each holds a full cache line of write data a read miss never uses

**Each MSHR entry stores a whole cache line of write data** (128 bits
on DDR3, 256 on DDR2) even though a read miss needs none of it and only a
partial write miss does. That is what makes **8 MSHRs unroutable at 8 cores**
on the XC7A100T (94 % LUT at synthesis, congestion level 5, 27,257 node
overlaps).

Since 4 MSHRs is already an under-provision for 8 cores — each core holds one
outstanding BMB transaction — the measured 4.38x is a FLOOR for the approach,
not its best. Storing write data only for write entries, or merging into the
line at allocation, is the obvious fix.

<a id="item-41"></a>

### Item 41 — Neither 8-core DRAM build closes timing, MSHRs or not

**8-core builds do not close timing on either DRAM board**, and did not
before the MSHR work either: DDR2 -3.056 ns (was -3.059), DDR3 -0.501 ns
blocking and -0.465 ns with MSHRs. `jbe.Scale`'s `CHECK` is bit-identical
across every build, which is what makes measuring on them defensible — but
they are **measurement vehicles, not shippable bitstreams**.

The DDR2 case cannot be fixed by lowering the clock: `mem_if_clk_mhz` 150.0
at half rate floors the system clock at 62.5 MHz against the ~61 MHz needed.
The shared root cause is the `BmbMemoryController -> cmdFifo` path — item 5.

<a id="item-42"></a>

### Item 42 — Secondary-hit merging is not implemented — a request to a line being filled replays

**Secondary hits replay rather than attach.** A request to a line
already being filled is bounced back through `IDLE` instead of joining the
MSHR that is already fetching it. Deliberate: the one-in-flight-miss-per-set
rule makes merging an optimisation rather than a correctness requirement, and
leaving it out kept the first implementation tractable. Pure throughput, no
correctness impact.

<a id="item-43"></a>

### Item 43 — ~~Colorlight i5 SDRAM stage is silent~~ — FALSE ALARM, retracted same day

**Retracted 2026-08-18, hours after being raised.** The i5 SDRAM stage is fine.
It builds (49.65 MHz PASS at 40), programs, boots, and runs both benchmarks:

| | |
|---|---|
| `jbe.DoApp` | Kfl **5580**, UdpIp **2547**, Lift **7713** 1/s at 40 MHz |
| `jbe.Scale` | **287 kacc/s** single core, `CHECK 1645838336` — the same value every other board produces |

**How the false alarm happened, because it is worth not repeating.** The test
sequence was: program, run a download, download fails, then listen raw at a
sweep of bauds — silence at every one. But **the failed download had already
consumed the ready handshake**, which is precisely the trap documented the same
morning in `fpga/scripts/run_bench` and the Wukong board notes: *a previous
download attempt makes a live board look completely silent.* Reprogramming and
listening immediately gives a clean `0xAA` stream.

The download failure underneath it was mundane and also mine: `download.py`
output is block-buffered when piped, so `timeout` killed it and discarded the
buffer, producing no diagnostics at all. `fpga/scripts/run_bench` now runs it
with `python3 -u`.

**Two lessons, both already written down and both ignored in the moment:**

- **Order matters when diagnosing a silent board.** Reprogram immediately before
  listening, or the handshake state confounds the measurement. Writing the trap
  into a script does not help if the debugging is done by hand around it.
- **Silence versus garbage is a real discriminator** — and it only means what it
  is supposed to mean if the board has not been disturbed first.


<a id="item-44"></a>

### Item 44 — The compute floor C is per-configuration, so single-core latency decompositions are unsafe

**Found 2026-08-18** by trying to falsify the transaction-cost model on the
Colorlight i5 — the only board with a **32-bit SDRAM** (`BmbSdramCtrlWide`, one
op per BMB beat against the 16-bit boards' two), and therefore the only
available discriminator.

The test: subtract the compute floor C from measured cycles/access, divide by
transactions and ops-per-beat, and check the implied **ns per SDRAM operation**
is roughly a device property. It is not.

| board | SDRAM | MHz | kacc/s | cyc/acc | implied ns/SDRAM op |
|---|---|---|---|---|---|
| Colorlight i5 | 32-bit, 1 op/beat | 40 | 287 | 139.4 | **135.0** |
| Wukong SDR | 16-bit, 2 ops/beat | 100 | 621 | 161.0 | **38.9** |
| EP4CGX150 SDR | 16-bit, 2 ops/beat | 36 | 188 | 191.5 | **154.6** |

**The two boards that share a memory width AND a controller disagree by 4x**, so
the premise collapses before the i5 is considered — the test cannot
discriminate. What it does establish is that **C = 90.3 cycles/access is not
portable.** C was measured on one core configuration (the default BRAM harness);
`ep4cgx150Serial` and the Wukong SDR preset have different bytecode maps and
compute units. Subtracting the wrong C throws the entire remainder off.

**Consequence: do not derive per-operation costs from a single C.** Any
decomposition of the form `(cycles/access − C) / transactions` needs C measured
for *that* configuration. `JbeScaleBramSim` already does it; it just has to be
run per core config instead of once.

**What this does NOT invalidate:** the eight-core aggregate figure in
[the MSHR plan](architecture/nonblocking-cache-mshr-plan.md) —
`36.2 cyc/access / 9.09 txns / 2 halves = 1.99 cyc per 16-bit op` — uses no C at
all. It is a service RATE, not a latency decomposition, and stands.

**The i5 is healthy and is now a genuine third data point**: different fabric
(ECP5), different toolchain (yosys/nextpnr/ecppack), different memory width. Per
MHz on `jbe.DoApp` it beats every other board measured — figures in
[`../java/apps/JbeBench/README.md`](../java/apps/JbeBench/README.md), not
repeated here.

Two effects are confounded and separating them is the open work: a lower clock
makes fixed-ns memory latency cost fewer cycles (already documented for 36 vs
80 MHz on one board), and the 1:1 SDRAM halves operations per beat. The i5 at
40 MHz beats the EP4CGX150 at 36 MHz by 14-18 %, which is the right order for
the ops-per-beat effect but is not proof of it.


<a id="item-52"></a>

### Item 52 — The Java tools duplicate the hardware config by hand, and the copies are silently stale

**How this surfaced.** Item 51 changed one number in one place —
`JopCoreConfig.jpcWidth` 11 -> 13, `blockBits` 4 -> 6. Documenting that change
turned up **three** hand-maintained copies of the same geometry in the Java
tree, none of which moved with it, and none of which anything checks:

| copy | file | held | should be |
|---|---|---|---|
| `CACHE_BLOCKS` | `JOPConfig.java` | 16 | 64 |
| `CACHE_SIZE_WORDS` | `JOPConfig.java` | 1024 | 2048 |
| `getMaxMethodSize()` | `JOPModel.java` | `return 512;` | unclear — see below |

The first two are corrected as of 2026-08-20. **That is a patch, not a fix**,
and the next configuration change will break them again.

**Why it is worth an item rather than a shrug.** `CACHE_SIZE_WORDS` was
*already* wrong before item 51 — it declared 1024 against a hardware 512. That
error had been in the tree for years and was harmless by luck: it overstated the
cache, but in the direction that made WCET analysis pessimistic. After item 51
the same stale 1024 understates a 2048-word cache, and a WCET bound computed
from it would be **unsound** rather than merely loose. The value did not change;
the direction of its wrongness did, because the hardware moved past it. A
duplicated constant is not a small problem in a WCET tool — being conservative
by accident is not the same as being conservative.

Nothing in the current build flow reads any of them, which is the only reason
this has been survivable: `CACHE_BLOCKS` and `CACHE_SIZE_WORDS` are registered
in `jopOptions[]` but the `JOPConfig` constructor never reads them into a field
and there is no getter, and `getMaxMethodSize()` has no caller anywhere in
`java/tools`. They are dead today and wrong today. The failure mode is someone
wiring up the WCET analyser later and inheriting the drift without ever seeing
it happen.

**`getMaxMethodSize()` is left alone deliberately.** It carries its own
`// TODO get this from cache config` and its unit is ambiguous: JOP returns
512, which matched the *old* cache capacity in WORDS, while `JVMModel` and
`JamuthModel` both return 65535, which is the classfile limit in BYTES. One of
those readings is wrong and the code does not say which. Guessing a new number
for a WCET input is worse than leaving a stale one next to a TODO, so it stays
until someone establishes the unit.

**Note the constraint inverted.** Method size used to be bound by the cache
(`min(1024, 512) = 512` words); it is now bound by `METHOD_SIZE_BITS`
(`min(1024, 2048) = 1024`). Any code or analysis carrying "the cache is the
limit" is now wrong — see
[architecture/constant-dependencies.md](architecture/constant-dependencies.md)
section 3.

#### What to build instead

Two configs, not one hand-edited set:

1. **A generated config per preset.** `JopCoreConfig` is already the single
   source of truth in the SpinalHDL tree; emit the Java-side constants from it
   at generation time, the way `DramPllGen` now emits the PLL rather than
   having it hand-edited in `dram_pll.vhd` (see the EP4CGX150 PLL trap in
   section 8 — same species of bug, already solved once here).
2. **A checked-in default** for tools run without a preset, so `java/tools`
   still builds and runs standalone.

[architecture/configuration-driven-plan.md](architecture/configuration-driven-plan.md)
section 3c already proposes exactly this (`JopSimConfig.java` generated from
`JopCoreConfig`, then `JopSim.java` and `JOPConfig.java` importing from it).
That plan predates item 51 and was never executed; item 51 is the evidence for
why it should be. The plan's own example values were themselves stale (`// 16`,
`// 512`) and were corrected on 2026-08-20 — a plan to stop hand-maintaining
constants, whose constants had to be hand-maintained.

**Minimum acceptable outcome** if the full generator is too much: a build-time
cross-check that fails loudly when the Java copies and `JopCoreConfig` disagree.
The drift is only dangerous because it is silent. `constant-dependencies.md`
lists several other pairs with "NO LINK" against them (`METHOD_SIZE_BITS` vs
`MAX_BC`, `pcWidth` vs `Jopa.ADDRBITS`, `ramWidth` vs `Jopa.RAM_LEN`), so a
general checker would pay for itself well beyond the method cache.


<a id="item-53"></a>

### Item 53 — REGRESSION: the 8 KB method cache default broke 4-core Wukong SMP fit

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


<a id="item-54"></a>

### Item 54 — Statics are Kfl's largest stall category (41 %) and no cache touches them

**Promoted out of [item 51](#item-51) on 2026-08-23**, which fixed the category
that used to dominate. This is what the profile looks like underneath it.

On the A-E115FB with the 4 KB/32-block cache, Kfl's stall decomposes to
**statics 41.2 %** (692 cycles per iteration), `idle/direct` 38.8 % (652,
[item 55](#item-55)), indirection 213, bytecode fill 123. Those absolute figures
are the load-bearing ones: item 51 showed every category except bytecode fill was
unchanged **to the cycle** across the geometry change, so the shares moved only
because the total shrank.

**Statics are a uniform 7-18 % of stall on every board and every workload**
measured in item 50 — before the method cache fix, when they were third-order.
They are now first.

**Why they are addressable.** The static region is small and its size is known at
link time, so it can live in on-chip RAM instead of going to DRAM. Nothing caches
it today: the object cache keys on handle+offset and the array cache on array
elements, and a `getstatic`/`putstatic` is neither.

**What is not yet established** — and should be, before any RTL:

- **How big the region actually is** across the benchmark set and DoAll. It is
  asserted to be small; it has not been counted.
- **Whether the cost is latency or bandwidth.** 692 cycles/iteration is a
  category total, not a per-access cost, and the access count was never
  extracted. A handful of very hot statics behaves differently from a large
  working set, and only the first is fixed by a small BRAM.
- **The SMP story.** A per-core static RAM is wrong — statics are shared mutable
  state and JOP's memory model expects them coherent. A shared arbitrated port is
  another consumer on the arbiter, which items [5](#item-5) and [31](#item-31)
  already identify as the clock ceiling. This may make the multicore case a
  different design from the single-core one.

**Suggested first step, no RTL:** count static accesses and distinct static
addresses per benchmark, from a simulation trace. That distinguishes the three
cases above and is the same discipline that made item 51 tractable — the sweep
before the design.

<a id="item-55"></a>

### Item 55 — The core stalls on writes whose result it never uses (`idle/direct`, 39 % of Kfl stall)

**Promoted out of [item 51](#item-51) on 2026-08-23.** Kfl's second-largest
category after statics: `idle/direct` is **38.8 %** of stall, 652 cycles per
iteration on the A-E115FB, and 16-42 % of stall across the boards in item 50.

**The mechanism is known and is a genuine defect of the memory path, not a
tuning parameter.** `WRITE_WAIT` in `BmbMemoryController` is *busy until
`rsp.valid`* — the core blocks until the write is acknowledged. But
`putfield`, `iastore` and `putstatic` are **unconditionally write-through**
(`BmbMemoryController:1101-1104` issues the BMB write regardless of tag hit;
the "only on tag hit" condition gates the cache UPDATE, not the write). So the
core waits for a result it does not consume.

**What a fix needs, and why it is not a small change:**

- **Read-after-write forwarding.** Once writes are posted, a subsequent read of
  the same address must see the buffered value rather than stale memory. This is
  the part that makes it a correctness change rather than a latency change.
- **An SMP coherence story.** Posted writes are visible to the issuing core
  before other cores, which is exactly the class of bug that cost days in the
  global-lock work ([the lock is not reentrant](#item-1) presented as corruption,
  not deadlock). `CmpSync`, the card-marking barrier and the GC's cross-core root
  scan all assume writes have landed.
- **A bound for WCET.** A write buffer that can be full turns a fixed stall into
  a variable one; the analysis needs a depth and a drain rate.

**Not yet established:** how much of the 39 % is actually the write wait. The
`idle/direct` counter aggregates write stalls with other direct-path idle
cycles, and nothing has separated them. **Do that first** — it sets the ceiling
on the whole exercise, and item 51's history is that the category names in this
profile do not always mean what they sound like.


<a id="item-56"></a>

### Item 56 — WBNI: derive the hardware configuration from the application, instead of picking a preset

**Raised 2026-08-23.** Build the core for the program it will run: analyse the
Java application, work out what it actually needs, emit a `JopConfig`, then
build the FPGA. Light on doubles, leave the DCU out. The flow would be

```
develop on host JDK/sim -> analyser -> .jop + JopConfig for this target
                        -> FPGA build -> remote debug if needed
```

**This is worth recording because most of the machinery already exists.**

- **JOPizer already walks the whole application.** It is the linker: it visits
  every class and method, so the bytecode usage and the method-length
  distribution are both in its hands already. It emits `code_length` per method
  in the `.jop.txt` dump — that is the input the method-cache geometry needs, and
  it is what `docs/architecture/tuning-guide.md` already tells you to grep.
- **`BytecodeConfig` already encodes which CUs may legally be dropped.** Every
  one of the 32 entries carries an `ImpConstraint`: `Asm` (JOPizer keeps the
  bytecode), `JavaOk` (a microcode `_sw` handler exists), `NoMicrocode` (only
  Java or hardware). That is the analyser's safety table, already written and
  already enforced — `bc=double:mc` is refused today with "dadd: mc is invalid —
  no SW handler exists".
- **The knobs are already CLI-addressable**: `bc=<key>:<impl>`, `mcache=`,
  `l2sets=` on `JopTopVerilog`.
- **The measurement harnesses already answer the sizing questions per app**:
  `MethodCacheSweepSim` counts misses per geometry, `ScaleL2` sweeps L2 capacity,
  and `docs/analysis/wukong-utilization-sweep.md` holds the per-feature LUT
  costs.
- **[Item 52](#item-52) is the same idea pointing the other way** — generate the
  Java tools' config FROM the preset. Both want one source of truth instead of
  hand-copied constants; doing either should consider the other.

**A manual proof of concept exists.** On 2026-08-23, by hand: the benchmark set
is integer, so the DCU is dead weight; dropping it on a 4-core Wukong freed
~17,240 LUTs and bought the 64-block method cache, taking Kfl's miss rate from
16.6 % to 0.1 % and timing from -0.043 ns to **+0.069 ns MET**. That is exactly
what the analyser would have concluded, and it took a day of measurement to
reach by hand.

**What is missing:** JOPizer emits no bytecode histogram (nothing in
`java/tools/src` counts opcodes), there is no dynamic frequency data, and
nothing emits a config.

**The traps, which are the reason this is an item and not a weekend:**

- **Static presence is not need, and static absence is not safety.**
  [Item 17](#item-17) is precisely this failure: the `needs*Compute` predicates
  under-approximated CU reachability and cost 10 JVM tests (66 -> 56) before
  being reverted. An analyser that concludes "no doubles" from the application's
  own bytecodes can be wrong via library code, `JVMHelp`, GC and exception
  paths.
- **Frequency, not presence.** One `dmul` on an error path should not buy a DCU;
  one in a hot loop should. A boolean analysis gets this wrong in both
  directions, so it needs counts — which means a profile or at least a call-graph
  weighting, not a grep.
- **Dropping a CU is only safe where the fallback is both legal AND covered.**
  `ImpConstraint` gives legality. Coverage is [item 18](#item-18), and it is
  uneven: `lmul_sw` went years unexecuted with a `require` checking the wrong
  predicate. A generated config must be validated by the JVM suite, not just
  elaborated.
- **Costs are per-part.** The LUT figures above are XC7A100T. A Cyclone IV LE
  and an ECP5 slice are not the same currency, so the cost table has to be
  per-family or the analyser will make confident wrong trades.
- **It ties the bitstream to the application.** Fine for a fixed embedded
  deployment, which is JOP's normal case, but it means changing the app can mean
  re-running P&R — and the fallback of "build the generous preset" must stay
  available.

**FIRST STEP DONE 2026-08-23 — `OpcodeStats`, a JOPizer visitor.** Every link
now writes `<app>.jop.stats.txt`: the method-length distribution, the blocks
each method would consume at 128/256/512/1024 B, and a per-opcode histogram.
A report, not a config generator, so it cannot make a wrong trade.

It validates against numbers reached by other means: JbeBench median **9 B** and
max **882 B** match what was previously grepped out of `.jop.txt`, and the block
table independently confirms the 512 B geometry policy from the application side
— 962 of 963 methods fit one 512 B slot, against 890 at today's 128 B where 73
methods burn extra tag slots and the worst needs 7. The sweep reached 512 B from
MISS COUNTS; this reaches it from the LENGTH DISTRIBUTION.

**It also immediately demonstrated why the analyser is the hard part.** JbeBench
contains double opcodes — one `dsub`, four `ddiv`, ten `dcmpl`, several
conversions — in linked library code the benchmarks never execute. A naive
"does the application mention doubles?" rule would have kept ~19,800 LUTs of DCU
at four cores, when dropping it is measurably correct (DoAll 66/66 without it).
**Static presence is not need. Absence is the only trustworthy signal.**

Deliberately emits RAW COUNTS and no conclusion: deciding what may replace a
bytecode needs `BytecodeConfig`'s `ImpConstraint` registry, which is in Scala,
and copying it into the Java tools would create exactly the drift item 52
tracks.

**Remaining, and it is the bulk of the work:** a FRAMEWORK for measuring the
existing and future hardware set — not a one-off analyser. The pieces that exist
today (`MethodCacheSweepSim`, `ScaleL2`, `DoAppPerf`, the utilization sweep,
now `OpcodeStats`) were each built for one question and are driven by hand.
Turning "which CUs does this application need" into an answer means running a
matrix of configurations against an application and comparing, repeatedly, as
boards are added. **Preference: build it in Java** — the toolchain, JOPizer and
the applications are already Java, so the analysis should live with them and
drop to Scala only where it must (the config registry, elaboration).

Note what `OpcodeStats` still cannot do, and the framework must: it is static,
so it cannot distinguish an error path from an inner loop; and it counts
everything JOPizer linked, not what is reachable. Both need execution counts,
which is a simulator or hardware-counter job, not a linker job.


<a id="item-57"></a>

### Item 57 — The XDC/QSF generators exist and NOTHING USES THEM — constraints are still hand-written

**Raised 2026-08-23**, after a summary sent the wrong console port and cost an
hour. `jop.generate.XdcGenerator` and `jop.generate.QsfGenerator` both exist,
both take a `JopConfig`, and both resolve pins through `PinResolver`. Neither
is invoked by any Makefile or TCL under `fpga/` — `XdcGeneratorMain` prints to
stdout and stops there. **Every board build reads hand-maintained
constraints.**

So the config is not the source of truth for pins, and the two drift:

| | says |
|---|---|
| `wukongFull` preset | `devicePart = Some("CH340N")`, assembly `SystemAssembly.wukong` |
| `wukong_ddr3_base.xdc` (what the build reads) | UART on **J11 -> Pico uart0** (A4/A5); the CH340N at E3/F3 is hardwired and "cannot be tapped" |

The XDC is right and the config is wrong, and the generated summary faithfully
reported the wrong one. [Item 52](#item-52) is the same disease pointing at the
Java tools; this is the constraints half.

**Adopting the generator today would emit the wrong pins**, so this is not
"wire it up". `SystemAssembly.wukong` has no J11 device at all — only
`wukongWithJ11Uart` carries `Board.J11UartAdapter`, and it is described as the
DUAL-subsystem assembly, though single-system DDR3 builds use J11 too. The
assembly data has to be corrected before generation can be trusted.

Note the J11 choice is a HOST-side decision, not electrical: the XDC explains
that a second `1a86:7523` bridge is indistinguishable from the A-E115FB's, so
J11 gives a Pico CDC with a real serial number. That reasoning lives only in an
XDC comment and is invisible to the config.

**Scope, honestly.** Pin constraints are generable. Some of what is in these
files is not: `wukong_ddr3.xdc` carries hand-tuned timing exceptions (a
`ui_clk` -> `sys_clk` UART crossing, with a comment explaining it stayed
invisible while the clocks were exactly equal). The realistic split is
**generate the pins, keep hand-written timing exceptions in a separate
file** — which also makes it obvious which constraints are derived and which
are judgement.

**On templating (jmustache or similar): not recommended.** The existing
generators build strings in plain Scala from typed `PinResolver` output, and
that is the right shape — the output is structured data (pin -> property), not
prose with holes, so a template would stringify early and lose the typing that
catches a bad pin at elaboration. It would also add a dependency and a second
artefact to keep in sync. The problem here is adoption and wrong assembly data,
not the rendering mechanism.

**Order:** (1) fix the assembly so `wukongFull`'s UART resolves to J11,
(2) diff generated XDC against the hand-written one per board until they agree,
(3) switch one board's build to the generated file, (4) roll out. Step 2 is the
real work and is a pure comparison — no build risk until step 3.

#### Step 2 done for the Wukong, 2026-08-23 — the gap is TWO PINS

Ran `XdcGeneratorMain` per preset and compared pin-by-pin against the files the
builds actually read. This is far better than the item assumed.

**`wukongSdram` vs `wukong_jop_sdram.xdc`: PIN-IDENTICAL.** 45 pins each, no
mismatched assignment, nothing missing on either side — all 16 SDRAM data pins,
address, control, clock, reset, UART and LEDs. **That board is adoptable
today.**

**`wukongDdr3` vs `wukong_ddr3_base.xdc`: identical except the UART.**

| port | generated | hand-written |
|---|---|---|
| `clk` + `create_clock` | M21, 20.000 ns | same |
| `resetn` | H7 | same |
| **`ser_txd`** | **E3** | **A5** |
| **`ser_rxd`** | **F3** | **A4** |
| `led[0]`, `led[1]` | G21, G20 | same |
| CFGBVS / CONFIG_VOLTAGE / COMPRESS | same |

The whole DDR3 gap is the two UART pins — E3/F3 is the CH340N, A5/A4 is J11 ->
Pico uart0. So step 1 is not merely a prerequisite, it is *the entire remaining
difference* for this board.

**The non-pin content splits exactly as predicted.** Generated already:
`create_clock`, `CFGBVS`, `CONFIG_VOLTAGE`, `BITSTREAM.GENERAL.COMPRESS`. Not
generable and must stay hand-written: the `set_clock_groups -asynchronous`
timing exceptions in `wukong_ddr3.xdc`. One real gap: the generator emits
`# source <path-to>/fpga/constraints/sdram_sdr.xdc` as a COMMENT, so a generated
file used as-is would lose the SDR IOB packing — it needs to emit a real
`source` line, and the relative path differs per board directory.

#### Step 2 for Quartus, same day — one MISSING pin, and it is the reset button

`QsfGeneratorMain` against `fpga/qmtech-ep4cgx150-sdram/jop_sdram.qsf`:

- **44 shared ports, ZERO assignment conflicts** — including `clk_in` on
  PIN_B14 and the whole SDRAM bus. The generator gets the Altera port names
  right, which is not obvious: the RTL port is `clk_in`, not `clk`.
- **`reset_n` (PIN_AD24, the SW1 reset button) is not emitted at all.**
  `QsfGenerator` has **no reset handling whatsoever** — its only "reset" match
  is a comment — while `XdcGenerator` has a section calling
  `PinResolver.resetFpgaPin`. This is an omission, not a decision. Adopting the
  generated file as-is would leave the reset button unassigned for Quartus to
  place wherever it likes.
- **37 generated-only pins**: the Ethernet and SD daughter-board pins that
  `QsfGeneratorMain` adds unconditionally "for pin reservation". Reasonable for
  the DB build, but it means the output is not a drop-in for a preset without
  those ports.

**The reset fix is not a copy-paste from the Xilinx side.** `XdcGenerator` emits
the port as `resetn`; the Altera top level calls it `reset_n`. The name is
family-specific, which is exactly the sort of detail that makes "just wire the
generators up" the wrong instinct.

**Overall after step 2:** two boards compared, and between them the generated
constraints are wrong in **three pins total** — two UART on the Wukong DDR3 and
one reset on the EP4CGX150 — with everything else, including every memory-bus
pin on both, already identical. The generators are much closer to usable than
this item assumed when it was filed.

**Not yet checked:** the A-E115FB `.qsf`, and the non-Wukong Vivado boards.


<a id="item-58"></a>

### Item 58 — `source` inside an XDC is silently ignored by Vivado — four shared constraint files have never been applied

**Found 2026-08-23 by switching one board to generated constraints and running
the control.** `wukongSdram` was built twice from the SAME Verilog, differing
only in which XDC was read. The results were not identical:

| | generated XDC | hand-written XDC |
|---|---|---|
| Slice LUTs | 5,979 | 5,967 |
| **Slice Regs** | **5,574** | **5,608** |
| WNS | +0.414 ns | +0.404 ns |

Vivado is deterministic for fixed inputs, so a 34-register difference had to
come from the constraints. It did:

```
CRITICAL WARNING: [Designutils 20-1307] Command 'source' is not supported in
the xdc constraint file. [wukong_jop_sdram.xdc:122]
```

**`source` does not work inside a file read by `read_xdc`.** Every shared
constraint file included that way has been silently absent from every build:

| file | includes | consequence |
|---|---|---|
| `wukong_jop_sdram.xdc:122` | `sdram_sdr.xdc` | **SDR SDRAM IOB packing never applied** |
| `wukong_sdram.xdc:132` | `sdram_sdr.xdc` | same, SDRAM exerciser |
| `wukong_ddr3.xdc:68` | `rtl8211eg_gmii.xdc` | **Ethernet GMII constraints never applied** |
| `wukong_ddr3.xdc:5` | `wukong_ddr3_base.xdc` | harmless — the build TCL reads it directly |

The DDR3 SMP build logs **ten** of these critical warnings. IOB mentions in the
build log: 26 with the generated file, 2 with the hand-written one.

**What it cost.** `sdram_sdr.xdc` exists to "place data and DQM registers in I/O
blocks for deterministic timing" on the SDRAM interface. That has never
happened, so every SDR build has had its DQ/DQM registers placed in the fabric
wherever the placer liked — working (these builds pass `DoAll` on hardware) but
with I/O timing that is neither deterministic nor as good as intended. The
Ethernet case is worse in principle: GMII constraints simply absent on any DDR3
build carrying the PHY.

**Already fixed for one board.** `XdcGenerator` now INLINES the two IOB
properties instead of emitting a `source` line, so `wukongSdram` — the first
board on generated constraints — is the only Wukong build where that packing has
ever taken effect. The register delta above is that fix landing.

**FIXED 2026-08-23, and the fix had a second trap in it.** All three moved to
`read_xdc` in the build TCL. `wukongFull` (the only DDR3 preset carrying the
PHY, so the only one that can test it) now reports **zero** `Designutils
20-1307`.

But applying constraints that were never applied is not a no-op, and ORDER
matters:

| `wukongFull` DDR3 | 20-1307 | `e_rxc` resolved | timing |
|---|---|---|---|
| dead `source` — before | 2 | never created | "MET" — nothing was analysed |
| GMII read AFTER `wukong_ddr3.xdc` | 0 | **no** | **VIOLATED -1.228 ns** |
| **GMII read BEFORE it** | 0 | yes | **MET +0.349 ns** |

`rtl8211eg_gmii.xdc` does `create_clock -name e_rxc`, and `wukong_ddr3.xdc`
references `[get_clocks e_rxc]` in `set_clock_groups -asynchronous`. Read the
wrong way round, that matched nothing —

```
WARNING: [Vivado 12-627] No clocks matched 'e_rxc'. [wukong_ddr3.xdc:83]
```

— so the asynchronous exclusion silently did not apply and genuinely-async RX
crossings were analysed as real paths. The shared file says so in its own
header: *"After sourcing, add e_rxc to your project's set_clock_groups."*

**The design meets GMII timing.** The violation was constraint ordering, not
the hardware. Worth stating because the intermediate result looked exactly like
"the Ethernet path has always been broken", and acting on that would have been
expensive.

**Still unresolved, pre-existing:** `clk_pll_i` and `clk_125_ddr3_clk` also
match nothing at read time — they are MIG and clock-wizard clocks that do not
exist until the IP is synthesised. Vivado says it defers those
(`[Project 1-498] ... will be read post-synthesis`), and the MET result implies
they do bind, but that has not been verified directly. Any DDR3 timing number
rests on it.

#### The same disease in Quartus, found 2026-08-24 — a dead `set_clock_groups`

Not `source` this time (that works in an SDC, which is Tcl the timing analyser
executes — item 58 is Vivado-specific). A hand-copied NAME:

```
Warning (332049): Ignored set_clock_groups at jop_sdram.sdc(23): Argument -group
with value pll|altpll_component|auto_generated|pll1|clk[1] ... could not match
any element of the following types: ( clk )
```

`jop_sdram.sdc` declares the DRAM PLL's outputs asynchronous to the Ethernet
PLL and the PHY RX clock. It names the instance `pll|...`; the design
instantiates it as `dramPll|...`. **Every group is discarded**, so the
asynchronous declaration has never applied on this board.

Harmless where it was found — a single-core SDR build has +9.714 ns and no
Ethernet — but it is inert on every build that reads this file, and the effect
of a missing async exclusion is pessimism or a false violation, which is exactly
what the Wukong GMII ordering bug produced.

The instance name is chosen in Scala (`JopTop`) and was copied into the SDC by
hand, where nothing rechecks it. Same shape as the CH340N routing and the
SignalTap virtual pins: an assertion about the design, written once, never
verified again.

**The general lesson, and why this was invisible.** A CRITICAL WARNING in a
30-minute log is not a failure: the build completes, the bitstream works, and
the missing constraints only show up as timing that is quietly worse than the
constraint file claims. **The bug was found by comparing a generated artefact
against a hand-written one and refusing to explain away a 34-register
difference** — not by reading the log, which had been saying so on every build
for as long as the file has existed.


<a id="item-59"></a>

### Item 59 — ~~the Colorlight i5 SDRAM build does not meet timing~~ — WITHDRAWN, it passes. I read the wrong line

**Raised and withdrawn 2026-08-24. There is no failure and no regression.** The
i5 meets its 40 MHz target and always did:

| i5 build | LUT4s | DP16KD | Fmax (final) |
|---|---|---|---|
| `89da8fb^`, 08-12 code | 9,364 (38 %) | 12 | **47.45 MHz PASS** |
| BRAM stage, 08-18 | 8,847 (36 %) | 40 | **48.62 MHz PASS** |
| current default, 08-24 | 13,318 (54 %) | 15 | **49.40 MHz PASS** |

**The error.** A nextpnr log contains TWO "Max frequency" lines per clock: an
estimate after PLACEMENT, and the real figure after ROUTING. In this design they
differ by 17 MHz --

```
line 195:  32.56 MHz (FAIL at 40.00 MHz)   <- post-placement estimate
line 488:  49.40 MHz (PASS at 40.00 MHz)   <- final, post-routing
```

-- and I grepped the first match every time. The Makefile already does it
correctly, `grep -E "Max frequency" ... | tail -1`, which is where the reporting
convention was available to be read.

**What survives, and is worth keeping.** The method cache default DID cost this
board area: 9,364 -> 13,318 LUT4s and 12 -> 15 DP16KD, the latter being exactly
2 KB -> 8 KB of JBC RAM (1 -> 4 blocks). But it cost NO timing -- 47.45 ->
49.40 MHz, slightly faster. So the i5 absorbed the change that broke the 4-core
Wukong and the 12-core EP4CGX150, which fits: it is a single-core build at 54 %
LUT, not a multi-core build at 90 %+.

**And the critical-path reading stands**: the post-placement path does start at
`jbcRamWord`, the JBC RAM output mux. That is a real property of the design on
this part, and the reason `jpcWidth` rather than `blockBits` would be the knob
if this board ever did need one.

**The lesson is not about the i5.** Three claims were built on one mis-read
line, each of which sounded plausible and reinforced the last: a board failing
timing, a regression to bisect, and recorded passes contradicted by the logs.
The prior reporting was right the whole time; the check that broke the chain was
being asked "are we sure we are correct and prior reporting is wrong?" -- at
which point the surviving PASS lines were sitting in the same files I had
already grepped.


### Item 60 — Everything generated should live under `build/<config>/`, and most of it still does not

**Raised 2026-08-23, in progress.** The goal the user set: *nothing generated or
built ends up anywhere other than under that build directory*, one directory per
build CONFIGURATION (preset plus arguments — not `entityName`, which collapses
core counts and overrides together). The layout itself is data
(`jop.generate.BuildLayout`), so it can be changed later without another sweep.

**Why it matters more than tidiness.** Shared generated files are read by
whichever build runs next. Two defects of exactly that shape were found by doing
this work, and neither failed a build:

| defect | symptom | found |
|---|---|---|
| `Const.java` generated into `java/runtime/src`, a shared source tree, with no dependency on the preset | switching preset printed "Nothing to be done" and left the previous configuration's constants in place — every `.jop` built afterwards carried them | 2026-08-24, `517bff7` |
| `<Top>.summary.txt` still written to the legacy directory under `buildtree` | `emit_fit_summary` prepends it and skips silently when absent, so the configuration header vanished off the fit report while the numbers stayed correct | 2026-08-24, `9fe823d` |

The first is the mechanism behind the long-standing "`make -C java all` does not
reliably rebuild apps" gotcha. It is contained by a `.const-preset` stamp, not
fixed: `Const.java` is per-configuration and belongs under `build/<config>/`.

**Progress.**

| flow | outputs | RTL | commit |
|---|---|---|---|
| `ep4cgx150Serial` (Quartus) | `build/` | `build/` | `e01b51e` |
| `colorlightI5Sdram` (nextpnr) | `build/` | `build/` | `1af3a9e` |
| `wukongSdram` (Vivado) | `build/` | `build/` | `9fe823d` |
| 48 other flows across ten boards | in-tree | in-tree | — |

Each was verified by a COLD build reproducing the known-good result: 11,112 LE
/ +0.626 ns, 49.40 MHz PASS, and 5,979 LUTs / +0.414 ns respectively.

**Why the RTL move is opt-in (`buildtree`) rather than a global switch.** 51
Makefiles and TCL scripts read `spinalhdl/generated`, and only three of those
flows can be built on this host — the rest need hardware or a toolchain that is
not installed. Flipping the default would change 48 flows nobody could check.
The flag says WHERE to write, not WHAT to build, so it is filtered out of the
configuration name.

**Stage 2 done 2026-08-24 (`4cea16b`): `java/` moves under `build/<config>/`.**
`Const.java`, the runtime classes and every `.jop` follow the configuration,
opt-in with `BUILDTREE=1`, shared logic in `java/config.mk`. Five apps produce
BYTE-IDENTICAL images in both layouts; two presets produce separate directories
carrying `SUPPORT_FLOAT` true and false, and building one does not touch the
other. Three defects were found by testing it rather than reading it:

- **javac takes the FIRST match on the sourcepath.** With a legacy `Const.java`
  still in `runtime/src`, the generated one was written, ignored, and the wrong
  constants compiled in — silently. Proven by compiling both orders and reading
  the constant back with `javap`, not by assuming.
- The find exclusion `*/com/jopdesign/sys/Const.java` matched the GENERATED copy
  too, dropping both and failing 100 classes on "cannot find symbol Const".
- `APP_NAME` is not unique — `apps/Smallest` and `apps/Small` are both
  `HelloWorld` — so keying the output directory on it collided.

**Stage 3 done 2026-08-24 (`59a74f8`): the microcode moves to
`build/microcode/<variant>/`.** Shared, not per-config, for the reason stated
above. Every variant now has its own directory, simulation included — it used to
be written to the tree root, which is why `JopConfig` needed a special case.
Every regenerated file is byte-identical to the one it replaced; a cold
EP4CGX150 build, which reaches the microcode through `SEARCH_PATH`, reproduces
11,112 LE / +0.626 ns.

Three dead or stale things fell out, none of which had failed anything:

- `build.sbt` listed **four microcode source directories that have never
  existed** — `dsp`, `serial-dsp`, `hwmath`, `serial-hwmath`. sbt ignores a
  missing source directory, so they cost nothing and implied variants that are
  not built. (My earlier note here said "six siblings", taking that list at face
  value. There are two.)
- **`asm`'s `all` never built the flash variant**, yet `JumpTableInitData`
  references `FlashJumpTableData` unconditionally — so a clean checkout could
  not compile, and `asm/generated/flash` survived only because someone had once
  run `flash-altera` by hand. It was dated **Aug 8** against an asm source
  modified the same day I found it: **16 days stale**. CI was unaffected because
  it named the targets explicitly, which is exactly why nobody noticed.
- `asm/generated/ram.mif` was an orphan — 1349 bytes where the generator
  produces 4672, and read by nothing.

**HARDWARE-VALIDATED 2026-08-24, EP4CGX150, from a scratch rebuild.** `build/`
wiped and the whole chain rebuilt in order — microcode, Scala, Java tools,
runtime, applications, RTL, Quartus project, bitstream — then programmed over
the Terasic USB-Blaster and run at 2 Mbaud on the CP2102N console:

| test | result |
|---|---|
| `DoAll` | **66/66 ok**, `JVM exit!`, zero failures |
| `CardMarkTest` | **CARD OK** |
| `MultiArrayGcTest` | **MULTIARRAY GC OK** — 13 minor GCs, corrupt 0, badYoung 0, badCompact 0 |
| `GcStressTest` | **479,784 rounds**, free flat at 5,257,068 — no leak, no corruption |

Fit was 11,112 LE / +0.626 ns, identical to every previous build of this preset.
The bitstream's RTL, IP, Quartus project and microcode all came from `build/`,
and the images from `build/ep4cgx150Serial/java/apps/`, so this is the first
end-to-end hardware confirmation of the restructured tree.

**Wukong SDR and Colorlight i5 followed, 2026-08-24**, each from a wiped config
directory and a full regenerate:

| board | fit | `DoAll` |
|---|---|---|
| Wukong `wukongSdram` (Vivado) | 5,979 LUTs, WNS +0.414 ns | **66/66**, 5 runs of 6 |
| Colorlight i5 `colorlightI5Sdram` (nextpnr) | 13,938 LUTs, 49.40 MHz PASS | **66/66** |

Both ran the SAME `DoAll.jop` as the EP4CGX150 — the three configurations
produce a byte-identical image, `Const.java` differing only in an assembly-name
comment — so all three toolchains are confirmed against one known-good artifact.

**One unexplained Wukong failure, recorded rather than explained away.** The
FIRST DoAll run on the new bitstream crashed at startup into an endless
`Uncaught exception:` loop, with visible character corruption in the banner.
It has not recurred in five subsequent runs. It nearly became a false regression
report: the natural conclusion was "the build-tree work broke the Wukong". Two
checks refuted it — the Aug 23 bitstream, built before any of this session's
work, passes 66/66 with today's image, and the same new bitstream then passed
five times running. The generated XDC and the Verilog are byte-identical to the
pre-session ones apart from the git-hash comment. See [item 63](#item-63).

The last three EP4CGX150 tests matter for a second reason: they are REGENERATED `apps/Small`
images, ~1.8 KB larger than the ones they replace ([item 61](#item-61)). Those
were the only genuinely new bytes in this work — everything else was verified
byte-identical — so hardware was the only place they could be checked.

**EP4CGX150 SMP converted 2026-08-25 (`f38fa1b`)** — and the flow was never a
separate one. It was the same six rules hand-copied with a different config,
carrying its own project name, its own SDC path and a `dram_pll.vhd` that was
NOT the one the JOP builds used. It now re-enters the parameterised rules:
`make smp CORES=n [MHZ=m]`.

| cores | clock | LE | slack (Slow 100C) | hardware |
|---|---|---|---|---|
| 2 | 80 MHz (preset default) | 26,906 | **+0.144 ns** | `cores 2` → SMPGC OK |
| 4 | 60 MHz | 51,935 | **+0.510 ns** | `cores 4, publishers 3` → SMPGC OK |
| 4 | 80 MHz | 51,701 | **−2.367 ns** | not run — violated |

**RETRACTED: "the 4-core row has decayed."** I built 4 cores at the preset
default of 80 MHz, got −2.367 ns, and attributed it to the method-cache default
growing the design (item 53) — the decay pattern that really did break the
4-core Wukong. It is nothing of the sort. `ep4cgx150Smp(n, clkMhz = 80)`
defaults to the board's MAXIMUM clock, and the recorded validation for 4 cores
is **60 MHz**. The STA clock table says so plainly:

```
clk_in                20.000 ns   50.0 MHz    board oscillator
dramPll ... clk[1]    12.500 ns   80.0 MHz    ×8/÷5, the system clock
Fmax                              67.26 MHz   what it achieves
```

12.5 ns demanded against 14.87 ns achieved IS −2.367 ns. At the validated
60 MHz it closes with +0.510 ns and passes on hardware. The recorded row was
right the whole time.

The lesson is the one already written up twice in this document: read the actual
number before reaching for a story. A pattern that fits ("a global default broke
a 4-core build") is not evidence that it applies here, and the clock table was
one grep away.

**THE CONVERSION LOOP, and it is five steps not four (2026-08-24).**

1. point the flow's RTL and outputs at `build/<config>/`
2. generate its constraints — `QsfGenerator` / `XdcGenerator` / `LpfGenerator`
   now cover Quartus, Vivado and Lattice, so no new generator is needed
3. cold-build and compare against a known-good result
4. delete the hand-written file, retiring it from `ConstraintDriftTest` if it
   was an oracle
5. **`fpga/scripts/hw_verify.py <preset>`** — program the board and run it

**Why step 5 is not optional.** Steps 1-4 compare artifacts, so they can only
prove that nothing CHANGED. They cannot prove something still WORKS when a
change was intended, and three artifacts this week had no byte-identical
predecessor to compare against: the regenerated `apps/Small` images, the flash
microcode (16 days stale), and the generated `.lpf`. Step 3 passes on all three
regardless.

**Why it is a script.** Three boards were verified by hand on 2026-08-24 and got
three different incantations — one programmed a stale bitstream from the
pre-move path, one used a bare `-c dirtyJtag` with TWO dirtyJtag probes attached
(which takes whichever enumerated first, possibly the other board), and one used
a GUESSED console alias that resolved to nothing and was reported as a broken
board. All three are board facts restated at the point of use. The script takes
them from `HwVerifyDescriptor` (the config) and the two registries, and REFUSES
on an unresolved alias rather than letting an empty string flow into a device
path.

It records one line per run rather than a boolean, because a Wukong SDR build
failed 1 run in 6 that day ([item 63](#item-63)) — collapsing that to "pass"
would make the next sighting look like the first.

```
$ fpga/scripts/hw_verify.py ep4cgx150Serial
2026-08-24T19:45:13+00:00 ep4cgx150Serial JvmTests/DoAll run=1/1 ok=66 fail=0 exit=True crash=0 PASS
```

Verified on all three converted boards: EP4CGX150 1/1, Wukong 2/2, i5 1/1.

**Coverage.** Six boards carry a probe and console alias and can run step 5:
EP4CGX150, Wukong, i5, CYC5000, Alchitry Au, XC7A100T DB V5. Two cannot and take
the four-step path — the **MAX1000** (no hardware) and the **A-E115FB** (its
JTAG resolves to the same Terasic blaster as the EP4CGX150, so both cannot be
attached at once, and its CH340 console is not currently plugged in either).
A conversion without step 5 must be recorded as **converted, not
hardware-verified**; treating the two as the same is the conflation item 60
already caught once.

**Remaining, roughly in order.**

1. The i5's `.lpf` is still hand-written and says so: *"Mirrored in
   Board.ColorlightI5 ... which is the source of truth -- keep the two in
   step."* `TimingConstraints.toLpf` renders the timing half already; the pins
   and I/O attributes need an `LpfGenerator` sibling to `XdcGenerator`. Folds
   into [item 57](#item-57).
4. The other 48 flows — mechanical. **Not blocked by tooling**: every board's
   device is supported by the installed toolchains (Quartus 25.1 covers Cyclone
   10 LP / IV E / IV GX / V, MAX II / V and MAX 10; Vivado 2025.2 has artix7;
   yosys/nextpnr cover the ECP5) and the MIG and clock-wizard IP is checked in.
   Proven by building the CYC5000 cold on 2026-08-24 — 3,728 ALMs (40 %),
   +0.864 ns, four minutes.

**What actually limits the sweep** — and it is not what this item first said.
Hardware is available for every FPGA type except the MAX1000's 10M08: both
XC7A100T boards, the EP4CGX150, the i5, the CYC5000, the Alchitry Au, and the
A-E115FB (sharing the Terasic blaster). The two real constraints are:

- **Baselines, not buildability.** A conversion is verified by a cold build
  REPRODUCING a known-good result, and there are only **7 recorded fit summaries**
  in the tree. Many of the 48 are not JOP builds at all — blink, SDRAM and DDR3
  exercisers, SPI diagnostics, a UART echo — and several have never had a number
  recorded. For those a cold build proves the paths resolve and nothing more.
  Worth fixing on its own account: see [item 60a](#item-60).
- **Time.** The Vivado DDR3 SMP builds run 30-60 minutes each.

**The `fpga/` directory does not disappear.** Board-specific inputs that are not
derived from the config live there legitimately: `pll_jop_i5.v`, the MIG IP, the
programming and monitor recipes.


### Item 61 — ~~`make -C java all` fails at HEAD~~ — FIXED 2026-08-24. It was worse: NO app in `apps/Small` could be built

**Found 2026-08-24** while establishing a baseline for the build-tree move, and
**confirmed pre-existing** by stashing that work and reproducing it at HEAD.

```
com.jopdesign.common.misc.ClassInfoNotFoundException:
  Class 'java.lang.Throwable' could not be loaded: Couldn't find: java/lang/Throwable.class
    at com.jopdesign.build.PreLinker.main(PreLinker.java:53)
```

`apps/Small` is the ONE app that compiles with `-sourcepath` from a single entry
point:

```make
$(TARGET_JAVAC) -sourcepath $(SRCPATH) -d $(CLASSES_DIR) $(APP_SRC)/$(APP_PKG)/$(APP_NAME).java
```

The other seven `find` every source and compile the lot. With `-sourcepath`,
javac emits only what is reachable from the entry point, so classes the JVM
needs but the application never names — `java.lang.Throwable` among them — are
never written, and PreLinker cannot resolve them.

It builds when `build/classes` already holds the output of some earlier, wider
compile, which is why this survived: the failure only shows from clean. Note
`make -C java all` builds `Smallest`, `Small` and `InterruptTest`, so the
aggregate target fails even though six of the eight apps are fine.

**FIXED (`4e1c669`), and the first attempt was wrong.** Using the `find` form
like the other seven fails here with 89 errors: this directory holds apps with
different dependencies — `NetTest` and `HttpServer` need
`EXTRA_SRC=../../net/src` — so compiling all of `src/test` needs every optional
source root supplied. The fix bulk-compiles the runtime and *names* the app,
letting `-sourcepath` pull in what it uses.

Regenerated images are ~1.8 KB larger than the stale ones, the runtime now being
fully compiled rather than reachability-pruned. `JopCoreBramSim` runs the
rebuilt `Small/HelloWorld.jop` to completion and prints `Hello World!`.

**The scope was wider than first filed**: not just `HelloWorld` but every app in
the directory — `GcStressTest`, `CardMarkTest`, `NCoreHelloWorld`,
`MultiArrayGcTest`, `ZeroBench` and the rest. Their `.jop` files on disk were
artifacts of an older working state that no longer regenerated, so any test
using one was running an image that could not be reproduced from source.

### Item 62 — `JopFloatCuBramSim` reads a microcode variant that has never existed

**Raised 2026-08-24** while moving the microcode tree. The simulation loads:

```scala
val romFilePath = s"${MicrocodePaths.root}/floatcu/mem_rom.dat"
```

`asm/Makefile` builds `simulation`, `serial` and `flash`. There is no `floatcu`
target and there is no evidence there ever was, so this simulation has never
run.

It was left pointing at the variant it asks for rather than quietly repointed at
the simulation ROM, which would make it run against microcode nobody has checked
it against — a passing test proving nothing. Same family as the `lmul_sw`
fallback that went years unexecuted: an implementation no preset selects gets no
coverage, and a simulation no variant supplies gets none either.

Decide whether the float CU wants its own microcode variant or whether the
simulation should use the standard one, then either add the target or repoint
it.


### Item 63 — One unexplained Wukong SDR startup crash in six runs

**Observed 2026-08-24**, first hardware run of the rebuilt `wukongSdram`
bitstream. `DoAll` never reached its first test:

```
GC: generational, #"-word cards
Uncaught exception: Uncaught exception: Uncaught exception: ...
```

The `#"` where a digit belongs, and a later `Uncaught pxcetion`, are character
corruption on the CH340N — the crash loop floods it faster than it keeps up.
That is a CONSEQUENCE, not the cause: `HelloWorld` on the same bitstream at the
same 2 Mbaud printed a clean banner immediately afterwards.

**Not reproduced.** Five subsequent runs of the identical bitstream, each with a
fresh reprogram, gave 66/66.

**Why this is filed rather than dismissed.** It was one run away from being
reported as "this session's build-tree work broke the Wukong", which would have
been wrong. What refuted it:

- the **Aug 23 bitstream**, built before any of this session's work, passes
  66/66 with today's image — so the image and the shared chain are sound
- the same NEW bitstream then passed five times
- the generated XDC is byte-identical to the pre-session one, and the Verilog
  differs only in its git-hash comment

So there is no regression to bisect, and equally no explanation. Recorded so a
second sighting is recognised as the second, not the first. Note the design
meets timing comfortably (WNS +0.414 ns), which argues against a marginal path.


### Item 64 — `GcStressTest` free memory falls 0.42 bytes per round, on every board

**Found 2026-08-25** by giving `hw_verify.py` a soak check, and it had been
sitting in plain sight in two hardware logs.

`GcStressTest` allocates ten `int[32]` per round, all immediate garbage, and
prints `GC.freeMemory()`. Free memory does not hold level — it declines
MONOTONICALLY, with no recovery in any window:

| board | memory | rounds | free start → end | rate |
|---|---|---:|---|---|
| Colorlight i5 | 8 MB SDR, ECP5 | 345,115 | 5,459,328 → 5,313,644 | **0.422 B/round** |
| EP4CGX150 | SDR, Cyclone IV GX | 479,784 | 5,459,328 → 5,257,068 | **0.421 B/round** |

Two boards, two memory systems, two FPGA families, and the rate agrees to three
significant figures. That is deterministic consumption, not measurement noise.

Windowed, the i5 run shows no sawtooth at all — each tenth has a band of about
14.5 KB and its floor sits below the previous one's:

```
  win 1   rounds      0.. 34510   min 5444204
  win 5   rounds 138044..172554   min 5386092
  win10   rounds 310599..345109   min 5313644
```

**Why it went unnoticed:** it is slow. 470 MB is allocated over 345k rounds and
only 146 KB goes missing, so the collector is plainly working — the heap would
be gone in a few thousand rounds otherwise. At this rate free memory lasts about
12.6 million rounds, well past any soak anyone has run.

**And it was reported as "free flat" three times in this session.** That claim
came from `tail -3` of the log, where consecutive rounds do look identical.
Three lines of a 345,116-line series is not a trend, and a soak whose verdict is
a substring match cannot see the difference — which is the argument for the
checker that found it.

Candidates not yet distinguished: handle-table growth (`GC.MAX_HANDLES`),
fragmentation in the tenured space, or something retaining a few bytes per
collection. **Measure before choosing** — the rate being identical across memory
systems points away from anything memory-controller-specific.


### Item 65 — Both SD exercisers fail on hardware, and it is not the conversion

**Found 2026-08-25**, first hardware run of the converted SD exercisers on the
EP4CGX150 + DB v4 with a card in the slot.

| | T1:DETECT | INIT | T2:WRITE | T3:READ |
|---|---|---|---|---|
| SD native (4-bit) | PASS | **FAIL** `0x41` | PASS | PASS |
| SD over SPI | PASS | **FAIL** | FAIL | FAIL |

`dbgStep 0x41` is **ACMD41 timeout — no response**, after 0xac8 (2760) retries.
`docs/peripherals/db-fpga-sd-card.md` records all four passing, so this is a
change from a state that once worked.

**It is NOT the build-tree conversion.** The only functional difference that
conversion introduced was the clock: the hand-written project built against the
60 MHz `dram_pll.vhd` while the top declared 80 MHz, and the converted flow
generates a PLL that matches its declared 80. Rebuilt at **60 MHz** — the
historical frequency — and the failure is IDENTICAL, same code and same retry
counts (0x588 then 0xac8). Pins were already proven identical to the
hand-written `.qsf`, and the SDRAM exerciser on the same board, same generated
PLL and same generated project passes all three of its tests.

**The odd part, and where to start.** Native reports INIT failed yet WRITE and
READ then pass — so the card is demonstrably working. Either the ACMD41 check is
reporting a false failure (the same shape as bug 27, where T2:WRITE reported
FAIL on a write that had succeeded), or INIT recovers through a later path and
the verdict is stale. The SPI exerciser failing everything after DETECT is a
different symptom and may not share a cause.

**Confound worth eliminating first**: an SD card latches SPI mode until it is
power-cycled, and the SPI exerciser was run before the native one. The native
result was reproduced on a second run, so it is not order-dependent within
native, but neither exerciser has been run from a cold card.

Not chased further here -- this session's task was the build port, and the
conversion is cleared.


### Item 66 — the EP4CGX150's Ethernet/VGA/SD was lost in a migration, not removed

**Found 2026-08-25** while deciding whether to convert the flow.

`jop_dbfpga.qsf` assigns **95 pins**, including Ethernet (`e_mdc`, `e_mdio`,
`e_rxd`, `e_txd`, `e_gtxc`, …), VGA (`vga_r/g/b`, `vga_hs`, `vga_vs`) and SD
(`sd_clk`, `sd_cmd`, `sd_dat_*`, `sd_cd`).

Its `generate-dbfpga` target runs **`ep4cgx150Serial`**, whose top has 45 ports:
`clk_in`, `led`, `sdram_*`, `ser_rxd`, `ser_txd`. Nothing else. So fifty
assignments name ports that do not exist — which Quartus reports as warnings and
otherwise ignores, so the flow "builds" while none of that hardware is driven.

**CORRECTED — it is not a stale project. This board HAD working Ethernet, and
the capability was lost in one line.** Commit **`8641942`** (2026-03-14, "Fix
connector labels and device assignments for QMTECH boards and DB_FPGA"):

```diff
 generate-dbfpga:
-	sbt "runMain jop.system.JopDbFpgaTopVerilog"
+	sbt "runMain jop.system.JopTopVerilog ep4cgx150Serial"

 generate-dbfpga-vgadma:
-	sbt "runMain jop.system.JopDbFpgaVgaDmaTopVerilog"
+	sbt "runMain jop.system.JopTopVerilog ep4cgx150Serial"
```

Two DIFFERENT tops -- one carrying Ethernet/VGA/SD, one carrying VGA DMA -- were
both repointed at a preset that declares none of them, as part of the migration
away from hand-written tops (`7258661`, "Remove IoConfig and legacy tops"). The
`.qsf` still describes what the design used to have. That is also why the two
dbfpga flows now emit byte-identical RTL.

`docs/peripherals/networking.md` documents the working system in detail -- "a
poll-based TCP/IP stack running on the QMTECH EP4CGX150 + DB_FPGA daughter board
with RTL8211EG Gigabit Ethernet PHY", 1 Gbps GMII with MDIO, ARP, DHCP, TCP --
and its build instructions still name `JopDbFpgaTopVerilog`, a main that no
longer exists.

**Everything except the preset survived:**

| piece | state |
|---|---|
| `RTL8211EG`, `VGA`, `SD_CARD` on the DB v4 board | present in `Board.scala` |
| `ethernet`, `vgadma`, `vgatext`, `sdnative`, `sdspi` device types | present |
| Java TCP/IP stack | 16 files in `java/net/src/com/jopdesign/net/` |
| `NetTest`, `DhcpTest`, `HttpServer` | present in `java/apps/Small` |
| a preset wiring them together | **missing** |

**The fix is a preset, and its template already exists.** `xc7a100tDbFull`
declares exactly this device set -- `RTL8211EG`, `VGA`, `SD_CARD` -- on the DB
**v5** assembly. The EP4CGX150 equivalent is the same map on
`SystemAssembly.qmtechWithDb` with the UART on `CP2102N` rather than `RP2040`.
Once it exists, `generate-dbfpga` names it, the 95 pins have ports again, and
the constraints generate from the config like every other converted flow.

Worth doing on its own merits: it restores a documented, hardware-proven
capability, and it is the only EP4CGX150 configuration that would exercise the
Ethernet path at all.

**WRITTEN BACK 2026-08-25.** `JopConfig.ep4cgx150DbFull` -- the device set mined
from `IoConfig.qmtechDbFpga` in history, in the modern declarative form taken
from `xc7a100tDbFull`. Its generated project is **PIN-IDENTICAL to the
hand-written `jop_dbfpga.qsf`, all 95**, which is the evidence that the
reconstruction is faithful.

| | |
|---|---|
| fit | **15,282 LE, 95 pins, Fitter Successful** |
| clocks | `clk_in` 50 MHz, `dramPll` 80 MHz system, `ethPll` **125 MHz** |

**TIMING: MET, once a one-word bug was fixed.** The first build reported −1.812
ns setup and it was not a timing problem at all. Every failing path was
`StreamCCByToggle` inside `MacTxManagedStreamFifoCc` — the clock-domain crossing
between the 80 MHz system and the 125 MHz Ethernet TX domain, which is
asynchronous BY CONSTRUCTION and must be excluded.

`TimingConstraints.forConfig` tested `deviceType.key == "eth"`. The DeviceType
key is **`ethernet`**; `eth` is only the conventional MAP key a preset happens to
use. So the predicate was never true on any design, no Ethernet clock group was
ever emitted, and with fewer than two groups the whole `set_clock_groups` is
dropped — leaving the CDC paths timed as if synchronous.

| clock | before | after |
|---|---|---|
| `dramPll` clk[1] — 80 MHz system | −1.812 (TNS −20.755) | **+0.458** (TNS 0) |
| `ethPll` clk[0] — 125 MHz Ethernet | −1.503 (TNS −17.161) | **+0.802** (TNS 0) |
| `dramPll` clk[3] | +0.667 | +0.704 |

15,270 LE, 95 pins, all three clocks MET.

**The VGA DMA sibling is also back (`ep4cgx150DbVgaDma`) and does NOT close.**
History had two DB_FPGA configurations -- `IoConfig.qmtechDbFpga` (VGA text) and
`qmtechDbFpgaVgaDma` -- and `8641942` repointed both at `ep4cgx150Serial`, so
they have produced byte-identical RTL ever since. Restored as a preset variant
(same 95 pins; VGA is VGA either way), it builds at 14,429 LE but the SYSTEM
clock misses by **−1.011 ns** while Ethernet passes at +0.619 — so this is not
the clock-group bug. The failing paths run into `BmbSdramCtrl32` from
`BmbMemoryController` and `VgaBmbDma`'s CC FIFO: a third BMB master pushing the
arbiter path over, which is [item 5](#item-5) / [item 31](#item-31) again rather
than anything specific to VGA.

**The same bug, twice, from opposite sides.** The comment beside that predicate
records an earlier fix: the hand-written `jop_sdram.sdc` named `e_rxc` on a
UART-only build, Quartus could not match it, and it discarded the whole
`set_clock_groups`. The replacement stopped naming what does not exist — and
never matched what does. Both versions produce the same symptom, silently: a
constraint file that looks right and constrains nothing.

**This was reachable only because a design used Ethernet.** No converted flow had
one until now, so the dead predicate cost nothing and showed nothing. Restoring
a capability found a bug in the machinery built to replace it.

One generator gap closed on the way: a board's Ethernet PLL (`pll_125.v`) had no
route into a generated project, so synthesis stopped with "instantiates
undefined entity". `Board.extraIpFiles` now carries it, emitted only when the
DESIGN declares an Ethernet device -- the board has the PLL either way. It is
hand-written IP and a PllSpec candidate.


### Item 67 — `ep4cgx150DbFull` has `useStackCache` off; the original had it on

**Raised 2026-08-25** when the DB_FPGA preset was written back (item 66).

`JopDbFpgaTopVerilog`, the configuration that ran the working TCP/IP stack, set
`useStackCache = true`. The restored preset sets it **false**, matching
`ep4cgx150Serial`.

That was deliberate: stack-cache SDRAM integration is still open -- it is
verified in BRAM but needs per-core stack regions -- so turning it on at the
same time as restoring the peripherals would have made the first build back
differ from the known-good configuration in TWO places rather than one. If the
restored build misbehaves, the cause should not have two candidates.

Revisit once the stack cache lands, or sooner if a measurement wants it: the
original chose it for a reason that is not recorded, and the Ethernet driver
moving whole frames is the obvious guess.


### Item 68 — Ethernet links at 1 Gbps but no packets move

**Found 2026-08-25**, first hardware run of the restored `ep4cgx150DbFull`
(item 66) on the EP4CGX150 + DB_FPGA V4, cable in, switch connected.

| test | result |
|---|---|
| `NetTest` | `Link UP 1000M`, `UDP:7`, `TCP:7`, `GARP`, `Ready`, then `Rx=0 Er=0 Dr=0` indefinitely |
| `DhcpTest` | `Link UP 1000M`, `DHCP: start` → `DHCP: timeout, restart`, repeating |

**The DHCP timeout is a real failure: there IS a DHCP server on that switch.**
So the board sends DISCOVER and either the frames never reach the wire, or the
offer comes back and the receive path drops it.

What is established:

- **PHY and MDIO work.** Auto-negotiation reaching 1000M is a conversation with
  the RTL8211EG over the management interface, so that path is sound.
- **The MAC sees nothing at all.** `Rx=0` with `Er=0 Dr=0` — not corrupt
  frames, not dropped frames. Nothing.
- **The stack drives TX.** GARP on startup, DISCOVER on a retry loop.

What is NOT established: whether frames leave the PHY. Nothing on this host can
see that segment — it is a NAT'd VM on 192.168.122.0/24 that routes 192.168.0.x
via the gateway.

**The board cannot ping out**, so that diagnostic is unavailable as things
stand: `ICMP.java` has no `sendEchoRequest`. `TYPE_ECHO_REQUEST` appears only as
a constant and in the receive path, and `pingSentCount` counts REPLIES — which
is also why `NetTest`'s `Tx=0` says nothing about the GARP.

**Where to start.** The counters distinguish the halves cheaply: a host on
192.168.0.x pinging the board tests RX and the reply path in one step, and a
capture on that segment shows immediately whether DISCOVER is on the wire. Note
this configuration is not otherwise identical to the one that worked in March —
`useStackCache` is off (item 67) and the design has grown since — but neither of
those would plausibly silence the MAC.


### Item 69 — `"*" -> "hw"` forces hardware for a bytecode that has none

**Found 2026-08-25**, first `DoAll` run on the converted Wukong DDR3 flow:

```
Conversion ok
FloatTestni 056818  019109  010  114
JOP: bytecode 114 not implemented
```

Bytecode 114 is **`frem`**, and it is implemented NOWHERE — not in
`asm/src/jvm.asm`, not in Scala. There is no hardware `frem` and no microcode
`frem`.

`wukongFull` sets `bytecodes = Map("*" -> "hw")`. That forces the HARDWARE
implementation of every bytecode, including one that has none, so
`resolveJumpTable` leaves the entry empty and the JVM traps at run time.
`ep4cgx150Serial` sets only `idiv` and `irem` to `hw`, leaves the rest at their
defaults, and `DoAll` passes 66/66 there — the difference is the wildcard, not
the board.

**Affects every `*=hw` preset**: `wukongFull`, `xc7a100tDbFull`, and the
dual-cluster half at `JopConfig.scala:1301`.

**Not the build-tree conversion, and proved so on hardware.** `wukongDdr3` --
same board, same DDR3, same generated constraints, same converted flow, and
`bytecodes = Map("idiv" -> "hw", "irem" -> "hw")` instead of the wildcard --
runs **DoAll 66/66 with `JVM exit!`**. The only difference between the two is
`"*" -> "hw"`.

| preset | bytecodes | LUTs | timing | DoAll |
|---|---|---|---|---|
| `wukongFull` | `"*" -> "hw"` | 20,514 | +0.349 ns | **dies at FloatTest, `frem`** |
| `wukongDdr3` | `idiv`, `irem` | 12,448 | +0.642 ns | **66/66** |

The converted flow's RTL is also byte-identical to the legacy path's, and
`wukongFull`'s fit reproduces the known-good 20,514 LUTs / +0.349 ns exactly.

**The wildcard needs to mean "hardware where one exists".** Silently producing a
jump-table hole for a bytecode with no implementation is the same failure shape
as [item 17](#item-17), where `needs*Compute` predicates understated CU
reachability and cost ten JVM tests. A `require` at elaboration -- "`*=hw` names
`frem`, which has no hardware implementation" -- would turn a run-time trap into
a build-time error.


### Item 70 — the UART baud is stated three times and the three disagree

**Raised 2026-08-25**, after two boards in one session refused to download until
their build's own summary was read.

The rate a bitstream actually uses comes from the CONFIG. It is stated in three
places:

1. **A preset override** — `params = Map("baudRate" -> n)`. Only four presets set
   one: `wukongFull` 1 M, `ae115fbDdr2` 2 M, `colorlightI5Bram/Sdram` 1 M.
2. **A default** — `JopCoreConfig.uartBaudRate` falls back to **2 000 000** for
   every preset that does not override, which is most of them.
3. **Twelve Makefile constants** — `BAUD_RATE`, `UART_BAUD`, `DDR3_UART_BAUD`,
   `BAUD`: seven say 2 M, four say 1 M, and they are what `make download` and
   `make monitor` pass.

Nothing checks that (3) matches (1)/(2), and the bitstream only listens to
(1)/(2).

**Two failures from this in one session.** The Wukong DDR3 flow: the Makefile
says `DDR3_UART_BAUD := 2000000`, `wukongFull` says **1 M**, and downloading at
2 M produced "FPGA not responding" on BOTH the CH340N and the Pico — which reads
exactly like a dead board. Then `wukongDdr3` on the same board, same flow, says
**2 M**, so the correct rate flipped between two presets of the same memory type.

Earlier, the i5's `UartBaudTick` work was prompted by the same class of problem
from the other end: an integer divider that only produced the requested rate on
lucky clocks.

**What to do.** One rate everywhere unless a board cannot reach it — the CH340
boards are the known constraint, and 1 Mbaud is the rate every attached board has
demonstrated. Then:

- the Makefile constants should be DERIVED, not restated. Each build already
  emits its baud in `<Top>.summary.txt`, so `make download` can read it rather
  than carry a constant that is right by coincidence.
- a preset that overrides the rate should say why, at the override.

**Cheap partial fix available now**: `download.py` and `monitor.py` could take
the config directory instead of a baud and read the summary. That removes the
guess at the point where it actually bites, without touching any preset.


### Item 71 — the EP4CGX150 BRAM presets do not close timing at 80 MHz

**Found 2026-08-26** while filling in the hardware record for that board.

| preset | setup (Slow 100C) | TNS | on hardware |
|---|---|---|---|
| `ep4cgx150BramSerial` | **−2.544 ns** | −170.3 | prints `Hello World!` |
| `ep4cgx150Bram` | **−3.745 ns** | −447.9 | nothing — no app is embedded |
| `ep4cgx150BramGc` | **−2.870 ns** | −238.9 | 180 bytes of garbage |

All three run at `clkFreq = 80 MHz`, the board's maximum. The SDRAM presets on
the same board close comfortably (`ep4cgx150Serial` +0.626 ns), so this is
specific to the BRAM configurations.

**`BramSerial` passing is the dangerous part.** It downloads and prints
`Hello World!` on a bitstream missing timing by 2.5 ns, and was recorded here as
a hardware pass on that basis before anyone looked at the slack. This document
already says why that is worthless -- *a passing DoAll on a violated bitstream
proves nothing; it can misbehave arbitrarily and the failure would be
intermittent* -- and the check was skipped anyway.

**FIXED three ways.**

*1. The cause.* The critical path is the BRAM READ-DATA path, identical in all
three builds: from an M9K address register inside `BmbOnChipRam`, through the
memory's clock-to-out, through the read mux across the block array, through the
BMB response fabric and into `BmbMemoryController.rdDataReg` -- and on to the
UART FIFO -- in ONE combinational cycle. 15.1 ns for a 32-block array, 16.3 ns
for the 126 blocks the 512KB preset needs, against a 12.5 ns period.
`ep4cgx150Serial` closes at +0.626 ns on the same device at the same 80 MHz
because `BmbSdramCtrl32` REGISTERS read data and breaks the path. Array size is
the SECONDARY term: 32 blocks is already 2.5 ns over.

*2. Why nobody noticed for five months.* `dram_pll.vhd` used to be hardwired to
60 MHz whatever the preset declared, so the silicon ran at 60 while the `.sdc`
claimed 80 -- violated on paper, fine in practice. The declared frequency was
understood to be decorative, and `ep4cgx150BramSmp`'s doc comment says so in as
many words. `DramPllGen` generates the PLL from the preset now
(`clk1 = 50 MHz x 8 / 5 = 80`), which made the declared frequency real and the
violation real with it. **A comment explaining why a value can be ignored
outlives the reason.**

*3. The fix.* Reclocked, all three now MET, all verified on hardware:

| preset | clock | slack | hardware |
|---|---|---|---|
| `ep4cgx150BramSerial` | 60 MHz | **+0.968** | `Hello World!` 3/3 |
| `ep4cgx150BramGc` | 60 MHz | **+0.745** | `Hello World!` (after item 72) |
| `ep4cgx150Bram` | 50 MHz | **+0.388** | fit only -- embeds no app |

`ep4cgx150Bram` needs the lower clock for its 126-block array; it still missed by
0.431 ns at 60 when the others had cleared. `ep4cgx150BramGc` sets 60
explicitly rather than inheriting, so shrinking the base's memory cannot
silently reclock it.

**`hw_verify.py` now REFUSES a violated bitstream** (`--allow-violated` to
override, and the log line records the override). It reads the timing report
that sits beside the bitstream, for all three toolchains: Quartus `.sta.rpt`
(worst setup slack over EVERY corner, not a corner picked by name), Vivado
`fit_summary.txt`, and nextpnr's log -- taking the LAST Fmax verdict per clock,
never the first, because the post-place estimate and the post-route result
disagree (32.56 MHz FAIL then 49.40 MHz PASS on the i5). Every log line now
carries `timing=MET|VIOLATED|unknown`, and a run whose timing is not MET prints
`NOT A VERIFICATION`.

**Two smaller things found alongside**, both config rather than conversion:

- `ep4cgx150Bram` embeds NO application. `JopTopVerilog` sets a `jopFile` only
  for `ep4cgx150BramGc`, so the plain preset produces a bitstream with an empty
  main memory. It is a fit-measurement configuration, not a runnable one, and
  nothing said so.
- `ep4cgx150BramGc`'s garbage output was NOT this violation -- see item 72. It
  survived the reclock unchanged. Attributing it here would have been wrong, and
  plausible.


### Item 72 — every FPGA build got the SERIAL microcode, whatever its boot mode

**Found 2026-08-26**, chasing item 71's leftover symptom. `JopTopVerilog`
resolved the microcode tree with the CONSTANT `MicrocodePaths.serialDir`:

```scala
val withMif = MifPathOverride(config, layout.relativeTo(prjDir, MicrocodePaths.serialDir))
```

`MicrocodePaths` is keyed by `BootMode` precisely so this cannot happen, and its
own doc comment describes `serialDir` as *"the serial directory, which the FPGA
flows load their microcode from"* -- true of every FPGA preset except the
`BootMode.Simulation` ones, which is the assumption that made a constant look
safe. Fixed to `MicrocodePaths.dir(config.system.bootMode)`.

**The symptom was `ep4cgx150BramGc` emitting ~180 identical non-ASCII bytes.**
It boots from preloaded BRAM, was handed the serial boot ROM, and sat in the
download handshake emitting its sync byte forever. It now prints `Hello World!`
from BRAM on hardware.

**Worth noting how nearly this was mis-attributed.** The same preset ALSO missed
timing by 2.870 ns (item 71), which is a complete and plausible explanation for
garbage on a UART. Reclocking it to 60 MHz made timing MET and the garbage
continued unchanged -- only then did the boot ROM come into view. Two independent
defects on one preset, the louder one fully capable of masking the quieter.

Scope: `ep4cgx150Bram`, `wukongBram`, `wukongBramFull` are the other
`BootMode.Simulation` presets. None embeds an application, so none could show
the symptom.

### Item 73 — `ep4cgx150DbVgaDma` misses by −1.011 ns (OPEN)

Not the BRAM path of item 71 -- a different subsystem. The four worst paths all
END at the same node:

```
BmbSdramCtrl32|AlteraSdramAdapter|altera_sdram_tri_controller|efifo_module|entry_1[40]
```

and start from `BmbMemoryController` (`bcFillCount`, `addrReg`) and from
`VgaBmbDma`'s clock-crossing FIFO. That is two masters arbitrating into the
SDRAM controller's command FIFO, with the arbitration mux on the critical path.
Lowering the clock would close it, but that is a workaround for a real
arbitration path rather than a fix, so it is left open and the preset is
**unverified**. It is the board's only remaining violated flow.


### Item 74 — item 69 is wider than `"*" -> "hw"`

`ep4cgx150HwFloat` was built and run on hardware for the first time on
2026-08-26 and dies exactly as `wukongFull` does:

```
FloatTest
JOP: bytecode 114 not implemented
```

**It does NOT use the wildcard.** It sets `bytecodes = Map("idiv" -> "hw",
"irem" -> "hw", "float" -> "hw")`. Item 69 says the defect "affects every `*=hw`
preset" and names three; the **`float` group key reaches it too**, so the list
was incomplete and the framing -- *the difference is the wildcard* -- was wrong.

**`frem` (0x72) has no `BytecodeEntry` at all.** Every other float bytecode is in
`BytecodeConfig.all` -- fadd, fsub, fmul, fdiv, fneg, i2f, f2i, fcmpl, fcmpg --
and `frem` is simply absent, so nothing in `resolveJumpTable` can reason about
it. That is the likelier root than either key: a bytecode outside the table
cannot be given an implementation by a config that only knows about the table.
The exact mechanism by which `float -> hw` drops it has NOT been traced; what is
established is the three facts above.

`ep4cgx150HwFloat` is therefore **built and MET (+0.684 ns, 13,714 LE) but
NOT verified** -- it fails for a real reason, on a defect it shares with
`wukongFull` and `xc7a100tDbFull`.

### Item 75 — `ep4cgx150HwMath` is a duplicate of `ep4cgx150Serial`

Built for the first time on 2026-08-26. Its generated Verilog is **byte-identical
to `ep4cgx150Serial`'s** apart from the git-hash comment, and both fit at
11,112 LE.

```scala
def ep4cgx150HwMath = base.copy(... bytecodes = Map("idiv" -> "hw", "irem" -> "hw"))
```

`ep4cgx150Serial` already sets exactly that (`JopConfig.scala:507`). The preset
meant something when the base did not, and the base changed underneath it.

**It still looks maintained.** `JopConfigTest` asserts *"ep4cgx150HwMath preset
has IntegerComputeUnit"*, which passes because `ep4cgx150Serial` has one -- the
test never checks that the preset is DISTINCT from its base, so it would pass if
the preset were deleted and aliased. `system-configuration.md` lists it as a
separate configuration in two tables.

**Do not "verify" it on hardware.** A DoAll run would produce a green line for a
bitstream already verified under another name -- coverage that reads as two
configurations and is one. Either give it a distinguishing setting or retire it;
that is a decision, not a cleanup.


### Item 76 — the 4-core BRAM SMP stall is gone, cause unknown

`ep4cgx150BramSmp` exists to bisect a failure: at 4 cores SmpGcTest passed in
simulation on both memory models but STALLED on the board with one core starving
deterministically, and its comment concluded *"the remaining difference is
silicon itself."* Built and run for the first time under the new tree on
2026-08-26:

| clock | timing | hardware |
|---|---|---|
| 50 MHz | **MET +1.906 ns** | **`SMPGC OK`, 4 cores** |
| 60 MHz | VIOLATED −0.629 ns | also prints `SMPGC OK` -- NOT a verification |

**The stall does not reproduce.** Something fixed it between then and now and
**which fix is not established** -- the plausible candidates (the CmpSync
reentrancy fix, the GC work) were never tested against this preset.

**Timing closure was the obvious explanation and it is REFUTED.** The preset
defaulted to 60 MHz, which misses by 0.629 ns, and an unclosed design starving
one core is exactly the right shape of story. So the violated build was run
deliberately (`--allow-violated`) and it passes too. One run each -- enough to
kill the hypothesis, not enough to certify the 60 MHz build, which the log
records as `timing=VIOLATED!OVERRIDDEN` and `NOT A VERIFICATION`.

The default is now **50 MHz**, which closes. The 4-core critical path REVERSES
direction relative to the single-core BRAM presets: it runs from core 3's
`BmbMemoryController.addrReg` INTO the shared `BmbOnChipRam` port-A address and
write-enable registers -- four cores arbitrating for one on-chip memory, not the
read-data path out of it (item 71).

**Do not treat this preset as still demonstrating the original defect.**

### Gotcha — `JOP_PRESET` must carry the SAME arguments as `CFG`

Building the Java tree with `JOP_PRESET="ep4cgx150BramSmp"` while the RTL was
built with `CFG="ep4cgx150BramSmp 4 50"` puts the `.jop` under
`build/ep4cgx150BramSmp/` and the bitstream under
`build/ep4cgx150BramSmp-4-50/`. Both commands succeed. `hw_verify` then reports
no image, and the near-miss is worse: a bare preset RESOLVES (to the argument
defaults), so the app tree is built against a different configuration than the
hardware without anything failing.

`BuildLayout` keys on the invocation precisely so two configurations cannot
collide; it cannot help when the operator hands the two halves of one build
different invocations.


### Item 77 — the EP4CGX150 SDRAM Makefile, 701 lines to 195

Converted 2026-08-26 onto `fpga/quartus.mk`. It was the last board Makefile
carrying its own copy of the Quartus flow, and the biggest.

**Cold-verified.** `build/ep4cgx150Serial` deleted and rebuilt end to end through
the new file: fit summary **byte-identical** to the known-good apart from its
timestamp line, timing +0.626 ns unchanged, and **DoAll 66/66** on hardware.

**The bug that prompted it.** `program-smp` programmed
`output_files/jop_smp_sdram.sof` -- the pre-build-tree in-tree path, whose newest
copy was from **23 August** -- while the current bitstream sat in
`build/<config>/`. The comment on `program` recorded that exact defect being
fixed *for `program`*; the SMP twin fifteen lines below was left as it was. Now
an alias for `smp-program`, which re-enters the parameterised rules.

**~150 lines were flows that could not run.** `generate-smp8` invoked
`jop.system.JopSmp8TestVerilog` and `generate-flash-boot` invoked
`jop.system.JopCfgFlashTopVerilog`; **both mains were deleted in `7258661` on
2026-03-13**, five and a half months earlier. Their build, program and full
targets, and the `flash-image`/`flash-jic`/`program-flash` chain hanging off the
flash-boot `.sof`, all went with them. `microcode-flash` ran `make flash` in
`asm/`, where the target is `flash-altera` -- dead the same way.

**Nothing failed, because nothing invoked them.** A Makefile target is only
checked when someone runs it, so a dead one is indistinguishable from a working
one until the day you need it. The same is true of the `.qsf` files they
referenced, which were still tracked.

Also removed: **nine `.cdf` generators**, eleven near-identical lines each.
`quartus_pgm` takes an explicit operation, so the `.sof` goes to it directly.

**Kept deliberately:** `assert-device` (this board shares the Terasic
USB-Blaster with the A-E115FB, and programming the wrong one succeeds silently),
which is why `program` wraps `program-sof` rather than using it; and the
`config_flash_exerciser` / `flash_programmer` in-tree builds, still blocked on
the config-flash pad abstraction. `microcode` moved into `quartus.mk` -- it was
identical on every board.

`uart_test.qsf` is now orphaned here: no target references it, and `UartTestTop`
still exists. It belongs in `bringup/` with the other jigs.

## 4. Two workstreams, both largely done

**GC (Stage 3)** — generational GC is on by default and hardware-validated on
both the EP4CGX150 and XC7A100T. The minor pause is now **bounded**, which was
the real goal:

| | session start | now |
|---|---|---|
| EP4CGX150 SDR minor pause | 30.54 ms, **growing** | **11.94 ms**, flat |
| XC7A100T DDR3 minor pause | 95.92 ms, **growing** | **19.27 ms**, capped |

"Growing" is the important word — the pause used to increase with the tenured
live set, so no nursery size could have bounded it. It no longer does.

**DDR2 (A-E115FB)** — 1 GB DDR2 verified on hardware, the full stack built:

| piece | status |
|---|---|
| 1 GB memory itself | ✅ 77 passes, ~154 GB, zero errors |
| Half-rate IP (256-bit @ 75 MHz) | ✅ regenerates from a checked-in variation |
| `CacheToDdr2Adapter` | ✅ simulation, 0 mismatches |
| 256-bit cache line | ✅ 7/7 at 32/128/256/512 |
| `ae115fbDdr2` preset + JopTop | ✅ elaborates, no regression to other boards |
| JOP building on the board | ✅ 27% LE, +0.584 ns slack, programs |
| JOP serial handshake | ✅ **fixed and confirmed on hardware** |
| Download > 32 KB | ✅ fixed — the adapter now responds to writes |
| Full GC suite on ~1.07 GB | ✅ JVM 66/66, minor pause 14.1 ms |

## 5. How the serial-boot handshake was fixed (history)

**Resolved and confirmed on all four boards.** Kept because the failure mode is
instructive and the reasoning is reusable.

JOP was sending `0xAA` correctly the whole time — the *receiver* was locked onto
the wrong bit. A gapless stream of 8N1 `0xAA` frames is the repeating pattern
`0010101011`, which offers four phases where a receiver finds a falling edge,
eight data bits and a valid stop bit. `0x4D` is the phase-5 lock (`0x35` and
`0x53` are the other two). Nothing escapes a false lock without an idle line.

The cause was `rdy_send` timing its ACK poll with an instruction count
(`ldi 78`) rather than a real interval. That scales with clock but not with
baud, so at 115200 the loop outran the UART ~4x, the 16-deep TX FIFO never
drained, and the ready bytes went out back-to-back with no idle gap. At 2 Mbaud
on the other two boards the same loop is *slower* than a byte, leaving a ~9 us
gap that lets the receiver resync — which is why this never showed up before.

`rdy_send` now derives a deadline from `io_us_cnt` and tests the sign of
`deadline - now` (wrap-safe). `rdy_timeout_us = 500000` and the counter were
both already defined and unused. Constant-pool cost was zero. `JopCoreBramSim`
still boots to `Hello World!`.

**Confirmed on ALL THREE boards that existed at the time (2026-08-03/04).** The
microcode is shared, so every board was re-run after the change; each emits
`0xAA` at a measured ~0.5 s cadence and completes the handshake. The Colorlight
i5 is listed below too, but it was brought up later (2026-08-05) and so is
independent corroboration on a fourth board rather than part of that run:

| board | clock / baud | ready byte | download | JVM suite |
|---|---|---|---|---|
| A-E115FB (DDR2) | 75 MHz / 1 Mbaud | ✅ 0.51 s | ✅ 0.5 s @ 88 KB/s | ✅ 66/66 |
| XC7A100T (DDR3) | 100 MHz / 2 Mbaud | ✅ ~0.5 s | ✅ 215 KB/s | — |
| EP4CGX150 (SDR) | 100 MHz / 2 Mbaud | ✅ ~0.5 s | ✅ 188 KB/s | ✅ 66/66 |
| Colorlight i5 (SDRAM) | 40 MHz / 1 Mbaud | ✅ | ✅ 4.6 s @ 63 KB/s | ✅ 66/66 |

(i5 row added 2026-08-05, after the three-board run above.)

The i5 could not run the JVM suite in its BRAM stage: `DoAll.jop` is 72,428 words =
**283 KB**, against 64 KB of configured main memory and **126 KB of total EBR on
the LFE5U-25F** — it would not fit even with every block RAM given to main memory
and nothing left for the microcode ROM, JBC cache, stack cache or jump table.
The SDRAM stage (8 MB) removed that limit and it now passes 66/66.

All three return identical checksums for the same image (`0x8f197bc7` for
HelloWorld, `0x2ed0b59a` for DoAll), so the transfers are byte-identical across
boards. The i5's HelloWorld checksum is `0xbdc92b6f`, which does **not**
contradict that: `HelloWorld.jop` is a build artefact (gitignored) and was
rebuilt at 21:25 on 2026-08-04, two minutes after the `wrIntG` fix in `b3fd4e5`
(21:23) — i.e. after the three-board run above. It is a different image, not a
different transfer. Checksums are only comparable across boards for the same
build of the `.jop`. The old instruction-count loop left only a ~9 us idle gap at 2 Mbaud —
which is why those two boards happened to work; the timer version idles ~500 ms
and is baud-independent.

**Two things noted while regressing, neither caused by the microcode change:**
- **XC7A100T timing is marginal**: this build came out at **WNS +0.001 ns**
  (previously +0.117 ns) — placement noise, since the RTL is unchanged and
  microcode is ROM content. One download out of seven produced garbage carve
  values and repeated `Uncaught exception`; it did not reproduce in 5
  consecutive runs afterwards. A regression platform with no timing margin will
  eventually generate false failures — worth re-implementing for margin.
- EP4CGX150 closed at +0.479 ns, 8,386 LE (6%) — healthy.

**Second blocker, found and fixed the same day**: the download hung at exactly
8193 words. 8192 words = 32 KB = the full cache (`LruCacheCore`, 4 ways x 256
sets x 32 B), so word 8192 was the first write that had to evict a dirty line.

Root cause: **`CacheToDdr2Adapter` never responded to writes.** `LruCacheCore`
issues an eviction as a `memCmd` write and then blocks in `WAIT_EVICT_RSP` for a
`memRsp` — its contract is one response per command, writes included, which
`CacheToMigAdapter` honours by pushing a dummy response per write. The DDR2
adapter pushed only from `local_rdata_valid`, which a write never asserts.

The same fix removes a second latent hang: `FILL_DRAIN` waits for
`fillRsp === fillIssued` over the writes `FILL_WRITE` issues, so the GC's
hardware zero-fill (`hasBackendFill = true`) would have deadlocked identically
the first time it ran.

`CacheToDdr2AdapterSim` missed it because it drives the adapter alone and
modelled the DDR2 interface faithfully — where a write *is* fire-and-forget.
New `CacheDdr2EvictSim` wires the real cache to the real adapter at a shrunk
geometry, reproduced the hang (16/17 completions), and now passes 200 line
writes through 184 evictions with full readback verification.

Do not chase these two — both were checked and neither is involved:
`Startup.java:161`'s memory probe (runs only *after* download; `0xAA` is pure
microcode), and the ROM/RAM pair (already `asm/generated/serial/`, per
`JopDdr2Ae115fbTop.summary.txt`).

**Clock**: leave it at 75 MHz. It divides exactly into 1 M, 1.5 M and 3 M baud;
only 2 Mbaud is unreachable (+7.14%), and 83 MHz is worse. Once the handshake is
proven at 115200, 1 Mbaud is a free ~8x download speedup (divider 15, 0.00%
error) — but change one thing at a time.

**The control that still passes**: reprogramming `ddr2_exerciser.sof` prints
`i=1 … e=0000` cleanly. Board, DDR2 and the CH340 path are healthy — the problem
is confined to the JOP design. Keep using that control; it has repeatedly
separated "board broken" from "our design broken".

## 6. GC roadmap — history (superseded by section 1)

1. ~~**GC suite at 1 GB**~~ — **DONE 2026-08-03, all green.** DoAll 66/66,
   GcStressTest 537k rounds clean, MultiArrayGcTest and IntHandlerGcTest OK,
   `free 1,067,359,856 bytes`. Detail in the bring-up doc.
2. ~~**The minor-pause bound is VIOLATED on the A-E115FB**~~ — **FIXED, all
   four boards inside 20 ms** (see the four-board table further down). The
   heading is kept struck through because the investigation below is worth
   reading; the numbers immediately following it are the *starting* state, not
   the current one.

   Measured with `GcPauseTest` (2026-08-04), before any of the fixes:

   | board | fixed us | sweep ns/handle | swept | worst | model predicts |
   |---|---:|---:|---:|---:|---:|
   | EP4CGX150 SDR | 3637 | 1346 | 6168 | **11.94 ms** | 11.94 |
   | XC7A100T DDR3 | 4920 | 1567 | 9687 | **20.11 ms** | 20.10 |
   | A-E115FB DDR2 | 8795 | 1711 | 9687 | **25.38 ms** | 25.37 |

   The model's *shape* is exactly right — `fixed + swept x per-handle` predicts
   all three to within 0.01 ms. Only the constants are wrong:
   `SWEEP_NS_PER_HANDLE` 1600 vs 1346/1567/**1711** measured, and
   `MINOR_FIXED_US` 4500 vs 3637/4920/**8795**. The XC7A100T lands on 20 ms by
   luck: its fixed cost is already over budget (4920) but its sweep is under
   (1567), and the two errors cancel.

   The dominant term is the **root scan**, and it does not track clock — the
   EP4CGX150 and XC7A100T are both 100 MHz yet differ 2.1x (2.211 vs 4.719 ms),
   so it is memory latency. Across SDR -> DDR3 -> DDR2 it is 2.2 / 4.7 / 8.5 ms.

   **Correction to an earlier note here**: adopting the slowest board's numbers
   does *not* tax every board. The EP4CGX150 sweeps 6168 handles — fewer than
   the 9687 cap — because its ~6 MB heap makes the nursery the binding
   constraint, so it is unaffected by any cap change. Only the two large-heap
   boards are cap-bound.

   **APPLIED**: `SWEEP_NS_PER_HANDLE = 1750`, `MINOR_FIXED_US = 8800`, giving
   `MAX_YOUNG_OBJECTS = 6400` (was 9687). Re-measured:

   Four boards, after all the Stage 3 work (constants, tenure-bounded card
   scan, card granularity):

   | board | clock / memory | worst | swept | bound by |
   |---|---|---:|---:|---|
   | CYC5000 SDR | 80 MHz / 8 MB | **10.181 ms** | 6168 | nursery |
   | EP4CGX150 SDR | 100 MHz / 32 MB | **11.943 ms** | 6168 | nursery |
   | XC7A100T DDR3 | 100 MHz / 256 MB | **12.523 ms** | 6400 | object cap |
   | A-E115FB DDR2 | 75 MHz / 1 GB | **14.143 ms** | 6400 | object cap |

   All four now inside the 20 ms target, and `copy` is the dominant phase on
   every one of them (79-82%).

   | board | before | after | swept | status |
   |---|---:|---:|---:|---|
   | EP4CGX150 SDR | 11.942 ms | **11.943 ms** | 6168 | unchanged — nursery-bound, so the cap never binds |
   | XC7A100T DDR3 | 20.109 ms | **14.400 ms** | 6400 | cap now binding, bound holds with 5.6 ms margin |
   | A-E115FB DDR2 | 25.376 ms | **21.633 ms** | 6400 | improved 15%, but **still over the 20 ms target** |

   The EP4CGX150 result confirms the prediction that a global cap change costs
   the small-heap board nothing. The A-E115FB **falsified** the other
   prediction, and in the opposite direction:

   - Predicted <=19.75 ms on the reasoning that the root scan would shrink with
     the young set, as it did on the XC7A100T (4.719 -> 3.847 ms).
   - Measured 21.633 ms, with the root scan **rising** 8.530 -> 10.096 ms.

   Most likely a sampling artefact rather than a real regression: a smaller cap
   means more collections (42 -> 63), so the *worst* of them is drawn from more
   samples. The mean moved the way the model expects — 25.089 -> 19.837 ms, a
   21% improvement, and now under target. Do not read a single worst-case figure
   as the whole story when the collection count changes with the parameter.

   **The "root scan" turned out to be two different scans, and the expensive
   one was pure waste.** `gcTRoots` bundled `getYoungRoots()` (stack + statics)
   with `scanCards()` (dirty-card walk); splitting the timer showed:

   | | A-E115FB |
   |---|---:|
   | stack + static scan | 0.647 ms (3%) |
   | **dirty-card scan** | **7.671 ms (38%)** |

   The obvious optimisation — hoisting `pushYoung`'s statics out of the scan
   loop, per the "statics live in main memory" rule — would have targeted the
   0.647 ms. Measuring first is what stopped that.

   `scanCards` walked the card table across the whole tenure span, but tenure is
   **two used regions with a huge free gap**: compacted data grows up from
   `heapStart` to `copyPtr`, promotions grow down from `tenureTop` to
   `allocPtr`. On the 1 GB board the span scanned was **99.98% free** — 4072
   card-table words to reach 2 words of real work, at ~141 cycles each (two I/O
   accesses per iteration, so no tighter loop would have helped).

   **Fix: scan only `[heapStart, copyPtr)` and `[allocPtr, tenureTop)`.** The
   gap holds no objects, so the write barrier can never mark a card there and
   there is nothing to trace even if a stale bit survived.

   | board | constants only | + card-scan fix |
   |---|---:|---:|
   | XC7A100T DDR3 | 14.400 ms | **12.523 ms** |
   | A-E115FB DDR2 | 19.887 ms | **17.338 ms** |

   Both large-heap boards now clear the 20 ms target with margin;
   `MAJOR OK`, retained 64/64, corrupt 0 on both.

   **Card granularity — done, and it needed no RTL change.** `cardShift` is
   derived as the smallest shift fitting `cardTableBudgetBytes`, so raising the
   A-E115FB budget 16 KB -> 64 KB took cards from 2048 to 512 words, matching
   the XC7A100T's granularity (the same 16 KB budget covers 4x less memory
   there, which is why its card scan was already cheap). Software reads
   `IO_CARD_SHIFT` at runtime, so nothing on the Java side changed.

   Cost: BRAM 15% -> 25% (978,272 bits) on the EP4CE115, timing still closes at
   +0.543 ns, 31,170 LE (27%).

   **A-E115FB minor pause, cumulative:**

   | step | worst | card scan |
   |---|---:|---:|
   | original constants | 25.376 ms | — |
   | retuned constants (cap 6400) | 19.887 ms | 7.671 ms |
   | + scan only used tenure regions | 17.338 ms | 5.122 ms |
   | + 512-word cards | **14.143 ms** | **1.931 ms** |

   **44% off the pause overall**, and comfortably inside the 20 ms target.
   Predicted ~13.5 ms and a 4x card-scan cut; got 14.143 ms and 2.65x — halving
   card size does not quite halve the scanned words, because scattered writes
   dirty proportionally more cards.

   **Next lever is the copy phase**, now **79%** of the pause (11.300 ms) and
   essentially unchanged throughout — see item 4.
3. ~~**Major GC constant**~~ — **FIXED 2026-08-06.** Full history below; the
   outcome is **2214.9 -> 865.6 ms (EP4CGX150) / 689.8 ms (XC7A100T)** at 36k
   live, and the address sort no longer runs at all. Design and validation:
   [gc/major-gc-evacuation.md](gc/major-gc-evacuation.md). Remaining work is
   item 24 (the object-size trade) and item 25 (loose ends).

   The measurement that started it — the recorded next action, time
   `sortUseListByAddress()` separately — on XC7A100T DDR3, 36000 live objects:

   | live objs | pause | mark | compact | **sort** | slide | copy | live words |
   |---:|---:|---:|---:|---:|---:|---:|---:|
   | 6000 | 452.6 | 199.5 | 252.2 | **186.8** | 65.4 | 33.9 | 48244 |
   | 18000 | 1134.5 | 486.3 | 647.3 | **554.6** | 92.8 | 9.4 | 72244 |
   | 36000 | **2214.9** | 915.8 | 1298.2 | **1127.6** | 170.6 | 9.5 | 108244 |

   The merge-sort hypothesis was right: **1127.6 ms of the 1298.2 ms compact
   phase, 51% of the whole pause.**

   **The data copy is 9.5 ms — 0.4%**, and that is the result that changes
   plans. A hardware block-copy engine was the obvious move given the zero-fill
   DMA gets 110.7x on this board; it would take 9.5 ms off 2215. A major GC here
   moves 108k words and spends almost none of its time doing it. **Do not build
   copy acceleration for this pause.**

   **A real defect found while measuring** (bug 29): `push()`/`pushYoung()` ran
   an `imul` bytecode — ~775 cycles of microcode on a preset with no ICU
   multiplier — on every candidate root. Precomputing `handleEnd` took mark
   915.8 -> **422.2 ms** and the pause 2214.9 -> **1720.8 ms**. Remaining at
   36k: sort 1127 (65%), mark 422 (25%), slide 171 (10%), copy 9.6 (0.6%).

   **Next, and not yet chosen** — measure the sort's actual pass count first,
   because per-handle sort cost is *flat* at ~30 µs from n=6024 to 36024 while
   `ceil(log2 n)` goes 13 -> 16, so the obvious `n x passes x constant` model is
   wrong and neither option below can be sized honestly until that is resolved:

   - **Replace the sort** with a linear-time radix distribution over the
     address. Contained, low risk, keeps every existing heap invariant. Each
     pass touches one handle per element (all three words are in the same cache
     line) against the merge sort's two or three scattered handles per step.
   - **Eliminate the sort by evacuating** rather than sliding — what G1,
     Shenandoah and ZGC all do. Source and destination are disjoint, so no
     ordering is needed. JOP suits this *better* than HotSpot: relocating an
     object costs one word (the handle's `OFF_PTR`) instead of a pointer-
     adjustment phase. Cost: needs free >= live, and changes the layout
     invariants `carveNursery` and the tenure-bounded card scan depend on.
     Design note: [gc/major-gc-evacuation.md](gc/major-gc-evacuation.md).

   **Why a desktop JVM does not have this problem**: not software versus
   hardware — the data structure. HotSpot has no handle table, so nothing is
   sorted; references are direct pointers, marking uses a side bitmap, and
   compaction forwards over regions. JOP's handle indirection forces a full
   address sort of every live object on every major GC, and a handle is exactly
   one 256-bit cache line so no walk of the table ever gets spatial reuse. Same
   root cause as item 4.
4. **Copy phase — now the dominant term**, 11.3 ms of a 14.1 ms A-E115FB pause
   (79%) and 10.3 ms of 12.5 ms on the XC7A100T (82%). It barely moved when the
   clock rose because it is **latency-bound, not clock-bound**: 1766 ns/handle
   at 75 MHz is *132* cycles against *162* on the 100 MHz DDR3 board.

   The dead path is already down to two main-memory reads per handle
   (`OFF_NEXT` to walk `youngList`, `OFF_SPACE` to test survivorship) with
   run-splicing removing most writes — so there is no easy fat left. The cost is
   structural: the handle table is **2 MB against a 32 KB cache**, and
   `HANDLE_SIZE` = 8 words = 32 bytes = **exactly one 256-bit cache line**, so
   every handle touched is one compulsory miss with no intra-handle locality.
   ~6400 scattered line fetches to find ~66 survivors.

   Both causes are placement decisions, not algorithmic necessities:
   - `youngList` is a linked list, so walking it needs `OFF_NEXT` from each
     handle. A dense **array of refs** would put 8 per cache line — 8x fewer
     misses for the traversal.
   - The survivor mark lives in the handle (`OFF_SPACE == YOUNG_SURV`), forcing
     a random read each. A dense **bitmap** would be 6400 bits = 200 words
     ~ 25 cache lines instead of 6400.

   Together: ~6400 scattered fetches -> ~825 sequential ones. That is where a
   5-8x copy improvement would come from. **It is a real redesign** of the young
   generation bookkeeping (allocation, marking and sweeping all change), and
   this area has produced subtle premature-collection bugs before — which is why
   `MultiArrayGcTest` and `IntHandlerGcTest` exist.

   **Written up in [gc/copy-phase-redesign.md](gc/copy-phase-redesign.md)** —
   measurements, the structural analysis, a four-stage plan where each stage is
   independently measurable, the constraints that must not break, and the open
   questions (cache pressure being the main one). Not started.

## 7. Hardware setup

| board | cable | how it is programmed |
|---|---|---|
| EP4CGX150 (SDR) | Terasic USB-Blaster (`terasic`) | `quartus_pgm -c "$(jtag_probe_map --cable terasic)"` |
| XC7A100T + DB_FPGA V5 (DDR3) | RP2040 on the DB-V5, pico-dirtyJtag | `openFPGALoader` |
| A-E115FB (1 GB DDR2) | **Terasic** — its Pico clone cannot configure | `quartus_pgm` |
| Colorlight i5 (ECP5, 8 MB SDR) | DAPLink on the ext board (`i5`) | `openFPGALoader -b colorlight_i5` — also the UART bridge |
| CYC5000 (Cyclone V, 8 MB SDR) | on-board Arrow USB Blaster TEI0050 (`cyc5000`) | `openFPGALoader -b cyc5000` on an **.rbf** — see below |
| Alchitry Au V2 (XC7A35T, DDR3) | on-board FT2232H (`alchitry`) | `make -C fpga/alchitry-au program` (Vivado hw target) |

**The Alchitry Au is the smallest part in the set and the only one where fit is
the binding constraint** — XC7A35T, 20,800 LUTs. Brought up end-to-end
2026-08-22 (`auSerial`: build, program, serial download at 2 Mbaud, HelloWorld
and `JbeBench` running). Two things about it are not like the other boards:

- **It needs a 4 KB L2, not the 32 KB default.** With the default it needs
  22,554 LUTs and does not fit. `JopMemoryConfig.l2SetCount = 64` in the
  preset; the size costs nothing measurable — see [item 50](#item-50).
- **Its UART is the FT2232H's interface 1**, sharing vid:pid `0403:6010` with
  the CYC5000's Arrow blaster, so it is resolved by product string as well:
  `usb_serial_map --by-id alchitry`. Reprogram before each download, or use
  `download.py -r` to reset the core over UART without touching JTAG.

**Colorlight i9+ v6.1 (XC7A50T) — evaluated on paper 2026-08-22, not ordered.**
32,600 LUTs, between the Au's 20,800 and the XC7A100T's 63,400, so it would say
where the fit line falls. Its memory is **8 MB of 32-bit-wide SDRAM**
(M12L64322A) — the same width as the Colorlight i5, so `BmbSdramCtrlWide` and
`SdramCtrlNoCke` already cover it (CKE is tied VCC, CS tied GND). Two things to
check before building anything: **DQM0-3 are tied to GND**, so there is no byte
masking and every write is a full 32 bits; and the board itself has **no UART**
— to be solved by a base board carrying an RP2040-stamp for programming and
serial. Board doc: `/srv/git/Colorlight-FPGA-Projects/colorlight_i9plus_v6.1.md`.

Note it is an **SDR** board, so it is not comparable with the Au or the Wukong
DDR3 builds — `createSdr` has no L2 at all. The like-for-like comparison would
be the Artix-7 SDR path (`wukongSdrFull` / `JopSdramWukongTop`), not a DDR3
preset with its part-specific MIG.

The **Alchitry Io V2** daughter board (24 LEDs, 24 DIP switches, 5 buttons,
4-digit seven-segment) is fully pin-mapped in `Board.scala` and wired into
`SystemAssembly.alchitryAuV2WithIo` — but **no preset selects it**, so nothing
drives it. Its only reference outside its own definition is `JopConfigTest`.
Same species as the microcode-fallback coverage gap in item 18: config no
preset selects gets no coverage.

**The CYC5000's Arrow blaster needs two things nobody wrote down.** It is an
FT2232H (`0403:6010`), so its vid:pid is shared with every other FTDI dual-UART
part — `jtag_probe_map` identifies it by serial (`ARA…`) and product string.
`openFPGALoader` needs neither of the following and is the easy path; Quartus
needs both:
- `libjtag_hw_arrow.so` in `quartus/linux64/`. It does **not** ship with
  Quartus in any version — it comes from Arrow/Trenz and is copied in by hand.
  It works in 18.1 as well as 25.1.
- `ftdi_sio` unbound from **interface 0 only**:
  `echo -n 1-8:1.0 | sudo tee /sys/bus/usb/drivers/ftdi_sio/unbind`, then
  `pkill jtagd`. Interface 1 must stay bound — that one is the FPGA UART.
  With the driver present but `ftdi_sio` still attached, `jtagconfig` lists
  nothing, which is what made "Quartus cannot see this board" look true.

Also: `openFPGALoader` refuses a `.sof` for SRAM programming, so the CYC5000
flow converts to `.rbf` with `quartus_cpf` first.

**Why the Pico USB-Blaster clone cannot configure either Altera board — it has
no level shifter.** Pin 4 of the Altera 10-pin JTAG header is VCC(TRGT); a
genuine USB-Blaster powers its output buffers and sets its input thresholds from
that pin, which is how one cable spans 1.5-5 V targets. The Pico is fixed 3.3 V
in both directions. The EP4CGX150's JTAG bank is **2.5 V** (TDI/TMS 10 k pull-up
to 2.5 V, TCK 1 k pull-down, TDO no pull), so:
- Pico -> FPGA (TCK/TMS/TDI) drives 3.3 V into a 2.5 V bank, forward-biasing the
  input clamp diodes into VCCIO. Out of spec and potentially damaging.
- FPGA -> Pico (TDO) presents 2.5 V into an RP2040 input whose V_IH is ~0.65 x
  IOVDD ~ 2.15 V — only ~0.35 V of margin, marginal at 6 MHz over flying leads.

This explains the whole symptom set: IDCODE reads fine (short burst), sustained
shifts corrupt after the first byte, and a 3.5 MB configuration stream never
completes. **No firmware change can fix it** — three were tried and all failed.
Fix is a fixed-direction translator (74LVC8T245 / 74LVC2T45) with VCCB taken
from header pin 4. Avoid TXS0108E (open-drain oriented, poor at 6 MHz push-pull).

One real firmware bug *was* found and fixed on the way: `gpio_init()` leaves
RP2040 pads pull-DOWN, but a target's TDO is high-Z outside Shift states, so a
genuine Blaster reads 1 where the clone read 0. `gpio_pull_up()` on TDO and
DATAOUT now makes the bit-bang reads match the genuine cable byte-for-byte
(verified by usbmon capture). Diagnostic tool: `pico-usb-blaster/jtag_pintest.c`,
which selects the probe by bus:dev — necessary because a genuine cable and the
clone share VID:PID 09fb:6001.

`fpga/scripts/jtag_probe_map` resolves board → USB serial → the selector each
tool needs; `usb_serial_map` does the same for tty devices. **Never hardcode port
paths** — they move on every replug. An alias names whatever the serial is
attached to: a Pico soldered to a board names that board, but the Terasic is a
cable that moves.

## 8. Traps that cost real time — worth reading before debugging hardware

- **Ghost USB devices.** The VM held stale passthrough entries: a physically
  disconnected board still appeared in `lsusb`/sysfs, and writes to it
  "succeeded" at a plausible rate while reaching nothing. This invalidated a
  full day of firmware theories. **Tell: transfers succeed but produce no
  observable effect, and no firmware change alters the throughput.** Disconnect
  and replug everything.
- **Tools that open the first matching VID:PID.** A genuine Altera cable and the
  Pico clone are both `09fb:6001`. `openFPGALoader` ignores `--busdev-num` on
  *both* its dirtyJtag and usb-blaster backends; `program_fpga` used
  `libusb_open_device_with_vid_pid` (now patched to take `bus:dev`).
  `quartus_pgm -c "<cable name>"` selects correctly.
- **The A-E115FB LEDs lie.** The board auto-loads a factory EPCS demo at
  power-up that blinks LED0 and lights the rest — indistinguishable from a
  running design. LED0 blinked with the FPGA *unconfigured*, which is what
  exposed it. Use the UART.
- **Small UART dividers quantise badly.** `UartCtrl` divides by `baud x 5
  samples`; at 75 MHz a 2 Mbaud request gives a divider of 7.5 -> 7 = +7%, far
  outside tolerance and unframeable at any host rate. Keep the divider large.
- **Generational GC is UNSOUND on any preset without `hasCardTable`, and it
  fails silently.** `GC.USE_GENERATIONAL` defaults to true, but the card table
  is per-preset. Without it `JopCore` drives `cardRdData := 0`, so
  `IO_CARD_SHIFT` reads 0, every card read returns 0, and `scanCards` finds
  nothing — the remembered set is permanently empty, every tenured->nursery
  reference is invisible to the minor collector, and those young objects are
  collected while still live. Measured on the CYC5000: **copied 3 survivors
  instead of 66, `corrupt 23`, `MAJOR FAIL`** — while **`DoAll` passed 66/66 on
  the same bitstream minutes earlier**. The mutator cannot see the damage; only
  the collector can. `GcPauseTest`'s verify step is the only thing in the suite
  that catches it.
  **Only three standalone presets set it**: `ep4cgx150Serial`,
  `xc7a100tDbSerial`, `ae115fbDdr2` (plus derivatives). Seventeen do not,
  including every Wukong preset, `max1000Sdram`, `auSerial`, `ep4ce6Sdram` and
  `xc7a100tDbFull` — so any GC result recorded on those boards after
  generational became the default should be treated as suspect.
  **GUARDED as of 2026-08-04.** `GC.init` now reads `IO_CARD_SHIFT` before
  laying out the heap and sets `genActive = USE_GENERATIONAL && shift != 0`
  (hardware never reports below `cardMinShift = 2`, so 0 is an unambiguous
  "absent" sentinel). Without a card table it falls back to the classic
  mark-compact collector, which needs no remembered set and is always safe.
  Verified both ways on the CYC5000 — the same configuration that gave
  `corrupt 23 / MAJOR FAIL` now reports
  `GC: classic (no card table - generational disabled)` with `corrupt 0`,
  `MAJOR OK`.

  **The collector is now named at boot**, so the mode is visible instead of
  inferred from a corrupted heap later:

  ```
  GC: generational, 64-word cards     <- CYC5000
  GC: generational, 512-word cards    <- XC7A100T, A-E115FB
  GC: classic (no card table - generational disabled)
  ```

  Seeing `GC: classic` on a board you expected to be generational is the signal
  that `hasCardTable` is missing from its preset. The other sixteen presets are
  now *safe* but still *slow* — they run classic, so add `hasCardTable` to any
  board where generational performance is wanted.
- ~~**`GC.wrIntG` prints only the low 5 digits.**~~ **FIXED.** It started at
  `if (v >= 10000)`, so any value >= 100000 was silently truncated — on a 1 GB
  board that is every heap figure it prints. A `[carve ...]` line read as a
  ~500 KB heap when the real values were `hStart=535768`, `hSize=267891496`,
  `nSize=1048576`. `GcStressTest` had its own copy of the same printer, which
  wrapped both the round counter and `GC.freeMemory()`. Both now print the full
  32-bit range (and handle `Integer.MIN_VALUE`, which the old code both
  truncated and mis-negated). Verified on the A-E115FB: `f=1067369664`.
  **Related trap that this exposed**: the `[carve ...]` line was appearing at
  all only because `java/apps/Smallest/HelloWorld.jop` was a stale build from
  when `GEN_TRACE` was true. `GEN_TRACE` is `false` in the current source. That
  is the documented "make does not reliably rebuild apps" gotcha showing up as
  a phantom debug line — rebuild the app before trusting anything it prints.
- **A component testbench can model the hardware correctly and the contract
  wrongly.** `CacheToDdr2AdapterSim` faithfully reproduced the DDR2 local
  interface, where a write completes on `local_ready` and returns nothing — and
  passed, while `LruCacheCore` above it was deadlocked waiting for a write
  response. **An adapter has two interfaces; a testbench that only models the
  far one proves half of it.** Wire the real consumer in.
- **Measure completions, not acceptances.** `LruCacheCore` has a 4-deep input
  FIFO, so `frontend.req.ready` keeps asserting after everything behind it has
  stopped. The first version of `CacheDdr2EvictSim` reported PASS on a fully
  deadlocked cache for exactly this reason.
- **A gapless UART stream can lock a receiver into a stable wrong byte.** Sent
  back-to-back with no idle line, one repeated value has several phases that
  yield a valid start bit, eight data bits and a valid stop bit; the receiver
  picks one and never leaves. `0xAA` has three such false locks — `0x35`,
  `0x4D`, `0x53`. **Tell: perfectly clean framing, no aliasing, wrong content,
  and the byte is not explainable by any baud error.** Before blaming the
  sender, check that the line ever goes idle. Any handshake that repeats a byte
  needs its interval timed in real units (`io_us_cnt`), never in instruction
  counts — an instruction count tracks the clock but not the baud, so it
  silently inverts when either changes.
- **`make -C java all` does not reliably rebuild apps.** Force
  `make -C java runtime && make -C java/apps/<X> clean && make -C java/apps/<X> [APP_NAME=Y]`.
  Stale `.jop` files have produced both false passes and false failures.
- **JOP keeps statics in main memory**, so `getstatic`/`putstatic` are memory
  accesses. Hoisting a static out of a hot loop is a real optimisation.
- **`OFF_TYPE` is only read by the collector**, never by `iaload`/`iastore`. An
  array can be completely broken for GC while working perfectly for the mutator —
  which is why DoAll's `MultiArray` passed throughout the multianewarray bug.
- **`checkcast` is not implemented for array types**: `(int[]) someObject` throws.
- **Don't count `dmesg` lines to judge USB stability** — the ring buffer evicts
  them and the count falsely holds constant. Watch `devnum` instead.
- **A flaky USB cable looks exactly like a design failure.** During the
  2026-08-04 `GcPauseTest` run the EP4CGX150's CP2102N vanished from the bus
  mid-test; `download.py` died with "device reports readiness to read but
  returned no data". Nothing in software touched it — the device was simply
  gone from `usb_serial_map` and only returned, at a new devnum, after a
  physical replug. **Tell: the port disappears entirely rather than erroring,
  and it happens partway through sustained traffic rather than at open.**
  **Confirmed to be the cable**: after swapping it out, a `GcStressTest` soak on
  the same board ran **704,984 rounds** (~7M allocations, 10.5 MB of continuous
  2 Mbaud output over ~5 min) with zero serial errors and the devnum constant
  across 145 samples taken every 2 s — far heavier traffic than the run that
  failed. Before believing a mid-run failure on any board, check the tty still
  exists and its devnum has not changed.
- JVM tests deliberately fire hardware exceptions; judge by ok/fail text and
  `JVM exit!`, not `excFired`. Grepping for "exception" also matches the test
  *name* `HwExceptionTest`.
- **Testbench: decide acceptance from the value the DUT actually saw** at that
  edge. Randomising a `ready` signal and then using the new value to decide
  whether the current command was accepted produces duplicated transactions that
  look exactly like an RTL ordering bug.

## 9. Build quick reference

**Running a benchmark on a board** — `fpga/scripts/run_bench <board> <bitstream>
<app.jop> [seconds]`, for `ae115fb` / `wukong` / `ep4cgx150`. It reprograms
first (mandatory: a previous download consumes the ready handshake and the board
then looks dead), asserts the IDCODE (the two Altera boards share one cable),
and picks the per-board UART and baud. (It carried the Wukong at 2037000 until
2026-08-18 — not a typo but a rounding error, now fixed at source by
`jop.io.UartBaudTick`; bitstreams older than that date still need it.) `fpga/scripts/scale_parse.py <log>` recovers `jbe.Scale` results from
the per-core timers rather than the printed `AGGREGATE`, because the CH340 drops
a character every few hundred at 2 Mbaud and has already mangled that line.


```bash
# GC / test apps.  Use `rm -rf build`, NOT `make clean`: clean deletes
# HelloWorld.jop, because JOP_OUT derives from APP_NAME (item 13).
make -C java runtime
rm -rf java/apps/Small/build && make -C java/apps/Small APP_NAME=GcPauseTest
rm -rf java/apps/JvmTests/build && make -C java/apps/JvmTests      # DoAll

# A-E115FB DDR2
cd fpga/a-e115fb-ddr2
make ip                        # regenerate the DDR2 IP (needs Quartus 18.1)
make PROJECT=jop_ddr2 all      # or PROJECT=ddr2_exerciser for the memory test
/opt/altera/18.1/quartus/bin/quartus_pgm -c "USB-Blaster [1-5]" -m JTAG -o "p;output_files/jop_ddr2.sof"
```

**Program, then download — in that order, every time.** The serial bootloader
listens once, right after configuration, so a failed download needs a reprogram
before the retry (the script's own retry cannot work).

| board | program | download |
|---|---|---|
| EP4CGX150 | `make -C fpga/qmtech-ep4cgx150-sdram program` | `… /dev/ttyUSB0 2000000` |
| XC7A100T | `make -C fpga/qmtech-xc7a100t-dbfpga-v5 ddr3-program` | `… /dev/ttyACM0 2000000` |
| Colorlight i5 | `make -C fpga/colorlight-i5 program` | `… <DAPLink by-id> 1000000` |
| CYC5000 | `make -C fpga/cyc5000-sdram program` | `… /dev/ttyUSB2 2000000` |
| Wukong | `openFPGALoader -c dirtyJtag --busdev-num "$(jtag_probe_map --busdev wukong)" <bit>` | `… /dev/ttyUSB3 1000000` |

**The Wukong needs the PATCHED openFPGALoader** (see `jtag_probe_map`'s header):
its Pico 2 W runs dirtyJtag, so two dirtyJtag probes are attached and stock
openFPGALoader silently takes whichever enumerated first. Its Makefile's
`UART_BAUD := 1000000` is right only for a bitstream built after 2026-08-07;
older ones are 2 Mbaud, where the on-board CH340N drops characters.

```bash
python3 fpga/scripts/download.py -e <app.jop> <tty> <baud>
```

**The baud is baked into the bitstream** — the UART divider is fixed at build
time, so downloading at the wrong rate simply never handshakes. It is 2 Mbaud
everywhere except the i5, which is 1 Mbaud over the DAPLink.

**Port paths above are examples, not constants.** They renumber on every replug
— the XC7A100T's `ttyACM0` and the CYC5000's `ttyUSB2` both moved during a
single session. Re-resolve with `fpga/scripts/usb_serial_map` (tty) and
`fpga/scripts/jtag_probe_map` (JTAG) rather than trusting this table.
