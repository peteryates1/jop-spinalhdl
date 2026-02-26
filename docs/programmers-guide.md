# I/O Programmer's Guide

This document describes the Java API for JOP's I/O devices. Each device has a
**hardware object** (`com.jopdesign.hw.*`) that maps volatile fields directly
to I/O registers — field reads and writes become I/O reads and writes with no
overhead. Hardware objects are the recommended way to access I/O.

## Hardware Object Pattern

Each device is a singleton obtained via `getInstance()`. Fields are `volatile`
and map sequentially to I/O registers starting at the device's base address.
Reading a field reads the corresponding hardware register; writing a field
writes it.

```java
import com.jopdesign.hw.VgaText;

VgaText vga = VgaText.getInstance();
vga.statusControl = 1;                    // enable VGA (write to register +0)
int status = vga.statusControl;           // read status (read from register +0)
```

All hardware object classes are in `java/runtime/src/jop/com/jopdesign/hw/`.

## Available Devices

| Class | Device | Java Constant | Config Flag |
|---|---|---|---|
| `SysDevice` | System registers | `IO_BASE + 0x00` | always |
| `SerialPort` | UART serial port | `IO_UART` | `hasUart` |
| `EthMac` | Ethernet MAC | `IO_ETH` | `hasEth` |
| `Mdio` | MDIO PHY management | `IO_MDIO` | `hasEth` |
| `SdSpi` | SD card (SPI mode) | `IO_SD_SPI` | `hasSdSpi` |
| `SdNative` | SD card (native 4-bit) | `IO_SD` | `hasSdNative` |
| `VgaText` | VGA text controller | `IO_VGA` | `hasVgaText` |

Device presence is controlled by `IoConfig` flags. Devices not present in the
build return 0 on read. SD SPI and SD Native are mutually exclusive.

---

## SysDevice -- System Registers

```java
SysDevice sys = SysDevice.getInstance();
```

| Field | Register | Read | Write |
|---|:---:|---|---|
| `cntInt` | +0 | Clock cycle counter | Interrupt enable |
| `uscntTimer` | +1 | Microsecond counter | Timer compare |
| `intNr` | +2 | Interrupt source | SW interrupt trigger |
| `wd` | +3 | -- | Watchdog |
| `exception` | +4 | Exception type | Exception trigger |
| `lock_acquire` | +5 | Lock status (halted) | Lock acquire |
| `cpuId` | +6 | CPU ID | Lock release |
| `signal` | +7 | Boot signal | Boot signal set |
| `intMask` | +8 | -- | Interrupt mask |
| `clearInt` | +9 | -- | Clear all interrupts |
| `deadLine` | +10 | -- | Deadline |
| `nrCpu` | +11 | CPU count | -- |
| `perfCounter` | +12 | -- | Performance counter reset |

### Timing

```java
SysDevice sys = SysDevice.getInstance();
int start = sys.cntInt;
// ... work ...
int cycles = sys.cntInt - start;
```

### Watchdog

Toggle bit 0 periodically to prove liveness (bit 0 drives an LED):

```java
sys.wd = sys.wd ^ 1;
```

---

## SerialPort -- UART

```java
SerialPort uart = SerialPort.instance0;
```

| Field | Register | Read | Write |
|---|:---:|---|---|
| `status` | +0 | Status (TDRE, RDRF) | -- |
| `data` | +1 | RX byte (consumes from FIFO) | TX byte (pushes to FIFO) |

**Status bits**: `MASK_TDRE = 1` (TX has space), `MASK_RDRF = 2` (RX has data).

UART config: 8N1, 1 Mbaud, 16-entry TX and RX FIFOs.

### Send / Receive

```java
// Blocking send
while (!uart.txEmpty()) { }
uart.write('A');

// Blocking receive
while (!uart.rxFull()) { }
int ch = uart.read();

// Non-blocking receive
if (uart.rxFull()) {
    int ch = uart.read();
}
```

### Multi-Core Note

Only core 0 has a UART. Other cores' reads return 0.

---

## EthMac -- Ethernet MAC

```java
EthMac eth = EthMac.getInstance();
```

| Field | Register | Read | Write |
|---|:---:|---|---|
| `statusControl` | +0 | Status | Control (flush) |
| `txAvailability` | +1 | TX free words | -- |
| `txData` | +2 | -- | TX data push |
| `rxData` | +3 | RX data pop | -- |
| `rxStats` | +4 | Errors/drops (auto-clear) | -- |

