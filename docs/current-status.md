# Where we are — 2026-08-18

> ## How to read this file
>
> | you want | go to |
> |---|---|
> | **what to work on next** | [§1 Outstanding now](#1-outstanding-now--in-priority-order) — 57 entries, highest first |
> | **whether item N is open** | [§2 All items](#2-all-items--summary) — the scannable index |
> | **why item N is the way it is** | [§3 Item detail](#3-item-detail-and-journals) — summary and gotchas per item |
> | **the full investigation** | `docs/status/item-N.md` — linked from each item that has one |
>
> **Item numbers are stable IDs**, not reading order, and they are referenced
> from other documents and from commit messages. `#item-N` anchors resolve for
> all of them.
>
> **The 18 longest journals live in `docs/status/`** since 2026-08-31 (item 116).
> This file was 9,671 lines and the review auditing it had to be told not to
> open it whole. The summary, the verdict and the gotchas stay here; the
> narrative of how it was found moved out. Nothing was deleted — the split was
> verified byte-identical.
>
> `make check-build` asserts every item is anchored, no anchor is defined twice,
> every `#item-N` link in the repo resolves, no CLOSED item sits in the priority
> list, and every journal is present and linked.


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
**Reordered 2026-08-31, later the same day.** It briefly led with hardware
blockers "because #100 blocks the primary board and #101 cannot be measured
until it is fixed". Both clauses died within hours: the cable swap made #100
10/10 and #101 was resolved from evidence that already existed. The entry for
#100 still asserted "3 detections in 10 — blocks the primary board" after both
had happened, which is this document's characteristic failure in miniature.

What leads now is the work that CORRECTS or PROTECTS the record — an overstated
closure (#109), evidence being thrown away (#114), the corpora no review has
seen (#110), and the fact that nothing proves a test can fail (#111). Broken
capabilities (#82, #68, #65) rank below them: they are real, but nothing depends
on them, and none of them can mislead anyone.

> **Reconciled 2026-08-31.** This list had stopped at item 64 while items 65-101
> existed only as detail sections — **37 items invisible to the section that
> calls itself the ground truth**, which is most of what was filed during the
> build port. Ten genuinely open ones have been slotted in (#100, #101, #82,
> #68, #65, #84, #73, #70, #67, #75); seven that had since closed were removed
> (#9, #10, #50, #57, #59, #60, #61). The rest of 65-101 are work records or
> were already closed, and live in section 2.
>
> `make check-build` now asserts that every item has an anchor, that no anchor
> is defined twice, and that every `#item-N` link in the repo resolves — none of
> which was true before: anchors stopped at 59, `item-46` and `item-47` were
> each defined twice by a pasted block, and 36 numbers were linked but
> unanchored.

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

1. **[#131](#item-131)** — The card-table clear-all DROPS every concurrent mark and the GC resumes the mutators ~3,900 cycles before the sweep ends. Unsafe direction; matches a mystery already recorded in `GC.java`. Verified
2. **[#132](#item-132)** — The card-table read port is stolen by ANY write, and the "all cores halted" invariant that makes that safe is deliberately broken by the lock-owner exemption. Verified
3. **[#133](#item-133)** — The microcode was never taught the stack cache exists: non-resident reads return 0 and non-resident writes are dropped, and the GC root scan, `athrow` and the context switch all walk the whole stack. Live on every single-core DDR3 build
4. **[#130](#item-130)** — `JopTop` silently overrides four `memConfig` fields the preset declares, so presets, summaries and harnesses describe a different machine than the one built. Verified against elaborated RTL
5. **[#129](#item-129)** — `arraylength` has no null check: `null.length` reads word address 1 and returns it as a length, so a loop over a null array runs instead of throwing. Hardware-confirmed
6. **[#110](#item-110)** — Three corpora have never been reviewed (~106k lines: runtime, tools, RTL, microcode). The frem defect lived on a boundary a single-corpus review cannot see
7. **[#127](#item-127)** — The boundary review is unfinished: five done, four more named and scoped (class-struct layout, GC↔card-table hardware, JOPizer↔runtime, stack cache↔microcode)
8. **[#119](#item-119)** — The object handle layout is re-expressed in ~25 places across four languages, and the RTL's only use of it has no test, no formal property and no elaboration check
9. **[#121](#item-121)** — Absent devices all resolve to 0x80 and the `HAS_*` flags meant to guard them are read by nothing
10. **[#122](#item-122)** — `JopCore` and `ConstGenerator` run the I/O allocator over different device sets
11. **[#124](#item-124)** — On Altera the microcode comes from the `.mif`, one call site decides which variant, and the summary omits it
12. **[#125](#item-125)** — `run_bench` hardcodes the baud per board, uses port paths, and can drive the wrong board
13. **[#126](#item-126)** — The baud derivation is never exercised by its own guard: `check-console-baud.sh` passes `BAUD=` explicitly, so `console.mk`'s grep/awk never runs against a real summary
14. **[#111](#item-111)** — Nothing measures whether a test CAN fail. Three guards written this week passed vacuously before being corrected
15. **[#113](#item-113)** — `cold-check` covers 3 boards of 12, and the primary board is not one of them
16. **[#112](#item-112)** — `ConstraintDriftTest` covers 2 presets of 46 — the strongest check in the tree, at 4 % coverage
17. **[#82](#item-82)** — Flash boot has been unbuildable since 2026-03-13; a hardware-verified capability deleted as collateral
18. **[#68](#item-68)** — Ethernet links at 1 Gbps but no packets move
19. **[#65](#item-65)** — Both SD exercisers fail on hardware, and it is not the conversion
20. **[#84](#item-84)** — No MAX1000 configuration fits the 10M08 — single-core overflows it by a third
21. **[#73](#item-73)** — `ep4cgx150DbVgaDma` misses timing by −1.011 ns
22. **[#54](#item-54)** — Statics are Kfl's largest stall category (41 %) and no cache touches them. Count the accesses before designing anything
23. **[#55](#item-55)** — The core stalls on writes whose result it never uses — `idle/direct`, 39 % of Kfl stall. Needs read-after-write forwarding and an SMP story
24. **[#37](#item-37)** — The method cache dominates real memory traffic — 62 % of DoApp's BMB transactions, and [50](#item-50) confirms it in TIME on real memory: bytecode fill is 47-63 % of stall on Kfl and UdpIp
25. **[#64](#item-64)** — `GcStressTest` loses **0.42 bytes/round**, at the same rate on two boards and two memory systems. Deterministic, so it is a defect, not drift — and three candidate causes need ONE measurement to separate
26. **[#4](#item-4)** — Copy phase — 79-82% of the minor pause and the dominant remaining term
27. **[#39](#item-39)** — The L2 hit path is serial — 3 cycles per hit, 58-61 % of the DRAM access interval. **[50](#item-50) raises the priority of this**: bytecode fill is a sequential burst and improved only 3 % with a 32 KB L2 in front of DDR3, which is what a 3-cycle hit would predict
28. **[#44](#item-44)** — The compute floor C is per-configuration; re-measure it before trusting any per-operation cost
29. **[#45](#item-45)** — ONE unidentified register is read before it is written; the other ~401 look benign
30. **[#32](#item-32)** — UART corruption on seed 871203250 — no longer reachable, pin removed; cause never found
31. **[#5](#item-5)** — The BMB arbiter sets the clock ceiling — FREQUENCY, not core count
32. **[#31](#item-31)** — The BMB arbiter caps TIMING CLOSURE on both FPGA families (not throughput — see 2026-08-18 note)
33. **[#41](#item-41)** — Neither 8-core DRAM build closes timing, MSHRs or not
34. **[#70](#item-70)** — The UART baud is stated three times; console.mk now refuses an unknown one, but the i5 still hardcodes its own
35. **[#67](#item-67)** — `ep4cgx150DbFull` has `useStackCache` off; gated on item 14
36. **[#75](#item-75)** — `ep4cgx150HwMath` is a byte-identical duplicate of `ep4cgx150Serial`
37. **[#104](#item-104)** — The generators restate board facts as literals: one board's PLL shape, a device-wide I/O standard, a clock port spelled three ways
38. **[#105](#item-105)** — Assembly navigation uses `boards.head` where it means `fpgaBoard`; safe only because every composite lists the core board first
39. **[#106](#item-106)** — The device map is keyed by raw strings in three places — the surviving family of the `"eth"`/`"ethernet"` bug
40. **[#107](#item-107)** — `alchitry-au`'s `bitstream` has no prerequisite on `project` — racy under `make -j`
41. **[#3](#item-3)** — Sixteen presets still run classic GC. Safe but slow
42. **[#53](#item-53)** — 4-core Wukong takes `15/6` + `double:java` (64 blocks, DoAll 66/66, 68.5 % LUT). **The preset still does not build at defaults** — threshold needs the 8/12-core data
43. **[#52](#item-52)** — The Java tools hold hand-copied duplicates of the hardware config. Generate them from the preset instead
44. **[#17](#item-17)** — `needs*Compute` predicates understate compute-unit reachability
45. **[#18](#item-18)** — Software/microcode fallback coverage is uneven — 18 of 32 configurables
46. **[#19](#item-19)** — Write the missing `_sw` microcode handlers
47. **[#20](#item-20)** — Decide whether the double group gets microcode at all
48. **[#27](#item-27)** — The `aastore` type check's cost was never measured
49. **[#12](#item-12)** — `LongComputeUnitConfig` has no enable flag for its base 64-bit ALU
50. **[#7](#item-7)** — Root-scan floor: 2.2 / 4.7 / 8.5 ms across SDR / DDR3 / DDR2
51. **[#8](#item-8)** — XC7A100T timing margin is +0.001 ns — one bad run in seven
52. **[#14](#item-14)** — Stack cache SDRAM integration — 3-bank rotation verified in BRAM, needs per-core regions
53. **[#40](#item-40)** — A leaner MSHR entry — each holds a full cache line of write data a read miss never uses
54. **[#42](#item-42)** — Secondary-hit merging is not implemented — a request to a line being filled replays
55. **[#21](#item-21)** — Colorlight i5 is EBR-bound in BRAM-only builds, not logic-bound
56. **[#11](#item-11)** — Application benchmark exists (`java/apps/JbeBench`) — remaining questions it should answer
57. **[#13](#item-13)** — `java/apps/Small` `make clean` deletes `HelloWorld.jop`
58. **[#56](#item-56)** — WBNI: derive the hardware config from the application. **JOPizer static profile DONE**; the remaining bulk is a measurement FRAMEWORK (preferably Java) across the hardware set
59. **[#58](#item-58)** — `source` inside an XDC is silently ignored — SDRAM IOB packing and Ethernet GMII constraints have never been applied
60. **[#117](#item-117)** — Nothing prevents an eighth preset that no flow selects
61. **[#115](#item-115)** — Every simulation reports `Elaboration failed (2 errors)` and then succeeds; pre-existing, deterministic, unexplained
62. **[#118](#item-118)** — The nightly CI run was cancelled after 1h4m; item 47's failure mode, and its failure mode is silence
63. **[#108](#item-108)** — README's 16-core claim rests on resource figures README itself withdrew as undated
64. **[#100](#item-100)** — The EP4CGX150 cable reads 10/10 since the 2026-08-31 swap and blocks nothing. What is left is an unresolved confound: the swap changed the cable AND re-seated both plugs, so put the Pico back on that board to confirm re-seating was the cure
65. **[#63](#item-63)** — One unexplained Wukong SDR startup crash in six runs; not reproduced, cause unknown
66. **[#62](#item-62)** — `JopFloatCuBramSim` reads a `floatcu` microcode variant that has never been generated, so it has never run

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
- **[58](#item-58)** — `source` inside an XDC is silently ignored by Vivado — four shared constraint files never applied
- ~~**[61](#item-61)**~~ — FIXED 2026-08-24 — no app in `apps/Small` built from clean; the runtime is now bulk-compiled and the app named
- **[62](#item-62)** — `JopFloatCuBramSim` reads a microcode variant that does not exist, so it has never run
- **[63](#item-63)** — One Wukong SDR startup crash in six runs, not reproduced — recorded so a second sighting is not treated as the first
- **[64](#item-64)** — `GcStressTest` free memory declines monotonically at 0.42 B/round, identically on the i5 and the EP4CGX150
- **[65](#item-65)** — Both SD exercisers fail on hardware — `ACMD41` times out. NOT the build-tree conversion: identical at the old clock
- ~~**[66](#item-66)**~~ — The EP4CGX150's Ethernet/VGA/SD was lost in migration `8641942` — **preset written back 2026-08-25, pin-identical to the historical project, 15,270 LE, all clocks MET.** Found a dead `"eth"` vs `"ethernet"` predicate that had silently dropped every `set_clock_groups`
- **[81](#item-81)** — Build port, phase 0 — the ten superseded project files deleted, and three leftovers from my own conversions
- **[82](#item-82)** — **Flash boot has been unbuildable on BOTH boards since 2026-03-13** — a working, hardware-verified capability removed as collateral by a refactor (OPEN)
- **[83](#item-83)** — Build port, phase 1 — the board-by-board flow audit, and a live wrong-board programming hazard on the DB_FPGA V5
- **[84](#item-84)** — No MAX1000 configuration is known to fit — a 1- or 2-core 10M08 setup is wanted (OPEN)
- **[85](#item-85)** — Build port, phase 2 — the SDRAM exerciser folded onto the shared flow, and the baud it never reported
- **[86](#item-86)** — Build port, phase 3a — three shared Vivado scripts, proven equivalent by control build; and the DB_FPGA DDR3 build has quietly got 43 % smaller
- **[100](#item-100)** — Newcomer hardware path verified on the i5 and A-E115FB; the EP4CGX150's level-shifted Pico cable is INTERMITTENT (3 detections in 10, bursty) — first diagnosed as failed, which single-sample testing made look certain (OPEN)
- **[87](#item-87)** — Build port, phase 3b — `vivado.mk` finally has a user, and it found a live baud bug and a latent one
- **[88](#item-88)** — CI "formal verification failure" was a yosys cache that hits and rebuilds anyway — not the push, not the proofs
- **[89](#item-89)** — Build port — the DB_FPGA V5 was the last board generating in-tree; now on `vivado.mk` and the build tree
- **[90](#item-90)** — Build port — the Alchitry Au converted; and a cosmetic-warning "fix" that unconstrained the UART
- **[78](#item-78)** — the A-E115FB DDR2 project is generated now, and building it found four separate defects
- **[77](#item-77)** — the EP4CGX150 SDRAM Makefile is converted: 701 → 195 lines, and ~150 of those lines were flows DEAD since March
- **[76](#item-76)** — the 4-core BRAM SMP stall no longer reproduces, and timing was tested as the cause and REFUTED
- **[75](#item-75)** — `ep4cgx150HwMath` generates byte-identical RTL to `ep4cgx150Serial` — a preset that expresses nothing, with a test that passes trivially
- **[73](#item-73)** — `ep4cgx150DbVgaDma` misses by −1.011 ns on SDRAM command-FIFO arbitration between the core and the VGA DMA — OPEN
- **[72](#item-72)** — `JopTopVerilog` gave every FPGA build the SERIAL microcode regardless of the config's boot mode — FIXED
- **[71](#item-71)** — All three EP4CGX150 **BRAM** presets missed timing at 80 MHz; the BRAM read-data path will not close there — FIXED by reclocking, `hw_verify` now refuses violated bitstreams
- **[70](#item-70)** — UART baud is stated in THREE places that disagree — preset override, a 2 Mbaud default, and 12 Makefile constants. Pick one rate and derive the rest
- **[68](#item-68)** — EP4CGX150 Ethernet: link comes up at 1 Gbps but NO packets move. DHCP times out against a server that IS on that switch
- **[67](#item-67)** — `ep4cgx150DbFull` runs with `useStackCache = false`; the original had it true. Revisit once stack-cache SDRAM integration lands
- **[4](#item-4)** — Copy phase — 79-82% of the minor pause and the dominant remaining term
- **[5](#item-5)** — The BMB arbiter sets the clock ceiling — FREQUENCY, not core count
- **[7](#item-7)** — Root-scan floor: 2.2 / 4.7 / 8.5 ms across SDR / DDR3 / DDR2
- **[8](#item-8)** — XC7A100T timing margin is +0.001 ns — one bad run in seven
- **[31](#item-31)** — The BMB arbiter caps TIMING CLOSURE on both FPGA families (not throughput — see 2026-08-18 note)
- **[11](#item-11)** — Application benchmark exists (`java/apps/JbeBench`) — remaining questions it should answer
- **[45](#item-45)** — ONE unidentified register is read before it is written; the other ~401 look benign
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

- ~~**[57](#item-57)**~~ — The XDC/QSF generators exist and nothing uses them — DONE 2026-08-31; every board reads generated constraints, and the tracked files are now ConstraintDriftTest's oracles
- ~~**[60](#item-60)**~~ — Everything generated belongs under `build/<config>/` — three FPGA flows and the Java/JOP tree done and verified, 48 flows and `asm/` to go
- ~~**[80](#item-80)**~~ — `PerfCounterVerifySim` fails on an unassigned ICU register — pre-existing, confirmed by bisect
- ~~**[95](#item-95)**~~ — The README advertises 13 simulation commands; CI watched a different set, and the gap is where four broken sims were hiding — **CLOSED: CI executes the README itself**
- ~~**[96](#item-96)**~~ — `JopCoreWithSdramSim` stalls — WITHDRAWN 2026-08-30, never a bug: the cycle budget was 19x short, and the three-PC steady state was a slow boot seen through a narrow window
- ~~**[97](#item-97)**~~ — `JopSmpBramSim` runs 100 M cycles, both cores boot, then fails its own `GC test start` check (OPEN)
- ~~**[98](#item-98)**~~ — `JopInterruptSim` fires 2 of 5 interrupts — deterministic, reproduced twice (OPEN)
- ~~**[99](#item-99)**~~ — `JopDebugProtocolSim` NPEs during **elaboration** — `JopCluster.gcRootRamAddr()` is null (OPEN)
- ~~**[74](#item-74)**~~ — item 69's scope was too narrow: `"float" -> "hw"` hits the `frem` trap too, not just `"*" -> "hw"` — and `frem` is absent from the bytecode table
- ~~**[69](#item-69)**~~ — `DoAll` died at `FloatTest` on `wukongFull`. NOT because `"*" -> "hw"` forced hardware for `frem` (it could not — `frem` was absent from the registry): the wildcard drove `SUPPORT_FLOAT` false, which dropped the SoftFloat library `frem` runs in. FIXED 2026-08-31
- ~~**[9](#item-9)**~~ — Pico USB-Blaster needs a level shifter (74LVC8T245 or 2x 74LVC2T45)
- ~~**[10](#item-10)**~~ — pico-usb-blaster protocol bug — low-level shift works, Quartus handshake does not
- ~~**[29](#item-29)**~~ — ~~`BytecodeFetchStage: JumpTable integration` is flaky in CI~~ — **FIXED** (X-state)
- ~~**[30](#item-30)**~~ — ~~`JopJvmTestsBramSim` — the CI baseline job — intermittently dies~~ — **FIXED** (X-state)
- ~~**[49](#item-49)**~~ — ~~The UART divided the clock by an integer, so the baud was only right on lucky clocks~~ — **FIXED** (`UartBaudTick`)
- ~~**[48](#item-48)**~~ — ~~No runtime reset: the FPGA had to be reprogrammed before every download~~ — **DONE** (UART escape + button)
- ~~**[46](#item-46)**~~ — ~~`formal-verification` fails intermittently~~ — **ALREADY FIXED** 2026-08-15 (`6bce639b`, formal timeout 300→900 s)
- ~~**[47](#item-47)**~~ — ~~A push cancelled the nightly scheduled CI run~~ — **FIXED** (concurrency group)

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

**[Full journal →](status/item-1.md)** — 1837 lines of investigation detail.

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

**[Full journal →](status/item-34.md)** — 385 lines of investigation detail.

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

### Item 9 — ~~Pico USB-Blaster needs a level shifter~~ — BUILT, then REGRESSED

> **Status 2026-08-31.** The level shifter exists and worked: the
> `pico-usb-debug-jtag` carrier (SN74LVC1T45 per signal, referenced to the
> target's VTREF on header pin 4) configured a 4.93 MB `jop_sdram.sof` on the
> EP4CGX150 in 43 s on 2026-08-29, and the board then ran HelloWorld.
>
> **It regressed the next day** — 1 detection in 10, `found 0xFFFFFFFF` (a
> floating TDO), programming failing after 410 s. VTREF measures 2.53 V, so the
> shifter has its reference.
>
> **This item is closed** — the shifter was the ask, and it exists and is
> proven. The cable's dependability is a separate problem and is
> [item 100](#item-100), which carries the evidence.

**Pico USB-Blaster needs a level shifter** — 74LVC8T245 (or 2x 74LVC2T45)
with `VCCB` from JTAG header pin 4. No firmware change can fix it: the clone
drives a fixed 3.3 V into a 2.5 V bank and reads 2.5 V against an RP2040
V_IH of ~2.15 V. Unblocks having both Altera boards connected at once. The
pull-up fix and `jtag_pintest.c` are **uncommitted** in `~/workspaces/pico-usb-blaster`.

<a id="item-10"></a>

### Item 10 — ~~pico-usb-blaster protocol bug — Quartus handshake does not work~~ — FIXED

> **Closed 2026-08-31.** It does work: `598c1a0` records a full
> `jop_sdram.sof` configuring the EP4CGX150 in 43 s through this cable, with
> Quartus reporting "Configuration succeeded". The remaining problem is
> reliability, not protocol — see [item 100](#item-100).

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

**[Full journal →](status/item-31.md)** — 121 lines of investigation detail.

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

**[Full journal →](status/item-30.md)** — 190 lines of investigation detail.

<a id="item-45"></a>

### Item 45 — ONE unidentified register is read before it is written; the other ~401 look benign

**RESCOPED 2026-08-19 after the sweep. The "~405 registers" framing is not what
the evidence supports, and two of my own diagnoses of it were wrong.**

Across five seeds x 471 tests with X-state randomised, the register class
produced **zero** failures. The one reproducible symptom is
`BytecodeFetchStage: JumpTable integration` reading `entries[0xEC]` — an
undefined bytecode — instead of NOP's `entries[0x00]`.

**[Full journal →](status/item-45.md)** — 146 lines of investigation detail.

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

**[Full journal →](status/item-48.md)** — 141 lines of investigation detail.

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

**[Full journal →](status/item-28.md)** — 207 lines of investigation detail.

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

**[Full journal →](status/item-50.md)** — 391 lines of investigation detail.

<a id="item-51"></a>

### Item 51 — ~~The method cache is capped at 2 KB~~ — FIXED. Default is now 8 KB/64 blocks: **+35 % Kfl, +27.7 % UdpIp**, validated on FOUR BOARDS

**Why this matters.** Item 50 measured where stall time goes on real memory:
bytecode fill is **62.8 % of Kfl's stall and 52.9 % of UdpIp's**, and stall is
~52 % of all cycles. So the method cache owns roughly **a third of every cycle
Kfl executes** (0.522 x 0.628 = 32.8 %; UdpIp 27.5 %; Lift 0.9 %). It is the
largest single line item in the whole profile, and item 50 also showed that a
32 KB L2 in front of DRAM takes only 3 % off it — the fix has to be in the
method cache itself, not behind it.

**[Full journal →](status/item-51.md)** — 398 lines of investigation detail.

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

**[Full journal →](status/item-53.md)** — 147 lines of investigation detail.


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

**[Full journal →](status/item-56.md)** — 106 lines of investigation detail.


<a id="item-57"></a>

### Item 57 — ~~The XDC/QSF generators exist and NOTHING USES THEM~~ — DONE

> **Closed 2026-08-31.** Every board build now reads generated constraints. The
> Wukong and i5 Makefiles invoke `XdcGeneratorMain` / `LpfGeneratorMain`, the
> EP4CGX150 takes a generated `pins.tcl`, `quartus.mk` generates the `.sdc` and
> the project Tcl, and the Wukong's SMP SDR flow — the last one reading a
> tracked file — was converted the same day.
>
> The tracked `.xdc`/`.qsf` files that remain are no longer INPUTS. They are the
> oracles `ConstraintDriftTest` checks the generators against, and deleting them
> as "unused" would remove the only thing that would notice the generator
> drifting.

**[Full journal →](status/item-57.md)** — 124 lines of investigation detail.


<a id="item-58"></a>

### Item 58 — `source` inside an XDC is silently ignored by Vivado — four shared constraint files have never been applied

> **Partially closed 2026-08-31.** The SDR SDRAM IOB packing named below is now
> applied: the Wukong's SMP SDR flow was reading a tracked `wukong_jop_sdram.xdc`
> that lacked `set_property IOB TRUE` on `sdram_DQ`/`sdram_DQM`, while the
> generated file carries both. Pointing that flow at the generated constraints
> applied them for the first time. The Ethernet GMII half of this item is
> untouched.

**[Full journal →](status/item-58.md)** — 122 lines of investigation detail.


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


<a id="item-60"></a>

### Item 60 — ~~Everything generated should live under `build/<config>/`~~ — DONE

> **Closed 2026-08-31.** `fpga/` went from 1,775 MB to 7 MB; no board directory
> is a build directory any more, and `4471933` records the last of it. See also
> [item 94](#item-94), which states the same completion and disagreed with this
> heading for six days.

**Raised 2026-08-23, in progress.** The goal the user set: *nothing generated or
built ends up anywhere other than under that build directory*, one directory per
build CONFIGURATION (preset plus arguments — not `entityName`, which collapses
core counts and overrides together). The layout itself is data
(`jop.generate.BuildLayout`), so it can be changed later without another sweep.

**[Full journal →](status/item-60.md)** — 241 lines of investigation detail.


<a id="item-61"></a>

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

<a id="item-62"></a>

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


<a id="item-63"></a>

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


<a id="item-64"></a>

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


<a id="item-65"></a>

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


<a id="item-66"></a>

### Item 66 — the EP4CGX150's Ethernet/VGA/SD was lost in a migration, not removed

**Found 2026-08-25** while deciding whether to convert the flow.

`jop_dbfpga.qsf` assigns **95 pins**, including Ethernet (`e_mdc`, `e_mdio`,
`e_rxd`, `e_txd`, `e_gtxc`, …), VGA (`vga_r/g/b`, `vga_hs`, `vga_vs`) and SD
(`sd_clk`, `sd_cmd`, `sd_dat_*`, `sd_cd`).

**[Full journal →](status/item-66.md)** — 116 lines of investigation detail.


<a id="item-67"></a>

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


<a id="item-68"></a>

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


<a id="item-69"></a>

### Item 69 — ~~`"*" -> "hw"` forces hardware for a bytecode that has none~~ — FIXED 2026-08-31, and the premise was wrong

> **The heading states the cause backwards, and so did README.** Nothing forces
> `frem` anywhere: it was absent from `BytecodeConfig.all` entirely, and
> `resolve` only iterates that list, so a wildcard could never reach it.
>
> What actually happened: `frem` is implemented in Java
> (`JVM.f_frem` -> `SoftFloat32.float_rem`), and that library is compiled in
> only when `Const.SUPPORT_FLOAT` is set — which `ConstGenerator` derives from
> "does any REGISTERED float bytecode still resolve to Java". Give the FCU all
> the registered ones and the flag goes false, the library is dropped, and
> `f_frem` falls through to `JVMHelp.noim()`. Proven end to end: generating
> `Const.java` for `wukongFull` gave `SUPPORT_FLOAT = false` before the fix and
> `true` after.
>
> Fixed by registering `frem` (and `drem`) under a new `ImpConstraint.JavaOnly`,
> which a wildcard and a group key may not retarget while an explicit
> `frem: hw` is refused by name. Merged with [item 74](#item-74), which is the
> same defect filed twice.

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
reachability and cost ten JVM tests.

> **The remedy proposed here would not have worked**, and the diagnosis above it
> was wrong. A `require` reading "`*=hw` names `frem`" could never fire: `frem`
> was not in `BytecodeConfig.all`, so the wildcard never named it. What the
> wildcard actually did was drive `SUPPORT_FLOAT` false and drop the software
> float library. Fixed 2026-08-31 by registering `frem`/`drem` as
> `ImpConstraint.JavaOnly`, which a wildcard may not retarget and an explicit
> `frem: hw` is refused by name.


<a id="item-70"></a>

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


<a id="item-71"></a>

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


<a id="item-72"></a>

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

<a id="item-73"></a>

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


<a id="item-74"></a>

### Item 74 — ~~item 69 is wider than `"*" -> "hw"`~~ — MERGED into [item 69](#item-69), FIXED 2026-08-31

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

<a id="item-75"></a>

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


<a id="item-76"></a>

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


<a id="item-77"></a>

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


<a id="item-78"></a>

### Item 78 — the A-E115FB DDR2 project generates, and what that took

`ae115fbDdr2` builds from a fully generated Quartus project (2026-08-26).
Assignment-identical to the hand-written `jop_ddr2.qsf`: **506 = 506, zero
differences** either way. Timing **MET on every corner** -- +1.050 ns (Slow
100C), +1.305 (Slow -40C), +1.397 (Fast -40C) -- against the +0.584 ns recorded
for the hand-built August bitstream. 28,614 LE. It programs.

**HARDWARE-VERIFIED**: `DoAll` **66/66, three runs of three**, from the generated
project. (Written first as "not verified, console not patched through"; the
console was connected and the runs were done.)

Both CH340s are attached now and separate correctly by `iProduct` -- `ae115fb`
resolves to `USB2.0-Serial`, `wukong` to `USB Serial` -- which is the only thing
that distinguishes them, since neither carries a serial number.

**Quartus 18.1 runs the generated Tcl unmodified** -- 0 errors, 0 warnings. That
was the main risk (Cyclone IV DDR2 ALTMEMPHY is unsupported past 18.1) and it is
cleared.

Five things were wrong, and only the first was expected:

**1. The DDR2 pins are SOURCED, not transcribed.** `ddr2_pins.qsf` holds ~380
instance assignments of six kinds, and only two of them -- location and
`IO_STANDARD` -- are pin facts. `MEM_INTERFACE_DELAY_CHAIN_CONFIG`,
`OUTPUT_ENABLE_GROUP`, `CKN_CK_PAIR`, `PAD_TO_CORE_DELAY` and
`CURRENT_STRENGTH_NEW` are ALTMEMPHY and SODIMM properties emitted by the vendor
tool. Transcribing them into Scala would make a second copy of a vendor artifact
free to drift from its reference. New `Board.constraintFiles` has the generated
Tcl `source` the file instead -- one source of truth, which is the point.

**2. The board had no clock and no reset.** `PinResolver` finds the clock via a
`CLOCK_*` device and the reset via a `SWITCH`, and `Board.AE115FB` declared
neither -- so the generated project assigned neither and left the 50 MHz input
for the fitter to place wherever it liked. Exactly the floating-SW1 defect of
item 57. Added `CLOCK_50MHz` -> `PIN_AB11` and `SWITCH` reset -> `PIN_N21`.

**3. `PIN_` prefixes are inconsistent in board data**, and a bare name is not an
error: `set_location_assignment A5` names a different KIND of location, so the
pin silently goes unassigned. Altera boards are written both ways because the
Xilinx and Lattice entries in the same table legitimately use bare names.
Normalised in `QsfGenerator`, the only generator that emits `.qsf`, so the data
cannot get it wrong.

**4. The DRAM tops have a reset PORT even though they have no reset BUTTON.**
`resetInput` returned None for DDR2/DDR3 on the grounds that wiring a button into
a per-controller reset is real RTL work -- true, and beside the point:
`ddr2Ctl.io.global_reset_n` is driven from `ClockDomain.current.readResetWire`,
which SpinalHDL materialises as a top-level `reset` input. The port exists
regardless, and omitting it left a REQUIRED INPUT unassigned.

**5. nCEO, and the cost of fixing it in the wrong place.** The A-E115FB puts
`mem_addr[10]` on nCEO, which the configuration-pin releases do not cover, so the
fit failed with `Can't place multiple pins assigned to pin location Pin_K22`.
Adding `CYCLONEII_RESERVE_NCEO_AFTER_CONFIGURATION` for every Cyclone board --
consistent with the block it sits in -- is **not free**: the EP4CGX150, which
does not need it, grew 27 LEs and lost 0.084 ns (11,112 / +0.626 became 11,139 /
+0.542). It is gated on **board AND family** now: the board says whether it needs
it, the family says whether the assignment exists at all. Neither alone is
enough, since emitting an assignment a family does not have makes Quartus REFUSE
the project, as `ACTIVE_SERIAL_CLOCK` already does on Cyclone V. EP4CGX150
re-verified back at 11,112 / +0.626.

<a id="item-78b"></a>

### Item 78b — a vendor DRAM PHY owns its own clock constraints

> **Rediscovered the hard way 2026-08-31.** This item had no anchor and nothing
> linked to it, so writing `GeneratedConstraintsTest` I asserted that every
> board emits a `create_clock`, watched the A-E115FB fail, and briefly took it
> for a serious defect in a board validated on hardware the day before. The fact
> below is what settles it, and the STA report confirms it independently:
> "Unconstrained Clocks: 0", worst-case setup slack +1.050 ns.
>
> An item that cannot be linked to is an item that gets rediscovered.

The generated `.sdc` created the board clock and called `derive_pll_clocks`,
which is right for every other Altera board and wrong here. ALTMEMPHY ships
`ddr2_64bit_phy_ddr_timing.sdc` (pulled in by its `.qip`) and already does both.
Doing it again re-derived the PHY's internal clocks under our constraint instead
of its own, and the board went from the **+0.584 ns** its hand-written project
achieved to **-4.807 ns, TNS -14,397** -- identical RTL, identical pins.

`TimingConstraints` now emits, for Altera + DDR2, only what the PHY does not own:
the four JOP-side I/O false paths. That reproduces the hand-written
`jop_ddr2.sdc` exactly, whose header said so in one line all along.

**Adding a constraint is not conservative.** The instinct that a generated `.sdc`
should say MORE than a hand-written one had it backwards; here the extra two
lines cost 5.4 ns.

### Gotcha — `hw_verify.py` took 2m09s on a DDR2 timing report

The DDR2 `.sta.rpt` is **297,552 lines** of very wide tables and the corner-header
regex backtracked across all of them -- reading as a hang, on a script whose
whole purpose is to fail fast. A substring test before the regex: **0.081s**,
same answer.


<a id="item-79"></a>

### Item 79 — the A-E115FB Makefiles: one converted, one retired

**`fpga/a-e115fb-ddr2`** is on `quartus.mk` (2026-08-26), with
`QUARTUS_DIR ?= /opt/altera/18.1/quartus` -- the include already took it with
`?=`, so pinning the version cost one line. Cold-rebuilt through the new file:
fit identical apart from the timestamp, **DoAll 66/66 three runs of three**.

Quartus **25.1 Lite still supports Cyclone IV E**, so only the DDR2 path needs
18.1, not the device. Worth knowing before pinning anything else to 18.1.

The DDR2 **exerciser** stays in-tree: it is a standalone
`Ddr2ExerciserTopVerilog` design rather than a preset, so converting it means
giving it a `BoardDesign` as the SD and SDRAM exercisers have. Left alone
because the JOP path it existed to unblock now works end to end.

**`fpga/a-e115fb-bram` is RETIRED.** It contained a Makefile and nothing else --
no `.qsf`, no `.qpf`, so `quartus_map jop_bram` had nothing to compile -- and its
`generate` target ran:

```
sbt "runMain jop.system.JopTopVerilog ep4cgx150Bram"
```

**another board's preset**, a Cyclone IV GX config aimed at a Cyclone IV E
project. Same shape as item 66, where the dbfpga flow ran `ep4cgx150Serial` into
a 95-pin project. No `ae115fbBram` preset has ever existed.

Two docs referenced it and both were stale: `stage1-card-table-design.md` still
said "no DDR2 build yet", and `pico-dirtyjtag-setup.md` used it as the
`program-djtag` example for a board that programs over the shared Terasic
blaster, its own Pico being blocked on level shifters. Both corrected.

### Gotcha — the stale baud note that nearly caused a regression

`ae115fbDdr2` declares 2 Mbaud at 75 MHz, and both a memory note and a comment
in the Makefile said that is impossible: 75/(2 x 5) = 7.5, so the divider
rounds to 8 and the rate is 7 % off. **Superseded.** `jop.io.UartBaudTick` was
made FRACTIONAL on 2026-08-18 -- *because of this board* -- and accumulates a
fractional phase instead of an integer divider, reaching 2 Mbaud within
0.0006 %. The preset's own comment says so; the Makefile comment next to it did
not, and even flagged itself "Unverified".

Reading the note instead of the code would have "fixed" a working configuration
down to 1 Mbaud. DoAll 66/66 at 2 Mbaud settles it.


<a id="item-80"></a>

### Item 80 — ~~`PerfCounterVerifySim` fails, and has been failing~~ — FIXED 2026-08-30

`sbt test` on 2026-08-26: **651 succeeded, 1 failed**.

```
[Warning] UNASSIGNED REGISTER (toplevel/jopSystem/pipeline/cu/icu/resultReg : UInt[64 bits])
          with init value, please apply the allowUnsetRegToAvoidLatch tag if that's fine
    at jop.core.IntegerComputeUnit.<init>(IntegerComputeUnit.scala:50)
```

The ICU's 64-bit `resultReg` has no driver, and elaboration refuses.

**PRE-EXISTING, established by bisect rather than assumed.** It fails identically
at `c022296`, before any of this session's changes: same register, same message,
same ~48 s. The failing harness builds `JopCoreConfig` directly with default
bytecodes, so it never sees the `ep4cgx150HwMath` change that made `imul`
hardware -- but "it can't be mine" is a hypothesis, and checking it cost one
worktree.

**The first bisect attempt was worthless and looked conclusive.** The test
"failed at base" in 37 ms -- because `java/apps/JbeBench/JbeBench.jop` is a
gitignored artifact that does not exist in a fresh worktree, so it failed on a
missing file rather than on the defect. A pass/fail comparison across trees is
meaningless until both trees fail the same WAY. Copying the fixture in gave the
real answer. This is the gitignored-artifact CI trap wearing a different hat.

**Not diagnosed.** Whether `resultReg` lost its driver or never had one under
this particular `JopCoreConfig` (no `bytecodes` map, so ICU ops take their
defaults) is open.

<a id="item-81"></a>

### Item 81 — Build port, phase 0: closing the conversions I left half-open

The five-step conversion loop ends with **delete the hand-written file**. On two
boards I ran steps 1-3 and 5, proved the generated project equivalent, and then
did not run step 4 -- leaving a hand-written `.qsf` sitting beside a generated
one, which is exactly the "which did the build read?" ambiguity this workstream
exists to remove.

**Ten tracked files deleted**, all with generated equivalents and no Makefile or
Tcl referencing them:

| board | files |
|---|---|
| `a-e115fb-ddr2` | `jop_ddr2.{qsf,sdc,qpf}`, `jop_ddr2_smp.{qsf,qpf}` |
| `cyc5000-sdram` | `jop_cyc5000.{qsf,sdc,qpf}`, `jop_smp_cyc5000.{qsf,qpf}` |

Both boards' `smp` targets already re-enter `quartus.mk` with the right `REV`,
so the SMP project regenerates from `setup_proj.tcl`. `ddr2_pins.qsf` stays --
it is `Board.constraintFiles` now -- as do the exerciser projects.

**Three leftovers from my own conversions, found while checking the above.**
The CYC5000 conversion was sloppier than the EP4CGX150 one:

- **Four variables nothing read.** `BAUD_RATE = 2000000` was the worst: it
  *looked* like the authority on the wire rate while `console.mk` was deriving
  `BAUD` from the build's own summary. Editing it would have changed nothing --
  the item 70 hazard, re-created by the very commit that removed it elsewhere.
  `SERIAL_PORT` and `JOP_FILE` were subtler: set with `?=` *before* the include,
  so they SHADOWED `console.mk` rather than being dead. `JOP_FILE` pointed at
  the in-tree `java/apps/Smallest/HelloWorld.jop`, not the build tree's copy --
  a live bug. Deleting all four let `console.mk` do its job; `console-info` now
  reports the same port and the same 2000000, read from the bitstream.
- **A second `.cdf` generator rule**, byte-identical to the first, whose
  prerequisite `$(SOF_SMP_FILE)` was **never defined anywhere**. The whole
  `.cdf` class is gone now, as on the EP4CGX150: `quartus_pgm` takes an explicit
  operation, so the `.sof` goes to it directly.
- **A header advertising six targets that did not exist** -- `all`, `run`,
  `full`, `full-smp`, `build-smp`, `generate-smp`. `all`, `full` and `run` were
  dropped by the conversion itself and nothing noticed. Restored; the rest
  corrected to the real names. 146 -> 106 lines, every target dry-run.

**The `build_exerciser` macro is gone.** The EP4CGX150 commit that deleted nine
`.cdf` generators added, in the same file, a `define build_exerciser` carrying
its own copy of `quartus_sh` plus the four `quartus_*` commands -- a SIXTH copy
of the flow, in the file whose header records removing the other five. What
genuinely varies between an exerciser and a JOP preset is only which generator
main runs and whether that main also wrote the project, so `quartus.mk` grew
exactly two knobs:

```make
GEN_MAIN          ?= jop.system.JopTopVerilog
GEN_ARGS          ?= $(CFG) buildtree
GEN_MAKES_PROJECT ?= no
```

An exerciser sets all three and re-enters the same rules. The `cfgName` and
`revision` inside each `*Build` main are exactly the `CFG` and `REV` passed, so
the two halves cannot drift. Both branches dry-run verified: the exercisers emit
the identical command sequence the macro did, and programming now goes through
`program-sof` -- resolving the cable by serial -- instead of a hand-copied
`quartus_pgm` line.

**Net: 1,509 lines deleted, 122 added.** All five `quartus.mk` boards still
dry-run clean, and a config with no build directory still fires the full
eight-step chain.

**Also fixed:** `implementation-notes.md` claimed `make full-smp` and named the
two deleted `.qsf` files, and said the PLL frequency is configurable in
`dram_pll.vhd` -- superseded by `DramPllGen`. A comment explaining why a value
can be ignored outlives its reason.

<a id="item-82"></a>

### Item 82 — Flash boot was removed as collateral (OPEN)

> **The heading used to end "and the docs still say it works".** That half is
> fixed: `README.md`, `docs/boards/flash-boot.md` and
> `docs/boards/flash-boot-artix7.md` all carry a REGRESSED warning. The
> capability is still gone, which is what keeps this open.

Found during the phase 1 board audit, from a question that looked like
bookkeeping: the Alchitry Au keeps its UART flash programmer but the flash-boot
JOP top it programs lost its main in commit `7258661`. Pulling on that gives a
much larger answer.

**Both boards booted autonomously from SPI flash, and both were fully verified.**
`docs/boards/flash-boot-artix7.md` records every milestone as Done, ending with
"Autonomous boot after power-cycle"; `docs/boards/flash-boot.md` says the
EP4CGX150 version "boots autonomously ... with no JTAG connection needed" and
calls the Artix-7 one "also fully working".

**Commit `7258661` (2026-03-13), "Remove IoConfig and legacy tops
JopSdramTop/JopDdr3Top", deleted both flash entry points.** `JopSdramTop.scala`
(928 lines) and `JopDdr3Top.scala` (925 lines) carried `JopCfgFlashTopVerilog`
and `JopDdr3FlashTopVerilog` respectively. The commit message says it moved the
Makefiles "to use JopTopVerilog preset names" -- and it did, for every SERIAL
flow. It provided no flash equivalent, and **no `JopConfig` preset anywhere sets
`bootMode = BootMode.Flash`.**

**Everything around it survived, which is why nothing noticed:**

| piece | state |
|---|---|
| `BootMode.Flash`, `JumpTableInitData.flash`, `MicrocodePaths` | live, keyed on the mode |
| `asm/Makefile` `flash-altera` / `flash-alchitry` | live -- `flash-altera` is in `all` |
| `build/microcode/flash/` | populated right now |
| `FlashProgrammerTop`, `FlashProgrammerDdr3Top` | live, unaffected |
| flash XDC/QSF, Vivado Tcl, `make_flash_image.py`, `flash_program.py` | all present |
| the two generator mains | **deleted** |
| a preset selecting flash boot | **never existed** |

So the tree still builds flash microcode on every `asm` run, for a boot mode no
configuration can select, and has done for five and a half months.

**This is the "validated records decay silently" pattern, one level worse.**
There the record went stale while the code still worked; here the CODE PATH was
removed and the record kept asserting success. Both share the root cause: no CI
job builds an FPGA, so nothing re-derives these claims. The Makefile targets
surviving their implementation is what made it invisible -- `make generate-flash`
failed with an sbt "class not found", which reads like a local environment
problem rather than a deleted feature.

**The fix is a preset, not a Makefile.** An `auFlash` and an `ep4cgx150Flash`
with `bootMode = BootMode.Flash` restore both boards through the normal
`JopTopVerilog <preset>` path, and the flash-boot flow becomes ordinary
config-driven work rather than two bespoke tops. That is the right shape and it
is exactly what the deleted commit was trying to achieve -- it simply dropped
this mode on the way.

**Done meanwhile:** `make generate-flash` on the Alchitry now fails with an
explanation instead of a class-not-found, and both flash-boot docs carry a
REGRESSED banner so the next reader is not misled the way this audit nearly was.

**Not started:** the presets themselves. Neither board has been re-verified.

<a id="item-83"></a>

### Item 83 — Build port, phase 1: auditing the flows before deduplicating them

Three questions per flow: does its generator main exist, does its Tcl exist, is
there a hardware record. Asked before any conversion work, because ~150 of the
EP4CGX150's 701 lines turned out to be **dead rather than duplicated** -- and
deduplicating dead code is worse than leaving it, since the result looks
maintained.

| board | outcome |
|---|---|
| Alchitry Au | 4 flows retired, 252 -> 151 lines, 25 -> 11 Tcl. Flash boot is item 82 |
| MAX1000 | `program` and `download` REPAIRED -- `download` called a nonexistent class in a nonexistent jar |
| DB_FPGA V5 | **live wrong-board hazard fixed** (below) |
| Wukong | 3 orphaned `bench_*.tcl`; kept, see below |
| `ep4cgx150-sdram-test` | foldable onto `quartus.mk` now that `GEN_MAIN` exists (phase 2) |
| `alchitry-au-ddr3-test` | still on the legacy in-tree path |
| Colorlight i5 | closed -- no shared include warranted for a single ECP5 |

**The DB_FPGA V5 could program the wrong board, today.** Its `BUSDEV ?=` was
empty, with a comment instructing the reader to pass the probe selection by
hand. Two dirtyJtag probes are attached to this host right now -- the Wukong's
Pico 2 W at `001:014` and this board's RP2040 at `001:039` -- and a bare
`-c dirtyJtag` takes the lower one. So `make ddr3-program` here programmed the
**Wukong** unless someone remembered to type the workaround, and three further
program targets (`uart-echo`, `loopback`, `txgen`) omitted `BUSDEV` entirely.
Now resolved by serial like every other flow; all four verified selecting
`1:39`. *A hazard that is only avoided when someone remembers to type the
workaround is not avoided* -- the comment sat there describing the danger
accurately while doing nothing about it.

**The MAX1000's `download` was doubly dead:**

```
java -cp java/tools/dist/jop-tools.jar com.jopdesign.tools.SerialDownload
```

`java/tools/dist` holds `jopa.jar`, `jopizer.jar` and `jopsim.jar`; there is no
`jop-tools.jar`, and no `SerialDownload` anywhere in `java/tools/src`. The
project's downloader has been `fpga/scripts/download.py` for a long time.
`program` used a bare `-c USB-Blaster` -- the same wrong-board hazard.

**These were REPAIRED, not retired, and the first attempt got that wrong.** I
read "no hardware" in the triage table as "no such board" and rewrote the file
as a fit check only. The boards exist -- they were the project's ORIGINAL target
and remain one; they are simply not at this site. "Not attached to this host" and
"cannot be tested, ever" are different facts, and a triage table recording the
first does not license acting on the second. Both targets are now implemented
correctly (`download.py`, probe by serial with a fallback) and marked
UNVERIFIED, which is what they actually are. The fit-check value stands
alongside: 8k LEs against the EP4CGX150's 149k catches an area regression early.

**A false positive worth recording.** The DB_FPGA's `DDR3_BITSTREAM` names
`JopDdr3Top.bit`, and `JopDdr3Top.scala` was one of the two files deleted by
`7258661` -- it reads exactly like the stale paths found elsewhere. It is
correct: `JopConfig.entityName` *derives* `JopDdr3Top` for that preset, because
the board sets neither `entityTag` nor `entitySuffix`. **A name surviving its
file is not evidence of a dead path when the name is computed.** Checking cost
one `sed`; assuming would have "fixed" a working flow.

**Kept deliberately: the Wukong's `bench_cu.tcl` and `synth_only.tcl`.** No
Makefile references either, so both matched the orphan pattern -- but they are
cited by `docs/analysis/compute-unit-timing-benchmark.md` and
`docs/analysis/wukong-utilization-sweep.md` as the reproduction recipe for
published tables. **A script referenced only by an analysis document is not
orphaned; it is the evidence.** `bench_icu`, `bench_dcu_only` and
`bench_fcu_dcu` are subset variants of the four-unit run and are cited by
nothing -- flagged, not deleted, pending a decision.

**Also noted:** `docs/boards/flash-boot-artix7.md`,
`docs/architecture/sd-card-boot-loader.md` and
`docs/architecture/serial-remote-debug.md` have CRLF line endings. Editing them
through Python's text mode silently rewrites every line -- an 11-line addition
came out as a 1,121-line diff before it was caught and redone at byte level.

<a id="item-84"></a>

### Item 84 — No MAX1000 configuration is known to fit (OPEN)

The Arrow MAX1000 (10M08 + SDRAM) was this project's original target and is
wanted again: a **1- or 2-core configuration that fits**. Nothing currently
establishes that one does.

`max1000Sdram` exists -- single-core, 80 MHz, `SystemAssembly.max1000` -- and
`fpga/max1000/` carries a hand-written `jop_max1000.qsf`, `.sdc` and PLL. There
is **no recorded fit result** for it, and the board has not been connected to
this host, so the flow has never been run end to end here.

The part is the constraint: a 10M08 has **8k logic elements** against the
EP4CGX150's 149k, so this is roughly 5 % of the area the validated
configurations assume. Two things already measured say where the pressure will
be. The method cache costs **850-869 LUTs per core** (item 53), and it is
FRAGMENTATION rather than capacity that matters -- block count beats block size
(2 KB -> 4 KB/32 blocks gave +34.4 % Kfl). And a 32 KB L2 in front of DRAM buys
only 3-5 %, so it is the first thing to drop. `ep4cgx150McFallback` shows the
compute units can go entirely (`bytecodes = "mc"`) with DoAll still passing,
just slower -- on a part this size that trade is likely mandatory rather than
optional.

Two cores on 8k LEs is the ambitious end; one core is the thing to establish
first, since a single-core fit result is also the number that tells us whether
two is arithmetically possible at all.

**Next action:** run `make -C fpga/max1000 all` for the fit figure. It needs no
hardware -- that is exactly what this flow is good for while the board is
elsewhere -- and it converts an open question into a number.

<a id="item-85"></a>

### Item 85 — Build port, phase 2: the exerciser folds in, and the baud it never reported

`fpga/qmtech-ep4cgx150-sdram-test` now includes `quartus.mk`. It was already
GENERATING everything (item 78: Verilog, PLL, SDC and the Quartus project all
come from `SdramExerciserDesign`), but it still carried its own copy of the four
`quartus_*` commands, its own `quartus_pgm` line with a hand-resolved blaster,
its own `BAUD_RATE = 1000000`, and a monitor pointing at another board's
`monitor.py`. What let it fold in was `GEN_MAIN` / `GEN_MAKES_PROJECT` from
phase 0 -- an exerciser is a standalone `BoardDesign`, not a preset, and those
two knobs are the whole difference. The emitted command sequence is identical.

**The conversion introduced a regression, and catching it produced the better
fix.** `console.mk` derives `BAUD` from the build's own `*.summary.txt` rather
than a Makefile constant (item 70). The exercisers **never emitted a summary**,
so `BAUD` came out EMPTY and `make monitor` would have run with no rate at all
-- strictly worse than the constant it replaced. The rate itself was a literal
`1000000 Hz` buried in the UART setup inside `SdramExerciserTop`.

Fixed at the source rather than by reinstating the constant. The baud is now
DECLARED on the device, where the JOP path already keeps it:

```scala
val uartBaud = 1000000
val devices  = Map("uart" -> DeviceInstance(DeviceType.Uart, ...,
                     params = Map("txOnly" -> true, "baudRate" -> uartBaud)))
```

The RTL divider reads it, `SdramExerciserBuild` writes it into a summary, and
`console.mk` finds it. Verified end to end: `BAUD` resolves to 1000000, and the
generated divider is unchanged at `20'h00013` -- 100 MHz / (1 Mbaud x 5) - 1.
**The summary and the divider can no longer disagree**, which is the property
that mattered; a constant that happens to be right is not the same as one that
cannot be wrong.

**`CONSOLE_TXONLY`.** This design's UART reports results and listens to nothing,
so `download`, `redownload` and `reset` -- which `console.mk` offers every board
-- are meaningless here. Overriding the three recipes in the board Makefile
works but draws `overriding recipe for target` warnings, and **warnings that are
normal are warnings nobody reads**; the flag is the third of the same shape as
`GEN_MAKES_PROJECT`. They now fail with the reason instead of a serial timeout.

**Still open in phase 2:** the MAX1000 stays in-tree -- its `jop_max1000.qsf`
reads `spinalhdl/generated/`, so folding it in needs a MAX1000 `BoardDesign`
first, not just an include.

All six `quartus.mk` boards dry-run clean; 63/63 config and generator tests pass,
`ConstraintDriftTest` included.

<a id="item-86"></a>

### Item 86 — Build port, phase 3a: the Vivado flow, once

The Vivado side never had a shared flow. `vivado.mk` existed but was included by
NO board, so the Make layer was untested -- and the real duplication was never
in Make anyway: it was **56 Tcl scripts, 2,495 lines**, four boards each
carrying its own copy of the same five families.

Three shared scripts now under `fpga/scripts/`, all environment-driven the way
`JOP_CFG_DIR` already reached the non-project builds:

**[Full journal →](status/item-86.md)** — 113 lines of investigation detail.

<a id="item-87"></a>

### Item 87 — Build port, phase 3b: `vivado.mk` finally has a user

`vivado.mk` was written on 2026-08-24 for the two Xilinx boards and then
included by **none of them**. A shared include nobody includes is
indistinguishable from a broken one, and this one was in fact the wrong shape:
it wrapped a per-board `BUILD_TCL` and `BITSTREAM`, which stopped making sense
once phase 3a moved the build itself into `fpga/scripts/`.

Rewritten around what is actually shared -- resolving the config directory,
generating the RTL once per change, programming by serial, and the console --
and the Wukong now includes it.

**It found a live bug.** The Wukong carried four baud constants. `UART_BAUD :=
1000000` was used by `sdram-monitor` and `jop-sdram-monitor`, and
`build/wukongSdram`'s own summary says the bitstream runs at **2000000**. So
monitoring the SDR JOP build listened at half the wire rate and produced
garbage -- which reads as a dead board, not as a wrong constant. Exactly the
item 70 failure mode, still present on this board, found by deleting the
constants and asking the build instead. A fifth constant, `DUAL_UART_BAUD`, had
already been corrected from 115200 by hand at some earlier point; the others had
not been.

**And a latent one in `console.mk` itself.** The baud was extracted with
`awk '{print $3}'`, which is positional:

```
  UART baud:   2000000            <- single system, rate is field 3
  [ddr3] UART baud:   2000000     <- MULTI-system, field 3 is "baud:"
```

The dual-cluster flow therefore resolved `BAUD` to the literal string `baud:`.
Nothing had caught it because **no multi-system board had ever used
`console.mk`** -- the Wukong was the first. Now `$NF`, which is right for both.
`head -1` still takes the first system's rate, correct while both halves run at
2 Mbaud as `wukongDualIndependent` sets them, and documented as the thing to
revisit if a future dual config differs.

**The other duplication removed** was a second copy of `BuildLayout`'s naming
rules living in Make:

```make
DDR3_SMP_DIR = $(REPO_ROOT)/build/wukongSmp-$(DDR3_SMP_CORES)
```

Two copies of a naming rule agree until the day they do not, and the failure is
a path silently pointing at a stale directory or none at all -- which is the
defect `BuildLayoutMain` exists to prevent, and `quartus.mk` already says so in
its header. The SMP flows re-enter with their `CFG` so the name is resolved by
Scala; only the flow actually invoked pays for the sbt round trip.

Verified: all twelve board Makefiles parse, every Wukong target dry-runs, and
the baud now resolves correctly for four Wukong flows and four other boards
(including the exerciser's 1 Mbaud, which differs from every other board's).

<a id="item-88"></a>

### Item 88 — The CI formal failure was a build cache, not a proof

The 2026-08-26 push failed `formal-verification` with **"The job has exceeded
the maximum execution time of 45m0s"**. Every other job passed. It reads as a
formal regression and was reported as one.

**It was neither the push nor the proofs.**

The push touched 41 files -- docs, FPGA Makefiles and `.qsf`s, four
config/generator Scala files, `JopTopVerilog`, one config test. **No RTL, no
formal test, no microcode, no CI config.** The formal suites instantiate
`jop.core.*` / `jop.memory.*` / `jop.io.*` components, none of which changed, so
the job's inputs were byte-identical to the previous green run.

Re-running the same commit reproduced it exactly, which ruled out the first
hypothesis (SMT nondeterminism) -- and the job log then gave it away:

| | green run 03:55 | failing run 14:01 |
|---|---|---|
| first `Run sbt` | 03:55:28 | 14:01:52 |
| next `Run sbt` | 03:56:04 (+36 s) | **14:30:54 (+29 min)** |
| `Building ...` lines | **1** | **307** |
| proof window | ~19 min | ~15 min, then killed |

**The yosys cache HIT -- 290 MB restored -- and `make install` rebuilt yosys
from source anyway**, 27 minutes of it, reaching 88 % before the wall. The
re-run did the same: 318 `Building` lines, 25.5 minutes. The proofs never got
their ~19 minutes and were killed mid-flight with z3 still running.

**Why caching the build tree can never work here.** `make install` re-derives
`kernel/version_*.cc` from the git state, which a restored tree does not
reproduce, so everything downstream is stale on every restore. Fixed by caching
the **installed** tree instead (`make install DESTDIR=...`, restore with
`cp -a`): the install output is a handful of files with no build rules behind
them, so restoring it cannot trigger a rebuild.

**And the budget was always too small for a cold cache.** 25 min build + 19 min
proofs + setup does not fit in 45, and GitHub evicts a cache after 7 days
without a hit -- so any quiet week brings the cold path back, presenting as a
formal timeout rather than as a build cost. Raised to 75.

**What made this expensive to diagnose** is that the job-level wall reports
nothing about what was slow. The project already knew the shape of this: the
comment on `BytecodeFetchStageFormal.withTimeout(900)` says a formal timeout
"should mean 'this property has become intractable', not 'the runner was
busy'". That lesson was applied per-proof and never reconciled at the job level
-- **the per-proof timeouts sum to 400 minutes against a 45-minute budget**, and
twelve of the twenty-three suites set no timeout at all. So the wall always
wins, and always anonymously.

**Still open:** splitting `formal-verification` so a genuine blowup names its
suite. Runtime is dominated by four deep suites (`CacheToMigAdapterFormal` alone
was 540 s, 47 % of the job); the other ~18 finish in about two minutes and would
gate a push far faster.

<a id="item-89"></a>

### Item 89 — The last in-tree board

`qmtech-xc7a100t-dbfpga-v5` was the only board still generating into
`spinalhdl/generated` -- the one remaining place where "everything generated
lives under `build/<config>/`" was not true. It now includes `vivado.mk`, and
its JOP flow writes to `build/xc7a100tDbSerial/`.

**Verified against an exact number, not a plausible one.** The in-tree control
built earlier the same day on identical RTL gave a fit summary to compare
against line for line:

| | control (in-tree) | build tree |
|---|---|---|
| Slice LUTs | 12,872 (20.30 %) | 12,872 (20.30 %) |
| Slice Regs | 11,505 (9.07 %) | 11,505 (9.07 %) |
| Block RAM | 22 (16.30 %) | 22 (16.30 %) |
| Timing | MET, WNS +0.242, WHS +0.052 | MET, WNS +0.242, WHS +0.052 |

Identical excluding the build timestamp. 0 errors, 0 critical warnings.

`UART_BAUD := 2000000` went with it; `console.mk` now derives 2000000 from the
build's own summary, so the constant was right here -- unlike the Wukong's,
which was wrong for two flows (item 87). Being right is not the same as being
checked, and nothing had checked it.

The three bring-up jigs (`uart-echo`, `loopback`, `txgen`) stay in-tree
deliberately: they are not `JopConfig` presets and have no config directory to
belong to -- `UartEchoTop` has its own generator main, and `UartLoopback` and
`UartTxGen` are hand-written Verilog.

**Left in-tree and now stale:** `spinalhdl/generated/JopDdr3Top*`, from the last
in-tree build. Gitignored, so harmless, but the flow no longer writes there.

### Gotcha — two self-inflicted failures that looked like a Makefile bug

The build-tree conversion was reported as a parse-time Makefile error twice
before it was one at all. Neither failure was in the Makefile:

1. A backgrounded `make -C fpga/<board>` inherited a working directory from an
   earlier `cd`, so the relative `-C` resolved inside that same directory.
2. The retry used `until ! pgrep -f 'runMain jop.system.JopTopVerilog'` as a
   waiter. **That literal appears in the waiter's own command line**, so
   `pgrep -f` matched itself and the loop never exited -- the build never ran,
   and the STALE log from attempt 1 was read as its result.

A foreground run had already succeeded in between, so the evidence was
contradictory and the older artefact won anyway. The rule that would have
caught it is already written down: prefer an explicit completion marker in the
log over process-matching, and use absolute paths for anything backgrounded.

<a id="item-90"></a>

### Item 90 — The Alchitry Au, and a warning that was right to leave alone

`alchitry-au` is on `vivado.mk`, generating into `build/auSerial/`. **11 Tcl
scripts -> 7.** The main JOP flow and the flash PROGRAMMER (the live half of
flash boot) now use the shared create/build scripts; `BAUD_RATE = 2000000` is
gone in favour of the rate the build records.

**Two real defects found by converting:**

- `program_bitstream.tcl` hardcoded `vivado/build/jop_ddr3/...`, the
  pre-build-tree path. Once the project moved, `make program` would have loaded
  a **stale bitstream, silently**. It takes the path from the caller now.
- `project-flash` and `bitstream-flash` had no dependency on `generate-flash`,
  so they would have built from whatever was left in `spinalhdl/generated` --
  pre-conversion bytes. Both now depend on it and fail with the item 82
  explanation instead.

**The probe hazard I flagged was not one, and measuring said so.** Vivado
enumerates exactly ONE target here (`xilinx_tcf/Xilinx/000000A`, `xc7a35t_0`):
it claims the Alchitry's FT2232H and ignores the CYC5000's Arrow blaster and
both dirtyJtag Picos. So `[lindex $hw_targets 0]` is correct -- by accident
rather than by selection, so both program scripts now refuse and list the
targets if more than one appears.

**Fit moved the way the DB_FPGA's did:**

| | baseline 2026-08-22 | now |
|---|---|---|
| Slice LUTs | 13,407 (64.46 %) | **12,330 (59.28 %)** |
| Slice Regs | 11,624 (27.94 %) | 11,190 (26.90 %) |
| Timing | MET, WNS +0.477 | MET, WNS +0.369 |

Five points of LUT headroom recovered on the tightest part in the fleet, from
RTL work done for other reasons. Same pessimistic-record effect as item 86.

### Gotcha — the four critical warnings are correct, and both "fixes" are worse

This build emits four `set_property expects at least one object` CRITICAL
WARNINGs. One `.xdc` serves three designs that disagree on UART port names --
the presets emit `ser_rxd`/`ser_txd`, `FlashProgrammerDdr3Top` and
`Ddr3ExerciserTop` still use `usb_rx`/`usb_tx` -- so both pairs are constrained
to P15/P16 and whichever is absent warns.

Both obvious clean-ups are wrong, and **both were attempted on 2026-08-26**:

1. **Delete the unused pair.** It is not unused -- it silently unconstrains the
   UART on the two designs that still use it. Caught before doing it, by
   grepping for the names rather than trusting a comment that called them "old".
2. **Guard with `if {[llength [get_ports -quiet $port]]}`.** This one was
   actually done, and it BROKE THE BUILD: in project mode the file is evaluated
   in a pass where `get_ports` does not resolve, so the guard is false and
   **both** pairs are dropped. `DRC UCIO-1`, two unconstrained ports, no
   bitstream. Reverted; the fit after reverting is identical to before.

Leave it unconditional. Four harmless warnings beat an unconstrained UART, and
the reasoning now lives in the `.xdc` so neither attempt gets repeated.

**The wider point**, twice over today: noise is worth reading, not silencing.
`make`'s "overriding recipe" warnings led to `CONSOLE_TXONLY`, which was a real
improvement. These led to a broken bitstream. The difference is whether the
warning is telling you something -- and the only way to find out is to
understand it before acting, not to make it go away.

<a id="item-91"></a>

### Item 91 — Two Altera boards, two cables, and a check that finally has a reason

The level shifters arrived, so the EP4CGX150 now has its own pico-usb-blaster
(serial `e6616408` -- the Pico then believed to be blocked driving 3.3 V into
the A-E115FB's JTAG; that bank measured **3.25 V** on 2026-08-31, so it was
never an over-voltage problem) and the Terasic (`91d28408`) stays on the
A-E115FB. Both are attached at once and both were proven on hardware today:

**[Full journal →](status/item-91.md)** — 108 lines of investigation detail.

<a id="item-92"></a>

### Item 92 — The tree did not build from a clean clone, and the working tree hid it

Asked whether the port survives `git clean`, the safe form of that test is a
fresh clone -- non-destructive, and it answers the same question. It failed:

```
[error] JumpTable.scala:95:39: not found: value FlashJumpTableData
[error]   def flash: JumpTableInitData = from(FlashJumpTableData)
```

**Cause.** `build.sbt` declares all THREE microcode directories as Scala source
roots (`simulation`, `serial`, `flash`) and `JumpTable.scala` references all
three objects unconditionally. Every `sbt` compile therefore needs all three
generated -- but every board flow ran `make -C asm serial`, which builds only
the first two. The FIRST sbt invocation of a cold build dies.

**Why nobody saw it.** `build/microcode/flash/FlashJumpTableData.scala` survives
from some earlier `make -C asm all` and nothing invalidates it, so a working
tree compiles fine forever. And CI has always run `make -C asm all` -- its own
comment records this exact hazard, "a local `make -C asm all` left the flash
variant 16 days stale while CI was fine". CI had the fix; the board flows were
the half that never got it.

Three shapes of one bug:

| | before | after |
|---|---|---|
| `quartus.mk` (6 boards) | `asm serial` | `asm all` |
| `alchitry-au` | own copy, `asm serial` | deleted, inherits `vivado.mk` |
| `vivado.mk` | **no microcode rule at all** | added |
| `qmtech-xc7a100t-wukong` | `all: build` | `all: microcode build` |

**Verified.** Fresh clone, `make -C fpga/qmtech-ep4cgx150-sdram all`, exit 0
through map/fit/asm/sta, and the fit is identical to the working tree's:
11,112 LE, 5650 registers, 268,416 memory bits, 45 pins.

**The general lesson.** A build tree is a cache of decisions nobody is
re-checking. Generated state that no rule invalidates makes a broken dependency
*look* satisfied for months, and the longer it sits the more confident everyone
becomes. A cold clone is cheap (86 MB here) and is the only thing that reads
the dependency graph honestly. Worth doing after any build-system change, not
just when something looks wrong.

Related: `git clean -xfd` would have been the WRONG tool for this. It removes
`.claude/settings.local.json`, all of `build/`, and 77 MB of generated MIG /
clock-wizard IP that is regenerable only from the tracked `mig.prj` through the
slow per-IP scripts. A clone tests more and destroys nothing.

### Gotcha — a backgrounded `( ... ) &` reports its own exit, not the build's

`( make ... ; echo "EXIT=$?" >> log ) &` returns the exit status of the `echo`,
so the completion notification said "exit code 0" for a build that had failed
with 2, and later said "completed" while four Quartus processes were still
running. The `EXIT=` marker in the log was right both times. Read the marker,
not the notification.

<a id="item-93"></a>

### Item 93 — Every converted board, cold — and six layers of hidden dependency

Item 92 cold-built ONE board per toolchain. Doing all twelve found that the
shared includes were only the first layer. Four workers, one clone each (three
in tmpfs), `git clean -xfd` between runs:

| board | result |
|---|---|
| a-e115fb-ddr2 | PASS 487s |
| qmtech-xc7a100t-dbfpga-v5 | PASS 714s |
| cyc5000-sdram | PASS 253s |
| colorlight-i5 | PASS 144s |
| qmtech-ep4cgx150-bram-serial / -sdram-test | PASS after fix |
| qmtech-ep4cgx150-bram | PASS 477s after fix |
| qmtech-xc7a100t-wukong (ddr3-build) | PASS 698s after fix |
| qmtech-xc7a100t-wukong (all / BRAM) | PASS 430s after conversion |

**Six layers, each hidden by the one above it.** Every fix revealed the next:

1. **`UCODE := $(wildcard ...)`.** A wildcard prerequisite expands to EMPTY when
   the files are absent, so `$(GEN_STAMP): ... $(UCODE)` meant "depend on
   microcode only once microcode already exists". Cold-buildability was an
   accident of whether each board's own `all` listed `microcode`.
2. **The wukong bypasses `vivado.mk`'s `generate`** with four bespoke rules, so
   the shared fix did not reach it.
3. **`JopTopVerilog` read the embedded `.jop` from `java/apps/...`** -- a source
   path, and the last build product read out of the source tree.
4. **Nothing built that `.jop`.**
5. **`wukongBram` had never been converted** -- in-tree RTL *and* in-tree `.jop`.
6. **`make -C java all BUILDTREE=1` never worked cold**: `java/tools` hardcodes
   `TARGET_SRC=../runtime/src/jop`, the legacy home of the generated
   `Const.java`.

**And layer 6 had a second bug inside it.** `GEN_SRC` is written relative to
`java/` while `tools/Makefile` runs in `java/tools/`, so the first fix resolved
one level short. **javac does not warn about a sourcepath entry that does not
exist** -- it fails later with `cannot find symbol`, pointing at the symbol
rather than at the path. The fix looked right and was not; only expanding the
variable proved it.

**The result that matters.** After a full build in a cold clone:

```
untracked/modified in java|spinalhdl|asm : 0
spinalhdl/generated                      : absent
```

### Gotcha — iterate on the cheap prefix, not the whole build

Five rounds of this were run as `make all`, paying a full Quartus or Vivado
build (155-714 s) to re-check a failure that surfaces in the first 30 s. Every
bug in the chain -- microcode, `Const.java`, the embedded `.jop` -- fails during
`generate`. Iterating on `make generate` (51 s and 69 s) and spending the full
`all` only on the final confirmation is ~20x faster for the same information.

Cleaning is NOT the cost, and the cold tree is not negotiable: a fresh clone is
227 ms and `git clean -xfd` is 18 ms. Reusing a dirty tree is what hides these
bugs in the first place.

<a id="item-94"></a>

### Item 94 — Nothing generates into the source tree any more (CLOSED)

The eight generators listed as OPEN under item 93 are converted. Every FPGA
flow in the tree now writes to `build/<config>/`, and the legacy
`*TopVerilog` objects that wrote to `spinalhdl/generated` are deleted rather
than left as a trap — running one by hand would have recreated the directory.

| generator | now |
|---|---|
| `UartEcho`, `Ddr3Exerciser`, `FlashProgrammerDdr3`, `SdramExerciserWukong` | `build/<cfg>/rtl` via `StandaloneBuild` |
| `ConfigFlashExerciser`, `FlashProgrammer`, `UartTest` | generated Quartus project, `.qsf`/`.qpf` deleted |
| `Ddr2Exerciser` | generated Quartus project, `.qsf`/`.qpf`/`.sdc` deleted |
| `JopTopVerilog max1000Sdram` | see item 93's MAX1000 entry |

**Every switch was diffed against the file it replaced before being made**, and
the diffs are what made it safe:

- config-flash exerciser: all 8 pins identical; programmer: all 9. Builds are
  637 LEs / 8 pins and 337 LEs / 9 pins.
- DDR2 exerciser: the 8 board pins identical. The 110 `mem_*` assignments in
  the old `.qsf` were redundant -- `ddr2_pins.qsf`, which the generated project
  `source`s, carries them. 7,620 LEs / 118 pins.

**Three generator gaps this exposed, all of the same shape** -- a generator
encoding one flow's naming as if it were universal:

1. `QuartusProject` emitted `ENABLE_CONFIGURATION_PINS OFF` unconditionally. A
   design driving the EPCS needs it `ON` plus four `RESERVE_*` lines or its
   pins stay reserved and it is wired to nothing. Now keyed off whether the
   DESIGN declares a `cfgflash` device, not off what the board carries.
2. `Board.QmtechEP4CGX150` had **no config-flash device at all**, so a
   generated project would silently have dropped all four EPCS pins.
3. `QsfGenerator.toIoStandards` looked up I/O standards by port name, and
   boards spell the reset `"reset"`. The DDR2 exerciser calls it `rst_n`, got
   no standard, and inherited the global 3.3-V LVCMOS on a pin in the 1.8 V
   DDR2 bank -- `Error (169029): Pin rst_n is incompatible with I/O bank 5`. A
   reset port now falls back to the board's `"reset"` entry whatever it is
   called.

**Ports were renamed to the convention rather than aliased in the generators.**
`flash_*` -> `cf_*` on the two EPCS tops, and `clk`/`uart_tx`/`uart_rx` ->
`clk_in`/`ser_txd`/`ser_rxd` on the DDR2 exerciser. Justified because the pins
those names resolve to are *identical* to the ones the board's converted JOP
flow already generates -- the RTL's own comments recorded them.

**The XDC side is still NOT converted**, and deliberately: `XdcGenerator`
filters UART pins on `verilogPort.startsWith("ser_")` and emits the clock as
`clk`, so for the Vivado tops it silently drops pins. See item 93. The Quartus
generators did not have that problem because those tops already used the JOP
spelling.

`uart_test` was a bonus find: a `.qsf` and RTL with **no Makefile target at
all**, so neither could be built by anything. Converted and given a target.

<a id="item-95"></a>

### Item 95 — ~~The README promises 13 simulations; CI watches a different set~~ — DONE 2026-08-30

Found by pointing a cold agent — no project context, no memory, a fresh clone
from GitHub — at README.md and asking it to build the project and show it
working. It reached `Hello World!` in about 45 s, so the headline path is
sound. Then it found four broken simulations (items 96-99), and the reason
they were all still broken is this item.

**None of the four is in CI, and three were mentioned nowhere in this
document.** The README advertises 13 simulation and test commands; CI runs
`testOnly jop.core.* jop.io.* jop.pipeline.* jop.memory.* jop.ddr3.*
jop.config.* jop.sim.*` plus a JVM-suite matrix. Those two sets are not the
same, and **the difference between them is precisely where the rot is**.

Same shape as the flash microcode nobody built and the PLL tests that
`assume`d themselves into silence: nothing was watching, so nothing said
anything.

**DONE 2026-08-30.** `.github/scripts/run-readme-walkthrough.sh` extracts the
fenced block under "### Build and Run Simulation" from README.md and executes
it. Two CI jobs call it: `readme-walkthrough` runs steps 1-7 on every push
(~6 min); `readme-walkthrough-long` runs step 8 nightly (~25-50 min, since it
runs until a collection actually happens).

**The sync problem is solved by construction, not by discipline.** Listing the
commands in the workflow would create two lists that drift apart -- the same
defect this project keeps finding in itself, a constant outliving whatever it
was copied from. There is ONE list, in the README, and CI runs it.

**It found a defect on its first run**, one that had survived two cold-newcomer
walkthroughs: step 2's `sbt compile` executed in `asm/`, because step 1 leaves
you there and step 2 only said "from project root" in a COMMENT. It reported
success while compiling none of the HDL, then self-healed because the later
runMain steps compile from the root. Both agents missed it by running the
commands sensibly rather than literally. **An executable README is stricter
than a careful reader**, which is the point.

Covers the SIMULATION section only. The FPGA section cannot run in CI, so
hardware stays with newcomer runs and the bench.

Related: a seventh symptom of the same class was fixed at the same time — the
documented simulations load `.jop` files that no documented build step
produced. See `java`'s `test-apps` target.

<a id="item-96"></a>

### Item 96 — ~~`JopCoreWithSdramSim` stalls~~ — WITHDRAWN 2026-08-30, never a bug

README step 5. Runs, exits cleanly at its `maxCycles = 500000` cap, and never
prints "GC done" or "Hello World!" — the trace stops after `Small boot / GC
init...`.

**Diagnosed, not merely observed.** The cap is hard with no other exit, so "no
output" could have been a short window. It is not:

- last UART byte at cycle 42,189; nothing for the remaining 457,811
- from ~100 k to 500 k the trace visits **exactly three PC values** (`0638`,
  `02c8`, `02d2`) in a fixed 4:2:1 ratio, near-identical between the
  100k–110k and 250k–260k windows (137/68/273 vs 138/68/273)
- the BRAM sim, which does finish, visits **360** distinct PCs
- BMB cmd/rsp counters keep climbing, so it is issuing real bus traffic

A closed loop in steady state. **More cycles will not help** — this is a real
bug in the SDRAM/memory-controller interaction, not a timeout to tune.

> **RETRACTED 2026-08-30 (`17d3e5c`). The paragraph above is wrong**, and it is
> left standing because the reasoning is instructive: every observation in it is
> accurate, and the conclusion drawn from them is not. More cycles were exactly
> what it needed — the budget was **19x short**. `maxCycles` is now 12,000,000
> in `JopCoreWithSdramSim.scala:111` and the simulation completes.
>
> The three-PC steady state is what a *slow* boot looks like through a narrow
> window, not what a deadlock looks like. Distinguishing the two needs a run
> long enough to falsify the loop hypothesis, and no amount of detail inside the
> window can substitute for that.

Pre-existing: the file was last touched in `4e1c669`, long before the build
port.

<a id="item-97"></a>

### Item 97 — ~~`JopSmpBramSim` fails its own GC check~~ — FIXED 2026-08-30

README step 8. Runs the full 100,000,000 cycles in 22m50s, both cores boot and
print "Hello World!", and then the simulation's own assertion fails:

```
FAIL: Did not see 'GC test start'
```

So the cores are alive and the SMP path works; whatever should trigger the GC
phase of this test does not. Not investigated further.

<a id="item-98"></a>

### Item 98 — ~~`JopInterruptSim` fires 2 of 5 interrupts~~ — WITHDRAWN 2026-08-30, budget was 4x short

```
FAIL: Expected 'I:TTTTTOK' in output, got: '...I:TT'
```

Runs its full 4,000,000-cycle cap. **Deterministic** — reproduced twice with
identical output, once under heavy concurrent load and once on a nearly idle
machine, so it is not a scheduling flake. The README describes this as "5
interrupts, ~2.6M cycles", so the expectation is documented and unmet.

<a id="item-99"></a>

### Item 99 — ~~`JopDebugProtocolSim` NPEs during elaboration~~ — FIXED 2026-08-30

Fails in about 2 s, before any simulation starts:

```
java.lang.NullPointerException: Cannot invoke "spinal.core.Vec.apply(int)"
because the return value of "jop.system.JopCluster.gcRootRamAddr()" is null
    at jop.system.JopCluster.$anonfun$debugCtrl$2(JopCluster.scala:319)
```

An elaboration-time fault, so it is a configuration/wiring problem rather than
a functional one: the debug controller reaches for a GC root RAM address that
this configuration never built.

### Gotcha — a cold agent is only cold if the PATH is cold too

The same walkthrough concluded "no Quartus, no Vivado on this machine" and
chose the Colorlight i5 on that basis. Both vendor tools ARE installed
(`/opt/altera`, `/opt/xilinx`); they are simply not on the default `PATH`,
and the board Makefiles supply absolute paths. The conclusion happened to be
defensible — the i5 is the only board needing no vendor tools — but it was
reached from a false premise, and a newcomer would draw the same wrong
conclusion about what this machine can build.

Separately: a subagent gets none of `.claude/settings.local.json`, which is
gitignored and therefore absent from a fresh clone. So the cold agent had a
narrower permission set than the session that spawned it, and hardware
programming was refused for that reason rather than for anything in the docs.

<a id="item-100"></a>

### Item 100 — The newcomer hardware path, and a JTAG cable that was INTERMITTENT

> **2026-08-31 — measured, and it is not the cable.** The two Altera cables were
> swapped: the Terasic (`91d28408`) onto the EP4CGX150, the level-shifted Pico
> (`e6616408`) onto the A-E115FB. Ten single-try detections each, retries
> disabled (`JTAG_SCAN_TRIES=1`, or the built-in retry hides exactly what is
> being measured):
>
> | | Pico `e6616408` | Terasic `91d28408` |
> |---|---|---|
> | EP4CGX150 | 3/10 (before swap) | **10/10** |
> | A-E115FB | **10/10** | worked (before swap) |
>
> The Pico is fine. It was 3/10 on the EP4CGX150 and is perfect on the
> A-E115FB, so the fault travelled with the BOARD END, not the cable — which is
> the opposite of what the old heading ("a cable that died") asserted.
>
> **The experiment alone carries a confound**: the swap changed which cable AND

**[Full journal →](status/item-100.md)** — 153 lines of investigation detail.


<a id="item-101"></a>

### Item 101 — ~~every hardware record before 2026-08-26 was taken when the declared clock could differ from the silicon~~ — LARGELY RESOLVED 2026-08-31

> **The suspicion came from a decoy file, and the decoy is gone.** The sentence
> that raised this — "confirming whether the 2026-08-15 baseline row was also
> really 60 MHz, **which its own PLL file suggests**" — was reading
> `fpga/qmtech-ep4cgx150-sdram/dram_pll.vhd`, a TRACKED but unbuilt file dated
> 2026-08-15 carrying `clk1 = x6/5` = 60 MHz. Nothing generated from it; the
> Makefile merely listed it as a prerequisite, so it looked live. It was deleted
> in `30496e9` when the template moved in with its generator.
>
> **12 cores was already settled on 2026-08-23**, by the STA clock table rather
> than by any file on disk:
>
> ```
> clk_in       Base       20.000 ns  50.0 MHz
> pll1|clk[1]  Generated  27.777 ns  36.0 MHz   Divide by 25, Multiply by 18
> ```
>
> — with `SMPGC OK`, `cores 12, publishers 11`, `minors 10 verified 192 errors
> 0` on the `14/5` bitstream. That investigation is recorded in item 34 and
> explicitly names the decoy as having "produced a confident and wrong
> conclusion".
>
> **8 cores re-validated 2026-08-31** under the generated flow: PLL `x1/÷1` from
> the 50 MHz input = 50 MHz as declared, timing MET at **+0.532 ns** (Slow
> 1200mV 100C — the binding corner; the -40C models read +2.755 and +7.735 and
> reporting those would flatter it fivefold), 94,847/149,760 LE (63 %), and on
> hardware `cores 8, publishers 7`, `minors 10 verified 192 errors 0`,
> **`SMPGC OK`**.
>
> **What is genuinely unknowable:** what the August bitstreams' hand-maintained
> PLL actually ran at. Those files are gone. It no longer matters, because both
> rows have been re-established under a flow where the PLL is generated from the
> preset.
>
> **Left open only as a habit:** any record still citing a pre-2026-08-26 build
> that has NOT been re-run should say "unverified since", rather than being
> re-dated.

#### The original filing, kept for the reasoning

**Filed 2026-08-31**, from the build-port review. Split out of the note in item
34's 12-core section, where it had been sitting since 2026-08-15 as a sentence
that suspected itself and was never acted on.

Until `DramPllGen` (`99112f3`, 2026-08-26), `dram_pll.vhd` was a hand-maintained
file. A preset could declare one frequency while the PLL ran the silicon at
another, and nothing in the flow compared them — the old note in
`TROUBLESHOOTING.md` even told you to change `clkFreq` to match the PLL, which
is backwards. That is how three BRAM presets sat 2.5-3.7 ns over timing for five
months.

**The specific exposure** is the EP4CGX150 SMP rows validated 2026-08-15: 8
cores recorded at 50 MHz and 12 cores at 36 MHz. If the PLL was actually at 60,
STA signed off against a period the parts never ran at, and both records are
worth nothing. Their own PLL file is what raises the suspicion.

**Not affected:** anything built after 2026-08-26, because the PLL is generated
from the preset. The i5, A-E115FB and Wukong were all re-validated on
2026-08-30 under the fixed flow and are sound.

**Next action is a control build, not reasoning.** Re-run 8 and 12 cores and
read the PLL the build actually generated. This is blocked in practice on the
EP4CGX150's cable ([item 100](#item-100)) being dependable, or the board moving
to the Terasic blaster.

**Do not simply re-date the old rows.** If they are not re-run, mark them
"unverified since 2026-08-26" — an honest gap beats a record nobody trusts.

<a id="item-102"></a>

### Item 102 — the bitstream depends on a `.jop` it does not contain

**Filed 2026-08-31**, found by rebuilding the SmpGcTest apps after an 8-core
bitstream and watching `make smp-program` start a fresh Quartus fit.

The chain in `fpga/quartus.mk`:

```
$(SOF_FILE):  $(GEN_STAMP) $(QUARTUS_PRJ)/$(REV).qsf
$(GEN_STAMP): $(SCALA_SRC) $(UCODE) $(UCODE_SCALA) $(JOP_APP_FILE)
JOP_APP_FILE = $(CFG_DIR)/java/apps/Smallest/HelloWorld.jop
```

`JOP_APP_FILE` is gated on `GEN_MAIN` being `JopTopVerilog` — on "is this a
preset?", not on "does this design embed a program".

**For a serial-boot design it embeds nothing.** `ep4cgx150Smp` is
`BootMode.Serial` with SDR memory; its generated RTL loads no program image and
every `.bin` beside it is per-core microcode, jump table or JBC RAM. The program
arrives over UART at download time. Yet rebuilding `HelloWorld.jop` invalidates
the stamp, and a 30-60 minute refit follows for a file the bitstream never
contained.

Most presets are serial-boot, so this is the common case, not the corner.

**Why the dependency exists at all:** a **`BootMode.Simulation`** design boots
from a preloaded image, so the `.jop` IS baked into the bitstream, and there
`$(SCALA_SRC)` does not cover the change — the app must retrigger generation or
the bitstream keeps a stale program.

**The discriminator is the boot mode, not BRAM.** Two BRAM presets on the same
board differ: `ep4cgx150Bram` is Simulation and embeds, `ep4cgx150BramSerial` is
Serial and downloads. `ep4cgx150BramGc` inherits Simulation from
`ep4cgx150Bram` by deriving from it and overriding only `clkFreq`, which is easy
to miss by reading its own definition. `quartus.mk`'s comment says "a BRAM
design that does not serial-boot" — the operative clause is the second half, and
leading with BRAM invites exactly this error.

**The two obvious fixes are both wrong.**

- Gating on boot mode restates configuration in Make, which is exactly the
  duplication this file refuses elsewhere ("two copies of a naming rule agree
  until the day they do not").
- Making it order-only (`|`) fixes the serial case and silently breaks the
  embedded one: a changed program would no longer rebuild the bitstream that
  contains it. A correctness bug traded for a latency one.

**The shape that fits** is to ask the generator, as `CFG_NAME` already asks
`BuildLayoutMain` rather than reimplementing the sanitising rules. A preset
knows whether it embeds an app; Make should not have to guess.

**Workaround until then:** build the apps BEFORE the bitstream, not after.

<a id="item-103"></a>

### Item 103 — ~~the A-E115FB's JTAG bank is not 3.3 V~~ — MEASURED, and it always was

**VTREF on header pin 4 = 3.25 V** (2026-08-31). The bank is 3.3 V nominal. A
bare Pico clone should have worked on this board all along, with no level
shifting.

**What the record said instead**, in four places: "its own Pico drove 3.3 V into
a JTAG bank that is not 3.3 V"; "the A-E115FB's 1.8 V-banked JTAG"; a README row
saying 2.5 V; and a note in `pico-dirtyjtag-setup.md` saying 1.8 V. The last two
deferred to EACH OTHER about a number neither had measured — and by 2026-08-30
one of them no longer contained the figure it was being cited for.

**Where the wrong number came from.** This board genuinely has 1.8 V banks:
3 to 6 carry the DDR2 interface at SSTL-18, and `clk`, `rst_n` and the LEDs sit
in banks shared with them, which is why they take a 1.8 V I/O standard. All of
that is true and is still documented. It was attributed to the JTAG bank, which
is in neither group.

**What it cost.** The bare Pico's failure on this board was explained by
over-voltage; `fpga/a-e115fb-bram` was retired partly citing it; and a level
shifter was designed and built on that premise. The board-side JTAG plug was
later found loose (see [item 100](#item-100)), which is the leading candidate
for the original failure.

**The shifter was worth building anyway, for the other board.** The EP4CGX150's
bank measures 2.53 V, so a fixed-3.3 V Pico really does overdrive it — that
claim was checked and holds. The two boards' requirements were exactly inverted
in the documentation: the shifter was built for the board that did not need it
and turned out to be necessary on the one that did.

**The lesson is the deferral, not the number.** Two documents pointed at each
other for weeks and neither owned the fact. A measurement nobody had taken read
as a disagreement between sources, which is the shape that makes it survive
review.

<a id="item-104"></a>

### Item 104 — the generators restate board facts as literals

From the 2026-08-30 review, "suspected" tier. Three findings, one fix pass —
each restates in a generator something the config already knows.

- `TimingConstraints.scala:127` — `pllOutputs = Seq(1, 2, 3)` and
  `Board.scala:105` `alteraClockPath` encode the EP4CGX150's altpll hierarchy.
  MAX1000 uses `c0/c1`; CYC5000 uses `outclk_*` on a path that is not
  `altpll_component` at all. Neither consults `PllSpec`, which exists as the
  single source for PLL shape.
- `QuartusProject.scala:72` states `"3.3-V LVCMOS"` device-wide, three lines
  below a comment reading *"what Quartus CANNOT know stays here: which I/O
  voltage the board wires its banks to"*. `XdcGenerator.scala:128` defaults
  every pin to `LVCMOS33` and never reads `portIoStandards` at all.
- `TimingConstraints.scala:123` says `clk_in`, `XdcGenerator.scala:37` says
  `sys_clk` on port `clk`, `LpfGenerator.scala:85` states it a third way. The
  fact is already in `manufacturer.explicitClockPort`.

**Why this is not urgent and is still real.** What protects the other boards
from the PLL literal is `instanceName.isEmpty` — a DIFFERENT fact from "this
PLL has three outputs". Add an instance name to the MAX1000 and Quartus
discards the entire `set_clock_groups` with a warning.

**The I/O standard half got sharper on 2026-08-31**: see [item 103](#item-103).
Board voltages in this project have been wrong in the docs for weeks at a time,
and a generator that hardcodes one is a place that cannot be corrected by
measuring.

<a id="item-105"></a>

### Item 105 — assembly navigation assumes the first board is the FPGA

`HwVerifyDescriptor.scala:124` and `LpfGenerator.scala:70` use
`assembly.boards.head`; everything else uses `fpgaBoard`
(`boards.find(_.hasFpga).get`). `SystemAssembly`'s `require` guarantees SOME
board carries an FPGA, not the first one.

Safe today only because every composite happens to list the core board first. A
daughter-board-first assembly gives `HwVerifyDescriptor` `FAMILY=unknown` and no
`PROBE_ALIAS`/`CONSOLE_ALIAS` lines, and hands `LpfGenerator` the daughter
board's `ioStandard` and `parkedPins`.

Same root: `UartPartOverride` (`JopConfig.scala:179`) validates against
`fpgaBoard.devices` only, so `JopTopVerilog wukongDualIndependent uart=J11_UART`
is refused even though `assembly.pinMapping` resolves it — `J11UartAdapter` is a
second board in the assembly. Loud rather than silent, but wrong.

<a id="item-106"></a>

### Item 106 — the device map is keyed by raw strings in three places

`BoardDesign.scala:175` is `Map[String, DeviceInstance]`. Two consumers still
match literals rather than `DeviceType.key`:

- `JopConfig.scala:1425` — `filter { case (k, _) => k == "uart" }`. A preset
  naming its UART entry anything else yields an EMPTY device map, which is
  valid: the design elaborates with no UART and no UART constraints.
- `JopConfig.scala:596` — `sys.devices - "vgaText"`, a silent no-op if the key
  differs, which would leave `ep4cgx150DbVgaDma` instantiating both VGA drivers.
- `HwVerifyDescriptor.scala:129` uses the key but as a literal.

Every producer currently writes `"uart"`, so nothing is broken. This is the
surviving family of the `"eth"` / `"ethernet"` bug (item 66), which emitted no
error and simply left the Ethernet clock group out.

<a id="item-107"></a>

### Item 107 — `alchitry-au`'s bitstream has no prerequisite on its project

`fpga/alchitry-au/Makefile:73` — `all: microcode generate ips project bitstream`
— and `bitstream:` at line 96 does not depend on `project`. Correct when make
runs them in order; racy under `make -j`. Same shape at `cyc5000-sdram:43` and
`max1000:81`, though those bottom out in `$(SOF_FILE)`, which does carry its
prerequisites.

<a id="item-108"></a>

### Item 108 — README's 16-core claim rests on figures README itself withdrew

`README.md` advertises SMP "up to 16-core" on the EP4CGX150 in four places.
The supporting figure — 86 % LE, +1.8 ns slack at 80 MHz — appears undated in
`implementation-notes.md:155`, `ihlu-design-analysis.md` and
`smp-performance-analysis.md`.

Two things undercut it. `README.md:463` explicitly drops SMP resource figures as
undated — *"they have been dropped rather than left undated"* — while keeping
the headline that depends on them. And `JopCluster.scala:59`'s
`require(cpuCnt <= 16)` is an ARCHITECTURAL ceiling from a 4-bit `rootSel`
field, not a fit result; the docs read it as a fit guarantee.

Note the validated ceiling is 12 (and 12 needs `mcache=14/5` to route at all —
see item 34). 16 has never been built successfully: it was measured at 182,501
of 149,760 LE.

<a id="item-109"></a>

### Item 109 — ~~`wukongFull` has never been rebuilt since the frem fix~~ — CLOSED 2026-08-31, DoAll 66/66

> **The closure test was run and it passes.** `wukongFull`, all four compute
> units, `bytecodes "*" -> "hw"`, DDR3 at 100 MHz: timing **MET (WNS +0.349 ns,
> WHS +0.048 ns)**, `Download OK (checksum 0xe3b17aaa)`, and DoAll
> **66/66 with zero failures**, ending `JVM exit!`. `FloatTest ok`,
> `FloatField ok`, `FloatArray ok` — the three that died before.
>
> This is the first time this preset has ever passed DoAll.
>
> **A false start worth recording.** The first attempt streamed all 288 KB, then
> `Checksum timeout: got 0 bytes` and the board went unresponsive. It was not
> the fix and not the baud: `wukongFull` deliberately declares 1 Mbaud rather
> than the 2 Mbaud default (a measured CH340N limit, documented in the preset),
> and the ready handshake had already succeeded at that rate. Downloading
> HelloWorld to the same bitstream gave 889 clean lines, which isolated the
> board as healthy; a reprogram and retry then passed. Transient, cause not
> established — if it recurs on a 288 KB image, that is the thread to pull.

[Items 69/74](#item-69) were closed on 2026-08-31 having proven the MECHANISM:
generating `Const.java` for `wukongFull` gives `SUPPORT_FLOAT = false` before the
registry change and `true` after.

**The closure test was not run.** `wukongFull` has not been built, programmed or
put through `DoAll` since. The claim that it now passes is an inference from the
flag, not an observation of the board — and this project's own record is that a
mechanism proven in isolation is not the same as a design that runs.

Cheap to close: `make -C fpga/qmtech-xc7a100t-wukong ddr3-build DDR3_CFG=wukongFull`,
then program and run DoAll. Until then item 69 should be read as "cause found and
fixed", not "preset works".

<a id="item-110"></a>

### Item 110 — three corpora have never been reviewed

The 2026-08-30 review scoped to the generator layer, the Makefiles and the
docs — roughly 2,000 lines of new code. It never looked at:

| corpus | lines |
|---|---:|
| `java/runtime/src` — GC, `JVM.java`, SoftFloat | 50,895 |
| `java/tools/src` — jopa, jopizer, jopsim | 46,412 |
| `jop/core` + `jop/pipeline` — the RTL | 5,923 |
| `asm/src` — microcode | 2,982 |

**This is the gap that matters most, and the evidence is in the review itself.**
The `frem` defect lived exactly at the boundary between `JopConfig` and
`JVM.java`: a Scala registry decided a flag, the flag decided whether a Java
library was compiled in, and neither side was wrong on its own. A review scoped
to one corpus is structurally incapable of seeing that, and this project keeps
producing it — `Const.java` per-config (item 47's GOTCHA), the microcode boot
mode handed to the wrong preset (`ep4cgx150BramGc`), the baud that lives in
elaboration and is consumed by a Python downloader.

**Next review should be scoped by BOUNDARY, not by directory.**

---

**SCOPING EVIDENCE, 2026-08-31.** Two crossings found in about twenty minutes of
looking, both of the predicted shape — one fact, several languages, nothing
connecting them.

**B1. The object handle layout.** `GC.java:90` defines `OFF_PTR = 0`,
`OFF_MTAB_ALEN = 1`, `OFF_SPACE = 2`, `OFF_TYPE = 3`, `OFF_NEXT = 4`,
`OFF_GREY = 5`. The array-length offset is then restated twice more as a bare
literal:

```
asm/src/jvm.asm:1992      ldi 1 / add        // arrayref+1 (in handle)
BmbMemoryController:1259  ((addrReg + 1) << 2)   // handle[1]
```

Three languages, three copies of `1`, joined only by comments. Nothing would
notice a divergence, and changing the layout means finding all three by hand.
`OFF_PTR = 0` is implicit in the hardware as "read the handle with no offset",
which is the same dependency wearing no name at all.

**B2. The I/O address space** — historically the first entry in
`TROUBLESHOOTING.md`, "I/O Address Mismatch". `Const.java` is GENERATED
(`IO_BASE = -128`) from a Scala allocator that assigns addresses dynamically in
`0x80-0xFF`. `jvm.asm` carries **12 hand-written** `io_*` constants
(`io_cnt = -16`, `io_wd = -13`, `io_exc = -12`, ...) that must agree with it and
are maintained by hand. A mismatch sends reads and writes to the wrong device,
which is exactly the failure that troubleshooting entry describes.

**Candidate boundaries still unexamined:** boot mode -> microcode variant
(`ep4cgx150BramGc` was handed the serial ROM once); elaboration -> `summary.txt`
-> host tooling (`download.py`, `monitor.py`, `console.mk` all consume the baud
the RTL baked in); and `JopInstr.java`'s IMP_ASM/IMP_JAVA table against
`BytecodeConfig` in Scala, which is the boundary `frem` fell through.

<a id="item-111"></a>

### Item 111 — nothing measures whether a test can fail

660 tests pass. Nothing establishes that any of them would go red if the thing
it guards broke.

This is not hypothetical — it happened three times while WRITING the guards on
2026-08-30/31:

- `check-status-index.sh` reported *"priority list: 0 entries, none closed"* —
  passing because the extraction regex matched nothing. Visible only because it
  printed a count rather than a verdict.
- `check-console-baud.sh` first asserted a non-zero exit, which the unfixed tree
  also produced (`No rule to make target 'require-baud'`) — and then a
  `grep -i baud`, which matched that very message.
- `GeneratedConstraintsTest` asserted an invariant that was simply wrong, and
  "fixing" the generator to satisfy it would have introduced the defect it was
  meant to catch.

Each was caught by deliberately breaking the code and watching for red. That is
a habit, not a property. No coverage figure, no mutation testing, and no
convention that a new guard must be demonstrated red before it is trusted.

<a id="item-112"></a>

### Item 112 — `ConstraintDriftTest` covers 2 presets of 46

It compares generator output against tracked oracles for `wukongSdram` and
`wukongDdr3` — and nothing else. `JopTopVerilog.resolveBase` accepts 46 names.

That is the STRONGEST check in the tree: an actual diff against a known-good
file, as opposed to `GeneratedConstraintsTest`'s completeness assertions. It has
roughly 4 % coverage.

The obstacle is real: most tracked `.qsf` files are BOARD pin references whose
port names are the board's rather than the design's, so a direct diff needs a
name mapping. Worth solving for the Altera boards, where the generators had no
drift coverage at all until 2026-08-31.

<a id="item-113"></a>

### Item 113 — `cold-check` covers 3 boards of 12

`cyc5000-sdram`, `alchitry-au`, `colorlight-i5` — one per toolchain, which was
the design. Only the CYC5000 reaches constraint generation; the other two stop
at RTL.

Not covered at all: the EP4CGX150 (the primary board), the Wukong (the one board
with a confirmed shared-rule defect), `max1000`, `a-e115fb-ddr2`, and every
exerciser flow. Every regression `cold-check` was built to catch was of a shape
that could equally have hit those.

<a id="item-114"></a>

### Item 114 — ~~hardware evidence is discarded after every run~~ — WITHDRAWN 2026-08-31, it never was

> **This item was wrong, and filing it was a failure of the same kind it
> described.** `hw_verify.py` writes the full console text for EVERY run, pass
> or fail, to `<config>/hw_verify.run<N>.txt`, under a comment reading *"Keep
> the console text for EVERY run, pass or fail. A bare PASS with nothing behind
> it is not evidence, and this is the only record that the board actually said
> what the verdict claims."*
>
> The 8-core run of 2026-08-31 has `hw_verify.run1.txt`, 3,350 bytes, containing
> `SmpGcTest: cores 8, publishers 7`, `minors 10 verified 192 errors 0` and
> `SMPGC OK` — the exact lines this item claimed were thrown away.
>
> **How it was got wrong:** I looked at `hw_verify.log`, which is deliberately a
> one-line-per-run INDEX, saw 131 bytes, and concluded the evidence was gone
> without checking whether anything else had been written beside it. Then I
> reprogrammed the board and re-downloaded by hand to "recover" a transcript
> that was already on disk.
>
> The lesson is the one this whole review keeps producing: an absence observed
> in one file is not an absence. It is the same shape as reading a decoy
> `dram_pll.vhd` ([item 101](#item-101)) and as the two documents deferring to
> each other about a voltage neither had measured ([item 103](#item-103)).
>
> **What survives** is a much smaller point: `hw_verify.log` does not say that
> the transcripts exist or where they are, so a reader lands where I landed. One
> line in the summary naming the transcript file would close that.

`hw_verify.py` writes a 131-byte summary line and throws the board transcript
away:

```
2026-08-31T09:05:29 ep4cgx150Smp SmpGcTest run=1/1 expect='SMPGC OK'
ok=0 fail=0 exit=True crash=0 timing=MET PASS
```

The `SMPGC OK`, `cores 8, publishers 7`, `minors 10 verified 192 errors 0` that
constitute the actual evidence are gone. Re-establishing the 8-core record on
2026-08-31 meant programming and downloading by hand to recover them.

**This is how validation decay starts.** A verdict cannot be re-examined; a
transcript can. Every historical hardware claim in these documents is therefore
unauditable, which is exactly the position [item 101](#item-101) found itself
in — and it was resolved by a transcript that happened to be pasted into an item
by hand.

<a id="item-115"></a>

### Item 115 — every simulation reports an elaboration failure and then succeeds

`JopCoreBramSim` prints

```
[Warning] Elaboration failed (2 errors).
          Spinal will restart with scala trace to help you to find the problem.
```

then re-elaborates, runs, and produces correct output. Deterministic — three
consecutive runs, and it is **pre-existing**: reverting only `JopCoreConfig.scala`
to `origin/main` reproduces it exactly.

The second pass names `UNASSIGNED REGISTER
(toplevel/jopCore/pipeline/cu/icu/resultReg : UInt[64 bits])` at
`IntegerComputeUnit.scala:50`, which is [item 45](#item-45)'s territory — but
nobody has established that the two errors of the first pass ARE that register,
or why the second pass proceeds where the first refused.

Nothing depends on it today. It is filed because a permanent "failed" banner in
every simulation log is a warning that has become normal, and this project has
already learned what those cost (item 100's `overriding recipe`).

<a id="item-116"></a>

### Item 116 — ~~`current-status.md` is 491 KB and cannot be read~~ — DONE 2026-08-31

9,200 lines. It is the file every session is told to read first.

As of 2026-08-31 `make check-build` keeps it CONSISTENT — every item anchored,
no duplicate anchors, every `#item-N` link resolving, no closed item in the
priority list. None of that makes it usable. The 2026-08-30 review had to be
told not to open it whole, and its own agent worked by `grep` and line ranges.

**Done 2026-08-31: split, plus navigation.** The 18 items whose sections ran to
100 lines or more — 5,046 lines, 58 % of all item text — moved to
`docs/status/item-<N>.md`. Each keeps its heading, its anchor, its opening
summary and its gotchas here, and gains a `Full journal →` link. 9,671 lines to
4,828.

**The anchors deliberately did NOT move.** Every `#item-N` reference in the repo
and in commit messages still resolves against this file, so the split is
invisible to everything that cites it.

**Verified byte-identical**, not eyeballed: each journal was diffed against the
original span, 18 of 18 exact. That mattered — the first attempt bounded each
item from its heading to the NEXT heading, which swallowed the following item's
`<a id=>` line and silently unanchored 18 items. `check-status-index.sh` caught
it immediately, which is why the restructuring was safe to attempt at all: the
guards landed first, on 2026-08-31, and this was the first real use of them.

A navigation header now says which of the three sections answers which question,
and the guard was extended to assert every journal is present and linked.

<a id="item-117"></a>

### Item 117 — nothing prevents a preset that no flow selects

Seven presets are reachable only by typing their names — recorded in
`docs/measurement-presets.md` on 2026-08-31, with the finding that all seven
carry real published results and none has EVER been referenced by a Makefile or
CI job.

Documenting them does not stop an eighth. A test could assert that every name
`resolveBase` accepts is either referenced by a flow or listed as a measurement
vehicle, which would make the choice explicit at the moment a preset is added
rather than discovered by an audit months later.

<a id="item-118"></a>

### Item 118 — the nightly CI run was cancelled after 1h4m

Run `33377819507`, 2026-08-31T09:28Z, `schedule` on `main`: **cancelled** after
1h4m13s.

That is [item 47](#item-47)'s failure mode, which is marked FIXED — the
`concurrency.group` gained `github.event_name` precisely so a push could not
kill a scheduled run. No push of mine landed at 10:32.

**Do not assume the fix regressed.** The cancellation coincides with a 12-core
Quartus fit being killed on this host, so runner contention or a self-hosted
resource limit are at least as likely. The point is that a scheduled run did not
complete and nobody would have noticed: the nightly is the only thing exercising
the schedule-only jobs, and its failure mode is silence.

<a id="item-119"></a>

### Item 119 — the object handle layout has no single definition and no coverage

**Boundary review B1 (item 110), 2026-08-31.** `GC.java:90` defines the handle
field offsets. Roughly **25 sites outside it re-express the same layout** across
four languages, joined only by comments.

**Offset 1 (`OFF_MTAB_ALEN`) is written as a bare literal in:**
`BmbMemoryController.scala:1259` (`(addrReg + 1) << 2  // handle[1]`),
`asm/src/jvm.asm:1993`, `jvm_long.inc:333` and `:412` (array length), and
`jvm_call.inc:168` and `:312` — where the SAME word is read as the **method
table pointer** for every virtual and interface call. Word 1 has two meanings
and nothing but the code path discriminates them.

**Offset 0 (`OFF_PTR`) is worse: it is expressed as the ABSENCE of arithmetic**
in at least 14 places — `BmbMemoryController.scala:1055`, four `stmraf`s in
`jvm_long.inc`, two in `jvm.asm`, `System.arraycopy`, `Startup.xastore`. There
is no literal to grep for. Moving it would require an insertion at each, and
the failure lands in the GC's own handle table.

**A second, deliberately decoupled definition exists.**
`java/runtime/src/jvm/java/lang/System.java:17` redeclares
`OFF_MTAB_ALEN = 1`, and line 56 reads `rdMem(srcHandle + /*GC.*/OFF_MTAB_ALEN)`
— the `GC.` qualifier commented out on purpose. Change GC.java and `arraycopy`
keeps the old word as its length, then copies that many words. Silent overrun.

**`OFF_MEM = 5` aliases `OFF_GREY = 5`**, both live: `Memory.java:304` reads the
word `JVM.java:122` writes the grey-list link into. Under the SATB barrier
`getMemoryArea()` can return a grey-list successor cast to a `Memory`.

**COVERAGE: none.** No elaboration `require` mentions a handle offset. The
formal suite drives `handle := 0` and proves only state-machine liveness;
`ArrayCache`/`ObjectCache` treat the handle as an opaque tag.
`BmbMemoryControllerSdramTest` writes only `handle[0]`. And the tests that would
exercise the RTL's bounds check —
`java/apps/JvmTests/src/jvm/Array.java:89,105,113,117` — are **commented out**,
under a live `println("\tupper bound exception comes too late")` at :78. Only
the NEGATIVE index case runs, and that exits at `BmbMemoryController.scala:1044`
without ever reading handle[1].

So the RTL's one and only use of a handle offset has no test, no formal
property and no elaboration check. In the permissive direction DoAll still
reports 66/66, `SmpGcTest` still prints `SMPGC OK`, and every out-of-bounds
array access becomes a silent adjacent-memory read or write.

**`docs/architecture/constant-dependencies.md` names the wrong two sides** —
it says the layout must agree between GC.java and JOPizer. JOPizer has no stake:
it never lays out handles (grep over `java/tools/src` finds only BCEL
`InstructionHandle` noise). The real other sides are the microcode and the RTL,
which that document omits, and it also omits `OFF_ELEM = 6`.

**The fix that fits** is the one the project already uses for the neighbouring
class-struct constants: `ConstGenerator` emits `CLASS_HEADR`, `CLASS_SUPER` and
friends into `Const.java`. The handle offsets are the one constant family it
does not carry, and the mechanism is already there.

<a id="item-120"></a>

### Item 120 — ~~`Const.java`'s dependencies omit every file that decides an I/O address~~ — FIXED 2026-08-31

**Boundary review B2 (item 110), 2026-08-31.** `java/Makefile:47-52`:

```
$(CONST_JAVA): $(CONST_STAMP) \
               .../jop/generate/ConstGenerator.scala \
               .../jop/config/JopConfig.scala \
               .../jop/config/JopCoreConfig.scala
```

Missing: `jop/io/IoAddressAllocator.scala` (the packing rule),
`jop/io/DeviceTypes.scala` and `jop/config/DeviceInstance.scala` (every device's
`addrBits`), and `jop/memory/JopMemoryConfig.scala` (`JopIoSpace` — `SYS_BASE`,
`UART_BASE`, `CARD_BASE`, `ZERO_BASE`).

The RTL is regenerated from Scala on every build. `Const.java` is not. Change
`SdNative.addrBits`, or move `CARD_BASE`, and the hardware moves while
`Const.java` reports "Nothing to be done".

**Exactly the shape of item 60's `.sdc`**: a generated artefact whose
prerequisites omit an input that determines its content, so it is written once
and then silently stale. The observable failure is `TROUBLESHOOTING.md`'s first
entry — reads and writes land on the wrong device, the board looks dead, and
nothing goes red.

Confirmed by reading the Makefile. The stamp at `java/Makefile:29-33` already
exists to catch preset staleness; this is the same fix over a wider input set.

<a id="item-121"></a>

### Item 121 — absent devices collapse to one address, and the HAS_* flags guarding them are never read

**Boundary review B2 (item 110), 2026-08-31.** `ConstGenerator.scala:47-50`
resolves an absent device to `"0"`, so on a uart-only preset
(`build/wukongDdr3/.../Const.java:229-244`):

```
IO_ETH = IO_BASE + 0;  IO_SD = IO_BASE + 0;  IO_VGA = IO_BASE + 0;
IO_SD_SPI = IO_BASE + 0;  IO_VGA_DMA = IO_BASE + 0;  IO_CFG_FLASH = IO_BASE + 0;
```

— all aliasing 0x80, with `IO_MDIO = IO_ETH + 8` and `IO_USB = IO_MDIO`.

The generator's own comment says to use the `HAS_*` flags for runtime checks.
**Nothing does:** `grep -rn "HAS_ETHERNET\|HAS_SD_CARD\|HAS_VGA" java/ --include=*.java`
matches only the definitions in `Const.java` itself.
`java/net/src/com/jopdesign/net/EthDriver.java:116-202` drives `IO_ETH + 0..4`
unconditionally.

Harmless today because 0x80 decodes to nothing (`ioRdData := 0`,
`JopCore.scala:377`), so a driver for an absent device reads zeros. It stops
being harmless if I/O space fills enough for the downward packer to reach 0x80,
at which point an absent-device driver writes to a real peripheral.

**The pattern that gets this right is already in the tree**: `GC.java:595,638`
reads `IO_CARD_SHIFT` at run time and derives `genActive = USE_GENERATIONAL &&
cardShift0 != 0`, with the RTL driving `cardRdData := 0` when the preset has no
card table. A device that answers proves itself present. It is the only in-band
probe anywhere, and it is the model the other drivers lack.

<a id="item-122"></a>

### Item 122 — `JopCore` and `ConstGenerator` run the I/O allocator over different device sets

**Boundary review B2 (item 110), 2026-08-31.** Two independent allocation runs
that must agree:

- `JopCore.scala:315-316` allocates from `config.effectiveDeviceDescriptors(ctx)`
  — **one core's** devices.
- `ConstGenerator.scala:41-44` allocates from
  `config.systems.flatMap(_.effectiveDevices).toMap` — the **superset across all
  systems**.

Identical only while every system carries the same device set. A dual-system
preset with different devices per system — the shape `wukongDualSystem` already
uses at `JopConfig.scala:1033-1042` — would give `Const.java` the superset map
while each system's RTL used its own. No `require` compares them.

Not currently reachable: cores 1+ get an empty device map
(`JopConfig.scala:102-107`) and have no auto devices, and the boot-device gap is
covered by the null-UART mux at `JopCore.scala:380-382`.

**SUSPECTED**, not observed. Filed because the divergence would be silent and
the preset shape that triggers it already exists.

<a id="item-123"></a>

### Item 123 — ~~the `frem` hole is wider than `frem`~~ — FIXED 2026-08-31; `l2f` and `f2l` registered

**Boundary review B5 (item 110), 2026-08-31.** `BytecodeConfig.all` holds 34
entries; `JopInstr.java` marks **49** opcodes `IMP_JAVA`. The 15-name gap is the
hole `frem` fell through, and two more names in it have `frem`'s exact shape:

| bytecode | JopInstr | registry | JVM.java |
|---|---|---|---|
| `l2f` 0x89 | `:257` IMP_JAVA | **absent** | `:503`, gated on `SUPPORT_FLOAT` |
| `f2l` 0x8C | `:260` IMP_JAVA | **absent** | `:527`, gated on `SUPPORT_FLOAT` |

Verified directly. Both are unregistered, so `needsJavaFloat` cannot see them,
and both lose their implementation if `SUPPORT_FLOAT` goes false — exactly how
`frem` failed.

**They are DEFUSED, not fixed, and the defusing is a side effect.** Registering
`frem` as `("frem", 0x72, "float", Java, JavaOnly)` made `needsJavaFloat` — "any
float-group entry resolves to Java" — **a tautology**, since a `JavaOnly` entry
cannot be retargeted by a wildcard or a group key. So `SUPPORT_FLOAT` can never
be false again, and `l2f`/`f2l` ride along. Nothing records that they depend on
this.

**Which makes my own test unfalsifiable.** `SoftFloatLibraryTest`'s fifth case
asserts `cores.exists(_.needsJavaFloat)` for `wukongFull` and `xc7a100tDbFull`.
That predicate is now always true, so the test **can no longer fail** — it
asserts the same thing as its first case. It was written on 2026-08-31 to guard
this exact property, and it is [item 111](#item-111)'s concern appearing in code
written to prevent it, one day later. If someone removes the redundancy by
making `needsJavaFloat` ignore `JavaOnly` entries, `l2f` and `f2l` go with it.

**Fixed 2026-08-31.** `l2f` and `f2l` are registered as `JavaOnly` in the float
group — neither appears in any compute-unit predicate, so like `frem` there is
no hardware to select. `needsJavaFloat` gained an explicit `d2f` clause.

**And the test written to guard this was itself unfalsifiable — three times.**
The first asserted `needsJavaFloat` for two presets; the second asserted it per
gated bytecode; the third asserted it for `d2f` alone. All three stayed green
against a deliberately reverted fix, because the `JavaOnly` entries pin the
predicate true. The version that works asserts the MECHANISM — that the float
group contains a `JavaOnly` entry — which fails the moment `frem` is demoted.
The `d2f` clause is consequently unreachable and is documented as
belt-and-braces rather than tested.

**SUSPECTED cross-wiring: `d2f`.** Registered in group `"double"`
(`JopCoreConfig.scala:250`) while its Java body reads `SUPPORT_FLOAT`
(`JVM.java:560`). Its sibling `f2d` is in `"double"` and reads `SUPPORT_DOUBLE`,
which is consistent. So `d2f` is counted by neither guard. Latent only because
`SUPPORT_FLOAT` is pinned.

**The gate set is wider than `JVM.java`.** `SUPPORT_FLOAT` also gates five
`java/lang/Math.java` methods — `atan:362`, `sin:453/470`, `cos:486/502` — which
throw a plain `RuntimeException` rather than `JVMHelp.noim()`. Had the flag gone
false on a preset using `Math.sin`, the signature would have differed from
frem's and been harder to recognise.

**The structural gap.** Three of the four representations are now cross-checked
pairwise — registry↔ROM by `JumpTableResolutionTest`, registry↔`JVM.java` for
the float group by `SoftFloatLibraryTest`. **`JopInstr.java` is checked against
nothing.** The registry is a hand-maintained subset of it. The assertion that
would have caught `frem` in August, and still catches `l2f`, `f2l` and `d2f`, is:
*every `IMP_JAVA` opcode whose `JVM.java` body reads a `Const.SUPPORT_*` flag
must be registered in a group that keeps that flag true.*

The one existing cross-check for the JopInstr side, `JopInstr.java:476-503`, is
commented out **and** logically dead: it tests `if (staticInfo == IMP_JAVA) { if
(!JopInstr.isInJava(i)) ... }`, and `isInJava` is defined as
`imp(opcode) == IMP_JAVA`, so the inner branch is unreachable.

<a id="item-124"></a>

### Item 124 — on Altera the microcode comes from the `.mif`, and one call site decides which variant

**Boundary review B3 (item 110), 2026-08-31.**

**The `.dat` files are not in the bitstream on any Altera board.**
`Parts.scala:45,51-53` give every Altera family `memoryStyle = AlteraLpm`, and
`JopCoreConfig.scala:126,143` build `AlteraLpmRom/Ram` from `"$mifBasePath/rom.mif"`
while **discarding** `initBigInt`/`initData`. Evidence:
`build/ae115fbDdr2/rtl/JopDdr2Ae115fbTop.v:16929` reads
`.LPM_FILE ("../../../build/microcode/serial/rom.mif")`.

**Exactly one call site sets the variant** — `JopTopVerilog.scala:374-375`,
`MifPathOverride(config, … MicrocodePaths.dir(bootMode))`. That is the fix for
the `ep4cgx150BramGc` failure. `JopTopVerilog.generate` has three callers;
the other two (`AlteraUtilSweep.scala:125`, `UtilSweep.scala:100`) do not apply
it and survive on their bases' boot modes.

**The class default is wrong in two ways.**
`JopCoreConfig.scala:121` defaults `mifBasePath = "../../build/microcode/serial"`
— the wrong VARIANT for a Simulation-mode design, and the wrong DEPTH (two
levels, where a generated project sits at `build/<cfg>/quartus`, three).

**Two comments in the tree contradict each other about whether that matters.**
`QuartusProject.scala:158-160` says SEARCH_PATH lets Quartus find the mif by
name from wherever the project is; `JopConfig.scala:277-278` says SEARCH_PATH
does not rescue it because Quartus resolves the literal. If the first is right,
a wrong path resolves silently to the **serial** mif — the known failure, with
no `Error (127001)` to notice. **This is settled by one Quartus run, not by
reading**, and it is the highest-value thing in this item.

**The record omits the deciding fact.** `summary.txt` prints `ROM:`/`RAM:` as
the `.dat` paths, which on Altera nothing reads, and never prints
`mifBasePath`, which decides the outcome. A mif/dat divergence would be
invisible in the file that exists to catch this class.

**Coverage: none.** No `require`, no assertion, no test relates boot mode to
the artefacts it selects. The cheapest fix closing all of it: print
`mifBasePath` into the summary and `require` that the resolved mif directory
ends in `bootMode.dirName`.

<a id="item-125"></a>

### Item 125 — `run_bench` hardcodes the baud per board and can address the wrong board

**Boundary review B4 (item 110), 2026-08-31.** `fpga/scripts/run_bench:41-44`
is the twelfth Makefile constant that never got migrated, with three defects in
four lines:

```
ae115fb)   UART=/dev/ttyUSB0; BAUD=2000000; KIND=quartus ;;
ep4cgx150) UART=/dev/ttyUSB1; BAUD=2000000; KIND=quartus ;;
wukong)    UART=$(... awk '/1a86:7523/{print $1; exit}') BAUD=2000000
```

1. **Baud is hardcoded per BOARD, but baud is per PRESET.** `wukongFull` is
   1 Mbaud and `wukongDdr3` is 2 Mbaud on the same board, so
   `run_bench wukong <wukongFull.bit>` prints garbage.
2. `/dev/ttyUSB0` and `ttyUSB1` are **port paths**, which this project's own
   tooling exists to avoid — they move on every replug.
3. The wukong branch matches VID:PID `1a86:7523`, which `usb_serial_map:11-17`
   records as shared by the Wukong **and** the A-E115FB with no serial number.
   `exit` takes whichever enumerated first, so `run_bench wukong` can drive the
   A-E115FB's console.

`run_bench` takes a bitstream path rather than a config, so as written it
cannot reach the summary. Fixing it means taking a preset or a `CFG_DIR`.

<a id="item-126"></a>

### Item 126 — the baud derivation is never exercised by its own guard

**Boundary review B4 (item 110), 2026-08-31.** Two gaps in guards added the
same day.

**The derivation has no regression test.** `check-console-baud.sh` passes
`BAUD=` and `BAUD=115200` explicitly, so
`grep -h 'UART baud' … | head -1 | awk '{print $NF}'` (`console.mk:34`) **never
runs against a real summary**. Rename the label, add a field, or change the
per-system prefix at `JopTopVerilog.scala:205` and BAUD silently returns empty
or the wrong token while the guard stays green. The `$NF`-vs-`$3` bug that
`console.mk:24-28` memorialises — a multi-system summary shifted the column and
yielded the literal string `"baud:"` — is itself untested.

**FIXED 2026-08-31: `require-port` now exists**, on `download`, `redownload`,
`reset` and `monitor`, refusing with the alias name and a pointer at
`usb_serial_map --reset`. What remains open is the first half below.

~~**There is no `require-port` beside `require-baud`.**~~ `console.mk:22` is a
`$(shell …)` that yields empty when the board is unplugged, unguarded. With
`SERIAL_PORT` empty, `console.mk:97` becomes `download.py -R  1000000`, and
`download.py:275-306` binds `pos[1]="1000000"` as the **port**. It fails loudly
— "could not open port 1000000" — but names the baud as the port, which is the
same shape the baud guard was written to remove.

**A third, smaller one:** `download.py:298` defaults to 2000000 and
`monitor.py:14` to 1000000 for the same fact. Unreachable through `console.mk`
now that both are guarded; reachable through `run_bench` ([item 125](#item-125))
and by hand.

<a id="item-127"></a>

### Item 127 — the boundary review is unfinished; five done, more named

**Filed 2026-08-31** so the scoping survives the session. [Item 110](#item-110)
reviewed five boundaries and every one produced findings — items 119 to 126.
The approach works and is not exhausted.

**Reviewed:** the object handle layout; the I/O address space; boot mode ->
microcode variant; elaboration -> host tooling; bytecode implementation across
JopInstr / BytecodeConfig / jvm.asm / JVM.java.

**PROGRESS 2026-09-01.** Two of the four ran and both produced verified
findings — **GC ↔ card-table hardware** returned [items 131](#item-131)
and [132](#item-132), and **stack cache ↔ microcode** returned
[item 133](#item-133), plus the shared root [item 130](#item-130). The other
two — **class-struct layout** and **JOPizer ↔ runtime** — were killed by an API
session limit partway through and returned nothing; they are still open and the
prompts are reusable as written.

Seven boundaries, seven with findings. The method has still not produced a clean
one.

**Named and not reviewed:**

- **The class-struct layout.** `java/tools/.../ClassStructConstants.java:37` vs
  `ConstGenerator.scala:102` — two definitions of `CLASS_HEADR`, and it is
  COUPLED to the handle layout, since `JVMHelp.java:671,677` and
  `JVM.java:816,857` compute `rdMem(ref + OFF_MTAB_ALEN) - CLASS_HEADR`. The B1
  review found it while looking at something else and did not follow it.
- **GC software <-> the card-table and zero-fill hardware.** `GC.java` reads
  `IO_CARD_SHIFT` at run time to decide `genActive`; that is the only in-band
  device probe in the tree, and the rest of the GC's hardware assumptions are
  not checked at all.
- **JOPizer's link-time layout <-> the runtime's expectations** — the static
  field area, the constant pool, `Startup`'s `<clinit>` interpreter.
- **The stack cache <-> the microcode's spill/fill sequences**, which item 14
  touches from the hardware side only.

**How to run it:** one agent per boundary, each given the crossing already found
so it does not re-derive, and each told the traps — an absence in one file is
not an absence; read whole definitions, because a grep bounded by the next
keyword runs past an inheriting preset; verify rather than assert. That prompt
shape produced findings on five boundaries out of five.

**What it cost and returned:** five agents, roughly 100k tokens each, one
session. Returned a second and third `frem` (`l2f`, `f2l`), a generated file
whose dependencies omitted every input that decides its content, a bounds check
with no test, a benchmark script that can drive the wrong board, and a defect in
a guard written the previous day.

<a id="item-128"></a>

### ~~Item 128~~ — an array-cache hit skipped the bounds check, so out-of-range READS returned adjacent memory — **FIXED 2026-09-01**

**Found 2026-08-31** by re-enabling the tests [item 119](#item-119) reported as
disabled. Hardware-confirmed on `wukongFull` (Wukong DDR3):

```
MISS: arraylength-NPE     nulla.length does not throw
MISS: iaload-upper        ia[3] on a 3-element array does not throw
      iastore-lower       throws correctly
      iastore-upper       throws correctly
```

**The bounds check is asymmetric: stores are checked, loads are not.**

**Mechanism, established from the source rather than inferred.**
`BmbMemoryController.scala:682-694` — on an `iaload` that HITS in the array
cache the controller stays in `IDLE`, sets `readArrayCache := True`, and the
output MUX at `:483` returns `arrayCache.io.dout` directly. It never enters
`HANDLE_READ`, so it never reaches `HANDLE_BOUND_READ`, so it never reads
`handle[1]`. `ArrayCache.scala` has no notion of array length at all — its only
mention of "length" is a comment about tag slicing.

`iastore` has no such path: `:761` always sets `handleIsArray` and enters
`IAST_WAIT`, so the check always runs. That is exactly the split observed.

**Consequence.** `int x = ia[n]` with `n` past the end returns whatever is in
the cached line, silently. Reads are less destructive than writes, but this is
the "permissive direction" [item 119](#item-119) predicted: `DoAll` reports
66/66 while every out-of-bounds read is an adjacent-memory access. The array
cache is present on `wukongFull` (470 `arrayCache` references in its RTL) and
is on by default.

**Diagnosis completed 2026-08-31.** A MISS is fine: `:687-694` enters
`HANDLE_READ` and checks. `useAcache = false` is fine: `:696-702` always enters
`HANDLE_READ`. Only the HIT path is defective, and it has a second half.

**The fill reads past the end of the array.** `:1092-1096` computes
`alignedIndex = (handleIndex >> fieldBits) << fieldBits` and fills a whole line
from there, bounded by the LINE, never by the length. For a 3-element array with
a 4-word line, an in-bounds `ia[0]` fills indices 0,1,2 **and 3** — so the fill
itself performs the out-of-bounds read, and caches it. The full sequence:

```
ia[0]  miss -> bound check passes (0 < 3) -> fill reads 0,1,2,3, caching 3
ia[3]  HIT  -> returns the cached word, no check, no exception
```

**Why the cheap fix is not available.** Clamping the fill to the length and
marking the trailing words invalid would fix both halves at source — but
`ArrayCache.scala:16-17` states a **single valid bit per line**, set on the
first `wrIal` and covering the whole line, explicitly unlike `ObjectCache`,
which has per-field valid bits. Partial fills would need per-element validity.

**FIXED 2026-09-01 (`4b25831`).** `ArrayCache` now carries, per line, a count of
how many of its `fieldCnt` elements are inside the array. The controller
computes it in `HANDLE_CALC` from the `handle[1]` read it has just done in
`HANDLE_BOUND_WAIT` — `min(fieldCnt, length - alignedBase)` — and `hit` requires
`idxLower < elems`. An out-of-bounds element reads as a MISS, which routes the
access to `HANDLE_BOUND_READ` and raises `EXC_AB`.

**A COUNT, not the length.** `fieldBits+1` bits per line rather than
`maxIndexBits`, and a 3-bit compare rather than 24 on the *combinational* hit
path — `io.hit` feeds the controller's state decision in the same cycle. A full
line carries `fieldCnt` and behaves exactly as before; only a line straddling
the end of an array is affected. The fill still over-reads adjacent heap; what
changed is that the over-read word can no longer be returned. Clamping the fill
at source still needs per-element valid bits, which this cache deliberately does
not have — that half is unchanged and remains acceptable, because the read is
within the heap and the word is now unreachable.

**Red before green, and shown to be red.** Three pieces, each of which fails
against the unfixed RTL:

| vehicle | before | after |
|---|---|---|
| `ArrayCacheBoundsTest` (new) — controller-level sim | ia[3] returns the planted `0xDEADDEAD`, `abFire` never asserts | `EXC_AB` raised |
| `ArrayCacheFormal` — new 11th property | assertion fails | proved |
| `jvm/Array.java` iaload-upper, live again | `MISS: iaload-upper` | `Array ok`, `DoAll` 67/67 |

Confirmed by reverting **only** the `inBounds` term and re-running: the new
formal property and both new sim assertions fail; the other nine properties and
every control pass. That is the check [item 111](#item-111) says nothing
performs — done by hand here, not by a tool.

**The control case is half the test.** `an out-of-bounds iaload that MISSES
faults` runs the same read on a COLD cache, where the miss path has always been
correct, and must pass both before and after. Without it, "no exception fired"
and "this harness cannot see an exception fire" are the same output, and a
bounds test that cannot observe a bounds fault passes against any RTL
whatsoever. The `abFire` watcher samples at every clock edge for the same
reason: on a cache hit the controller never goes busy, so a busy-gated poll
would inspect nothing.

**One formal property needed its environment extended, not its statement.** The
bounds term adds a third way to miss — the region is cached but the index is
past the end — and left unmodelled the solver satisfies `tag+index uniqueness`'s
protocol FSM by filling a SECOND line for a `(handle, tagIdx)` already present.
The controller cannot do that: a bounds miss implies an out-of-bounds access,
which raises `EXC_AB` and never reaches `AC_FILL_CMD`. Assumed explicitly, with
that reasoning recorded at the assumption. An environment gap reads exactly like
a defect until it is named.

**Verification.** `sbt test` 666/666 across 66 suites; `ArrayCacheFormal` 11/11;
`JopJvmTestsBramSim` 67/67 with `Array ok` and no `MISS: iaload-upper`.

**Hardware-validated on THREE boards, 2026-09-01.** `wukongFull` first, because
it is the board and preset the defect was confirmed on and the only place a fix
for it can be closed; then the EP4CGX150 and the Colorlight i5. Three vendors,
three toolchains (Vivado / Quartus / yosys+nextpnr) and three memory systems, so
these are not the same measurement three times — which is the point, given how
[often a validated record decays](#item-100) when one board stands in for all.

| board | preset | memory | timing (binding report) | DoAll |
|---|---|---|---|---|
| Wukong XC7A100T | `wukongFull` | DDR3, 1 Mbaud | MET, **WNS +0.235 ns** | **67/67**, `Array ok` |
| QMTECH EP4CGX150 | `ep4cgx150Serial` | SDR, 2 Mbaud | MET, setup **+0.738 ns**, hold +0.317 ns (Slow 1200mV 100C) | **67/67**, `Array ok` |
| Colorlight i5 (ECP5) | `colorlightI5Sdram` | SDRAM 8 MB, 1 Mbaud | PASS, **45.77 MHz** at 40 MHz | **67/67**, `Array ok` |

The i5 figure is the **post-route** one. nextpnr prints the post-place estimate
first — `32.28 MHz (FAIL at 40.00 MHz)` — and reading that line instead has
already invented one board failure in this project. 13,562 of 24,288
TRELLIS_COMB (55 %), so the added term did not move the i5 off its
[EBR-bound](#item-21) shape either.

The `MISS: iaload-upper` line is gone on both. `MISS: arraylength-NPE` still
prints on both, which is [item 129](#item-129) and is the point of having split
it — the two symptoms were filed as one item and only one of them moved.

**It costs slack, and the number is worth keeping.** `wukongFull` closed at
**WNS +0.235 ns**, against **+0.349 ns** for the same preset on 2026-08-31.
`io.hit` is combinational and feeds the controller's state decision in the same
cycle, so the added term is on that path; ~0.11 ns is what a 3-bit compare
across 16 lines costs there. This is the argument for the count rather than the
length made concrete — a 24-bit compare in the same place would have cost more,
and `xc7a100tDbSerial` sits at [+0.001 ns](#item-8) where it would have mattered.

**One path is untested on hardware.** `AC_FILL_CMD`/`AC_FILL_WAIT` branch on
`burstLen > 0`, and **`ep4cgx150DbFull` is the only preset that sets it**
(`burstLen = 4`). Every board validated here takes the non-burst branch. The
burst branch is correct by construction — `acFillElems` is a register the CACHE
consumes on each `wrIal`, not something the fill loop reads, and both branches
issue the same `fieldCnt` writes — but that is an argument, not a measurement,
and `ep4cgx150DbFull` is unbuildable anyway ([item 67](#item-67)).

**Test status.** `jvm/Array.java` was disabled twice over — four assertions
commented out INSIDE a class that was itself commented out of `DoAll.java:42`.
The class is back in the suite, and the `iaload-upper` assertion is live again.
One assertion is still disabled — `nulla.length` — and it is a DIFFERENT defect
with a different cause, now [item 129](#item-129) rather than a loose end of
this one. Its reporter had to be given its own `caught = false`: with the
assertion commented out it was reading the *previous* assertion's result, which
passes, and so stayed silent about an open defect. A disabled assertion that
reports nothing is how this pair stayed invisible in the first place.
<a id="item-129"></a>

### Item 129 — `arraylength` has no null check, so `null.length` reads address 1

**Split out of [item 128](#item-128) on 2026-09-01**, where it was filed as a
second symptom of one defect. It is not: the array cache had nothing to do with
it, and fixing the cache did not fix this.

Hardware-confirmed on `wukongFull`, and still reproducing in
`JopJvmTestsBramSim` after the item 128 fix:

```
Array  MISS: arraylength-NPE     nulla.length does not throw
```

**Mechanism.** `asm/src/jvm.asm:1990`:

```
arraylength:
            ldi 1
            add             // arrayref+1 (in handle)
            stmraf          // read ext. mem
```

`stmraf` is `memIn.rdf` — the PLAIN field-read path. It goes nowhere near
`HANDLE_READ`, so `BmbMemoryController`'s null check (`addrReg === 0` →
`EXC_NP`) never runs. On a null reference the microcode computes `0 + 1` and
reads **word address 1**, returning whatever is there as the array's length.

**Why this is worse than it looks.** The value returned is then used as a bound.
`for (int i = 0; i < a.length; i++)` over a null `a` does not throw — it runs
for however many iterations word 1 happens to encode, and every one of those
iterations does an `iaload` on handle 0, which *does* fault. So the observable
failure is an NPE at an unrelated place, several statements later, or a very
long loop first.

**Not yet diagnosed further.** The obvious fix is a null test in the microcode
before the `add`, but `arraylength` is on the hot path of every array loop and
the JOP idiom for a cheap null test here has not been checked. The alternative —
route `arraylength` through the handle path so the hardware check applies — costs
a state-machine trip on an operation that is currently three instructions.
**Measure before choosing**: the same reasoning that made the item 128 fix a
3-bit compare rather than a 24-bit one applies here.

**Test in place, failing.** `jvm/Array.java`'s `nulla.length` assertion is
commented out with a comment naming this item, and its reporter prints
`MISS: arraylength-NPE` on every run of `DoAll` so the gap is visible rather
than silent.


<a id="item-130"></a>

### Item 130 — `JopTop` silently overrides four `memConfig` fields the preset declares

**Found 2026-09-01**, as the shared root of two separate boundary-review
findings and of a wrong conclusion I reached earlier the same day.

`JopTop.scala:494` and `:504-515` (and again at `:761-771` for the dual-cluster
path) rewrite the per-core config after the preset has been resolved:

```scala
val burstLen = if (sys.cpuCnt > 1 && isSdr) 4 else 0
...
  burstLen = burstLen,
  stackRegionWordsPerCore = 8192,          // or: if (board.useStackCache) 8192 else 0
  useStackCache = (isDdr3 && sys.cpuCnt == 1) || (isSdr && board.useStackCache)
```

**So the preset is not what gets built.** `ep4cgx150DbFull` declares
`burstLen = 4` and `stackRegionWordsPerCore = 1024`; both are discarded. Every
preset's own `useStackCache` is discarded unconditionally.

**Verified against ELABORATED RTL, not by reading** — the BMB length port width
is `burstLengthWidth`, so it reports `burstLen` directly:

| build | `io_bmb_cmd_payload_fragment_length` | effective `burstLen` |
|---|---|---|
| `ep4cgx150Serial` | `[1:0]` | 0 |
| `wukongFull` | `[1:0]` | 0 |
| `colorlightI5Sdram` | `[1:0]` | 0 |
| `ep4cgx150Smp-8-50` | `[3:0]` | **4** |
| `ep4cgx150Smp-12-36` | `[3:0]` | **4** |

**This corrected a wrong conclusion of mine.** I had grepped `JopConfig.scala`,
found `burstLen` set on exactly one preset, and stated that the burst path was
dead on every buildable board. It is not: it is live on every SDR SMP build,
including the hardware-validated 8- and 12-core EP4CGX150s. Concluding from an
absence in one file is the exact trap the boundary-review prompts warn about,
and I walked into it while running them.

**It also breaks a harness that was fixed for precisely this.**
`JopSmpSdramSim.scala:47-58` carries a long comment explaining that the harness
used to hardcode `burstLen = 4` while "the board runs burstLen = 0", and was
corrected to derive from the preset — "TAKEN FROM THE BOARD PRESET, not
hand-rolled … cannot drift again". But `JopConfig.ep4cgx150Smp(n).system
.coreConfig` yields `burstLen = 0`, and the board builds `4`. The fix moved the
divergence rather than removing it, and the comment now asserts the opposite of
what the RTL does. **`ep4cgx150Smp` has `board.useStackCache = false`**
(`Board.scala:718` is `WukongXC7A100T`, the only board that sets it), so the
harness matches the board on that field — by luck, not by derivation.

**Consequence.** Anything reading the preset — a simulation harness, the build
summary, a test, a person — describes a different machine than the one on the
bench. `JopTopVerilog.scala:221` prints "Stack cache: on" from
`sys.coreConfig.useStackCache`, i.e. the PRESET, so the builds that actually
have it say nothing.

**Fix shape:** the override belongs in config resolution, where the preset can
see and record it, not in the top level after the fact. Until then, every one of
these four fields is a place where the preset lies.

<a id="item-131"></a>

### Item 131 — the card-table clear-all DROPS every concurrent mark, and the mutators are resumed inside the window

**Found 2026-09-01** by boundary review B7. **Verified in full**, both halves.

`CardTable.scala:107-112` — one write port, sweep wins:

```scala
val wrEn   = s1valid || io.clrEn || clrAllActive
val wrIdx  = Mux(clrAllActive, clrAllCnt, Mux(io.clrEn, io.clrIdx, s1widx))
val wrData = Mux(clrAllActive || io.clrEn, B(0, 32 bits), newWord)
mem.write(wrIdx, wrData, enable = wrEn)
```

A mark reaching stage 2 while `clrAllActive` has **both its index and its data
replaced** by the sweep's. No stall, no retry, no backpressure — the mark is
lost. `io.clrBusy := clrAllActive` exists at `:84` and **is read by nothing**
(`grep -rn clrBusy spinalhdl/src` matches only its own definition). Someone
built the signal to gate on and never wired it; that is the whole defect in one
line.

The sweep runs `cardWords32` cycles: **4096** on `ep4cgx150Serial`,
`xc7a100tDbSerial` and `wukongDdr3` (16 KB budget over 8 MB), **16384** on
`ae115fbDdr2` (64 KB over 1 GB — 218 µs at 75 MHz), 2048 on `wukongSdram` and
`colorlightI5Sdram`.

Against that, `GC.java:2230-2239`:

```java
if (cardClearEnabled) Native.wr(-1, Const.IO_CARD_CLEAR);   // starts a 4096-cycle sweep
nurseryAllocPtr = nurseryTop;  youngObjects = 0;
Native.invalidate();
...
Native.wr(0, Const.IO_GC_HALT);                             // resume the other cores
```

`Native.wr` to an I/O register never leaves `State.IDLE`, so `notBusy` stays
true and nothing waits. Eight statements — a couple of hundred cycles at most —
then the world restarts with ~3,900 cycles of sweep still to run.

**Why it is a correctness bug and not a slowdown.** `GC.java:2228` states the
safe direction: "Leaving cards dirty is always SAFE, only slower." A dropped
mark leaves the card **clean**, which is the unsafe direction. A tenure→nursery
store in that window is invisible to the next minor GC, and the canonical
generational pattern lands squarely in it: `allocGen` calls `minorGc()`, returns
the object, and the mutator's next act is usually the `putfield` that links it
into an older object. That object then has no other root → collected while live.

**It matches a mystery already recorded in the source.** `GC.java:194-196`
describes exactly this symptom — *"The card for a cross-generation store is
provably marked, and the holder provably lies in a range scanCards() visits, yet
the reference is still lost"* — and `cardClearEnabled` was added as an A/B to
test the clear as a suspect. The A/B was judged worthless because toggling it
perturbs the heap layout. **The RTL answers it without a rebuild.** Whether this
is also [item 64](#item-64)'s 0.42 bytes/round is a separate question and should
be settled by a test, not by the resemblance.

**Nothing would catch it.** `CardTableTest.scala:46-48` defines `clrAll()` as
*assert, then `waitSampling(nWords + 4)`* — the testbench deliberately waits out
the exact window the software does not, so a lossless-marking test coexists
with a lossy design. That is a test shaped around the defect.

**Fix shape:** wire `clrBusy` — either stall the mark pipeline while the sweep
runs, or have the GC poll it before releasing `IO_GC_HALT`. The second is a
software-only change and testable first. **Write the failing test first**: a
`CardTableTest` case that marks DURING `clrAll` and asserts the bit survives.

<a id="item-132"></a>

### Item 132 — the card-table read port is stolen by any write, and the invariant that makes that safe is deliberately broken elsewhere

**Found 2026-09-01** by boundary review B7. Both halves verified; the exploit
timing is inferred.

`CardTable.scala:88-91`:

```scala
// mValid, not io.markValid: ... the GC readback path is unaffected because the
// collector only reads with every core halted, so no mark can be in flight.
val readAddr = Mux(mValid, wIdx, io.rdIdx)
```

The steal is gated on `mValid` — **not on `inRange`**. Any BMB write anywhere in
memory diverts the read port for that cycle, so the collector's `IO_CARD_DATA`
read returns a different card word than it asked for.

**The stated invariant does not hold.** A core owning a lock is exempt from
`gcHalt`, by design and with a comment saying so:

- `Ihlu.scala:371` — `io.syncOut(i).halted := lockWait || (gcHaltFromOthers && !isLockOwner)`
- `CmpSync.scala:143` — `io.syncOut(i).halted := False  // Owner: exempt from everything (including gcHalt)`

The exemption is correct on its own terms — the owner must finish its critical
section or the cluster deadlocks. But it means a core CAN be issuing writes on
the bus that feeds `markValid` (`JopCluster.scala:620-621`) while the collector
reads the table. `scanCardRange` (`GC.java:1977-1980`) does one write+read pair
per table word — 4096 per minor GC on the EP4CGX150 — so with an exempt core
running, a collision over 4096 reads is near-certain. One collision skips a
whole table word: 32 cards of tenure→nursery references, silently.

Single-core is inferred safe: the collector's own writes assert `mValid` while
the core is still stalled in `WRITE_WAIT`, cycles before it can issue the next
I/O read.

**Same root, second consequence:** an exempt lock owner can also store
tenure→nursery *after* `scanCards` has read that word — a plain
stop-the-world violation independent of the RTL. `GC.java`'s
`mutatorTick`/`haltDeltaMax` instrumentation already watches for it and is the
place to look first.

**Fix shape:** gate the steal on `inRange`, not `mValid` — an out-of-range write
has no RMW to do and needs no read. That removes most collisions for one MUX
term. It does not remove the in-range case, which needs either a second read
port or the STW invariant actually enforced.

<a id="item-133"></a>

### Item 133 — the microcode was never taught the stack cache exists

**Found 2026-09-01** by boundary review B9. The single sentence that explains
the whole group: **spill/fill is entirely hardware, and no microcode sequence is
stack-cache-aware.** Unlike `lmul_sw` ([item 18](#item-18)) there is no
unselected alternate implementation here — there is no implementation at all.

Rotation is triggered only by push/pop/`stsp` (`DecodeStage.scala:388-398` sets
`selSmux` for those alone). Every other path into the stack RAM — `vp0-3`,
`vpadd`, `ar`, `dirAddr` — never triggers one. And on a miss the hardware does
not fault: `StackStage.scala:518-527` returns **0** for a non-resident read, and
`:592-602` **silently drops** a non-resident write.

The microcode and runtime do exactly that, at arbitrary depth:

- `jvm.asm:2175-2183` — `jopsys_rdint`/`jopsys_wrint` (`Native.rdIntMem`/`wrIntMem`)
- `GC.java:769` and `:1927` — the root scan walks the WHOLE stack this way
- `JVM.java:726-780` — `f_athrow` walks every frame, then WRITES a faked return frame
- `Scheduler.java:99,149` — `int2extMem`/`ext2intMem`, the RT-thread context switch

**Where it is live.** `useStackCache` is forced on by
[item 130](#item-130)'s override for **every single-core DDR3 build** —
`wukongFull`, `wukongDdr3`, `xc7a100tDbSerial`, `xc7a100tDbFull` — and for the
Wukong's SDR builds. It is **off** on the EP4CGX150 (`Board.scala` sets
`useStackCache = true` on `WukongXC7A100T` only), so the validated 4/8/12-core
EP4CGX150 results are unaffected. Latent rather than firing because ordinary
code never pushes SP past the resident window.

Other findings in the same group, each verified:

- **Cross-core GC root scan reads the 64-word SCRATCH RAM only.**
  `StackStage.scala:650-655` — the debug port is `scratchRam` and the address is
  `resize`d to 6 bits — while `GC.java:1893` scans `j` up to
  `STACK_RAM_WORDS = 256`. Indices 64-255 alias onto scratch 0-63, which holds
  the microcode's variables and constant pool. Applies to SDR SMP **with** the
  stack cache, i.e. `wukongSdrSmp(n)`, not to the EP4CGX150.
- **Spill region unchecked against the 16-bit virtual SP.**
  `stackRegionWordsPerCore = 8192` against a hardware range of 65,472; the only
  guard (`JopCoreConfig.scala:437-438`) checks `> 0`. Past SP 8256 the lowest
  core's spill address wraps to word 0 and overwrites the image header.
- **Victim bank chosen by index, not address** (`StackStage.scala:697-702`,
  `(activeBankIdx + 2) % 3`), leaving a 192-word non-resident hole between two
  resident banks after the first rotation.
- **`spOv` is dangling.** `StackStage.scala:1096` drives it; nothing in
  `JopPipeline.scala` or `JopCore.scala` reads it. `EXC_SPOV` is never raised in
  any configuration and `JVMHelp.java:110-113`'s recovery is dead code.
- **DDR2 reserves 8192 words/core for a stack cache it never enables**
  (`JopTop.scala:502-505` vs `:515`) — the A-E115FB loses 32 KB of heap for
  nothing.

**Coverage is zero.** `JopStackCacheSim` is not in CI, and it passes
`separateStackDmaBus = true` with a private 64 KB spill RAM — a topology no real
build uses (`JopCluster.scala:47` defaults it false; `JopTop` never sets it). So
"3-bank rotation verified in BRAM" was verified against an isolated, zero-based
spill memory with no arbiter contention and no address wrap — the two things the
overrun finding is about. `DeepRecursion` is excluded from `DoAll` and runs
nowhere automatically; it allocates nothing, throws nothing and uses no threads,
so it exercises none of the above.

**[Item 14](#item-14) is partly stale**: per-core spill bases landed
(`JopCoreConfig.scala:543-545`). The unbounded region SIZE is the real gap.

<a id="item-134"></a>

### Item 134 — the array-cache line fill is 0.0 % of Kfl's stall, and the benchmark that would show otherwise was never reached

**Measured 2026-09-01** on real SDR (`DoAppMemTimeSdrSim`, W9825G6JH6 at 80 MHz,
single core, `burstLen = 0` — the serial fill path):

| category | txns | stall cyc | stall % | cyc/txn |
|---|---|---|---|---|
| idle/direct | 3,166,392 | 26,269,610 | 41.9 % | 8.30 |
| statics | 2,545,277 | 25,764,278 | 41.1 % | 10.12 |
| element | 225,598 | 3,160,953 | 5.0 % | 14.01 |
| bytecode fill | 2,259 | 2,931,895 | 4.7 % | 1297.87 |
| handle deref | 225,662 | 2,512,581 | 4.0 % | 11.13 |
| bounds check | 225,608 | 2,048,757 | 3.3 % | 9.08 |
| **A$ line fill** | **244** | **2,344** | **0.0 %** | 9.61 |

**244 line fills in 203 million cycles.** Making the array fill infinitely fast
would save 0.0037 % of Kfl's stall. Neither bursting it nor pipelining it is
worth anything on this benchmark, and no design work should proceed on Kfl
evidence.

**The run did not reach Lift, which is the one that matters.** It hit the
400 M-cycle cap during UdpIp (UART tail: `Kfl 12992 1/s ..UdpIp`). Lift is
**78 % indirection** on BRAM ([item 50](#item-50)) — the only benchmark in the
suite where array and handle traffic dominates. So the honest state is: *refuted
for Kfl, unmeasured for the case that motivated the question.*

**What a fix would even be, for when that measurement exists.** Two different
things sit under "burst the array cache":

1. **Bursting** — already implemented (`AC_FILL_CMD` issues one command with
   `length = fieldCnt*4-1`) and already live on SDR SMP via
   [item 130](#item-130)'s override. Replaces 4 × latency with latency + 4:
   on this SDR roughly 34 cycles down to 12.
2. **Pipelining the non-burst fill** — `BC_FILL_LOOP` issues the next read in
   the SAME cycle its response fires; `AC_FILL_WAIT` returns to `AC_FILL_CMD`
   and issues a cycle later. The array fill therefore pays one dead cycle per
   element that the method-cache fill does not. Worth ~1 cycle in 9.6, i.e.
   ~10 % of the fill — **not** the 3x that bursting gives. The cheap fix is not
   the big one.

**And bursting is not a free performance knob** — `e252ecd` enabled it on SDR
SMP for CORRECTNESS: *"Burst reads prevent A$ interleaving corruption on SDR
SDRAM SMP. DDR3 doesn't need bursts: LruCacheCore serializes all access."* The
mechanism of that corruption is not recorded anywhere, and the DDR3 half of the
rationale is now conditional: `l2MshrCount` defaults to 1 (still serialising),
but `wukongDdr3SmpMshr`-style presets raise it, which voids the stated reason
for their own `burstLen = 0` — and those are the presets the DDR3 MSHR scaling
numbers were taken on. **Settle what the corruption actually was before turning
bursting on or off anywhere.**


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
no level shifter.** (SUPERSEDED 2026-08-29 for the EP4CGX150: the
[pico-usb-debug-jtag](https://github.com/peteryates1/pico-usb-debug-jtag)
carrier does exactly what the last paragraph here prescribes, and that board now
programs and runs from a Pico. The analysis below is why, and still applies to
any *bare* Pico.) Pin 4 of the Altera 10-pin JTAG header is VCC(TRGT); a
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
