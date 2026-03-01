# Flash Boot — Artix-7 (Alchitry Au V2)

Autonomous flash boot for the Alchitry Au V2 board (XC7A35T-2FTG256 + DDR3).
After the SPI flash is programmed, the board runs Java programs on power-up
with no JTAG connection needed.

This document covers the Artix-7 implementation. See `flash-boot.md` for
the original Cyclone IV (QMTECH EP4CGX150) version.

## Status (Mar 2026)

| Milestone | Status |
|-----------|--------|
| STARTUPE2 BlackBox created | Done |
| JopDdr3Top flash variant (Verilog generation) | Done |
| FlashProgrammerDdr3Top (UART flash programmer) | Done |
| Flash XDC constraints (pin mapping) | Done |
| Vivado TCL scripts (project, bitstream, program) | Done |
| Makefile targets | Done |
| Flash programmed + verified (HelloWorld.jop at 0x240000) | Done |
| Serial boot verified (BBBBM + download echo) | Done |
| Bitstream built with all timing met (WNS=+0.159ns) | Done |
| Flash boot producing HelloWorld output | Done |
| Autonomous boot after power-cycle | Done |

## Architecture

### How It Works

```
Power-on
  |
  v
Artix-7 reads bitstream from SST26 SPI flash (0x000000)
  |
  v
ClkWiz (MMCM) starts: 100 MHz board clock -> clk_100 + clk_200
  |
  v
Board-clock diagnostic FSM runs:
  - RSTQIO (0xFF) to exit factory QPI mode
  - RSTEN (0x66) + RST (0x99) to software-reset flash
  - JEDEC ID read + data read for verification
  - Sets spiDiagDone when complete (~200-300 us)
  |
  v
MIG DDR3 calibration (~200ms)
  |
  v
JOP processor starts in MIG ui_clk domain (100 MHz)
  |
  v
Microcode polls flashReady (status bit 1) — waits for spiDiagDone
  |
  v
SPI mux switches from diagnostic FSM to BmbConfigFlash
  |
  v
Microcode sends RSTEN (0x66) + RST (0x99) to reset flash state
  |
  v
Microcode sends SPI READ_DATA (0x03) to flash at offset 0x240000
  |
  v
.jop application streams in from flash -> written to DDR3
  |
  v
Java program runs
```

### Root Cause of Prior Failures and Fix

The flash boot was producing `BBBBM` diagnostic output (confirming MIG
calibration) but no HelloWorld program output. The root cause was a
**clock-domain mux race condition**:

1. The board-clock diagnostic FSM runs RSTQIO + RSTEN + RST + JEDEC ID
   read + data read on the SPI bus (~200-300 us).
2. MIG ui_clk starts during this time, and the JOP microcode begins
   executing SPI operations via BmbConfigFlash *before* the diagnostic
   FSM completes and the SPI mux switches from the diagnostic path to
   the BmbConfigFlash path.
3. The microcode's SPI commands go nowhere (or collide with the
   diagnostic FSM), resulting in 0xFF reads from flash.

**Fix**: A `flashReady` input was added to `BmbConfigFlash` (exposed as
status register bit 1). This is driven by `spiDiagDone` from the
board-clock domain, synchronized to ui_clk via `BufferCC`. The microcode
polls `flashReady` before starting any flash I/O, ensuring the SPI mux
has switched and the flash is in a known good state.

### Key Difference from Cyclone IV: STARTUPE2

On Xilinx 7 Series, the SPI clock pin (CCLK, pin E8) is **dedicated** — it
cannot be accessed as regular I/O after configuration. The `STARTUPE2`
primitive must be instantiated to drive CCLK from user logic via the
`USRCCLKO` port.

The data pins (DQ0/MOSI, DQ1/MISO) and CS (FCS_B) are regular user I/O
after configuration and are constrained in the XDC.

