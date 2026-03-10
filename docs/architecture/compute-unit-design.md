# Compute Unit Interface Design

## Overview

Refactor the compute unit interface from the current JVM-bytecode-driven
approach to a decoupled architecture using three microcode instructions:
`stop`, `sthw`, and `ldop`. The compute unit has an internal operand stack,
eliminating the need to expand the TOS register file beyond 2 registers (A, B).

Four compute units share the interface: ICU (integer), FCU (float), LCU
(long), and DCU (double). The `sthw` instruction encodes a 6-bit operation
code that selects the unit and operation in hardware.

### Why Change the Current Implementation?

The current implementation (merged in commits 832cee8–ec9d339) uses the JVM
bytecode from `bcfetch.io.jinstr_out` as the CU opcode. This ties the CU
operation to whichever JVM bytecode triggered the microcode. If `fdiv`'s
microcode needs to use an integer multiply (e.g., for a Newton-Raphson step),
it cannot — the bytecode says `fdiv`, so the CU sees `fdiv`.

The 6-bit operand in `sthw` decouples CU operation selection from the JVM
bytecode entirely. Microcode for any bytecode can invoke any CU operation.
The internal operand stack (`stop`) also lets microcode build up operands
incrementally rather than requiring all values in specific stack positions.

### Implementation Status (2026-03-10)

The decoupled CU interface is fully implemented and verified:

- **All four CU cores** (ICU, FCU, LCU, DCU) implemented and tested
- **`ComputeUnitTop`** wraps all four units with shared 4-deep operand stack and result sequencing
- **Three microcode instructions**: `stop` (0x01F, POP), `sthw` (0x140+6bit, NOP), `ldop` (0x0E1, PUSH)
- **`ComputeUnitCoreBundle`** per-unit interface: `operands(4)`, `op(4-bit)`, `start`, `busy`, `resultLo/Hi`, `resultCount`
- **Pipeline wiring** in `JopPipeline.scala`: CU busy wired to `fetch.io.bsy` (wait-based stall)
- **Superset ROM**: Both `_hw` and `_sw` handlers coexist unconditionally; 13 `altEntries` for elaboration-time selection
- **52/52 BRAM JVM tests pass**, 568/570 unit tests pass

Bugs found and fixed during implementation:
- **LCU shift operand swap**: `operands(1)##operands(2)` gave val_lo as MSB; fixed to `operands(2)##operands(1)`
- **lneg operand order**: Push order swapped (value first into opbReg, 0L into opaReg) — was computing `value - 0` instead of `0 - value`
- **CU busy 1-cycle gap**: Added `io.start` to busy signal for immediate stall on dispatch cycle

## Instructions

### `stop` — Store Operand (to compute unit)

- **Encoding**: `0x01F` (POP class, was reserved shift slot)
- **Stack effect**: POP (pops TOS)
- **Action**: Pops TOS (A register) and pushes the value onto the compute
  unit's internal operand stack.

Each `stop` pops one 32-bit word from the JOP stack into the compute unit.
Multiple `stop` instructions load all operands before starting. For 32-bit
operations (int/float), 2 `stop` instructions load both operands. For 64-bit
operations (long/double), 4 `stop` instructions load both 64-bit operands
as pairs of 32-bit words.

### `sthw` — Start Hardware

- **Encoding**: `0x140` with 6-bit operand (NOP class, covers `0x140-0x17F`)
- **Stack effect**: NOP (no stack change)
- **Action**: The 6-bit operand encodes the compute unit and operation.
  Bits [5:4] select the unit (ICU/FCU/LCU/DCU), bits [3:0] select the
  operation within that unit. The CU begins the operation using the
  operands already loaded via `stop`. The CU's `busy` output stalls
  the pipeline until the result is ready.

### `ldop` — Load Operand (from compute unit)

- **Encoding**: `0x0E1` (PUSH class, replaces `ldmul`)
- **Stack effect**: PUSH (pushes onto TOS)
- **Action**: Pops the compute unit's internal result stack and pushes the
  value onto the JOP stack (into A register).

## Operation Encoding

The 6-bit `sthw` operand encodes both unit selection and operation:

```
Bits [5:4] = unit select    Bits [3:0] = operation
  00 = ICU (32-bit integer)   decoded within each unit
  01 = FCU (32-bit float)
  10 = LCU (64-bit long)
  11 = DCU (64-bit double)
```

Hardware decode is a 2-bit mux followed by a 4-bit operation decoder:

```scala
val unitSel = io.opcode(5 downto 4)  // route start to correct unit
val op      = io.opcode(3 downto 0)  // decoded within the selected unit
```

### ICU — Integer Compute Unit (sthw 0-15)

| Opcode | Operation | Operands | Results | Description              |
|-------:|-----------|:--------:|:-------:|--------------------------|
|      0 | imul      |    2     |    1    | 32×32→32 multiply        |
|      1 | idiv      |    2     |    1    | signed divide            |
|      2 | irem      |    2     |    1    | signed remainder         |
|   3-15 | reserved  |          |         |                          |

### FCU — Float Compute Unit (sthw 16-31)

| Opcode | Operation | Operands | Results | Description              |
|-------:|-----------|:--------:|:-------:|--------------------------|
|     16 | fadd      |    2     |    1    | float add                |
|     17 | fsub      |    2     |    1    | float subtract           |
|     18 | fmul      |    2     |    1    | float multiply           |
|     19 | fdiv      |    2     |    1    | float divide             |
|     20 | fcmpl     |    2     |    1    | float compare (-1 on NaN)|
|     21 | fcmpg     |    2     |    1    | float compare (+1 on NaN)|
|     22 | i2f       |    1     |    1    | int→float                |
|     23 | f2i       |    1     |    1    | float→int (truncate)     |
|  24-31 | reserved  |          |         |                          |

