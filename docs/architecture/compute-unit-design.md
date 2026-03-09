# Compute Unit Interface Design

## Overview

Replace the current `stmul`/`ldmul`/`ldmulh` mechanism with a general-purpose
compute unit interface using three microcode instructions: `stop`, `sthw`,
and `ldop`. The compute unit has an internal operand stack, eliminating the
need to expand the TOS register file beyond the current 2 registers (A, B).

Four compute units share the interface: ICU (integer), FCU (float), LCU
(long), and DCU (double). The `sthw` instruction encodes a 6-bit operation
code that selects the unit and operation in hardware.

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

- **Encoding**: `0x120` with 6-bit operand (NOP class, covers `0x120-0x15F`)
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

### Operations that stay in microcode

These operations have no hardware benefit or are trivial:

| Operation      | Microcode cycles | Rationale                          |
|----------------|:----------------:|------------------------------------|
| land, lor, lxor|        8         | Same as CU path (4 stop+sthw+2 ldop+1 nxt) |
| lneg           |        8         | Invert + add 1                     |
| i2l            |        4         | Sign-extend (dup, shr 31)          |
| l2i            |        3         | Truncate (stm, pop, ldm)           |
| fneg, dneg     |      3-5         | Flip sign bit                      |

## Microcode Sequences

### 32-bit, 2 operands, 1 result (imul, fadd, fsub, fmul, fdiv, ...)

```asm
                            ;  JOP stack             CU stack
                            ;  ..., value1, value2   (empty)
imul:
        stop                ;  ..., value1            value2
        stop                ;  ...                    value2, value1
        sthw 0              ;  start ICU multiply, busy stalls
        ldop nxt            ;  ..., result            (empty)

fadd:
        stop                ;  ..., value1            value2
        stop                ;  ...                    value2, value1
        sthw 16             ;  start FCU add, busy stalls
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
        sthw 32             ;  start LCU add, busy stalls
        ldop                ;  ..., result_hi
        ldop nxt            ;  ..., result_hi, result_lo

dadd:
        stop
        stop
        stop
        stop
        sthw 48             ;  start DCU add
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
        ldop                ;  ..., result_hi
        ldop nxt            ;  ..., result_hi, result_lo

lushr:
        stop
        stop
        stop
        sthw 40             ;  start LCU logical shift right
        ldop
        ldop nxt
```

### Cross-width conversions

```asm
; 1 operand (32-bit) → 2 results (64-bit)
i2d:
        stop                ;  pop int → CU
        sthw 56             ;  start DCU i2d (lossless: 32-bit int fits in 53-bit mantissa)
        ldop                ;  push double_hi
        ldop nxt            ;  push double_lo

f2d:
        stop                ;  pop float → CU
        sthw 54             ;  start DCU f2d
        ldop                ;  push double_hi
        ldop nxt            ;  push double_lo

; 2 operands (64-bit) → 1 result (32-bit)
d2i:
        stop                ;  pop double_lo → CU
        stop                ;  pop double_hi → CU
        sthw 57             ;  start DCU d2i
        ldop nxt            ;  push int result

d2f:
        stop                ;  pop double_lo → CU
        stop                ;  pop double_hi → CU
        sthw 55             ;  start DCU d2f
        ldop nxt            ;  push float result

; 2 operands (64-bit) → 2 results (64-bit)
l2d:
        stop                ;  pop long_lo → CU
        stop                ;  pop long_hi → CU
        sthw 58             ;  start DCU l2d
        ldop                ;  push double_hi
        ldop nxt            ;  push double_lo

d2l:
        stop                ;  pop double_lo → CU
        stop                ;  pop double_hi → CU
        sthw 59             ;  start DCU d2l
        ldop                ;  push long_hi
        ldop nxt            ;  push long_lo
```

## Compute Unit Bundle

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

  io.busy := icu.io.busy || fcu.io.busy || lcu.io.busy || dcu.io.busy
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

