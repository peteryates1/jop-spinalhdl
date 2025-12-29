# Decode Stage Analysis (decode.vhd)

## Overview

**Source**: `original/vhdl/core/decode.vhd` (564 lines)
**Purpose**: Decode microcode instructions and generate control signals for execution
**Complexity**: HIGH - Central control logic with 40+ output signals

## Entity Interface

### Inputs

| Signal | Width | Description |
|--------|-------|-------------|
| clk | 1 | Clock |
| reset | 1 | Async reset |
| instr | i_width (10) | Microcode instruction from fetch stage |
| zf | 1 | Zero flag from ALU |
| nf | 1 | Negative flag from ALU (unused) |
| eq | 1 | Equal flag from ALU (unused) |
| lt | 1 | Less-than flag from ALU (unused) |
| bcopd | 16 | Bytecode operand from bcfetch (for MMU) |

### Outputs

#### Branch/Jump Control (to fetch stage)
| Signal | Width | Description |
|--------|-------|-------------|
| br | 1 | Branch enable (bz, bnz) |
| jmp | 1 | Jump enable (jmp instruction) |
| jbr | 1 | Bytecode branch enable (jbr instruction) |

#### Memory/MMU Control
| Signal | Width | Description |
|--------|-------|-------------|
| mem_in | record | Memory control signals (rd, wr, bc_rd, iaload, etc.) |
| mmu_instr | MMU_WIDTH | MMU instruction select |
| mul_wr | 1 | Multiplier write strobe |
| wr_dly | 1 | Write delay flag |
| dir | ram_width | Direct RAM address |

#### ALU Control Signals
| Signal | Width | Description |
|--------|-------|-------------|
| sel_sub | 1 | 0=add, 1=sub |
| sel_amux | 1 | 0=sum, 1=lmux |
| ena_a | 1 | Enable A register |
| sel_bmux | 1 | 0=a, 1=mem |
| sel_log | 2 | Logic operation: pop/st, and, or, xor |
| sel_shf | 2 | Shift operation: sr, sl, sra |
| sel_lmux | 3 | Load mux: log, shift, mem, io, reg |
| sel_imux | 2 | Immediate mux for operands |
| sel_rmux | 2 | Register mux: sp, vp, jpc |
| sel_smux | 2 | Stack pointer mux: sp, sp-1, sp+1, a |
| sel_mmux | 1 | Memory mux: 0=a, 1=b |
| sel_rda | 3 | Read address mux select |
| sel_wra | 3 | Write address mux select |
| wr_ena | 1 | RAM write enable |
| ena_b | 1 | Enable B register |
| ena_vp | 1 | Enable VP (variable pointer) register |
| ena_jpc | 1 | Enable JPC (Java PC) register |
| ena_ar | 1 | Enable AR (address) register |

## Functional Blocks

### 1. Branch/Jump Decode (Lines 140-170)

**Registered outputs**: br, jmp (1 cycle latency)
**Combinational output**: jbr (0 cycle latency)

```vhdl
-- Registered branch/jump (clocked process)
if((ir(9 downto 6)="0110" and zf='1') or   -- bz (branch if zero)
   (ir(9 downto 6)="0111" and zf='0')) then -- bnz (branch if not zero)
    br <= '1';
end if;
if (ir(9)='1') then                         -- jmp (MSB set = jump)
    jmp <= '1';
end if;

-- Combinational jbr (unclocked process)
jbr <= '0';
if ir="0100000010" then  -- jbr instruction (0x102)
    jbr <= '1';
end if;
```

**Key Instructions:**
- `0110xxxxxx` - BZ (branch if zero flag set)
- `0111xxxxxx` - BNZ (branch if not zero flag)
- `1xxxxxxxxx` - JMP (jump with offset in bits[8:0])
- `0100000010` - JBR (bytecode branch, triggers Java-level branch)

### 2. Stack Operation Decode (Lines 176-264)

**Combinational outputs**: is_pop, is_push, wr_ena, sel_rda, sel_wra, sel_smux, dir, sel_imux

Determines stack behavior based on instruction opcode:

| Opcode[9:6] | Operation | Stack Effect |
|-------------|-----------|--------------|
| 0000, 0001 | POP | SP-- |
| 0010, 0011 | PUSH | SP++ |
| 0110, 0111 | POP (branch) | SP-- |
| Others | No change | SP = SP |