### LCU — Long Compute Unit (sthw 32-47)

| Opcode | Operation | Operands | Results | Description              |
|-------:|-----------|:--------:|:-------:|--------------------------|
|     32 | ladd      |    4     |    2    | long add                 |
|     33 | lsub      |    4     |    2    | long subtract            |
|     34 | lmul      |    4     |    2    | long multiply            |
|     35 | ldiv      |    4     |    2    | long signed divide       |
|     36 | lrem      |    4     |    2    | long signed remainder    |
|     37 | lcmp      |    4     |    1    | long compare             |
|     38 | lshl      |    3     |    2    | long shift left          |
|     39 | lshr      |    3     |    2    | long arithmetic shift right |
|     40 | lushr     |    3     |    2    | long logical shift right |
|  41-47 | reserved  |          |         |                          |

Long shifts take 3 operands: shift amount (32-bit int), then value
(64-bit long as two 32-bit words).

### DCU — Double Compute Unit (sthw 48-63)

| Opcode | Operation | Operands | Results | Description              |
|-------:|-----------|:--------:|:-------:|--------------------------|
|     48 | dadd      |    4     |    2    | double add               |
|     49 | dsub      |    4     |    2    | double subtract          |
|     50 | dmul      |    4     |    2    | double multiply          |
|     51 | ddiv      |    4     |    2    | double divide            |
|     52 | dcmpl     |    4     |    1    | double compare (-1 on NaN)|
|     53 | dcmpg     |    4     |    1    | double compare (+1 on NaN)|
|     54 | f2d       |    1     |    2    | float→double             |
|     55 | d2f       |    2     |    1    | double→float             |
|     56 | i2d       |    1     |    2    | int→double (lossless)    |
|     57 | d2i       |    2     |    1    | double→int (truncate)    |
|     58 | l2d       |    2     |    2    | long→double              |
|     59 | d2l       |    2     |    2    | double→long (truncate)   |
|  60-63 | reserved  |          |         |                          |

Cross-width conversions (i2d, d2i, l2d, d2l, f2d, d2f) are placed in the
DCU because they require double-precision hardware. The operand and result
counts vary per operation; the CU knows from the opcode how many to expect.

### Operations that stay in microcode (no CU variant)

These operations have no hardware benefit or are trivial:

| Operation      | Microcode cycles | Rationale                          |
|----------------|:----------------:|------------------------------------|
| land, lor, lxor|        8         | Same as CU path (4 stop+sthw+2 wait+2 ldop) |
| i2l            |        4         | Sign-extend (dup, shr 31)          |
| l2i            |        3         | Truncate (stm, pop, ldm)           |
| fneg, dneg     |      3-5         | Flip sign bit                      |

Note: `lneg` has both HW and SW variants. The HW variant pushes 0L then the
value, uses `sthw CU_LSUB` (0 - value). The SW variant uses the original
microcode invert + add 1 approach. Selected via `altEntries` like other ops.

## Microcode Sequences

### 32-bit, 2 operands, 1 result (imul, fadd, fsub, fmul, fdiv, ...)

```asm
                            ;  JOP stack             CU stack
                            ;  ..., value1, value2   (empty)
imul:
        stop                ;  ..., value1            value2
        stop                ;  ...                    value2, value1
        sthw 0              ;  start ICU multiply, busy stalls via bsy
        wait                ;  re-fetches while CU busy
        wait                ;  second wait for result stability
        ldop nxt            ;  ..., result            (empty)

fadd:
        stop                ;  ..., value1            value2
        stop                ;  ...                    value2, value1
        sthw 16             ;  start FCU add, busy stalls via bsy
        wait
        wait
        ldop nxt            ;  ..., result            (empty)
```

### 32-bit, 2 operands, 1 result (fcmpl, fcmpg)

```asm
fcmpl:
        stop
        stop
        sthw 20             ;  start FCU compare (returns -1 on NaN)
        ldop nxt
```

### 32-bit, 1 operand, 1 result (i2f, f2i)

```asm
i2f:
        stop                ;  pop int → CU
        sthw 22             ;  start FCU i2f
        ldop nxt            ;  push float result

f2i:
        stop                ;  pop float → CU
        sthw 23             ;  start FCU f2i
        ldop nxt            ;  push int result
```

### 64-bit, 4 operands, 2 results (ladd, lsub, dadd, dsub, ...)

```asm
                            ;  JOP stack                    CU stack
                            ;  ..., a_hi, a_lo, b_hi, b_lo (empty)
ladd:
        stop                ;  ..., a_hi, a_lo, b_hi       b_lo
        stop                ;  ..., a_hi, a_lo              b_lo, b_hi
        stop                ;  ..., a_hi                    b_lo, b_hi, a_lo
        stop                ;  ...                          b_lo, b_hi, a_lo, a_hi
        sthw 32             ;  start LCU add, busy stalls via bsy
        wait                ;  re-fetches while CU busy
        wait                ;  second wait for result stability
        ldop                ;  ..., result_hi
        ldop nxt            ;  ..., result_hi, result_lo

dadd:
        stop
        stop
        stop
        stop
        sthw 48             ;  start DCU add
        wait
        wait
        ldop
        ldop nxt
```

### 64-bit, 4 operands, 1 result (lcmp, dcmpl, dcmpg)

```asm
lcmp:
        stop
        stop
        stop
        stop
        sthw 37             ;  start LCU compare
        wait
        wait
        ldop nxt            ;  ..., result (-1, 0, or 1)
```

### Long shifts — 3 operands (int + long), 2 results

