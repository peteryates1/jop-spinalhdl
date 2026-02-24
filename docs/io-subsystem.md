# I/O Subsystem Programmer's Guide

This document describes the JOP I/O subsystem as implemented in SpinalHDL. It covers the register maps, addressing scheme, and Java-side API for the two I/O slaves: BmbSys (system) and BmbUart (serial port).

## Address Space

JOP's 32-bit address space uses the top 2 bits for routing:

| Bits [31:30] | Region |
|---|---|
| `00`, `01` | External memory (SDRAM/BRAM) |
| `10` | Scratch pad (unused) |
| `11` | I/O space (`0xFFFFFF80` -- `0xFFFFFFFF`) |

The I/O base address is `0xFFFFFF80` (Java constant `Const.IO_BASE`). Within I/O space, bit layout is:

```
  [31:30] = 11 (I/O space)
  [7:6]   = reserved
  [5:4]   = slave select (0-3)
  [3:0]   = sub-address within slave
```

Slave selection (bits [5:4]):

| Slave ID | Address Range | Component |
|---|---|---|
| 0 | `IO_BASE + 0x00` -- `IO_BASE + 0x0F` | BmbSys (system registers) |
| 1 | `IO_BASE + 0x10` -- `IO_BASE + 0x1F` | BmbUart (serial port) |
| 2-3 | `IO_BASE + 0x20` -- `IO_BASE + 0x3F` | Not implemented (reads return 0) |

Source: `JopCore.scala` routes I/O using `ioSlaveId = ioAddr(5 downto 4)`.

## BmbSys -- System Registers (Slave 0)

**Source**: `spinalhdl/src/main/scala/jop/io/BmbSys.scala`

**VHDL equivalent**: `sc_sys.vhd`

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
- `EXC_NP = 2` — Null pointer (handle address == 0)
- `EXC_AB = 3` — Array bounds (negative index or index >= length)

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

## BmbUart -- Serial Port (Slave 1)

**Source**: `spinalhdl/src/main/scala/jop/io/BmbUart.scala`

**VHDL equivalent**: `sc_uart.vhd`

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

Only core 0 has a UART (`JopCoreConfig.hasUart`). Other cores have no UART instance -- their slave 1 reads return 0. All serial output (e.g., `System.out.println`) must be done from core 0.

## I/O Routing in the Memory Controller

The memory controller (`BmbMemoryController.scala`) detects I/O addresses by checking bits [31:30] = `11`. I/O reads and writes are single-cycle operations (no wait states). The address byte (bits [7:0]) is passed to `JopCore`, which decodes the slave select and sub-address:

```
ioAddr[7:0] from memory controller
    |
    +-- [5:4] = slave select --> BmbSys (0) or BmbUart (1)
    +-- [3:0] = sub-address  --> register within slave
```

I/O reads are combinational: the memory controller presents the address, the slave responds in the same cycle, and the result is available on the next cycle (matching SimpCon `rdy_cnt=1`).

## See Also

- [implementation-notes.md](implementation-notes.md) -- Full architecture details
- [JOPA_TOOL.md](JOPA_TOOL.md) -- Microcode assembler
- [STACK_ARCHITECTURE.md](STACK_ARCHITECTURE.md) -- Stack buffer architecture
