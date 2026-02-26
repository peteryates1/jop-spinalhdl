# I/O Subsystem Programmer's Guide

This document describes the JOP I/O subsystem register maps, addressing scheme, and Java API for all I/O devices. All I/O access is via `Native.rd(addr)` and `Native.wr(value, addr)` using constants from `Const.java`.

## Address Space

JOP's 32-bit address space uses the top 2 bits for routing:

| Bits [31:30] | Region |
|---|---|
| `00`, `01` | External memory (SDRAM/BRAM) |
| `10` | Scratch pad (unused) |
| `11` | I/O space (`0xFFFFFF80` -- `0xFFFFFFFF`) |

The I/O base address is `0xFFFFFF80` (Java constant `Const.IO_BASE`). The low 7 bits form an 8-bit I/O address (`ioAddr`) used for device routing.

### I/O Address Map

The memory controller extracts an 8-bit `ioAddr` from the full address. Each device occupies a range of addresses and decodes a sub-address from the low bits.

| ioAddr Range | Size | Java Base | Device | Config Flag |
|---|---|---|---|---|
| `0x80`--`0x8F` | 16 regs | `IO_BASE + 0x00` | BmbSys (system) | always |
| `0x90`--`0x93` | 4 regs | `IO_BASE + 0x10` | BmbUart (serial) | `hasUart` |
| `0x98`--`0x9F` | 8 regs | `IO_BASE + 0x18` | BmbEth (Ethernet MAC) | `hasEth` |
| `0xA0`--`0xA7` | 8 regs | `IO_BASE + 0x20` | BmbMdio (PHY management) | `hasEth` |
| `0xA8`--`0xAB` | 4 regs | `IO_BASE + 0x28` | BmbSdSpi (SD card SPI) | `hasSdSpi` |
| `0xB0`--`0xBF` | 16 regs | `IO_BASE + 0x30` | BmbSdNative (SD card) | `hasSdNative` |
| `0xC0`--`0xCF` | 16 regs | `IO_BASE + 0x40` | BmbVgaText (VGA text) | `hasVgaText` |

Source: `JopMemoryConfig.scala` (`JopIoSpace` object), `JopCore.scala`.

Devices not present in the build configuration return 0 on read. Device presence is controlled by `IoConfig` flags (see [system-configuration.md](system-configuration.md)).

---

## BmbSys -- System Registers

**Source**: `spinalhdl/src/main/scala/jop/io/BmbSys.scala`

**Java base**: `Const.IO_BASE + 0x00` (`IO_CNT`, `IO_WD`, etc.)

**Parameters**: `clkFreqHz` (clock frequency), `cpuId` (core ID, 0-based), `cpuCnt` (total cores)

### Register Map

All registers are 32 bits wide. Sub-address is bits [3:0] of the I/O address.

| Addr | Java Constant | Read | Write |
|---|---|---|---|
| 0 | `IO_CNT` / `IO_INT_ENA` | Clock cycle counter (free-running, 32-bit) | Interrupt enable (bit 0: 1=enable, 0=disable) |
| 1 | `IO_US_CNT` / `IO_TIMER` | Microsecond counter (prescaled from clkFreqHz) | Timer compare register |
| 2 | `IO_SWINT` / `IO_INTNR` | Interrupt source number (bits [4:0], captured on ackIrq) | Software interrupt (sets intstate for source N) |
| 3 | `IO_WD` | -- | Watchdog register (value exposed on `io.wd` output) |
| 4 | `IO_EXCPT` | Exception type (8-bit, set by memory controller) | Exception trigger (sets type and fires exc pulse) |
| 5 | `IO_LOCK` | Lock status: bit 0 = halted | Lock acquire (sets lockReq to CmpSync) |
| 6 | `IO_CPU_ID` / `IO_UNLOCK` | CPU ID (compile-time constant) | Lock release (clears lockReq) |
| 7 | `IO_SIGNAL` | Boot signal (broadcast from core 0 via CmpSync) | Boot signal (bit 0 written to signalReg) |
| 8 | `IO_INTMASK` | -- | Interrupt mask (per-source enable bits) |
| 9 | `IO_INTCLEARALL` | -- | Clear all interrupt flags |
| 11 | `IO_CPUCNT` | CPU count (compile-time constant) | -- |
| 13 | `IO_GC_HALT` | -- | GC halt (bit 0: 1=halt other cores, 0=release) |