```asm
                            ;  JOP stack                    CU stack
                            ;  ..., val_hi, val_lo, amount  (empty)
lshl:
        stop                ;  ..., val_hi, val_lo          amount
        stop                ;  ..., val_hi                  amount, val_lo
        stop                ;  ...                          amount, val_lo, val_hi
        sthw 38             ;  start LCU shift left
        wait
        wait
        ldop                ;  ..., result_hi
        ldop nxt            ;  ..., result_hi, result_lo

lushr:
        stop
        stop
        stop
        sthw 40             ;  start LCU logical shift right
        wait
        wait
        ldop
        ldop nxt
```

### Cross-width conversions

```asm
; 1 operand (32-bit) → 2 results (64-bit)
i2d:
        stop                ;  pop int → CU
        sthw 56             ;  start DCU i2d (lossless: 32-bit int fits in 53-bit mantissa)
        wait
        wait
        ldop                ;  push double_hi
        ldop nxt            ;  push double_lo

f2d:
        stop                ;  pop float → CU
        sthw 54             ;  start DCU f2d
        wait
        wait
        ldop                ;  push double_hi
        ldop nxt            ;  push double_lo

; 2 operands (64-bit) → 1 result (32-bit)
d2i:
        stop                ;  pop double_lo → CU
        stop                ;  pop double_hi → CU
        sthw 57             ;  start DCU d2i
        wait
        wait
        ldop nxt            ;  push int result

d2f:
        stop                ;  pop double_lo → CU
        stop                ;  pop double_hi → CU
        sthw 55             ;  start DCU d2f
        wait
        wait
        ldop nxt            ;  push float result

; 2 operands (64-bit) → 2 results (64-bit)
l2d:
        stop                ;  pop long_lo → CU
        stop                ;  pop long_hi → CU
        sthw 58             ;  start DCU l2d
        wait
        wait
        ldop                ;  push double_hi
        ldop nxt            ;  push double_lo

d2l:
        stop                ;  pop double_lo → CU
        stop                ;  pop double_hi → CU
        sthw 59             ;  start DCU d2l
        wait
        wait
        ldop                ;  push long_hi
        ldop nxt            ;  push long_lo
```

## Compute Unit Bundle

### New Interface

```scala
case class ComputeUnitBundle() extends Bundle {
  val din    = in UInt(32 bits)     // data in (wired to A register / TOS)
  val push   = in Bool()            // stop: push din onto internal operand stack
  val opcode = in UInt(6 bits)      // sthw operand: ir(5 downto 0)
  val start  = in Bool()            // sthw: latch opcode + start operation
  val dout   = out UInt(32 bits)    // data out (top of internal result stack)
  val pop    = in Bool()            // ldop: pop internal result stack
  val busy   = out Bool()           // stall pipeline until operation complete
}
```

### Old Interface (replaced)

The old interface sampled 4 registers simultaneously on `wr` and used the 8-bit JVM bytecode for operation selection. This has been fully replaced by the new decoupled interface above.

```scala
// REMOVED — replaced by ComputeUnitBundle (din/push/pop) + ComputeUnitCoreBundle (per-unit)
case class OldComputeUnitBundle() extends Bundle {
  val a        = in UInt (32 bits)   // TOS
  val b        = in UInt (32 bits)   // NOS
  val c        = in UInt (32 bits)   // TOS-2
  val d        = in UInt (32 bits)   // TOS-3
  val wr       = in Bool ()          // sthw asserted
  val opcode   = in Bits (8 bits)    // JVM bytecode
  val resultLo = out UInt (32 bits)
  val resultHi = out UInt (32 bits)
  val is64     = out Bool ()
  val busy     = out Bool ()
}
```

### Key Differences

| Aspect             | Current (a/b/c/d)              | New (din/push/pop)              |
|--------------------|--------------------------------|---------------------------------|
| Operand delivery   | 4 registers sampled on `wr`    | Sequential via `stop` + internal stack |
| Operation select   | 8-bit JVM bytecode             | 6-bit sthw operand (unit + op)  |
| Result retrieval   | `resultLo`/`resultHi` + `is64` | Sequential `ldop` pops from result stack |
| Unit routing       | `lastWasFloat` register mux    | `opcode[5:4]` hardware mux      |
| Decoupling         | Tied to JVM bytecode           | Any microcode can invoke any CU op |

### Top-Level Dispatch

The top-level compute unit dispatches to the individual units based on
`opcode[5:4]` and routes the shared `din`/`push`/`pop` signals:

```scala
class ComputeUnitTop extends Component {
  val io = ComputeUnitBundle()

  val icu = new IntegerComputeUnit()
  val fcu = new FloatComputeUnit()
  val lcu = new LongComputeUnit()
  val dcu = new DoubleComputeUnit()

  val unitSel = io.opcode(5 downto 4)

  // Shared operand stack — all units share one stack since only one
  // can be active at a time
  val opStack = Vec(Reg(UInt(32 bits)) init(0), 4)
  val opSp    = Reg(UInt(3 bits)) init(0)

  when(io.push) {
    opStack(opSp.resize(2)) := io.din
    opSp := opSp + 1
  }

  // Route start to selected unit
  icu.io.start := io.start && unitSel === 0
  fcu.io.start := io.start && unitSel === 1
  lcu.io.start := io.start && unitSel === 2
  dcu.io.start := io.start && unitSel === 3

  // All units see the same operands and operation
  for (unit <- Seq(icu, fcu, lcu, dcu)) {
    unit.io.op := io.opcode(3 downto 0)
    unit.io.operands := opStack
  }

  // Reset operand stack on start
  when(io.start) { opSp := 0 }

  // Result mux — only one unit is active
  io.dout := unitSel.mux(
    0 -> icu.io.dout,
    1 -> fcu.io.dout,
    2 -> lcu.io.dout,
    3 -> dcu.io.dout
  )

  // Include io.start in busy for immediate stall on dispatch cycle
  io.busy := icu.io.busy || fcu.io.busy || lcu.io.busy || dcu.io.busy || io.start
}
```

