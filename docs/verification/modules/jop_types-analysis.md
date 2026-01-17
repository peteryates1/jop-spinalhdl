# jop_types.vhd Analysis

**Agent**: vhdl-tester
**Date**: 2025-12-27
**Source File**: `/home/peter/git/jopmin/vhdl/core/jop_types.vhd`
**Status**: Analysis Complete

## Overview

`jop_types.vhd` is the foundation package for the entire JOP processor. It defines all the record types and constants used for communication between modules. This module must be translated first as all other modules depend on it.

## Key Characteristics

- **Type**: VHDL Package (no entity/architecture)
- **Sequential Logic**: None - just type definitions and constants
- **Dependencies**:
  - `work.jop_config_global.all` - Global configuration constants
  - `work.sc_pack.all` - SimpCon interface types (via jop_config_global)
- **Used By**: All JOP core modules

## Dependencies Analysis

### jop_config_global.vhd Constants

Location: `/home/peter/git/jopmin/vhdl/top/jop_config_global.vhd`

```vhdl
-- Stack/RAM size
constant STACK_SIZE_GLOBAL : integer := 8;  -- # of address bits

-- Object Cache Configuration
constant USE_OCACHE : std_logic := '1';
constant OCACHE_ADDR_BITS : integer := SC_ADDR_SIZE;  -- 23 bits
constant OCACHE_WAY_BITS : integer := 4;
constant OCACHE_MAX_INDEX_BITS : integer := 8;
constant OCACHE_INDEX_BITS : integer := 3;

-- Array Cache Configuration
constant USE_ACACHE : std_logic := '1';
constant ACACHE_ADDR_BITS : integer := SC_ADDR_SIZE;  -- 23 bits
constant ACACHE_MAX_INDEX_BITS : integer := SC_ADDR_SIZE;  -- 23 bits
constant ACACHE_WAY_BITS : integer := 4;
constant ACACHE_FIELD_BITS : integer := 2;
```

### sc_pack.vhd Constants

Location: `/home/peter/git/jopmin/vhdl/simpcon/sc_pack.vhd`

```vhdl
constant SC_ADDR_SIZE : integer := 23;  -- SimpCon address width
constant RDY_CNT_SIZE : integer := 2;   -- Ready counter width
```

**Important**: SimpCon (Simple Interconnect) is the on-chip bus system.

## Constants Defined

### Memory Management Unit (MMU) Instructions

```vhdl
constant STMUL : std_logic_vector(2 downto 0) := "000";  -- Load/store multiple
constant STMWA : std_logic_vector(2 downto 0) := "001";  -- Write allocate
constant STMRA : std_logic_vector(2 downto 0) := "010";  -- Read allocate
constant STGF  : std_logic_vector(2 downto 0) := "011";  -- Get field
constant STPF  : std_logic_vector(2 downto 0) := "100";  -- Put field
constant STGFS : std_logic_vector(2 downto 0) := "101";  -- Get field static
constant STPFS : std_logic_vector(2 downto 0) := "110";  -- Put field static
constant STGFA : std_logic_vector(2 downto 0) := "111";  -- Get field array
```

These are 3-bit instruction codes for memory operations.

### Method Cache Constants

```vhdl
constant METHOD_SIZE_BITS : integer := 10;  -- 1024 words max method size
```

## Record Types Defined

### 1. mem_in_type - Memory Interface Input

**Purpose**: Inputs to memory management unit

```vhdl
type mem_in_type is record
    bc_rd       : std_logic;        -- Bytecode read request
    bc_wr       : std_logic;        -- Bytecode write request
    bc_addr_wr  : std_logic;        -- Bytecode address write
    cinval      : std_logic;        -- Cache invalidate
    instr       : std_logic_vector(2 downto 0);  -- MMU instruction (STMUL, etc.)
end record;
```

**Used for**: Controlling memory operations from the core.

### 2. mem_out_type - Memory Interface Output

**Purpose**: Outputs from memory management unit

```vhdl
type mem_out_type is record
    bc_out      : std_logic_vector(31 downto 0);  -- Bytecode output data
    bsy         : std_logic;                       -- Busy signal
end record;
```

**Used for**: Reading bytecode and checking memory busy status.

### 3. exception_type - Exception Information

**Purpose**: Exception handling state

```vhdl
type exception_type is record
    np          : std_logic;  -- Null pointer exception
    ab          : std_logic;  -- Array bounds exception
    ii          : std_logic;  -- Invalid instruction exception
end record;
```

**Used for**: Tracking which exception occurred.

### 4. irq_bcf_type - Bytecode Fetch IRQ

**Purpose**: Interrupt request from bytecode fetch stage

```vhdl
type irq_bcf_type is record
    irq     : std_logic;  -- Interrupt request
    ena     : std_logic;  -- Interrupt enable
end record;
```

### 5. irq_ack_type - IRQ Acknowledge

**Purpose**: Interrupt acknowledgment

```vhdl
type irq_ack_type is record
    ack     : std_logic;  -- Acknowledge signal
end record;
```

### 6. ser_in_type - Serial Input

**Purpose**: Serial port input interface

