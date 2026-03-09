# Analysis: Expanding TOS from 2 to 4 Registers

## Current 2-Register Design

JOP keeps the top two stack values in hardware registers:

```
Register A  ← TOS   (sp)
Register B  ← NOS   (sp-1)
RAM[sp-2]   ← 3rd element (pointed to by hardware SP register)
RAM[sp-3]   ← 4th element
  ...
RAM[vp+N]   ← local variable N
```

### Stack RAM Architecture

The stack RAM uses **async read + sync write** on a single-port Mem (maps to
FPGA distributed/LUT RAM):

- **Read**: `ramDout := stackRam.readAsync(ramRdaddrReg)` — combinational
- **Write**: `stackRam.write(wrAddrDly, mmux, wrEnaDly)` — clocked

Both the read address and write enable are **registered** (delayed 1 cycle from
decode), so they are phase-aligned:

```
Cycle T:    Decode sets rdaddr, wraddr, wrEna (combinational)
Cycle T+1:  ramRdaddrReg := rdaddr    (registered read address)
            wrAddrDly := wraddr       (registered write address)
            wrEnaDly := wrEna         (registered write enable)
            → RAM write executes (sync, from cycle T's instruction)
            → ramDout available (async read, from cycle T's instruction)
            → B loads from ramDout or A (depending on selBmux)
            → A loads from ALU output or lmux
```

### Why 2 Registers Avoids Hazards

The decode stage guarantees a structural invariant: **the RAM read address
(always `sp` for the 3rd element) never collides with the RAM write address
for local variable stores (`vp+N`)**. This works because:

1. `st N` writes TOS to `stack[vp+N]` — locals live deep in the stack
2. B is loaded from `ramDout` at address `sp` — the 3rd element from TOS
3. VP and SP are far enough apart that `vp+N` never equals `sp`
4. Push/pop adjust SP and shift values through A←B←RAM naturally

No bypass logic, no forwarding, no address comparators needed.

## Proposed 4-Register Design

```
Register A  ← TOS   (sp)
Register B  ← NOS   (sp-1)
Register C  ←        (sp-2)
Register D  ←        (sp-3)
RAM[sp-4]   ← 5th element (pointed to by hardware SP register)
  ...
RAM[vp+N]   ← local variable N
```

### Problem 1: Register-RAM Aliasing on Stores

When `st N` writes TOS to `stack[vp+N]`, the target address might now fall
within the 4-register window. With 2 registers this couldn't happen (locals
are too far from SP). With 4 registers, the window is wider and methods with
few locals + deep operand stacks can alias.

**Example**: a method with 2 locals and 4+ operand stack entries:

```
Register A  ← sp     = vp+5   (TOS)
Register B  ← sp-1   = vp+4
Register C  ← sp-2   = vp+3
Register D  ← sp-3   = vp+2   ← st 2 targets this address!
RAM[sp-4]   ← vp+1             ← st 1 targets this
RAM[vp]     ← vp+0             ← st 0 targets this
```

`st 2` writes to RAM at `wraddr = vp+2`, but the live value for address
`vp+2` is held in Register D. The RAM write succeeds but Register D retains
the stale value. Any subsequent read of sp-3 returns the stale register
instead of the updated RAM.

Similarly, `ld 2` reads from RAM at `rdaddr = vp+2`, but if Register D holds
the current value for that address (after a push shifted it there), `ramDout`
returns a stale RAM value instead of the register's live value.

### Problem 2: Write Forwarding (Read-After-Write Hazard)

Even for accesses that stay in RAM (below the register window), the 1-cycle
write delay creates a forwarding hazard.

**Example**: two consecutive instructions where the second reads what the
first just wrote:

```
Cycle T:    st 0    → wraddr=vp, wrEna=1, mmux=TOS_value
Cycle T+1:  ld 0    → rdaddr=vp (set at T+1, latched at T+2)
            RAM write fires: stackRam.write(vp, TOS_value)  ← from cycle T
Cycle T+2:  ramDout = readAsync(vp) — sees newly written value (OK for LUT RAM)
```

This specific case works with async-read LUT RAM because the write at T+1
completes before the async read at T+2. But with **block RAM** (synchronous
read), or if the read and write to the same address happen in the *same* cycle
(T+1), the read-during-write behavior is undefined on many FPGA families.

With 4 registers, the increased register window means more instructions
involve register-file operations rather than pure RAM, multiplying the
scenarios where forwarding is needed.

## Required Bypass Logic

### Read Bypass (for `ld N`, `ldmi`, and any RAM read)

Before using `ramDout`, check if the read address matches any register:

```
if      ramRdaddrReg == sp_addr    → use Register A
else if ramRdaddrReg == sp_addr-1  → use Register B
else if ramRdaddrReg == sp_addr-2  → use Register C
else if ramRdaddrReg == sp_addr-3  → use Register D
else                               → use ramDout
```

This requires 4 address comparators and a 5-input mux on the read data path.

### Write Bypass (for `st N`, `stmi`, and any VP/AR-relative write)

Before writing to RAM, check if the write address matches any register:

```
if      wraddr == sp_addr    → write Register A (not RAM)
else if wraddr == sp_addr-1  → write Register B
else if wraddr == sp_addr-2  → write Register C
else if wraddr == sp_addr-3  → write Register D
else                         → write RAM (via wrEnaDly, normal path)
```

This requires 4 more address comparators and write-enable steering.

### Write Forwarding (for back-to-back RAM accesses)

Handle the case where instruction T writes to RAM and instruction T+1 reads
the same RAM address before the write has propagated:

```
val forward = (ramRdaddrReg === wrAddrDly) && wrEnaDly
val effectiveRead = Mux(forward, wrDataDly, ramDout)
```

This adds 1 comparator and 1 mux, but must be combined with the register
bypass above with correct priority:

```
Priority: register bypass > write forwarding > raw RAM read
```

### Summary of Added Hardware

| Component              | Count | Notes                            |
|------------------------|-------|----------------------------------|
| Address comparators    | 9     | 4 read + 4 write + 1 forwarding |
| Read data mux          | 5:1   | 4 registers + ramDout            |
| Write steering mux     | 5:1   | 4 registers + RAM                |
| Forward data mux       | 2:1   | forwarded vs ramDout             |
| Priority mux           | 3:1   | register vs forward vs RAM       |

### Timing Impact

The current path from `ramDout` to `B` register is a single mux
(`selBmux` choosing between `A` and `ramDout`). The 4-register design
replaces this with:

```
rdaddr → comparators (4×) → priority encode → 5:1 mux → B register
```

This adds ~2 LUT levels of combinational depth to what is currently a
single-LUT path, which may impact Fmax on the stack stage critical path.

## Interaction with AR-Relative Access (`star`/`ldmi`/`stmi`)

The AR (address register) is set by `star` and used by `ldmi`/`stmi` for
indirect memory access. AR can point **anywhere** in the stack, making it
the hardest case:

- `ldmi` reads `stack[AR]` — needs the full read bypass check
- `stmi` writes to `stack[AR]` — needs the full write bypass check
- AR-relative accesses are common in invoke/return (frame save/restore)

No special handling beyond the general bypass logic, but AR's unpredictability
means the bypass comparators cannot be optimized away.

## Motivation: Single-Cycle 64-bit Operand Delivery via `sthw`

The purpose of 4 TOS registers is to feed two 64-bit operands to a hardware
compute unit (replacing `stmul`) in a single `sthw` instruction. The compute
unit interface (`ComputeUnitBundle`) already has 64-bit `operand0` and
`operand1` ports. With 4 registers, `sthw` can wire them directly:

```
operand0 = {B, A}    = {hi0, lo0}   (first 64-bit value)
operand1 = {D, C}    = {hi1, lo1}   (second 64-bit value)
```

### What This Replaces

**Current long addition (`ladd`) — 26 microcode cycles:**

```asm
ladd:   stm a       // bl
        stm b       // bh
        stm c       // al
        stm d       // ah
        // ... 22 more instructions doing manual carry propagation
        // using shifts, adds, and scratch variables
        add nxt
```

The current approach pops all 4 values into scratch RAM (addresses 0-31),
performs 32-bit carry arithmetic in microcode, then pushes the 2-word result.
`lsub` is 38 cycles, `lcmp` is up to 80 cycles, `lmul` requires 3 separate
`stmul` calls with scratch accumulation.

**With 4 registers + hardware long ALU:**

```asm
ladd:   sthw        // captures A,B,C,D → compute unit, 1 cycle
        wait        // (if multi-cycle)
        ldhw nxt    // pushes 64-bit result back to A,B (or A,B,C,D)
```

### Cycle Count Comparison

| Operation | Current (microcode) | With 4-reg + HW unit |
|-----------|--------------------:|---------------------:|
| ladd      |          26 cycles  |       ~3 cycles      |
| lsub      |          38 cycles  |       ~3 cycles      |
| lcmp      |      ~60-80 cycles  |       ~3 cycles      |
| lmul      |     ~50+ cycles     |    ~3-5 cycles (DSP) |
| lshl/lshr |          28 cycles  |       ~3 cycles      |
| land/lor  |           8 cycles  |       ~3 cycles      |
| dadd      |    software trap    |   ~10 cycles (FPU)   |
| dmul      |    software trap    |   ~10 cycles (FPU)   |

