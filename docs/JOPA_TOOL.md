# Jopa - JOP Microcode Assembler

## Overview

Jopa is the microcode assembler for the Java Optimized Processor (JOP). It translates microcode assembly into various formats needed for FPGA synthesis and simulation.

## Location

**Source**: `/home/peter/git/jop.arch/jop/java/tools/src/com/jopdesign/tools/Jopa.java`

**Build System**: `/home/peter/git/jop.arch/jop/asm/Makefile`

## What Jopa Does

```
┌──────────────┐
│  jvm.asm     │  Microcode assembly source
│              │  (human-written JVM implementation)
└──────┬───────┘
       │
       ▼
┌──────────────┐
│  Jopa Tool   │  Microcode assembler (Java)
│              │  - Parses assembly
│              │  - Assigns addresses
│              │  - Creates jump table
│              │  - Generates multiple formats
└──────┬───────┘
       │
       ├────────────────────────────────────────┐
       │                                        │
       ▼                                        ▼
┌──────────────┐                        ┌──────────────┐
│ VHDL Outputs │                        │ Data Outputs │
├──────────────┤                        ├──────────────┤
│ jtbl.vhd     │  Jump table            │ mem_rom.dat  │  ROM data
│ rom.vhd      │  Microcode ROM         │ mem_ram.dat  │  RAM data
│              │                        │ rom.mif      │  Altera ROM
│              │                        │ ram.mif      │  Altera RAM
└──────────────┘                        └──────────────┘
       │                                        │
       └────────────────┬───────────────────────┘
                        ▼
              ┌──────────────────┐
              │ jvmgen.asm       │  Annotated assembly
              │                  │  (with addresses)
              └──────────────────┘
```

## Input Files

### Primary Input: jvm.asm

**Location**: `/home/peter/git/jop.arch/jop/asm/src/jvm.asm`

**Format**: Microcode assembly language

**Contents**:
- JVM instruction implementations
- Microcode instruction sequences
- Labels and symbols
- Comments

**Example snippet**:
```asm
; NOP implementation
nop:
    nop             ; Microcode NOP
    fetch           ; Fetch next bytecode

; ICONST_0 implementation
iconst_0:
    ldi 0           ; Load immediate 0
    nop
    fetch
```

### Additional Inputs

- Configuration files
- Include files (if any)
- Symbol definitions

## Output Files

All outputs generated to: `/home/peter/git/jop.arch/jop/asm/generated/`

### 1. jtbl.vhd - Jump Table (VHDL)

**Purpose**: Maps Java bytecode to microcode ROM addresses

**Format**: VHDL entity with case statement

**Size**: ~12KB

**Usage**: Instantiated in bcfetch.vhd

**Example**:
```vhdl
entity jtbl is
port (
    bcode : in std_logic_vector(7 downto 0);
    q : out std_logic_vector(10 downto 0)
);
end jtbl;

architecture rtl of jtbl is
begin
    process(bcode) begin
        case bcode is
            when "00000000" => addr <= "01000011000";  -- nop
            when "00000011" => addr <= "01000011010";  -- iconst_0
            -- ... 256 entries
        end case;
    end process;
end rtl;
```

### 2. rom.vhd - Microcode ROM (VHDL)

**Purpose**: Contains microcode instructions

**Format**: VHDL ROM implementation

**Size**: ~97KB

**Usage**: Instantiated in fetch.vhd

### 3. jvmgen.asm - Annotated Assembly

**Purpose**: Assembly with addresses for debugging

**Format**: Assembly with address annotations

**Size**: ~52KB

**Example**:
```asm
0218: nop          ; NOP implementation
0219:   nop
021A:   fetch

021A: iconst_0    ; ICONST_0 implementation
021B:   ldi 0
021C:   nop
021D:   fetch
```

### 4. mem_rom.dat - ROM Data (Generic)

**Purpose**: Microcode ROM initialization (RECOMMENDED for SpinalHDL)

**Format**: One decimal value per line

**Size**: ~2KB

**Example**:
```
256
256
192
256
27
...
```

**Usage**: Simple to parse, one microcode instruction per line

### 5. mem_ram.dat - RAM Data (Generic)

**Purpose**: Stack buffer initialization (RECOMMENDED for SpinalHDL)

**Format**: One decimal value per line

**Size**: ~2KB

**Example**:
```
0
0
0
0
...
```

**Layout**:
- Lines 0-31: Variables
- Lines 32-63: Constants
- Lines 64+: Stack (SP starts at 64)

### 6. rom.mif / ram.mif - Altera MIF Format

**Purpose**: FPGA tool initialization (Quartus)

**Format**: Altera Memory Initialization File

**Usage**: Quartus synthesis

**Note**: More complex format, use .dat files for SpinalHDL

### 7. Other Generated Files

- `xram_block.vhd` - RAM block wrapper
- `xv4ram_block.vhd` - Virtex-4 RAM block
- `actelram_initrom.vhd` - Actel FPGA ROM

## Running Jopa

### From Command Line

```bash
# Navigate to asm directory
cd /home/peter/git/jop.arch/jop/asm

# Run make (builds Jopa if needed, then runs it)
make

# Output appears in generated/
ls -la generated/
```

### Makefile Process

