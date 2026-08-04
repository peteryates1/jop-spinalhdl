# Where we are — 2026-08-03

Resumption notes covering the GC work, the A-E115FB DDR2 bring-up, and the
board/probe setup. Written to be read cold.

Detail lives in:
- [gc/stage3-followups.md](gc/stage3-followups.md) — GC items, each with its next action
- [boards/ae115fb-ddr2-bringup.md](boards/ae115fb-ddr2-bringup.md) — DDR2, including everything that went wrong
- [bugs-and-issues.md](bugs-and-issues.md) — the defects fixed along the way

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
| **Download > 32 KB** | ❌ **new blocker: first dirty cache eviction deadlocks** |

## 2. The immediate next task

**The `0x4D` blocker is diagnosed and fixed in microcode; it needs a hardware
run to confirm.**

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

**Confirmed on ALL THREE boards (2026-08-03/04).** The microcode is shared, so
every board was re-run after the change; each emits `0xAA` at a measured ~0.5 s
cadence and completes the handshake:

| board | clock / baud | ready byte | download | JVM suite |
|---|---|---|---|---|
| A-E115FB (DDR2) | 75 MHz / 1 Mbaud | ✅ 0.51 s | ✅ 0.5 s @ 88 KB/s | ✅ 66/66 |
| XC7A100T (DDR3) | 100 MHz / 2 Mbaud | ✅ ~0.5 s | ✅ 215 KB/s | — |
| EP4CGX150 (SDR) | 100 MHz / 2 Mbaud | ✅ ~0.5 s | ✅ 188 KB/s | ✅ 66/66 |

All three return identical checksums for the same image (`0x8f197bc7` for
HelloWorld, `0x2ed0b59a` for DoAll), so the transfers are byte-identical across
boards. The old instruction-count loop left only a ~9 us idle gap at 2 Mbaud —
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
3. **Major GC constant** — 2.2 s at 36k live objects, O(live) confirmed but the
   constant is 20-25x the minor sweep's and unexplained. Next action is a
   *measurement*, not a change: time `sortUseListByAddress()` separately from the
   rest of `compactAndSweep`. Two hypotheses about this pause have already been
   wrong.
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
- **`GC.wrIntG` prints only the low 5 digits.** It starts at `if (v >= 10000)`,
  so any value >= 100000 is silently truncated — which on a 1 GB board is every
  heap figure it prints. The `[carve ...]` line looked like a ~500 KB heap on a
  1 GB board; the real values were `hStart=535768`, `hSize=267891496`,
  `nSize=1048576` (exactly `NURSERY_MAX_WORDS`), all reconciling perfectly.
  **Nothing was wrong.** Reconstruct the full number before believing a GC trace
  on a large heap, or fix the printer.
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
