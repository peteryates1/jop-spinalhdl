# Dual-Subsystem Architecture — Design Proposal

**Status**: Proposal — for discussion and future implementation.

**Target board**: QMTECH XC7A100T Wukong V3 (DDR3 + SDR SDRAM on-board).

## Contents

- [Overview](#overview)
- [Motivation](#motivation)
- [Architecture](#architecture)
  - [SDR Subsystem — I/O Cores](#sdr-subsystem--io-cores)
  - [DDR3 Subsystem — Compute Cores](#ddr3-subsystem--compute-cores)
  - [Inter-Subsystem Message Queues](#inter-subsystem-message-queues)
  - [Independent Watchdog FSM](#independent-watchdog-fsm)
- [Hardware Resources](#hardware-resources)
  - [Memory Bandwidth Budget](#memory-bandwidth-budget)
  - [FPGA Resource Budget](#fpga-resource-budget)
- [Software Model](#software-model)
  - [Message Queue I/O Registers](#message-queue-io-registers)
  - [Work Offload Pattern](#work-offload-pattern)
  - [Boot Sequence](#boot-sequence)
- [Implementation Plan](#implementation-plan)
- [Future Extensions](#future-extensions)

## Overview

The QMTECH Wukong V3 board has two independent memory subsystems on-board:
DDR3 (256 MB, 1.35V, Bank 16) and SDR SDRAM (32 MB, 3.3V, Bank 15). This
design exploits both by running two independent JOP clusters — lightweight
cores on SDR for I/O tasks, heavyweight cores on DDR3 for computation — with
FIFO-based message passing and an independent hardware watchdog.

```
┌──────────────────────────────┐    ┌──────────────────────────────┐
│     SDR SDRAM Subsystem      │    │      DDR3 Subsystem          │
│         (80 MHz)             │    │        (100 MHz)             │
│                              │    │                              │
│  Light JOP cores (1-4)       │    │  Heavy JOP cores (1-4)       │
│  - Integer-only microcode    │    │  - DSP multiply              │
│  - No compute units          │    │  - HW integer divider        │
│  - Small method cache        │    │  - Long/Float/Double units   │
│                              │    │  - Larger caches             │
│  I/O peripherals:            │    │  Compute duties:             │
│  - Ethernet (GMII)           │    │  - Application logic         │
│  - SD card (native 4-bit)    │    │  - Math-heavy workloads      │
│  - UART (USB CH340N)         │    │  - Data processing           │
│  - VGA text (80x30)          │    │  - VGA DMA framebuffer       │
│  - GPIO / LEDs               │    │                              │
│                              │    │                              │
│   tx_fifo ──────────────────────────────────────────► rx_fifo    │
│   rx_fifo ◄────────────────────────────────────────── tx_fifo    │
│                              │    │                              │
│   heartbeat ─┐               │    │               ┌─ heartbeat   │
│              │               │    │               │              │
└──────────────┼───────────────┘    └───────────────┼──────────────┘
               │                                    │
        ┌──────┴────────────────────────────────────┴──────┐
        │              Watchdog FSM (pure RTL)             │
        │                                                  │
        │  heartbeat_sdr ──► timeout counter ──► reset_sdr │
        │  heartbeat_ddr3 ──► timeout counter ──► reset_ddr│
        │                                                  │
        │  Clock: raw 50 MHz oscillator (independent)      │
        │  Outputs: reset_n, status LEDs, error flag       │
        └──────────────────────────────────────────────────┘
```

## Motivation

### Heterogeneous Core Complexity

JOP cores vary significantly depending on which bytecode compute units are
enabled:

| Core Variant | Compute Units | Approx. LUTs | Approx. BRAMs |
|---|---|---|---|
| Minimal (integer-only) | None | ~2,000 | ~6 |
| With DSP multiply | `IntMul` | ~2,500 | ~6 |
| With DSP mul + HW div | `IntMul` + `IntDiv` | ~3,500 | ~6 |
| Full (Long/Float/Double) | All | ~10,000-15,000 | ~15-20 |

Putting heavyweight cores on I/O polling tasks wastes FPGA resources. A light
core handles Ethernet MAC polling, SD card transfers, and UART I/O just as
effectively as a full core — bytecodes like `getfield`, `putfield`, `iaload`,
`iastore`, and I/O reads/writes are all integer operations.

### Fault Isolation

Two independent subsystems provide natural fault domains:

- A runaway computation on DDR3 cannot starve I/O cores of memory bandwidth
- A misbehaving peripheral cannot crash the compute subsystem
- The watchdog FSM is pure RTL with no software — nothing can crash it
- Either subsystem can be independently reset without affecting the other

### Bandwidth Matching

| Memory | Peak BW | Effective BW | Suitable For |
|---|---|---|---|
| SDR SDRAM (16-bit, 80 MHz) | ~320 MB/s | ~100-130 MB/s | I/O + 1-4 light cores |
| DDR3 (16-bit, DDR3-1333) | ~2,670 MB/s | ~1,600-1,900 MB/s | Compute + 1-4 heavy cores |

Ethernet at 1 Gbps is ~125 MB/s — SDR handles this alongside a few light
cores. DDR3 is free for computation without I/O contention.

## Architecture

### SDR Subsystem — I/O Cores

Runs on the existing JOP SDRAM controller (`JopSdramTop` style), 80 MHz
system clock derived from the 50 MHz oscillator via PLL.

**Cores**: 1-4 lightweight JOP cores with integer-only microcode. No DSP
multiply, no HW divider, no floating-point units. Stack cache optional
(DMA burst length 4 for SDR).

**Peripherals** (directly connected via BMB I/O):
- Ethernet MAC (GMII, RTL8211EG PHY)
- SD card (native 4-bit interface)
- UART (CH340N USB bridge)
- VGA text controller (80x30, optional)
- GPIO (LEDs, buttons)
- Message queue registers (to DDR3 subsystem)
- Watchdog heartbeat register

**Memory map**: Standard JOP layout — code + heap in SDR SDRAM, I/O at
`0xC0000000`, scratchpad at `0x80000000`.

### DDR3 Subsystem — Compute Cores

Runs on the DDR3 MIG controller (`JopDdr3Top` style), 100 MHz system clock
from MIG `ui_clk`. LruCacheCore L2 cache between BMB arbiter and MIG.

**Cores**: 1-4 heavyweight JOP cores with full compute units enabled:
- DSP multiply (`useDspMul = true`)
- HW integer divider (`useHwDiv = true`)
- Long arithmetic unit (future)
- Float unit (future)
- Double unit (future)

Uses `perCoreConfigs` if cores need different compute unit mixes.

**Peripherals** (minimal I/O):
- Message queue registers (to SDR subsystem)
- Watchdog heartbeat register
- VGA DMA framebuffer (optional — 640x480 or 1080p@30 depending on use case)
- Debug UART (optional, separate from main UART)

**Memory map**: Standard JOP layout — code + heap in DDR3, I/O at
`0xC0000000`, scratchpad at `0x80000000`.

### Inter-Subsystem Message Queues

Two `StreamFifoCC` instances provide bidirectional clock-domain-crossing
communication between the 80 MHz SDR domain and 100 MHz DDR3 domain.

```
     SDR domain (80 MHz)              DDR3 domain (100 MHz)
           │                                   │
           ├──► StreamFifoCC(32 bits, depth N) ►│   SDR → DDR3
           │                                   │
           │◄── StreamFifoCC(32 bits, depth N) ◄┤   DDR3 → SDR
           │                                   │
```

**FIFO parameters**:
- Data width: 32 bits (one JOP word per entry)
- Depth: 16-64 entries (configurable, power of 2 for StreamFifoCC)
- CDC: Gray-code pointer synchronization (built into StreamFifoCC)

**Message format** (application-defined, example):

| Word | Content |
|---|---|
| 0 | Command / message type (upper 8 bits) + length (lower 24 bits) |
| 1..N | Payload words |

Multi-word messages are sent as sequential FIFO pushes. Software is
responsible for framing — the hardware just moves words.

**Backpressure**: StreamFifoCC provides `full` and `empty` signals. Software
polls status before push/pop. No flow control beyond FIFO capacity — sender
must check `full` before writing.

### Independent Watchdog FSM

A pure-RTL finite state machine, outside both JOP clusters, clocked from the
raw 50 MHz oscillator (not from either PLL). This ensures the watchdog runs
even if a PLL loses lock.

**Inputs**:
- `heartbeat_sdr` — toggle signal from SDR subsystem (synchronized to 50 MHz)
- `heartbeat_ddr3` — toggle signal from DDR3 subsystem (synchronized to 50 MHz)

**Outputs**:
- `reset_sdr_n` — active-low reset to SDR subsystem
- `reset_ddr3_n` — active-low reset to DDR3 subsystem
- `status_leds[1:0]` — alive indicators (directly on board LEDs)
- `wd_error` — sticky error flag (optional, directly readable)

**Operation**:
1. Each subsystem toggles its heartbeat register periodically (e.g. every
   10 ms from a timer interrupt or a software loop)
2. The watchdog detects toggles via edge detection (XOR with previous sample)
3. Each toggle resets that subsystem's timeout counter
4. If a counter reaches the timeout threshold (e.g. 50 ms at 50 MHz =
   2,500,000 cycles), the watchdog asserts reset for that subsystem
5. Reset is held for a minimum pulse width (e.g. 1 ms), then released
6. Optional: if one subsystem fails N consecutive times, reset both

**Resource cost**: ~50 LUTs, 2 synchronizer chains, 2 counters. Negligible.

**Software interface**: Each subsystem has a single I/O register. Writing any
value toggles the heartbeat output. Typical usage:

```java
// In main loop or timer interrupt
Native.wr(heartbeat, Native.rd(heartbeat) ^ 1);
```

## Hardware Resources

### Memory Bandwidth Budget

**SDR Subsystem** (W9825G6KH-6, 16-bit, 80 MHz):

| Consumer | Bandwidth | Notes |
|---|---|---|
| 2 light JOP cores | ~100-150 MB/s | ~50-75 MB/s per core |
| Ethernet DMA | ~125 MB/s | 1 Gbps wire rate, bursty |
| VGA text | ~2 MB/s | 80x30 @ 60 Hz, minimal |
| **Total** | ~230-280 MB/s | Fits in ~320 MB/s peak |

With 2 light cores and Ethernet, SDR utilization is ~70-85%. A third core is
possible if Ethernet traffic is bursty (typical). Four cores would need
careful bandwidth management.

**DDR3 Subsystem** (MT41K128M16JT-125, 16-bit, DDR3-1333):

| Consumer | Bandwidth | Notes |
|---|---|---|
| 4 heavy JOP cores | ~200-400 MB/s | ~50-100 MB/s per core |
| VGA DMA (optional) | ~50-150 MB/s | 640x480: 50, 1080p@30: 150 |
| **Total** | ~250-550 MB/s | Well within ~1,600 MB/s effective |

DDR3 utilization with 4 heavy cores + VGA DMA is ~15-35%. Substantial
headroom for memory-intensive workloads or more cores.

### FPGA Resource Budget

XC7A100T-FGG676: 63,400 LUTs, 135,680 FFs, 270 BRAMs (36Kb), 240 DSP48E1.

| Component | LUTs | BRAMs | DSP48 | Notes |
|---|---|---|---|---|
| **SDR Subsystem** | | | | |
| SDR memory controller | ~500 | 0 | 0 | |
| 2x light JOP cores | ~4,000 | ~12 | 0 | Integer-only |
| Ethernet MAC + buffers | ~2,000 | ~4 | 0 | |
| SD card controller | ~500 | ~1 | 0 | |
| UART + VGA text | ~1,000 | ~4 | 0 | Including font ROM |
| BMB arbiter + I/O | ~500 | 0 | 0 | |
| **SDR subtotal** | **~8,500** | **~21** | **0** | |
| **DDR3 Subsystem** | | | | |
| MIG controller | ~5,000 | ~5 | 0 | Xilinx IP |
| LruCacheCore L2 | ~2,000 | ~8 | 0 | 16KB 4-way |
| 2x heavy JOP cores | ~20,000-30,000 | ~30-40 | ~8-16 | Full compute |
| VGA DMA (optional) | ~1,000 | ~2 | 0 | |
| BMB arbiter + I/O | ~500 | 0 | 0 | |
| **DDR3 subtotal** | **~28,500-38,500** | **~45-55** | **~8-16** | |
| **Shared** | | | | |
| Message FIFOs (2x) | ~200 | 0 | 0 | Distributed RAM |
| Watchdog FSM | ~50 | 0 | 0 | |
| PLLs (3x) | 0 | 0 | 0 | MMCM primitives |
| **Shared subtotal** | **~250** | **0** | **0** | |
| | | | | |
| **Total** | **~37,000-47,000** | **~66-76** | **~8-16** | |
| **Available** | 63,400 | 270 | 240 | |
| **Utilization** | 58-74% | 24-28% | 3-7% | |

Comfortable fit. Room for additional cores or larger compute units.

## Software Model

### Message Queue I/O Registers

Each subsystem sees the message queue as memory-mapped I/O registers:

| Offset | Register | R/W | Description |
|---|---|---|---|
| +0 | `MSG_STATUS` | R | Bit 0: TX FIFO full, Bit 1: RX FIFO empty |
| +1 | `MSG_TX` | W | Push one word to TX FIFO (SDR→DDR3 or DDR3→SDR) |
| +2 | `MSG_RX` | R | Pop one word from RX FIFO |

```java
public class MsgQueue {
    public static final int IO_MSG = IO_BASE + 0x50;  // example offset

    public static boolean canSend() {
        return (Native.rd(IO_MSG) & 1) == 0;  // TX not full
    }

    public static boolean hasMessage() {
        return (Native.rd(IO_MSG) & 2) == 0;  // RX not empty
    }

    public static void send(int word) {
        Native.wr(IO_MSG + 1, word);
    }

    public static int receive() {
        return Native.rd(IO_MSG + 2);
    }
}
```

### Work Offload Pattern

The I/O subsystem receives external data (Ethernet packet, SD block, sensor
reading) and offloads processing to the compute subsystem:

```
SDR core                          DDR3 core
   │                                  │
   │  receive Ethernet packet         │
   │  extract payload                 │
   │  send(CMD_PROCESS | len)    ──►  │  receive command
   │  send(word0)                ──►  │  receive data
   │  send(word1)                ──►  │  receive data
   │  ...                             │  process...
   │                                  │  send(RSP_RESULT | len)
   │  receive response           ◄──  │  send(result0)
   │  send reply over Ethernet        │
   │                                  │
```

The message protocol is application-defined. The hardware provides raw
word-level FIFO transport.

### Boot Sequence

Both subsystems boot independently from their respective memories:

1. FPGA configuration loads bitstream from SPI flash
2. Watchdog FSM releases both resets
3. **SDR subsystem** boots from embedded BRAM (or config flash loader),
   initializes I/O peripherals
4. **DDR3 subsystem** waits for MIG calibration, then boots from BRAM
5. Both subsystems begin toggling heartbeat
6. SDR subsystem can optionally load DDR3 application code via message
   queue (enables updating compute firmware over Ethernet/SD)

Alternative: both subsystems have their own config flash loader and boot
independently from SPI flash partitions.

## Possible Implementation Phases

These phases are a suggested order of incremental development. Each phase
is independently useful and validates a subset of the design.

### Phase 1: Single-Subsystem DDR3

Get one JOP cluster running on DDR3 via MIG on the Wukong board. This
validates the DDR3 memory path, MIG integration, and pin assignments.

- Generate MIG IP for Wukong DDR3 pins
- Adapt `JopDdr3Top` for Wukong (clock, pins, reset)
- Verify basic operation (UART echo, LED blink)

### Phase 2: Dual Memory Controllers

Run both SDR SDRAM and DDR3 controllers simultaneously. Two independent
JOP clusters, no inter-connection yet.

- Instantiate both memory controllers in one top-level
- Separate clock domains (80 MHz SDR, 100 MHz DDR3)
- Independent UART for each subsystem (for debug)
- Verify both clusters run concurrently

### Phase 3: Message Queues

Add `StreamFifoCC` pair between subsystems. Implement `BmbMsgQueue`
peripheral (BMB slave wrapping the FIFO interfaces).

- Hardware: CDC FIFOs + BMB slave wrapper
- Software: `MsgQueue` Java class
- Test: echo messages between subsystems

### Phase 4: Watchdog FSM

Add independent watchdog. Heartbeat I/O register in each subsystem.

- Pure RTL module, 50 MHz oscillator clock
- Synchronizers for heartbeat inputs
- Timeout + reset logic
- LED status indicators
- Test: verify reset on heartbeat timeout

### Phase 5: Heterogeneous Cores

Configure light cores (SDR) and heavy cores (DDR3) with different
`JopCoreConfig` / compute units. Move I/O peripherals to SDR cluster,
compute peripherals to DDR3 cluster.

- SDR: integer-only microcode, Ethernet + SD + UART
- DDR3: full compute units, VGA DMA
- Work offload demo application

## Open Questions

- **Core count per subsystem**: 1-4 each shown here, but the right split
  depends on target applications. A single I/O core may suffice for many
  use cases.
- **Message queue depth**: 16 vs 64 entries — depends on message size and
  latency tolerance. Larger FIFOs buffer more but cost distributed RAM.
- **Shared BRAM vs FIFO-only**: For bulk data (e.g. Ethernet frames), a
  shared dual-port BRAM may be more efficient than streaming through FIFOs.
  Trade-off is added complexity and potential coherence issues.
- **Boot coordination**: Who boots first? Does the SDR subsystem load DDR3
  firmware, or do both boot independently from flash?
- **Interrupt vs polling**: Message arrival could trigger an interrupt instead
  of polling. Adds hardware complexity but reduces latency.
- **Watchdog policy**: Should failure of one subsystem reset the other?
  Application-dependent — safety-critical vs fault-tolerant designs differ.

## Future Extensions

- **DVI-D / HDMI output**: Drive HDMI connector from DDR3 subsystem via
  OSERDESE2 TMDS serializers. VGA DMA framebuffer feeds pixel data.
  640x480@60 or 1080p@30 (74.25 MHz pixel clock).

- **Remote firmware update**: SDR I/O core receives new DDR3 firmware
  over Ethernet, loads via message queue, then triggers DDR3 reset.

- **Hardware mailbox**: Extend message queue with interrupt support
  (IRQ on message arrival) to replace polling.

- **Shared memory region**: Small dual-port BRAM accessible from both
  subsystems for bulk data transfer (bypassing FIFO for large payloads).

- **Triple modular redundancy**: Three light cores on SDR with voting
  for safety-critical I/O control.