Supports MII (100Mbps) and GMII (1Gbps). See [db-fpga-ethernet.md](db-fpga-ethernet.md).

### Status Bits

| Constant | Bit | Meaning |
|---|---|---|
| `STATUS_TX_FLUSH` | 0 | TX flush active |
| `STATUS_TX_READY` | 1 | TX ready |
| `STATUS_RX_FLUSH` | 4 | RX flush active |
| `STATUS_RX_VALID` | 5 | RX frame available |

### Transmit

Write frame length in bits first, then data words (little-endian byte order):

```java
int frameBytes = 42;
eth.txData = frameBytes * 8;                       // length in bits
eth.txData = EthMac.le(0xFF, 0xFF, 0xFF, 0xFF);   // dst MAC [0:3]
eth.txData = EthMac.le(0xFF, 0xFF, 0x02, 0x00);   // dst [4:5] + src [0:1]
// ... remaining words ...
```

### Receive

```java
if (eth.rxValid()) {
    int bitLen = eth.rxData;                // first pop = frame length in bits
    int words = (bitLen + 31) / 32;
    for (int i = 0; i < words; i++) {
        int word = eth.rxData;              // data words (auto-pop)
    }
}
```

### Stats and Flush

```java
int stats = eth.rxStats;                    // auto-clears on read
int errors = EthMac.errors(stats);
int drops = EthMac.drops(stats);

eth.flush();                                // flush TX + RX
// ... wait ...
eth.unflush();
```

---

## Mdio -- MDIO PHY Management

```java
Mdio mdio = Mdio.getInstance();
```

| Field | Register | Read | Write |
|---|:---:|---|---|
| `command` | +0 | bit0=busy | go/write |
| `data` | +1 | Read data [15:0] | Write data [15:0] |
| `address` | +2 | -- | regAddr[4:0], phyAddr[9:5] |
| `phyReset` | +3 | -- | PHY reset (bit0) |
| `intControl` | +4 | Interrupt pending | Interrupt enable mask |

### Read / Write PHY Register

```java
int phyId = mdio.read(0, 2);     // read PHY 0, register 2 (ID1)

mdio.write(0, 0, 0x1200);        // write PHY 0, register 0 (restart auto-neg)
```

These methods handle addressing, go/busy polling, and return the result.

### PHY Reset

```java
mdio.assertReset();
// delay ~10ms
mdio.releaseReset();
// delay ~150ms for PHY to initialize
```

### Interrupt Control

| Constant | Bit | Source |
|---|---|---|
| `INT_ETH_RX` | 0 | Ethernet RX frame available |
| `INT_ETH_TX` | 1 | Ethernet TX complete |
| `INT_MDIO` | 2 | MDIO operation complete |

---

## SdSpi -- SD Card (SPI Mode)

```java
SdSpi sd = SdSpi.getInstance();
```

| Field | Register | Read | Write |
|---|:---:|---|---|
| `statusControl` | +0 | Status | Control (CS, intEnable) |
| `txRxData` | +1 | RX byte [7:0] | TX byte (starts transfer) |
| `clockDivider` | +2 | Clock divider [15:0] | Set divider |

SPI Mode 0 (CPOL=0, CPHA=0). `SPI_CLK = sys_clk / (2 * (divider + 1))`.
Default divider=199 gives ~200 kHz at 80 MHz.

**Note**: SD SPI and SD Native are mutually exclusive (share pins).

### Status Bits

| Constant | Bit | Meaning |
|---|---|---|
| `STATUS_BUSY` | 0 | SPI transfer in progress |
| `STATUS_CARD_PRESENT` | 1 | SD card detected |
| `STATUS_INT_ENABLE` | 2 | Interrupt enabled |

### SPI Transfer

```java
sd.csAssert();                       // CS low
int rx = sd.transfer(0x40 | 0);      // send CMD0, receive response
sd.csDeassert();                     // CS high

sd.send(0xFF);                       // send byte, ignore response
int b = sd.receive();                // send 0xFF, return response
```

### Clock Speed

```java
sd.setClockDivider(1);  // ~20 MHz at 80 MHz sys clock
```

### Card Detection

