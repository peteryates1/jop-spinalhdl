# Where we are — 2026-08-07

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
24, 25, ... 23, 26, 27, ... 17-22 ... 21. They are referenced from `bugs-and-issues.md`,
the GC design notes and a good many commit messages, so renumbering would
silently invalidate all of that. Use this to find one:

| # | section | # | section | # | section |
|---|---|---|---|---|---|
| 1-3 | Blocking / correctness | 11 | The measurement gap | 21 | Boards |
| 4-7, 24, 25 | Performance | 12-16, 23, 26, 27 | Smaller | 17-20, 22, 28 | Compute units |
| 8-10, 31 | Hardware / infrastructure | 29, 30 | Smaller (CI flakiness) | | |

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

- **2.** **`JopIhluGcBramSim` cannot fail.** It loads `java/apps/Small/HelloWorld.jop`
   — a single-core app — so core 1 parks in the boot-wait loop and IHLU is never
   exercised. Verified by running it to 49M cycles: core 1 never moved. Needs a
   real SMP GC application before "IHLU GC verified" means anything — the same
   application item 1 needs to build its failing test on (see *Coupling*).

- **34.** **4-CORE STATUS after the fetch-stall fixes (2026-08-13).** Two DISTINCT
   failures remain, and the fixes landed today are implicated in neither.

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

   The generational SMP guard is raised **2 -> 4** on that evidence. 8 and 16
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

- **33.** ~~**`AlteraLpm.createRam` discarded the debug stack-RAM address**~~ —
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
   **download at 1.5 Mbaud, not 2** (`ep4cgx150Serial` declares `clkFreq = 80 MHz`
   but `dram_pll.vhd` is hardwired to 60 — 2e6 x 60/80 = 1.5e6; the preset/PLL
   mismatch is a live trap worth fixing properly), and **reprogram immediately
   before each download** — the ready handshake is consumed once and the board
   then waits for data, so a download run standalone times out on `0xAA`.
   When probing the port by hand, listen for **>500 ms**: that is the ready-byte
   period, and a shorter window reads zero bytes and looks like a dead board.

- **32.** **UART data corruption on seed 871203250 — CI seed now PINNED around it.**
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

- **3.** **Sixteen presets still run classic GC.** Safe but slow after the guard;
   `hasCardTable` is one line each and the boot line confirms it took effect.
   ~~The Wukong presets are elaboration-verified only~~ — **confirmed on
   hardware 2026-08-07**: a Wukong was attached for the first time and
   `wukongFull` boots `GC: generational, 512-word cards`. The sixteen other
   presets remain unverified.

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

- **31.** **The BMB arbiter is what stops SMP scaling, on both FPGA families.**
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

    On the EP4CGX150 the worst path is
    `cores_1|memCtrl|zeroCur -> [BMB arbiter] -> cores_3|memCtrl|bcFillAddr`,
    with **zero CardTable nodes and 16 arbiter nodes** on it — so this is not
    the shared card table from item 1, and the 2-core build with identical RTL
    closes. On the Wukong at 8 cores the negative slack is spread over many
    endpoints (TNS -20.3) rather than one path, which is congestion, not a
    single fixable chain.

    **Capacity is not the limit.** 8 minimal cores fit the XC7A100T in 76% of
    its LUTs, and 4 cores on the EP4CGX150 use 25% of its LEs. The ceiling is
    entirely arbiter timing.

    Consequence today: 4-core SMP GC validation runs at 60 MHz on the
    EP4CGX150. That is enough to prove correctness but not to measure SMP
    scaling honestly — any throughput number taken there is at a handicapped
    clock. Fixing this is what unlocks the item 5 question (whether a cycle of
    arbiter latency is worth 4+ cores), and it needs a pipelined or
    tree-structured arbiter rather than the flat round-robin.

### The measurement gap

- **11.** **There is no application benchmark, and four decisions rest on it:**
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

- **29.** **`BytecodeFetchStage: JumpTable integration` is flaky in CI, and the
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
- **30.** **`JopJvmTestsBramSim` (the CI baseline job) intermittently dies at
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
- **15.** ~~**`GcPauseTest` on the Wukong boards** — never run~~ — **DONE
    2026-08-07.** Wukong XC7A100T + DDR3, `wukongFull` at 100 MHz: minor pause
    **worst 11.840 ms / mean 11.813** over 63 collections, sweep 1624
    ns/handle, copy **87%** of the pause (the other boards are 79-82%), major
    `MAJOR OK` with retained 64/64 and `corrupt 0`, free 262 MB.
    `GcMajorPauseTest` at 36k live: **681.2 ms**, the best of the four boards
    measured — sort never runs. (The CYC5000 was measured too — see item 24.)
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

- **27.** **The `aastore` type check's cost was never measured.** Item 26 added
    a covariant store check to `f_aastore`, which every reference-array store
    goes through. The common case is inlined — three reads and no call, chosen
    because a helper call is ~142 cycles — but "chosen because" is reasoning,
    not measurement, and this document's record on that is four wrong out of
    five. Nothing in the suite times array stores, so the check could be costing
    a few percent or a third and nobody would know. Needs a store-heavy
    microbenchmark, or the item 11 application benchmark, before the design is
    called settled.

- **28.** ~~**`DoAll` dies at `CollectionTest` on the Wukong**~~ — **FIXED
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

## 4. Hardware setup

| board | cable | how it is programmed |
|---|---|---|
| EP4CGX150 (SDR) | Terasic USB-Blaster (`terasic`) | `quartus_pgm -c "$(jtag_probe_map --cable terasic)"` |
| XC7A100T + DB_FPGA V5 (DDR3) | RP2040 on the DB-V5, pico-dirtyJtag | `openFPGALoader` |
| A-E115FB (1 GB DDR2) | **Terasic** — its Pico clone cannot configure | `quartus_pgm` |
| Colorlight i5 (ECP5, 8 MB SDR) | DAPLink on the ext board (`i5`) | `openFPGALoader -b colorlight_i5` — also the UART bridge |
| CYC5000 (Cyclone V, 8 MB SDR) | on-board Arrow USB Blaster TEI0050 (`cyc5000`) | `openFPGALoader -b cyc5000` on an **.rbf** — see below |

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
