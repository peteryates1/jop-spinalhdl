# Reference Files Guide for Agents

## Overview

During the JOP migration, agents will need to reference the original VHDL files. This guide explains where files are located and how to access them.

## File Locations

### Primary Reference (Read-Only)
```
/home/peter/git/jop.arch/jop/vhdl/
├── core/           # Core processor (298 total VHDL files in repository)
├── memory/         # Memory controllers
├── scio/           # I/O peripherals
└── simpcon/        # Bus interconnect
```

### Local Copies (In This Project)
```
original/vhdl/
└── core/          # Core files copied for convenience
    ├── bcfetch.vhd
    ├── fetch.vhd
    ├── decode.vhd
    ├── stack.vhd
    ├── jop_types.vhd  # Important: type definitions
    └── ...
```

## For vhdl-tester Agent

When creating test vectors for a module:

### Step 1: Identify the Module
```bash
# Module to test
TARGET=/home/peter/git/jop.arch/jop/vhdl/core/bcfetch.vhd
```

### Step 2: Find Dependencies
```bash
# Check what the module uses
grep "^use " $TARGET

# Typical output:
# use work.jop_types.all;
```

### Step 3: Read Required Files
```bash
# Read the main module
cat /home/peter/git/jop.arch/jop/vhdl/core/bcfetch.vhd

# Read type definitions
cat /home/peter/git/jop.arch/jop/vhdl/core/jop_types.vhd

# Read interface modules (modules that connect to this one)
cat /home/peter/git/jop.arch/jop/vhdl/core/decode.vhd  # to understand interface
```

### Step 4: Document in Your Analysis
In `docs/verification/modules/bcfetch-analysis.md`, note:
- Main file: `core/bcfetch.vhd`
- Dependencies: `jop_types.vhd`
- Interfaces with: `decode.vhd`, `cache.vhd`
- External dependencies: Memory interface

## For spinalhdl-developer Agent

When porting a module to SpinalHDL:

### Step 1: Reference Files Needed
```bash
# Module being ported
VHDL_SOURCE=/home/peter/git/jop.arch/jop/vhdl/core/bcfetch.vhd

# Type definitions (translate these first!)
TYPES=/home/peter/git/jop.arch/jop/vhdl/core/jop_types.vhd

# Connected modules (for interface understanding)
INTERFACES=/home/peter/git/jop.arch/jop/vhdl/core/{decode,fetch,cache}.vhd
```

### Step 2: Create Parallel Structure
```
VHDL: /home/peter/git/jop.arch/jop/vhdl/core/bcfetch.vhd
  ↓
Scala: core/spinalhdl/src/main/scala/jop/pipeline/BytecodeFetch.scala

VHDL: /home/peter/git/jop.arch/jop/vhdl/core/jop_types.vhd
  ↓
Scala: core/spinalhdl/src/main/scala/jop/types/JopTypes.scala
```

### Step 3: Cross-Reference During Translation
Keep both files open:
- VHDL source on left
- SpinalHDL implementation on right
- Translate signal-by-signal, maintaining comments

## Critical: jop_types.vhd

This file is used by **ALL** modules and should be translated first.

### Location
```
/home/peter/git/jop.arch/jop/vhdl/core/jop_types.vhd
```

### Contains
- Constants (data widths, stack sizes, etc.)
- Common types (std_logic_vector ranges)
- Record types (if any)
- Shared functions

### Translation Priority
1. **First** create `core/spinalhdl/src/main/scala/jop/types/JopTypes.scala`
2. Translate all constants
3. Then port other modules that depend on it

## Common Files Reference

### Core Pipeline Stages
```bash
# Bytecode fetch (translates Java bytecode to microcode addresses)
/home/peter/git/jop.arch/jop/vhdl/core/bcfetch.vhd

# Microcode fetch (fetches microcode instructions)
/home/peter/git/jop.arch/jop/vhdl/core/fetch.vhd

# Microcode decode
/home/peter/git/jop.arch/jop/vhdl/core/decode.vhd

# Stack-based execution (TOS/NOS operations)
/home/peter/git/jop.arch/jop/vhdl/core/stack.vhd
```

