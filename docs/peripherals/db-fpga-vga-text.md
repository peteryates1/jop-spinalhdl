# DB_FPGA Daughter Board — VGA Text Output

VGA text-mode output for the QMTECH DB_FPGA daughter board.
80x30 characters, 8x16 pixel font, 640x480 @ 60Hz, RGB565 (5-6-5).

![VGA text output on DB_FPGA](vga-text-screenshot.jpg)

## Quick Start

```bash
# 1. Build FPGA bitstream (includes VGA text controller)
cd fpga/qmtech-ep4cgx150-sdram
make full-dbfpga

# 2. Program FPGA
make program-dbfpga

# 3. Build the VGA test app
cd ../../java/apps/Small
make clean && make all APP_NAME=VgaTest

# 4. Download and run
cd ../../fpga/qmtech-ep4cgx150-sdram
make download SERIAL_PORT=/dev/ttyUSB0 JOP_FILE=../../java/apps/Small/VgaTest.jop

# 5. Monitor UART output (progress messages)
make monitor SERIAL_PORT=/dev/ttyUSB0
```

Connect a VGA monitor to the DB_FPGA daughter board VGA port. Text output
appears immediately after the test app starts.

## Hardware Requirements

- QMTECH EP4CGX150 core board + DB_FPGA daughter board
- VGA monitor with 640x480 support (any modern monitor)
- VGA cable (directly to DB_FPGA VGA port — no adapter needed)
- USB-Blaster for FPGA programming
- CP2102N USB-UART adapter for serial download/monitor

## Architecture

The VGA text controller (`BmbVgaText`) runs in the **25 MHz pixel clock domain**
(PLL output c3) and generates standard 640x480 @ 60Hz VGA timing.

```
System Clock (80 MHz)              Pixel Clock (25 MHz)
┌────────────────────┐             ┌──────────────────────────┐
│   JOP CPU          │             │  3-stage pixel pipeline   │
│                    │  dual-port  │                          │
│  I/O writes ──────────── RAM ──────► char/attr lookup       │
│  (char, attr,      │    BRAM     │  font ROM lookup         │
│   cursor, control) │             │  bit select + palette    │
│                    │             │          │               │
└────────────────────┘             │   ┌──────┴──────┐        │
                                   │   │ VGA output  │        │
                                   │   │ R[4:0]      │        │
                                   │   │ G[5:0]      │        │
                                   │   │ B[4:0]      │        │
                                   │   │ HSync VSync │        │
                                   │   └─────────────┘        │
                                   └──────────────────────────┘
```

The dual-port BRAMs (character buffer and attribute buffer) allow the CPU to
write at system clock speed while the pixel pipeline reads at 25 MHz.
No frame tearing — writes take effect on the next pixel fetch.

### Clock Domains

| Clock | Source | Frequency | Domain |
|-------|--------|-----------|--------|
| c1 | DRAM PLL output 1 | 80 MHz | System: CPU, memory, I/O |
| c3 | DRAM PLL output 3 | 25 MHz | VGA: pixel pipeline, sync generation |

The VGA pixel clock (c3) is declared as asynchronous to the system clock
in the SDC timing constraints. Clock domain crossing uses dual-port BRAM
with `clockCrossing = true`.

## Register Map

Base address: `Const.IO_VGA` (`IO_BASE + 0x40` = `0xFFFFFF_C0`)

| Offset | Read | Write | Bit Fields |
|:---:|---|---|---|
| +0 | Status | Control | R: bit0=enabled, bit1=vblank, bit2=scrollBusy |
| | | | W: bit0=enable output |
| +1 | Cursor position | Set cursor | col[6:0], row[12:8] |
| +2 | — | Write char+attr | char[7:0], attr[15:8] (auto-advance cursor) |
| +3 | Default attribute | Set default attr | fg[3:0], bg[7:4] |
| +4 | — | Palette write | index[19:16], rgb565[15:0] |
| +5 | Columns (80) | — | |
| +6 | Rows (30) | — | |
| +7 | — | Direct write | char[7:0], attr[15:8], col[22:16], row[28:24] |
| +8 | — | Clear screen | Any write triggers (fills with space + default attr) |
| +9 | — | Scroll up | Any write triggers (shifts rows up, clears bottom) |

