# Troubleshooting

Symptom first. Find what you are seeing, work down the causes in order — they
are ordered by how often each has actually been the answer, not by how
interesting it is.

**Rewritten 2026-08-30.** The previous version had eleven entries of which five
described a build system that no longer exists: it told you to hand-edit
generated `.qsf` files, to set `clkFreq` to match `dram_pll.vhd` (backwards —
the PLL is generated *from* `clkFreq`), and to `cd` into a submodule that has
never existed at this path. A file where half the advice is wrong is worse than
a shorter file that is right, so the stale half is gone rather than patched.

## Before anything else

```bash
make check-build
```

Seconds, no toolchain. It asserts the build graph's guards: that generated
constraints regenerate, that no board shadows a shared rule, that an unknown
baud stops the flow, that no generator falls back to a named board, and that the
docs' code fences balance. Each of those guards a defect that let a build
**succeed while being wrong**, which is the failure mode this page mostly exists
for.

---

## No UART output at all

Both LEDs may be blinking. Simulation may be fine. Work down in this order.

### 1. The baud is not what you think

Most common, and the one that looks most like dead hardware. Check what the
build actually chose:

```bash
make -C fpga/<board> console-info
```

The baud comes from the build summary, not from a constant — the rate is baked
in at elaboration and each build records it. If `console-info` cannot state a
baud, the console targets now refuse to run rather than guessing; before
2026-08-30 an empty value silently became 2 Mbaud, which prints garbage at any
other rate.

A wrong baud and a board that never booted are indistinguishable at the far end
of a serial cable. Rule this out first, every time.

### 2. Stale microcode

`asm/src/jvm.asm` changed and the ROM did not. SpinalHDL embeds the microcode at
elaboration, so a stale ROM is baked into the bitstream.

```bash
ls -la asm/src/jvm.asm build/microcode/serial/mem_rom.dat
```

If `jvm.asm` is newer, rebuild — **`all`, not `serial`**, because `build.sbt`
declares all three microcode directories as Scala source roots and
`JumpTable.scala` references all three unconditionally:

```bash
make -C asm all
```

Building only `serial` leaves `FlashJumpTableData` undefined and the first sbt
invocation of a cold build fails.

### 3. `ser_rxd` is floating

The mechanism is worth knowing because nothing about it looks like a receive
problem. SpinalHDL's `UartCtrl` has break detection: when RXD is held low the RX
module asserts `rx_io_break`, which **disables TX** by discarding FIFO writes:

```verilog
if(rx_io_break) begin
  io_write_throwWhen_valid = 1'b0;  // TX data silently discarded
end
```

An unassigned `ser_rxd` floats low, so the design sees a permanent break and
transmits nothing. Both LEDs still blink and simulation is still clean.

Pins are generated now, so the fix is not a `.qsf` edit — check that the board
data actually gives the design a receive pin, and that the constraints reaching
the fitter contain it:

```bash
grep ser_rxd build/<config>/quartus/*.qsf     # Altera
grep ser_rxd build/<config>/vivado/*.xdc      # Xilinx
```

If it is absent there, the board entry in `jop/config/Board.scala` is what needs
changing.

### 4. I/O addresses disagree with the `.jop`

The compiled `.jop` uses the addresses `Const.java` was generated with. If
`JopIoSpace` and that `Const.java` disagree, reads and writes land on the wrong
devices — including the UART.

```bash
sbt "runMain jop.generate.ConstGeneratorMain <preset> --write buildtree"
diff build/<config>/java/gen/com/jopdesign/sys/Const.java <the one your .jop was built with>
```

`Const.java` is per-config and generated into `build/<config>/java/gen/`.

### 5. The program was truncated

`mainMemSize` smaller than the `.jop` needs. SpinalHDL truncates BRAM content
**silently**, so the design runs and computes rubbish.

The first line of a `.jop` file is its word count; multiply by four for bytes,
and leave headroom for the heap.