The biggest wins are `lcmp` (up to ~27x faster) and `lsub` (~13x). Even
`land`/`lor`/`lxor` at 8 cycles would drop to ~3 — a modest 2.7x improvement.

## Cost-Benefit Analysis

### Pros

1. **Massive speedup on long/double operations** — 10-27x for arithmetic,
   eliminating the most expensive microcode sequences in the entire ISA.

2. **Simpler microcode** — long bytecodes drop from 8-80 instructions to
   2-3 instructions each. Less microcode ROM, easier to verify.

3. **Enables double-precision FPU** — currently doubles trap to software.
   With 4 registers, a hardware double FPU becomes practical since operands
   can be delivered in one shot.

4. **Cleaner compute unit interface** — `ComputeUnitBundle` already has
   64-bit ports. Currently the microcode must manually decompose and
   recompose 64-bit values through scratch RAM.

5. **`stmul`/`ldmul` already work this way for 32 bits** — extending from
   {A,B} to {A,B,C,D} is a natural generalization of the existing pattern.

### Cons

1. **Significant bypass complexity** — 9 address comparators, priority muxing,
   and write forwarding added to the stack stage. Currently zero hazard logic.

2. **Critical path risk** — the read data path gains ~2 LUT levels
   (comparators + 5:1 mux). The stack stage is already on or near the
   critical path at 80 MHz on Cyclone IV.

3. **Register management overhead** — every push/pop now shifts 4 registers
   instead of 2. C and D need fill/spill logic from/to RAM on every stack
   depth change.

4. **Verification burden** — the current design is hazard-free by construction.
   4 registers require proving that the bypass logic is correct for all
   combinations of `ld`/`st`/`ldmi`/`stmi`/`push`/`pop`/`dup`/`swap` and
   the 4-register window. This is a large formal verification surface.

5. **Area cost** — 4×32-bit registers + bypass muxes + comparators. Modest
   in absolute terms but non-trivial on smaller FPGAs (Cyclone IV).

6. **Limited applicability** — the speedup only matters for code that uses
   `long` or `double` types. JOP's primary use case (real-time embedded Java)
   tends to use `int` and `float`. The current `stmul`/`ldmul` mechanism
   handles `imul`/`fmul` fine with 2 registers.

## Alternative: Keep 2 Registers, Stage Through Scratch

Instead of expanding to 4 TOS registers, the compute unit could accumulate
operands over multiple cycles using the existing 2-register interface:

```asm
ladd:   sthw_lo     // compute unit latches A (lo0) and B (lo1) internally
        pop
        pop         // drop the two low words
        sthw_hi     // compute unit latches A (hi0) and B (hi1), starts op
        pop
        pop
        ldhw_hi     // push result high
        ldhw_lo nxt // push result low
```

This takes ~8 cycles instead of ~3, but avoids all bypass complexity. Compared
to the current 26-80 cycle microcode, 8 cycles is still a 3-10x speedup.
The compute unit would need a small 2-word input register file (2 cycles to
load instead of 1), but the stack stage remains untouched.

A second variant uses scratch RAM, similar to the FPU auto-capture pattern
already used for `fadd`/`fmul`:

```asm
ladd:   stm a       // save lo0 to scratch
        stm b       // save hi0 to scratch
        stm c       // save lo1 to scratch
        // hi1 is now TOS (A register)
        // write to compute unit I/O address, auto-captures TOS (hi1)
        ldi hw_ladd
        stmwa
        stmwd       // captures A=hi1, triggers compute unit
        // now feed remaining operands from scratch via I/O writes
        ldm c
        stmwd       // feeds lo1
        ldm b
        stmwd       // feeds hi0
        ldm a
        stmwd       // feeds lo0, starts computation
        // ... wait + read result
```

This is ~14 cycles — still 2-6x faster than pure microcode, zero hardware
changes to the stack stage, and uses the existing I/O write mechanism.

## Conclusion

The 4-register design gives the best raw performance (~3 cycles for 64-bit
ops) but at substantial hardware complexity cost. The bypass logic adds area,
verification burden, and critical path risk to a pipeline stage that currently
has none.

The staged 2-register alternative (8 cycles) captures most of the speedup
(3-10x vs 10-27x) with zero stack stage modifications. For a processor
targeting real-time embedded Java where `long`/`double` usage is secondary,
the 2-register staged approach is likely the better engineering trade-off.

The 4-register design becomes more compelling if double-precision floating
point is a primary requirement, where the additional 2-3x speedup per
operation compounds across FP-intensive workloads.