### Attribute Byte

```
  bit 7  6  5  4  3  2  1  0
     └──bg color──┘  └──fg color──┘
```

Attribute = `(bg << 4) | fg`

### CGA Color Palette

| Index | Color | Index | Color |
|:---:|---|:---:|---|
| 0 | Black | 8 | Dark gray |
| 1 | Blue | 9 | Light blue |
| 2 | Green | 10 | Light green |
| 3 | Cyan | 11 | Light cyan |
| 4 | Red | 12 | Light red |
| 5 | Magenta | 13 | Light magenta |
| 6 | Brown | 14 | Yellow |
| 7 | Light gray | 15 | White |

The palette is stored as RGB565 values and can be reprogrammed via register +4.

## Java API

All VGA I/O uses `Native.wr()` / `Native.rd()` with `Const.IO_VGA` offsets.

### Enable and Clear

```java
// Enable VGA output
Native.wr(1, Const.IO_VGA + 0);

// Set default attribute (used by clear screen)
Native.wr(0x0F, Const.IO_VGA + 3);  // white on black

// Clear screen
Native.wr(0, Const.IO_VGA + 8);

// Wait for clear to complete
while ((Native.rd(Const.IO_VGA + 0) & 4) != 0) { }
```

### Cursor-Based Writing

```java
// Set cursor to column 0, row 0
Native.wr((0 & 0x7F) | ((0 & 0x1F) << 8), Const.IO_VGA + 1);

// Write 'H' with white-on-black attribute (auto-advances cursor)
int ch = 'H';
int attr = 0x0F;  // fg=white, bg=black
Native.wr((ch & 0xFF) | ((attr & 0xFF) << 8), Const.IO_VGA + 2);
```

### Direct Write (No Cursor Change)

```java
// Write 'X' at column 79, row 29 with yellow-on-blue
int ch = 'X';
int attr = (1 << 4) | 14;  // bg=blue, fg=yellow
int col = 79;
int row = 29;
Native.wr((ch & 0xFF) | ((attr & 0xFF) << 8)
    | ((col & 0x7F) << 16) | ((row & 0x1F) << 24),
    Const.IO_VGA + 7);
```

### Scroll

```java
// Scroll up one row (top row lost, bottom row cleared)
Native.wr(0, Const.IO_VGA + 9);

// Wait for scroll to complete
while ((Native.rd(Const.IO_VGA + 0) & 4) != 0) { }
```

### Helper Functions

See `java/apps/Small/src/test/VgaTest.java` for reusable helpers:

- `setCursor(col, row)` — set cursor position
- `writeChar(ch, attr)` — write character with attribute at cursor
- `writeString(s, attr)` — write string at cursor
- `directWrite(ch, attr, col, row)` — write at position without moving cursor
- `waitVgaReady()` — wait for scroll/clear to complete

## Building a VGA App

### 1. Create Java Source

Create your app in `java/apps/Small/src/test/YourApp.java`:

```java
package test;
import com.jopdesign.sys.*;

public class YourApp {
    public static void main(String[] args) {
        // Enable VGA
        Native.wr(1, Const.IO_VGA + 0);

        // Set default attribute and clear screen
        Native.wr(0x0F, Const.IO_VGA + 3);
        Native.wr(0, Const.IO_VGA + 8);
        // Wait for clear
        for (int i = 0; i < 100000; i++) {
            if ((Native.rd(Const.IO_VGA + 0) & 4) == 0) break;
        }

        // Write text at cursor position
        Native.wr((0 & 0x7F) | ((0 & 0x1F) << 8), Const.IO_VGA + 1);
        String msg = "Hello VGA!";
        int attr = 0x0F;
        for (int i = 0; i < msg.length(); i++) {
            Native.wr((msg.charAt(i) & 0xFF) | ((attr & 0xFF) << 8),
                Const.IO_VGA + 2);
        }

        // Watchdog loop
        int wd = 0;
        for (;;) {
            wd = ~wd;
            Native.wr(wd, Const.IO_WD);
            for (int i = 0; i < 500000; i++) { }
        }
    }
}
```