---

## The build succeeded but the board does nothing

### The bitstream missed timing and nobody looked

A build can print the right thing while failing setup by several nanoseconds,
and a violated design can still compute correct results — until it does not.
Check the slack before concluding anything about the design:

```bash
# Altera
grep -i "slack" build/<config>/quartus/output_files/*.sta.rpt | head
# Xilinx
grep -iE "Timing:|WNS" build/<config>/vivado/build/fit_summary.txt
```

`fpga/scripts/hw_verify.py` refuses a bitstream that missed timing, which is
there precisely because a hardware "pass" was once recorded on a design sitting
−2.544 ns over setup.

Check **which corner** the number came from, too: Quartus reports Slow 100C by
default, and a design can be negative there and fine elsewhere, or the reverse.

### `grep ERROR` on a Vivado log always matches

`fpga/scripts/vivado_build_nonproject.tcl` echoes its own source into the build
log, including the lines that WOULD print an error:

```
#     puts "ERROR: $name must be set (vivado_build_nonproject.tcl)"
#     puts "ERROR: IP not found: $f"
```

So an unanchored `grep -E "ERROR:"` reports three hits on a perfectly clean
build, and a waiter watching for that pattern fires during synthesis. Anchor it:

```bash
grep -cE "^ERROR:" build/vivado-logs/vivado.log      # 0 on a clean build
grep -E  "^  Timing: " build/<config>/vivado/build/fit_summary.txt
```

### You programmed a different board

`quartus_pgm` **exits 0 on a broken chain** — a powered-off board, an unplugged
header and a level shifter with no VTREF all "program successfully". More than
one blaster is usually attached to this host, so a bare cable name takes
whichever enumerated first.

```bash
fpga/scripts/jtag_probe_map              # what is attached, by serial
make -C fpga/<board> assert-device       # refuses unless it is the right FPGA
```

Every `program-sof` depends on `assert-device` for this reason. Never conclude a
board is dead from a programming step that reported success.

### The download had nothing listening

Most boards must be **reprogrammed before each download** — the loader waits for
a ready signal that only appears after configuration. `FPGA not responding (no
ready signal)` from a board that is visibly running its previous program means
exactly this, not a fault.

---

## Serial port problems that are not the board

A USB-serial adapter can stall its control endpoint: the tty still exists, every
open fails with `EIO`, and it looks precisely like dead hardware while JTAG works
perfectly.

```bash
fpga/scripts/usb_serial_map                 # ttys by serial number, not by path
fpga/scripts/usb_serial_map --reset <alias> # USB-level reset; the tty name changes
```

Identify adapters and probes by **serial**, never by `/dev/ttyUSB<n>` or port
path — both move on every replug.

---

## Simulate before debugging hardware

```bash
sbt "Test / runMain jop.system.JopCoreBramSim"
```

If simulation fails too, it is a software or configuration problem and the board
is not involved. If simulation passes and hardware does not, the difference is
timing, pins, clocking or the program image — in roughly that order.

---

## What is deliberately not here

Things this file used to say, removed because the tree changed and a wrong
instruction costs more than a missing one:

- **Add the LPM VHDL wrappers to your QSF.** `QuartusProject` emits those
  assignments itself, with the right relative path and the right keyword by file
  extension.
- **Copy `.bin` files into the Quartus project directory.** Generated flows
  place them; nothing is copied by hand.
- **Set `clkFreq` to match `dram_pll.vhd`.** Exactly backwards since
  `DramPllGen`: the PLL is generated from the preset. Believing the old note hid
  three builds sitting 2.5–3.7 ns over timing for five months.
- **Add `FITTER_EFFORT` to the QSF.** The `.qsf` is generated; a setting worth
  having belongs in `QuartusProject`, not in a file that is overwritten.
- **Use the submodule copy, not the standalone one.** There is one repository
  and no `.gitmodules`.
