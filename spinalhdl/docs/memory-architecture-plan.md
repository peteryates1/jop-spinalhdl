# JOP SpinalHDL Memory Architecture Plan

## Overview

This document captures the design decisions and implementation plan for the JOP memory subsystem in SpinalHDL, with support for multiple memory backends and multicore configurations.

## Current State

### What's Implemented

The `JopSimulator.scala` has a basic memory controller with:
- Direct memory read/write (`stmra`/`stmwd`)
- Bytecode cache fill (`stbcrd`)
- Handle dereference for `getfield`/`putfield`/`iaload`/`iastore`
- IO address detection (top 2 bits = "11")
- Simple block RAM backend (single-cycle, simulation only)

### What's Missing (from VHDL `mem_sc.vhd`)

| Feature | Priority | Notes |
|---------|----------|-------|
| SimpCon bus protocol | High | Variable latency handshaking |
| Null pointer detection | High | Hardware NPE |
| Array bounds checking | High | Hardware AIOOBE |
| Method cache | Medium | Avoid reloading cached methods |
| Object cache | Medium | Fast repeated field access |
| Copy operation | Low | GC support |
| Atomic operations | Low | Synchronized blocks |

See `docs/memory-controller-comparison.md` for full details.

## Architecture Decision: BMB Bus

### Why BMB (Bus Matrix Backbone)?

We evaluated three bus interface options:

| Option | Pros | Cons |
|--------|------|------|
| **SimpCon** | Matches VHDL, simple | No SpinalHDL ecosystem support |
| **BMB** | Native SpinalHDL, built-in arbitration, SDRAM controllers | Learning curve |
| **AXI4** | Industry standard | Overkill for JOP, complex |

**Decision: Use BMB** because:

1. **Built-in arbitration** - `BmbArbiter` for multicore support
2. **Built-in memory controllers** - `BmbOnChipRam`, `BmbSdramCtrl`, `xdr.CtrlWithPhy`
3. **Unified interface** - Same port for BRAM, SDR, DDR2, DDR3
4. **Address decoding** - `BmbDecoder` for memory map
5. **Atomic operations** - `BmbExclusiveMonitor` for Java synchronized
6. **Future-proof** - Compatible with VexRiscv ecosystem

### BMB Interface Summary

```scala
// Master sends commands
case class BmbCmd() extends Bundle {
  val source   = UInt()      // Transaction source ID
  val opcode   = Bits()      // Read/Write
  val address  = UInt()      // Memory address
  val length   = UInt()      // Burst length
  val data     = Bits()      // Write data
  val mask     = Bits()      // Byte enables
  val context  = Bits()      // User context (returned with response)
}

// Slave sends responses
case class BmbRsp() extends Bundle {
  val source   = UInt()      // Matches cmd.source
  val opcode   = Bits()      // Success/Error
  val data     = Bits()      // Read data
  val context  = Bits()      // Returned from cmd
}
```

## Target Memory Backends

| Backend | Use Case | SpinalHDL Component |
|---------|----------|---------------------|
| Block RAM | Simulation, small FPGAs | `BmbOnChipRam` |
| SDR SDRAM | Intel/Altera boards | `BmbSdramCtrl` |
| DDR2 SDRAM | Intel/Altera boards | `xdr.CtrlWithPhy` + custom PHY |
| DDR3 SDRAM | Xilinx Alchitry Au V2 | `xdr.CtrlWithPhy` + `XilinxS7Phy` |

## System Architecture

### Single Core

```
┌─────────────┐     ┌───────────────┐     ┌─────────────┐     ┌──────────────┐
│  JOP Core   │────▶│   mem_sc      │────▶│  BMB Master │────▶│  Memory      │
│  (stack,    │     │  (state mach, │     │  Interface  │     │  Backend     │
│   decode)   │     │   caches)     │     │             │     │              │
└─────────────┘     └───────────────┘     └─────────────┘     └──────────────┘
      │                    │
      │                    ▼
      │              ┌───────────┐
      └─────────────▶│  JBC RAM  │ (bytecode cache - internal)
                     └───────────┘
```

### Multicore

```
┌─────────┐   ┌─────────┐
│ JOP 0   │   │ mem_sc  │──┐
│ (core)  │──▶│ (cache) │  │
└─────────┘   └─────────┘  │
                           │     ┌─────────────┐     ┌─────────────┐
┌─────────┐   ┌─────────┐  ├────▶│ BmbArbiter  │────▶│ BmbDecoder  │
│ JOP 1   │   │ mem_sc  │──┤     │ (N masters) │     │ (mem map)   │
│ (core)  │──▶│ (cache) │  │     └─────────────┘     └──────┬──────┘
└─────────┘   └─────────┘  │                                │
                           │          ┌─────────────────────┼─────────────────────┐
┌─────────┐   ┌─────────┐  │          │                     │                     │
│ JOP N   │   │ mem_sc  │──┘          ▼                     ▼                     ▼
│ (core)  │──▶│ (cache) │       ┌───────────┐        ┌───────────┐         ┌───────────┐
└─────────┘   └─────────┘       │ Main RAM  │        │ IO Space  │         │ Scratch   │
                                │ (SDRAM/   │        │ (UART,    │         │ (fast     │
                                │  BRAM)    │        │  timers)  │         │  local)   │
                                └───────────┘        └───────────┘         └───────────┘
```