### Clock Cycle Counter (addr 0, read)

Free-running 32-bit counter incrementing every clock cycle. Used by `Startup.java` to measure clock frequency.

```java
int start = Native.rd(Const.IO_CNT);
// ... do work ...
int elapsed = Native.rd(Const.IO_CNT) - start;
```

### Microsecond Counter (addr 1, read)

32-bit counter incrementing once per microsecond. Derived from a prescaler: `divVal = clkFreqHz / 1_000_000 - 1`. At 100 MHz, the prescaler counts down from 99 and increments `usCntReg` on each zero crossing.

```java
int now = Native.rd(Const.IO_US_CNT);
int deadline = now + 1000; // 1ms from now
while (Native.rd(Const.IO_US_CNT) - deadline < 0) { /* wait */ }
```

### Watchdog Register (addr 3, write)

32-bit register written by Java code to prove liveness. Bit 0 is routed to an LED on all FPGA targets. The Java application should toggle this periodically.

```java
Native.wr(wdValue ^ 1, Const.IO_WD); // toggle bit 0
```

The full 32-bit value is exposed on `io.wd` but only bit 0 reaches the LED pin. There is no read-back path -- the register is write-only from the Java side.

### Exception Register (addr 4)

**Read**: Returns the 8-bit exception type, zero-extended to 32 bits. Read by `JVMHelp.except()` to determine which Java exception to throw.

**Write**: Sets the exception type (bits [7:0]) and fires a one-cycle `exc` pulse to the bcfetch stage, which triggers exception handling on the next bytecode fetch.

**Exception types** (defined in `JVMHelp.java`):
- `EXC_NP = 2` -- Null pointer (handle address == 0)
- `EXC_AB = 3` -- Array bounds (negative index or index >= length)

**Exception flow**: The memory controller (`BmbMemoryController`) detects violations during handle dereference and writes the exception type to addr 4 via the I/O bus. BmbSys latches the type in `excTypeReg`, sets `excPend`, and fires a single-cycle `exc` pulse. BytecodeFetchStage catches the pulse (using combinational `excPendImmediate = excPend || io.exc`) and redirects the next bytecode fetch to the `sys_exc` microcode handler. `sys_exc` calls `JVMHelp.except()`, which reads the exception type from addr 4, selects the pre-allocated exception object (`NPExc` or `ABExc`), and throws it via `f_athrow`. The Java catch handler receives the exception normally.

### CMP Lock Interface (addrs 5-7)

Used by `monitorenter`/`monitorexit` for multi-core mutual exclusion via CmpSync.

**Lock acquire** (addr 5, write): Sets `lockReqReg` high. CmpSync grants the lock to one core and halts all others. The requesting core's pipeline stalls until the lock is granted.

**Lock status** (addr 5, read): Bit 0 = `halted` (1 if this core is halted by CmpSync, 0 if running or lock owner).

**Lock release** (addr 6, write): Clears `lockReqReg`. CmpSync immediately checks for the next requester (no idle gap). In VHDL, the `wr` signal is held high by the pipeline stall; in SpinalHDL, a held register achieves the same effect.

**CPU ID** (addr 6, read): Returns the compile-time `cpuId` parameter. Addr 6 is shared: reads return CPU ID, writes release the lock.

**Boot signal** (addr 7): Core 0 writes `1` after initialization is complete. CmpSync broadcasts this to all cores via `s_out`. Secondary cores poll addr 7 until they see `1`, then proceed to their application entry point.

**CPU count** (addr 11, read): Returns the compile-time `cpuCnt` parameter. Used by `Startup.java` to size per-core data structures.

```java
// Core 0: signal boot complete
Native.wr(1, Const.IO_SIGNAL);

// Core N: wait for boot signal
while (Native.rdMem(Const.IO_SIGNAL) == 0) { /* spin */ }
```

### CmpSync Protocol (multi-core only)

CmpSync (`spinalhdl/src/main/scala/jop/io/CmpSync.scala`) provides global mutual exclusion with round-robin fairness:

