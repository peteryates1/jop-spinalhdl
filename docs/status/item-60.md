# Item 60 — ~~Everything generated should live under `build/<config>/`~~ — DONE

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 60](../current-status.md#item-60).

---

> **Closed 2026-08-31.** `fpga/` went from 1,775 MB to 7 MB; no board directory
> is a build directory any more, and `4471933` records the last of it. See also
> [item 94](#item-94), which states the same completion and disagreed with this
> heading for six days.

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

**Coverage.** SUPERSEDED 2026-08-29 for the A-E115FB. Seven boards carry a probe
and console alias and can run step 5: EP4CGX150, Wukong, i5, CYC5000, Alchitry
Au, XC7A100T DB V5, and now the **A-E115FB** — it no longer shares the Terasic
with the EP4CGX150 (that board has its own level-shifted Pico) and its CH340
console is plugged in and working, so the "both cannot be attached at once"
restriction below is gone. Verified end to end on hardware the same day. Only
the **MAX1000** still takes the four-step path, for want of hardware on site.
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
  Worth fixing on its own account, and tracked as part of [item 60](#item-60)
  rather than as a separate "60a", which was never a real item number.
- **Time.** The Vivado DDR3 SMP builds run 30-60 minutes each.

**The `fpga/` directory does not disappear.** Board-specific inputs that are not
derived from the config live there legitimately: `pll_jop_i5.v`, the MIG IP, the
programming and monitor recipes.