```scala
// In JopDdr3Top.scala — MIG ui_clk domain
val startup = StartupE2()
startup.io.USRCCLKO  := cluster.io.cfDclk.get    // SPI clock -> CCLK pin
startup.io.USRCCLKTS := False                     // Enable CCLK output
// ... other ports tied to safe defaults
io.cf_cs.get   := cluster.io.cfNcs.get
io.cf_mosi.get := cluster.io.cfAsdo.get
cluster.io.cfData0.get := io.cf_miso.get
```

### Clock Domains

```
Board 100 MHz (N14)
  |
  +---> ClkWiz (MMCM)
  |       |-> clk_100 (100 MHz) -> MIG sys_clk_i
  |       |-> clk_200 (200 MHz) -> MIG clk_ref_i
  |
  +---> Board clock domain (default CD):
          - Bitbang UART ('B' every ~10ms, then 'M' after MIG cal)
          - Heartbeat (1 Hz LED toggle)
          - Hang detector (memBusy > 167ms -> DiagUart dump)
          - LED display (EOS, TX count, RX byte in flash mode)
          - SPI diagnostic FSM (RSTQIO + RSTEN + RST, sets spiDiagDone)

MIG ui_clk (100 MHz):
  - JopCluster (JopCore + BmbConfigFlash + BmbUart)
  - BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MIG -> DDR3
  - STARTUPE2 (USRCCLKO drives CCLK pad)
  - SPI mux: switches from diagnostic FSM to BmbConfigFlash when spiDiagDone
```

### SPI Flash: SST26VF032BT-104I/MF

| Parameter | Value |
|-----------|-------|
| Manufacturer | Microchip (formerly SST) |
| Part | SST26VF032BT-104I/MF |
| Capacity | 32 Mbit / 4 MB |
| JEDEC ID | `bf2642` |
| Interface | SPI / Dual SPI / Quad SPI |
| Max SPI clock | 104 MHz (40 MHz for READ_DATA 0x03) |

**Important**: The SST26VF032B has factory-default block protection that
must be unlocked before erase/write. The `ULBPR` (0x98) command performs
a Global Block Protection Unlock. This is handled by `flash_program.py --sst26`.

**Important**: Vivado does NOT support SST26VF032B in its `cfgmem_parts`
list, so indirect SPI programming via Vivado won't work. We use the UART
flash programmer instead.

## Flash Image Layout

```
SST26VF032B (4 MB)
+---------------------------+ 0x000000
|                           |
|  FPGA bitstream           |  ~2.09 MB
|  (Xilinx .bin format)     |
|                           |
+---------------------------+ ~0x21728C
|  Padding (0xFF)           |  ~160 KB
+---------------------------+ 0x240000
|                           |
|  JOP application (.jop)   |  variable
|  (big-endian 32-bit)      |
|                           |
+---------------------------+
|  Free (0xFF)              |  remainder
+---------------------------+ 0x3FFFFF
```

The `.jop` offset (0x240000 = 2.25 MB) is chosen to fit the XC7A35T
bitstream (~2.09 MB) with padding, leaving ~1.75 MB for the application.

### Why No Bit-Reversal (Unlike Cyclone IV)

On Cyclone IV, the RPD/RBF stores bitstream data in Passive Serial bit
order (LSB-first per byte), but the AS controller reads SPI flash MSB-first.
Each byte must be bit-reversed before programming.

On Xilinx 7 Series, the `.bin` file produced by Vivado's `write_cfgmem`
command is already in the correct bit order for SPI flash. No bit-reversal
is needed. The `make_flash_image.py` script accepts a `--no-bitrev` flag
for this.

## Pin Mapping

### Au V2 Flash Pins (Bank 14, LVCMOS33)

| Signal | Pin | Function | Notes |
|--------|-----|----------|-------|
| CCLK | E8 | SPI clock | Dedicated pin, via STARTUPE2 |
| cf_mosi / DQ0 | J13 | MOSI (FPGA -> flash) | Regular I/O after config |
| cf_miso / DQ1 | J14 | MISO (flash -> FPGA) | Regular I/O after config |
| cf_cs / FCS_B | L12 | Chip select | Regular I/O after config |
| DQ2 | K15 | Unused | SPI x1 mode only |
| DQ3 | K16 | Unused | SPI x1 mode only |

