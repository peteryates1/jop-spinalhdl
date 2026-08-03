# A-E115FB DDR2 bring-up plan

Getting JOP running against the 1 GB DDR2 SODIMM on the A-E115FB (EP4CE115).
Board reference: [ep4ce115-ddr2-board.md](ep4ce115-ddr2-board.md).

Status: **DDR2 WORKS ON HARDWARE (2026-08-03).** Calibration completes against
the real 1 GB SODIMM and a write/read/verify pass runs clean:

```
DDR2 i=1 s=5 w=0fff r=1000 e=0000
```

**FULL 1 GB VERIFIED.** The sweep covers the whole 25-bit space:

```
DDR2 i=1 s=5 w=1ffffff r=2000000 e=0000
```

`w=0x1FFFFFF` = 33,554,431 words written, `r=0x2000000` = 33,554,432 read back,
zero errors. Over a 130 s window: **77 complete 1 GB passes, ~154 GB of traffic,
and the only error count ever observed was 0000**, each pass using a different
seed so stale data cannot pass twice. Sustained ~1.2 GB/s combined
(write + read), which is command-rate limited — single-word commands
(`local_size=1`), no bursting.

This exercises row, bank AND rank decoding, including the `mem_cs_n` rank
boundary near the top of the space.

---

## Already de-risked

- **The DDR2 IP is already generated.** ALTMEMPHY DDR2 High Performance
  Controller v13.1 at
  `/srv/git/cycloneEthernet/A-E115FB_core_V2/E115_core_test/read_write_1G/DDR667_read_write/ddr2_64bit/`
  — including `ddr2_64bit_bb.v` (exact blackbox port list) and
  `quartus/ddr2_64bit_pin_assignments.tcl` (504 lines of DDR2 pin constraints).
  We do **not** need to regenerate IP; Quartus 18.1 is installed as a fallback
  (25.1 cannot generate Cyclone IV DDR2 IP, but compiles fine with pre-generated
  RTL).
- **A working reference design** — `ddr2_sodimm.v` / `ddr2_read_write.v` with
  `.qsf`. Device `EP4CE115F23I7`, family `Cyclone IV E`, clk `AB11`, rst_n `N21`.
- **Programming works** — pico-usb-blaster via Quartus, verified 2026-08-02
  (IDCODE `020F70DD`). See the probe map in memory / `fpga/scripts/jtag_probe_map`.
- **UART works** — the board's own CH340 (FPGA TX `H5`, RX `N1`), proven by
  loopback in commit `a32434b`. The Pico is not needed for serial here, which is
  why it could be switched to pico-usb-blaster (that firmware has no CDC).
- **`addressWidth = 30` (1 GB) already elaborates** — Stage 1 Part A
  parameterised the datapath; `Ddr3WidthElabTest` builds a 30-bit config.
- **`LruCacheCore` is already line-width parameterised** — `CacheConfig.dataWidth`
  drives every register, memory and mask. `CacheWidthElabTest` confirms clean
  elaboration at **128 / 256 / 512** bits with the fill path enabled, at a 30-bit
  address width. No RTL work needed to widen the line.
- **The GC is ready for a big heap** — a major GC is now O(live) rather than
  O(heap) and `zeroMem` chunks the hardware fill (`5e0a3a0`). At 1 GB the old
  whole-heap bulk zero would have been catastrophic.

## Local interface (from the generated IP)

| Signal | Width | Notes |
|---|---:|---|
| `local_address` | 26 | 128-bit granularity → 2^26 × 16 B = **1 GB** |
| `local_wdata` / `local_rdata` | 128 | |
| `local_be` | 16 | byte enables |
| `local_ready` | 1 | one signal for command **and** write data |
| `local_rdata_valid` | 1 | read data valid pulse |
| `local_read_req` / `local_write_req` | 1 | |
| `local_burstbegin`, `local_size` | | multi-beat bursts |
| `local_init_done` | 1 | calibration complete — boot must gate on this |
| `phy_clk` | 1 | **user logic runs on this**, ~83 MHz at DDR2-667 half-rate |

