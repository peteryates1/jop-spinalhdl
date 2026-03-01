# JOP Stack Architecture

## Overview

JOP uses a stack-based execution model with a dedicated stack buffer in hardware.

## Stack Buffer Organization

```
+-------------------------------------+
|  Address 0-31:  Variables (32)      |  Local variables storage
+-------------------------------------+
|  Address 32-63: Constants (32)      |  Constant values
+-------------------------------------+
|  Address 64+:   Stack               |  <- SP starts at 64
|                 |                   |  <- Grows upward
|                 |                   |
|                                     |
+-------------------------------------+
```

## Stack Pointer (SP)

**Initial Value**: 64

**Direction**: Grows upward (increasing addresses)

**Operations**:
- **PUSH**: Increment SP, write value
- **POP**: Read value, decrement SP

## Top-of-Stack (TOS) / Next-of-Stack (NOS)

JOP keeps the top two stack values in registers for fast access:

```
TOS  (Top of Stack)     - Register holding top value
NOS  (Next of Stack)    - Register holding second value
SP   (Stack Pointer)    - Points to third value in buffer
```

**Example Stack State**:
```
TOS = 0x1234    (in register)
NOS = 0x5678    (in register)
SP  = 66        (points to stack buffer)

Stack Buffer[66] = 0x9ABC
Stack Buffer[65] = 0xDEF0
Stack Buffer[64] = 0x1111
...
```

## Stack Operations

### PUSH Operation
```
1. NOS -> Stack[SP]      (spill NOS to buffer)
2. TOS -> NOS            (shift TOS to NOS)
3. new_value -> TOS      (new value to TOS)
4. SP++                 (increment SP)
```

### POP Operation
```
1. NOS -> TOS            (shift NOS to TOS)
2. Stack[SP] -> NOS      (fill NOS from buffer)
3. SP--                 (decrement SP)
```

### DUP (Duplicate TOS)
```
1. NOS -> Stack[SP]      (spill NOS)
2. TOS -> NOS            (copy TOS to NOS)
3. TOS remains          (TOS stays same)
4. SP++
```

### SWAP (Swap TOS and NOS)
```
temp = TOS
TOS = NOS
NOS = temp
(SP unchanged)
```

## Variables (Address 0-31)

**Purpose**: Local variable storage

**Access**:
- ILOAD n - Load variable n onto stack
- ISTORE n - Store TOS to variable n

## Constants (Address 32-63)

**Purpose**: Frequently used constant values

**Initialization**: From `asm/generated/mem_ram.dat`

**Access**: Similar to variables but read-only in practice

## Stack Buffer Initialization

**File**: `asm/generated/mem_ram.dat`

**Format**: One decimal value per line, one per address

**Layout**:
- Lines 0-31: Variables
- Lines 32-63: Constants
- Lines 64+: Stack (SP starts at 64)

**Used by**: `StackStage` in `spinalhdl/src/main/scala/jop/StackStage.scala`

## Important Notes

1. **SP Initialization**: Always starts at 64, not 0
2. **Variables vs Stack**: Addresses 0-63 are special, stack starts at 64
3. **TOS/NOS Registers**: Top two values cached in registers for performance
4. **Spill/Fill**: Automatic movement between registers and buffer
5. **RAM Initialization**: Required for variables and constants

## See Also

- [microcode.md](microcode.md) - Microcode and ROM files
- [JOPA_TOOL.md](JOPA_TOOL.md) - Jopa microcode assembler
- VHDL reference: `/srv/git/jop/vhdl/core/stack.vhd`
