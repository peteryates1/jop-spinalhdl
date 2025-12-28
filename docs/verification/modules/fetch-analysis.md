# fetch.vhd Analysis - Microcode ROM Fetch Stage

## Overview

The `fetch.vhd` module implements the microcode ROM fetch stage of the JOP (Java Optimized Processor) pipeline. This stage is responsible for:
- Fetching microcode instructions from internal ROM
- Managing the program counter (PC)
- Handling branches and jumps with relative offsets
- Implementing the wait instruction for memory stalls
- Generating jfetch/jopdfetch signals for Java bytecode dispatch

**Source file:** `original/vhdl/core/fetch.vhd` (188 lines)

## Architecture

### Block Diagram

```
                    +------------------------------------------+
                    |              fetch.vhd                    |
                    |                                          |
  br    ----------->|    +--------+      +---------+           |
  jmp   ----------->|    | PC MUX |----->|  PC REG |           |---> dout
  bsy   ----------->|    +--------+      +---------+           |---> nxt (jfetch)
  jpaddr ---------->|        ^               |                  |---> opd (jopdfetch)
                    |        |               v                  |
                    |    +--------+      +---------+           |
  clk   ----------->|    | Offset |      |   ROM   |           |
  reset ----------->|    | Calc   |      |  (mcrom)|           |
                    |    +--------+      +---------+           |
                    |                          |               |
                    |                     +----v----+          |
                    |                     | IR REG  |          |
                    |                     +---------+          |
                    +------------------------------------------+
```

### Generics

| Generic   | Type    | Default | Description                              |
|-----------|---------|---------|------------------------------------------|
| pc_width  | integer | -       | Address bits of internal instruction ROM |
| i_width   | integer | -       | Instruction width                        |

**Typical configuration:** `pc_width=10, i_width=10` (1024 ROM entries, 10-bit instructions)

### Ports

| Port   | Direction | Width      | Description                            |
|--------|-----------|------------|----------------------------------------|
| clk    | in        | 1          | System clock                           |
| reset  | in        | 1          | Asynchronous reset                     |
| nxt    | out       | 1          | jfetch signal (fetch Java bytecode)    |
| opd    | out       | 1          | jopdfetch signal (fetch Java operand)  |
| br     | in        | 1          | Branch control signal                  |
| jmp    | in        | 1          | Jump control signal                    |
| bsy    | in        | 1          | Memory busy signal (from memory module)|
| jpaddr | in        | pc_width   | Jump address for Java bytecode fetch   |
| dout   | out       | i_width    | Instruction output                     |

## Functional Description

### ROM Structure

The microcode ROM stores instructions with additional control bits:

```
ROM word format: [jfetch][jopdfetch][instruction]
                    ^        ^           ^
                 bit n+1   bit n     bits n-1:0

Where n = i_width
Total ROM word width = i_width + 2
```

- **jfetch (bit i_width+1):** When '1', triggers Java bytecode fetch
- **jopdfetch (bit i_width):** When '1', indicates Java operand fetch
- **instruction (bits i_width-1:0):** Microcode instruction

### PC Control Logic

The PC selection follows this priority (highest to lowest):

1. **jfetch='1':** Load from `jpaddr` (Java bytecode dispatch)
2. **br='1':** Load from `brdly` (branch target)
3. **jmp='1':** Load from `jpdly` (jump target)
4. **pcwait='1' AND bsy='1':** Hold current PC (stall)
5. **Default:** Increment PC (pc + 1)

```vhdl
-- PC MUX priority logic
if jfetch='1' then
    pc_mux <= jpaddr;
else
    if br='1' then
        pc_mux <= brdly;
    elsif jmp='1' then
        pc_mux <= jpdly;
    else
        if (pcwait='1' and bsy='1') then
            pc_mux <= pc;           -- Stall
        else
            pc_mux <= pc_inc;       -- Normal increment
        end if;
    end if;
end if;
```

### Branch and Jump Offsets

**Branch offset:** 6-bit signed from `ir[5:0]`
- Range: -32 to +31
- Target: `brdly = PC + sign_extend(ir[5:0])`

**Jump offset:** 9-bit signed from `ir[i_width-2:0]`
- Range: -256 to +255
- Target: `jpdly = PC + sign_extend(ir[8:0])`

Both offsets are registered (calculated on rising edge), so the branch/jump takes effect one cycle after the instruction is in IR.

### Wait Instruction

The wait instruction (opcode `0b0100000001` = `0x101`) implements memory synchronization:

1. When decoded from ROM output, `pcwait` is set on next rising edge
2. When `pcwait='1' AND bsy='1'`, PC holds its value
3. When `bsy='0'`, PC resumes incrementing

This allows the pipeline to stall while waiting for memory operations.

## Timing Characteristics

### Clock Domains

All signals are synchronous to `clk`. The reset is asynchronous.

### Pipeline Timing

