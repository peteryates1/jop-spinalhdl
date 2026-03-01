# SD Card Application Loader

## Goal

Flash the FPGA bitstream and a loader application once, then swap Java
applications by placing `.jop` files on an SD card. This avoids
reprogramming flash for every application change.

## Architecture Constraint

JOP has no runtime class loading. JOPizer performs whole-program linking
at build time — all symbolic references are resolved to absolute memory
addresses. The `.jop` file is a single flat image containing runtime
classes, application classes, method tables, string tables, and class info
structures. There is no mechanism to parse `.class` files or link new code
at runtime.

**Consequence:** each `.jop` on the SD card must be a complete image
(runtime + application). The loader overwrites itself entirely when
loading a new application.

## Design

### Two-Stage Boot

```
Power-on
  |
  v
[Microcode] -- loads loader.jop from SPI flash into RAM
  |
  v
[Loader app] -- Java application linked with FAT32 + SD driver
  |
  +-- Init SD card (SdNativeBlockDevice)
  +-- Mount FAT32 filesystem
  +-- Read .jop file from SD card into RAM (overwriting self)
  +-- Jump to new application's boot entry
  |
  v
[User application] -- runs as if it was loaded directly from flash
```

### Loader Application

A small Java application linked with:

- `com.jopdesign.fat32.*` — FAT32 filesystem (already in `java/fat32/`)
- `com.jopdesign.hw.SdNative` — SD card hardware object
- `com.jopdesign.sys.Native` — raw memory access for loading

The loader:

1. Initializes the SD card via `SdNativeBlockDevice`
2. Mounts the FAT32 filesystem
3. Looks for a `.jop` file (e.g., `APP.JOP` or the first `.jop` found)
4. Reads the file contents
5. Parses the text-format `.jop` (comma-separated decimal 32-bit words)
6. Writes each word to RAM via `Native.wr(value, address)` starting at
   address 0 — this overwrites the loader itself
7. Triggers a soft reset or jumps to the new image's boot entry

### The Jump Problem

Once the loader overwrites itself in memory, it cannot continue executing
Java bytecode — the method cache would contain stale loader bytecode while
RAM now holds the new application.

Options for transferring control to the new image:

1. **Microcode-assisted reset**: Add a new `Native` call (e.g.,
   `Native.softReset()`) implemented in microcode that re-executes the
   boot sequence from the "load complete" point (read special pointers,
   invoke `Startup.boot()`). The microcode reads the entry point from the
   freshly-loaded image. This is the cleanest approach — the microcode
   already knows how to do this.

2. **Write then reset via I/O**: Write a control register that triggers a
   CPU reset. The microcode's reset vector would need to skip the flash
   load (since RAM already has the application) and go straight to the
   boot entry. This requires a "RAM already loaded" flag, e.g., a magic
   value at a known address.

3. **Careful self-replacement**: Load the new image into a staging area
   above the loader's heap, then have a small native-code trampoline
   (running from microcode ROM) copy it down to address 0 and jump to
   boot. More complex but avoids modifying the reset path.

Option 1 is recommended — a `softReset` microcode instruction that
re-reads the special pointer table from RAM and calls `Startup.boot()`.

### .jop File on SD Card

The `.jop` text format (comma-separated decimal words) is convenient for
the loader to parse — no complex binary format. The loader reads the file
line by line, extracts the integer before each comma, and writes it to
sequential memory addresses.

Alternatively, pre-convert to binary (4 bytes per word, big-endian) on
the host to simplify the loader and reduce SD card read time. The
`make_flash_image.py` script already does this conversion.

## Existing Components

| Component | Location | Status |
|-----------|----------|--------|
| SD native hardware | `spinalhdl/.../BmbSdNative.scala` | Working |
| SD native Java driver | `java/fat32/.../SdNativeBlockDevice.java` | Working |
| FAT32 filesystem | `java/fat32/.../Fat32FileSystem.java` | Working |
| FAT32 input stream | `java/fat32/.../Fat32InputStream.java` | Working |
| Native memory access | `java/runtime/.../Native.java` | Working |
| .jop text parser | `fpga/scripts/make_flash_image.py` (Python reference) | Reference |

## Implementation Steps

### Phase 1: Soft Reset Microcode Instruction

Add a `jopsys_softboot` instruction to `jvm.asm` that:

1. Reads the special pointer table address from RAM word 1
2. Loads `jjp`, `jjhp` from the table (same as normal boot)
3. Invokes the boot method at `mp+0` (`Startup.boot()`)

Register it in `Native.java` so Java code can call `Native.softReset()`.

### Phase 2: Loader Application

Write `SdBootLoader.java`:

1. Init SD card, mount FAT32
2. Open `APP.JOP` (or configurable filename)
3. Parse .jop text format, write words to RAM via `Native.wr()`
4. Call `Native.softReset()`

Link with: runtime + FAT32 + SD driver + loader main class.

### Phase 3: Flash Image with Loader

Build a flash image containing the FPGA bitstream + `loader.jop` (instead
of the application `.jop`). The loader boots from flash, then loads the
real application from SD card.

### Phase 4: Binary .jop Format (Optional)

For faster loading, define a binary `.jop` format (raw 32-bit words,
big-endian) and add a `make_binary_jop.py` conversion script. The loader
would then just stream bytes from SD to memory without parsing text.

## Performance Estimate

A typical `.jop` file is ~8500 words (34 KB for HelloWorld). Larger
applications might be 50-200 KB.

- SD card read at 10 MHz, 4-bit bus: ~5 MB/s theoretical
- FAT32 overhead + block reads: ~1-2 MB/s realistic
- 200 KB application: ~0.1-0.2 seconds to load

Even large applications should load in under a second from SD card.
