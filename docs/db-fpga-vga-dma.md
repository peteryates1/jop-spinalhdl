# DB_FPGA Daughter Board -- VGA DMA Framebuffer

VGA framebuffer output for the QMTECH DB_FPGA daughter board.
640x480 @ 60Hz, RGB565 pixel format, DMA from SDRAM.

## Quick Start

```bash
# 1. Build FPGA bitstream (VGA DMA variant -- mutually exclusive with VGA Text)
cd fpga/qmtech-ep4cgx150-sdram
make full-dbfpga-vgadma

# 2. Program FPGA
make program-dbfpga-vgadma

# 3. Build the VGA DMA test app
cd ../../java/apps/Small
make clean && make all APP_NAME=VgaDmaTest

# 4. Download and run
cd ../../fpga/qmtech-ep4cgx150-sdram
make download SERIAL_PORT=/dev/ttyUSB0 JOP_FILE=../../java/apps/Small/VgaDmaTest.jop

# 5. Monitor UART output (progress messages)
make monitor SERIAL_PORT=/dev/ttyUSB0
```

Connect a VGA monitor to the DB_FPGA daughter board VGA port. Test patterns
appear after the app starts: border, color bars, gradient, solid colors.

## Hardware Requirements

Same as VGA Text:

- QMTECH EP4CGX150 core board + DB_FPGA daughter board
- VGA monitor with 640x480 support
- VGA cable (directly to DB_FPGA VGA port)
- USB-Blaster for FPGA programming
- CP2102N USB-UART adapter for serial download/monitor

## Architecture

The VGA DMA controller (`BmbVgaDma`) reads an RGB565 framebuffer from SDRAM
via BMB DMA and outputs it through a CDC FIFO to the 25 MHz pixel clock domain.

```
System Clock (80 MHz)              Pixel Clock (25 MHz)
┌─────────────────────┐            ┌──────────────────────┐
│   JOP CPU           │            │  Pixel unpacker       │
│                     │            │                       │
│  I/O writes ───────────► regs   │  FIFO pop ──► halfSel │
│  (base, size, ctrl) │            │  32-bit word → 2 pix  │
│                     │  StreamFifoCC                      │
│   BMB master ──────────► FIFO ──────► RGB565 output      │
│   DMA engine        │  (512 deep)│          │            │
│   reads SDRAM       │            │   ┌──────┴──────┐     │
│   burstBytes/cmd    │            │   │ VGA output  │     │
│                     │            │   │ R[4:0]      │     │
│                     │            │   │ G[5:0]      │     │
│                     │            │   │ B[4:0]      │     │
│                     │            │   │ HSync VSync │     │
└─────────────────────┘            │   └─────────────┘     │
                                   └──────────────────────┘
```

### Clock Domains

| Clock | Source | Frequency | Domain |
|-------|--------|-----------|--------|
| c1 | DRAM PLL output 1 | 80 MHz | System: CPU, memory, DMA, I/O |
| c3 | DRAM PLL output 3 | 25 MHz | VGA: pixel output, sync generation |

Cross-domain communication uses `StreamFifoCC` (push at 80 MHz, pop at 25 MHz)
and `BufferCC` for control signals.

### DMA Timing

Each frame cycle:

1. **Active display** (lines 0-479): Pixel pipeline pops words from FIFO, unpacking 2 RGB565 pixels per word.
2. **Early blanking** (lines 480-491): FIFO is drained of any remaining data.
3. **Vsync end** (line 492): DMA engine restarts, begins filling FIFO.
4. **Back porch** (lines 492-524): DMA has 33 lines (~26,400 pixel clocks) to pre-fill the FIFO before the next active display begins.

The DMA reads exactly one frame per refresh cycle, then stops until the next
vsync restart.

## Register Map

Base address: `Const.IO_VGA_DMA` (`IO_BASE + 0x2C` = `0xFFFFFF_AC`)

| Offset | Read | Write |
|:---:|---|---|
| +0 | Status: bit0=enabled, bit1=vsyncPending, bit2=underrun | Control: bit0=enable, bit1=clearVsync |
| +1 | Framebuffer base address (byte address) | Set base address (word-aligned) |
| +2 | Framebuffer size in bytes | Set size (default 614400) |

### Pixel Format

RGB565, 16 bits per pixel. Two pixels per 32-bit word:

