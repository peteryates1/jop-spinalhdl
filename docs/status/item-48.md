# Item 48 — ~~No runtime reset: the FPGA had to be reprogrammed before every download — DONE~~

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 48](../current-status.md#item-48).

---

**DONE 2026-08-18, hardware-verified on the EP4CGX150.** Programmed once, then
`HelloWorld -> reset -> CardMarkTest (CARD OK) -> HelloWorld`, with JTAG
untouched after the initial configuration.

**What the problem actually was.** Not the missing resets (item 45) -- the
missing reset SOURCE. `ResetGenerator`'s `res_cnt` is `resetKind = BOOT`, so it
initialises when the FPGA is CONFIGURED and never again, and `pll.io.areset` is
tied `False`. Reprogramming was not a habit, it was the only reset in the
design. The Xilinx boards were the accident: they already carry a `resetn` port
into the clock wizard, which is why only Altera and Lattice felt the pain.

**Two sources, because they fail differently.** `UartResetEscape` needs the
serial link alive; the button needs a human present.

| | trigger | pin cost | boards |
|---|---|---|---|
| UART escape | BREAK then `'R'` | **none** -- taps `ser_rxd` | every SDR/BRAM board |
| Button | active-low, 10 ms debounce | one input | boards mapping a SWITCH `"reset"` (EP4CGX150 `sw1`, PIN_AD24) |

Both live in the `resetKind = BOOT` domain, outside the reset they drive.

**Why a break and not a magic byte.** A break is a framing violation, so it
cannot be forged by data: at 8N1 the longest low run a valid frame can hold is
9 bit-times against a 13 bit-time threshold. `UartResetEscapeSim` proves it by
sending all 256 byte values, and back-to-back `0x00`, without a trigger. The
confirming byte then covers what a break alone does not -- a floating line is
an infinite break, bridges glitch on open/close, and a mismatched baud can read
as one.

**`pyserial.send_break()` does not work on a CP2102N.** The ioctl is accepted
and nothing reaches the wire: the FPGA's own `UartCtrl` discards TX while a
break is asserted, and a 2-second break produced no suppression at all. So
`download.py` generates the break out of ORDINARY DATA -- drop the host baud,
send `0x00`, restore it. At 1/16th baud that is 144 bit-times of low. Every
bridge can do it, because it is just a byte.

**Two of my own bugs worth recording, both of which passed simulation:**

1. The confirmation window was sized in BIT-TIMES (64, = 32 us at 2 Mbaud).
  The host cannot possibly meet that -- `send_break()` returns, then another
  syscall, then a USB frame: milliseconds. Simulation passed because the
  testbench sent the byte immediately. It is now 100 ms of wall clock, and
  `UartResetEscapeSim` has a "byte arriving LATE" case that fails without it.
2. `hasRuntimeReset` was declared BEFORE the `sys`/`isDdr3` vals it reads. A
  Scala `val` referencing a later `val` silently gets its default, so the
  feature would have been dead everywhere with no error.

**I predicted item 45 would gate this. It did not.** Bare reset from a running
application: **10/10** into the bootloader. Reset-and-redownload: **8/8**. The
intermittent hangs I saw first were my own test harness -- a 5 ms settle
between the `0x00` and restoring the baud, too short for the byte to clear the
USB buffer, so it went out truncated. At 10 ms it is reliable. The ~405
unreset registers remain a real hazard in principle and item 45 stands, but on
this board with these applications they do not stop a clean reboot.

**DDR3 done too (Wukong, same day).** It needed a different reset, not a
wider one: `sys_rst` stays tied to PLL lock so the **MIG keeps its
calibration**, and what resets is everything in the `ui_clk` domain -- core, L2
cache and `CacheToMigAdapter`, all built inside `mainArea`. Measured on the
Wukong at `Ddr3_366`: **8/8** reset-and-redownload cycles, `CardMarkTest`
running `CARD OK` afterwards, Vivado timing MET at **WNS +0.696 ns**.

**Why that is safe with the MIG still running underneath.** The MIG does not
know a reset happened, `app_rd_data_valid` cannot be back-pressured, and this
path matches responses **by position** -- the shape of both the DDR2 write-ack
bug and the AlteraSdramAdapter corruption, each of which produced wrong data
rather than a hang. Two properties make it work:

1. *Writes cannot be stranded.* `CacheToMigAdapter` gates on
  `app_rdy && (!headIsWrite || app_wdf_rdy)` and drives `app_en`,
  `app_wdf_wren` and `app_wdf_end` in the **same cycle**, so the MIG is never
  left waiting for a write burst.
2. *Reads in flight are dropped.* While the adapter is in reset its FIFOs stay
  empty, so late beats are not captured, and the hold outlasts any MIG read
  latency.

`CacheMigResetSim` tests both, and pins the second: an A/B differing **only**
in hold length shows a long hold returns correct data while a one-cycle hold
corrupts. `ResetGenerator.Ddr3ResetCycles` is therefore load-bearing, and that
test is the alarm if anyone shortens it.

**Honest limit on the evidence.** Time-to-ready after a UART reset was
0.20-0.60 s against 0.60 s after configuration, but those are multiples of the
boot loader's ~0.2 s `0xAA` retry cadence, so the measurement is too coarse to
independently prove calibration survived. The real evidence is structural and
checked in the generated Verilog: `sys_rst = !clkWizBlackBox_locked`, untouched
by the runtime reset.

**DDR2 done too (A-E115FB, 2026-08-18): 6/6.** The pattern transferred, with
one difference that matters: that domain is **ASYNC and active LOW**, not SYNC
active HIGH, so the hold is inverted and ANDed rather than ORed. The
controller's `global_reset_n` and `soft_reset_n` stay on the outer reset, so the
ALTMEMPHY keeps its calibration; the generated Verilog reads
`(reset_phy_clk_n && local_init_done) && !(hold != 0)`. Quartus 18.1 timing met
at **+0.510 ns**. The safety argument is the same as DDR3's and holds for the
same reason: `CacheToDdr2Adapter` covers command and write data with ONE
`local_ready` and samples `local_wdata` alongside `local_write_req`, so a write
cannot be left half-issued.

`Ddr3ResetCycles` is now `DramResetCycles`, shared by both DRAM paths -- 4096
cycles is ~45 us at 91.7 MHz and ~55 us at 75 MHz.

The i5 gets the UART escape but no button, because no user-button pin is
documented for that board. The Xilinx boards get no `reset_n` either: they
already carry a `resetn` into the clock wizard, which is a FULL reset
(recalibration and all) and so complements the fast core-only UART path.

**Verified on every attached board, across all three vendors and toolchains:**

| board | fabric / toolchain | memory | UART | reset-and-redownload |
|---|---|---|---|---|
| EP4CGX150 | Cyclone IV GX, Quartus | SDR | 2 M | **8/8** (+ button on SW1) |
| Wukong XC7A100T | Artix-7, Vivado | DDR3/MIG | 2 M | **6/6** (core-only) |
| Colorlight i5 | ECP5, yosys/nextpnr | SDR | 1 M | **4/4** |
| CYC5000 | Cyclone V, Quartus | SDR | 2 M | **4/4** |
| A-E115FB | Cyclone IV E, Quartus 18.1 | **DDR2** | 1 M | **6/6** (core-only) |
| XC7A100T + DB V5 | Artix-7, Vivado | DDR3/MIG | 2 M | **8/8 + 6/6** (core-only) |

That is every board, every vendor, every toolchain and every memory technology
in the project.

**One anomaly on the DB V5, recorded rather than smoothed over.** Between the
first and second runs the board went quiet: a download reported OK, produced no
output, and the next attempt got "no ready signal"; an earlier readback also
showed three corrupted bytes (`512` as `\x15\x11\x10`). Reprogramming cleared
it, after which 8/8 and then 6/6 ran clean with state verified at every step.
**The cause was not isolated.** It is plausibly the Pico's CDC bridge rather
than the FPGA -- that board's UART is a USB-CDC bridge on the dirtyJtag probe,
not a hardware UART -- but nothing here separates the two, so do not assume it.
Note also this build's timing is +0.010 ns (item 8: that board runs marginal),
which is a second candidate and equally unproven.

The i5 and CYC5000 needed no new code -- they are single-system SDR boards, so
they picked up the escape from the generic path. Both were nonetheless rebuilt
and run, because "it elaborates" is not "it works": i5 43.66 MHz PASS at 40,
CYC5000 +0.957 ns.

Usage: `make -C fpga/qmtech-ep4cgx150-sdram redownload JOP_FILE=<app>.jop`,
or `download.py -r <app>` / `-R` for reset-only. Neither the i5 nor the CYC5000
maps a SWITCH `"reset"` pin, so both are UART-only -- no `reset_n` port, no pin
cost.