Simpler than the Xilinx MIG: single `local_ready` (MIG splits command and
write-data channels), `local_wdata` accepted on the same cycle as
`local_write_req` (no `app_wdf_*` handshake), and a single clock domain.

## Done so far

- `Parts.scala`: added `EP4CE115F23I7` and a **Cyclone IV E** family (the
  existing `CycloneIV` hardcodes "Cyclone IV GX", which is a different Quartus
  family). Noted in passing that `EP4CE6E22C8` looks mis-assigned to GX as well —
  left alone rather than changed blind.
- `jop.ddr2.Ddr2BlackBox` — ports transcribed from `ddr2_64bit_bb.v`.
- `jop.system.Ddr2ExerciserTop` — drives the local interface directly, with an
  address-derived pattern, UART reporting and LED status.
- `fpga/a-e115fb-ddr2/` — Quartus project. The vendor IP is **not** vendored into
  the repo (~3.7 MB, vendor licence, not regenerable after 18.1); `make ip` links
  it from `DDR2_IP_DIR`.
- Build result: **6,534 LEs (6%)**, 118 pins, `.sof` produced with Quartus 18.1.

Three things the vendor reference taught us that were not in the plan:
- `mem_addr[10]` sits on **K22, the nCEO configuration pin** — needs
  `CYCLONEII_RESERVE_NCEO_AFTER_CONFIGURATION "USE AS REGULAR IO"`.
- `clk`, `rst_n` and the LEDs are in banks shared with DDR2, so they must use the
  **1.8 V** I/O standard, not 3.3-V LVTTL.
- The UART pins (H5/N1) are in banks 1/2, which are *not* DDR2 banks, so they can
  and should be explicitly 3.3-V LVTTL — left unconstrained they defaulted to
  2.5 V, marginal into the CH340.

## Timing: closed, but only just

**`phy_clk` is 166 MHz, not the ~83 MHz the plan assumed.** The IP was generated
with `local_if_drate = Full` and `mem_if_clk = 166 MHz`, so the local interface
carries 128 bits per memory clock. Confirmed from the timing report, not
inferred: `clk` base 40.000 ns (25 MHz), `pll1|clk[1..4]` 6.021 ns (166.0 MHz).

The SDC turned out to be **fine**, contrary to a first reading. The real
constraints come from `ddr2_64bit_phy_ddr_timing.sdc`, pulled in by
`ddr2_64bit_phy.qip`, and they do `create_clock` on the 25 MHz reference plus
`derive_pll_clocks`. The inapplicable `ddr2_64bit_example_top.sdc` contains only
three `set_false_path`s to ports we do not have — noisy warnings, nothing more.
`ddr2_exerciser.sdc` was added for our own pins (reset, UART, LEDs) so they stop
appearing as unconstrained.

Getting to closure, worst setup slack at Slow 1200mV 100C:

| step | slack |
|---|---|
| first build | -2.151 ns (our 128-bit read compare) |
| pipeline the compare | -0.329 ns |
| split compare from the error-counter increment | -0.439 ns (now inside the IP) |
| project SDC | -0.079 ns |
| `SEED 3` + `HIGH PERFORMANCE EFFORT` | **+0.008 ns** |

Our logic is off the critical path; the residual was always inside
`ddr2_64bit_controller_phy`. **+0.008 ns is a seed-dependent close, not real
margin** — treat it as "will probably work on the bench", not as timing closure
you can build on.

## JOP on DDR2 — builds and runs, serial handshake not yet working

`make PROJECT=jop_ddr2 all` builds `JopDdr2Ae115fbTop`:
**30,765 LE (27%)**, 585 Kbit BRAM (15%), worst setup slack **+0.123 ns**.
It programs, and the design comes up and drives the UART. It does NOT yet
complete the serial-boot handshake.

Three real problems were found and fixed getting this far:

