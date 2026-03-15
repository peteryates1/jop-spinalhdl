# Cross-File Constant Dependencies

This document maps all interdependent constants and configurations that must
stay consistent across multiple files. Constants that drift out of sync cause
silent failures — wrong handlers, data corruption, or heap/stack collisions.

## Quick Reference: Risk Summary

| § | Category | Cross-file check? | Risk | Failure mode |
|---|----------|-------------------|------|-------------|
| 3 | Bytecode opcodes | **NO** — 3 sources | Critical | Wrong handler runs silently |
| 14 | GC handle struct | **NO** | Critical | GC corrupts object graph |
| 1 | Method cache sizing | **NO** | High | Silent bytecode truncation |
| 4 | I/O addresses | Partial — generated but microcode hardcoded | High | Reads/writes wrong device |
| 7 | Memory / heap | **NO** — Startup.java unaware of stack regions | High | Heap/stack collision |
| 11 | Jopa assembler | **NO** | High | ROM/RAM layout mismatch |
| 12 | JOPizer linker | **NO** | High | Class struct / method size mismatch |
| 16 | Device register maps | **NO** | High | Driver misreads device status |
| 5 | Stack layout | **NO** — STACK_OFF=64 assumed | Medium | Scratch area corruption |
| 6 | Clock / baud rate | **NO** | Medium | UART garbage, timing drift |
| 8 | SDRAM timing | **NO** — assumes correct device | Medium | Data corruption |
| 9 | Memory style | **NO** — paths hardcoded | Medium | ROM loads zeros |
| 10 | Interrupt config | Partial — generated | Medium | Wrong exception handler |
| 13 | JopSim simulator | **NO** | Medium | Sim diverges from hardware |
| 2 | Microcode widths | YES (`require`) | Low | Won't compile |
| 15 | Runtime feature flags | YES (generated) | Low | Stale if not regenerated |

---

## 1. Method Cache Sizing

The method cache limits how large a single Java method's bytecode can be.
Three independent constants must agree.

| Constant | File | Value | Purpose |
|----------|------|-------|---------|
| `METHOD_SIZE_BITS` | `jop/types/JopConstants.scala:56` | 10 | Max method size field width (2^10 = 1024 words) |
| `methodSizeBits` | `jop/memory/MethodCache.scala:31` | 10 | Hardware `bcLen` port width |
| `MAX_BC` | `com/jopdesign/tools/Cache.java:28` | 1024 | JOPizer linker limit per method |
| `MAX_BC_MASK` | `com/jopdesign/tools/Cache.java:29` | 0x3ff | = MAX_BC - 1 |
| `jpcWidth` | `jop/config/JopCoreConfig.scala:294` | 11 | Total bytecode cache = 2^jpcWidth bytes |
| `blockBits` | `jop/config/JopCoreConfig.scala:296` | 4 | Cache blocks = 2^blockBits |

### Derived values

- Block size = 2^(jpcWidth - 2 - blockBits) = 2^5 = 32 words per block
- Total cache = 2^(jpcWidth - 2) = 512 words
- Max blocks per method = method_words / block_size

### Constraint

A method must fit in contiguous cache blocks. The effective limit is:

    min(2^METHOD_SIZE_BITS, total_cache_words) = min(1024, 512) = 512 words

So the cache size (512 words) is the real limit, not METHOD_SIZE_BITS (1024).

### To increase

1. Increase `jpcWidth` (e.g., 12 → 4KB cache, 13 → 8KB) — costs BRAM
2. Increase `METHOD_SIZE_BITS` and `methodSizeBits` if methods exceed 1024 words
3. Update `MAX_BC` and `MAX_BC_MASK` in `Cache.java` to match
4. Remove the `require(jpcWidth == 11)` guard in `JopCoreConfig`

### Validation

`require(jpcWidth == 11)` exists in JopCoreConfig but there is **no automated
cross-check** between `METHOD_SIZE_BITS` (SpinalHDL) and `MAX_BC` (Java tools).

---

## 2. Microcode Architecture

Instruction width and ROM sizing control the fetch/decode pipeline.

