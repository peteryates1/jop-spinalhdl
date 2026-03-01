# Memory Controller Comparison: VHDL vs SpinalHDL

This document compares the VHDL `mem_sc.vhd` memory controller with the SpinalHDL `BmbMemoryController`.

## Overview

| Aspect | VHDL (mem_sc.vhd) | SpinalHDL (BmbMemoryController) |
|--------|-------------------|-------------------------------|
| Bus | SimpCon | BMB (Bus Master Bridge) |
| Caching | Method cache + Object cache | Method cache + Object cache |
| BC fill | Sequential | Pipelined + configurable burst |
| Exception detection | NPE, AIOOBE, IAE | NPE + AIOOBE enabled, IAE not needed |
| Copy operation | Sequential | Pipelined (CP_SETUP/READ/WRITE) |

## Feature Status

| Feature | Status | Notes |
|---------|--------|-------|
| Basic read/write (stmra/stmwd) | Done | BMB single-word access |
| Bytecode cache fill (stbcrd) | Done | Pipelined, with configurable burst reads |
| Method cache | Done | 16-block tag-only, FIFO replacement |
| Object cache | Done | 16-entry fully-associative, FIFO, 8 fields per entry |
| getfield/putfield | Done | Handle dereference state machine |
| iaload/iastore | Done | Handle dereference state machine |
| IO address decode | Done | Top 2 bits = "11" for IO, HANDLE_ACCESS I/O routing |
| Hardware memCopy | Done | CP_SETUP/CP_READ/CP_WRITE states for GC |
| getstatic/putstatic | Done | Treated as regular memory ops (address pre-computed by linker) |
| Null pointer detection | Done | Hardware NPE on handle address 0 (GC.push null guard fixes false positives) |
| Array bounds checking | Done | Negative index (MSB check) + upper bounds (compare index >= array length) |
| SCJ scope checking | Not implemented | Not needed for standard Java |

## Remaining Differences from VHDL

### 1. SCJ Scope Checking

**VHDL State:** `iaexc`

**Purpose:** Illegal assignment detection for Safety-Critical Java memory scopes.

**Current status:** Not implemented. Only needed for SCJ compliance, not standard Java.

## VHDL State Machine Reference

```
Basic Operations:
  idl          - Idle, waiting for operation
  rd1          - Simple memory read
  wr1          - Simple memory write
  last         - Operation complete

Bytecode/Method Cache:
  bc_cc        - Check method cache      (SpinalHDL: BC_CACHE_CHECK)
  bc_r1        - Read first word          (SpinalHDL: BC_FILL_R1)
  bc_w         - Wait for read            (SpinalHDL: BC_FILL_LOOP)
  bc_rn        - Read next word           (SpinalHDL: BC_FILL_CMD)
  bc_wr        - Write to JBC
  bc_wl        - Write last word

Array Load (iaload):
  iald0        - Start array load         (SpinalHDL: HANDLE_READ)
  iald1        - Read array handle        (SpinalHDL: HANDLE_WAIT)
  iald2        - Wait for handle
  iald3        - Calculate element addr   (SpinalHDL: HANDLE_CALC)
  iald4        - Read element             (SpinalHDL: HANDLE_ACCESS)

Array Store (iastore):
  iast0        - Start array store        (SpinalHDL: IAST_WAIT)

getfield:
  gf0          - Start getfield           (SpinalHDL: HANDLE_READ)
  gf1          - Read handle
  gf2          - Wait for handle
  gf3          - Read field
  gf4          - Field read complete

putfield:
  pf0          - Start putfield           (SpinalHDL: HANDLE_READ)
  pf1          - Read handle
  pf2          - Wait for handle
  pf3          - Calculate field address
  pf4          - Write field

Copy Operation:
  cp0          - Start copy               (SpinalHDL: CP_SETUP)
  cp1          - Read source              (SpinalHDL: CP_READ)
  cp2          - Wait for read            (SpinalHDL: CP_READ_WAIT)
  cp3          - Write destination        (SpinalHDL: CP_WRITE)
  cp4          - Check completion
  cpstop       - Copy complete            (SpinalHDL: CP_STOP)

Exceptions:
  npexc        - Null pointer exception   (SpinalHDL: NP_EXC)
  abexc        - Array bounds exception   (SpinalHDL: AB_EXC via HANDLE_BOUND states)
  iaexc        - Illegal assignment (SCJ) (not implemented)
```

## File References

- SpinalHDL memory controller: `spinalhdl/src/main/scala/jop/memory/BmbMemoryController.scala`
- SpinalHDL method cache: `spinalhdl/src/main/scala/jop/memory/MethodCache.scala`
- SpinalHDL object cache: `spinalhdl/src/main/scala/jop/memory/ObjectCache.scala`
- VHDL reference: `/srv/git/jop/vhdl/memory/mem_sc.vhd`
- VHDL object cache: `/srv/git/jop/vhdl/cache/ocache.vhd`