```
Bit:  31          16 15          0
      ┌─────────────┬─────────────┐
      │   pixel 1    │   pixel 0    │
      │ R G  B       │ R G  B       │
      │ 5 6  5       │ 5 6  5       │
      └─────────────┴─────────────┘
        second pixel   first pixel
```

Each pixel: `R[15:11] G[10:5] B[4:0]`.

## Framebuffer Allocation

The framebuffer must reside in SDRAM at a known byte address. The recommended
approach is to allocate at the top of SDRAM, below the stack spill regions:

```java
// Get usable memory end (word address)
int memEnd = Native.rd(Const.IO_MEM_SIZE);
if (memEnd == 0) memEnd = 8 * 1024 * 1024;  // 8M words default

// Place framebuffer at top of usable memory
int fbWords = 640 * 480 / 2;           // 153,600 words
int fbWordAddr = memEnd - fbWords;
int fbByteAddr = fbWordAddr << 2;       // byte address for DMA

// Configure DMA
Native.wr(fbByteAddr, Const.IO_VGA_DMA + 1);  // base address
Native.wr(640 * 480 * 2, Const.IO_VGA_DMA + 2);  // size in bytes
```

### Writing Pixels

Use `Native.wrMem()` for direct SDRAM word writes:

```java
// Pack two RGB565 pixels into one word
int word = (pixel0 & 0xFFFF) | ((pixel1 & 0xFFFF) << 16);
Native.wrMem(word, fbWordAddr + y * 320 + x);

// Fill solid red
int red = 0xF800;
int word = (red & 0xFFFF) | ((red & 0xFFFF) << 16);
for (int i = 0; i < fbWords; i++) {
    Native.wrMem(word, fbWordAddr + i);
}
```

JOP's array cache is write-through, so SDRAM always has the latest data.
The DMA reads directly from SDRAM, not from cache.

## SpinalHDL Configuration

VGA DMA uses `IoConfig.hasVgaDma = true`. This is **mutually exclusive** with
`hasVgaText` (same VGA output pins):

```scala
IoConfig.qmtechDbFpgaVgaDma  // hasVgaDma=true, hasEth=true, hasSdNative=true
```

The VGA DMA variant uses the same Quartus project and pin assignments as
VGA Text (same physical VGA pins). Only the FPGA bitstream differs.

Key source files:

| File | Description |
|------|-------------|
| `spinalhdl/src/main/scala/jop/io/BmbVgaDma.scala` | VGA DMA controller (DMA engine, CDC FIFO, pixel output) |
| `spinalhdl/src/main/scala/jop/system/IoConfig.scala` | `hasVgaDma` config flag, `qmtechDbFpgaVgaDma` preset |
| `spinalhdl/src/main/scala/jop/system/JopSdramTop.scala` | `JopDbFpgaVgaDmaTopVerilog` generation object |
| `spinalhdl/src/main/scala/jop/memory/JopMemoryConfig.scala` | `VGA_DMA_BASE = 0xAC` address decode |
| `java/runtime/src/jop/com/jopdesign/sys/Const.java` | `IO_VGA_DMA = IO_BASE + 0x2C` |
| `java/runtime/src/jop/com/jopdesign/hw/VgaDma.java` | Java hardware object |
| `java/apps/Small/src/test/VgaDmaTest.java` | Hardware test app |

## Troubleshooting

**No image**: Verify the FPGA is programmed with the VGA DMA bitstream
(`make program-dbfpga-vgadma`), not the standard DB_FPGA bitstream (which has
VGA Text). Check that the Java app enables DMA output by writing `1` to
register +0.

**Black screen after enable**: The DMA needs a valid framebuffer address and
size. Ensure both are written before enabling. Also check that the framebuffer
address points to actual SDRAM (not beyond physical memory).

**FIFO underrun (status bit 2)**: The DMA couldn't keep up with pixel output.
This can happen if other bus masters (multiple CPU cores, stack DMA) consume
too much SDRAM bandwidth. Reduce contention or increase FIFO depth.

**Image appears shifted**: Should not occur with the current frame-based DMA
design. If seen, check that the CDC FIFO is being fully drained during
vertical blanking (lines 480-491) and that DMA restarts at the correct point
(vsync end, line 492).

**Tearing artifacts**: The DMA reads the framebuffer continuously during each
frame. If the CPU updates the framebuffer while it's being read, tearing is
possible. Use vsync synchronization to update during vertical blanking:

```java
VgaDma vga = VgaDma.getInstance();
vga.clearVsync();
while (!vga.isVsyncPending()) { }
// Update framebuffer here -- safe during vertical blanking
```
