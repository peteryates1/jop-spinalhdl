# Original VHDL Reference Files

This directory contains copies of the original JOP VHDL files for reference during migration.

## Source

These files are copied from: `/home/peter/git/jop.arch/jop/vhdl/`

## Current Files

Files copied to `core/`:
- bcfetch.vhd
- cache.vhd
- cache_two_blocks.vhd
- core.vhd
- decode.vhd
- fetch.vhd
- jopcpu.vhd
- jop_types.vhd
- mul.vhd
- shift.vhd
- stack.vhd

## Adding More Files

If you need additional files during development:

```bash
# Copy from reference
cp /home/peter/git/jop.arch/jop/vhdl/<subdir>/<file>.vhd original/vhdl/<subdir>/

# For core files
cp /home/peter/git/jop.arch/jop/vhdl/core/<file>.vhd original/vhdl/core/
```

## Important

- **These are read-only references** - Do not modify
- **The source of truth** is `/home/peter/git/jop.arch/jop/vhdl/`
- If you need to reference the full context, use the paths in `../REFERENCE.md`

## Structure

```
vhdl/
├── core/          # Core processor files (copied)
├── memory/        # (create if needed for memory controller references)
├── scio/          # (create if needed for I/O references)
└── simpcon/       # (create if needed for bus references)
```

## See Also

- [../REFERENCE.md](../REFERENCE.md) - Detailed reference guide
- Full reference: `/home/peter/git/jop.arch/jop/vhdl/`
