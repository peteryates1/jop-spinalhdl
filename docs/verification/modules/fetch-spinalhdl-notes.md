# FetchStage SpinalHDL Translation Notes

## Overview

This document describes the translation of `fetch.vhd` to SpinalHDL using the Pipeline API, resulting in `FetchStage.scala`.

**Source**: `original/vhdl/core/fetch.vhd` (188 lines)
**Target**: `core/spinalhdl/src/main/scala/jop/pipeline/FetchStage.scala`
**Verification**: 15/15 CocoTB tests passing

## Architecture Summary

The fetch stage implements the microcode ROM fetch portion of the JOP pipeline:

```
                    +------------------------------------------+
                    |              FetchStage                   |
                    |                                          |
  br    ----------->|    +--------+      +---------+           |
  jmp   ----------->|    | PC MUX |----->|  PC REG |           |---> dout
  bsy   ----------->|    +--------+      +---------+           |---> nxt (jfetch)
  jpaddr ---------->|        ^               |                  |---> opd (jopdfetch)
                    |        |               v                  |
                    |    +--------+      +---------+           |
  clk   ----------->|    | Offset |      |   ROM   |           |
  reset ----------->|    | Calc   |      | (mcrom) |           |
                    |    +--------+      +---------+           |
                    |                          |               |
                    |                     +----v----+          |
                    |                     | IR REG  |          |
                    |                     +---------+          |
                    +------------------------------------------+
```

## Key Translation Decisions

### 1. ROM Implementation

**VHDL** (separate component):
```vhdl
component rom is
  generic (width : integer; addr_width : integer);
  port (clk : in std_logic; address : in std_logic_vector; q : out std_logic_vector);
end component;
```

**SpinalHDL** (inline memory):
```scala
val rom = Mem(Bits(config.romWidth bits), config.romDepth)
val romAddrReg = Reg(UInt(config.pcWidth bits)) init(0)
val romData = rom.readAsync(romAddrReg)
```

**Note**: SpinalHDL `readAsync` with a registered address achieves the same timing as VHDL's ROM with "registered address, unregistered output".

### 2. Signed Offset Arithmetic

**VHDL** (explicit conversion):
```vhdl
brdly <= std_logic_vector(signed(pc) +
         to_signed(to_integer(signed(ir(5 downto 0))), pc_width));
```

**SpinalHDL** (cleaner syntax):
```scala
val branchOffset = ir(5 downto 0).asSInt.resize(config.pcWidth bits)
brdly := (pc.asSInt + branchOffset).asUInt
```

### 3. PC MUX Priority Logic

**VHDL** (nested if-elsif):
```vhdl
if jfetch='1' then
    pc_mux <= jpaddr;
else
    if br='1' then
        pc_mux <= brdly;
    elsif jmp='1' then
        pc_mux <= jpdly;
    else
        if (pcwait='1' and bsy='1') then
            pc_mux <= pc;
        else
            pc_mux <= pc_inc;
        end if;
    end if;
end if;
```

**SpinalHDL** (when/elsewhen chain):
```scala
when(jfetch) {
  pcMux := io.jpaddr
}.elsewhen(io.br) {
  pcMux := brdly
}.elsewhen(io.jmp) {
  pcMux := jpdly
}.elsewhen(pcwait && io.bsy) {
  pcMux := pc
}.otherwise {
  pcMux := pcInc
}
```

### 4. Reset Behavior

**VHDL** (async reset with sync update):
```vhdl
process(clk, reset)
begin
    if (reset='1') then
        pc <= (others => '0');
        brdly <= (others => '0');
        jpdly <= (others => '0');
    elsif rising_edge(clk) then
        -- updates
    end if;
end process;
```

**SpinalHDL** (explicit clock domain):
```scala
val fetchClockDomain = ClockDomain(
  clock = clk,
  reset = reset,
  config = ClockDomainConfig(
    clockEdge = RISING,
    resetKind = ASYNC,
    resetActiveLevel = HIGH
  )
)
```

### 5. Wait Instruction Detection

**VHDL**:
```vhdl
if (rom_data(i_width-1 downto 0)="0100000001") then
    pcwait <= '1';
end if;
```

**SpinalHDL**:
```scala
pcwait := False
when(romInstr === B(config.waitOpcode, config.iWidth bits)) {
  pcwait := True
}
```

## Pipeline API Integration

The `FetchStage` component includes a Pipeline API output node for connecting to downstream stages:

```scala
val outputNode = Node()

// Create payload instances for this configuration
val PC_PAYLOAD = FetchPayloads.PC(config)
val INSTR_PAYLOAD = FetchPayloads.INSTRUCTION(config)

// Export payloads to output node
outputNode(PC_PAYLOAD) := pc
outputNode(INSTR_PAYLOAD) := ir
outputNode(FetchPayloads.JFETCH) := jfetch
outputNode(FetchPayloads.JOPDFETCH) := jopdfetch
outputNode(FetchPayloads.PC_WAIT) := pcwait

outputNode.valid := True
outputNode.ready := True
```