### Au V2 Board Pins

| Signal | Pin | Function |
|--------|-----|----------|
| clk | N14 | 100 MHz crystal |
| resetn | P6 | Reset button (active-low) |
| usb_rx | P15 | UART RX (FT2232 Channel B) |
| usb_tx | P16 | UART TX (FT2232 Channel B) |
| led[0:7] | Various | 8 LEDs |

### Serial Port

The Au V2 has an FT2232 dual-channel USB-to-serial chip:
- **Channel A** (`/dev/ttyUSB0`): Used by Vivado for JTAG. Detached when
  Vivado opens it; may not reappear as ttyUSB0 afterward.
- **Channel B** (`/dev/ttyUSB1` or `/dev/ttyUSB3`): UART at 1 Mbaud.
  This is the serial port for JOP communication. The device number varies
  depending on USB enumeration order after replug — check
  `ls /dev/ttyUSB*` if the expected port isn't present.

## Flash Boot Microcode

The flash boot microcode is the same as Cyclone IV, with different
preprocessor defines:

| Parameter | Cyclone IV | Artix-7 |
|-----------|-----------|---------|
| `FLASH_ADDR_B2` | 128 (0x80 -> 0x800000) | 36 (0x24 -> 0x240000) |
| `FLASH_CLK_DIV` | 3 (10 MHz at 80 MHz sys) | 15 (3.125 MHz at 100 MHz sys) |
| Build target | `make flash` | `make flash-au` |

### Microcode Sequence

1. **Set clock divider** — `FLASH_CLK_DIV` -> io_cf_div (0xD2)
2. **Poll flashReady** — Read io_cf_status (0xD0), wait for bit 1 = 1
3. **RSTEN (0x66)** — Assert CS, send byte, poll busy, deassert CS
4. **RST (0x99)** — Assert CS, send byte, poll busy, deassert CS
5. **Wait** — Count down from 255 (~640 us at 100 MHz)
6. **Assert CS** — Write 1 to io_cf_status (0xD0)
7. **READ_DATA (0x03)** — Send command byte, poll busy
8. **Address bytes** — Send FLASH_ADDR_B2, 0x00, 0x00 (3-byte address)
9. **Read loop** — Send dummy 0xFF bytes, read MISO data, assemble 32-bit
   words (big-endian, 4 bytes per word), write to DDR3 via memory controller

### I/O Register Map (BmbConfigFlash at 0xD0-0xD3)

| Address | Microcode name | Read | Write |
|---------|---------------|------|-------|
| 0xD0 | io_cf_status | bit 0 = SPI busy, bit 1 = flashReady | bit 0 = CS assert |
| 0xD1 | io_cf_data | received byte | transmit byte |
| 0xD2 | io_cf_div | — | clock divider |
| 0xD3 | — | — | — |

## UART Flash Programmer

Since Vivado doesn't support the SST26VF032B flash chip, we use the same
UART flash programmer approach as Cyclone IV, adapted for Artix-7.

### FlashProgrammerDdr3Top

A minimal 13-state FSM that runs directly on the 100 MHz board clock (no
MIG or DDR3 needed). Uses STARTUPE2 for CCLK, same protocol as
`FlashProgrammerTop`.

```
+-----------+    UART     +-----------+    SPI     +-------------+
| Host PC   |<--1 Mbaud-->|  FPGA     |<--3.1MHz->| SST26VF032B |
| Python    |    8N1      |  13-state |           | SPI flash   |
| script    |             |  FSM      |           |             |
+-----------+             +-----------+           +-------------+
flash_program.py    FlashProgrammerDdr3Top
```

### SST26-Specific Handling (--sst26 flag)

- **RSTQIO (0xFF)** + **RSTEN (0x66)** + **RST (0x99)**: Reset flash from
  possible QPI mode back to standard SPI.
