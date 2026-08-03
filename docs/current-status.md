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
| JOP building on the board | ✅ 27% LE, +0.123 ns slack, programs |
| **JOP serial handshake** | ❌ **the one open blocker** |

## 2. The immediate next task

**JOP on the A-E115FB emits a continuous `0x4D` instead of the `0xAA` ready byte
that `download.py` waits for.** Framing is correct (57,856 identical bytes, no
aliasing), so this is content, not baud.

Two leads, both untested, in the bring-up doc:
- Does `Startup`'s memory-size probe terminate on a 1 GB cached space? It writes
  `0xaaaa5555` and reads it back (`Startup.java:161`).
- Is the ROM/RAM pair built for this preset the serial-boot one?

**The control that still passes**: reprogramming `ddr2_exerciser.sof` prints
`i=1 … e=0000` cleanly. Board, DDR2 and the CH340 path are healthy — the problem
is confined to the JOP design. Keep using that control; it has repeatedly
separated "board broken" from "our design broken".

## 3. After that, in priority order

1. **GC suite at 1 GB** — the actual point of the DDR2 exercise. DoAll,
   GcStressTest, GcPauseTest, MultiArrayGcTest, IntHandlerGcTest.
2. **Re-measure the pause constants on this board.** `SWEEP_NS_PER_HANDLE` and
   `MINOR_FIXED_US` in `GC.java` were measured at 100 MHz on different silicon.
   Different hardware silently invalidates the pause bound rather than failing
   loudly — re-measure with `GcPauseTest`.
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
