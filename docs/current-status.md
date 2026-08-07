# Where we are — 2026-08-05

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
- [architecture/software-cost-model.md](architecture/software-cost-model.md) — what operations cost on JOP, measured (method call ~142 cycles, static read ~22, microcode imul ~775)

---

## 0. Outstanding items

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
24, 25, ... 23, ... 17-22 ... 21. They are referenced from `bugs-and-issues.md`,
the GC design notes and a good many commit messages, so renumbering would
silently invalidate all of that. Use this to find one:

| # | section | # | section | # | section |
|---|---|---|---|---|---|
| 1-3 | Blocking / correctness | 11 | The measurement gap | 21 | Boards |
| 4-7, 24, 25 | Performance | 12-16, 23, 26 | Smaller | 17-20, 22 | Compute units |
| 8-10 | Hardware / infrastructure | | | | |

### Blocking / correctness

- **1.** **Generational GC is unsound on SMP — currently guarded off.** The card table
   is per core, snoops that core's own BMB port ahead of the arbiter, and
   `IO_CARD_*` is decoded per core, so the collector sees only its own table: a
   tenured->nursery write by another core is invisible and that young object is
   collected while still live. `GC.init` falls back to classic when `cpuCnt > 1`
   and says so at boot (verified on hardware). Fix is ONE cluster-level card
   table fed from the arbiter output — `CmpSync` is the precedent for a
   cluster-level resource reached through per-core I/O. **Write the failing test
   first**: the existing SMP GC tests do not construct a cross-core old->young
   reference and would pass either way. ~1-2 days, dominated by the test — and
   that test is **the same missing artefact as items 2 and 11** (see
   *Coupling* below).

- **2.** **`JopIhluGcBramSim` cannot fail.** It loads `java/apps/Small/HelloWorld.jop`
   — a single-core app — so core 1 parks in the boot-wait loop and IHLU is never
   exercised. Verified by running it to 49M cycles: core 1 never moved. Needs a
   real SMP GC application before "IHLU GC verified" means anything — the same
   application item 1 needs to build its failing test on (see *Coupling*).

- **3.** **Sixteen presets still run classic GC.** Safe but slow after the guard;
   `hasCardTable` is one line each and the boot line confirms it took effect.
   The Wukong presets have it but are **elaboration-verified only** — no Wukong
   board has been attached, so `GC: generational` is unconfirmed there.

### Performance

- **4.** **Copy phase — 79-82% of the minor pause** and the dominant remaining term.
   Latency-bound, not clock-bound: 132 cycles/handle at 75 MHz against 162 at
   100 MHz. The handle table is 2 MB against a 32 KB cache and a handle is
   exactly one 256-bit line, so ~6400 compulsory misses to find ~66 survivors.
   Plan in [gc/copy-phase-redesign.md](gc/copy-phase-redesign.md). The 5-8x
   estimate is **asserted from transaction counts, not measured**.

- **5.** **The BMB arbiter caps SMP at 2 cores @ 100 MHz.** Path is
   `coreX zeroCur -> arbiter -> coreY memCtrl state machine`, widening with core
   count. Measured on EP4CGX150: 1 core 7,870 LE (~107 MHz), 2 cores 19,439 LE
   (+0.270 ns at 100 MHz), 4 cores 38,372 LE (**65.3 MHz**). Area allows ~12
   cores at 73% with full caches; BRAM never binds (~52% at 16). Pipelining the
   arbiter costs a cycle on every memory access — see item 11 before committing.