### Memory Map (matching VHDL)

| Address Range | Top 2 Bits | Description |
|---------------|------------|-------------|
| `0x00000000 - 0x3FFFFFFF` | `00` | Main memory (SDRAM/BRAM) |
| `0x40000000 - 0x7FFFFFFF` | `01` | Reserved |
| `0x80000000 - 0xBFFFFFFF` | `10` | Scratch pad (fast local memory) |
| `0xC0000000 - 0xFFFFFFFF` | `11` | IO space (UART, timers, etc.) |

## Implementation Plan

### Phase 1: BMB Infrastructure

**Goal:** Create BMB interface types and basic connectivity

1. **Create `BmbTypes.scala`**
   - Define BMB parameter case classes
   - Helper functions for common configurations

2. **Create `MemoryController.scala`**
   - Refactor `mem_sc` logic from JopSimulator
   - Add BMB master interface
   - Keep existing handle dereference state machine

3. **Create `BmbBlockRam.scala`**
   - Simple wrapper using `BmbOnChipRam`
   - For simulation and testing

### Phase 2: Single Core Integration

**Goal:** JOP core running with BMB memory

4. **Create `JopCore.scala`**
   - Extract core pipeline from JopSimulator
   - Clean interface to memory controller

5. **Create `JopSystem.scala`**
   - Instantiate core + memory controller
   - Connect via BMB
   - Configurable memory backend

6. **Update simulation testbench**
   - Verify HelloWorld still runs
   - Compare cycle counts with current implementation

### Phase 3: SDRAM Support

**Goal:** Run on real hardware with SDRAM

**Target:** QMTECH EP4CGX150 + Daughter Board with W9825G6JH6 SDR SDRAM

7. **Create `JopSystemWithSdram.scala`**
   - Wrap SpinalHDL's `BmbSdramCtrl` with W9825G6JH6 configuration
   - Use SpinalHDL's `SdramModel` for simulation
   - Run HelloWorld.jop in simulation with SDRAM timing

8. **Create SDRAM simulation test**
   - Use `spinal.lib.memory.sdram.sdr.sim.SdramModel`
   - Verify read/write timing matches W9825G6JH6 datasheet
   - Run HelloWorld.jop with SDRAM simulation model

9. **Create FPGA top-level for QMTECH EP4CGX150**
   - Target: `EP4CGX150DF27I7`
   - SDRAM: W9825G6JH6 (32MB, 16-bit, SDR)
   - Pin assignments from: `/home/peter/git/jop/quartus/qmtech-ep4cgx150gx/jop.qsf`
   - PLL: 50MHz input → system clock + SDRAM clock (phase shifted)
   - I/O:
     - UART: CP2102N (ser_txd/ser_rxd)
     - LEDs: Core board (2), Daughter board (5), PMOD J10/J11 (16)
     - Switches: Core board (2), Daughter board (5)
     - 7-segment display: 3-digit on daughter board
   - Reference: `/home/peter/git/jop/quartus/qmtech-ep4cgx150gx/`

10. **Verify on hardware**
    - Program FPGA via USB-Blaster
    - Run HelloWorld.jop
    - Verify UART output and watchdog LED toggle

### Phase 4: Multicore

**Goal:** Multiple JOP cores sharing memory

10. **Create `JopMulticore.scala`**
    - Parameterized core count
    - `BmbArbiter` for memory access
    - Per-core scratch memory (optional)

11. **Add atomic operation support**
    - `BmbExclusiveMonitor` for synchronized blocks
    - Test with multicore Java programs

### Phase 5: DDR Support (Future)

12. **DDR2 for Intel/Altera**
    - Custom PHY or vendor IP wrapper

13. **DDR3 for Xilinx**
    - Use `XilinxS7Phy` with `xdr.CtrlWithPhy`
    - Target Alchitry Au V2

## File Structure

```
core/spinalhdl/src/main/scala/jop/
├── core/
│   ├── JopCore.scala          # CPU core (fetch, decode, execute)
│   └── ...
├── memory/
│   ├── BmbTypes.scala         # BMB interface definitions
│   ├── MemoryController.scala # mem_sc equivalent
│   ├── BmbBlockRam.scala      # Block RAM backend
│   ├── BmbSdrSdram.scala      # SDR SDRAM backend
│   └── BmbDdr3.scala          # DDR3 backend (future)
├── system/
│   ├── JopSystem.scala        # Single core system
│   └── JopMulticore.scala     # Multicore system
└── JopSimulator.scala         # Current integrated simulator (keep for now)
```

