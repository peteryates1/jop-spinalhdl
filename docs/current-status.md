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
2. **The minor-pause bound is VIOLATED on this board: 25.4 ms against a 20 ms
   target.** The cap works (it swept exactly `MAX_YOUNG_OBJECTS` = 9687); the
   model is wrong. `SWEEP_NS_PER_HANDLE` 1600 vs 1711 measured is fine, but
   `MINOR_FIXED_US` 4500 vs **8795** measured is not — the root scan alone is
   8.53 ms, and 75 vs 100 MHz explains only 1.33x of it, so the rest is DDR2
   latency. Holding 20 ms here needs the cap at 6548, i.e. 1.48x more frequent
   minor GCs on *every* board. **Re-measure the EP4CGX150 and XC7A100T before
   changing anything** — a single global constant cannot bound the pause across
   these three boards without taxing the fast two, so the real choice is
   per-board constants or a runtime-derived cap.
3. **Major GC constant** — 2.2 s at 36k live objects, O(live) confirmed but the
   constant is 20-25x the minor sweep's and unexplained. Next action is a
   *measurement*, not a change: time `sortUseListByAddress()` separately from the
   rest of `compactAndSweep`. Two hypotheses about this pause have already been
   wrong.
4. **Root scan** — 3.9 ms, the minor-pause floor, doesn't shrink with the object
   cap. Needed to get much below ~10 ms.

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
