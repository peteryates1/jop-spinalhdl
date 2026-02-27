# A-E115FB DDR2 Board — EP4CE115 + 1GB DDR2 SODIMM

## Board Overview

Two-board system from Chinese manufacturer (A-E115FB):

**Core board (A-E115FB V2)**:
- **FPGA**: Altera Cyclone IV E — EP4CE115F23I7 (114,480 LEs, 3,888 Kbit M9K, 266 multipliers, 484-pin FBGA, industrial temp, speed grade 7)
- **Memory**: DDR2 SODIMM socket with 1GB module (Hynix HYS64T128021, DDR2-667)
- **Clock**: 27 MHz crystal oscillator (DDR2 PLL reference)
- **Config**: Active Serial (EPCS) or JTAG

**Bottom board (A-E115FB_bottom_2019)**:
- **Ethernet**: Marvell 88E1111 Gigabit PHY (10/100/1000 Mbps)
- **Audio**: Wolfson WM8731 codec
- **Display**: VGA output (ADV7123 DAC)
- **Storage**: microSD card slot
- **Serial**: CH340 USB-to-UART
- **GPIO**: Keys, LEDs, 7-segment displays

Reference files: `/srv/git/cycloneEthernet/`

## DDR2 SODIMM Interface

**Memory specifications:**
- DDR2-667 (333.5 MHz data rate, 166.75 MHz clock)
- 64-bit data bus, 8 data strobes, 8 data masks
- 14-bit address, 3-bit bank (8 banks)
- 2 chip selects, 2 CKE, 2 ODT (dual-rank SODIMM)
- I/O standard: SSTL-18 Class I (1.8V)
- FPGA I/O banks 3-6 (1.8V) for DDR2 signals

**Altera DDR2 controller IP** (`ddr2_64bit`):
- Altera ALTMEMPHY DDR2 High Performance Controller v13.1
- PLL reference: 27 MHz → generates PHY clock
- Local interface (user-facing):

| Signal | Width | Description |
|--------|------:|-------------|
| `local_address` | 26 | Memory address (128-bit granularity) |
| `local_wdata` | 128 | Write data |
| `local_rdata` | 128 | Read data |
| `local_rdata_valid` | 1 | Read data valid pulse |
| `local_read_req` | 1 | Read request |
| `local_write_req` | 1 | Write request |
| `local_burstbegin` | 1 | Burst begin marker |
| `local_ready` | 1 | Controller ready for commands |
| `local_init_done` | 1 | DDR2 calibration complete |
| `local_be` | 16 | Byte enables (128 bits / 8) |
| `local_size` | — | Burst length |
| `phy_clk` | 1 | Output clock (user logic runs on this) |

**Address space**: 2^26 × 128 bits = 2^26 × 16 bytes = **1 GB**

## Key Pin Assignments

From `ddr2_sodimm.qsf`:

| Signal | Pin | Notes |
|--------|-----|-------|
| `clk` (27 MHz) | PIN_AB11 | DDR2 PLL reference |
| `rst_n` | — | Active low reset |
| UART TX | PIN_H5 | CH340 on bottom board |
| UART RX | PIN_N1 | CH340 on bottom board |

DDR2 pins use FPGA I/O banks 3-6 at 1.8V SSTL-18. Full pin assignment
TCL script: `ddr2_64bit_pin_assignments.tcl`

## Address Mapping for JOP

The DDR2 local interface maps cleanly to the existing LruCacheCore:

```
JOP pipeline → BmbMemoryController → BMB bus → BmbCacheBridge → LruCacheCore → CacheToDdr2Adapter → DDR2
  30-bit word     (aoutAddr<<2).resized   32-bit byte    addr(29:0)→30-bit   26-bit cache    26-bit DDR2
  [29:28]=type                            [31:30]=00     strips type bits     line addr       local_address
```

