# Item 1 — ~~Generational GC is unsound on SMP — RESOLVED (2026-08-15~~

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 1](../current-status.md#item-1).

---

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
file suggests. That last question is now [item 101](#item-101) — it had sat here
as a sentence suspecting itself since 2026-08-15 without ever being acted on,
which is precisely how a validation record decays.

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
