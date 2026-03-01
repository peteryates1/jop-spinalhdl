# Flash Boot — Autonomous Active Serial Configuration

The QMTECH EP4CGX150 board boots autonomously from the on-board W25Q128 SPI
flash. After the flash is programmed, the board runs Java programs on
power-up with no JTAG connection needed.

## How It Works

```
Power-on
  │
  ▼
FPGA AS controller reads bitstream from W25Q128 flash (0x000000)
  │
  ▼
JOP processor starts, microcode executes
  │
  ▼
Microcode sends SPI READ_DATA (0x03) to flash at offset 0x800000
  │
  ▼
.jop application streams in from flash → written to SDRAM
  │
  ▼
Java program runs
```

The FPGA's built-in Active Serial (AS) controller handles bitstream loading
automatically. Once JOP is running, the microcode uses the `BmbConfigFlash`
I/O peripheral to read the `.jop` application from a fixed offset in the
same flash chip.

MSEL[3:0] is hardwired to 0000 (Active Serial mode) on the QMTECH board.

## Flash Image Layout

```
W25Q128 (16 MB)
┌──────────────────────────┐ 0x000000
│                          │
│  FPGA bitstream          │  ~4.7 MB
│  (bit-reversed RPD)      │
│                          │
├──────────────────────────┤ ~0x4B0000
│  Padding (0xFF)          │  ~3.3 MB
├──────────────────────────┤ 0x800000
│                          │
│  JOP application (.jop)  │  variable
│  (big-endian 32-bit)     │
│                          │
├──────────────────────────┤
│  Free (0xFF)             │  remainder
└──────────────────────────┘ 0xFFFFFF
```

The `.jop` offset (0x800000 = 8 MB) leaves room for the full bitstream
plus padding, and gives the application up to 8 MB in the upper half
of the flash.

## Flash Image Format

### Why RPD, Not RBF

Quartus can produce both RBF (Raw Binary File) and RPD (Raw Programming
Data) formats. The RPD is generated via a two-step SOF → POF → RPD
conversion, which embeds five Active Serial option bytes that RBF lacks.
These option bytes are required for the AS controller to configure the
FPGA correctly. Using an RBF will fail silently — the FPGA won't start.

### Bit-Reversal

RPD and RBF store bitstream data in Passive Serial (PS) bit order, where
each byte is transmitted LSB-first. The Cyclone IV AS controller reads
SPI flash MSB-first. **Each byte must be bit-reversed** before programming
into the flash.

This is confirmed by comparing the known preamble byte: the RPD file
contains `0x6A` (01101010), which becomes `0x56` (01011010) after
bit-reversal — matching what was observed in a working flash image
programmed by Quartus SFL.

Quartus SFL does this reversal internally. Our custom UART flash
programmer writes raw bytes, so `make_flash_image.py` performs the
reversal.

### Trailing 0xFF Stripping

Quartus generates RPD files padded to the full flash capacity (16 MB
for EPCS128). The actual bitstream is ~4.7 MB, followed by ~11 MB of
0xFF padding. `make_flash_image.py` strips the trailing 0xFF bytes
before combining with the `.jop` application.

### make_flash_image.py Pipeline

```
Read RPD file (16 MB)
  → Strip trailing 0xFF (~4.7 MB remains)
  → Bit-reverse each byte (PS → AS bit order)
  → Pad with 0xFF to JOP offset (0x800000)
  → Append .jop words as big-endian 32-bit values
  → Write combined flash_image.bin (~8.8 MB)
```

## UART Flash Programmer

### Why Not Quartus SFL?

Quartus SFL (Serial Flash Loader) is the standard tool for programming
configuration flash via JTAG. On the QMTECH EP4CGX150 board, SFL
consistently fails — chip erase starts but errors out after ~2.5 minutes.
The root cause is unknown but reproducible.

### Architecture

```
┌──────────┐    UART     ┌──────────┐    SPI     ┌──────────┐
│ Host PC  │◀──1 Mbaud──▶│  FPGA    │◀──10 MHz──▶│ W25Q128  │
│ Python   │    8N1      │  13-state│            │ SPI flash│
│ script   │             │  FSM     │            │          │
└──────────┘             └──────────┘            └──────────┘
flash_program.py      FlashProgrammerTop
```

The FPGA design (`FlashProgrammerTop`) is a minimal 13-state FSM that
interprets UART bytes as SPI control commands. All flash command
sequencing (JEDEC ID, write enable, chip erase, page program, verify)
is done in the Python host script (`flash_program.py`).

### Protocol

