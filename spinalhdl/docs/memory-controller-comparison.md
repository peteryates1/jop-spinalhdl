# Memory Controller Comparison: VHDL vs SpinalHDL

This document compares the VHDL `mem_sc.vhd` memory controller with the current SpinalHDL implementation in `JopSimulator.scala`.

## Overview

| Aspect | VHDL (mem_sc.vhd) | SpinalHDL (JopSimulator) |
|--------|-------------------|--------------------------|
| Lines of code | 1143 | ~200 (memory section) |
| States | 38 | 7 (HandleOpState) + 4 (BcFillState) |
| Caching | Method cache + Object cache | None |
| Exception detection | NPE, AIOOBE, IAE | None |

## Currently Implemented in SpinalHDL

| Feature | Status | Notes |
|---------|--------|-------|
| Basic read/write (stmra/stmwd) | ✅ | Direct memory access |
| Bytecode cache fill (stbcrd) | ✅ | Loads methods to JBC RAM |
| getfield/putfield | ✅ | Handle dereference state machine |
| iaload/iastore | ✅ | Handle dereference state machine |
| IO address decode | ✅ | Top 2 bits = "11" for IO |
| getstatic/putstatic | ⚠️ | Treated as regular memory ops |

## Missing Features (from VHDL mem_sc.vhd)

### 1. Method Cache

**VHDL States:** `bc_cc`, `bc_r1`, `bc_w`, `bc_rn`, `bc_wr`, `bc_wl`

**Purpose:** Block-based method cache that avoids reloading methods already in the bytecode cache.

**How it works:**
- `mcache` component tracks which methods are cached
- On `stbcrd`, checks if method is already in cache
- If hit: returns existing `bcstart` address immediately
- If miss: loads method from main memory

**Current behavior:** Every method invocation reloads bytecodes from main memory.

**Impact:** Performance - repeated method calls are slow.

### 2. Object Cache

**VHDL Component:** `ocache`

**Purpose:** Caches recently accessed object fields for fast repeated access.

**How it works:**
- Indexed by object handle + field index
- On getfield: check cache first, return cached value if hit
- On putfield: update cache and write to memory
- Invalidated on `stidx` or `cinval`

**Current behavior:** Every field access goes to main memory.

**Impact:** Performance - loops accessing object fields are slow.

### 3. Null Pointer Exception Detection

**VHDL State:** `npexc`

**Purpose:** Hardware detection of null pointer dereference.

**How it works:**
```vhdl
if addr_reg=0 then
    next_state <= npexc;
end if;
```
- Checked at start of getfield (gf0), putfield, iaload, iastore
- Sets `np_exc` output signal
- Triggers exception handling in CPU

**Current behavior:** Null pointer access reads/writes address 0 silently.

**Impact:** Correctness - null dereferences go undetected.

### 4. Array Bounds Exception Detection

**VHDL State:** `abexc`

**Purpose:** Hardware detection of array index out of bounds.

**How it works:**
- Array length read during iaload/iastore
- Index compared against length
- If `index >= length`, triggers `ab_exc`

**Current behavior:** Out-of-bounds access reads/writes wrong memory.

**Impact:** Correctness - buffer overflows go undetected.

### 5. Static Field Access (getstatic/putstatic)

**VHDL States:** `gs1`, `ps1`

**Purpose:** Access static fields through constant pool indirection.

**How it works:**
- `stgs` triggers getstatic sequence
- `stps` triggers putstatic sequence
- Address comes from constant pool lookup

**Current behavior:** May work as regular memory ops if address is pre-computed.

**Impact:** Unclear - needs testing.

### 6. Copy Operation

**VHDL States:** `cp0`, `cp1`, `cp2`, `cp3`, `cp4`, `cpstop`

**Purpose:** Hardware-assisted memory copy for GC and `System.arraycopy()`.

**How it works:**
- `mem_in.copy` triggers copy sequence
- Reads from source, writes to destination
- Handles address translation for GC compaction