| Constant | File | Value | Checked? |
|----------|------|-------|----------|
| `instrWidth` | `jop/config/JopCoreConfig.scala:293` | 10 | YES — `require(instrWidth == 10)` |
| `pcWidth` | `jop/config/JopCoreConfig.scala:292` | 12 | YES — `require(pcWidth == 12)` |
| ROM depth | `jop/pipeline/FetchConfig.scala:27` | 2^12 = 4096 | Derived from pcWidth |
| Jump table | `jop/pipeline/BytecodeFetchStage.scala` | 256 entries | Fixed — one per JVM bytecode |

### What breaks

- `instrWidth ≠ 10`: All decode bit-slice logic fails — decode stage has
  hardcoded field positions.
- `pcWidth ≠ 12`: ROM too small/large, jump table addressing breaks.

### Validation

Strong — both have `require()` checks. Low risk.

---

## 3. Bytecode Opcodes — Three Independent Sources

This is the most dangerous dependency. Bytecode opcode numbers exist in three
separate files with **no cross-validation**.

| Source | File | Role |
|--------|------|------|
| Hardware truth | `jop/config/JopCoreConfig.scala` BytecodeConfig | Defines opcode → handler mapping |
| Java tools | `com/jopdesign/tools/Instruction.java` | JOPizer uses for .jop linking |
| Microcode | `asm/src/jvm.asm` | Handler labels |

### Examples

| Bytecode | Opcode | JopCoreConfig | Instruction.java | jvm.asm |
|----------|--------|---------------|-------------------|---------|
| imul | 0x68 | BytecodeEntry | `ia[0x68] = "imul"` | `imul:` |
| idiv | 0x6C | BytecodeEntry | `ia[0x6C] = "idiv"` | `idiv:` |
| irem | 0x70 | BytecodeEntry | `ia[0x70] = "irem"` | `irem:` |
| lmul | 0x69 | BytecodeEntry | `ia[0x69] = "lmul"` | `lmul:` |

### What breaks

If any opcode number differs between these three files, the processor jumps
to the wrong microcode handler. The failure is **silent** — no error, just
wrong results.

### Custom instructions (DSP multiply)

| Mnemonic | Opcode | Class | File |
|----------|--------|-------|------|
| `dspmul` | 0x103 | NOP | Instruction.java, DecodeStage.scala |
| `lddsp` | 0x008 | POP | Instruction.java, DecodeStage.scala |
| `lddsph` | 0x009 | POP | Instruction.java, DecodeStage.scala |

These must match between `Instruction.java` (assembler) and `DecodeStage.scala`
(hardware decode).

### Validation

**NONE**. Three independent sources of truth.

### Recommendation

Generate `Instruction.java` opcode table from `BytecodeConfig`, or add a
compile-time cross-check test.

---

## 4. I/O Address Space

Device addresses are split between generated and hardcoded constants.

### Fixed addresses (hardcoded in microcode)

| Constant | File | Value | Notes |
|----------|------|-------|-------|
| `SYS_BASE` | `jop/memory/JopIoSpace.scala:154` | 0xF0 | System device (timer, WD, etc.) |
| `UART_BASE` | `jop/memory/JopIoSpace.scala:155` | 0xEE | Boot UART (status + data) |
| Microcode refs | `asm/src/jvm.asm` | bipush values | **Must match** SYS_BASE/UART_BASE |

Microcode uses `bipush` with hardcoded offsets to access system and UART
registers. If `SYS_BASE` or `UART_BASE` change in SpinalHDL, microcode
must be updated too.

### Generated addresses (safe)

| Constant | File | Value | Notes |
|----------|------|-------|-------|
| `IO_BASE` | `Const.java:108` | -128 (0xFFFFFF80) | Generated by ConstGenerator |
| `IO_CNT` | `Const.java:116` | IO_BASE + 0 | Generated |
| `IO_UART` | `Const.java:164` | IO_BASE + 0x11 | Generated |
| Auto-alloc start | `IoAddressAllocator` | 0xED downward | Derived |

### Address encoding

JOP I/O addresses use negative word addresses in Java:

    bipush -128  →  0xFFFFFF80 (sign-extended)
    Hardware decodes low byte: 0x80 = start of I/O space
    Top 2 bits = 11 → I/O space (0xC0000000 in word addressing)

The 8-bit I/O address space runs from 0x80 to 0xFF:
- 0xF0–0xFF: System device (16 registers)
- 0xEE–0xEF: Boot UART (2 registers)
- 0xED downward: Auto-allocated devices (largest-first)

### Validation

`IoAddressAllocator.allocate()` checks for address overlap, but does **not**
validate against hardcoded microcode assumptions.