1. **Reset must be gated on `local_init_done`, not just `reset_phy_clk_n`.**
   The latter deasserts as soon as phy_clk is stable, but the memory is unusable
   until calibration finishes, so JOP started executing against uncalibrated
   DRAM. The MIG needs no equivalent because `ui_clk_sync_rst` already spans
   calibration.
2. **83 MHz was unachievable.** `LruCacheCore` missed by -1.053 ns
   (`pendingIndex -> compVictimIsDirty`) on this -7 part. The IP was regenerated
   at a 150 MHz memory clock, so phy_clk is 75 MHz — that fixes every path at
   once, and DDR2 bandwidth is not the constraint (the exerciser was
   command-rate limited at 1.2 GB/s, not clock limited).
3. **UART baud quantisation.** `UartCtrl` divides by `baud x 5 samples`, so at
   75 MHz a 2 Mbaud request gives a divider of 7.5 -> 7 = 2.143 Mbaud, **+7%**,
   far outside UART tolerance — the line was unframeable at every host rate. At
   115200 the divider is 130 (+0.16%) and the stream decodes cleanly. Small
   dividers quantise badly; keep the divider comfortably large.

**Where it stops**: the design emits a clean, correctly framed, continuous
stream of `0x4D` instead of the `0xAA` ready byte that `download.py` waits for.
Framing is right (57,856 identical bytes, no aliasing), so this is content, not
baud. `0x4D` is `'M'`, which appears in `Startup.java:221` (`"MHz, "` in the boot
banner) — but a banner would produce several distinct characters, so a single
repeating byte is more consistent with the TX register being re-sent than with
JOP printing.

Worth checking next:
- Whether the boot path reaches the ready state at all. `Startup` probes memory
  size by writing `0xaaaa5555` and reading it back (`Startup.java:161`); on a
  1 GB space backed by a cache that may not terminate as expected.
- Whether the ROM/RAM images built for this preset are the serial-boot pair.
- **Control that still passes**: reprogramming `ddr2_exerciser.sof` prints
  `i=1 ... e=0000` cleanly, so the board, the DDR2 interface and the CH340 path
  are all healthy — the problem is confined to the JOP design.

## Still to prove

- The GC suite at 1 GB, which is the actual point of the exercise. That needs
  `CacheToDdr2Adapter` + the JOP preset (items 3, 4, 6 below).
- Bursting. At `local_size=1` the interface is command-rate limited to ~1.2 GB/s
  against a ~5.3 GB/s DDR2-667 peak. A 256-bit local word already equals the
  BL=4 burst, so multi-word bursts are the next bandwidth lever if the cache
  path needs it.

**Gotcha found while scaling the sweep**: the periodic status line reset the test
state machine when it fired, which was invisible at 4096 words (a pass finished
between ticks) but restarts a 1 GB pass forever. Only a send triggered *by*
completion may advance the pass — see `sendFromReport`.

## Getting it programmed — what actually mattered

Two days of the bring-up went on programming rather than DDR2, so:

- **The pico-usb-blaster clone never successfully configured this board.** It
  reads IDCODE reliably but a 3.5 MB configuration stream ends with CONF_DONE
  low. The Terasic cable + `quartus_pgm` worked first time. Suspect the flying
  leads from the Pico to the JTAG header.
- **A ghost USB device wasted most of the effort.** The VM held stale
  passthrough entries: a disconnected board still appeared in `lsusb`/sysfs, and
  writes to it "succeeded" at a plausible rate while reaching nothing. Every
  firmware theory (TCK too fast, FIFO desync, stale binary) was chasing that
  artefact. **Tell: transfers succeed but produce no observable effect on the
  target, and no firmware change alters the throughput.** Disconnect and replug
  everything when that pattern appears.
- **Tools that pick the first matching VID:PID are a trap.** A genuine Altera
  cable and the clone are both `09fb:6001`. `openFPGALoader` ignores
  `--busdev-num` on both its dirtyJtag *and* usb-blaster backends, and
  `program_fpga` used `libusb_open_device_with_vid_pid()`. `quartus_pgm -c
  "<cable name>"` is the one that selects correctly.
