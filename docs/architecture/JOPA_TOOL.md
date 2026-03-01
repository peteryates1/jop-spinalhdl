# Jopa - JOP Microcode Assembler

## Overview

Jopa is the microcode assembler for the Java Optimized Processor (JOP). It translates microcode assembly into various formats needed for FPGA synthesis and simulation.

## Location

**Source**: `java/tools/src/com/jopdesign/tools/Jopa.java`

**Build System**: `asm/Makefile`

## What Jopa Does

```
+------------------+
| asm/src/jvm.asm  |  Microcode assembly source
|                  |  (human-written with C preprocessor macros)
+--------+---------+
         |
         v
+------------------+
|  gcc -E -C -P    |  C preprocessor
|                  |  - Expands #define macros
|                  |  - Processes #ifdef directives
|                  |  - Removes C comments
+--------+---------+
         |
         v
+------------------+
| generated/       |  Preprocessed assembly
|   jvm.asm        |  (expanded, ready for assembly)
+--------+---------+
         |
         v
+------------------+
|  Jopa Tool       |  Microcode assembler (Java)
|                  |  - Parses assembly
|                  |  - Assigns addresses
|                  |  - Creates jump table
|                  |  - Generates multiple formats
+--------+---------+
         |
         +-----------------------------------------------+
         |                                               |
         v                                               v
+------------------+                        +------------------+
|  VHDL Outputs    |                        |  Data Outputs    |
+------------------+                        +------------------+
| jtbl.vhd         |  Jump table (VHDL)     | mem_rom.dat      |  ROM data
| JumpTableData    |  Jump table (Scala)    | mem_ram.dat      |  RAM data
|   .scala         |                        | rom.mif          |  Altera ROM
| rom.vhd          |  Microcode ROM         | ram.mif          |  Altera RAM
+------------------+                        +------------------+
```

## Input Files

### Primary Input: jvm.asm

**Location**: `asm/src/jvm.asm`

**Format**: Microcode assembly language with C preprocessor macros

**Variants**: Preprocessor defines select boot mode:
- `-DUSB_BOOT` — USB serial boot (default for FPGA)
- No define — ROM-only boot (for simulation/cocotb)

### Build Targets

```bash
cd asm && make serial   # Serial-boot microcode (FPGA)
cd asm && make rom      # ROM-only microcode (cocotb tests)
```

## Output Files

All outputs generated to: `asm/generated/`

### 1. JumpTableData.scala - Jump Table (Scala)

**Purpose**: Maps Java bytecode to microcode ROM addresses

**Format**: Scala object with `Seq[BigInt]` initialization data

**Usage**: Loaded by SpinalHDL at elaboration time via `JumpTableInitData`

### 2. mem_rom.dat - Microcode ROM Data

**Purpose**: Microcode ROM initialization

**Format**: One decimal value per line

**Usage**: Loaded by SpinalHDL at elaboration time

### 3. mem_ram.dat - Stack RAM Data

**Purpose**: Stack buffer initialization (variables + constants)

**Format**: One decimal value per line

**Layout**:
- Lines 0-31: Variables
- Lines 32-63: Constants
- Lines 64+: Stack (SP starts at 64)

### 4. jtbl.vhd / rom.vhd - VHDL Formats

**Purpose**: VHDL equivalents for original JOP synthesis and cocotb tests

### 5. rom.mif / ram.mif - Altera MIF Format

**Purpose**: Altera Memory Initialization File (not used by SpinalHDL)

### 6. jvmgen.asm - Annotated Assembly

**Purpose**: Assembly with address annotations for debugging

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

## Running Jopa

### Standard Build

```bash
# Build serial-boot microcode (used by FPGA targets)
cd asm && make serial

# Build ROM-only microcode (used by cocotb tests)
cd asm && make rom

# Full Java build (tools + runtime + apps, includes Jopa)
cd java && make all
```

The FPGA Makefiles (`fpga/*/Makefile`) include a `microcode` target that runs `make serial` automatically.

### Jopa Source

```bash
# Jopa assembler source
java/tools/src/com/jopdesign/tools/Jopa.java

# Jopa build
cd java/tools && make dist/jopa.jar
```

## Microcode Instruction Format

```
12-bit microcode instruction
+-----+-----+-----+-----+
| Op  | Src | Dst | Ctl |
+-----+-----+-----+-----+

Op:  Operation code
Src: Source operand
Dst: Destination
Ctl: Control flags
```

(Exact format defined in `asm/src/jvm.asm` and Jopa source)

## See Also

- [microcode.md](microcode.md) - Microcode architecture details
- [STACK_ARCHITECTURE.md](STACK_ARCHITECTURE.md) - Stack buffer architecture