2. **`sthw`** at `0x120-0x15F`: New NOP instruction with 6-bit operand.
   Decoded by `ir(9 downto 6) === B"4'b0100"` (NOP class) and
   `ir(5) === False || ir(5) === True` within the `0x120-0x15F` range.
   Generates `cuStart` signal (1 bit). The 6-bit operand `ir(5 downto 0)`
   is passed to the CU as the operation code. `enaA := False` (no A
   register update).

3. **`ldop`** at `0x0E1`: Already decoded as `ldmul` with PUSH behavior.
   The `din` mux selects `ir(1:0) == 1` for this slot. Change the mux
   input from `mul.io.dout` to `cu.io.dout`. Add `cuPop` signal (1 bit).

4. **`stmul`** at `0x040`: Retained for backward compatibility during
   migration. Eventually removed.

5. **`ldmulh`** at `0x0E3`: Retained for backward compatibility during
   migration. Eventually removed when `lmul` moves to the LCU.

### Pipeline Wiring (JopPipeline.scala)

```scala
// New compute unit (alongside old Mul during migration)
val cu = new ComputeUnitTop()

// Operand feed: TOS (A register) → CU
cu.io.din := stack.io.aout.asUInt

// stop at 0x01F
cu.io.push := decode.io.cuPush

// sthw at 0x120-0x15F — 6-bit operand from instruction
cu.io.opcode := decode.io.cuOpcode   // ir(5 downto 0), registered
cu.io.start  := decode.io.cuStart

// ldop at 0x0E1
cu.io.pop := decode.io.cuPop

// During migration: cuActive flag selects between old Mul and new CU
// on din mux slot 1. Set on sthw, cleared on ldop.
val cuActive = Reg(Bool()) init(False)
when(decode.io.cuStart) { cuActive := True }
when(decode.io.cuPop)   { cuActive := False }

stack.io.din := dinMuxSel.mux(
  0 -> io.memRdData,
  1 -> Mux(cuActive, cu.io.dout.asBits, mul.io.dout.asBits),
  2 -> io.memBcStart.asBits.resized,
  3 -> mul.io.doutH.asBits      // kept for old lmul during migration
)

// CU busy → pipeline stall (unconditional freeze via extStall)
fetch.io.extStall  := stackRotBusy || cu.io.busy
decode.io.stall    := stackRotBusy || cu.io.busy
bcfetch.io.stall   := stackRotBusy || cu.io.busy
```

### Stall Strategy

The CU asserts `busy` after `sthw`, and the pipeline freezes via `extStall`
(unconditional — no `wait` instructions needed). When `busy` deasserts, the
pipeline resumes and `ldop` executes immediately:

```asm
ladd:   stop
        stop
        stop
        stop
        sthw 32     ;  CU starts, asserts busy, pipeline freezes
        ldop        ;  stalled until busy deasserts, then pushes result
        ldop nxt
```

This handles variable-latency operations (division, FP ops) without
worst-case padding in the microcode.

## Cycle Count Summary

| Operation    | Current       | New (CU)         | Speedup |
|--------------|:-------------:|-----------------:|--------:|
| imul (DSP)   |    4 cycles   |     4 + 1 busy   |   1.0×  |
| idiv/irem    |   34 cycles   |     4 + latency   |   ~2×  |
| fadd/fsub    |   10 cycles   |     4 + latency   |   ~2×  |
| fmul         |   10 cycles   |     4 + latency   |   ~2×  |
| fdiv         |   10 cycles   |     4 + latency   |   ~2×  |
| i2f/f2i      |   10 cycles   |     3 + latency   |   ~3×  |
| ladd         |   26 cycles   |     7 + 1 busy    |   3.3×  |
| lsub         |   38 cycles   |     7 + 1 busy    |   4.8×  |
| lcmp         |  ~80 cycles   |     6 + 1 busy    |  10.0×  |
| lmul (DSP)   |  ~44 cycles   |     7 + latency   |   ~5×  |
| lshl/lshr    |   28 cycles   |     6 + latency   |   ~4×  |
| lushr        |   28 cycles   |     6 + latency   |   ~4×  |
| dadd/dmul    |  SW trap      |     7 + latency   |   N/A  |
| i2d          |  SW trap      |     4 + latency   |   N/A  |
| d2i          |  SW trap      |     4 + latency   |   N/A  |