1. Core writes to `IO_LOCK` -> `lockReqReg` goes high -> CmpSync sees `req=1`
2. CmpSync grants lock to one core (`halted=0`), halts all others (`halted=1`)
3. Halted cores' pipelines stall (via `BmbSys.io.halted` -> `JopCore`)
4. Lock owner writes to `IO_UNLOCK` -> `lockReqReg` goes low -> CmpSync sees `req=0`
5. CmpSync immediately arbitrates among remaining requesters (round-robin)

The round-robin arbiter rotates priority after each grant. For 2 cores, this alternates between core 0 and core 1.

---

## BmbUart -- Serial Port

**Source**: `spinalhdl/src/main/scala/jop/io/BmbUart.scala`

**Java base**: `Const.IO_STATUS` / `Const.IO_UART` (`IO_BASE + 0x10`)

**Parameters**: `baudRate` (default 1,000,000), `clkFreqHz` (default 100,000,000)

**UART config**: 8N1 (8 data bits, no parity, 1 stop bit), 5x oversampling

### Register Map

| Addr | Java Constant | Read | Write |
|---|---|---|---|
| 0 | `IO_STATUS` / `IO_UART_STATUS` | Status register | -- |
| 1 | `IO_UART` / `IO_UART_DATA` | RX data (consumes from FIFO) | TX data (pushes to FIFO) |

### Status Register (addr 0, read)

| Bit | Name | Meaning |
|---|---|---|
| 0 | TDRE (TX Data Register Empty) | 1 = TX FIFO has space, 0 = TX FIFO full |
| 1 | RDRF (RX Data Register Full) | 1 = RX FIFO has data, 0 = RX FIFO empty |
| 31:2 | -- | Always 0 |

Java constants: `Const.MSK_UA_TDRE = 1`, `Const.MSK_UA_RDRF = 2`.

### Data Register (addr 1)

**Read**: Returns the next byte from the 16-entry RX FIFO (bits [7:0], zero-extended). The byte is consumed on read. Reading when RDRF=0 returns stale data.

**Write**: Pushes bits [7:0] to the 16-entry TX FIFO. The UART controller drains the FIFO at the configured baud rate. Writing when TDRE=0 drops the byte.

### Typical Usage

**Transmit a character** (from `JVMHelp.wr_char`):

```java
// Wait for TX FIFO space
while ((Native.rd(Const.IO_UART_STATUS) & Const.MSK_UA_TDRE) == 0) { }
// Write character
Native.wr(c, Const.IO_UART_DATA);
```

**Receive a character** (from `JOPInputStream`):

```java
// Wait for RX data
while ((Native.rd(Const.IO_UART_STATUS) & Const.MSK_UA_RDRF) == 0) { }
// Read character (consumes from FIFO)
int c = Native.rd(Const.IO_UART_DATA);
```

**Check for available data** (non-blocking):

```java
if ((Native.rd(Const.IO_UART_STATUS) & Const.MSK_UA_RDRF) != 0) {
    int c = Native.rd(Const.IO_UART_DATA);
    // process byte
}
```

### FIFO Details

Both TX and RX use 16-entry StreamFIFOs from SpinalHDL's standard library. The RX FIFO is necessary because `UartCtrl.read.valid` is a one-cycle pulse -- without buffering, received bytes would be lost if the processor doesn't read them immediately.

At 1 Mbaud with 8N1 (10 bits per byte), the maximum receive rate is 100,000 bytes/sec. The 16-entry RX FIFO provides 160 us of buffering before overflow.

### Multi-Core Note

Only core 0 has a UART (`JopCoreConfig.hasUart`). Other cores have no UART instance -- their reads return 0. All serial output (e.g., `System.out.println`) must be done from core 0.

---

## BmbEth -- Ethernet MAC

**Source**: `spinalhdl/src/main/scala/jop/io/BmbEth.scala`

**Java base**: `Const.IO_ETH` (`IO_BASE + 0x18`)

**Config**: `IoConfig.hasEth = true`, `IoConfig.ethGmii = true` for 1Gbps GMII

Wraps SpinalHDL's `MacEth` with JOP I/O interface. Supports MII (4-bit, 100Mbps) and GMII (8-bit, 1Gbps) modes. See [db-fpga-ethernet.md](db-fpga-ethernet.md) for hardware details.

### Register Map

| Addr | Java Constant | Read | Write |
|---|---|---|---|
| 0 | `IO_ETH + 0` | Status | Control |
| 1 | `IO_ETH + 1` | TX availability (free words) | -- |
| 2 | `IO_ETH + 2` | -- | TX data push |
| 3 | `IO_ETH + 3` | RX data pop | -- |
| 4 | `IO_ETH + 4` | RX stats (auto-clear) | -- |

