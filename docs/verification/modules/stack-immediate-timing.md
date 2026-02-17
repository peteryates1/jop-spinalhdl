# Stack Stage: Immediate Value Pipeline Timing

## Overview

The stack/execute stage (stage 4 of the JOP 4-stage microcode pipeline) contains an internal sub-pipeline for processing immediate values from Java bytecode operands.

## JOP 4-Stage Microcode Pipeline

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  Bytecode    │    │  Microcode   │    │  Microcode   │    │  Microcode   │
│  Fetch &     │───▶│  Fetch       │───▶│  Decode      │───▶│  Execute     │
│  Translate   │    │              │    │              │    │  (Stack)     │
└──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘
   Stage 1             Stage 2            Stage 3            Stage 4
```

## Immediate Value Sub-Pipeline (Within Execute Stage)

### VHDL Implementation (stack.vhd)

```vhdl
-- Signals
signal opd      : std_logic_vector(15 downto 0);  -- Input: bytecode operand
signal opddly   : std_logic_vector(15 downto 0);  -- Registered opd
signal imux     : std_logic_vector(31 downto 0);  -- Combinational processing
signal immval   : std_logic_vector(31 downto 0);  -- Registered immediate value

-- Combinational logic (lines 269-275)
process(opddly, sel_imux)
begin
    case sel_imux is
        when "00" => imux <= x"000000" & opddly(7 downto 0);     -- 8-bit unsigned
        when "01" => imux <= sign_extend(opddly(7 downto 0));    -- 8-bit signed
        when "10" => imux <= x"0000" & opddly;                   -- 16-bit unsigned
        when "11" => imux <= sign_extend(opddly);                -- 16-bit signed
    end case;
end process;

-- Registered pipeline (lines 440-441)
process(clk, reset)
begin
    if reset = '1' then
        opddly <= (others => '0');
        immval <= (others => '0');
    elsif rising_edge(clk) then
        opddly <= opd;        -- Stage 1: Register input operand
        immval <= imux;       -- Stage 2: Register processed immediate
    end if;
end process;

-- A register loading (lines 305-307)
process(clk, reset)
begin
    if rising_edge(clk) then
        if ena_a = '1' then
            a <= amux;        -- Stage 3: Load into A register (TOS)
        end if;
    end if;
end process;
```

## Timing Diagram

### Example: 8-bit Unsigned Immediate (0xFF)

```
Cycle 0: Set opd=0xFF, sel_imux=00, ena_a=1, sel_amux=1, sel_lmux=3
─────────────────────────────────────────────────────────────────────

     │  opd   │ opddly │  imux  │ immval │ A (aout)│
─────┼────────┼────────┼────────┼────────┼─────────┤
  0  │  0xFF  │  0x00  │  0x00  │  0x00  │  0x00   │ ← Inputs applied
     │   ↓    │        │ (comb) │        │         │
─────┼────────┼────────┼────────┼────────┼─────────┤
 clk │   ⤵    │   ⤵    │        │   ⤵    │   ⤵     │ ← Clock edge 0→1
─────┼────────┼────────┼────────┼────────┼─────────┤
  1  │  0xFF  │  0xFF  │  0xFF  │  0x00  │  0x00   │ ← opddly updated
     │        │        │ (comb) │        │         │   A loads old immval
─────┼────────┼────────┼────────┼────────┼─────────┤
 clk │        │        │        │   ⤵    │   ⤵     │ ← Clock edge 1→2
─────┼────────┼────────┼────────┼────────┼─────────┤
  2  │  0xFF  │  0xFF  │  0xFF  │  0xFF  │  0x00   │ ← immval updated
     │        │        │        │        │         │   A loads old immval (still 0!)
─────┼────────┼────────┼────────┼────────┼─────────┤
 clk │        │        │        │        │   ⤵     │ ← Clock edge 2→3
─────┼────────┼────────┼────────┼────────┼─────────┤
  3  │  0xFF  │  0xFF  │  0xFF  │  0xFF  │  0xFF   │ ← A loads new immval ✓
─────┴────────┴────────┴────────┴────────┴─────────┘
```

## Key Insight: Read-Before-Write

At each clock edge, **registers read their inputs BEFORE updating**. This means:

- **Clock 0→1**: A reads immval (value=0x00), THEN immval updates to 0x00
- **Clock 1→2**: A reads immval (value=0x00), THEN immval updates to 0xFF
- **Clock 2→3**: A reads immval (value=0xFF), THEN loads it ✓

## Total Latency: 3 Cycles

```
Cycle 0: Apply opd input
Cycle 1: opd → opddly (registered)
Cycle 2: imux → immval (registered)
Cycle 3: immval → A (registered) ← Check aout here!
```

## Test Vector Correction

**Original (incorrect):**
```json
{
  "inputs": [{"cycle": 0, "signals": {"opd": "0xFF", "ena_a": "0x1", ...}}],
  "expected_outputs": [{"cycle": 2, "signals": {"aout": "0xFF"}}],
  "cycles": 3
}
```

**Corrected:**
```json
{
  "inputs": [{"cycle": 0, "signals": {"opd": "0xFF", "ena_a": "0x1", ...}}],
  "expected_outputs": [{"cycle": 3, "signals": {"aout": "0xFF"}}],
  "cycles": 4
}
```

## Affected Test Cases

All immediate value extension tests:
- `imux_8u` - 8-bit unsigned
- `imux_8s_positive` - 8-bit signed (positive)
- `imux_8s_negative` - 8-bit signed (negative)
- `imux_16u` - 16-bit unsigned
- `imux_16s_positive` - 16-bit signed (positive)
- `imux_16s_negative` - 16-bit signed (negative)

## References

- Original VHDL: [stack.vhd](https://github.com/peteryates1/jop/blob/main/vhdl/core/stack.vhd)

## Date

2026-01-02 - Timing analysis and correction during CocoTB test development