```java
if (sd.isCardPresent()) { ... }
```

---

## SdNative -- SD Card (Native 4-bit Mode)

```java
SdNative sd = SdNative.getInstance();
```

| Field | Register | Read | Write |
|---|:---:|---|---|
| `statusControl` | +0 | Status | Control (sendCmd/abort/openDrain) |
| `cmdArgResponse0` | +1 | CMD response [31:0] | CMD argument |
| `cmdIndexResponse1` | +2 | CMD response [63:32] | CMD index/flags |
| `response2` | +3 | CMD response [95:64] | -- |
| `response3` | +4 | CMD response [127:96] | -- |
| `dataFifo` | +5 | FIFO pop | FIFO push |
| `occupancyClkDiv` | +6 | RX FIFO occupancy | Clock divider [9:0] |
| `crcDataCtrl` | +7 | CRC error flags | Data xfer control |
| `cardBusWidth` | +8 | cardPresent, busWidth4 | Set busWidth4 |
| `blockLength` | +9 | -- | Block length in bytes |

Hardware handles CRC7 (CMD) and CRC16 (DATA). 512-byte block FIFO.

### Status Bits

| Constant | Bit | Meaning |
|---|---|---|
| `STATUS_CMD_BUSY` | 0 | CMD in progress |
| `STATUS_CMD_RESP_VALID` | 1 | Response received |
| `STATUS_CMD_RESP_ERR` | 2 | Response CRC error |
| `STATUS_CMD_TIMEOUT` | 3 | CMD timeout |
| `STATUS_DATA_BUSY` | 4 | Data transfer in progress |
| `STATUS_DATA_CRC_ERR` | 5 | Data CRC error |
| `STATUS_DATA_TIMEOUT` | 6 | Data timeout |
| `STATUS_DATA_DONE` | 7 | Data transfer complete |
| `STATUS_CARD_PRESENT` | 8 | Card detected |
| `STATUS_FIFO_FULL` | 9 | FIFO full |
| `STATUS_FIFO_EMPTY` | 10 | FIFO empty |
| `STATUS_FIFO_HAS_DATA` | 11 | FIFO has data |

### Send Command

```java
// CMD0 (GO_IDLE): no argument, no response
sd.sendCmd(0, 0, false, false);
sd.waitCmd();

// CMD17 (READ_SINGLE_BLOCK): arg=block address, short response
sd.sendCmd(17, blockAddr, true, false);
int st = sd.waitCmd();
int resp = sd.cmdArgResponse0;  // R1 response
```

### Data Read

```java
sd.setBlockLength(512);
sd.sendCmd(17, blockAddr, true, false);
sd.waitCmd();
sd.startRead();
sd.waitData();
for (int i = 0; i < 128; i++) {   // 512 bytes = 128 words
    int word = sd.dataFifo;
}
```

### Configuration

```java
sd.setClockDivider(0);      // fastest clock: sys_clk / 2
sd.setBusWidth4(true);       // enable 4-bit data bus
```

---

## VgaText -- VGA Text Controller

```java
VgaText vga = VgaText.getInstance();
```

| Field | Register | Read | Write |
|---|:---:|---|---|
| `statusControl` | +0 | Status | Control (enable) |
| `cursorPos` | +1 | Cursor position | Set cursor |
| `charAttr` | +2 | -- | Write char+attr (auto-advance) |
| `defaultAttr` | +3 | Default attribute | Set default attr |
| `paletteWrite` | +4 | -- | Palette write |
| `columns` | +5 | Columns (80) | -- |
| `rows` | +6 | Rows (30) | -- |
| `directWrite` | +7 | -- | Direct write (no cursor change) |
| `clearScreen` | +8 | -- | Clear screen |
| `scrollUp` | +9 | -- | Scroll up 1 row |

80x30 characters, 8x16 font, 640x480@60Hz, RGB565 output.
See [db-fpga-vga-text.md](db-fpga-vga-text.md) for hardware setup.

### Status Bits

| Constant | Bit | Meaning |
|---|---|---|
| `STATUS_ENABLED` | 0 | Output active |
| `STATUS_VBLANK` | 1 | Vertical blanking |
| `STATUS_BUSY` | 2 | Scroll/clear in progress |

### Quick Start