- **6.** ~~**Major GC constant unexplained**~~ — **LARGELY FIXED 2026-08-06, 2.6-3.2x.**
   At 36k live objects the pause went **2214.9 -> 865.6 ms (EP4CGX150)** and
   **-> 689.8 ms (XC7A100T DDR3)**, from three changes: an `imul` in `push()`
   (bug 29), hoisting `push()`'s loop-invariant statics, and replacing sliding
   compaction with **evacuation**, which removes the O(n log n) address sort
   entirely (`passes 0`). `GcPauseTest`'s explicit `GC.gc()` went 161 -> 12.4 ms.
   Validated on both boards; minor GC unchanged at 1344 ns/handle.
   Detail: [gc/major-gc-evacuation.md](gc/major-gc-evacuation.md).

   **Now mark is 64% of what remains** (432-556 ms), and `push()` is 78% of
   mark. The one lever left there is inlining `push` into `mark`'s two loops to
   save its ~142-cycle call — worth ~102 ms, against duplicating GC logic in the
   most safety-critical loop in the collector. Not obviously worth it.

- **24.** ~~**The evacuation trade is untested at larger object sizes**~~ —
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

- **25.** ~~**Two loose ends from the GC work**~~ — **DONE 2026-08-06.**
    `GC_SORT_TRACE` and `GC_MARK_TRACE` now default `false`; having them on cost
    6.4 ms of the 865.6 ms pause, which matches the estimate made when they were
    added. `prepareCompact` is now documented as **deliberately frozen** rather
    than merely untouched — it is the last caller of `sortListByAddress`, still
    slides to `heapStart`, and was not converted because the incremental
    collector is unexercised (item 2) and evacuation needs its destination
    reserved before the walk starts, which does not obviously survive being cut
    into `COMPACT_STEP` pieces. Its comment warns that the pause figures in
    `major-gc-evacuation.md` do not apply to that path.

- **7.** **Root-scan floor: 2.2 / 4.7 / 8.5 ms** across SDR / DDR3 / DDR2. Tracks
   memory latency, not clock (the SDR and DDR3 boards are both 100 MHz yet
   differ 2.1x), so it will grow again on slower memory.

### Hardware / infrastructure

- **8.** **XC7A100T timing margin is +0.001 ns**, with one bad run in seven during
   regression testing. A regression platform with no margin manufactures false
   failures. Re-implement for margin.

- **9.** **Pico USB-Blaster needs a level shifter** — 74LVC8T245 (or 2x 74LVC2T45)
   with `VCCB` from JTAG header pin 4. No firmware change can fix it: the clone
   drives a fixed 3.3 V into a 2.5 V bank and reads 2.5 V against an RP2040
   V_IH of ~2.15 V. Unblocks having both Altera boards connected at once. The
   pull-up fix and `jtag_pintest.c` are **uncommitted** in `~/workspaces/pico-usb-blaster`.

- **10.** **pico-usb-blaster protocol bug** — low-level shift works (IDCODE reads
    correctly), so the fault is in byte-shift-mode or response framing. Lower
    priority now the level shifter is understood as the real blocker.

### The measurement gap

- **11.** **There is no application benchmark, and four decisions rest on it:**
    whether a cycle of arbiter latency is worth 4+ cores (item 5); whether the
    caches (2,213 LE/core, 33% of a core) earn their area; whether the copy
    redesign helps real workloads (item 4); and whether the double bytecodes are
    used enough to deserve microcode at all (item 20). Currently all four are
    reasoned rather than measured. Probably the highest-leverage thing to build next —
    and items 1 and 2 need a multi-core allocating application anyway, so the
    first slice of this is already on the critical path (see *Coupling*).

### Smaller

- **12.** **`LongComputeUnitConfig` has no enable flag** for its base 64-bit ALU
    (`ladd/lsub/lneg/lcmp`), unlike `FloatComputeUnitConfig.withAdd`. Worked
    around at the `ComputeUnitTop` level (conditional instantiation), but the
    config asymmetry remains and would bite anyone relying on the `with*` flags
    alone.
- **13.** **`java/apps/Small` `make clean` deletes `HelloWorld.jop`** — `JOP_OUT`
    derives from `APP_NAME`, which defaults to HelloWorld. Cost a sim failure
    and nearly a wrong SMP result. Build HelloWorld last, or `rm -rf build`.