- **ULBPR (0x98)**: Global Block Protection Unlock before erase/write.
  Must be preceded by WREN.
- **JEDEC ID check**: Expects `bf2642` (not `ef4018` for W25Q128).
- **Flash size**: 4 MB (not 16 MB).

### Programming Performance

Measured on Au V2 with ~2.28 MB flash image:

| Phase | Time | Notes |
|-------|------|-------|
| Flash reset | ~0.1 s | RSTQIO + soft reset |
| ULBPR | <1 s | Block protection unlock |
| Chip erase | ~0.2 s | Full 4 MB erase |
| Page program | ~1948 s (~32.5 min) | 256 bytes/page, skip 0xFF pages |
| Verify | ~1047 s (~17.5 min) | Read-back comparison |
| **Total** | **~50 min** | With verify (`-v` flag) |

The bottleneck is UART throughput at 1 Mbaud. Each SPI byte requires a
UART TX + RX round-trip plus protocol overhead.

## Bitbang UART Diagnostic

The flash-boot bitstream includes a bitbang UART in the board clock domain
that sends diagnostic characters before JOP takes over:

```
'B' (0x42) — sent every ~10ms while waiting for MIG calibration
'M' (0x4D) — sent once after MIG calibration completes
... then JOP UART takes over (serial boot download or flash boot program output)
```

This was added to diagnose what was initially thought to be "zero UART
output" from the Artix-7 — which turned out to be a monitoring timing issue,
not a design bug.

### Monitoring Timing Gotcha

The bitbang UART sends `BBBBM` within ~50ms of FPGA start (4 'B' at ~10ms
intervals + 'M' after MIG calibrates). If you program via JTAG and then
open the serial port, the data is already gone.

**Solution**: Open the serial port BEFORE starting Vivado JTAG programming,
use a background reader thread to capture all output:

```python
import serial, time, subprocess, threading

ser = serial.Serial('/dev/ttyUSB1', 1000000, timeout=0.1)
ser.reset_input_buffer()

received = []
stop = threading.Event()
t0 = time.time()

def reader():
    while not stop.is_set():
        data = ser.read(200)
        if data:
            received.append((time.time() - t0, data))

t = threading.Thread(target=reader, daemon=True)
t.start()

# Program FPGA (Vivado JTAG)
subprocess.run(["vivado", "-mode", "batch", "-source", "program.tcl"], ...)

time.sleep(5)  # Wait for output
stop.set()
t.join()

# Print captured data
for ts, data in received:
    print(f"t={ts:.3f}s: {data!r}")
```

### LED Debug Display (Flash Boot Mode)

When `ioConfig.hasConfigFlash = true` (single-core):

| LED | Signal | Meaning |
|-----|--------|---------|
| 7 | EOS | STARTUPE2 End Of Startup (should be 1) |
| 6:5 | RX byte [7:6] | Last SPI MISO byte bits (0xFF = stuck high) |
| 4 | Heartbeat | 1 Hz toggle (board alive) |
| 3:0 | TX count [3:0] | Number of SPI bytes sent by microcode |

## Build and Program

### Step 1: Build Flash Boot Bitstream

```bash
cd fpga/alchitry-au

# Build flash microcode (FLASH_ADDR_B2=36, FLASH_CLK_DIV=15)
make microcode-flash

# Generate Verilog with STARTUPE2 + BmbConfigFlash
make generate-flash

# Create Vivado project (includes flash.xdc)
make project-flash

# Build bitstream + generate .bin via write_cfgmem
make bitstream-flash
```

### Step 2: Create Flash Image

```bash
# Combine bitstream .bin + HelloWorld.jop -> flash_image.bin
make flash-image
# Uses --no-bitrev (Xilinx), --jop-offset 0x240000
```

### Step 3: Load UART Flash Programmer

```bash
# Generate + build + program flash programmer
make generate-flash-programmer
make project-flash-programmer
make bitstream-flash-programmer
make program-flash-programmer
```

