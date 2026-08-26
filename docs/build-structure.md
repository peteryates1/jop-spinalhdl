# Building JOP — the structure, and how to drive it

One page on how a build is put together and what to type. For the reasoning
behind each piece see `docs/current-status.md` items 60, 77–87.

## The shape of it

```
JopConfig preset  ──►  RTL + constraints + Java + microcode  ──►  vendor tools
   (one source                                                    (one shared
    of truth)              all under build/<config>/               flow per
                                                                   toolchain)
```

Three rules the whole thing rests on:

1. **A preset is the source of truth.** Clock, cores, memory, devices, UART
   baud, bytecode implementations — all of it comes from one `JopConfig`.
2. **Everything generated lives under `build/<config>/`**, keyed by the
   *invocation*, not the entity name. `ep4cgx150Smp 4` and `ep4cgx150Smp 12 36`
   are different builds and get different directories.
3. **The flow is written once per toolchain**, not once per board.

## Where things land

```
build/<config>/
  rtl/          generated Verilog + <Top>.summary.txt   ← the build's own record
  ip/           generated PLLs
  quartus/      setup_proj.tcl, <rev>.sdc, .qsf/.qpf, output_files/<rev>.sof
  vivado/       generated .xdc, build/<Top>.bit, fit_summary.txt
  nextpnr/      .json, .config, .bit, .lpf
  java/         Const.java, runtime classes, app .jop images
build/microcode/<serial|flash|simulation>/    shared, keyed by BOOT MODE only
```

`<config>` is the preset plus sanitised arguments: `ep4cgx150Serial`,
`ep4cgx150Smp-12-36-mcache14_5`, `wukongSmp-4`. Ask for it rather than spelling
it out — `sbt "runMain jop.generate.BuildLayoutMain <preset> <args>"`. The
Makefiles do exactly this; a second copy of the naming rule in Make is how you
get a path silently pointing at a stale directory.

## The shared flow

| file | what it gives a board |
|---|---|
| `fpga/quartus.mk` | config dir, generate, `.sdc`/`.qsf` generation, map/fit/asm/sta, `program-sof` |
| `fpga/vivado.mk` | config dir, generate, `program-bit`, clean |
| `fpga/console.mk` | `download`, `redownload`, `reset`, `monitor`, `console-info` |
| `fpga/scripts/vivado_create_project.tcl` | project-mode project creation |
| `fpga/scripts/vivado_build_project.tcl` | project-mode synth + impl + bitstream |
| `fpga/scripts/vivado_build_nonproject.tcl` | non-project (in-process) build |

A board Makefile declares what is *specific to it* and inherits the rest:

```make
PROJECT_ROOT  = ../..
CFG          ?= ep4cgx150Serial     # which preset
REV          ?= jop_sdram           # Quartus revision name
BOARD_ALIAS   = ep4cgx150           # for jtag_probe_map  (JTAG probe, by serial)
CONSOLE_ALIAS = ep4cgx150           # for usb_serial_map  (tty, by serial)
include ../quartus.mk
```

Optional knobs, each added because one board genuinely needed it:

| knob | meaning |
|---|---|
| `GEN_MAIN` / `GEN_ARGS` | a different generator main (the exercisers are `BoardDesign`s, not presets) |
| `GEN_MAKES_PROJECT=yes` | that main already wrote `setup_proj.tcl` and the `.sdc` |
| `CONSOLE_TXONLY=yes` | UART is transmit-only; `download`/`reset` refuse instead of hanging |
| `JOP_IP_GEN_TARGET=yes` | pre-generate Vivado IP output products |

## Two things that are never constants

**The baud comes from the build, not the Makefile.** Every build writes
`build/<config>/rtl/<Top>.summary.txt`, and `console.mk` reads the rate out of
it. Twelve board Makefiles used to carry their own `BAUD_RATE`; several were
wrong, and a wrong baud reads as a *dead board*, not as a wrong number.