---

## 5. Stack Architecture

### On-chip stack RAM

| Constant | File | Value | Notes |
|----------|------|-------|-------|
| `ramWidth` | `JopCoreConfig.scala:295` | 8 | 2^8 = 256 entries — **no require()** |
| `STACK_OFF` | `Const.java:60` | 64 | Scratch area size (words 0–63) |
| `scratchSize` | `StackStage.scala:30` | 64 | **Must = STACK_OFF** |
| `bankSize` | `StackStage.scala:29` | 192 | = 256 - scratchSize |

### Scratch area layout (words 0–63)

These are architectural constants used by microcode and Java runtime:

| Offset | Const.java | Purpose |
|--------|-----------|---------|
| 0 | — | (reserved) |
| 1 | `RAM_CP` | Constant pool pointer |
| 2 | `RAM_VP` | Variable pointer |
| ... | ... | Other scratch registers |

### Stack cache (optional, 3-bank rotating)

| Constant | File | Value | Notes |
|----------|------|-------|-------|
| `useStackCache` | `JopCoreConfig.scala:303` | false | Gates entire feature |
| `numBanks` | `StackStage.scala:28` | 3 | Fixed — `require(numBanks == 3)` |
| `stackRegionWordsPerCore` | `JopMemoryConfig.scala:36` | 0 or 8192 | Per-core SDRAM spill region |
| `spillBaseAddr` | `StackStage.scala:32` | memWords - (cpuId+1)*region | DMA spill base address |

### Validation

- `useStackCache` requires `stackRegionWordsPerCore > 0` (checked)
- **No check** that `scratchSize == STACK_OFF`
- **No check** on `ramWidth`

---

## 6. Clock Frequency and UART Baud Rate

| Constant | File | Value | Notes |
|----------|------|-------|-------|
| `clkFreq` | `JopConfig` presets | 80 MHz (EP4CGX150), 100 MHz (Wukong/XC7A100T) | Per-board |
| `uartBaudRate` | `JopCoreConfig.scala:329` | 2000000 (default) | From device params |
| UART divider | `BmbUart.scala` | clkFreq / baudRate | Derived — must produce integer |
| Sys prescaler | `Sys.scala` | clkFreq / 1 MHz | Microsecond counter |
| download.py | `fpga/scripts/download.py:232` | 2000000 | **Hardcoded — must match** |
| monitor.py | `fpga/scripts/monitor.py` | Makefile BAUD_RATE | From Makefile variable |

### What breaks

- `clkFreq` wrong: UART baud is off (garbled serial), microsecond counter
  runs fast/slow, all Java timing loops affected.
- `download.py` baud ≠ hardware baud: download fails with checksum errors.
- Non-integer divider: baud rate error, marginal at high speeds.

### Clock frequency chain

```
Board oscillator (e.g., 50 MHz)
  → PLL (configured per board)
    → System clock (e.g., 80 MHz)
      → JopConfig.clkFreq (must match PLL output)
        → BmbUart (divider = clkFreq / baudRate)
        → Sys (prescaler = clkFreq / 1 MHz)
        → Java runtime (reads microsecond counter)
```

### Validation

**NONE** between download.py and hardware config. The PLL output frequency
is set in vendor IP (Quartus/Vivado) and must match `JopConfig.clkFreq`
manually.

---

## 7. Memory Addressing and Layout

### Address space encoding

| Top 2 bits | File | Space | Size |
|-----------|------|-------|------|
| 00 | `JopMemoryConfig.scala:101` | Main memory (SDRAM) | Up to 64MB |
| 01 | `JopMemoryConfig.scala:102` | Reserved | — |
| 10 | `JopMemoryConfig.scala:103` | Scratch (on-chip) | 256 words |
| 11 | `JopMemoryConfig.scala:104` | I/O | 128 addresses |

### Memory sizing

| Constant | File | Value | Notes |
|----------|------|-------|-------|
| `addressWidth` | `JopMemoryConfig.scala:19` | 24 | 2^(24-2) = 4M words = 16MB addressable |
| `mainMemSize` | `JopMemoryConfig.scala:20` | 8 MB default | Must fit in address space |
| SDRAM geometry | `Parts.scala` (per device) | bankWidth, columnWidth, rowWidth | Must match physical chip |
| `usableMemWords` | `JopMemoryConfig.scala:52` | mainMemSize - stack regions | Heap upper bound |