### 2. Build

```bash
cd java/apps/Small
make clean && make all APP_NAME=YourApp
```

### 3. Download and Run

```bash
cd fpga/qmtech-ep4cgx150-sdram
make download SERIAL_PORT=/dev/ttyUSB0 JOP_FILE=../../java/apps/Small/YourApp.jop
```

The FPGA must already be programmed with the DB_FPGA bitstream
(`make program-dbfpga`). VGA text support is included in the standard
DB_FPGA build — no RTL changes needed.

## VGA Pin Assignments (J3 Header)

VGA signals are on the J3 header lower pins (directly from FPGA to
resistor-DAC on the DB_FPGA daughter board):

| Signal | EP4CGX150 Pin | Description |
|--------|---------------|-------------|
| vga_hs | A6 | Horizontal sync |
| vga_vs | A7 | Vertical sync |
| vga_r[4:0] | D1, B1, E2, C1, E1 | Red (5-bit) |
| vga_g[5:0] | C5, A4, A3, C4, A2, B2 | Green (6-bit) |
| vga_b[4:0] | B6, B7, A5, B5, B4 | Blue (5-bit) |

The DB_FPGA uses a passive resistor-DAC (R-2R network) to convert the
digital RGB565 to analog VGA levels.

## Font

Built-in 8x16 pixel font with 128 printable characters (space through tilde,
0x20–0x7E). The font ROM is embedded in BRAM. Characters outside this range
display as blank.

## SpinalHDL Configuration

VGA text is enabled via `IoConfig.hasVgaText = true`:

```scala
IoConfig.qmtechDbFpga  // sets hasVgaText=true (plus Ethernet, SD)
```

The VGA pixel clock domain (25 MHz) is provided by PLL output c3
in `JopSdramTop`, passed to `JopCore` / `JopCluster` as `vgaCd`.

Key source files:

| File | Description |
|------|-------------|
| `spinalhdl/src/main/scala/jop/io/BmbVgaText.scala` | VGA text controller (register map, pixel pipeline, font ROM) |
| `spinalhdl/src/main/scala/jop/system/IoConfig.scala` | `hasVgaText` config flag |
| `spinalhdl/src/main/scala/jop/system/JopCore.scala` | VGA instantiation and I/O wiring |
| `spinalhdl/src/main/scala/jop/memory/JopMemoryConfig.scala` | `VGA_TEXT_BASE = 0xC0` address decode |
| `java/runtime/src/jop/com/jopdesign/sys/Const.java` | `IO_VGA = IO_BASE + 0x40` |
| `java/apps/Small/src/test/VgaTest.java` | Hardware test app |
| `fpga/qmtech-ep4cgx150-sdram/jop_dbfpga.qsf` | VGA pin assignments |
| `fpga/qmtech-ep4cgx150-sdram/dram_pll.vhd` | PLL with 25 MHz c3 output |

## Troubleshooting

**No image**: Check VGA cable is connected. Verify the FPGA is programmed with
the DB_FPGA bitstream (`make program-dbfpga`). Check that the Java app writes
`1` to `Const.IO_VGA + 0` to enable output.

**Garbled characters**: The pixel pipeline uses a 3-stage look-ahead. If you
modify `BmbVgaText.scala`, ensure `hLookAhead`, `vLookAhead`, and `bitIndex`
all use consistent pipeline delays.

**Image offset/shifted**: Use the monitor's auto-adjust feature after
programming. The VGA timing is standard 640x480 @ 60Hz but monitors
may need to re-sync after FPGA reprogramming.

**scrollBusy never clears**: Clear and scroll operations iterate through all
2400 character cells. At 80 MHz system clock this takes ~30 microseconds.
Ensure you're polling status register bit 2.
