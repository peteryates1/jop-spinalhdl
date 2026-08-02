# Where we are — 2026-08-02

Resumption notes covering the GC work, the board/probe setup, and the A-E115FB
DDR2 bring-up. Written to be read cold.

Detail lives in:
- [gc/stage3-followups.md](gc/stage3-followups.md) — open GC items, each with its next action
- [boards/ae115fb-ddr2-bringup.md](boards/ae115fb-ddr2-bringup.md) — DDR2 plan
- [bugs-and-issues.md](bugs-and-issues.md) — the defects fixed along the way

---

## 1. Headline

Generational GC is on by default and hardware-validated on both boards. The
minor pause is now **bounded** — that was the real goal, more than the absolute
numbers.

| | session start | now |
|---|---|---|
| EP4CGX150 SDR minor pause | 30.54 ms, **growing** | **11.94 ms**, flat |
| XC7A100T DDR3 minor pause | 95.92 ms, **growing** | **19.27 ms**, capped at 20 ms target |

"Growing" is the important word: the pause used to increase with the tenured
live set, so no nursery size could bound it. It no longer does.

**The big open problem is the MAJOR GC: 2.2 s at 36k live objects.** It is
O(live) as intended, but the constant is 20-25x the minor sweep's and is not
explained. Rare and unbounded is exactly what a real-time system cannot have.

## 2. What changed (14 commits, `dbfb146`..`fdf0cae`)

**Correctness — five defects, four of them pre-existing:**
- `multianewarray` gave the inner arrays of any `Object[][]` `OFF_TYPE = IS_OBJ`,
  so the collector never traced their elements and anything reachable only
  through them was **collected while still live**. This is `JVMHelp.ih`, the
  interrupt-handler table. (`78cc968`)
- `sortListByAddress` was an insertion sort, O(n²) — a major GC on the 256 MB
  board appeared to hang. Now a merge sort. (`5e0a3a0`)
- `gc()` bulk-zeroed the whole free region: 254 MB per collection on the 256 MB
  board, which never completed. Removed as redundant. (`5e0a3a0`)
- A single whole-heap hardware fill request ran away and overwrote memory;
  `zeroMem` now issues bounded 4 MB chunks. (`5e0a3a0`)
- The generational sweep rejected legal **zero-size** objects (a class with no
  fields), turning each into an unpromoted zombie. (`78cc968`)

The first four were pre-existing and hit the **classic** collector too — a full
GC had simply never been exercised on the 256 MB board.

**Performance:**
- `youngList` — sweep only nursery handles, not the whole `useList`. This is what
  removed the growth. (`8a8e154`)
- Nursery zero removed as redundant. (`e0af9f6`)
- Sweep cost cut ~25%: hoist `freeList`/`useList` out of the loop (statics live
  in main memory on JOP, so the dead path was making six memory accesses per
  handle, not four) and splice whole runs onto the free list. (`a94e574`)
- Minor pause bounded by capping young **object count**, not nursery bytes.
  (`6cc78b2`)

**Tests added** (all in `java/apps/Small`, run via `download.py`):
`GcPauseTest`, `MultiArrayGcTest`, `IntHandlerGcTest`, `GcMajorPauseTest`.
Each was verified to **fail** when the corresponding fix is reverted — a passing
test that cannot fail proves nothing.

**Tooling:** `fpga/scripts/jtag_probe_map` resolves board → USB serial → the
selector each tool wants, because port paths move on every replug.

## 3. Current pause profile (XC7A100T DDR3)

```
minor GC, worst 19.27 ms         major GC, 36k live objects: 2231 ms
  copy/sweep  15.2 ms  (78%)       mark      916 ms  (~25 us/live handle)
  roots        3.9 ms  (20%)       compact  1311 ms  (~36 us/live handle)
  mark         0.2 ms   (1%)
  zero         0.0 ms   (0%)     minor sweep for comparison: 1.5 us/handle
  cards        0.0 ms   (0%)
```

## 4. Next steps, in priority order

1. **Major GC constant.** Next action is one cheap measurement, not a change:
   time `sortUseListByAddress()` separately from the rest of `compactAndSweep`.
   Leading hypothesis is that the merge sort dominates (545k merge steps at 36k
   handles ≈ the measured compact time exactly); if so the fix is a bucket sort
   by address, since handle addresses are dense and bounded. **Two hypotheses
   about this pause have already been wrong — measure first.**
2. **Root scan** is the minor-pause floor at 3.9 ms (20%), and does not shrink
   with the object cap. Targeting under ~10 ms means attacking the conservative
   stack + static scan.
3. **A-E115FB DDR2 bring-up** — first milestone is a `Ddr2ExerciserTop`, not full
   JOP. Most risk is already retired: the ALTMEMPHY IP is pre-generated with its
   blackbox port list and a 504-line pin TCL, programming and UART both work, and
   `addressWidth=30` already elaborates. See the bring-up doc.
4. Smaller items in the follow-ups doc: `checkcast` unimplemented for array
   types, `multianewarray` limited to 2 dimensions, the intermittent XC7A100T
   startup fault.

## 5. Hardware state

| board | probe | how it is programmed |
|---|---|---|
| EP4CGX150 (SDR) | real Altera USB-Blaster | `quartus_pgm -c "$(jtag_probe_map --cable ep4cgx150)"` |
| XC7A100T + DB_FPGA V5 (DDR3) | RP2040 on the DB-V5, pico-dirtyJtag | `openFPGALoader` |
| A-E115FB (1 GB DDR2) | Pico, pico-usb-blaster | Quartus, cable `USB Blaster [port]` |

Moving the A-E115FB to Quartus leaves only **one** DirtyJTAG, which sidesteps
openFPGALoader's inability to choose between identical probes. That returns when
the Wukong board is attached via a Pico 2 W (both it and the XC7A100T are
Xilinx, so neither can move to Quartus); the fix then is a small patch to
openFPGALoader's dirtyJtag backend to match on serial.

**Resolved:** the XC7A100T's RP2040 was cold-booting every ~30 s (`HAD_POR`,
watchdog clean). Fixed by swapping its **USB cable**. Eliminated first, by
experiment: FPGA power draw, the board power cord, the USB port, the host/hub,
autosuspend. Cable-vs-reseat is not disambiguated — refitting the original cable
would settle it.

## 6. Gotchas worth keeping

- **`make -C java all` does not reliably rebuild apps.** Force
  `make -C java runtime && make -C java/apps/<X> clean && make -C java/apps/<X> [APP_NAME=Y]`.
  Stale `.jop` files have cost hours and produced both false passes and false failures.
- **JOP keeps statics in main memory**, so `getstatic`/`putstatic` are memory
  accesses. Hoisting a static out of a hot loop is a real optimisation.
- **`OFF_TYPE` is only read by the collector**, never by `iaload`/`iastore`. An
  array can be completely broken for GC while working perfectly for the mutator —
  which is why DoAll's `MultiArray` passed throughout.
- **`checkcast` is not implemented for array types**: `(int[]) someObject` throws.
- **Don't count `dmesg` lines to judge USB stability** — the ring buffer evicts
  them and the count falsely holds constant. Watch `devnum` instead.
- The JVM tests deliberately fire hardware exceptions; judge by ok/fail text and
  `JVM exit!`, not `excFired`. Grepping for "exception" also matches the test
  *name* `HwExceptionTest`.
- `SWEEP_NS_PER_HANDLE` / `MINOR_FIXED_US` in `GC.java` are measured for **these
  two boards at 100 MHz**. Different hardware silently invalidates the pause
  bound rather than failing loudly — re-measure with `GcPauseTest`.