### Heap / stack collision risk

| Constant | File | Value | Notes |
|----------|------|-------|-------|
| `stackRegionWordsPerCore` | `JopMemoryConfig.scala:36` | 0 (legacy) or 8192 | Per-core spill area at top of SDRAM |
| Heap end | `Startup.java` | `appEnd + 262144` (1MB fallback) | **Does not know about stack regions** |

When `useStackCache=true` with `stackRegionWordsPerCore=8192`, the top of
SDRAM is reserved for stack spill. But `Startup.java` uses a fixed fallback
heap size and does not account for this reservation. With enough cores or a
large heap, the heap could overwrite stack spill regions.

### Validation

- `addressWidth` has bounds check (16–28)
- **No validation** that heap end < stack spill base
- **No validation** that `mainMemSize` fits in `addressWidth` space

---

## 8. SDRAM Timing Parameters

| Constant | File | Value (W9825G6JH6) | Notes |
|----------|------|------|-------|
| `bankWidth` | `Parts.scala:110` | 2 | 4 banks |
| `columnWidth` | `Parts.scala:109` | 9 | 512 columns |
| `rowWidth` | `Parts.scala:111` | 13 | 8192 rows |
| `casLatency` | `Parts.scala:113` | 3 | Read latency in cycles |
| `burstLen` | `JopMemoryConfig.scala:22` | 0 (single), 4 (SDR), 8 (DDR3) | SDRAM burst length |

### What breaks

- Wrong `casLatency`: controller reads data at wrong cycle — corruption.
- Wrong `rowWidth`: refresh calculation wrong — DRAM loses data over time.
- Wrong `bankWidth`: bank address bits misaligned — writes to wrong bank.
- `burstLen` mismatch: BMB `lengthWidth` calculation wrong — short reads.

### Validation

**NONE** — the selected `MemoryDevice` is not validated against the actual
chip on the board. Wrong device in config silently produces wrong timing.

---

## 9. Memory Style (FPGA-Specific)

| Constant | File | Value | Notes |
|----------|------|-------|-------|
| `memoryStyle` | `JopCoreConfig.scala:307` | Generic or AlteraLpm | ROM/RAM instantiation style |
| FPGA family | `Parts.scala:44-50` | CycloneIV → AlteraLpm, Artix7 → Generic | Auto-derived |
| ROM .mif path | `MemoryStyle.scala:124` | `../../asm/generated/serial/rom.mif` | Hardcoded relative path |
| RAM .mif path | `MemoryStyle.scala:141` | `../../asm/generated/serial/ram.mif` | Hardcoded relative path |

### What breaks

- AlteraLpm on Xilinx: LPM BlackBox instantiation fails at synthesis.
- Wrong .mif path: ROM initializes to zeros, processor executes garbage.
- Generic on Altera: works but wastes LEs vs LPM (timing may fail).

### Validation

**NONE** — .mif paths are hardcoded with no existence check.

---

## 10. Interrupt Configuration

| Constant | File | Value | Notes |
|----------|------|-------|-------|
| Exception numbers | `Const.java:87-98` | EXC_SPOV=1, EXC_NP=2, EXC_AB=3, EXC_DIVZ=8 | Hardcoded in microcode handlers |
| `NUM_INTERRUPTS` | `Const.java:253` | 1 + maxNumIoInt | Generated by ConstGenerator |
| `numIoInt` | `JopCoreConfig.scala:341` | Sum of device interrupt counts | Derived from device config |

### What breaks

- Exception number changed in one place but not the other: handler
  misidentifies exception type, wrong recovery path.
- `NUM_INTERRUPTS` too small: interrupt vector table overflows.

### Validation

`ConstGenerator` computes `NUM_INTERRUPTS` dynamically. Exception numbers
are hardcoded — no cross-check.

---

## 11. Microcode Assembler (Jopa)

The assembler (`Jopa.java`) has hardcoded ROM/RAM layout constants that must
match the hardware pipeline.

| Constant | File | Value | Must match |
|----------|------|-------|------------|
| `ADDRBITS` | `Jopa.java` | 12 | = pcWidth in JopCoreConfig |
| `INSTLEN` | `Instruction.java` | 10 | = instrWidth in JopCoreConfig |
| `DATABITS` | `Jopa.java` | INSTLEN + 2 = 12 | Derived |
| `ROM_LEN` | `Jopa.java` | 2^ADDRBITS = 4096 | = ROM depth |
| `RAM_LEN` | `Jopa.java` | 256 | = 2^ramWidth in JopCoreConfig |
| `CONST_ADDR` | `Jopa.java` | 32 | Fixed ROM address for constant pool |