### Status / Control (addr 0)

| Bit | Read | Write |
|---|---|---|
| 0 | TX flush active | TX flush (1=flush) |
| 1 | TX ready | -- |
| 4 | RX flush active | RX flush (1=flush) |
| 5 | RX valid (frame available) | -- |

### TX Protocol

Write frame length in bits to addr 2, then write `ceil(length/32)` data words. MacTxBuffer auto-commits after the correct number of words. Data is little-endian byte order within each 32-bit word.

```java
int frameLen = 42;          // bytes
int frameBits = frameLen * 8;
Native.wr(frameBits, Const.IO_ETH + 2);           // length in bits
Native.wr(le(0xFF,0xFF,0xFF,0xFF), Const.IO_ETH + 2);  // word 1
Native.wr(le(0xFF,0xFF,0x02,0x00), Const.IO_ETH + 2);  // word 2
// ... remaining words ...
```

Helper for little-endian byte packing:

```java
static int le(int b0, int b1, int b2, int b3) {
    return (b0 & 0xFF) | ((b1 & 0xFF) << 8) |
           ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
}
```

### RX Protocol

When status bit 5 (RX valid) is set, read addr 3 repeatedly. First read returns frame length in bits, subsequent reads return data words. Each read pops one word from the RX stream.

```java
int st = Native.rd(Const.IO_ETH + 0);
if ((st & (1 << 5)) != 0) {         // RX valid
    int bitLen = Native.rd(Const.IO_ETH + 3);  // first pop = length
    int words = (bitLen + 31) / 32;
    for (int i = 0; i < words; i++) {
        int word = Native.rd(Const.IO_ETH + 3);  // data words
    }
}
```

### RX Stats (addr 4, read)

Auto-clears on read.

| Bits | Meaning |
|---|---|
| [7:0] | Error count (CRC errors) |
| [15:8] | Drop count (buffer overflow) |

### Flush

Write `(1 << 0)` for TX flush, `(1 << 4)` for RX flush, or both. Poll status until flush bits clear.

```java
Native.wr((1 << 0) | (1 << 4), Const.IO_ETH + 0);  // flush both
// wait...
Native.wr(0, Const.IO_ETH + 0);                      // release flush
```

---

## BmbMdio -- MDIO PHY Management

**Source**: `spinalhdl/src/main/scala/jop/io/BmbMdio.scala`

**Java base**: `Const.IO_MDIO` (`IO_BASE + 0x20`)

**Config**: Instantiated alongside `BmbEth` when `IoConfig.hasEth = true`

Generates IEEE 802.3 clause 22 MDIO frames for PHY register access. Also provides PHY hardware reset control and Ethernet interrupt management.

### Register Map

| Addr | Java Constant | Read | Write |
|---|---|---|---|
| 0 | `IO_MDIO + 0` | bit 0 = busy | bit 0 = go, bit 1 = write(1)/read(0) |
| 1 | `IO_MDIO + 1` | Read data [15:0] | Write data [15:0] |
| 2 | `IO_MDIO + 2` | -- | [4:0] = reg addr, [9:5] = PHY addr |
| 3 | `IO_MDIO + 3` | -- | bit 0 = PHY reset (active-high register, active-low output) |
| 4 | `IO_MDIO + 4` | Interrupt pending | Interrupt enable mask |

### MDIO Read

```java
// Set PHY addr and register addr
Native.wr((regAddr & 0x1F) | ((phyAddr & 0x1F) << 5), Const.IO_MDIO + 2);
// Start read (go=1, write=0)
Native.wr(1, Const.IO_MDIO + 0);
// Wait for completion
while ((Native.rd(Const.IO_MDIO + 0) & 1) != 0) { }
// Read result
int value = Native.rd(Const.IO_MDIO + 1) & 0xFFFF;
```

### MDIO Write

```java
// Set PHY addr and register addr
Native.wr((regAddr & 0x1F) | ((phyAddr & 0x1F) << 5), Const.IO_MDIO + 2);
// Set write data
Native.wr(data & 0xFFFF, Const.IO_MDIO + 1);
// Start write (go=1, write=1)
Native.wr(3, Const.IO_MDIO + 0);
// Wait for completion
while ((Native.rd(Const.IO_MDIO + 0) & 1) != 0) { }
```