**Probes and serial ports are resolved by SERIAL, never by path.** `ttyUSB3` and
`ttyACM0` renumber on every replug, and more than one probe of each kind is
attached here. Use the alias:

```bash
fpga/scripts/jtag_probe_map              # list attached probes and their boards
fpga/scripts/jtag_probe_map --boards     # known aliases
fpga/scripts/usb_serial_map              # list ttys
fpga/scripts/usb_serial_map --by-id wukong
```

## Typical sessions

```bash
# Altera — build, program, load an app, watch it
cd fpga/qmtech-ep4cgx150-sdram
make all                       # microcode + generate + build
make program download monitor

make smp CORES=4               # another config of the same board
make smp CORES=12 MHZ=36
make console-info              # which tty, which baud, which image — before you wonder

# Xilinx — the Wukong drives seven configurations from one directory
cd fpga/qmtech-xc7a100t-wukong
make ddr3-build ddr3-program ddr3-download ddr3-monitor
make ddr3-smp-build DDR3_SMP_CORES=8
make jop-sdram-build           # same board, SDR memory path
make dual-build                # two independent clusters, DDR3 + SDR

# ECP5 — the only open-source toolchain here
cd fpga/colorlight-i5
make bitstream program download
```

Verify a board end to end, including timing, in one command:

```bash
fpga/scripts/hw_verify.py ep4cgx150Serial --app JvmTests/DoAll --expect-text "66/66"
```

It refuses to call a run a pass if the build missed timing. A bitstream can
print the right answer while failing setup by 2.5 ns — that is a build that
happens to work on this die at this temperature, not a build that works.

## Boards

| directory | board | toolchain | shared flow |
|---|---|---|---|
| `qmtech-ep4cgx150-sdram` | EP4CGX150 + SDR (primary) | Quartus | ✅ |
| `qmtech-ep4cgx150-bram`, `-bram-serial` | EP4CGX150, BRAM only | Quartus | ✅ |
| `qmtech-ep4cgx150-sdram-test` | SDRAM exerciser | Quartus | ✅ |
| `a-e115fb-ddr2` | EP4CE115 + 1 GB DDR2 | Quartus | ✅ |
| `cyc5000-sdram` | Trenz CYC5000 | Quartus | ✅ |
| `qmtech-xc7a100t-wukong` | Wukong XC7A100T | Vivado | ✅ |
| `qmtech-xc7a100t-dbfpga-v5` | XC7A100T + DB_FPGA V5 | Vivado | shared Tcl only |
| `alchitry-au` | Alchitry Au V2 (XC7A35T) | Vivado | shared Tcl pending |
| `alchitry-au-ddr3-test` | **same board**, DDR3 exerciser project | Vivado | shared Tcl ✅ |
| `colorlight-i5` | Colorlight i5 (ECP5) | yosys/nextpnr | n/a — sole ECP5 |
| `max1000` | Arrow MAX1000 (10M08) | Quartus | not yet — needs a `BoardDesign` |

`alchitry-au-ddr3-test` is a second *project* on the Alchitry Au, not a second
board; it even shares that directory's IP. Directory count is not board count.

## When a build behaves oddly

- **Output is garbage** — check the baud actually in the bitstream:
  `make console-info`. Do not trust a number written down anywhere else.
- **The wrong board got programmed** — two dirtyJtag probes and two FTDI
  probes are attached. `jtag_probe_map` shows which is which; on the Altera
  boards `make program` runs `--assert-device` first and fails closed.
- **A change seems to have no effect** — you may be looking at another
  configuration's directory. `make console-info` prints the paths in force.
- **Fit or timing moved a lot and you cannot say why** — do not reason about
  it. Rebuild the previous version on identical RTL and diff the fit summaries.
  That is how a 43 % area drop was shown to be real RTL improvement rather than
  a broken build script (item 86).
