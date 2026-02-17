# JOP Stack Architecture

## Overview

JOP uses a stack-based execution model with a dedicated stack buffer in hardware.

## Stack Buffer Organization

```
┌─────────────────────────────────────┐
│  Address 0-31:  Variables (32)      │  Local variables storage
├─────────────────────────────────────┤
│  Address 32-63: Constants (32)      │  Constant values
├─────────────────────────────────────┤
│  Address 64+:   Stack               │  ← SP starts at 64
│                 ↓                   │  ← Grows upward
│                 ↓                   │
│                                     │
└─────────────────────────────────────┘
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
1. NOS → Stack[SP]      (spill NOS to buffer)
2. TOS → NOS            (shift TOS to NOS)
3. new_value → TOS      (new value to TOS)
4. SP++                 (increment SP)
```

### POP Operation
```
1. NOS → TOS            (shift NOS to TOS)
2. Stack[SP] → NOS      (fill NOS from buffer)
3. SP--                 (decrement SP)
```

### DUP (Duplicate TOS)
```
1. NOS → Stack[SP]      (spill NOS)
2. TOS → NOS            (copy TOS to NOS)
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

**Example**:
```
ILOAD 0  → Push variables[0] to TOS
ISTORE 5 → Pop TOS to variables[5]
```

## Constants (Address 32-63)

**Purpose**: Frequently used constant values

**Initialization**: From ram.mif / mem_ram.dat

**Access**: Similar to variables but read-only in practice

**Common Constants**:
- 0, 1, -1
- NULL reference
- Special values

## Stack Buffer Initialization

**File**: `/home/peter/git/jop/asm/generated/ram.mif` or `mem_ram.dat`

**Format**: Initialization data for the 64 pre-defined slots plus stack space

**Used by**: stack.vhd module

## SpinalHDL Implementation

### Stack Buffer
```scala
case class StackConfig(
  bufferSize: Int = 256,      // Total buffer size
  numVariables: Int = 32,     // Variables (0-31)
  numConstants: Int = 32,     // Constants (32-63)
  initialSP: Int = 64         // Stack starts at 64
)

class StackBuffer(config: StackConfig) extends Component {
  val io = new Bundle {
    val address = in UInt(log2Up(config.bufferSize) bits)
    val writeData = in Bits(32 bits)
    val writeEnable = in Bool()
    val readData = out Bits(32 bits)
  }

  // Stack buffer RAM
  val buffer = Mem(Bits(32 bits), config.bufferSize)

  // Initialize from file
  buffer.initFromFile("stack_init.bin")

  // Read/Write
  io.readData := buffer(io.address)
  when(io.writeEnable) {
    buffer(io.address) := io.writeData
  }
}
```

### Stack Execution Unit
```scala
class StackExecute(config: StackConfig) extends Component {
  val io = new Bundle {
    // Stack operations
    val push = in Bool()
    val pop = in Bool()
    val pushData = in Bits(32 bits)
    val popData = out Bits(32 bits)

    // Variable access
    val varRead = in Bool()
    val varWrite = in Bool()
    val varAddr = in UInt(5 bits)  // 0-31
    val varData = out Bits(32 bits)
    val varWriteData = in Bits(32 bits)
  }

  // TOS/NOS registers
  val tos = Reg(Bits(32 bits)) init(0)
  val nos = Reg(Bits(32 bits)) init(0)
  val sp = Reg(UInt(8 bits)) init(config.initialSP)

  // Stack buffer
  val buffer = new StackBuffer(config)

  // PUSH logic
  when(io.push) {
    buffer.io.address := sp
    buffer.io.writeData := nos
    buffer.io.writeEnable := True
    nos := tos
    tos := io.pushData
    sp := sp + 1
  }

  // POP logic
  when(io.pop) {
    buffer.io.address := sp - 1
    buffer.io.writeEnable := False
    tos := nos
    nos := buffer.io.readData
    sp := sp - 1
  }

  io.popData := tos
}
```

## Test Vectors for Stack

Test vectors should verify:

1. **Initialization**:
   - SP = 64 after reset
   - Variables 0-31 initialized correctly
   - Constants 32-63 initialized correctly

2. **PUSH/POP**:
   - PUSH increases SP
   - POP decreases SP
   - TOS/NOS updated correctly
   - Spill/fill to buffer works

3. **Variable Access**:
   - ILOAD from variables[0-31]
   - ISTORE to variables[0-31]
   - ILOAD from constants[0-31] (as offset 32)

4. **Stack Overflow/Underflow**:
   - SP never goes below 64
   - SP doesn't exceed buffer size
   - Proper exception handling

## Example Test Vectors

```json
{
  "module": "stack",
  "test_cases": [
    {
      "name": "reset_initializes_sp",
      "type": "reset",
      "description": "Verify SP initialized to 64 after reset",
      "initial_state": {},
      "inputs": [
        {"cycle": 0, "signals": {"reset": "0x1"}}
      ],
      "expected_state": {
        "sp": "0x40",
        "tos": "0x0",
        "nos": "0x0"
      },
      "cycles": 1
    },
    {
      "name": "push_increments_sp",
      "type": "microcode",
      "description": "PUSH operation increments SP and updates TOS/NOS",
      "initial_state": {
        "sp": "0x40",
        "tos": "0x1111",
        "nos": "0x2222"
      },
      "inputs": [
        {"cycle": 1, "signals": {"push": "0x1", "push_data": "0x3333"}}
      ],
      "expected_state": {
        "sp": "0x41",
        "tos": "0x3333",
        "nos": "0x1111"
      },
      "cycles": 1
    },
    {
      "name": "iload_variable_0",
      "type": "microcode",
      "description": "ILOAD 0 pushes variable 0 onto stack",
      "initial_state": {
        "variables[0]": "0xABCD"
      },
      "inputs": [
        {"cycle": 1, "signals": {"var_read": "0x1", "var_addr": "0x0"}}
      ],
      "expected_outputs": [
        {"cycle": 1, "signals": {"var_data": "0xABCD"}}
      ],
      "cycles": 1
    }
  ]
}
```

## Important Notes

1. **SP Initialization**: Always start at 64, not 0!
2. **Variables vs Stack**: Addresses 0-63 are special, stack starts at 64
3. **TOS/NOS Registers**: Top two values cached in registers for performance
4. **Spill/Fill**: Automatic movement between registers and buffer
5. **RAM Initialization**: Required for variables and constants

## See Also

- [docs/MICROCODE_AND_ROMS.md](MICROCODE_AND_ROMS.md) - Microcode and ROM files
- [docs/MODULE_DEPENDENCIES.md](MODULE_DEPENDENCIES.md) - Module dependencies
- stack.vhd - Original VHDL implementation
- ram.mif / mem_ram.dat - Stack buffer initialization data