| Layer | Address Width | Granularity | Range |
|-------|--------------|-------------|-------|
| JOP word address (with type bits) | 30 bits | 32-bit word | 1 GB + type space |
| JOP physical word address | 28 bits | 32-bit word | 1 GB (2^28 words) |
| BMB byte address | 30 bits | byte | 1 GB |
| Cache line address | 26 bits | 128-bit (4 words) | 1 GB (2^26 lines) |
| DDR2 `local_address` | 26 bits | 128-bit (4 words) | 1 GB |

The cache line address (26 bits) matches the DDR2 `local_address` width exactly.
Both operate at 128-bit (16-byte) granularity. No address translation needed
between cache and DDR2 controller — direct wire.

JOP configuration:
```scala
JopCoreConfig(
  memConfig = JopMemoryConfig(
    addressWidth = 30,               // 28-bit physical word + 2 type bits = 1 GB
    mainMemSize = 1024L * 1024 * 1024, // 1 GB DDR2
    burstLen = 8,                    // DDR2 burst
    stackRegionWordsPerCore = 8192   // 32 KB per core
  ),
  // ...
)
```

## FPGA Resource Budget

EP4CE115 vs EP4CGX150 (current primary platform):

| Resource | EP4CE115 | EP4CGX150 | JOP per-core |
|----------|----------|-----------|-------------|
| LEs | 114,480 | 149,760 | ~5,400 |
| Block RAM | 3,888 Kbit | 6,635 Kbit | ~28 Kbit |
| Multipliers | 266 | 360 | 1 |

Estimated JOP capacity:

| Config | LEs (est.) | % of EP4CE115 | BRAM |
|--------|-----------|---------------|------|
| 1-core + DDR2 cache | ~8,000 | 7% | ~200 Kbit |
| 4-core SMP | ~25,000 | 22% | ~320 Kbit |
| 8-core SMP | ~47,000 | 41% | ~530 Kbit |
| 12-core SMP | ~69,000 | 60% | ~740 Kbit |

12 cores is comfortably within resource limits. The main constraint is block
RAM (3,888 Kbit total) — each core uses ~28 Kbit for method cache + stack RAM,
plus the shared L2 cache. A 128 KB L2 cache would use 1,024 Kbit (26% of BRAM),
leaving room for 12+ cores.

## Cache Line Width and DDR2 Burst Alignment

DDR2 has a minimum burst length — each access transfers a fixed number of 64-bit beats:

| Burst Length | Transfer Size | Local Beats (128-bit) |
|:---:|---:|---:|
| BL4 | 4 × 64 = 256 bits (32 bytes) | 2 |
| BL8 | 8 × 64 = 512 bits (64 bytes) | 4 |

The Altera DDR2 HP controller presents a 128-bit local interface (half-rate design:
2 × 64-bit DDR2 width). Each `local_size=1` access reads one 128-bit word, but the
DDR2 must execute a full BL4 or BL8 burst internally. The extra beats are wasted.

**Current LruCacheCore uses 128-bit (4-word) lines.** This wastes DDR2 bandwidth:

| Cache Line | BL4 Efficiency | BL8 Efficiency |
|-----------|:-:|:-:|
| 128-bit (current) | 50% (128 of 256 used) | 25% (128 of 512 used) |
| 256-bit | 100% | 50% |
| 512-bit | 200% (2 bursts needed) | 100% |

**Recommendation: widen cache lines to match DDR2 burst width.**

For BL4 (256-bit lines = 8 words):
- Perfect bandwidth match — every DDR2 burst fully utilized
- `local_size=2` per cache access (two 128-bit local beats)
- Cache address width: 26 - 1 = 25 bits (each address covers 2 local words)
- 256 sets × 4 ways × 32 bytes = 32 KB (same size, half the sets)
- Doubles spatial locality (8 consecutive words per line vs 4)

