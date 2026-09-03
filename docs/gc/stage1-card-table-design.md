# Stage 1 — Parameterized HW Card-Marking Barrier (+ address-width prerequisite)

> **STATUS: DESIGN / FOR REVIEW.** Design for [Stage 1 of the generational GC
> plan](gc-generational-implementation-plan.md#2-staged-plan): a hardware card
> table for the minor-GC remembered set, sized per board so it scales from
> 32 MB SDR up to 1 GB DDR2/DDR3 without blowing the BRAM budget. Includes the
> datapath address-width parameterization that is a prerequisite for addressing
> anything larger than 256 MB.

---

## 0. Why this shape

A generational minor GC only needs to scan roots plus any *tenured* object that
was mutated to point into the nursery. A **card table** — one dirty bit per
fixed-size "card" of tenure — is how we find those cheaply, in hardware, with
zero mutator cost. The design must span very different targets:

| Board | Off-chip | Toolchain | ~total BRAM |
|---|---|---|---|
| QMTECH EP4CGX150 | 32 MB SDR | Quartus | ~810 KB |
| QMTECH XC7A100T + DB V5 | 256 MB DDR3 | Vivado/MIG | ~607 KB |
| Artix module (larger) | **1 GB DDR3** | Vivado/MIG | 607 KB – 2+ MB |
| A-E115FB (EP4CE115) | **1 GB DDR2** | Quartus/ALTMEMPHY | **~486 KB** |

The crux: a card table's bit count scales with *memory*, but BRAM does not.
A "1 bit / 16-word card" over 1 GB is 16 Mbit = **2 MB** — larger than the
EP4CE115's *entire* 486 KB of M9K. So the card *size* must be derived from the
memory size and a per-board BRAM budget, not fixed. Everything below follows
from that.

Two boards want to use all 1 GB with multiple cores, so the table is a **single
shared remembered set** snooping the arbitrated write stream, not per-core.

---

## Part A — Address-width parameterization (prerequisite) — ✅ DONE (2026-07-27, commit `6b50069`)

Implemented per A.3 below: `CacheToMigAdapter(addrWidth=28)`,
`MigBlackBox(appAddrWidth=28)`, `createDdr3Path` derives
`cacheAddrWidth = access.addressWidth − 2`, `JopTop` derives the MIG width from
the device, and `JopMemoryConfig.addressWidth` cap raised 28→32. All defaults
stay 28, so the 256 MB build is byte-identical (`app_addr` still `[27:0]`).
`Ddr3WidthElabTest` elaborates the full path at both 28-bit (256 MB) and 30-bit
(1 GB). Real 1 GB hardware still needs a board + MIG-IP regen.

### A.1 The problem
The DDR3 datapath is currently pinned to 28-bit byte addresses (= 256 MB) in two
places, so a 1 GB device would build a wider BMB against a narrower cache/adapter
and silently truncate the address space:

- `jop/ddr3/CacheToMigAdapter.scala` — `cmd.addr` and `app_addr` are literal
  `Bits(28 bits)` (also `wdata`/`wmask` fixed at 128/16, fine for now).
- `MemoryControllerFactory.createDdr3Path` — `cacheAddrWidth: Int = 28`, and
  `JopTop` calls it with the default even though it already derives
  `memConfig.addressWidth` from the device size.

`LruCacheCore` (tag/index geometry) and the **new fill FSM** are already written
against `config.addrWidth` / `fillAddrWidth`, so they widen automatically — only
the adapter and the `createDdr3Path` default are the gap.

### A.2 Address widths across targets
From the [EP4CE115 board doc](../boards/ep4ce115-ddr2-board.md#address-mapping-for-jop)
(authoritative for the 1 GB mapping) and the current DDR3 build:

| Layer | 256 MB DDR3 | 1 GB DDR2 / DDR3 | Notes |
|---|---:|---:|---|
| JOP word addr incl. type bits (`memConfig.addressWidth`) | 28 | 30 | `[hi:hi-1]` = handle/type |
| Physical word addr | 26 | 28 | `memWords = memSizeBytes/4` |
| BMB byte addr (`cacheAddrWidth`) | 28 | 30 | word + 2 |
| Cache **line** addr → backend | 24 | 26 | byte − log2(lineBytes) |
| Backend local addr | 28 (MIG, byte) | 26 (DDR2 `local_address`, line) | MIG aligns low 4 |

> ⚠️ **Verify the exact derivation** of `cacheAddrWidth` vs
> `memConfig.addressWidth` vs `bmbParameter.access.addressWidth` in
> `JopMemoryConfig` before coding — the `+2`s (type bits vs byte shift) must not
> double-count. The 256 MB build works today, so use it as the reference point
> and confirm 1 GB scales by the table above.

### A.3 Changes
1. `CacheToMigAdapter(addrWidth: Int)` — parameterize `cmd.addr` / `app_addr`
   width (default 28 keeps every current instantiation unchanged).
2. `MigBlackBox` — parameterize `app_addr` width to match the device.
3. `createDdr3Path` — derive `cacheAddrWidth` from `bmbParameter` (i.e. the
   device size) instead of the literal `28`; pass it to the adapter.
4. The Altera **`CacheToDdr2Adapter`** (new, see board doc §CacheToDdr2Adapter)
   takes the same `addrWidth` param and emits a 26-bit `local_address`.

### A.4 Adjacent, deferred: cache **line width** (128/256/512-bit)
The board doc shows 128-bit lines waste DDR2/DDR3 burst bandwidth (BL4/BL8), and
recommends parameterizing `LruCacheCore.lineWidth`. That is a real throughput win
(halves misses on sequential GC scans) but is **independent of Stage 1** and
higher-risk (touches the cache data BRAMs, tag geometry, and the fill FSM's
`wordsPerLine`). Keep it out of this stage; note only that line width changes the
cache-line-addr width (`byteAddr − log2(lineBytes)`), so parameterize address
widths in terms of `lineBytes` now to avoid reworking them later.

---

## Part B — The card table

### B.1 Sizing model (the parameterization)
Two inputs, one derived quantity:

- `memSizeBytes` — from the memory device (per board).
- `cardTableBudgetBytes` — BRAM to spend on the table (per board, from what's
  left after caches).

```
memWords     = memSizeBytes / 4
budgetBits   = cardTableBudgetBytes * 8
minCardShift = log2(lineWords)                     // never finer than a cache line
cardShift    = max(minCardShift, log2Up(memWords / budgetBits))
cardWords    = 1 << cardShift                      // words covered per card bit
cardTableBits = memWords >> cardShift              // ≤ budgetBits, fixed at synthesis
```

The table covers the **full** address space `[0, memWords)`; `cardIndex =
wordAddr >> cardShift`. The GC scans only the tenure sub-range
`[tenureBase >> cardShift, tenureTop >> cardShift)`. (Marking a few nursery cards
is harmless and avoids a runtime-dependent base-subtract in the mark path.)

**Scaling at a fixed 16 KB budget:**

| Off-chip mem | memWords | `cardShift` | card covers | table |
|---|---:|---:|---:|---:|
| 32 MB SDR | 8 M | 6 | 256 B | 16 KB |
| 256 MB DDR3 | 64 M | 9 | 2 KB | 16 KB |
| 1 GB DDR3 (Artix) | 256 M | 11 | 8 KB | 16 KB |
| 1 GB DDR2 (EP4CE115) | 256 M | 11 | 8 KB | 16 KB |

Same 1 GB heap on both 1 GB boards — but a roomier Artix part can raise its
budget to 32–64 KB and get 4 KB / 2 KB cards (less minor-GC scan work), while the
BRAM-tight EP4CE115 stays at 8–16 KB. **`cardTableBudgetBytes` is the per-board
pause-time-vs-BRAM dial**, and it's the entire point of parameterizing.

The trade-off it sets (matters for Stage 3, not here): a coarser card forces the
minor GC to scan more tenure words per dirty card. Worst-case scan work ≈
`dirtyCards × cardWords`; the budget picks the point on that curve.

### B.2 Placement — shared, post-arbiter snoop
A new `CardTable` component in `JopCluster`, snooping the **arbitrated** BMB
command stream (between the arbiter and the backend), *not* per-core:

- All cores write one physical heap through the arbiter → one table = one shared
  remembered set, the natural structure for a stop-the-world minor GC.
- Backend-agnostic: sits above SDR / DDR3 / DDR2 / BRAM.
- No N× BRAM duplication (critical on the EP4CE115).

Marking: on an arbitrated BMB **write** fire with word address `W`, if
`tenureBase ≤ W < tenureTop`, set `card[W >> cardShift] := 1`. One BRAM
read-modify-write per store into tenure; constant-time, invisible to software.

> Coherency note: the L2 is write-back, so a store may sit dirty in cache and
> reach the backend only on eviction. The card must be set at the point the
> **store is committed by the mutator**, i.e. snoop the BMB write *command*
> (pre-cache), not the eviction stream. Confirm the snoop tap is on the
> BmbCacheBridge input, not its `memCmd` output.

### B.3 Config surface
Add to `JopMemoryConfig` (defaults keep every existing preset unchanged):

```scala
hasCardTable:          Boolean = false
cardTableBudgetBytes:  Int     = 0        // required when hasCardTable
// derived (helpers on the config):
def cardShift: Int   = max(minCardShift, log2Up(memWords / (cardTableBudgetBytes*8)))
def cardCount: Int   = memWords >> cardShift
```

Enable per preset, e.g. `xc7a100tDbSerial` → `cardTableBudgetBytes = 16*1024`;
EP4CE115 preset → `8*1024`. Threaded to `JopCluster` like `hasBackendFill`.

### B.4 I/O register map (memory-mapped, read + clear)
Fixed I/O regs, same style as `IO_ZERO_START/END` / `IO_US_CNT`:

| Reg | Dir | Meaning |
|---|---|---|
| `IO_CARD_BASE` | W | tenure base (word addr) — marking window low |
| `IO_CARD_TOP` | W | tenure top (word addr) — marking window high |
| `IO_CARD_SHIFT` | R | `cardShift` (so SW computes card↔addr without hardcoding) |
| `IO_CARD_COUNT` | R | `cardCount` |
| `IO_CARD_IDX` | W | select a 32-card word for read/clear |
| `IO_CARD_DATA` | R | read 32 card bits at `IO_CARD_IDX` |
| `IO_CARD_CLEAR` | W | clear the word at `IO_CARD_IDX` (or all if a sentinel) |

GC reads 32 cards/word to scan quickly, and clears after scanning. **Future
accelerator:** `IO_CARD_NEXT_DIRTY` backed by a priority encoder over the table,
making scan cost ∝ *dirty* cards not *total* cards — a big win at 1 GB; leave out
of the first cut.

#### `IO_CARD_CLEAR` with the all-ones sentinel BLOCKS (2026-09-02)

**The sweep and marking must not overlap, and the write is what enforces it.**
The table has one write port, and the clear-all sweep wins it: a mark reaching
the write stage while the sweep runs has its index *and* its data replaced, and
is silently dropped. That is the unsafe direction — leaving a card dirty costs
only time, but losing a mark leaves it **clean**, so the next minor GC never
scans the holder and a live object with no other root is collected.

The sweep is `cardWords32` cycles: 1024 to 16384 depending on the board (4 KB
budget on the CYC5000 up to 64 KB on the A-E115FB, ~218 µs at 75 MHz). The
collector releases `IO_GC_HALT` about eight statements after starting the clear,
so without a stall the mutators run for essentially all of it.

So `IO_CARD_CLEAR` behaves like `IO_ZERO_END`: the write does not return until
the table is clear. `clrBusy` is held from the request cycle, through the
mark-pipeline drain, to the last word zeroed, and is routed
`CardTable` → `CardCtrlPort.busy` → `JopCore` → `pipeline.io.memBusy`, broadcast
to **every** core rather than just the issuer — the dangerous mark is another
core's.

Three things a reader of this table should know:

- **Giving the mark priority does not work.** A mark landing on a word the
  sweep has not yet reached is erased when it gets there. The race is not
  between two writers; it is between a mark and an unfinished sweep.
- **Busy covers the request cycle itself.** Both state registers set on the next
  edge, so without an explicit term a core sampling busy in the cycle it issues
  the write sees 0 and does not stall.
- **The single-word clear (`clrEn`) does not block** and does not need to: it
  writes one word in one cycle, with no window.

Full history, the reproduction, and why the obvious repair fails:
[current-status.md item 131](../current-status.md#item-131).

### B.5 GC integration (Stage 2 preview, not built here)
`minorGc()` will: read `IO_CARD_SHIFT`; for each dirty card in the tenure range,
scan its `cardWords` words for pointers into the nursery; add found objects to the
mark roots; clear the card after scanning. Stage 1 delivers only the HW table +
marking + the I/O interface — no `GC.java` changes.

---

## Part C — Validation plan (mirrors the fill work)

1. **`CardTable` unit sim** — drive synthetic BMB writes at known addresses across
   a few `(memSize, budget)` points; assert the right card bit sets and others
   stay clear; check edge/wrap and the tenure-window gate.
2. **`JopCardMarkSim`** — run a small app that writes a known scatter of addresses,
   then reads the table back via the I/O regs and verifies the expected cards are
   dirty (analogous to `JopDdr3FillMigSim`).
3. **Hardware `CardMarkTest.java`** — write a pattern spanning the tenure, read
   card bits over I/O, verify. Run first on the **XC7A100T** (available, DDR3),
   then on the EP4CE115 once it's brought up.

---

## Part D — Decisions (locked 2026-07-27)

1. **Per-board `cardTableBudgetBytes`** — ✅ **16 KB default**, 8 KB on the
   BRAM-tight EP4CE115, up to 32–64 KB on roomier Artix parts. Sets card size per
   the B.1 table.
2. **Shared post-arbiter snoop** — ✅ (one remembered set for all cores).
3. **Plain readable table now, HW `NEXT_DIRTY` scanner deferred** — ✅.
4. **Full-memory coverage** (`index = W >> cardShift`, GC filters) — ✅.
5. **Sequencing** — ✅ **Part A (address widths) first**, then the card table.

---

## Part E — Sequencing / tasks

1. **A — address-width parameterization** (adapter + `createDdr3Path` +
   MigBlackBox). Small, testable in isolation via Verilog gen at
   `addressWidth = 30` and the existing DDR3 sims; unblocks >256 MB on Artix and
   is a prerequisite for the DDR2 board. Ship first.
2. **B — `CardTable` component + config surface + I/O regs**, shared snoop in
   `JopCluster`. Unit sim + `JopCardMarkSim`.
3. **HW card-marking test** on XC7A100T (`CardMarkTest.java`).
4. Then Stage 2 (`GC.java` nursery + `minorGc()` using the table).

## Board bring-up notes (EP4CE115, parallel track)
- **Serial:** on-board CH340 **FPGA-TX (H5) is broken** (doesn't reach the CH340,
  per board doc); RX (N1) works. Plan: a Pico/RP2040 serial bridge on the
  power-side UP header (A14/B14), reusing the DirtyJTAG RP2040 UART approach that
  fixed the XC7A100T download path.
- **DDR2 IP:** ALTMEMPHY must be generated in **Quartus 18.1** (dropped in 19.x+);
  wrap the generated Verilog as a SpinalHDL `BlackBox`. Reuse the
  `read_write_1G` test project's IP.
- **Programmer:** may share the Altera USB-Blaster with the EP4CGX150 board.
- **Superseded:** the DDR2 build exists and works (`fpga/a-e115fb-ddr2`, DoAll 66/66 from a generated Quartus project). `fpga/a-e115fb-bram/` was retired on 2026-08-26 — it never had a Quartus project and generated another board's preset.

## References
- [Generational GC plan](gc-generational-implementation-plan.md)
- [EP4CE115 DDR2 board](../boards/ep4ce115-ddr2-board.md) — address map, DDR2
  adapter, resource budget, line-width/burst analysis, serial situation.
- `jop/ddr3/CacheToMigAdapter.scala`, `jop/ddr3/LruCacheCore.scala`,
  `jop/system/memory/MemoryControllerFactory.scala`, `jop/system/JopCluster.scala`.