- **14.** **Stack cache SDRAM integration** — pre-existing; 3-bank rotation verified
    in BRAM simulation, needs per-core stack regions on SDRAM.
- **15.** **`GcPauseTest` on the Wukong boards** — never run; they have card tables now
    but no measured pause.
- **16.** ~~**Colorlight i5 SDRAM ("stage 2" of that board's bring-up — unrelated to
    the GC stages elsewhere in this document)**~~ — **DONE** (`a7fdf93`). 8 MB working on
    hardware, DoAll 66/66 at 1 Mbaud. `BmbSdramCtrlWide` added for the 32-bit
    part; `MemoryControllerFactory.createSdr` selects on `layout.dataWidth`.
    Remaining i5 work is ordinary: raise the clock above 40 MHz, and try SMP now
    that block RAM is only 21% used. See `docs/boards/colorlight-i5-bringup.md`.
- **23.** ~~**`f_multianewarray` handles exactly 2 dimensions**~~ — **FIXED
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

- **26.** ~~**Reference arrays carry no element class**~~ — **FIXED
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

    Still open, small: `f_aastore` does no covariant store check, so storing a
    `Bar` into a `Foo[]` does not throw `ArrayStoreException` — the descriptor
    needed for it now exists, so this is a short follow-up. `(Cloneable) arr`
    and `(Serializable) arr` are still rejected; arrays would have to declare
    those interfaces. And `f_checkcast`'s WCET bound (`@WCA loop <= 5`) does not
    account for the element subtype walk.


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



- **17.** **`needs*Compute` predicates understate compute-unit reachability.**
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

- **18.** **Software/microcode fallback coverage is uneven** — 18 of 32 configurable
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

- **19.** **Write the missing `_sw` microcode handlers.** Goal: a microcode fallback
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

- **20.** **Decide whether the double group gets microcode at all** — measure before
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

- **22.** ~~**Five `_sw` handlers exist but do not work**~~ — **RESOLVED**. It was two
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

- **21.** **Colorlight i5 is EBR-bound in BRAM-only builds, not logic-bound** — with
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

## 1. Two workstreams, both largely done

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

## 2. How the serial-boot handshake was fixed (history)

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

## 3. After that, in priority order

1. ~~**GC suite at 1 GB**~~ — **DONE 2026-08-03, all green.** DoAll 66/66,
   GcStressTest 537k rounds clean, MultiArrayGcTest and IntHandlerGcTest OK,
   `free 1,067,359,856 bytes`. Detail in the bring-up doc.
2. **The minor-pause bound holds on two boards and is VIOLATED on the
   A-E115FB.** All three measured with `GcPauseTest` (2026-08-04):

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

## 4. Hardware setup

| board | cable | how it is programmed |
|---|---|---|
| EP4CGX150 (SDR) | Terasic USB-Blaster (`terasic`) | `quartus_pgm -c "$(jtag_probe_map --cable terasic)"` |
| XC7A100T + DB_FPGA V5 (DDR3) | RP2040 on the DB-V5, pico-dirtyJtag | `openFPGALoader` |
| A-E115FB (1 GB DDR2) | **Terasic** — its Pico clone cannot configure | `quartus_pgm` |

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

## 5. Traps that cost real time — worth reading before debugging hardware

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

## 6. Build quick reference

```bash
# GC test apps (force clean — see the trap above)
make -C java runtime && make -C java/apps/Small clean && make -C java/apps/Small APP_NAME=GcPauseTest

# A-E115FB DDR2
cd fpga/a-e115fb-ddr2
make ip                        # regenerate the DDR2 IP (needs Quartus 18.1)
make PROJECT=jop_ddr2 all      # or PROJECT=ddr2_exerciser for the memory test
/opt/altera/18.1/quartus/bin/quartus_pgm -c "USB-Blaster [1-5]" -m JTAG -o "p;output_files/jop_ddr2.sof"

# download / monitor
python3 fpga/scripts/download.py -e <app.jop> /dev/ttyUSB0 115200
```