### Internal Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     ComputeUnitTop                           │
│                                                              │
│  din ──push──→ ┌──────────┐                                  │
│   (stop)       │ opStack  │  shared operand stack (4 deep)   │
│                │ [3][2]   │                                  │
│                │ [1][0]   │                                  │
│                └────┬─────┘                                  │
│                     │ operands                               │
│    opcode ──→ ┌─────┴──────────────────────────────────┐     │
│    [5:4]      │         unit select (2-bit mux)        │     │
│               ├────────┬────────┬────────┬─────────────┤     │
│               │  ICU   │  FCU   │  LCU   │    DCU      │     │
│               │ [3:0]  │ [3:0]  │ [3:0]  │   [3:0]     │     │
│               │ imul   │ fadd   │ ladd   │   dadd      │     │
│    start ────→│ idiv   │ fsub   │ lsub   │   dsub      │     │
│    (sthw)     │ irem   │ fmul   │ lmul   │   dmul      │     │
│               │        │ fdiv   │ ldiv   │   ddiv      │     │
│               │        │ fcmpl  │ lrem   │   dcmpl     │     │
│               │        │ fcmpg  │ lcmp   │   dcmpg     │     │
│               │        │ i2f    │ lshl   │   f2d       │     │
│               │        │ f2i    │ lshr   │   d2f       │     │
│               │        │        │ lushr  │   i2d       │     │
│               │        │        │        │   d2i       │     │
│               │        │        │        │   l2d       │     │
│               │        │        │        │   d2l       │     │
│               ├────────┴────────┴────────┴─────────────┤     │
│               │         result mux (2-bit)             │     │
│               └────────────────┬───────────────────────┘     │
│                                │                             │
│  dout ←──pop──  result stack   │                             │
│   (ldop)        (2-4 deep)     │                             │
│                                                              │
│  busy ──────────────────────────────────────────────────────→│
└──────────────────────────────────────────────────────────────┘
```

## Hardware Integration (JopPipeline)

### Decode Changes

Changes to `DecodeStage.scala`:

1. **`stop`** at `0x01F`: New POP instruction in the shift range. Add decode
   case `is(B"10'b0000011111")`. Generates `cuPush` signal (1 bit). No ALU
   or shifter operation — just pops TOS into the CU.

2. **`sthw`** at `0x140-0x17F`: New NOP instruction with 6-bit operand.
   Decoded by `ir(9 downto 6) === B"4'b0101"` (NOP class, currently unused).
   Generates `cuStart` signal (1 bit). The 6-bit operand `ir(5 downto 0)`
   is passed to the CU as the operation code. `enaA := False` (no A
   register update).

3. **`ldop`** at `0x0E1`: Already decoded as `ldmul` with PUSH behavior.
   The `din` mux selects `ir(1:0) == 1` for this slot. Change the mux
   input from `cuResultLo` to `cu.io.dout`. Add `cuPop` signal (1 bit).

4. **Remove `hwWr`**: The current `hwWr` signal (asserted for `sthw` at
   `0x040`) is replaced by `cuStart` (for `sthw` at `0x140`). The `0x040`
   MMU slot becomes available for reuse.

5. **`ldmulh`** at `0x0E3`: Currently wires to `cuResultHi`. With the new
   interface, multi-word results are retrieved by multiple `ldop` calls, so
   this slot is freed. Can be repurposed or left as a duplicate `ldop`.

### Pipeline Wiring (JopPipeline.scala)

```scala
// Compute unit with all four cores (ICU, FCU, LCU, DCU)
val cu = ComputeUnitTop(
  icuConfig = IntegerComputeUnitConfig(withMul = true, withDiv = true, withRem = true),
  fcuConfig = FloatComputeUnitConfig(withAdd = true, withMul = true, withDiv = true,
    withI2F = true, withF2I = true, withFcmp = true),
  lcuConfig = LongComputeUnitConfig(withMul = true, withDiv = true, withRem = true, withShift = true),
  dcuConfig = DoubleComputeUnitConfig(withAdd = true, withMul = true, withDiv = true,
    withI2D = true, withD2I = true, withL2D = true, withD2L = true, withF2D = true, withD2F = true, withDcmp = true)
)

// Operand feed: TOS (A register) → CU
cu.io.din := stack.io.aout.asUInt

// stop at 0x01F
cu.io.push := decode.io.cuPush

// sthw at 0x140-0x17F — 6-bit operand from instruction
cu.io.opcode := decode.io.cuOpcode   // ir(5 downto 0), registered
cu.io.start  := decode.io.cuStart

// ldop at 0x0E1
cu.io.pop := decode.io.cuPop

// din mux — slot 1 reads from CU result stack
stack.io.din := dinMuxSel.mux(
  0 -> io.memRdData,
  1 -> cu.io.dout.asBits,
  2 -> io.memBcStart.asBits.resized,
  3 -> B(0, 32 bits)
)

