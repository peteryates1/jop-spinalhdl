# Original JOP Reference

## Reference Repository

Location: `/home/peter/git/jop.arch/jop/`

This is the original JOP implementation that serves as the golden reference for the SpinalHDL port.

## Directory Structure

```
/home/peter/git/jop.arch/jop/vhdl/
├── core/           # Core processor (PRIMARY FOCUS for initial migration)
├── memory/         # Memory controllers and interfaces
├── scio/           # Serial communication and I/O
├── simpcon/        # Simple bus interconnect
├── top/            # Top-level designs for various boards
└── simulation/     # Simulation-specific files
```

## Core Files (Primary Migration Target)

Located in: `/home/peter/git/jop.arch/jop/vhdl/core/`

### Pipeline Stages

- **bcfetch.vhd** - Bytecode fetch and address translation
- **fetch.vhd** - Microcode fetch stage
- **decode.vhd** - Microcode decode stage
- **stack.vhd** - Stack execution unit (TOS/NOS)

### Memory Components

- **cache.vhd** - Method cache
- **cache_two_blocks.vhd** - Two-block cache variant
- **jbc.vhd** - Java bytecode memory (if separate from bcfetch)

### Support Modules

- **jop_types.vhd** - Type definitions and constants (IMPORTANT - needed by all modules)
- **jopcpu.vhd** - Top-level CPU integration
- **core.vhd** - Core integration
- **mul.vhd** - Multiplier unit
- **shift.vhd** - Shifter unit

## Usage in This Project

### For vhdl-tester Agent

When analyzing modules, you'll need to reference:

1. **Primary module** - The one being tested (e.g., bcfetch.vhd)
2. **jop_types.vhd** - Type definitions used by all modules
3. **Related modules** - Dependencies and interfaces

Example for bcfetch:
```bash
# Primary
/home/peter/git/jop.arch/jop/vhdl/core/bcfetch.vhd

# Dependencies
/home/peter/git/jop.arch/jop/vhdl/core/jop_types.vhd
/home/peter/git/jop.arch/jop/vhdl/core/decode.vhd  # for interface
```

### For spinalhdl-developer Agent

You'll need access to:
1. Original VHDL for translation reference
2. Type definitions (jop_types.vhd) → translate to Scala
3. Interface specifications from connected modules

### Local Copies

We've copied core files to `original/vhdl/core/` but you can always reference the full repository:

```bash
# To view any file
cat /home/peter/git/jop.arch/jop/vhdl/core/<file>.vhd

# To copy additional files if needed
cp /home/peter/git/jop.arch/jop/vhdl/core/<file>.vhd original/vhdl/core/
```

## Key Dependencies

### jop_types.vhd

**Critical** - Defines common types used throughout:
- Constants (widths, sizes)
- Record types
- Function signatures

Must be translated to Scala early as `jop/types/JopTypes.scala`

### Entity Dependencies

```
jopcpu (top-level)
  └── core
      ├── bcfetch (bytecode fetch)
      │   └── uses: jop_types
      ├── fetch (microcode fetch)
      │   └── uses: jop_types
      ├── decode (decode)
      │   └── uses: jop_types
      ├── stack (execution)
      │   └── uses: jop_types, mul, shift
      └── cache (method cache)
          └── uses: jop_types
```

## Files Beyond Core

### memory/ - Memory Controllers
- DDR controllers
- SRAM controllers
- Flash interfaces

### scio/ - Serial Communication & I/O
- UART
- SPI
- I2C
- GPIO
- Timers

### simpcon/ - Simple Interconnect
- Bus arbitration
- Address decoding
- Bridge components

These will be migrated in later phases after core is complete.

## Migration Strategy

### Phase 1: Core Processor (Current Focus)
1. jop_types.vhd → types/JopTypes.scala
2. bcfetch.vhd → pipeline/BytecodeFetch.scala
3. fetch.vhd → pipeline/MicrocodeFetch.scala
4. decode.vhd → pipeline/MicrocodeDecode.scala
5. stack.vhd → pipeline/Execute.scala (or Stack.scala)
6. mul.vhd, shift.vhd → pipeline/Alu.scala (or separate)
7. cache.vhd → memory/MethodCache.scala

### Phase 2: Memory Subsystem
- Memory controllers
- Cache variants

### Phase 3: I/O and Peripherals
- SCIO modules
- SimpCon bus

### Phase 4: Integration
- Top-level for each board
- System integration

## Additional Resources

### Microcode and Generated Files (CRITICAL!)

**Location**: `/home/peter/git/jop.arch/jop/asm/generated/`

**Generated Files** (created by microcode assembler):

1. **jtbl.vhd** - Jump Table (Java bytecode → microcode address)
   - Maps all 256 Java bytecodes to microcode ROM addresses
   - Pure combinational logic
   - Used by bcfetch.vhd
   - **CRITICAL**: This IS the bytecode-to-microcode translation!

2. **rom.vhd** - Microcode ROM
   - Contains the actual JVM implementation in microcode
   - ~97KB of microcode instructions
   - Used by fetch.vhd

3. **jvmgen.asm** - Microcode assembly source (~52KB)
   - Human-readable JVM implementation
   - Source for rom.vhd and jtbl.vhd

4. **RAM/ROM initialization files**
   - ram.mif, rom.mif (Altera format)
   - mem_ram.dat, mem_rom.dat (generic)

**Source Location**: `/home/peter/git/jop.arch/jop/asm/`
- jvm.asm - Microcode source
- Jopa.java - Microcode assembler
- Makefile - Build system

**See Also**: [docs/MICROCODE_AND_ROMS.md](../docs/MICROCODE_AND_ROMS.md) for detailed documentation

### Java Runtime
Location: `/home/peter/git/jop.arch/jop/java/`
- JVM implementation
- Will need for functional testing

### Documentation
Location: `/home/peter/git/jop.arch/jop/doc/`
- Architecture documentation
- Original design notes

## Quick Reference Commands

```bash
# List all core VHDL files
ls /home/peter/git/jop.arch/jop/vhdl/core/*.vhd

# View a specific file
less /home/peter/git/jop.arch/jop/vhdl/core/bcfetch.vhd

# Search for a signal/entity across all files
grep -r "entity bcfetch" /home/peter/git/jop.arch/jop/vhdl/core/

# Copy a file to local
cp /home/peter/git/jop.arch/jop/vhdl/core/<file>.vhd original/vhdl/core/

# Find dependencies (what a file 'uses')
grep "^use " /home/peter/git/jop.arch/jop/vhdl/core/bcfetch.vhd
```

## Notes

- **Don't modify** files in `/home/peter/git/jop.arch/` - it's the reference
- **Do copy** files you're working on to `original/vhdl/core/`
- **Do reference** the full repository for context and dependencies
- Keep notes in `docs/migration/<module>-notes.md` as you analyze files