- **The LEDs lied.** The board auto-loads a factory EPCS demo at power-up, which
  blinks LED0 and lights the rest — identical to what a running design looks
  like. LED0 blinked with the FPGA unconfigured, which is what exposed it. Use
  the UART, not the LEDs.

## Preset and top-level: done (elaboration)

`JopConfig.ae115fbDdr2` → `JopDdr2Ae115fbTop`. The DDR2 path follows the DDR3
structure in `JopTop`: the controller sits outside the main clocking area and
the whole system runs in the clock it produces. Two differences from DDR3:

- **No board PLL.** ALTMEMPHY contains its own, takes the 25 MHz oscillator as
  its reference, and sources `phy_clk` (83 MHz half-rate) for everything. The
  board declares no `pllType`, and `Pll.create` throws if called — so the DDR2
  path skips it.
- **Reset is active-LOW and asynchronous** (`reset_phy_clk_n`), where the MIG's
  `ui_clk_sync_rst` is synchronous active-HIGH.

Also needed, and easy to miss because each fails in a different place:
`MemoryDevice.byName` must know the part (otherwise `resolveMemory` returns None
and the board silently looks like BRAM); the entity-name derivation needs a DDR2
branch; and the device-derived `addressWidth` override in `JopTop` applied only
to DDR3, so the adapter came out 24-bit instead of 30-bit.

Top-level ports match `Ddr2ExerciserTop`, so `ddr2_exerciser.qsf`'s pin
assignments carry over to the JOP build.

## Adapter: done (simulation)

`jop.ddr2.CacheToDdr2Adapter` bridges `LruCacheCore`'s memCmd/memRsp to the
local interface. Two asymmetries with the MIG that are easy to get wrong:

- **Mask polarity inverts.** The cache's `mask` is a KEEP mask (1 = leave this
  byte alone), which happens to match MIG's `app_wdf_mask` so that adapter
  passes it through. DDR2's `local_be` is a byte ENABLE, so `local_be := ~mask`.
- **Reads cannot be back-pressured.** `local_rdata_valid` arrives when the
  controller decides and cannot be stalled, but the cache's memRsp is a Stream
  that can. Responses therefore land in a FIFO and reads in flight are capped at
  the space available — otherwise a slow consumer silently loses returned data.

`CacheToDdr2AdapterSim` drives it against a behavioural model with random
`local_ready` back-pressure, 3-9 cycle read latency and a stalling consumer:
201 writes, 200 reads, **0 mismatches**, and the masked write confirms the byte
enables. Not yet exercised on hardware.

**Testbench gotcha worth remembering**: the model first randomised `local_ready`
and then used that new value to decide whether the DUT's current command had
been accepted — but the DUT saw the PREVIOUS value. The model and DUT then
disagreed about which commands were taken, producing duplicated reads that
looked exactly like an RTL ordering bug (`got[n] == want[n-1]`). Always decide
acceptance from the signal value the DUT actually saw at that edge.

## Half rate: done

The IP is now regenerated at half rate, and it resolved all three problems at
once:

| | full rate | half rate |
|---|---|---|
| `phy_clk` | 166.09 MHz | **83.04 MHz** |
| `local_wdata`/`local_rdata` | 128 bits | **256 bits** |
| `local_be` | 16 | 32 |
| `local_address` | 26 bits | **25 bits** (2^25 x 32 B = 1 GB) |
| worst setup slack | +0.008 ns, seed-dependent | **+1.086 ns** |
| logic | 6,517 LE | 7,430 LE (6%) |

83 MHz is within reach of JOP, and a 256-bit local word equals the DDR2 BL=4
burst (32 bytes) — so the "cache line width" open decision below answers itself:
go straight to a 256-bit line.

### How the IP is regenerated

`make ip` rebuilds it from `ip-src/ddr2_64bit.v`, a ~30 KB variation file under
version control; the ~3.7 MB of generated Verilog is derived and is not. Needs
Quartus **18.1** — Intel dropped Cyclone IV DDR2 ALTMEMPHY after that, which is
why 18.1 is installed.