### PHY Reset (addr 3)

Write `1` to assert PHY reset (output pin driven low), write `0` to release.

```java
Native.wr(1, Const.IO_MDIO + 3);   // assert reset
// delay ~10ms
Native.wr(0, Const.IO_MDIO + 3);   // release reset
// delay ~150ms for PHY to initialize
```

### Interrupt Control (addr 4)

| Bit | Source |
|---|---|
| 0 | Ethernet RX frame available |
| 1 | Ethernet TX complete |
| 2 | MDIO operation complete |

**Read**: Returns pending interrupt flags (OR of enabled sources). **Write**: Sets interrupt enable mask.

---

## BmbSdSpi -- SD Card (SPI Mode)

**Source**: `spinalhdl/src/main/scala/jop/io/BmbSdSpi.scala`

**Java base**: `IO_BASE + 0x28` (ioAddr `0xA8`)

**Config**: `IoConfig.hasSdSpi = true`

Raw SPI master for SD cards in SPI mode. Hardware handles byte-level SPI shifting (Mode 0: CPOL=0, CPHA=0, MSB first) and chip-select control. Software drives the SD protocol (CMD0, CMD8, ACMD41, etc.).

SPI clock: `SPI_CLK = sys_clk / (2 * (divider + 1))`. Default divider=199 gives ~200 kHz at 80 MHz (safe for SD init, which requires <= 400 kHz).

**Note**: SD SPI and SD Native are mutually exclusive (share pins).

### Register Map

| Addr | Read | Write |
|---|---|---|
| 0 | Status | Control |
| 1 | RX byte [7:0] | TX byte [7:0] (starts transfer) |
| 2 | Clock divider [15:0] | Set clock divider [15:0] |

### Status (addr 0, read)

| Bit | Meaning |
|---|---|
| 0 | Busy (SPI transfer in progress) |
| 1 | Card present (active-low CD pin, synchronized) |
| 2 | Interrupt enable |

### Control (addr 0, write)

| Bit | Meaning |
|---|---|
| 0 | CS assert (1 = CS driven low / selected) |
| 1 | Interrupt enable (fires on transfer complete) |

### SPI Transfer

Write a byte to addr 1 to start an 8-bit SPI transfer. The hardware shifts out the TX byte on MOSI while simultaneously shifting in 8 bits on MISO. Poll status bit 0 (busy) for completion, then read the received byte from addr 1.

```java
// Assert CS
Native.wr(1, ioBase + 0);           // csAssert=1

// Send/receive one byte
Native.wr(txByte & 0xFF, ioBase + 1);
while ((Native.rd(ioBase + 0) & 1) != 0) { }  // wait for busy=0
int rxByte = Native.rd(ioBase + 1) & 0xFF;

// Deassert CS
Native.wr(0, ioBase + 0);           // csAssert=0
```

### Clock Divider (addr 2)

Read or write the 16-bit clock divider. Change to a lower value after SD card initialization to increase SPI clock speed.

```java
// Set clock to ~20 MHz at 80 MHz system clock: 80/(2*(1+1)) = 20 MHz
Native.wr(1, ioBase + 2);
```

### SD Card Init Sequence (typical)

1. Set divider for <= 400 kHz (default 199 at 80 MHz)
2. Deassert CS, send 10 bytes of 0xFF (80 clock cycles for card power-up)
3. Assert CS, send CMD0 (GO_IDLE_STATE), expect R1 = 0x01
4. Send CMD8 (SEND_IF_COND), check voltage acceptance
5. Loop ACMD41 (SD_SEND_OP_COND) until card ready
6. Send CMD58 (READ_OCR) to check CCS bit
7. Increase clock divider for data transfers
8. Send CMD17 (READ_SINGLE_BLOCK) / CMD24 (WRITE_BLOCK) for data

---

## BmbSdNative -- SD Card (Native Mode)

**Source**: `spinalhdl/src/main/scala/jop/io/BmbSdNative.scala`

**Java base**: `Const.IO_SD` (`IO_BASE + 0x30`)

**Config**: `IoConfig.hasSdNative = true`