```vhdl
type ser_in_type is record
    rxd     : std_logic;  -- Receive data
    ncts    : std_logic;  -- Not clear to send (active low)
    ndsr    : std_logic;  -- Not data set ready (active low)
end record;
```

### 7. ser_out_type - Serial Output

**Purpose**: Serial port output interface

```vhdl
type ser_out_type is record
    txd     : std_logic;  -- Transmit data
    nrts    : std_logic;  -- Not ready to send (active low)
end record;
```

### 8. ocache_in_type - Object Cache Input

**Purpose**: Inputs to object cache

```vhdl
type ocache_in_type is record
    address : std_logic_vector(OCACHE_ADDR_BITS-1 downto 0);     -- 23 bits
    index   : std_logic_vector(OCACHE_MAX_INDEX_BITS-1 downto 0); -- 8 bits
    wrdata  : std_logic_vector(31 downto 0);
    wren    : std_logic;
    rden    : std_logic;
end record;
```

**Note**: Object cache caches object field accesses.

### 9. acache_in_type - Array Cache Input

**Purpose**: Inputs to array cache

```vhdl
type acache_in_type is record
    address : std_logic_vector(ACACHE_ADDR_BITS-1 downto 0);     -- 23 bits
    index   : std_logic_vector(ACACHE_MAX_INDEX_BITS-1 downto 0); -- 23 bits
    wrdata  : std_logic_vector(31 downto 0);
    wren    : std_logic;
    rden    : std_logic;
end record;
```

**Note**: Array cache caches array element accesses.

## Translation Strategy for SpinalHDL

### Constants

All constants should be translated to a Scala object:

```scala
object JopConstants {
  // MMU Instructions
  val STMUL = B"3'b000"
  val STMWA = B"3'b001"
  val STMRA = B"3'b010"
  val STGF  = B"3'b011"
  val STPF  = B"3'b100"
  val STGFS = B"3'b101"
  val STPFS = B"3'b110"
  val STGFA = B"3'b111"

  // Method cache
  val METHOD_SIZE_BITS = 10
}
```

### Record Types → Bundle Classes

Each VHDL record should become a SpinalHDL Bundle:

```scala
case class MemIn() extends Bundle {
  val bcRd      = Bool()
  val bcWr      = Bool()
  val bcAddrWr  = Bool()
  val cinval    = Bool()
  val instr     = Bits(3 bits)
}

case class MemOut() extends Bundle {
  val bcOut = Bits(32 bits)
  val bsy   = Bool()
}

// ... etc for all record types
```

### Configuration Dependencies

The configuration constants should be injected via `JopConfig`:

```scala
case class JopConfig(
  // From sc_pack
  scAddrSize: Int = 23,
  rdyCntSize: Int = 2,

  // From jop_config_global
  stackSizeGlobal: Int = 8,
  useOcache: Boolean = true,
  ocacheAddrBits: Int = 23,
  ocacheWayBits: Int = 4,
  ocacheMaxIndexBits: Int = 8,
  ocacheIndexBits: Int = 3,
  useAcache: Boolean = true,
  acacheAddrBits: Int = 23,
  acacheMaxIndexBits: Int = 23,
  acacheWayBits: Int = 4,
  acacheFieldBits: Int = 2,

  // Method cache
  methodSizeBits: Int = 10
)
```

## Testing Considerations

Since jop_types.vhd contains only type definitions and constants:

1. **No test vectors needed** - No behavioral logic to test
2. **No CocoTB tests needed** - Nothing to simulate
3. **Compilation verification only** - Ensure Scala types compile
4. **Integration testing** - Will be tested when used by other modules

## Migration Checklist

- [x] Analyze VHDL source
- [x] Document all constants
- [x] Document all record types
- [x] Identify dependencies (jop_config_global, sc_pack)
- [ ] Create `JopTypes.scala` with all Bundle definitions
- [ ] Create `JopConstants.scala` with all constants
- [ ] Update `JopConfig.scala` with configuration parameters
- [ ] Verify compilation with `sbt compile`
- [ ] Document in migration progress

## Important Notes

1. **Foundation Module**: All other modules depend on this - translate first!
2. **No Logic**: Pure type definitions - no simulation needed
3. **Configuration**: Many types are parameterized by config constants
4. **SimpCon**: The `sc_pack` types are for the on-chip bus interface
5. **Caches**: Object cache and array cache are optional (controlled by USE_OCACHE/USE_ACACHE)

## Next Steps

Proceed to **spinalhdl-developer** workflow:
1. Create `core/spinalhdl/src/main/scala/jop/types/JopTypes.scala`
2. Create `core/spinalhdl/src/main/scala/jop/types/JopConstants.scala`
3. Update existing `JopConfig.scala` with additional parameters
4. Compile and verify

## References

- **VHDL Source**: `/home/peter/git/jopmin/vhdl/core/jop_types.vhd`
- **Dependencies**:
  - `/home/peter/git/jopmin/vhdl/top/jop_config_global.vhd`
  - `/home/peter/git/jopmin/vhdl/simpcon/sc_pack.vhd`
- **Documentation**: [GETTING_STARTED.md](../../GETTING_STARTED.md)
