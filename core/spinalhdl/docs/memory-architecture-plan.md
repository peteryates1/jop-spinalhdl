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

7. **Create `SdrSdramBackend.scala`**
   - Wrap SpinalHDL's `BmbSdramCtrl`
   - Configuration for common chips (MT48LC16M16A2, etc.)

8. **Create FPGA top-level**
   - Intel/Altera DE2-115 or similar
   - Pin assignments, PLLs

9. **Test on hardware**
   - Verify timing
   - Debug any issues

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
- Memory controller: `/home/peter/git/jopmin/vhdl/memory/mem_sc.vhd`
- SDR SDRAM controller: `/home/peter/git/jopmin/vhdl/memory/sc_sdram_sdr_16Mx16.vhd`
- Arbiters: `/home/peter/git/jopmin/vhdl/simpcon/sc_arbiter_*.vhd`
- SimpCon package: `/home/peter/git/jopmin/vhdl/simpcon/sc_pack.vhd`

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