**Read Address Select (sel_rda):**
```vhdl
sel_rda <= "110";  -- Default: SP
if (ir(9 downto 3)="0011101") then  -- ld, ldn, ldmi
    sel_rda <= ir(2 downto 0);      -- Use bits[2:0] for address
end if;
if (ir(9 downto 5)="00101") then    -- ldm
    sel_rda <= "111";               -- Direct address mode
end if;
if (ir(9 downto 5)="00110") then    -- ldi (load immediate)
    sel_rda <= "111";
    dir <= addr > 31 for constants  -- Address in constant pool
end if;
```

**Write Address Select (sel_wra):**
```vhdl
sel_wra <= "110";  -- Default: SP
if ir(9 downto 3)="0000010" then  -- st, stn, stmi
    sel_wra <= ir(2 downto 0);
end if;
if ir(9 downto 5)="00001" then    -- stm
    sel_wra <= "111";
end if;
```

**Stack Pointer Update (sel_smux):**
- `00` - SP unchanged
- `01` - SP-- (pop)
- `10` - SP++ (push)
- `11` - SP = A (stsp instruction)

### 3. ALU Control Generation (Lines 270-440)

**Registered outputs**: All ALU control signals (1 cycle latency)

Massive case statement decoding 50+ unique instructions into ALU control signals.

**Example instruction decodes:**

```vhdl
case ir is
    when "0000000000" => -- pop (no operation, just stack movement)
    when "0000000001" => -- and
        sel_log <= "01";
    when "0000000010" => -- or
        sel_log <= "10";
    when "0000000011" => -- xor
        sel_log <= "11";
    when "0000000100" => -- add
        sel_sub <= '0';   -- Addition mode
        sel_amux <= '0';  -- Use adder output
    when "0000000101" => -- sub
        sel_sub <= '1';   -- Subtraction mode
        sel_amux <= '0';
    when "0000011100" => -- ushr (unsigned shift right)
        sel_shf <= "00";
    when "0000011101" => -- shl (shift left)
        sel_shf <= "01";
    when "0000011110" => -- shr (arithmetic shift right)
        sel_shf <= "10";
    -- ... 40+ more instruction cases
end case;
```

**Load Mux Select (sel_lmux) Priority:**
1. `000` - Logic unit output (default)
2. `001` - Shifter output (ushr, shl, shr)
3. `010` - Memory output (ld, ldm, ldi)
4. `011` - Immediate operand (ld_opd_8u/16u/8s/16s)
5. `100` - MMU/Multiplier output (ldmrd, ldmul)
6. `101` - Register output (ldsp, ldvp, ldjpc)

### 4. MMU/Memory Control (Lines 446-558)

**Registered outputs**: mem_in record, mul_wr, wr_dly (1 cycle latency)

Decodes memory management instructions:

```vhdl
if ir(9 downto 4)="000100" then  -- MMU/mul instruction prefix
    wr_dly <= '1';
    case ir(MMU_WIDTH-1 downto 0) is
        when STMUL => mul_wr <= '1';        -- Start multiplier
        when STMWA => mem_in.addr_wr <= '1'; -- Store write address
        when STMRA => mem_in.rd <= '1';      -- Start memory read
        when STMWD => mem_in.wr <= '1';      -- Start memory write
        when STALD => mem_in.iaload <= '1';  -- Array load
        when STAST => mem_in.iastore <= '1'; -- Array store
        when STGF  => mem_in.getfield <= '1'; -- Get object field
        when STPF  => mem_in.putfield <= '1'; -- Put object field
        -- ... more memory operations
    end case;
end if;
```

## Key Characteristics

### Timing

1. **Branch/Jump Control**: 1 cycle latency (registered)
   - Instruction arrives → next cycle: br/jmp asserted

2. **Stack Control**: 0 cycle latency (combinational)
   - Instruction arrives → same cycle: address mux selects set

3. **ALU Control**: 1 cycle latency (registered)
   - Instruction arrives → next cycle: ALU configured

4. **Memory Control**: 1 cycle latency (registered)
   - Instruction arrives → next cycle: memory operation starts

### Pipeline Stage

Decode sits between fetch and execute:

```
Fetch → [IR register] → Decode → [Control registers] → Execute
  ↑                        ↓
  └──── br/jmp/jbr ────────┘
```

## Instruction Set Categories

### Stack Operations
- `pop` (0x00) - Remove top of stack
- `and/or/xor` (0x01-0x03) - Logic operations
- `add/sub` (0x04-0x05) - Arithmetic

### Load/Store
- `st0-st3, st` (0x10-0x14) - Store to local variables
- `ld0-ld3, ld` (0x38-0x3C) - Load from local variables
- `ldm/stm` (0x28-0x2F, 0x08-0x0F) - Load/store from main memory
- `ldi` (0x30-0x37) - Load immediate constants