**Usage in a pipeline**:
```scala
val fetchStage = FetchStage(config)
val decodeNode = Node()
val link = StageLink(fetchStage.outputNode, decodeNode)
Builder(link)
```

## Test Compatibility

### Interface Matching

The `FetchStageTb` wrapper provides exact interface compatibility with `fetch_tb.vhd`:

| Port | Type | Direction | Notes |
|------|------|-----------|-------|
| clk | std_logic | in | Clock |
| reset | std_logic | in | Async reset |
| br | std_logic | in | Branch control |
| jmp | std_logic | in | Jump control |
| bsy | std_logic | in | Memory busy |
| jpaddr | std_logic_vector(9:0) | in | Jump target |
| nxt | std_logic | out | jfetch signal |
| opd | std_logic | out | jopdfetch signal |
| dout | std_logic_vector(9:0) | out | Instruction |
| pc_out | std_logic_vector(9:0) | out | Debug: PC |
| ir_out | std_logic_vector(9:0) | out | Debug: IR |

Key techniques used:
- `noIoPrefix()` removes the SpinalHDL `io_` prefix
- Explicit clock domain with async reset
- `Bits` instead of `UInt` for std_logic_vector ports

### ROM Initialization

The ROM is initialized with the same test patterns as `fetch_tb.vhd`:

| Address | jfetch | jopdfetch | Instruction | Purpose |
|---------|--------|-----------|-------------|---------|
| 0 | 0 | 0 | 0x000 | NOP (skipped) |
| 2 | 0 | 0 | 0x101 | Wait instruction |
| 3 | 0 | 0 | 0x005 | Branch offset +5 |
| 4 | 0 | 0 | 0x03D | Branch offset -3 |
| 50 | 1 | 0 | 0x000 | jfetch trigger |
| 51 | 0 | 1 | 0x000 | jopdfetch trigger |

## Verification Results

All 15 CocoTB tests pass:

1. `test_fetch_reset` - PC is 0 after reset
2. `test_fetch_sequential` - PC increments each cycle
3. `test_fetch_wait_instruction` - PC stalls when pcwait AND bsy
4. `test_fetch_branch_forward` - Forward branch works
5. `test_fetch_branch_backward` - Backward branch works
6. `test_fetch_jump_forward` - Forward jump works
7. `test_fetch_jfetch_signal` - jfetch loads jpaddr
8. `test_fetch_nxt_opd_outputs` - nxt/opd from ROM bits
9. `test_fetch_priority_br_over_jmp` - Branch wins over jump
10. `test_fetch_dout_latency` - 1-cycle output latency
11. `test_fetch_from_vectors` - 24 JSON test vectors
12. `test_fetch_pc_wraparound` - PC wraps at boundary
13. `test_fetch_multiple_stalls` - Extended stall behavior
14. `test_fetch_instruction_patterns` - ROM patterns verified
15. `test_fetch_coverage_summary` - Feature coverage

## Files

| File | Description |
|------|-------------|
| `core/spinalhdl/src/main/scala/jop/pipeline/FetchStage.scala` | SpinalHDL implementation |
| `core/spinalhdl/generated/vhdl/FetchStageTb.vhd` | Generated VHDL for testing |
| `verification/cocotb/tests/test_fetch.py` | CocoTB test suite |
| `verification/test-vectors/modules/fetch.json` | Test vectors |

## Build Commands

Generate VHDL:
```bash
cd core/spinalhdl
sbt "runMain jop.pipeline.FetchStageVhdl"
```

Run CocoTB tests:
```bash
cd verification/cocotb
make VHDL_SOURCES="vhdl/FetchStageTb.vhd" TOPLEVEL=fetchstagetb MODULE=tests.test_fetch
```

## Known Differences

1. **GHDL Case Sensitivity**: The toplevel must be specified as `fetchstagetb` (lowercase) when using CocoTB with GHDL.

2. **Memory Warning**: SpinalHDL generates a warning about `memReadAsync` write-first behavior. This does not affect functionality since the ROM is read-only.

3. **Package Functions**: SpinalHDL generates utility functions in `pkg_scala2hdl` that are not present in the original VHDL. These are internal helpers.

## Next Steps

1. Integrate with decode stage using Pipeline API
2. Add stall propagation for downstream backpressure
3. Consider moving ROM initialization to external file for production

---

**Document Version**: 1.0
**Created**: 2025-12-28
**Author**: SpinalHDL Migration Agent