For BL8 (512-bit lines = 16 words):
- Perfect bandwidth match — every DDR2 burst fully utilized
- `local_size=4` per cache access (four 128-bit local beats)
- Cache address width: 26 - 2 = 24 bits
- 256 sets × 4 ways × 64 bytes = 64 KB (or 128 sets for 32 KB)
- Quadruples spatial locality but increases eviction cost

**LruCacheCore parameterization**: The `lineWidth` (currently hardcoded at 128 bits)
should become a configurable parameter (128/256/512). The main changes:

1. **Data BRAMs** — wider read/write per line. Can use multiple 128-bit BRAMs read
   in parallel, or widen the BRAM primitive. BRAM width is flexible on Cyclone IV
   (M9K supports up to 36-bit native, but multiple M9Ks can be ganged).
2. **BmbCacheBridge** — on a 32-bit word write, only dirty the relevant 32-bit slice
   within the wider line (byte enables select sub-line position). On read, extract
   the correct 32-bit word using the sub-line offset bits from the BMB address.
3. **Tag/set geometry** — wider lines mean fewer sets at the same total cache size
   (or same sets = larger cache). Tags need fewer bits (fewer sets = fewer index bits).
4. **Eviction cost** — wider lines mean more data to write back on dirty eviction.
   A 512-bit dirty eviction writes 4× as much as 128-bit. Mitigated by the DDR2
   burst matching (one burst per eviction regardless of line width).

**Benefit to DDR3 path too**: The Artix-7 DDR3 MIG also executes BL8 internally
(128-bit port × BL8 = 1024 bits per DRAM burst, but MIG manages this). Widening
to 256-bit lines on DDR3 would halve the number of cache misses for sequential
access patterns (BC fill, memCopy, GC handle scanning) — a significant win for
GC-heavy workloads that were the original DDR3 bottleneck.

## CacheToDdr2Adapter Design

The adapter converts LruCacheCore memory commands to the Altera DDR2 local
interface. This is simpler than the Xilinx MIG adapter (`CacheToMigAdapter`)
because the Altera interface is straightforward request/response:

```
LruCacheCore                          DDR2 HP Controller
  memCmd.valid  ──────────────────►   local_write_req / local_read_req
  memCmd.addr   ──────────────────►   local_address
  memCmd.wdata  ──────────────────►   local_wdata (128-bit per beat, multi-beat for wide lines)
  memCmd.isWrite ─────────────────►   (selects write_req vs read_req)
  memRsp.valid  ◄──────────────────   local_rdata_valid
  memRsp.rdata  ◄──────────────────   local_rdata (128-bit per beat)
  memCmd.ready  ◄──────────────────   local_ready
```

Key differences from MIG adapter:
- **No app_rdy/app_wdf_rdy split**: DDR2 HP uses single `local_ready` for both
  command and data (MIG has separate command and write-data channels)
- **No write data FIFO**: DDR2 HP accepts `local_wdata` on same cycle as
  `local_write_req` (MIG requires `app_wdf_data` with separate handshake)
- **Single clock domain**: user logic runs on `phy_clk` output from DDR2 controller
  (MIG has separate `ui_clk`)
- **Multi-beat transfers**: for 256-bit or 512-bit cache lines, the adapter issues
  `local_size=2` or `local_size=4` with `local_burstbegin` and streams consecutive
  128-bit beats to/from the cache

## Existing Test Projects

Core board (`/srv/git/cycloneEthernet/A-E115FB_core_V2/E115_core_test/`):
- `DDR667_read_write/` — DDR2 read/write test (16 addresses × 4 patterns)
- `read_write_1G/` — Full 1 GB DDR2 test
- `CLK_27M_TEST/` — Clock test
- `KEY_LED/`, `LED-TEST/` — GPIO tests

