# Sample Applications

All sample applications run on the QMTECH EP4CGX150 + DB_FPGA platform. They
are built with the Small runtime and downloaded via serial UART.

## Application Index

| App | Source | Extra Modules | Port/Interface | Description |
|-----|--------|---------------|----------------|-------------|
| HelloWorld | `src/test/HelloWorld.java` | — | UART | Prints "Hello World" to serial |
| NCoreHelloWorld | `src/test/NCoreHelloWorld.java` | — | UART | Multi-core hello (SMP test) |
| VgaTest | `src/test/VgaTest.java` | — | VGA | Text mode 80x30 display test |
| VgaDmaTest | `src/test/VgaDmaTest.java` | — | VGA | DMA framebuffer RGB565 test |
| SdTest | `src/test/SdTest.java` | — | SD card | SD native interface read/write test |
| Fat32Test | `fat32/src/test/Fat32Test.java` | `fat32/src` | SD card | FAT32 filesystem: create, read, write, LFN (30 checks) |
| EthTest | `src/test/EthTest.java` | — | Ethernet | Raw Ethernet frame test |
| NetTest | `src/test/NetTest.java` | `net/src` | Ethernet | UDP+TCP echo on port 7, ICMP ping |
| DhcpTest | `src/test/DhcpTest.java` | `net/src` | Ethernet | DHCP+DNS, then UDP+TCP echo |
| HttpServer | `src/test/HttpServer.java` | `net/src fat32/src` | Ethernet+SD | HTTP/1.0 file server on port 80 |

All source paths are relative to `java/apps/Small/`.

## Build

All apps use the same build system:

```bash
cd java/apps/Small
make clean && make all APP_NAME=<name> [EXTRA_SRC="<paths>"]
```

### Examples

```bash
# Simple apps (no extra modules)
make clean && make all APP_NAME=HelloWorld
make clean && make all APP_NAME=VgaTest
make clean && make all APP_NAME=SdTest

# Fat32 (needs fat32 module)
make clean && make all APP_NAME=Fat32Test EXTRA_SRC=../../fat32/src

# Networking (needs net module)
make clean && make all APP_NAME=NetTest EXTRA_SRC=../../net/src
make clean && make all APP_NAME=DhcpTest EXTRA_SRC=../../net/src

# HTTP server (needs both net and fat32)
make clean && make all APP_NAME=HttpServer "EXTRA_SRC=../../net/src ../../fat32/src"
```

## Download and Run

```bash
cd fpga/qmtech-ep4cgx150-sdram

# Program FPGA (if not already configured)
make program-dbfpga

# Download app via serial
make download SERIAL_PORT=/dev/ttyUSB0 JOP_FILE=../../java/apps/Small/<name>.jop
```

The download script monitors UART output after transfer. Press Ctrl+C to exit.

## FPGA Configurations

Some apps require specific FPGA builds due to mutually exclusive peripherals:

| Config | Build Target | Peripherals |
|--------|-------------|-------------|
| `IoConfig.qmtechDbFpga` | `make build-dbfpga` | VGA text, SD, Ethernet |
| `IoConfig.qmtechDbFpgaVgaDma` | `make full-dbfpga-vgadma` | VGA DMA, SD, Ethernet |

VGA text and VGA DMA share the same pins and cannot be used simultaneously.

## App Details

### HelloWorld

Minimal test — prints "Hello World" to UART. Useful for verifying the basic
build/download/run cycle.

### Fat32Test

Comprehensive FAT32 filesystem test (30 checks). Requires a MBR-partitioned
FAT32 SD card. Tests: mount, directory listing, file create, read, write,
long file names (LFN), short 8.3 names, file size tracking.

```bash
make clean && make all APP_NAME=Fat32Test EXTRA_SRC=../../fat32/src
```

### NetTest

UDP and TCP echo server on port 7 with ICMP ping support. Prints periodic
diagnostics (RX/TX counts, MAC errors/drops). Uses static IP 192.168.0.123.

```bash
make clean && make all APP_NAME=NetTest EXTRA_SRC=../../net/src

# Test from host
ping 192.168.0.123
echo "hello" | nc -u -w1 192.168.0.123 7    # UDP echo
echo "hello" | nc -w1 192.168.0.123 7       # TCP echo
```

### DhcpTest

DHCP client test — obtains IP from network DHCP server, resolves a hostname
via DNS, then runs UDP+TCP echo on port 7.

```bash
make clean && make all APP_NAME=DhcpTest EXTRA_SRC=../../net/src
```

### HttpServer

HTTP/1.0 file server serving static files from FAT32 SD card on TCP port 80.
Auto-creates a default `index.htm` on the SD card if missing.

Features: GET requests, subdirectory traversal, content-type detection,
backpressure-aware streaming, pre-built error responses (400/404/500).

```bash
make clean && make all APP_NAME=HttpServer "EXTRA_SRC=../../net/src ../../fat32/src"

# Test from host
curl http://192.168.0.123/
curl http://192.168.0.123/index.htm
```

### VgaTest

VGA text mode test — 80x30 character display at 640x480@60Hz. Tests cursor
positioning, character writing, scrolling, attributes. Requires DB_FPGA build.

### VgaDmaTest

VGA DMA framebuffer test — 640x480@60Hz RGB565 from SDRAM. Draws test patterns
using `Native.wrMem()` direct memory writes. Requires the VGA DMA FPGA build
(`make full-dbfpga-vgadma`).

## Detailed Documentation

| Topic | Document |
|-------|----------|
| Networking stack | [peripherals/networking.md](peripherals/networking.md) |
| FAT32 filesystem | [peripherals/fat32-filesystem.md](peripherals/fat32-filesystem.md) |
| SD card hardware | [peripherals/db-fpga-sd-card.md](peripherals/db-fpga-sd-card.md) |
| Ethernet hardware | [peripherals/db-fpga-ethernet.md](peripherals/db-fpga-ethernet.md) |
| VGA text mode | [peripherals/db-fpga-vga-text.md](peripherals/db-fpga-vga-text.md) |
| VGA DMA mode | [peripherals/db-fpga-vga-dma.md](peripherals/db-fpga-vga-dma.md) |