```makefile
# Simplified Makefile process
1. Compile Jopa.java (if needed)
2. Run Jopa with jvm.asm as input
3. Generate all output files to generated/
```

### Manual Execution

```bash
# Build Jopa tool
cd /home/peter/git/jop.arch/jop/java/tools
ant jop_tools

# Run Jopa directly
java -cp build/lib/jop-tools.jar com.jopdesign.tools.Jopa \
    -o /home/peter/git/jop.arch/jop/asm/generated \
    /home/peter/git/jop.arch/jop/asm/src/jvm.asm
```

## Jopa Features

### Address Assignment

- Assigns microcode ROM addresses to labels
- Resolves forward references
- Optimizes address space

### Jump Table Generation

- Analyzes bytecode implementations
- Finds entry points for each Java bytecode
- Generates mapping (bytecode → microcode address)

### Multiple Output Formats

- VHDL (for synthesis)
- MIF (for Altera)
- DAT (generic, easy to parse)

### Error Checking

- Syntax validation
- Undefined label detection
- Address range checking

## For SpinalHDL Migration

### Don't Need to Run Jopa

The generated files already exist in the reference repository:
- `/home/peter/git/jop.arch/jop/asm/generated/`

### Use These Files

1. **jtbl.vhd** - Extract jump table mappings
   - Use `tools/scripts/extract_jtbl.py`
   - Generates Scala initialization data

2. **mem_rom.dat** - Use directly for ROM initialization
   - Simple format: one decimal per line
   - Parse in Scala/Python

3. **mem_ram.dat** - Use directly for RAM initialization
   - Simple format: one decimal per line
   - Parse in Scala/Python

### Parsing Examples

#### Python: Parse mem_rom.dat
```python
def load_microcode_rom(filepath):
    """Load microcode ROM from .dat file"""
    rom = []
    with open(filepath) as f:
        for line in f:
            value = int(line.strip())
            rom.append(value)
    return rom

# Usage
rom_data = load_microcode_rom('/home/peter/git/jop.arch/jop/asm/generated/mem_rom.dat')
```

#### Scala: Initialize SpinalHDL ROM
```scala
import scala.io.Source

def loadMicrocodeRom(filepath: String): Seq[Int] = {
  Source.fromFile(filepath)
    .getLines()
    .map(_.trim.toInt)
    .toSeq
}

// In SpinalHDL component
val romData = loadMicrocodeRom("mem_rom.dat")
val rom = Mem(Bits(12 bits), 2048)
romData.zipWithIndex.foreach { case (value, addr) =>
  rom.initialContent(addr) = value
}
```

## Microcode Instruction Format

Jopa processes microcode instructions with specific bit fields:

```
12-bit microcode instruction
┌─────┬─────┬─────┬─────┐
│ Op  │ Src │ Dst │ Ctl │
└─────┴─────┴─────┴─────┘

Op:  Operation code
Src: Source operand
Dst: Destination
Ctl: Control flags
```

(Exact format defined in jvm.asm and Jopa source)

## Modifying the JVM Implementation

### If You Need to Change Microcode:

1. **Edit**: `/home/peter/git/jop.arch/jop/asm/src/jvm.asm`
2. **Assemble**: `cd /home/peter/git/jop.arch/jop/asm && make`
3. **Re-extract**: Run extraction scripts for SpinalHDL
4. **Test**: Verify changes with test vectors

### For SpinalHDL Migration:

**DON'T modify** the microcode initially!
- Use existing generated files
- Port the processor first
- Verify cycle-accurate compatibility
- Only then consider microcode changes

## Reference Documentation

### Jopa Source Code

Study for understanding microcode format:
```bash
less /home/peter/git/jop.arch/jop/java/tools/src/com/jopdesign/tools/Jopa.java
```

### Microcode Assembly

Study for understanding JVM implementation:
```bash
less /home/peter/git/jop.arch/jop/asm/src/jvm.asm
```

### Generated Outputs

Reference for data extraction:
```bash
ls -la /home/peter/git/jop.arch/jop/asm/generated/
```

## Key Takeaways

1. **Jopa is a tool** - Java-based microcode assembler
2. **Already run** - Generated files exist in reference repository
3. **Use generated files** - Extract data, don't regenerate
4. **Simplest formats**: mem_rom.dat and mem_ram.dat
5. **Jump table**: Extract from jtbl.vhd with extraction script
6. **Don't modify** - Use as-is for initial migration

## See Also

- [docs/MICROCODE_AND_ROMS.md](MICROCODE_AND_ROMS.md) - Generated files documentation
- [docs/STACK_ARCHITECTURE.md](STACK_ARCHITECTURE.md) - Stack buffer architecture
- [tools/scripts/extract_jtbl.py](../tools/scripts/extract_jtbl.py) - Jump table extraction tool

## Note on JOP Build System

The main JOP directory (`/home/peter/git/jop.arch/jop/`) contains a complex Makefile that builds the entire JOP toolchain, including:
- Jopa (microcode assembler)
- JopSim (simulator)
- Java tools
- FPGA synthesis scripts
- Test programs

**For SpinalHDL migration**: You don't need to understand or use this complex Makefile. The generated files you need already exist in `asm/generated/`.

**If you need to regenerate** (unlikely):
```bash
cd /home/peter/git/jop.arch/jop/asm
make  # Much simpler - just runs Jopa
```
