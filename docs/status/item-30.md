# Item 30 — ~~`JopJvmTestsBramSim` — the CI baseline job — intermittently dies — FIXED~~

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 30](../current-status.md#item-30).

---

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