Bottom board (`/srv/git/cycloneEthernet/A-E115FB_bottom_2019/.../E115_core_test/`):
- `tcp_udp_tse_test/` — Ethernet TCP/UDP with Altera TSE MAC + 88E1111
- `USB_TTL_COM/` — UART loopback test (CH340, TX=PIN_H5, RX=PIN_N1)
- `VGA_7123_TEST/` — VGA output test (ADV7123)
- `Audio_Bypass/` — Audio loopback (WM8731)
- `SD_card/` — SD card test
- `WM8731_input_FFT_VGA/` — Audio FFT with VGA display

## Architectural Considerations

### DDR2 Bandwidth

DDR2-667 at 64-bit: theoretical peak 5.3 GB/s. Effective bandwidth is much lower
due to row activation, refresh, bank conflicts, and the 128-bit local interface
running at half-rate. But even at 10% efficiency (530 MB/s), this is 3x the SDR
SDRAM bandwidth (160 MB/s) and comparable to the Artix-7 DDR3 path.

With 12 cores sharing the DDR2 bus through a round-robin arbiter + write-back
cache, effective per-core bandwidth depends on L2 cache hit rate. A 128 KB L2
(feasible in EP4CE115's 486 KB BRAM) would significantly reduce DDR2 traffic.

### GC with 1 GB Heap

With `MAX_HANDLES = 65536` and 1 GB DDR2:
- Handle area: 2 MB (0.2% of memory)
- Usable heap: ~1022 MB
- Sweep time: ~6 ms at 100 MHz (same as 256 MB — capped by MAX_HANDLES)
- GC init: ~5 ms (65K × 8 word writes)

The GC architecture works unchanged. If applications need more than 65536 live
objects, MAX_HANDLES can be raised — each doubling adds ~6 ms to sweep time.

### Per-Core L1 Cache (Future)

With 12 cores, the shared L2 becomes a bottleneck (arbiter serializes all misses).
A per-core L1 write-back cache (4-8 KB, simple direct-mapped) would absorb most
working-set accesses before they reach the arbiter. This requires a cache coherency
protocol between L1s — similar to existing A$/O$ snoops but for the general cache.

### SMP Synchronization at 12 Cores

CmpSync's global lock becomes a severe bottleneck with 12 cores — only one core can
be in any `synchronized` block at a time. Options:

- **Larger IHLU** — current 32-slot CAM may not be enough for 12 cores with many
  contested objects. 64 or 128 slots are feasible on EP4CE115 (extra CAM area is
  small relative to the FPGA's 114K LEs).
- **Partitioned heaps** — each core owns a heap region with per-core GC. Cross-core
  references through handles still work (handles are global). Eliminates GC STW for
  non-owning cores.
- **Read-only shared data** — class structures, constant pools, method bytecodes are
  immutable after loading. Only mutable object data needs coherency.

### Concurrent GC (Future)

The address translation hardware already exists in BmbMemoryController (`translateAddr`)
but is unused because it causes timing violations at 100 MHz. If pipelined:

- Mutator reads go through hardware read barrier (redirects to new location if object
  is being moved by GC)
- GC compaction runs on a dedicated core in background
- No STW pause for compaction — only brief STW for root snapshot
- JOP's handle indirection makes this uniquely cheap: only `handle[OFF_PTR]` redirects,
  not every pointer in the heap

This is what makes 1 GB actually useful for real-time: predictable sub-millisecond
GC pauses regardless of heap size.

### System Clock

The DDR2 PLL reference is 27 MHz. The `phy_clk` output from the DDR2 controller
runs at half the DDR2 rate. JOP would need a separate PLL for its system clock
(80-100 MHz), with a clock-domain crossing between the JOP domain and the DDR2
`phy_clk` domain — similar to the Artix-7 DDR3 path where JOP runs on the MIG
`ui_clk` (100 MHz derived from the DDR3 controller).

Alternatively, JOP could run directly on `phy_clk` if its frequency is suitable
(~166 MHz for DDR667 half-rate — faster than needed, but EP4CE115 speed grade 7
may not meet timing at 166 MHz for JOP logic).