Getting `qmegawiz` to regenerate headlessly took three attempts, worth recording:

- `qmegawiz -silent -xmlin ddr2_64bit.xml` fails with *"No launch command line
  found for megafunction wizard plug-in Uninstalled/Unknown MegaWizard"*, and
  consumes the XML. Editing the XML's `version=` does not help.
- The wizard is found by the name in the **first line of the `.v`**:
  `// megafunction wizard: %DDR2 High Performance Controller v13.1%`. The
  installed plug-in registers aliases for **v18.1 / v18.0 / v9.0** only
  (`/opt/altera/18.1/ip/altera/ddr2_high_perf/lib/*.lst`), so v13.1 is not
  matched — bump it to v18.1.
- Parameters come from the **"Retrieval info" comments in the `.v`**, not from
  the XML. `local_if_drate` is set there; the wizard recomputes the derived
  values (`local_if_dwidth_label` 128 -> 256, `local_if_clk_mhz_label` 166 -> 83).
- `qmegawiz` generates against the project's family/device, so it needs a
  minimal `.qpf`/`.qsf` in the working directory. The Makefile writes them.

## Work items

1. **`Parts.scala`** — add `EP4CE115F23I7`. Note the existing `CycloneIV` entry
   hardcodes `quartusFamilyName = "Cyclone IV GX"`; this board is Cyclone IV **E**,
   so the family needs splitting.
2. **`Ddr2BlackBox`** — wrap `ddr2_64bit` from `ddr2_64bit_bb.v`.
3. **`CacheToDdr2Adapter`** — DONE (`jop.ddr2.CacheToDdr2Adapter`).
4. **DDR2 local-interface sim model** — DONE (`CacheToDdr2AdapterSim`), passes:
   200 reads, 0 mismatches, byte enables and the FIFO bound both verified.
5. **`Ddr2ExerciserTop`** — *first hardware milestone*. Model on
   `Ddr3ExerciserTop`: bring up the IP, wait for `local_init_done`, run read/write
   patterns over 1 GB. Proves BlackBox + adapter + pins + calibration in
   isolation, before JOP is in the picture.
6. **Preset + top-level** — DONE. `JopConfig.ae115fbDdr2` generates
   `JopDdr2Ae115fbTop`, with a 25-bit `local_address` (1 GB of 256-bit words)
   derived from the memory device, not hardcoded. Existing presets
   (xc7a100tDbSerial, ep4cgx150Serial, wukongDdr3) still elaborate cleanly.
7. **Quartus project** `fpga/a-e115fb-ddr2/` — Makefile modelled on
   `fpga/qmtech-ep4cgx150-sdram/`, sourcing the IP `.qip` and the pin-assignment
   TCL verbatim. Select the cable with
   `jtag_probe_map --cable ae115fb`.
8. **Re-run the GC suite at 1 GB** — DoAll, GcStressTest, GcPauseTest,
   MultiArrayGcTest. This is the payoff: the first real exercise of
   `addressWidth=30` and of the O(live) major GC at a heap size where it matters.

## Open decisions

- **Cache line width — SETTLED: use 256 bits.** DDR2 BL=4 on a 64-bit bus is 32
  bytes = 256 bits, which is also exactly the half-rate local word, so a 128-bit
  line would waste half of every burst. `LruCacheCore` elaborates at 128/256/512
  (`CacheWidthElabTest`) **and now passes 7/7 functionally at 32/128/256/512**
  (`LruCacheCoreUnitSim <width>`) — the vectors are derived from the cache
  geometry instead of hardcoded, so a 256-bit line is verified, not assumed.
- **`MAX_HANDLES`** is 65536, which caps the handle table regardless of heap size.
  At 1 GB that is worth revisiting — it bounds how many live objects the heap can
  hold, independent of free space.
- **Core count.** Resource budget suggests 12 cores fit; start at 1.