Note: "7 cycles" for 64-bit binary ops = 4 `stop` + 1 `sthw` + 2 `ldop`.
"6 cycles" for long shifts = 3 `stop` + 1 `sthw` + 2 `ldop`. Busy stall
absorbs compute latency without additional microcode instructions.

## Encoding Summary

| Instruction | Encoding      | Type | Stack  | Signals                  |
|-------------|---------------|------|--------|--------------------------|
| `stop`      | `0x01F`       | POP  | −1     | `cuPush` (new)           |
| `sthw`      | `0x120` +6bit | NOP  |  0     | `cuStart`, `cuOpcode[5:0]` (new) |
| `ldop`      | `0x0E1`       | PUSH | +1     | `cuPop` (new)            |

Encoding map within the 10-bit instruction word:

```
stop:  00_0001_1111  (0x01F) — POP class (ir[9:6]=0000)
sthw:  01_00xx_xxxx  (0x120-0x15F) — NOP class (ir[9:6]=0100,0101)
ldop:  00_1110_0001  (0x0E1) — PUSH class (ir[9:6]=0011)
```

The `sthw` operand is extracted as `ir(5 downto 0)` in the decode stage
and passed directly to the CU. No constant RAM slots are used.

## Assembler Changes

In `Instruction.java`:

```java
// New instructions:
new Instruction("stop", 0x01F, 0, JmpType.NOP, StackType.POP),
new Instruction("sthw", 0x120, 6, JmpType.NOP, StackType.NOP),
new Instruction("ldop", 0x0E1, 0, JmpType.NOP, StackType.PUSH),

// Keep during migration (remove when fully migrated):
new Instruction("stmul", 0x040, 0, JmpType.NOP, StackType.POP),
new Instruction("ldmul", 0x0E1, 0, JmpType.NOP, StackType.PUSH),
new Instruction("ldmulh", 0x0E3, 0, JmpType.NOP, StackType.PUSH),
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

### Phase 1: Infrastructure (no behavioral change)

1. Add `stop`, `sthw`, `ldop` to assembler alongside old names.
2. Add `cuPush`, `cuStart`, `cuOpcode`, `cuPop` decode signals.
3. Create `ComputeUnitTop` with shared operand/result stacks, initially
   containing only an ICU that wraps the existing `Mul` component.
4. Wire CU in `JopPipeline` alongside old `Mul` with `cuActive` mux.

### Phase 2: ICU (imul first)

5. Rewrite `imul` microcode to `stop`/`stop`/`sthw 0`/`ldop nxt`.
6. Verify with existing tests and JVM test suite.
7. Add `idiv`/`irem` to ICU.

### Phase 3: Migrate lmul

8. Add `lmul` support to LCU (handles 3 DSP partial products internally).
9. Rewrite `lmul` microcode to `stop`×4/`sthw 34`/`ldop`×2.
10. Remove old `Mul` component, `cuActive` mux, `stmul`/`ldmul`/`ldmulh`.

### Phase 4: Long operations (LCU)

11. Add `ladd`/`lsub`/`lcmp`/`lshl`/`lshr`/`lushr` to LCU.
12. Rewrite long microcode sequences. Each becomes 6-7 instructions.
13. Add `ldiv`/`lrem` to LCU.

### Phase 5: Float operations (FCU)

14. Implement FCU (replacing I/O-mapped BmbFpu approach).
15. Rewrite float microcode to use `stop`/`sthw`/`ldop`.

### Phase 6: Double operations (DCU)

16. Implement DCU with double-precision FPU.
17. Add cross-width conversions (i2d, d2i, l2d, d2l, f2d, d2f).
18. Remove software trap handlers for double bytecodes.