```java
VgaText vga = VgaText.getInstance();
vga.enable();
vga.clear(VgaText.attr(VgaText.WHITE, VgaText.BLACK));
vga.setCursor(0, 0);
vga.writeString("Hello VGA!", VgaText.attr(VgaText.YELLOW, VgaText.BLUE));
```

### Text Output

```java
vga.setCursor(10, 5);                                       // column 10, row 5
vga.writeChar('A', VgaText.attr(VgaText.WHITE, VgaText.BLACK));  // at cursor, advances
vga.writeString("Hello", 0x0F);                              // string at cursor
vga.writeAt('X', 0x1E, 79, 0);                              // direct: col 79, row 0
```

### Screen Operations

```java
vga.setDefaultAttr(VgaText.attr(VgaText.WHITE, VgaText.BLUE));
vga.clear();               // clears with default attr, waits for completion
vga.clear(0x0F);           // clears with specific attr
vga.scroll();              // scroll up, clears bottom row
```

### Color Constants

```java
VgaText.BLACK, VgaText.BLUE, VgaText.GREEN, VgaText.CYAN,
VgaText.RED, VgaText.MAGENTA, VgaText.BROWN, VgaText.LIGHT_GRAY,
VgaText.DARK_GRAY, VgaText.LIGHT_BLUE, VgaText.LIGHT_GREEN, VgaText.LIGHT_CYAN,
VgaText.LIGHT_RED, VgaText.LIGHT_MAGENTA, VgaText.YELLOW, VgaText.WHITE
```

Build attribute: `VgaText.attr(fg, bg)` returns `(bg << 4) | fg`.

See `java/apps/Small/src/test/VgaTest.java` for a complete hardware test.

---

## Low-Level I/O Access

All I/O can also be accessed directly via `Native.rd(addr)` and
`Native.wr(value, addr)` using constants from `Const.java`. This is useful
for hardware debugging or when a hardware object is not available.

### I/O Address Map

Base address: `Const.IO_BASE` (`0xFFFFFF80`). The low 7 bits form an 8-bit
`ioAddr` used for device routing by the memory controller.

| ioAddr Range | Size | Java Base | Device |
|---|---|---|---|
| `0x80`--`0x8F` | 16 regs | `IO_BASE + 0x00` | BmbSys |
| `0x90`--`0x93` | 4 regs | `IO_BASE + 0x10` | BmbUart |
| `0x98`--`0x9F` | 8 regs | `IO_BASE + 0x18` | BmbEth |
| `0xA0`--`0xA7` | 8 regs | `IO_BASE + 0x20` | BmbMdio |
| `0xA8`--`0xAB` | 4 regs | `IO_BASE + 0x28` | BmbSdSpi |
| `0xB0`--`0xBF` | 16 regs | `IO_BASE + 0x30` | BmbSdNative |
| `0xC0`--`0xCF` | 16 regs | `IO_BASE + 0x40` | BmbVgaText |

### Low-Level Example

```java
// Direct register access (equivalent to VgaText methods)
Native.wr(1, Const.IO_VGA + 0);                          // enable
Native.wr(0x0F, Const.IO_VGA + 3);                       // default attr
Native.wr(0, Const.IO_VGA + 8);                          // clear
while ((Native.rd(Const.IO_VGA + 0) & 4) != 0) { }      // wait busy

Native.wr((0 & 0x7F) | ((0 & 0x1F) << 8), Const.IO_VGA + 1);  // cursor (0,0)
Native.wr(('H' & 0xFF) | ((0x0F & 0xFF) << 8), Const.IO_VGA + 2);  // write 'H'
```

### I/O Routing

The memory controller detects I/O addresses by bits [31:30] = `11`. I/O reads
and writes are single-cycle (no wait states). The 8-bit `ioAddr` is decoded
by `JopIoSpace` predicates in `JopMemoryConfig.scala`.

Source files: `JopMemoryConfig.scala` (`JopIoSpace`), `JopCore.scala`.

## See Also

- [DB_FPGA Ethernet](db-fpga-ethernet.md) -- GMII architecture, pin mapping, PHY config
- [DB_FPGA VGA Text](db-fpga-vga-text.md) -- VGA text setup guide and troubleshooting
- [System Configuration](system-configuration.md) -- IoConfig, board configs
- [Implementation Notes](implementation-notes.md) -- Architecture details