### Step 4: Program Flash

```bash
# Program via UART (--sst26 for SST26VF032B, -v for verify)
make program-flash
# Or manually:
sudo python3 ../scripts/flash_program.py --sst26 \
    vivado/build/flash_image.bin --port /dev/ttyUSB1 -v
```

### Step 5: Test

Power-cycle the board for autonomous boot. No JTAG needed.

```bash
# Monitor serial output (1 Mbaud)
sudo timeout 10 python3 ../scripts/monitor.py /dev/ttyUSB1 1000000
```

### Full Flash Boot (All Steps)

```bash
make full-flash-boot
# = microcode-flash + generate-flash + project-flash + bitstream-flash
#   + flash-image + program-flash
```

## Files

| File | Description |
|------|-------------|
| `spinalhdl/src/main/scala/jop/ddr3/StartupE2.scala` | STARTUPE2 BlackBox for Xilinx 7 Series |
| `spinalhdl/src/main/scala/jop/system/JopDdr3Top.scala` | DDR3 top-level (serial + flash + SMP variants) |
| `spinalhdl/src/main/scala/jop/system/FlashProgrammerDdr3Top.scala` | UART flash programmer for Artix-7 |
| `spinalhdl/src/main/scala/jop/io/BmbConfigFlash.scala` | Config flash SPI I/O peripheral (generic) |
| `fpga/alchitry-au/vivado/constraints/flash.xdc` | Flash pin constraints (J13, J14, L12) |
| `fpga/alchitry-au/vivado/tcl/create_project_flash.tcl` | Vivado project with flash constraints |
| `fpga/alchitry-au/vivado/tcl/build_bitstream_flash.tcl` | Build bitstream + write_cfgmem .bin |
| `fpga/alchitry-au/vivado/tcl/create_project_flash_programmer.tcl` | Flash programmer Vivado project |
| `fpga/alchitry-au/vivado/tcl/build_bitstream_flash_programmer.tcl` | Flash programmer bitstream build |
| `fpga/alchitry-au/vivado/tcl/program_flash.tcl` | JTAG program flash-boot bitstream |
| `fpga/alchitry-au/vivado/tcl/program_flash_programmer.tcl` | JTAG program flash programmer |
| `fpga/scripts/make_flash_image.py` | Flash image builder (bitstream + .jop) |
| `fpga/scripts/flash_program.py` | Python UART flash programmer (W25Q128 + SST26) |
| `fpga/alchitry-au/Makefile` | Build targets for flash boot flow |
| `asm/src/jvm.asm` | Microcode source (`#ifdef FLASH`) |
| `asm/Makefile` | `flash-au` target for Artix-7 microcode |

## Comparison: Cyclone IV vs Artix-7

| Feature | Cyclone IV (QMTECH) | Artix-7 (Au V2) |
|---------|---------------------|-----------------|
| FPGA | EP4CGX150DF27 | XC7A35T-2FTG256 |
| Flash chip | W25Q128 (16 MB) | SST26VF032BT (4 MB) |
| Flash JEDEC | ef4018 | bf2642 |
| SPI clock access | Direct I/O (DCLK pin) | STARTUPE2 (CCLK dedicated) |
| Bit-reversal | Required (PS -> AS) | Not needed (.bin correct) |
| .jop offset | 0x800000 (8 MB) | 0x240000 (2.25 MB) |
| Bitstream size | ~4.7 MB | ~2.09 MB |
| System clock | 80 MHz | 100 MHz (MIG ui_clk) |
| SPI clock | 10 MHz (div=3) | 3.125 MHz (div=15) |
| Block protection | None (W25Q128) | ULBPR required (SST26) |
| Flash programmer | JTAG fails (Quartus SFL) | JTAG fails (Vivado no SST26) |
| Config pins | QSF RESERVE_*_AFTER_CONFIGURATION | XDC + CFGBVS/CONFIG_VOLTAGE |
| Autonomous boot | Working | Working |

## Discoveries and Gotchas

