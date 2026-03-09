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

## Alternative: Keep 2 Registers, Add a Small Cache

Instead of expanding the register file, a small direct-mapped cache (4-8
entries) for recently-accessed local variables could capture most of the
performance benefit without the bypass complexity. Local variable access
patterns are predictable (small indices, repeated access), making them
cache-friendly. The cache would sit beside the stack RAM and only need
coherence with the RAM write path, not with the TOS registers.

## Conclusion

Expanding to 4 TOS registers is **feasible** but requires significant bypass
logic (9 comparators, priority muxing, write forwarding) that complicates what
is currently a clean, hazard-free pipeline stage. The 1-cycle delayed write is
not a fundamental blocker — it just means the forwarding path must be
included alongside the register bypass. The main costs are silicon area,
routing, and potential Fmax reduction from the added combinational depth on
the read data path.