## Testing Strategy

| Phase | Test Method | Success Criteria |
|-------|-------------|------------------|
| 1 | Unit tests | BMB transactions work |
| 2 | HelloWorld simulation | Same output as current |
| 3 | FPGA test | HelloWorld on real board |
| 4 | Multicore simulation | Multiple cores run concurrently |
| 5 | DDR3 FPGA test | HelloWorld on Alchitry Au V2 |

## Open Questions

1. **Method cache location** - Inside mem_sc or separate component?
2. **Object cache** - Implement now or defer?
3. **IO bridge** - Use BMB for IO too, or keep separate?
4. **Clock domains** - Single clock or CDC for memory?

## References

### JOP VHDL Sources
- Memory controller: `/home/peter/git/jop/vhdl/memory/mem_sc.vhd`
- SDR SDRAM controller: `/home/peter/git/jop/vhdl/memory/sc_sdram_sdr_16Mx16.vhd`
- Arbiters: `/home/peter/git/jop/vhdl/simpcon/sc_arbiter_*.vhd`
- SimpCon package: `/home/peter/git/jop/vhdl/simpcon/sc_pack.vhd`

### SpinalHDL Documentation
- [BMB Source Code](https://github.com/SpinalHDL/SpinalHDL/blob/dev/lib/src/main/scala/spinal/lib/bus/bmb/Bmb.scala)
- [BMB Spec (WIP)](https://github.com/SpinalHDL/SaxonSoc#bmb-spec-wip)
- [SaxonSoc BMB Issue](https://github.com/SpinalHDL/SaxonSoc/issues/15) - Discussion of BMB design
- [Bus Slave Factory](https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/bus_slave_factory.html)
- [SDRAM Controllers](https://github.com/SpinalHDL/SpinalHDL/tree/dev/lib/src/main/scala/spinal/lib/memory/sdram)

### BMB Key Characteristics
From SpinalHDL maintainer (Dolu1990):
- Simpler than AXI4
- Slaves don't need to implement burst support or unaligned access
- Context feature enables stateless adapters
- Supports out-of-order transactions
- Covers both cached and cacheless SoC designs

### Project Documentation
- Memory controller comparison: `docs/memory-controller-comparison.md`
- This architecture plan: `docs/memory-architecture-plan.md`

## Target Hardware: QMTECH EP4CGX150

### Board Details
- **FPGA:** Cyclone IV GX EP4CGX150DF27I7
- **Clock:** 50MHz oscillator
- **SDRAM:** W9825G6JH6 (32MB, 16-bit, SDR)
  - 4 banks, 13-bit row, 9-bit column
  - 143MHz max clock (we'll use ~100MHz)
  - SpinalHDL has pre-defined timing: `W9825G6JH6.timingGrade7`

### Daughter Board (DB_FPGA)
- **UART:** CP2102N USB-to-Serial
- **LEDs:** 5 additional LEDs
- **Switches:** 5 additional switches
- **7-Segment:** 3-digit display
- **PMOD:** J10 and J11 headers (used for LED boards showing WD signals)
- **Ethernet:** RTL8211EG (future use)
- **SD Card:** MicroSD slot (future use)

### Pin Mapping Summary
```
# Clock
clk_in        → PIN_B14 (50MHz)

# SDRAM (directly from QSF)
dram_ctrl.cs_n  → PIN_H26
dram_ctrl.cke   → PIN_K24
dram_ctrl.clk   → PIN_E22
dram_ctrl.we_n  → PIN_G25
dram_ctrl.ras_n → PIN_H25
dram_ctrl.cas_n → PIN_G26
dram_ctrl.ba[1:0]   → PIN_J26, J25
dram_ctrl.addr[12:0] → see jop.qsf
dram_ctrl.dqm[1:0]  → PIN_H24, F26
dram_data[15:0]     → see jop.qsf

# UART
ser_rxd → PIN_AE21
ser_txd → PIN_AD20

# LEDs (active high)
led[0:1] → Core board (PIN_A25, A24)
led[2:6] → Daughter board (AD14, AC14, AD15, AC15, AE15)

# PMOD LEDs (active high)
pmod_j10[1:8] → See jop.qsf (watchdog indicators)
pmod_j11[1:8] → See jop.qsf (watchdog indicators)
```

### Reference Files
- VHDL Reference: `/home/peter/git/jop/quartus/qmtech-ep4cgx150gx/`
- Core Board Docs: `/home/peter/git/EP4CGX150DF27_CORE_BOARD/`
- Daughter Board Docs: `/home/peter/git/DB_FPGA/`