// CU busy → wait-based stall (via bsy, not extStall)
fetch.io.bsy := decode.io.wrDly || io.memBusy || stackRotBusy || cu.io.busy
fetch.io.extStall := stackRotBusy
decode.io.stall   := stackRotBusy
bcfetch.io.stall  := stackRotBusy
```

### What Gets Removed

- **`lastWasFloat` register** and associated JVM bytecode comparison chain
- **`bcfetch.io.jinstr_out`** connection to CU opcode
- **`intCu`/`floatCu` separate instantiations** — replaced by single `ComputeUnitTop`
- **`cuResultLo`/`cuResultHi` forward declarations** — replaced by `cu.io.dout`
- **`config.needsFloatCompute` conditional** — `ComputeUnitTop` always contains all units

### Stall Strategy

The CU asserts `busy` after `sthw` (including on the dispatch cycle itself via
`io.start || anyUnitBusy`). The pipeline freezes via `fetch.io.bsy` (wait-based
stall — the `wait` instruction checks `bsy` and re-fetches itself until clear).
Microcode uses a double-wait pattern:

```asm
ladd:   stop
        stop
        stop
        stop
        sthw 32     ;  CU starts, asserts busy
        wait        ;  stall: re-fetches while busy
        wait        ;  second wait ensures result is stable
        ldop        ;  push result_hi
        ldop nxt    ;  push result_lo
```

The double-wait is needed because `bsy` deasserts one cycle before the result
registers are stable. This handles variable-latency operations (division, FP ops)
without worst-case padding in the microcode.

Note: `extStall` is NOT used for CU busy — only for stack rotation. CU busy
goes through `fetch.io.bsy` alongside `wrDly` and `memBusy`.

## Cycle Count Summary

Cycle counts for all CU-supported operations across three implementation tiers.

**Java trap cost model** (for WCET): sys_noim dispatch = ~16 cycles,
invokestatic overhead = ~52 cycles, ireturn = ~23 cycles. Total per method
call: **~75 cycles** (invoke + return), plus the method body. SoftFloat
methods make multiple nested calls (isNaN, unpackMantissa, pack, etc.), each
adding ~75 cycles of call overhead. Java trap estimates below count the full
call chain on the normal path (no NaN/infinity/zero special cases).

**CU cost model**: microcode overhead (stop/sthw/wait/ldop) + hardware busy
cycles. The `wait`/`wait` pair adds 2 fixed cycles; busy stall is absorbed
by `wait` re-fetch (not additional cycles beyond the busy count).

### Microcode overhead patterns

| Pattern | Microcode | Total overhead |
|---------|-----------|:--------------:|
| 32-bit, 2 op, 1 result | stop stop sthw wait wait ldop | 6 |
| 32-bit, 1 op, 1 result | stop sthw wait wait ldop | 5 |
| 64-bit, 4 op, 2 result | stop stop stop stop sthw wait wait ldop ldop | 9 |
| 64-bit, 4 op, 1 result | stop stop stop stop sthw wait wait ldop | 8 |
| 64-bit shift, 3 op, 2 result | stop stop stop sthw wait wait ldop ldop | 8 |
| 64-bit, 2 op, 2 result | stop stop sthw wait wait ldop ldop | 7 |
| 64-bit, 2 op, 1 result | stop stop sthw wait wait ldop | 6 |
| 32-bit, 1 op, 2 result | stop sthw wait wait ldop ldop | 6 |

### ICU — Integer Compute Unit

| Operation | Java trap | Microcode (SW) | CU total | HW busy | Notes |
|-----------|:---------:|:--------------:|:--------:|:-------:|-------|
| imul | -- (IMP_ASM) | ~775 (shift-add loop, 32 iter) | 6 + 18 = **24** | 18 | Radix-4, 16 iterations |
| idiv | ~100+ dispatch | -- | 10 + 36 = **46** | 36 | Binary restoring, 32 iter. Microcode adds 4 cycles for div-by-zero check (dup/bnz/nop/nop) |
| irem | ~100+ dispatch | -- | 10 + 36 = **46** | 36 | Same divider as idiv |

imul is IMP_ASM (always microcode, never Java trap) — no Java fallback exists.
imul_sw is a naive 32-iteration shift-and-add: each iteration costs 22 cycles
(bit=0) or 26 cycles (bit=1) due to branch delay slot overhead (bnz/bz each
require 2 nop delay slots = 9 wasted cycles per iteration on branching alone).
This makes imul the single biggest CU win: **24 vs ~775 cycles (32x speedup)**.

idiv/irem have no pure microcode (SW) implementation — div-by-zero falls
through to Java trap, non-zero goes to CU. Total CU path: 4 (zero check) +
6 (stop/sthw/wait/ldop) + 36 (busy) = 46 cycles.

### DSP Multiply (ALU path, `useDspMul=true`)

| Operation | CU path | DSP path | Speedup | Notes |
|-----------|:-------:|:--------:|:-------:|-------|
| imul | 6 + 18 = **24** | **2** (dspmul + lddsp) | **12x** | 1-cycle registered DSP in ALU |
| imul_wide (32x32→64) | 24 | **3** (dspmul + lddsp + lddsph) | **8x** | For lmul partial products |

**Design principle**: If an FPGA operation completes in 1 cycle, it should be
available as a microcode ALU instruction — not routed through the multi-cycle
CU interface. The CU is for iterative operations (division, long multiply, etc.).

#### Architecture

The DSP multiply uses a registered 32x32→64 signed multiplier in the stack
stage (`StackStage.scala`), bypassing the CU entirely. Three microcode
instructions control the multiply and result retrieval:

| Opcode | Mnemonic | Class | Action |
|--------|----------|-------|--------|
| 0x103 | `dspmul` | NOP | Latches `(a.asSInt * b.asSInt).resize(64)` into 64-bit result register |
| 0x008 | `lddsp` | POP | `a <- dspResult[31:0]` via lmux slot 110 |
| 0x009 | `lddsph` | POP | `a <- dspResult[63:32]` via lmux slot 111 |

`dspmul` is NOP class (no stack pointer change) so that a NOP + POP sequence
gives the correct net -1 stack effect for `imul` (consumes 2 operands, produces
1 result). Making `dspmul` POP class would give net -2 which is wrong.

#### Pipeline timing

```
Cycle N  : Fetch fetches dspmul from ROM
Cycle N+1: Decode sets dspMulTriggerReg := True, enaAReg := False
Cycle N+2: Stack stage latches a*b into dspMulResult register
           (a,b hold pre-edge operand values since enaA was disabled)
