# Configuration Flash (W25Q128) Access

Runtime access to the W25Q128 SPI configuration flash on the QMTECH
EP4CGX150 core board. The flash is connected to the FPGA's dedicated
active serial configuration pins (DCLK, DATA0, ASDO, nCSO). After
JTAG configuration, these pins can be released as regular I/O for
SPI bit-bang access to the flash.

## Hardware

The QMTECH EP4CGX150 core board has a Winbond W25Q128 (128 Mbit / 16 MB)
SPI flash soldered on the underside, connected to the Cyclone IV GX
dedicated configuration pins:

| Signal | FPGA Pin | Flash Pin | Direction |
|--------|----------|-----------|-----------|
| DCLK   | F6       | CLK       | FPGA -> Flash |
| DATA0  | D6       | DO (MISO) | Flash -> FPGA |
| ASDO   | E6       | DI (MOSI) | FPGA -> Flash |
| nCSO   | D5       | /CS       | FPGA -> Flash |

The flash stores the FPGA bitstream for power-on configuration via
Active Serial mode. Programming the FPGA via JTAG (.sof) loads SRAM
only and does not modify the flash contents.

## Quartus Configuration Pin Settings

The dedicated configuration pins are reserved by Quartus by default.
To use them as regular I/O after JTAG configuration, all four must
be explicitly released in the QSF:

```tcl
set_global_assignment -name ENABLE_CONFIGURATION_PINS ON
set_global_assignment -name RESERVE_DATA0_AFTER_CONFIGURATION "USE AS REGULAR IO"
set_global_assignment -name RESERVE_DCLK_AFTER_CONFIGURATION "USE AS REGULAR IO"
set_global_assignment -name RESERVE_DATA1_AFTER_CONFIGURATION "USE AS REGULAR IO"
set_global_assignment -name RESERVE_FLASH_NCE_AFTER_CONFIGURATION "USE AS REGULAR IO"
```

Notes on Cyclone IV GX naming:
- **ASDO** is called **DATA1** (`RESERVE_DATA1_AFTER_CONFIGURATION`)
- **nCSO** is called **FLASH_NCE** (`RESERVE_FLASH_NCE_AFTER_CONFIGURATION`)
- `RESERVE_ASDO_AFTER_CONFIGURATION` is **obsolete** for Cyclone IV GX

### Why Not the ASMI Block?

The `cycloneiv_asmiblock` hard atom would be the natural approach, but
Quartus 25.1 reports "WYSIWYG ASMI primitives converted to equivalent
logic" and the resulting design reads all 0xFF. The converted logic does
not properly connect to the physical pins. Using direct I/O with the
RESERVE settings above works correctly.

## Config Flash Exerciser

`ConfigFlashExerciserTop` is a standalone FPGA design that reads the
flash and streams results via UART at 1 Mbaud.

### SPI Implementation

SPI Mode 0 (CPOL=0, CPHA=0) bit-bang at 10 MHz (80 MHz / 8):
- Clock idle low, data sampled on rising edge
- MSB first, active-low chip select

### Tests

**T1: JEDEC_ID** -- sends READ_JEDEC_ID (0x9F), verifies response is
`EF4018` (Winbond W25Q128).

**T2: READ** -- sends READ_DATA (0x03) at address 0, reads 256 bytes,
prints as hex dump with 16 bytes per line.

### Output

```
CONFIG FLASH TEST
T1:JEDEC_ID ef4018 PASS
T2:READ
00000000: ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff
00000010: ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff
00000020: 56 ff ef ef ef ef ef cf df 5f 97 5f 4f 0f 9f 1f
00000030: bf 9f 9f 9f bf 3f 7f 3f 3f 3f 9f 9f 3f 9f 9f bf
00000040: 3f 1f 1f 3f 7f 5f 7f 5f 5f 0a 29 ff ff ff ff ff
00000050: ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff
...
000000f0: ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff
LOOP 00000001
```

The non-FF bytes at offsets 0x20--0x4A are the Cyclone IV GX bitstream
header from an old design previously programmed into the flash.

### Build and Run

```bash
cd /home/peter/jop-spinalhdl
sbt "runMain jop.system.ConfigFlashExerciserTopVerilog"

cd fpga/qmtech-ep4cgx150-sdram
make build-config-flash-exerciser
make program-config-flash-exerciser
sudo timeout 30 python3 ../scripts/monitor.py /dev/ttyUSB0 1000000
```

Or in one step: `make full-config-flash-exerciser`

### Files

| File | Description |
|------|-------------|
| `spinalhdl/src/main/scala/jop/system/ConfigFlashExerciserTop.scala` | SpinalHDL source |
| `fpga/qmtech-ep4cgx150-sdram/config_flash_exerciser.qsf` | Quartus project |
| `spinalhdl/generated/ConfigFlashExerciserTop.v` | Generated Verilog |