### Support Modules
```bash
# Method cache
/home/peter/git/jop.arch/jop/vhdl/core/cache.vhd

# Multiplier
/home/peter/git/jop.arch/jop/vhdl/core/mul.vhd

# Shifter
/home/peter/git/jop.arch/jop/vhdl/core/shift.vhd

# Top-level integration
/home/peter/git/jop.arch/jop/vhdl/core/jopcpu.vhd
```

## Searching for Information

### Find where a signal is used
```bash
grep -r "signal_name" /home/peter/git/jop.arch/jop/vhdl/core/
```

### Find entity definitions
```bash
grep "^entity " /home/peter/git/jop.arch/jop/vhdl/core/*.vhd
```

### Find all uses of a type
```bash
grep "jop_types" /home/peter/git/jop.arch/jop/vhdl/core/*.vhd
```

### Find component instantiations
```bash
grep -A5 "component " /home/peter/git/jop.arch/jop/vhdl/core/jopcpu.vhd
```

## Copying Files to Local

If you need a file locally for easier access:

```bash
# Copy to appropriate location
cp /home/peter/git/jop.arch/jop/vhdl/core/<file>.vhd \
   /home/peter/workspaces/ai/jop/original/vhdl/core/

# Or for other subdirectories
mkdir -p /home/peter/workspaces/ai/jop/original/vhdl/memory
cp /home/peter/git/jop.arch/jop/vhdl/memory/<file>.vhd \
   /home/peter/workspaces/ai/jop/original/vhdl/memory/
```

## For spinalhdl-tester Agent

### Finding Test Vectors
Test vectors are shared in JSON, but you may need to reference VHDL to understand:
- Signal timing
- Reset behavior
- Edge cases

```bash
# Read the module to understand behavior
cat /home/peter/git/jop.arch/jop/vhdl/core/bcfetch.vhd

# Look at CocoTB test to see how it's tested
cat verification/cocotb/tests/test_bcfetch.py

# Create equivalent ScalaTest
# verification/scalatest/src/test/scala/jop/pipeline/BytecodeFetchSpec.scala
```

## For reviewer Agent

### Comparing Implementations
```bash
# Original VHDL
ORIGINAL=/home/peter/git/jop.arch/jop/vhdl/core/bcfetch.vhd

# Generated VHDL from SpinalHDL
GENERATED=/home/peter/workspaces/ai/jop/core/spinalhdl/generated/BytecodeFetch.vhd

# Compare structure (not exact match, but architecturally equivalent)
diff -u $ORIGINAL $GENERATED > docs/reviews/bcfetch-comparison.diff
```

## Summary Table

| Agent | Primary Reference | Secondary References |
|-------|------------------|---------------------|
| vhdl-tester | Target module VHDL | jop_types.vhd, interface modules |
| spinalhdl-developer | Target module VHDL, jop_types.vhd | Interface modules, integration examples |
| spinalhdl-tester | Test vectors (JSON) | Original VHDL for behavior understanding |
| reviewer | All of the above | Generated VHDL, test results |

## Important Notes

1. **Never modify** files in `/home/peter/git/jop.arch/` - it's the reference
2. **Always reference** from the full repository for context
3. **Copy locally** if you need frequent access
4. **Document dependencies** in your analysis files
5. **Keep track** of which VHDL version you're referencing (git hash if available)

## Questions to Ask When Referencing

1. What signals does this entity expose?
2. What types from jop_types.vhd does it use?
3. What other modules does it instantiate?
4. What are the timing/clocking requirements?
5. How does reset work?
6. What are the critical paths?
7. Are there any synthesis directives or pragmas?
8. What comments explain non-obvious behavior?

## See Also

- [original/REFERENCE.md](../../original/REFERENCE.md) - Detailed reference structure
- [original/vhdl/README.md](../../original/vhdl/README.md) - Local copy information