```
Cycle N:   pc_mux calculated (combinational)
           ROM address registered

Cycle N+1: ROM output available (combinational from registered address)
           IR captures instruction
           brdly/jpdly calculated from current PC and new IR
           pcwait decoded

Cycle N+2: dout reflects instruction from address at N
           Branch/jump targets ready
```

### Latency

| Operation          | Cycles | Notes                              |
|-------------------|--------|-------------------------------------|
| PC to dout        | 1      | ROM registered address, IR capture |
| PC to nxt/opd     | 0      | Combinational from ROM output      |
| Branch/Jump setup | 1      | Offset calculated from IR          |
| Branch/Jump exec  | 1      | PC loads target on next edge       |

### Critical Path

The critical path is through the PC MUX selection logic, which involves:
- ROM output combinational read
- pcwait detection
- Multiple MUX levels for PC source selection

## Test Strategy

### Test Bench Approach

Since `fetch.vhd` depends on an external ROM component, a test wrapper (`fetch_tb.vhd`) is used that includes:
- Integrated ROM with test patterns
- Debug outputs (`pc_out`, `ir_out`) for verification
- Configurable generics matching production defaults

### ROM Test Pattern Contents

| Address | jfetch | jopdfetch | Instruction | Purpose               |
|---------|--------|-----------|-------------|-----------------------|
| 0       | 0      | 0         | 0x000       | NOP (skipped)         |
| 1       | 0      | 0         | 0x000       | Regular NOP           |
| 2       | 0      | 0         | 0x101       | Wait instruction      |
| 3       | 0      | 0         | 0x005       | Branch offset +5      |
| 4       | 0      | 0         | 0x03D       | Branch offset -3      |
| 5       | 0      | 0         | 0x00A       | Jump offset +10       |
| 6       | 1      | 0         | 0x000       | jfetch trigger        |
| 7       | 0      | 1         | 0x000       | jopdfetch trigger     |
| 8       | 1      | 1         | 0x000       | Both triggers         |
| 32      | 0      | 0         | 0x01F       | Max branch +31        |
| 64      | 0      | 0         | 0x020       | Max branch -32        |
| 100     | 0      | 0         | 0x0FF       | Max jump +255         |

### Test Categories

1. **Reset Tests:** Verify PC=0 after reset
2. **Sequential Fetch:** Verify PC increments each cycle
3. **Wait Instruction:** Verify stall when pcwait AND bsy
4. **Branch Tests:** Forward and backward branches
5. **Jump Tests:** Forward and backward jumps
6. **Priority Tests:** Signal priority order
7. **Edge Cases:** Wraparound, max offsets
8. **Output Tests:** nxt, opd, dout timing

## SpinalHDL Translation Notes

### Pipeline API Considerations

When translating to SpinalHDL with Pipeline API:

1. **Stage Definition:**
   - Fetch is Stage 0 of the microcode pipeline
   - Input: PC (from previous cycle or control)
   - Output: Instruction, jfetch, jopdfetch

2. **ROM Implementation:**
   - Use SpinalHDL `Mem` with synchronous read
   - Consider using `readSync` for registered address

3. **PC Control:**
   - Use SpinalHDL `when/elsewhen/otherwise` for priority
   - Signal types: `Bool` for control, `UInt` for addresses

4. **Reset Behavior:**
   - SpinalHDL reset is typically synchronous
   - May need adjustment for async reset behavior

### Key Differences to Handle

1. **Two's complement sign extension:**
   ```scala
   // SpinalHDL equivalent of VHDL sign extension
   val offset6 = ir(5 downto 0).asSInt.resize(pc_width bits)
   val offset9 = ir(8 downto 0).asSInt.resize(pc_width bits)
   ```

2. **Wait instruction detection:**
   ```scala
   val isWait = ir === U"10'h101"
   val pcwait = RegNext(isWait)
   ```

3. **ROM instantiation:**
   ```scala
   val rom = Mem(Bits(i_width + 2 bits), 1 << pc_width)
   val romData = rom.readSync(pcMux)
   ```

## Coverage Requirements

Target: >= 95% coverage

### Statement Coverage
- All branches in PC MUX selection
- Wait instruction detection
- Offset calculations

### Branch Coverage
- jfetch='1' path
- br='1' path
- jmp='1' path
- Stall path (pcwait AND bsy)
- Increment path

### Functional Coverage
- All offset ranges (positive, negative, zero)
- PC boundary conditions
- Signal priority combinations

## Known Issues / Limitations

1. **First Instruction Skipped:** ROM address 0 is fetched during reset but instruction is never executed (documented behavior)

2. **Branch/Jump Delay:** Offset uses PC from when IR was set, not current PC - this is intentional pipeline behavior

3. **Wait Timing:** `pcwait` and `bsy` combination is combinational - timing-critical in real implementation

## References

- JOP Architecture Documentation
- `core.vhd` - Core instantiation of fetch stage
- `decode.vhd` - Consumer of fetch output
- `bcfetch.vhd` - Java bytecode fetch stage (uses jfetch signal)

---

**Document Version:** 1.0
**Created:** 2025-12-28
**Author:** VHDL Verification Agent
