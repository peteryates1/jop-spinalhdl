# mul.vhd Analysis

**Agent**: vhdl-tester
**Date**: 2025-12-27
**Source File**: `/home/peter/git/jopmin/vhdl/core/mul.vhd`
**Status**: Analysis Complete

## Overview

`mul.vhd` implements a **bit-serial multiplier** for the JOP processor. It performs 32-bit unsigned multiplication in a pipelined fashion, processing 2 bits per cycle.

## Key Characteristics

- **Type**: Bit-serial multiplier (processes 2 bits/cycle)
- **Algorithm**: Radix-4 multiplication (processes pairs of bits)
- **Latency**: 16 cycles (width/2 = 32/2)
- **Throughput**: Can start new multiplication every cycle (via `wr` signal)
- **Data Width**: Configurable (default 32 bits)
- **Signedness**: **Unsigned multiplication only**

## Entity Interface

### Generic Parameters

```vhdl
width : integer := 32  -- Data word width (default 32 bits)
```

### Ports

| Port | Direction | Width | Description |
|------|-----------|-------|-------------|
| `clk` | in | 1 | Clock input |
| `ain` | in | 32 | Multiplicand (A operand) |
| `bin` | in | 32 | Multiplier (B operand) |
| `wr` | in | 1 | Write/start signal (active high) |
| `dout` | out | 32 | Product output (A × B) |

## Behavior Analysis

### Operation Principle

This is a **radix-4 bit-serial multiplier** that:
1. Processes 2 bits of the multiplier (B) per clock cycle
2. Accumulates partial products
3. Shifts operands by 2 positions each cycle
4. Completes in width/2 = 16 cycles

### State Machine

**Start Multiplication** (`wr = '1`):
- Loads `ain` → register `a`
- Loads `bin` → register `b`
- Clears partial product `p` to 0
- Takes 1 cycle

**Multiplication in Progress** (`wr = '0`):
- Each cycle processes 2 bits of `b`:
  - If `b(0) = '1'`: Add `a` to partial product
  - If `b(1) = '1'`: Add `a << 1` to partial product
- Shift `a` left by 2 positions (`a <= a(width-3 downto 0) & "00"`)
- Shift `b` right by 2 positions (`b <= "00" & b(width-1 downto 2)`)
- Takes 16 cycles to complete

### Timing Diagram

```
Cycle  wr  Operation                           b bits processed
-----  --  ----------------------------------  ----------------
  0    1   Load ain→a, bin→b, clear p          (setup)
  1    0   Process b(1:0), shift               bits 1-0
  2    0   Process b(1:0), shift               bits 3-2
  3    0   Process b(1:0), shift               bits 5-4
  ...
 16    0   Process b(1:0), shift               bits 31-30
 17    0   Result available in p               (done)
```

**Total Latency**: 17 cycles from `wr='1'` to final result

## Critical Implementation Details

### Pipelining

- **Can overlap**: New multiplication can start while previous is in progress
- **wr='1'** immediately loads new values, overwriting any in-progress computation
- **No busy signal**: Software must track timing

### Unsigned Only

- Performs **unsigned × unsigned = unsigned**
- For signed multiplication, software must:
  1. Take absolute values
  2. Multiply unsigned
  3. Fix sign of result

### Overflow Behavior

- Result is **lower 32 bits** of full 64-bit product
- Upper 32 bits are discarded
- Example: `0xFFFF_FFFF × 0xFFFF_FFFF = 0xFFFF_FFFE_0000_0001`
  - Output: `0x0000_0001` (lower 32 bits)

### Example Multiplication

**Simple**: `5 × 3 = 15`

```
ain = 0x0000_0005 = ...00101
bin = 0x0000_0003 = ...00011

Cycle 0 (wr=1): a=5, b=3, p=0
Cycle 1:  b(1:0)=11 → add a + a<<1 = 5 + 10 = 15
          p = 15, a = 20, b = 0
Cycle 2-16: b=0, so no more additions, p stays 15

Result: p = 15 = 0x0000_000F ✓
```

### Edge Cases

1. **Multiply by zero**: `X × 0 = 0`
   - `b = 0`, so no additions occur
   - Result: 0 (correct)

2. **Multiply by one**: `X × 1 = X`
   - `b(0) = 1`, adds `a` once
   - Result: X (correct)

3. **Power of 2**: `X × 4 = X << 2`
   - `b = 0x0000_0004 = ...00100`
   - `b(1:0) = 00` (cycle 1), `b(1:0) = 01` (cycle 2)
   - Adds `a<<2` on cycle 2
   - Result: X << 2 (correct)