4-bit SD native mode controller. Hardware handles clock generation, CMD shift register with CRC7, DATA shift register with per-line CRC16, and a 512-byte block FIFO. Software drives the SD protocol (CMD sequences).

### Register Map

| Addr | Java Constant | Read | Write |
|---|---|---|---|
| 0 | `IO_SD + 0` | Status | Control (sendCmd, abort, openDrain) |
| 1 | `IO_SD + 1` | CMD response [31:0] | CMD argument [31:0] |
| 2 | `IO_SD + 2` | CMD response [63:32] | CMD index/flags |
| 3 | `IO_SD + 3` | CMD response [95:64] (R2) | -- |
| 4 | `IO_SD + 4` | CMD response [127:96] (R2) | -- |
| 5 | `IO_SD + 5` | Data FIFO pop | Data FIFO push |
| 6 | `IO_SD + 6` | RX FIFO occupancy | Clock divider |
| 7 | `IO_SD + 7` | CRC error flags | Data xfer control (startRead/startWrite) |
| 8 | `IO_SD + 8` | cardPresent, busWidth4 | busWidth4 |
| 9 | `IO_SD + 9` | -- | Block length in bytes |

### CMD Protocol

1. Write argument to addr 1
2. Write CMD index and flags to addr 2
3. Write sendCmd to addr 0
4. Poll status (addr 0) for completion
5. Read response from addrs 1--4

### Data Read

1. Set block length (addr 9)
2. Send CMD17/CMD18 to card
3. Write startRead to addr 7
4. Poll RX FIFO occupancy (addr 6)
5. Read data words from addr 5

---

## BmbVgaText -- VGA Text Controller

**Source**: `spinalhdl/src/main/scala/jop/io/BmbVgaText.scala`

**Java base**: `Const.IO_VGA` (`IO_BASE + 0x40`)

**Config**: `IoConfig.hasVgaText = true`

80x30 character text-mode VGA controller. 8x16 pixel font, 640x480 @ 60Hz, RGB565 output. Runs in a 25 MHz pixel clock domain (PLL output c3). See [db-fpga-vga-text.md](db-fpga-vga-text.md) for hardware setup guide.

### Register Map

| Addr | Java Constant | Read | Write |
|---|---|---|---|
| 0 | `IO_VGA + 0` | Status | Control |
| 1 | `IO_VGA + 1` | Cursor position | Set cursor position |
| 2 | `IO_VGA + 2` | -- | Write char+attr at cursor (auto-advance) |
| 3 | `IO_VGA + 3` | Default attribute | Set default attribute |
| 4 | `IO_VGA + 4` | -- | Palette write |
| 5 | `IO_VGA + 5` | Columns (80) | -- |
| 6 | `IO_VGA + 6` | Rows (30) | -- |
| 7 | `IO_VGA + 7` | -- | Direct write (no cursor change) |
| 8 | `IO_VGA + 8` | -- | Clear screen |
| 9 | `IO_VGA + 9` | -- | Scroll up 1 row |

### Status (addr 0, read)

| Bit | Meaning |
|---|---|
| 0 | Enabled (output active) |
| 1 | VBlank (in vertical blanking interval) |
| 2 | Busy (scroll or clear in progress) |

### Control (addr 0, write)

| Bit | Meaning |
|---|---|
| 0 | Enable VGA output (1=on, 0=off) |

### Cursor Position (addr 1)

**Read/Write**: `col[6:0]` (bits 6:0), `row[12:8]` (bits 12:8). Column range 0--79, row range 0--29.

```java
// Set cursor to column 10, row 5
Native.wr((10 & 0x7F) | ((5 & 0x1F) << 8), Const.IO_VGA + 1);
```

### Write Char+Attr (addr 2, write)

Writes a character with attribute at the current cursor position and auto-advances the cursor. At end of row, wraps to next row. At end of screen, wraps to (0,0).

| Bits | Field |
|---|---|
| [7:0] | ASCII character code |
| [15:8] | Attribute byte: `(bg << 4) \| fg` |

```java
int ch = 'A';
int attr = 0x0F;  // fg=white(15), bg=black(0)
Native.wr((ch & 0xFF) | ((attr & 0xFF) << 8), Const.IO_VGA + 2);
```

### Default Attribute (addr 3)

Read/write the default attribute used by clear screen. Attribute byte format: `(bg << 4) | fg`.