Cycle N+3: lddsp reads stable dspMulResult[31:0] via lmux
```

The `dspMulTrigger` signal flows: DecodeStage registered output →
combinational wire → StackStage `when(trigger) { result := a * b }`.

#### Microcode

imul_dsp (2 cycles, replaces 24-cycle CU path):
```asm
imul_dsp:
    dspmul       // NOP: latch a*b in DSP result register
    lddsp nxt    // POP: sp--, a <- product_lo, return
```

imul_wide_dsp (3 cycles, for lmul partial products):
```asm
    dspmul       // NOP: latch a*b
    lddsph       // POP: a <- product_hi
    lddsp nxt    // POP: a <- product_lo, return
```

#### Configuration

Controlled by `useDspMul: Boolean` in `JopCoreConfig`. When true:
- `resolveJumpTable` patches imul (0x68) to `imul_dsp` via `dspAltEntries`
- DSP multiplier instantiated in `StackStage` (conditional on config)
- `dspMulTrigger` port added to DecodeStage and StackStage
- Pipeline wiring in `JopPipeline.scala` connects decode → stack trigger
- lmux extended with slots 110 (DSP low) and 111 (DSP high)

The superset ROM always contains `imul_dsp` handlers (generated by Jopa
from `_dsp` label suffix). The `dspAltEntries` map in `JumpTableData.scala`
enables selection at elaboration time, parallel to `altEntries` for SW handlers.

#### Implementation files

| File | Changes |
|------|---------|
| `StackStage.scala` | 64-bit DSP result register, lmux slots 110/111, `dspMulTrigger` input |
| `DecodeStage.scala` | Decode 0x103/0x008/0x009, `dspMulTriggerReg`, selLmux overrides |
| `JopPipeline.scala` | Wire decode.dspMulTrigger → stack.dspMulTrigger |
| `JopCoreConfig.scala` | `useDspMul` param, `resolveJumpTable` DSP patching |
| `JumpTable.scala` | `dspAltEntries` map, `useDspAlt()` method |
| `Instruction.java` | `dspmul` (0x103 NOP), `lddsp` (0x008 POP), `lddsph` (0x009 POP) |
| `Jopa.java` | `_dsp` label suffix detection, `dspAltJinstrMap`, Scala output |
| `jvm.asm` | `imul_dsp:` handler (2 instructions) |

#### FPGA resources

| FPGA | DSP blocks used | Block type | Total available |
|------|:-:|------|:-:|
| Cyclone IV GX (EP4CGX150) | ~4 | 18x18 embedded multiplier | 360 |
| Artix-7 (XC7A100T) | ~2 | DSP48E1 | 240 |

#### Verification

| Test | Result | Details |
|------|:------:|---------|
| Unit tests (571) | PASS | All pipeline/formal/system tests pass with `useDspMul=false` (default) |
| BRAM sim — JVM suite (52 tests) | PASS | Imul, IntArithmetic, LongArithmetic all pass with `useDspMul=true` |
| FPGA — ImulTest (15 cases) | PASS | All edge cases pass on EP4CGX150 at 80 MHz |

FPGA build uses 33 embedded multiplier elements (vs 0 without DSP). Quartus
slow-corner Fmax is ~51 MHz (limited by DCU critical path, unrelated to DSP).
Fast-corner timing meets 80 MHz. Hardware verified on QMTECH EP4CGX150 DB_FPGA
board (2026-03-10).

ImulTest hardware test cases:
```
3*7=21, 0*12345=0, 1*99999=99999, -1*42=-42, -1*-1=1,
100*200=20000, 256*256=65536, 0x7FFF*0x7FFF=0x3FFF0001,
-2*0x40000000=-0x80000000, 0x10000*0x10000=0 (overflow),
12345*6789=83810205, -12345*6789=-83810205,
MAX_VALUE*2=-2, MIN_VALUE*-1=MIN_VALUE, 1000000*1000=1000000000
```

### FCU — Float Compute Unit

| Operation | Java trap | Microcode (SW) | CU total | HW busy | Notes |
|-----------|:---------:|:--------------:|:--------:|:-------:|-------|
| fadd | ~1500 (SoftFloat32) | -- | 6 + 6 = **12** | 5-6 | ADD pipeline: unpack/align/shift/exec/norm/round |
| fsub | ~1500 (SoftFloat32) | -- | 6 + 6 = **12** | 5-6 | Same pipeline as fadd |
| fmul | ~1400 (SoftFloat32) | -- | 6 + 4 = **10** | 4 | MUL: unpack/step1/step2/round |
| fdiv | ~1600 (SoftFloat32) | -- | 6 + 30 = **36** | 30 | 25 division iterations + overhead |
| fcmpl | ~600 (SoftFloat32) | -- | 6 + 2 = **8** | 2 | Unpack + compare |
| fcmpg | ~600 (SoftFloat32) | -- | 6 + 2 = **8** | 2 | Unpack + compare |
| i2f | ~800 (SoftFloat32) | -- | 5 + 3 = **8** | 3 | 1 operand. I2F_EXEC/shift/round |
| f2i | ~700 (SoftFloat32) | -- | 5 + 2 = **7** | 2 | 1 operand. Unpack + F2I_EXEC |

Java trap costs for SoftFloat32 (normal path, no NaN/Inf/zero): sys_noim
dispatch (~75) + method body + nested calls. Each SoftFloat method calls
isNaN (×2), isInfinite (×2), unpackMantissa (×2), unpackExponent (×2),
pack → countLeadingZeros → roundingRightShift. Each nested call adds ~75
cycles overhead. fmul additionally calls lmul for mantissa multiplication.

No microcode (SW) path exists for float ops — it's either CU hardware or
Java trap (SoftFloat32). The old I/O-based BmbFpu peripheral has been removed.

### LCU — Long Compute Unit

| Operation | Java trap | Microcode (SW) | CU total | HW busy | Notes |
|-----------|:---------:|:--------------:|:--------:|:-------:|-------|
| ladd | -- | 26 (half-add with carry) | 9 + 2 = **11** | 2 | Combinational add, 1-cycle exec |
| lsub | -- | 37 (negate + half-add) | 9 + 2 = **11** | 2 | Combinational sub, 1-cycle exec |
| lneg | -- | 34 (xor + fall-through to ladd_sw) | 13 + 2 = **15** | 2 | HW uses lsub(0 - value): stm(2)+ldm(2)+stop(4)+sthw+wait(2)+ldop(2) |
| lcmp | -- | ~25 (branch-heavy, sign checks) | 8 + 2 = **10** | 2 | Combinational compare, 1-cycle exec |
| lmul | ~1200 (Java f_lmul) | **BROKEN** (see note) | 9 + 34 = **43** | 34 | Radix-4, 32 iterations |
| ldiv | ~1500 (Java f_ldiv) | -- | 9 + 68 = **77** | 68 | Binary restoring, 64 iterations |
| lrem | ~1500 (Java f_lrem) | -- | 9 + 68 = **77** | 68 | Same divider as ldiv |
| lshl | -- | 6-23 (path-dependent) | 8 + 2 = **10** | 2 | Combinational barrel shift |
| lshr | -- | 6-23 (path-dependent) | 8 + 2 = **10** | 2 | Combinational barrel shift |
| lushr | -- | 6-23 (path-dependent) | 8 + 2 = **10** | 2 | Combinational barrel shift |

**lmul_sw is currently broken.** It uses the old `sthw`/`ldmul`/`ldmulh`
interface to perform 3 partial-product 32×32→64 multiplies (P0=a0*b0,
P1=a1*b0, P2=a0*b1), relying on `ldmulh` to get the upper 32 bits. The
new ICU only produces the lower 32 bits (`resultCount=1`) and the old
`sthw`(0x040)/`ldmul`/`ldmulh` instructions are no longer wired. Options:
(a) extend ICU imul to output 64 bits (resultCount=2) and rewrite lmul_sw
to use `stop`/`stop`/`sthw 0`/`wait`/`wait`/`ldop`/`ldop`, (b) rewrite as
pure shift-and-add (~1500+ cycles), or (c) remove lmul_sw entirely (lmul
always requires LCU or falls to Java trap). ldiv/lrem have no pure
microcode — Java trap only when CU disabled. Shift SW costs: cnt=0
early exit (6), cnt 1-31 (23), cnt 32-63 (12).

### DCU — Double Compute Unit

| Operation | Java trap | Microcode (SW) | CU total | HW busy | Notes |
|-----------|:---------:|:--------------:|:--------:|:-------:|-------|
| dadd | ~3000-5000 (SoftFloat64) | -- | 9 + 5 = **14** | 4-5 | ADD pipeline: unpack/align/exec/norm/round |
| dsub | ~3000-5000 (SoftFloat64) | -- | 9 + 5 = **14** | 4-5 | Same pipeline as dadd |
| dmul | ~3000-5000 (SoftFloat64) | -- | 9 + 4 = **13** | 4 | MUL: unpack/step1/step2/round |
| ddiv | ~4000-6000 (SoftFloat64) | -- | 9 + 56 = **65** | 56 | 54 division iterations + overhead |
| dcmpl | ~1500 (SoftFloat64) | -- | 8 + 2 = **10** | 2 | Unpack + compare |
| dcmpg | ~1500 (SoftFloat64) | -- | 8 + 2 = **10** | 2 | Unpack + compare |
| i2d | ~800 (SoftFloat64) | -- | 6 + 2 = **8** | 2 | 1 op in, 2 results out. Combinational |
| d2i | ~1000 (SoftFloat64) | -- | 6 + 2 = **8** | 2 | 2 ops in, 1 result out. Unpack + exec |
| l2d | ~1200 (SoftFloat64) | -- | 7 + 3 = **10** | 2-3 | 2 ops in, 2 results out. May need rounding |
| d2l | ~1000 (SoftFloat64) | -- | 7 + 2 = **9** | 2 | 2 ops in, 2 results out. Unpack + exec |
| f2d | ~600 (SoftFloat64) | -- | 6 + 2 = **8** | 2 | 1 op in, 2 results out. Combinational |
| d2f | ~1000 (SoftFloat32) | -- | 6 + 3 = **9** | 3 | 2 ops in, 1 result out. Unpack + exec + round |

All double operations are Java traps (SoftFloat64) when DCU is disabled —
there are no microcode-only implementations. Java trap costs are extremely
high because SoftFloat64 internally uses 64-bit long arithmetic (ladd, lsub,
lmul, lshl, lshr, lushr, lcmp) at every step. Each long operation is itself
either a microcode handler (26-37 cycles for add/sub, ~775 for lmul) or
another Java trap if LCU is disabled. The estimates above assume LCU is
enabled (microcode long ops); without LCU, costs roughly double.

### Operations with no CU support (microcode only)

| Operation | Microcode cycles | Notes |
|-----------|:----------------:|-------|
| land | 8 | Save/restore 4 regs, AND each half |
| lor | 8 | Save/restore 4 regs, OR each half |
| lxor | 8 | Save/restore 4 regs, XOR each half |
| i2l | 4 | Sign-extend: dup, shr 31 |
| l2i | 3 | Truncate: stm, pop, ldm |
| fneg | 3 | XOR sign bit |
| dneg | 5 | XOR sign bit of high word |

## Encoding Summary

| Instruction | Encoding      | Type | Stack  | Signals                  |
|-------------|---------------|------|--------|--------------------------|
| `stop`      | `0x01F`       | POP  | −1     | `cuPush` (new)           |
| `sthw`      | `0x140` +6bit | NOP  |  0     | `cuStart`, `cuOpcode[5:0]` (new) |
| `ldop`      | `0x0E1`       | PUSH | +1     | `cuPop` (new)            |

Encoding map within the 10-bit instruction word:

```
stop:  00_0001_1111  (0x01F) — POP class (ir[9:6]=0000)
sthw:  01_01xx_xxxx  (0x140-0x17F) — NOP class (ir[9:6]=0101)
ldop:  00_1110_0001  (0x0E1) — PUSH class (ir[9:6]=0011)
```

The `sthw` operand is extracted as `ir(5 downto 0)` in the decode stage
and passed directly to the CU. No constant RAM slots are used.

## Assembler Changes

In `Instruction.java`:

```java
// New/changed instructions:
new Instruction("stop", 0x01F, 0, JmpType.NOP, StackType.POP),
new Instruction("sthw", 0x140, 6, JmpType.NOP, StackType.NOP),
new Instruction("ldop", 0x0E1, 0, JmpType.NOP, StackType.PUSH),

