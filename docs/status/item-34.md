# Item 34 — ~~4-CORE STATUS after the fetch-stall fixes — the SDRAM row is~~

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 34](../current-status.md#item-34).

---

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