### Shift Operations
- `ushr` (0x1C) - Unsigned shift right
- `shl` (0x1D) - Shift left
- `shr` (0x1E) - Arithmetic shift right

### Control Flow
- `bz` (0x60-0x6F) - Branch if zero
- `bnz` (0x70-0x7F) - Branch if not zero
- `jmp` (0x80-0xFF) - Unconditional jump
- `jbr` (0x102) - Bytecode branch

### Special
- `nop` (0x100) - No operation
- `wait` (0x101) - Wait for memory
- `dup` (0x3F8) - Duplicate top of stack

### MMU/Memory (prefix 0x40-0x4F)
- `stmul` (0x40) - Start multiplier
- `stmra/stmwa/stmwd` - Memory address/read/write
- `stald/stast` - Array load/store
- `stgf/stpf` - Object field get/put

## Testing Strategy

### Challenge: Very Complex Control Logic

With 40+ output signals and 50+ unique instructions, testing every combination is impractical.

### Recommended Approach

#### 1. Category-Based Testing

Test representative instructions from each category:

**Stack Operations** (5-10 tests):
- pop, add, sub, and, or, xor
- Push/pop behavior verification

**Load/Store** (10-15 tests):
- ld0-ld3, st0-st3 (local variables)
- ldm, stm (memory)
- ldi (constants)

**Shift** (3 tests):
- ushr, shl, shr

**Control Flow** (5-8 tests):
- bz (taken/not taken)
- bnz (taken/not taken)
- jmp
- jbr

**Special** (3-5 tests):
- nop, wait, dup

**MMU/Memory** (8-12 tests):
- Key memory operations (stmul, stmra, stmwa, stmwd)
- Array operations (stald, stast)

#### 2. Signal Group Verification

Rather than testing all 40 signals for every instruction, group by function:

**Group 1: Branch/Jump** (br, jmp, jbr)
**Group 2: Stack Control** (sel_rda, sel_wra, sel_smux, wr_ena)
**Group 3: ALU Arithmetic** (sel_sub, sel_amux, ena_a)
**Group 4: ALU Muxing** (sel_bmux, sel_lmux, sel_mmux)
**Group 5: ALU Operations** (sel_log, sel_shf)
**Group 6: Memory** (mem_in.*, mul_wr, wr_dly)

Test each group with instructions that exercise those specific signals.

#### 3. Edge Cases

- Instruction 0x000 (pop/nop boundary)
- Instruction 0x3FF (maximum value)
- Back-to-back branches
- Branch with/without zero flag set

### Estimated Test Count

- Category tests: ~40 test cases
- Edge cases: ~10 test cases
- **Total: ~50 test vectors** in decode.json

This is manageable and provides good coverage of the instruction set.

## Implementation Notes for SpinalHDL

### Challenges

1. **Large case statement**: 50+ cases in ALU control
2. **Mixed latency**: Some outputs combinational, some registered
3. **Record types**: `mem_in` is a VHDL record (need Bundle in SpinalHDL)
4. **Default values**: Many signals have defaults that are overridden selectively

### Recommended SpinalHDL Structure

```scala
case class DecodeStage(config: JopConfig) extends Component {
  val io = new Bundle {
    val instr = in Bits(config.iWidth bits)
    val zf = in Bool()
    // ... more inputs

    val br = out Bool()
    val jmp = out Bool()
    // ... more outputs

    val memIn = out(MemoryControl())  // Bundle for mem_in record
  }

  // Combinational decode for stack control
  val stackControl = new Area {
    val isPop = Bool()
    val isPush = Bool()
    // ... decode logic
  }

  // Registered decode for ALU control
  val aluControl = new ClockingArea(ClockDomain(...)) {
    val selSub = Reg(Bool()) init(False)
    val selAmux = Reg(Bool()) init(False)
    // ... more control registers

    switch(io.instr) {
      is(M"0000000000") { /* pop */ }
      is(M"0000000001") { /* and */ selLog := B"01" }
      // ... more cases
    }
  }
}
```

## Open Questions

1. **Dependency on jop_types.all**: What's in the `mem_in_type` record?
2. **MMU_WIDTH**: What value is this?
3. **ram_width**: Configuration parameter value?

These will be answered by examining `jop_types.vhd` and `jop_config.vhd`.

---

**Next Steps:**
1. Examine jop_types.vhd to understand record structures
2. Create decode.json with ~50 test vectors covering instruction categories
3. Create decode_tb.vhd wrapper for CocoTB testing
4. Implement test_decode.py