// Removed (old interface):
// new Instruction("sthw", 0x040 + 0, 0, JmpType.NOP, StackType.POP),
// new Instruction("ldmul", 0x0e0 + 1, 0, JmpType.NOP, StackType.PUSH),
// new Instruction("ldmulh", 0x0e0 + 3, 0, JmpType.NOP, StackType.PUSH),
```

Define named constants for readability in microcode:

```asm
// CU operation codes (sthw operand)
// ICU (00_xxxx)
CU_IMUL     = 0
CU_IDIV     = 1
CU_IREM     = 2
// FCU (01_xxxx)
CU_FADD     = 16
CU_FSUB     = 17
CU_FMUL     = 18
CU_FDIV     = 19
CU_FCMPL    = 20
CU_FCMPG    = 21
CU_I2F      = 22
CU_F2I      = 23
// LCU (10_xxxx)
CU_LADD     = 32
CU_LSUB     = 33
CU_LMUL     = 34
CU_LDIV     = 35
CU_LREM     = 36
CU_LCMP     = 37
CU_LSHL     = 38
CU_LSHR     = 39
CU_LUSHR    = 40
// DCU (11_xxxx)
CU_DADD     = 48
CU_DSUB     = 49
CU_DMUL     = 50
CU_DDIV     = 51
CU_DCMPL    = 52
CU_DCMPG    = 53
CU_F2D      = 54
CU_D2F      = 55
CU_I2D      = 56
CU_D2I      = 57
CU_L2D      = 58
CU_D2L      = 59
```

## Migration Path

All phases are complete as of 2026-03-10.

### Phase 1: New Interface Wrapper (ComputeUnitTop) — DONE

- `ComputeUnitTop` wraps ICU/FCU/LCU/DCU with shared 4-deep operand stack
- Unit selection via `opcode[5:4]` (00=ICU, 01=FCU, 10=LCU, 11=DCU)
- Result sequencing: hi word pushed first, lo word second (matching JOP stack convention)
- `io.start` included in busy signal for immediate stall on dispatch cycle

### Phase 2: Decode + Pipeline Wiring — DONE

- `stop` (0x01F) → `cuPush`, `sthw` (0x140-0x17F) → `cuStart`+`cuOpcode`, `ldop` (0x0E1) → `cuPop`
- CU busy wired to `fetch.io.bsy` (wait-based stall, not extStall)
- Old `lastWasFloat` mux, `hwWr` decode, `bcfetch.io.jinstr_out` opcode path all removed
- `ldmulh` (0x0E3) freed — multi-word results use sequential `ldop` calls

### Phase 3: Assembler + Microcode — DONE

- `Instruction.java` updated: `stop`/`sthw`(6-bit)/`ldop` added, old `stmul`/`ldmul`/`ldmulh` removed
- CU operation constants (CU_IMUL=0, CU_FADD=16, CU_LADD=32, CU_DADD=48, etc.) in microcode include
- All HW handlers use `stop`/`sthw`/`wait`/`wait`/`ldop` pattern
- Superset ROM: both `_hw` and `_sw` handlers coexist unconditionally
- 13 `altEntries` in generated JumpTableData.scala for elaboration-time HW/SW selection

### Phase 4: Verification — DONE

- All CU unit tests updated for new `ComputeUnitCoreBundle` interface
- 52/52 BRAM JVM tests pass (LongArithmetic, TypeConversion, FloatField, DoubleField, DoubleArith all fixed)
- 568/570 unit tests pass (2 pre-existing JopFileLoaderSpec failures)

### Phase 5: DSP Multiply — DONE (2026-03-10)

- 1-cycle registered 32x32→64 signed DSP multiplier in StackStage (bypasses CU)
- 3 new microcode instructions: `dspmul` (NOP), `lddsp` (POP), `lddsph` (POP)
- `imul_dsp` handler: 2 cycles vs 24 cycles (CU) — 12x speedup
- `useDspMul: Boolean` config flag, `dspAltEntries` jump table mechanism
- Superset ROM: `_dsp` label suffix in Jopa assembler, parallel to `_sw`
- 52/52 BRAM JVM tests pass with `useDspMul=true`
- 15/15 ImulTest hardware tests pass on EP4CGX150 at 80 MHz
- 33 embedded multiplier elements used (Cyclone IV)