```java
Native.wr(0x0F, Const.IO_VGA + 3);  // white on black
```

### Palette Write (addr 4, write)

Reprogram a CGA palette entry. Default palette uses standard CGA colors.

| Bits | Field |
|---|---|
| [15:0] | RGB565 color value |
| [19:16] | Palette index (0--15) |

### Direct Write (addr 7, write)

Writes a character at a specified position without changing the cursor.

| Bits | Field |
|---|---|
| [7:0] | ASCII character code |
| [15:8] | Attribute byte |
| [22:16] | Column (0--79) |
| [28:24] | Row (0--29) |

```java
// Write 'X' at column 79, row 0 with yellow on blue
Native.wr(('X' & 0xFF) | ((0x1E & 0xFF) << 8)
    | ((79 & 0x7F) << 16) | ((0 & 0x1F) << 24),
    Const.IO_VGA + 7);
```

### Clear Screen (addr 8, write)

Any write triggers a hardware clear -- fills all 2400 cells with space (0x20) and the default attribute. Poll status bit 2 (busy) for completion.

```java
Native.wr(0, Const.IO_VGA + 8);                          // trigger clear
while ((Native.rd(Const.IO_VGA + 0) & 4) != 0) { }      // wait
```

### Scroll Up (addr 9, write)

Any write triggers a hardware scroll -- shifts all rows up by one, clears the bottom row with default attribute. Poll status bit 2 (busy) for completion.

```java
Native.wr(0, Const.IO_VGA + 9);                          // trigger scroll
while ((Native.rd(Const.IO_VGA + 0) & 4) != 0) { }      // wait
```

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

### Typical Usage

```java
// Enable VGA, clear screen, write text
Native.wr(1, Const.IO_VGA + 0);                          // enable
Native.wr(0x0F, Const.IO_VGA + 3);                       // default: white on black
Native.wr(0, Const.IO_VGA + 8);                          // clear
while ((Native.rd(Const.IO_VGA + 0) & 4) != 0) { }      // wait for clear

Native.wr((0 & 0x7F) | ((0 & 0x1F) << 8), Const.IO_VGA + 1);  // cursor to (0,0)
String msg = "Hello VGA!";
int attr = 0x0F;
for (int i = 0; i < msg.length(); i++) {
    Native.wr((msg.charAt(i) & 0xFF) | ((attr & 0xFF) << 8), Const.IO_VGA + 2);
}
```

See `java/apps/Small/src/test/VgaTest.java` for a complete hardware test with helper functions.

---

## I/O Routing in the Memory Controller

The memory controller (`BmbMemoryController.scala`) detects I/O addresses by checking bits [31:30] = `11`. I/O reads and writes are single-cycle operations (no wait states). The 8-bit `ioAddr` (bits [7:0]) is passed to `JopCore`, which uses match predicates from `JopIoSpace` to route to the correct device:

```
ioAddr[7:0] from memory controller
    |
    ├── [7:4] = 0x8  → BmbSys     (sub-addr = [3:0])
    ├── [7:2] = 0x24 → BmbUart    (sub-addr = [1:0])
    ├── [7:3] = 0x13 → BmbEth     (sub-addr = [2:0])
    ├── [7:3] = 0x14 → BmbMdio    (sub-addr = [2:0])
    ├── [7:2] = 0x2A → BmbSdSpi   (sub-addr = [1:0])
    ├── [7:4] = 0xB  → BmbSdNative (sub-addr = [3:0])
    └── [7:4] = 0xC  → BmbVgaText (sub-addr = [3:0])
```

I/O reads are combinational: the memory controller presents the address, the slave responds in the same cycle, and the result is available on the next cycle (matching SimpCon `rdy_cnt=1`).

## See Also

- [DB_FPGA Ethernet](db-fpga-ethernet.md) -- GMII architecture, pin mapping, PHY config
- [DB_FPGA VGA Text](db-fpga-vga-text.md) -- VGA text setup guide, Java API, troubleshooting
- [System Configuration](system-configuration.md) -- IoConfig, board configs, I/O address map
- [Implementation Notes](implementation-notes.md) -- Full architecture details
- [JOPA_TOOL.md](JOPA_TOOL.md) -- Microcode assembler
- [STACK_ARCHITECTURE.md](STACK_ARCHITECTURE.md) -- Stack buffer architecture