### What breaks

- `ADDRBITS ≠ pcWidth`: Assembled ROM has wrong depth, microcode
  addresses wrap or overflow.
- `RAM_LEN ≠ 2^ramWidth`: Assembler allows references to RAM addresses
  that don't exist in hardware.
- `INSTLEN ≠ instrWidth`: Instruction encoding misaligned, decode
  extracts wrong fields.

### Validation

**NONE** — Jopa hardcodes these independently from JopCoreConfig.

---

## 12. JOPizer (Bytecode Linker)

JOPizer converts Java class files into the `.jop` binary format. It has
architecture assumptions about class structure and method sizing.

| Constant | File | Value | Purpose |
|----------|------|-------|---------|
| `METHOD_MAX_SIZE` | `JOPizer.java` | 2048 | Max method bytecode bytes |
| `CLS_HEAD` | `ClassStructConstants.java` | 5 | Class header size (words) |
| `METH_STR` | `ClassStructConstants.java` | 2 | Method table entry size (words) |
| `IMPORTANT_PTRS` | `ClassStructConstants.java` | 12 | Important pointers offset |

### Class structure layout

These offsets define the in-memory layout of JOP class/method structures.
They are used by both JOPizer (linking) and the GC (scanning). Both must
agree on the layout.

| Constant | File | Value | Also in |
|----------|------|-------|---------|
| `CLS_HEAD` | `ClassStructConstants.java` | 5 | GC.java header scanning |
| `METH_STR` | `ClassStructConstants.java` | 2 | Pipeline method dispatch |
| `MTAB2CLINFO` | `ClassStructConstants.java` | -5 | GC.java, Startup.java |
| `MTAB2GC_INFO` | `ClassStructConstants.java` | -3 | GC.java |
| `CLINITS_OFFSET` | `ClassStructConstants.java` | 11 | Startup.java class init |

### What breaks

- `CLS_HEAD` mismatch: GC scans wrong header fields, corrupts live objects.
- `METH_STR` mismatch: method table lookup returns wrong code pointer.
- `METHOD_MAX_SIZE` too large for cache: method loads past cache end,
  bytecode fetch returns garbage.

### Validation

**NONE** between JOPizer and hardware cache size. JOPizer allows 2048 bytes
but hardware cache may only hold 512 words (2048 bytes at 4 bytes/word —
this happens to match by coincidence, not by design).

---

## 13. JopSim (Software Simulator)

JopSim simulates the JOP processor in Java for testing. It has its own
memory and cache constants that should match hardware but don't always.

| Constant | File | Value | Hardware equiv |
|----------|------|-------|---------------|
| `MAX_MEM` | `JopSim.java` | 262144 (1MB) | mainMemSize / 4 |
| `MAX_STACK` | `JopSim.java` | 65536 | 2^ramWidth = 256 (mismatch!) |
| `MIN_IO_ADDRESS` | `JopSim.java` | -128 | = IO_BASE |
| `SYS_INT` | `JopSim.java` | 0xf0 | = SYS_BASE |
| `SYS_EXC` | `JopSim.java` | 0xf1 | Not in hardware |

### JOPConfig.java (Timing Model)

Used by WCET analysis and simulator for cycle-accurate timing.

| Constant | File | Value | Purpose |
|----------|------|-------|---------|
| `CACHE_BLOCKS` | `JOPConfig.java` | 16 | = 2^blockBits |
| `CACHE_SIZE_WORDS` | `JOPConfig.java` | 1024 | Cache size in words |
| `OBJECT_CACHE_ASSOCIATIVITY` | `JOPConfig.java` | 16 | Object cache ways |
| `OBJECT_CACHE_WORDS_PER_LINE` | `JOPConfig.java` | 16 | Words per line |
| `OBJECT_CACHE_HIT_CYCLES` | `JOPConfig.java` | 5 | Hit latency |
| `OBJECT_CACHE_LOAD_FIELD_CYCLES` | `JOPConfig.java` | 8 | Field bypass latency |
| `OBJECT_CACHE_LOAD_BLOCK_CYCLES` | `JOPConfig.java` | 8 | Block miss latency |
| `READ_WAIT_STATES` | `JOPConfig.java` | 1 | Memory read wait |
| `WRITE_WAIT_STATES` | `JOPConfig.java` | 2 | Memory write wait |
| `CMP_CPUS` | `JOPConfig.java` | 8 | SMP CPU count |
| `CMP_TIMESLOT` | `JOPConfig.java` | 10 | Arbiter timeslot cycles |

