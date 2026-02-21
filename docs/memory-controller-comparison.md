# Memory Controller Comparison: VHDL vs SpinalHDL

This document compares the VHDL `mem_sc.vhd` memory controller with the SpinalHDL `BmbMemoryController`.

## Overview

| Aspect | VHDL (mem_sc.vhd) | SpinalHDL (BmbMemoryController) |
|--------|-------------------|-------------------------------|
| Bus | SimpCon | BMB (Bus Master Bridge) |
| Caching | Method cache + Object cache | Method cache + Object cache |
| BC fill | Sequential | Pipelined + configurable burst |
| Exception detection | NPE, AIOOBE, IAE | Infrastructure exists, checks disabled |
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
| Null pointer detection | Disabled | Infrastructure exists, GC null handle scanning causes false positives |
| Array bounds checking | Not implemented | |
| SCJ scope checking | Not implemented | Not needed for standard Java |

## Remaining Differences from VHDL

### 1. Array Cache

**VHDL Component:** `acache`

**Purpose:** Caches array element values for fast repeated access in loops.

**Current behavior:** Every `iaload`/`iastore` goes through the full handle-dereference state machine.

**Impact:** Performance -- loops over arrays are slower than with cache.

### 2. Null Pointer Exception Detection

**VHDL State:** `npexc`

**Purpose:** Hardware detection of null pointer dereference.

**Current status:** The exception detection infrastructure exists in the memory controller but checks are disabled. The VHDL bounds check is also dead code in the original. Re-enabling requires fixing GC.java's null handle scanning which triggers false NPE exceptions.

### 3. Array Bounds Exception Detection

**VHDL State:** `abexc`

**Purpose:** Hardware detection of array index out of bounds.

**Current behavior:** Out-of-bounds access reads/writes wrong memory silently.

### 4. SCJ Scope Checking

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
  npexc        - Null pointer exception   (disabled in SpinalHDL)
  abexc        - Array bounds exception   (not implemented)
  iaexc        - Illegal assignment (SCJ) (not implemented)
```

## File References

- SpinalHDL memory controller: `spinalhdl/src/main/scala/jop/memory/BmbMemoryController.scala`
- SpinalHDL method cache: `spinalhdl/src/main/scala/jop/memory/MethodCache.scala`
- SpinalHDL object cache: `spinalhdl/src/main/scala/jop/memory/ObjectCache.scala`
- VHDL reference: `/srv/git/jop/vhdl/memory/mem_sc.vhd`
- VHDL object cache: `/srv/git/jop/vhdl/cache/ocache.vhd`