### 1. Clock-Domain Mux Race + flashReady Fix

The board-clock diagnostic FSM and the MIG ui_clk JOP microcode both
need SPI flash access. A hardware mux selects between them based on
`spiDiagDone`. The race occurs because MIG ui_clk can start and run
microcode before the diagnostic FSM finishes, causing SPI commands from
BmbConfigFlash to be muxed to nowhere.

Fix: `flashReady` input on BmbConfigFlash (status bit 1), driven by
`BufferCC`-synchronized `spiDiagDone`. Microcode polls bit 1 before
starting flash I/O.

### 2. Factory QPI Mode — RSTQIO Required

The SST26VF032B ships (or is left by factory firmware) in QPI mode,
where all 4 data pins are bidirectional and standard SPI commands don't
work. The `RSTQIO` command (0xFF) must be sent before any SPI operations.
The board-clock diagnostic FSM sends this automatically on startup.
`flash_program.py --sst26` also sends it via `reset_flash()`.

### 3. JTAG Reprogramming Corrupts Flash State

After loading a bitstream via JTAG, the SPI flash returns 0xFF for all
reads. The Xilinx configuration process uses the flash pins during
bitstream load and can leave the flash in an undefined state. The
board-clock diagnostic FSM sends RSTQIO + RSTEN + RST to recover.

### 4. SST26 Block Protection (ULBPR)

The SST26VF032B ships with factory-set block protection on all sectors.
Erase/write operations silently fail unless `ULBPR` (0x98) is sent after
`WREN` (0x06). The Python flash programmer handles this with `--sst26`.

### 5. Vivado Doesn't Support SST26VF032B

Vivado's `create_hw_cfgmem` / `program_hw_cfgmem` commands don't list the
SST26VF032B in their supported flash parts. Indirect SPI programming via
JTAG won't work. Hence the UART flash programmer approach (same as
Cyclone IV where Quartus SFL fails).

### 6. SPI Mode 0 Timing (STARTUPE2 ~65ns CCLK Delay)

STARTUPE2 adds ~65ns of delay on the CCLK output path. With a naive
single shift register for both TX and RX, MISO sampling can miss the
correct data window. Fix: split `txShiftReg` and `rxShiftReg` in
BmbConfigFlash — MOSI shifts on the falling SCLK edge, MISO samples on
the rising edge. This is standard SPI Mode 0 and accommodates the
STARTUPE2 delay.

### 7. Serial Port Must Be Opened Before JTAG Programming

The bitbang UART sends diagnostic characters within milliseconds of FPGA
start. By the time Vivado finishes programming and you open the serial
port, the data is gone. Use the background reader thread pattern described
above.

### 8. FT2232 Channel Assignment and Replug Numbering

Channel A is JTAG (detached by Vivado), Channel B is UART. After JTAG
programming, ttyUSB0 may disappear and the UART port renumbers. The
serial port for JOP is typically `/dev/ttyUSB1` but can be `/dev/ttyUSB3`
after a USB replug. Always check `ls /dev/ttyUSB*`.

### 9. ClkWiz Reset Polarity

The ClkWiz BlackBox port is named `resetn` but is actually **active-HIGH**
internally (`assign reset_high = resetn;` feeds MMCM RST). JopDdr3Top
correctly inverts the board reset: `clkWiz.io.resetn := !readResetWire`.

### 10. No create_clock on Board Clock

The XDC file has no `create_clock` constraint on the `clk` port (N14).
ClkWiz creates internal clock constraints for the PLL output, but
registers in the board clock domain (bitbang UART, heartbeat, hang
detector) are technically unconstrained. This hasn't caused issues since
these are simple, low-frequency circuits.

### 11. I/O Addresses Are Static

BmbConfigFlash is always at 0xD0-0xD3 regardless of which optional
peripherals are present (Ethernet, SD card, VGA, etc.). The I/O address
space is fixed at design time in `JopIoSpace`, not dynamically assigned
based on `IoConfig` flags.