### What breaks

- `CACHE_BLOCKS ≠ 2^blockBits`: WCET analysis computes wrong miss penalty.
- `CACHE_SIZE_WORDS ≠ actual`: WCET analysis overestimates cache capacity.
- Wait state mismatches: WCET bounds are unsound (too optimistic or
  too pessimistic).

### Validation

**NONE** — JOPConfig.java is completely independent from SpinalHDL config.
WCET analysis results are only valid if these constants match actual hardware.

---

## 14. GC Handle Structure

The garbage collector uses a fixed handle structure that must match between
GC.java (runtime) and JOPizer (linking).

| Constant | File | Value | Purpose |
|----------|------|-------|---------|
| `HANDLE_SIZE` | `GC.java` | 8 | Words per GC handle |
| `MAX_HANDLES` | `GC.java` | 65536 | Maximum handle count |
| `OFF_PTR` | `GC.java` | 0 | Object pointer field |
| `OFF_MTAB_ALEN` | `GC.java` | 1 | Method table / array length |
| `OFF_SPACE` | `GC.java` | 2 | Mark space / scope level |
| `OFF_TYPE` | `GC.java` | 3 | Object type (obj/ref-arr/prim-arr) |
| `OFF_NEXT` | `GC.java` | 4 | Free/use list next |
| `OFF_GREY` | `GC.java` | 5 | Gray list threading |

### What breaks

- `HANDLE_SIZE` mismatch: handle table arithmetic wrong, GC overwrites
  adjacent handles.
- `OFF_*` mismatch: GC reads wrong field from handle, corrupts object graph.
- `MAX_HANDLES` too large: handle table exceeds available memory.

### Validation

**NONE** — these are pure architectural constants with no cross-check.
They are stable (unchanged since original JOP) but fragile if modified.

---

## 15. Runtime Feature Flags

`Const.java` contains feature flags that gate runtime behavior. These are
generated by `ConstGenerator` from `JopCoreConfig`.

| Flag | File | Value | Drives |
|------|------|-------|--------|
| `SUPPORT_FLOAT` | `Const.java` | true/false | Software float emulation in JVMHelp |
| `SUPPORT_DOUBLE` | `Const.java` | true/false | Software double emulation |
| `HAS_ETHERNET` | `Const.java` | true/false | Network stack initialization |
| `HAS_SD_CARD` | `Const.java` | true/false | SD card driver |
| `HAS_VGA` | `Const.java` | true/false | VGA text controller |
| `HAS_CONFIG_FLASH` | `Const.java` | true/false | Config flash support |
| `NUM_INTERRUPTS` | `Const.java` | computed | Interrupt vector table size |

### What breaks

- Flag set but device not in hardware: Java code accesses non-existent I/O
  registers, reads garbage.
- Flag clear but device present: device never initialized, wasted hardware.
- `NUM_INTERRUPTS` wrong: interrupt vector table too small, overwrite.

### Validation

Generated by `ConstGenerator` — safe as long as Const.java is regenerated
when config changes. Risk is forgetting to regenerate.

---

## 16. Device Register Maps

Each I/O device has register bit definitions in both SpinalHDL (hardware)
and Java (driver). These must match exactly.

### UART (BmbUart.scala ↔ SerialPort.java / Const.java)

| Bit | SpinalHDL | Java | Value |
|-----|-----------|------|-------|
| TX empty (TDRE) | `txReady` | `MSK_UA_TDRE` | bit 0 |
| RX full (RDRF) | `rxValid` | `MSK_UA_RDRF` | bit 1 |

### Ethernet MAC (BmbEth.scala ↔ EthMac.java)

| Bit | SpinalHDL | Java | Value |
|-----|-----------|------|-------|
| TX flush | status bit 0 | `STATUS_TX_FLUSH` | 1 << 0 |
| TX ready | status bit 1 | `STATUS_TX_READY` | 1 << 1 |
| RX flush | status bit 4 | `STATUS_RX_FLUSH` | 1 << 4 |
| RX valid | status bit 5 | `STATUS_RX_VALID` | 1 << 5 |