**Current behavior:** Not implemented - would fall back to software loop.

**Impact:** Performance - GC and arraycopy are slow.

### 7. SCJ Scope Checking

**VHDL State:** `iaexc`

**Purpose:** Illegal assignment detection for Safety-Critical Java memory scopes.

**How it works:**
- Tracks memory region "levels"
- Prevents references from outer scope to inner scope
- Sets `ia_exc` on violation

**Current behavior:** Not implemented.

**Impact:** None for standard Java; required for SCJ compliance.

### 8. SimpCon Bus Protocol

**VHDL Signals:** `rdy_cnt`, `sc_mem_out`, `sc_mem_in`

**Purpose:** Proper handshaking with external memory controller.

**How it works:**
- `rdy_cnt` indicates cycles until data ready
- Supports variable latency memory (SRAM, SDRAM, etc.)
- `atomic` signal for synchronized blocks

**Current behavior:** Direct memory access assumes single-cycle latency.

**Impact:** Won't work with real external memory controllers.

### 9. Cache Control

**VHDL Signals:** `cinval`, `tm_cache`

**Purpose:** Cache coherency and transactional memory hints.

**How it works:**
- `cinval` invalidates object cache entries
- `tm_cache` hints for transactional memory

**Current behavior:** No caching, so no cache control needed.

**Impact:** None currently.

## VHDL State Machine Reference

```
Basic Operations:
  idl          - Idle, waiting for operation
  rd1          - Simple memory read
  wr1          - Simple memory write
  last         - Operation complete

Static Field Access:
  ps1          - putstatic
  gs1          - getstatic

Bytecode/Method Cache:
  bc_cc        - Check method cache
  bc_r1        - Read first word
  bc_w         - Wait for read
  bc_rn        - Read next word
  bc_wr        - Write to JBC
  bc_wl        - Write last word

Array Load (iaload):
  iald0        - Start array load
  iald1        - Read array handle
  iald2        - Wait for handle
  iald23       - Fast memory path
  iald3        - Calculate element address
  iald4        - Read element
  iasrd        - Array store read (shared)
  ialrb        - Load result to B register

Array Store (iastore):
  iast0        - Start array store
  iaswb        - Write back
  iasrb        - Read back
  iasst        - Store complete

getfield:
  gf0          - Start getfield (null check)
  gf1          - Read handle
  gf2          - Wait for handle
  gf3          - Read field
  gf4          - Field read complete

putfield:
  pf0          - Start putfield
  pf1          - Read handle
  pf2          - Wait for handle
  pf3          - Calculate field address
  pf4          - Write field

Copy Operation:
  cp0          - Start copy
  cp1          - Read source
  cp2          - Wait for read
  cp3          - Write destination
  cp4          - Check completion
  cpstop       - Copy complete

Exceptions:
  npexc        - Null pointer exception
  abexc        - Array bounds exception
  iaexc        - Illegal assignment (SCJ)
  excw         - Exception wait
```

## Implementation Priority

### High Priority (Correctness)

1. **Null pointer detection** - Silent failures are dangerous
2. **Array bounds checking** - Buffer overflows cause corruption

### Medium Priority (Performance)

3. **Method cache** - Significant speedup for method-heavy code
4. **Object cache** - Speedup for field-heavy loops

### Low Priority (Special Cases)

5. **Copy operation** - Only matters for GC-heavy workloads
6. **SimpCon protocol** - Only needed for real hardware
7. **SCJ scope checking** - Only for Safety-Critical Java
8. **Atomic operations** - Only for multi-threaded code

## File References

- VHDL implementation: `/home/peter/git/jopmin/vhdl/memory/mem_sc.vhd`
- SpinalHDL implementation: `core/spinalhdl/src/main/scala/jop/JopSimulator.scala`
- Method cache: `/home/peter/git/jopmin/vhdl/cache/mcache.vhd`
- Object cache: `/home/peter/git/jopmin/vhdl/cache/ocache.vhd`