| Byte | Action | Echo |
|------|--------|------|
| `0xBB` | Assert CS (drive low) | `0xBB` |
| `0xCC` | Deassert CS (drive high) | `0xCC` |
| `0xDD` `<b>` | SPI transfer byte `b` (escape for `0xBB`/`0xCC`/`0xDD` data values) | MISO byte |
| other | SPI transfer byte | MISO byte |

Every command produces exactly one echo byte, which the host reads to
stay synchronized. The escape mechanism (`0xDD`) allows transferring
data bytes that would otherwise be interpreted as CS control commands.

### Python Host Script

`flash_program.py` performs the full programming flow:
1. Drain stale UART data
2. Read JEDEC ID, verify W25Q128 (`EF4018`)
3. Chip erase (poll SR1 BUSY bit until clear)
4. Page program in 256-byte pages (skip all-0xFF pages)
5. Optional read-back verify

## Build and Program

### Step 1: Build Flash Boot Design

```bash
cd fpga/qmtech-ep4cgx150-sdram
make microcode-flash              # Assemble flash boot microcode
make generate-flash-boot          # Generate Verilog (JopSdramTop with BmbConfigFlash)
make build-flash-boot             # Quartus synthesis
# Or all at once:
make full-flash-boot
```

### Step 2: Create Flash Image

```bash
make flash-image                  # SOF → POF → RPD → bit-reverse → combine with .jop
# Uses JOP_FILE (default: HelloWorld.jop) and JOP_OFFSET (default: 0x800000)
# Override: make flash-image JOP_FILE=path/to/app.jop
```

### Step 3: Load UART Flash Programmer

```bash
make generate-flash-programmer    # Generate FlashProgrammerTop Verilog
make build-flash-programmer       # Quartus synthesis
make program-flash-programmer     # Load into FPGA via JTAG
```

### Step 4: Program Flash

```bash
sudo python3 ../scripts/flash_program.py output_files/flash_image.bin \
    --port /dev/ttyUSB0 -v
# Or: make flash-program
```

### Step 5: Test

Power-cycle the board (or remove and re-insert USB). The board boots
from flash automatically.

```bash
make monitor                      # Watch serial output (1 Mbaud)
```

## Performance

Measured on hardware with an 8 MB flash image (4.7 MB bitstream +
34 KB HelloWorld.jop):

| Phase | Time | Notes |
|-------|------|-------|
| Chip erase | ~28 s | Full 16 MB erase |
| Page program | ~523 s | 256 bytes/page, skips all-0xFF pages |
| Verify | ~464 s | Read-back comparison |
| **Total** | **~9.2 min** | With verify (`-v` flag) |

The bottleneck is UART throughput at 1 Mbaud. Each SPI byte requires a
UART TX + RX round-trip plus protocol overhead.

## Troubleshooting

**Quartus SFL fails on this board.** Chip erase starts but errors after
~2.5 minutes. Use the UART flash programmer instead.

**Flash unresponsive after failed SFL attempt.** A failed SFL operation
can leave the flash in a bad state where it doesn't respond to commands.
Power-cycle the board to reset the flash.

**Serial port re-enumerates after JTAG programming.** When you program
the FPGA via JTAG (`make program-flash-programmer`), the USB serial port
may disappear and re-appear with a different device path. Wait a moment
and check `ls /dev/ttyUSB*` before running the flash programmer.

**MSEL[3:0] = 0000.** The QMTECH board hardwires MSEL for Active Serial
mode. This cannot be changed. The board always attempts AS boot on
power-up.

**Banner "FP" on connection.** The FlashProgrammerTop prints "FP\r\n"
after power-up to confirm it's running. The Python script drains this
before starting.

## Files

| File | Description |
|------|-------------|
| `spinalhdl/src/main/scala/jop/system/JopSdramTop.scala` | Flash boot top (JopCfgFlashTopVerilog) |
| `spinalhdl/src/main/scala/jop/io/BmbConfigFlash.scala` | Config flash SPI I/O peripheral |
| `spinalhdl/src/main/scala/jop/system/FlashProgrammerTop.scala` | UART flash programmer FPGA design |
| `fpga/scripts/make_flash_image.py` | Flash image builder (RPD + .jop) |
| `fpga/scripts/flash_program.py` | Python flash programmer host script |
| `fpga/qmtech-ep4cgx150-sdram/jop_flash_boot.qsf` | Flash boot Quartus project |
| `fpga/qmtech-ep4cgx150-sdram/flash_programmer.qsf` | Flash programmer Quartus project |
| `asm/src/jvm.asm` | Microcode source (flash boot variant via `#ifdef FLASH`) |
