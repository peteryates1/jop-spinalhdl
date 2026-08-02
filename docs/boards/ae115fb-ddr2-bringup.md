# A-E115FB DDR2 bring-up plan

Getting JOP running against the 1 GB DDR2 SODIMM on the A-E115FB (EP4CE115).
Board reference: [ep4ce115-ddr2-board.md](ep4ce115-ddr2-board.md).

Status: **exerciser builds, not yet run on hardware.** Items 1, 2 and 5 below
are done; the design synthesises, fits, assembles to a `.sof`, and its own logic
is off the critical path. Two things to resolve before or alongside the first
hardware run — see "Open before hardware".

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

## The half-rate question — decide before JOP integration

166 MHz is far above JOP's fmax (~80-100 MHz on faster silicon than this -7
part), so JOP cannot simply live in the phy_clk domain. Two ways out:

1. **Regenerate the IP at half rate** with Quartus 18.1 (installed for exactly
   this). `phy_clk` becomes ~83 MHz and `local_wdata`/`local_rdata` become
   **256 bits**. That suits JOP directly, gives real timing margin instead of
   +0.008 ns, and — neatly — a 256-bit local interface matches both the DDR2
   BL=4 burst (32 bytes) and the 256-bit cache line that the "Open decisions"
   section below already wanted. Three problems, one change.
2. Keep full rate and cross clock domains between JOP and phy_clk. More logic,
   no benefit to the burst/line-width mismatch.

Option 1 looks clearly better, and the exerciser is the right place to try it —
it is small, and it already proves the flow end to end.

## Work items

1. **`Parts.scala`** — add `EP4CE115F23I7`. Note the existing `CycloneIV` entry
   hardcodes `quartusFamilyName = "Cyclone IV GX"`; this board is Cyclone IV **E**,
   so the family needs splitting.
2. **`Ddr2BlackBox`** — wrap `ddr2_64bit` from `ddr2_64bit_bb.v`.
3. **`CacheToDdr2Adapter`** — `LruCacheCore` memCmd/memRsp ↔ `local_*`.
4. **DDR2 local-interface sim model** + adapter sim, analogous to the MIG model,
   so the adapter is validated before hardware.
5. **`Ddr2ExerciserTop`** — *first hardware milestone*. Model on
   `Ddr3ExerciserTop`: bring up the IP, wait for `local_init_done`, run read/write
   patterns over 1 GB. Proves BlackBox + adapter + pins + calibration in
   isolation, before JOP is in the picture.
6. **Preset + top-level** — `ae115fbDdr2` in `JopConfig` (1 GB → `addressWidth=30`,
   card table, backend fill) and a `createDdr2Path` in `JopTop`. Clock the core
   from `phy_clk`.
7. **Quartus project** `fpga/a-e115fb-ddr2/` — Makefile modelled on
   `fpga/qmtech-ep4cgx150-sdram/`, sourcing the IP `.qip` and the pin-assignment
   TCL verbatim. Select the cable with
   `jtag_probe_map --cable ae115fb`.
8. **Re-run the GC suite at 1 GB** — DoAll, GcStressTest, GcPauseTest,
   MultiArrayGcTest. This is the payoff: the first real exercise of
   `addressWidth=30` and of the O(live) major GC at a heap size where it matters.

## Open decisions

- **Cache line width.** DDR2 BL=4 on a 64-bit bus is 32 bytes = **256 bits**, but
  the current line is 128-bit, so each burst would be half wasted. `LruCacheCore`
  already elaborates at 256 — but see the caveat below before relying on it.
  Either run at 128 for bring-up and widen later, or go straight to 256.
- **PREREQUISITE for a wide line**: `LruCacheCoreUnitSim` only *passes* at
  `dataWidth=32`. Its test vectors hardcode addresses/patterns for a 32-bit line
  (at 256, `byteOffsetWidth` is 5 not 2, so eviction/write-back cases fail on
  address mapping — reads still pass). That is a harness limitation, not a known
  RTL defect, but **a 256-bit line is not functionally verified until those
  vectors are generalised.**
- **`MAX_HANDLES`** is 65536, which caps the handle table regardless of heap size.
  At 1 GB that is worth revisiting — it bounds how many live objects the heap can
  hold, independent of free space.
- **Core count.** Resource budget suggests 12 cores fit; start at 1.