### SD Native (BmbSdNative.scala ↔ SdNative.java)

| Bit | SpinalHDL | Java | Value |
|-----|-----------|------|-------|
| CMD busy | status bit 0 | `STATUS_CMD_BUSY` | 1 << 0 |
| CMD resp valid | status bit 1 | `STATUS_CMD_RESP_VALID` | 1 << 1 |
| CMD resp error | status bit 2 | `STATUS_CMD_RESP_ERR` | 1 << 2 |
| CMD timeout | status bit 3 | `STATUS_CMD_TIMEOUT` | 1 << 3 |
| Data busy | status bit 4 | `STATUS_DATA_BUSY` | 1 << 4 |
| Data CRC error | status bit 5 | `STATUS_DATA_CRC_ERR` | 1 << 5 |
| Data timeout | status bit 6 | `STATUS_DATA_TIMEOUT` | 1 << 6 |
| Data done | status bit 7 | `STATUS_DATA_DONE` | 1 << 7 |
| FIFO full | status bit 9 | `STATUS_FIFO_FULL` | 1 << 9 |
| FIFO empty | status bit 10 | `STATUS_FIFO_EMPTY` | 1 << 10 |

### VGA Text (BmbVgaText.scala ↔ VgaText.java)

Register offsets and control bit definitions in both files.

### VGA DMA (BmbVgaDma.scala ↔ VgaDma.java)

Status/control bit definitions for framebuffer DMA.

### What breaks