4. **Maximum values**: `0xFFFF_FFFF × 0xFFFF_FFFF`
   - Full product: `0xFFFF_FFFE_0000_0001` (64 bits)
   - Output: `0x0000_0001` (lower 32 bits only)

## Test Vector Requirements

### Reset/Initialization Tests

```json
{
  "name": "startup_state",
  "type": "reset",
  "description": "Output should be 0 before any multiplication",
  "cycles": 1,
  "expected_outputs": [
    {"cycle": 1, "signals": {"dout": "0x0"}}
  ]
}
```

### Basic Multiplication Tests

```json
{
  "name": "multiply_5_times_3",
  "type": "microcode",
  "description": "Simple multiplication: 5 × 3 = 15",
  "cycles": 18,
  "inputs": [
    {"cycle": 1, "signals": {"ain": "0x5", "bin": "0x3", "wr": "0x1"}},
    {"cycle": 2, "signals": {"wr": "0x0"}}
  ],
  "expected_outputs": [
    {"cycle": 17, "signals": {"dout": "0xF"}}
  ]
}
```

### Edge Case Tests

1. **Multiply by zero**
2. **Multiply by one**
3. **Multiply by power of 2**
4. **Maximum values (overflow)**
5. **Sequential multiplications** (test pipelining)

### Timing Tests

- Verify result available exactly at cycle 17 after `wr='1'`
- Verify intermediate values during computation
- Verify overlapping multiplications work correctly

## Resource Usage

From comments in VHDL:
- **244 Logic Cells** (Altera FPGA)
- Very efficient bit-serial implementation

## Dependencies

- **None** - Standalone module
- No dependencies on `jop_types` or other modules
- Self-contained multiplier

## Test Strategy

1. **CocoTB on original VHDL**:
   - Create `verification/cocotb/tests/test_mul.py`
   - Use JSON test vectors from `verification/test-vectors/modules/mul.json`
   - Verify all test cases pass

2. **SpinalHDL translation**:
   - Create `core/spinalhdl/src/main/scala/jop/pipeline/Mul.scala`
   - Generate VHDL with SpinalHDL
   - Test generated VHDL with same CocoTB tests

3. **ScalaTest verification**:
   - Create `verification/scalatest/src/test/scala/jop/pipeline/MulSpec.scala`
   - Use same JSON test vectors
   - Verify Scala simulation matches VHDL

## Translation Notes for SpinalHDL

### Key Challenges

1. **Radix-4 logic**: The dual-bit processing logic must be preserved exactly
2. **Shift operations**: Scala bit manipulation syntax
3. **Unsigned arithmetic**: SpinalHDL UInt type
4. **Variable vs Signal**: `prod` is a variable (combinational within process)

### Example Translation Structure

```scala
class Mul(config: MulConfig) extends Component {
  val io = new Bundle {
    val ain  = in UInt(config.width bits)
    val bin  = in UInt(config.width bits)
    val wr   = in Bool()
    val dout = out UInt(config.width bits)
  }

  val a = Reg(UInt(config.width bits)) init(0)
  val b = Reg(UInt(config.width bits)) init(0)
  val p = Reg(UInt(config.width bits)) init(0)

  when(io.wr) {
    a := io.ain
    b := io.bin
    p := 0
  } otherwise {
    val prod = cloneOf(p)
    prod := p

    when(b(0)) {
      prod := prod + a
    }
    when(b(1)) {
      prod := (prod |>> 1) + (a(width-2 downto 0) ## U(0, 1 bits))
    }
    p := prod

    a := a(width-3 downto 0) ## U(0, 2 bits)
    b := U(0, 2 bits) ## b(width-1 downto 2)
  }

  io.dout := p
}
```

## Verification Checklist

- [ ] Analyze VHDL implementation
- [ ] Document timing and behavior
- [ ] Create comprehensive test vectors (JSON)
- [ ] Implement CocoTB test using vectors
- [ ] Verify test passes on original VHDL
- [ ] Translate to SpinalHDL
- [ ] Generate VHDL from SpinalHDL
- [ ] Verify generated VHDL with CocoTB
- [ ] Create ScalaTest with same vectors
- [ ] Verify ScalaTest passes
- [ ] Cross-check: CocoTB results == ScalaTest results

## Next Steps

1. Create JSON test vectors: `verification/test-vectors/modules/mul.json`
2. Create CocoTB test: `verification/cocotb/tests/test_mul.py`
3. Run tests on original VHDL
4. Proceed to SpinalHDL translation

## References

- **VHDL Source**: `/home/peter/git/jopmin/vhdl/core/mul.vhd`
- **Algorithm**: Radix-4 bit-serial multiplication
- **JOP Documentation**: Comments indicate 244 LCs for implementation