- Bit position mismatch: driver reads wrong status, misinterprets device
  state (e.g., thinks TX is ready when it's not → data loss).
- Register offset mismatch: driver accesses wrong register entirely.

### Validation

**NONE** — each register map is defined independently in SpinalHDL and Java.
Could be addressed by generating Java driver constants from SpinalHDL
`DeviceType.registerNames` and a new `registerBits` field.

---

## Dependency Graph

```
Board oscillator
  │
  ├─→ PLL config (vendor IP, manual)
  │     │
  │     └─→ JopConfig.clkFreq ──→ BmbUart divider
  │           │                 └─→ Sys prescaler
  │           │                 └─→ download.py (HARDCODED, no link)
  │           │
  │           └─→ JopCoreConfig
  │                 ├─→ pcWidth=12 ──→ ROM depth (4096)
  │                 │     └─→ Jopa.ADDRBITS (NO LINK)
  │                 ├─→ instrWidth=10 ──→ decode bit slices
  │                 │     └─→ Instruction.INSTLEN (NO LINK)
  │                 ├─→ jpcWidth=11 ──→ JBC RAM depth
  │                 │     └─→ MethodCache(jpcWidth, blockBits)
  │                 │           └─→ METHOD_SIZE_BITS (NO LINK to MAX_BC)
  │                 │           └─→ JOPConfig.CACHE_SIZE_WORDS (NO LINK)
  │                 ├─→ ramWidth=8 ──→ stack depth (256)
  │                 │     └─→ Jopa.RAM_LEN (NO LINK)
  │                 │     └─→ STACK_OFF=64 (NO LINK to Const.java)
  │                 ├─→ BytecodeConfig ──→ jump table patching
  │                 │     └─→ Instruction.java (NO LINK)
  │                 │     └─→ jvm.asm (NO LINK)
  │                 ├─→ devices ──→ ConstGenerator ──→ Const.java
  │                 │     └─→ Feature flags (HAS_ETHERNET, etc.)
  │                 │     └─→ NUM_INTERRUPTS
  │                 │     └─→ I/O addresses
  │                 └─→ memoryStyle ──→ ROM/RAM factories
  │                       └─→ .mif paths (HARDCODED)
  │
  ├─→ MemoryDevice (Parts.scala)
  │     ├─→ bankWidth, rowWidth, columnWidth ──→ SDRAM controller
  │     └─→ casLatency ──→ read timing
  │
  ├─→ JopMemoryConfig
  │     ├─→ addressWidth=24 ──→ BMB address width
  │     ├─→ mainMemSize ──→ usableMemWords
  │     │     └─→ stackRegionWordsPerCore ──→ spill base addr
  │     │           └─→ Startup.java heap end (NO LINK)
  │     └─→ JopIoSpace
  │           ├─→ SYS_BASE=0xF0 ──→ microcode (HARDCODED)
  │           ├─→ UART_BASE=0xEE ──→ microcode (HARDCODED)
  │           └─→ IoAddressAllocator ──→ ConstGenerator ──→ Const.java
  │
  ├─→ SpinalHDL device RTL (BmbEth, BmbSdNative, BmbVgaText, ...)
  │     └─→ Register bit definitions (NO LINK to Java drivers)
  │           └─→ EthMac.java, SdNative.java, VgaText.java, VgaDma.java
  │
  └─→ Java Toolchain
        ├─→ Jopa (assembler)
        │     ├─→ ADDRBITS=12 (NO LINK to pcWidth)
        │     ├─→ RAM_LEN=256 (NO LINK to ramWidth)
        │     └─→ ROM_LEN=4096 (NO LINK to pcWidth)
        ├─→ JOPizer (linker)
        │     ├─→ METHOD_MAX_SIZE=2048 (NO LINK to cache size)
        │     └─→ ClassStructConstants (NO LINK to GC.java)
        ├─→ JopSim (simulator)
        │     ├─→ MAX_MEM=262144 (NO LINK to mainMemSize)
        │     └─→ JOPConfig cache/timing (NO LINK to hardware)
        └─→ GC.java (runtime)
              └─→ HANDLE_SIZE=8, OFF_* fields (NO LINK to JOPizer)
```

Arrows marked **(NO LINK)** or **(HARDCODED)** are where mismatches can
occur silently.

---

## File Index

All files containing hardware-dependent constants:

### SpinalHDL (source of truth)
- `jop/types/JopConstants.scala` — METHOD_SIZE_BITS
- `jop/config/JopCoreConfig.scala` — pcWidth, instrWidth, jpcWidth, blockBits, ramWidth
- `jop/config/JopConfig.scala` — presets, clkFreq
- `jop/config/Parts.scala` — SDRAM timing, FPGA families
- `jop/memory/JopMemoryConfig.scala` — addressWidth, mainMemSize, burstLen
- `jop/memory/JopIoSpace.scala` — SYS_BASE, UART_BASE
- `jop/memory/MethodCache.scala` — methodSizeBits
- `jop/io/BmbUart.scala` — UART divider
- `jop/io/BmbEth.scala` — Ethernet register bits
- `jop/io/BmbSdNative.scala` — SD register bits
- `jop/io/BmbVgaText.scala` — VGA register bits
- `jop/io/BmbVgaDma.scala` — VGA DMA register bits
- `jop/generate/ConstGenerator.scala` — generates Const.java

### Java Tools (must match hardware)
- `com/jopdesign/tools/Jopa.java` — ADDRBITS, RAM_LEN, ROM_LEN
- `com/jopdesign/tools/Instruction.java` — INSTLEN, opcode table
- `com/jopdesign/tools/Cache.java` — MAX_BC, MAX_BC_MASK
- `com/jopdesign/tools/JopSim.java` — MAX_MEM, MAX_STACK, MIN_IO_ADDRESS
- `com/jopdesign/build/JOPizer.java` — METHOD_MAX_SIZE
- `com/jopdesign/build/ClassStructConstants.java` — CLS_HEAD, METH_STR
- `com/jopdesign/common/processormodel/JOPConfig.java` — cache/timing model

### Java Runtime (must match hardware)
- `com/jopdesign/sys/Const.java` — I/O addresses, exceptions, feature flags (generated)
- `com/jopdesign/sys/GC.java` — HANDLE_SIZE, OFF_* handle fields
- `com/jopdesign/sys/Startup.java` — heap fallback, MAX_STACK
- `com/jopdesign/sys/Memory.java` — IM_SIZE
- `com/jopdesign/hw/EthMac.java` — Ethernet status/control bits
- `com/jopdesign/hw/SdNative.java` — SD status/control bits
- `com/jopdesign/hw/VgaText.java` — VGA color palette, status bits
- `com/jopdesign/hw/VgaDma.java` — VGA DMA control bits
- `com/jopdesign/io/SerialPort.java` — UART status masks

### Microcode (must match hardware)
- `asm/src/jvm.asm` — I/O bipush values, handler labels, scratch offsets

### Scripts (must match hardware)
- `fpga/scripts/download.py` — baud rate
