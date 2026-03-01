# JOP Networking — TCP/IP Stack

Comprehensive documentation for the JOP networking module: a poll-based TCP/IP
stack running on the QMTECH EP4CGX150 + DB_FPGA daughter board with RTL8211EG
Gigabit Ethernet PHY.

## Status

| Feature | Status | Notes |
|---------|--------|-------|
| Link layer (EthDriver) | Working | 1Gbps GMII, MDIO PHY management |
| ARP | Working | 8-entry cache, snooping, gratuitous ARP |
| IPv4 | Working | No fragmentation (MTU 1500 sufficient) |
| ICMP echo (ping) | Working | ~2.5% loss at 1Gbps (timing margin) |
| UDP | Working | Echo tested on port 7 |
| TCP | Working | Echo verified on port 7 (5/5 connections, 1KB payload) |
| java.net API | Written | Socket, ServerSocket, DatagramSocket stubs |

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│  Application (NetTest.java)                                      │
│    ├── UDP echo server (port 7)                                  │
│    ├── TCP echo server (port 7)                                  │
│    └── Main loop: toggleWd() → NetLoop.poll() → process I/O     │
├──────────────────────────────────────────────────────────────────┤
│  java.net API                                                    │
│    Socket, ServerSocket, DatagramSocket, DatagramPacket,         │
│    InetAddress, SocketException                                  │
├──────────────────────────────────────────────────────────────────┤
│  Transport Layer                                                 │
│    ├── TCP  (772 lines, RFC 793 state machine, all 11 states)   │
│    ├── UDP  (119 lines, stateless send/receive)                 │
│    └── ICMP (140 lines, echo reply)                             │
├──────────────────────────────────────────────────────────────────┤
│  Network Layer                                                   │
│    ├── IP   (182 lines, header/checksum/dispatch)               │
│    └── Arp  (265 lines, cache + snooping + request/reply)       │
├──────────────────────────────────────────────────────────────────┤
│  Link Layer                                                      │
│    ├── EthDriver (288 lines, MAC + PHY MDIO)                   │
│    ├── Packet    (135 lines, int[] buffer with byte accessors)  │
│    └── EthMac HW object (volatile I/O registers)                │
├──────────────────────────────────────────────────────────────────┤
│  SpinalHDL RTL                                                   │
│    ├── BmbEth       (I/O device wrapping MacEth)                │
│    ├── MacEth       (preamble → CRC → aligner → buffer CDC)    │
│    └── MacEthNoCrc  (variant without RX CRC, for testing)       │
├──────────────────────────────────────────────────────────────────┤
│  FPGA Hardware                                                   │
│    ├── pll_125.v    (50 MHz → 125 MHz TX clock)                 │
│    ├── FAST_INPUT_REGISTER on e_rxd/e_rxdv/e_rxer              │
│    └── RTL8211EG PHY (1000BASE-T, GMII, autoneg)               │
└──────────────────────────────────────────────────────────────────┘
```

## Source Files

### Protocol Stack (`java/net/src/com/jopdesign/net/`)

| File | Lines | Purpose |
|------|-------|---------|
| `NetConfig.java` | 80 | Static IP/MAC/gateway config, pool sizes, timeouts |
| `EthDriver.java` | 288 | Link layer: PHY init (MDIO), frame send/receive, MAC address handling |
| `Packet.java` | 135 | Frame buffer pool: `int[384]` with little-endian byte accessors |
| `Arp.java` | 265 | ARP cache (8 entries), request/reply, snooping, gratuitous ARP |
| `IP.java` | 182 | IPv4 send/receive, header checksum, protocol dispatch |
| `ICMP.java` | 140 | Echo reply (ping), destination unreachable, diagnostic counters |
| `UDP.java` | 119 | UDP send/receive with pseudo-header checksum |
| `UDPConnection.java` | 167 | UDP connection pool (4 slots), single-datagram receive buffer |
| `TCP.java` | 772 | TCP state machine (all 11 RFC 793 states), segment send/receive |
| `TCPConnection.java` | 374 | TCP connection pool (4 slots), retransmit tracking, state management |
| `TCPInputStream.java` | 105 | Circular byte buffer for TCP receive (4 KB) |
| `TCPOutputStream.java` | 134 | Circular byte buffer for TCP send with 3-pointer retransmit (4 KB) |
| `NetLoop.java` | 108 | Main poll loop: receive dispatch + round-robin TCP polling |
| `NumFunctions.java` | 87 | TCP sequence arithmetic with 32-bit wraparound |

### java.net API (`java/net/src/java/net/`)

| File | Lines | Purpose |
|------|-------|---------|
| `InetAddress.java` | 80 | IP address wrapper, dotted-decimal parser (no DNS) |
| `DatagramSocket.java` | 88 | UDP socket (non-blocking send/receive) |
| `DatagramPacket.java` | 58 | UDP datagram buffer holder |
| `Socket.java` | 117 | TCP client socket (non-blocking) |
| `ServerSocket.java` | 83 | TCP server socket (non-blocking accept, auto-relisten) |
| `SocketException.java` | 11 | Exception class |

### Hardware (`spinalhdl/src/main/scala/jop/io/`)

| File | Purpose |
|------|---------|
| `BmbEth.scala` | I/O device wrapping SpinalHDL MacEth (TX/RX streams, control registers) |
| `MacEthNoCrc.scala` | MacEth variant without RX CRC checker (for testing/mesochronous) |
| `BmbMdio.scala` | MDIO master for PHY register access |

### FPGA (`fpga/qmtech-ep4cgx150-sdram/`)

| File | Purpose |
|------|---------|
| `pll_125.v` | Altera altpll: 50 MHz → 125 MHz (GMII TX clock) |
| `jop_sdram.sdc` | Timing constraints: clock groups, false paths for Ethernet |
| `jop_dbfpga.qsf` | Pin assignments + FAST_INPUT_REGISTER for RX pins |

### Test Application

| File | Purpose |
|------|---------|
| `java/apps/Small/src/test/NetTest.java` | UDP+TCP echo on port 7, ICMP ping, diagnostics |

## Default Configuration

```
IP address:    192.168.0.123
Subnet mask:   255.255.255.0
Gateway:       192.168.0.1
MAC address:   02:00:00:00:00:01
```

Configured in `NetConfig.java`. All addresses stored in network byte order
(big-endian) as 32-bit ints.

## Memory Layout

### Static Allocation (no GC during operation)

| Pool | Count | Size Each | Total |
|------|-------|-----------|-------|
| Packet buffers | 8 | 1536 B (384 words) | 12 KB |
| TCP RX streams | 4 | 4 KB | 16 KB |
| TCP TX streams | 4 | 4 KB | 16 KB |
| UDP RX buffers | 4 | 1472 B | ~6 KB |
| **Total** | | | **~50 KB** |

**JOP memory note**: JOP byte arrays use 1 word (4 bytes) per byte element.
`new byte[1472]` actually allocates ~5.9 KB. The heap fallback in
`Startup.java` was increased to 1 MB (`appEnd + 262144` words) to accommodate
the networking stack.

## Hardware Interface

### BmbEth Register Map

The EthMac hardware object (`com.jopdesign.hw.EthMac`) maps to I/O address
`Const.IO_ETH`. Five 32-bit registers:

| Offset | Read | Write |
|--------|------|-------|
| +0 | Status: bit 0=TX flush, 1=TX ready, 4=RX flush, 5=RX valid | Control: bit 0=TX flush, 4=RX flush |
| +1 | TX availability (free words in TX FIFO) | — |
| +2 | — | TX data push |
| +3 | RX data pop (auto-pop) | — |
| +4 | RX stats: errors[7:0], drops[15:8] (auto-clear) | — |

### TX Protocol

1. Wait for TX ready (status bit 1)
2. Write frame length in **bits** to offset +2
3. Write `ceil(length/32)` data words to offset +2
4. MacTxBuffer auto-commits after the correct word count

### RX Protocol

1. Check RX valid (status bit 5)
2. Read offset +3 → frame length in bits
3. Read offset +3 repeatedly → data words (auto-pop each read)

### Frame Format

Data is **little-endian** within 32-bit words:

```
Word 0: byte[0] in bits [7:0], byte[1] in [15:8], byte[2] in [23:16], byte[3] in [31:24]
Word 1: byte[4] in bits [7:0], ...
```

The `Packet` class provides `getByte(offset)`, `getShort(offset)`,
`getInt(offset)` that handle the byte-to-word mapping and convert to
network byte order (big-endian) for protocol fields.

## SpinalHDL MAC Pipeline

### RX Pipeline (PHY clock domain → system clock domain)

```
PHY pins → [I/O block registers] → rxArea single-stage pipeline
    → MacRxPreamble (detect SFD 0xD5)
    → MacRxChecker  (CRC-32, residue = 0x2144DF1C)
    → MacRxAligner  (pad to 16-bit boundary)
    → MacRxBuffer   (dual-clock FIFO, CDC to system clock)
    → CPU reads via BmbEth
```

- CRC-32: IEEE 802.3, polynomial 0x04C11DB7, reflected I/O, init 0xFFFFFFFF
- Frames with CRC errors are flagged; MacRxBuffer discards them
- Stats counters (errors, drops) crossed to system clock via PulseCCByToggle

### TX Pipeline (system clock domain → PHY clock domain)

```
CPU writes via BmbEth
    → MacTxBuffer  (dual-clock FIFO, CDC to PHY clock)
    → MacTxAligner (absorb padding)
    → MacTxPadder  (enforce 60-byte minimum)
    → MacTxCrc     (append 4-byte FCS)
    → MacTxHeader  (prepend 8-byte preamble + SFD)
    → MacTxInterFrame (inter-frame gap)
    → txArea registered outputs → PHY pins
```

## GMII Clocking

Three asynchronous clock domains:

| Clock | Source | Frequency | Use |
|-------|--------|-----------|-----|
| System (pll clk[1]) | DRAM PLL | 80 MHz | CPU, memory, I/O |
| TX (ethPll clk[0]) | pll_125.v | 125 MHz | MacTxBuffer pop, PHY TX, drives e_gtxc |
| RX (e_rxc) | PHY pin B10 | 125 MHz | MacRxBuffer push, source-synchronous capture |

### RX Clocking Solution

The PHY's `e_rxc` is a CDR-recovered clock — it is **not** phase-locked to
the FPGA oscillator. Source-synchronous capture with I/O block registers is
the correct approach.

**Key constraint** in `jop_dbfpga.qsf`:
```
set_instance_assignment -name FAST_INPUT_REGISTER ON -to e_rxd[0]
... (all 8 data bits, e_rxdv, e_rxer)
```

This places the first register stage in the I/O block, providing ~6ns setup
margin at 125 MHz (8ns period minus ~2ns PHY output delay).

**Tested approaches** (200-packet ICMP ping at 1Gbps):

| Approach | ICMP Loss | Notes |
|----------|-----------|-------|
| PLL mesochronous 0° | 24% | Random phase vs CDR clock |
| PLL mesochronous 90° | 14% | Best mesochronous result |
| PLL mesochronous 180° | 34% | Worst mesochronous result |
| CRC disabled (mesochronous) | 30% | Proved data genuinely corrupted |
| **Source-sync rising + FAST_INPUT_REGISTER** | **2.5%** | **Current configuration** |
| Source-sync falling + FAST_INPUT_REGISTER | 16% | Wrong edge for data eye |

**Note**: `e_rxc` is on PIN_B10 (Column I/O), which cannot reach the FPGA's
global clock network. It uses the bank-local clock routing instead. The
residual ~2.5% CRC error rate is from timing margin limits on this routing.
TCP retransmission handles this easily.

### MacEthNoCrc

`MacEthNoCrc.scala` is a copy of MacEth with the `MacRxChecker` stage removed.
Created during debugging to test whether CRC errors were from real data
corruption or a checker bug. Result: disabling CRC gave 30% loss (same as
mesochronous), proving the data was genuinely corrupted. Upper-layer checksums
(IP/ICMP) caught the same errors. This file exists but is **not used** in the
current configuration (BmbEth uses standard `MacEth`).

## Protocol Details

### Packet Buffer

`Packet.java` provides a pool of 8 statically allocated `int[384]` buffers
(1536 bytes each, max Ethernet frame). Byte accessors handle the little-endian
word mapping:

```java
// byte offset → word index and bit position
int wordIndex = off >>> 2;
int byteInWord = off & 3;
int shift = byteInWord << 3;
// getByte: (buf[wordIndex] >>> shift) & 0xFF
// setByte: clear bits, OR in new value
```

Network byte order (big-endian) conversion for multi-byte fields:
```java
// getShort(off): getByte(off) << 8 | getByte(off+1)
// getInt(off):   getByte(off) << 24 | getByte(off+1) << 16 | ...
```

### ARP

- 8-entry cache: `ip[]`, `macHi[]`, `macLo[]`, `age[]`
- `resolve(pkt, ip)`: lookup cache → hit: set dst MAC, return true;
  miss: send ARP request, return false (caller retries next poll)
- **Snooping**: `IP.receive()` calls `Arp.learnFrom()` for every IP frame,
  populating cache entries from observed traffic without explicit requests
- **Gratuitous ARP**: Broadcast at startup and periodically to announce
  presence and pre-populate peer caches
- Gateway routing: `NetConfig.nextHop(ip)` returns gateway IP for off-subnet
  destinations; ARP resolves gateway MAC

### IP

- Header: version=4, IHL=5 (no options), don't-fragment flag always set
- TTL: 64 (NetConfig.IP_TTL)
- Checksum: one's complement sum of 16-bit header words, carry folding
- Protocol dispatch: 1=ICMP, 6=TCP, 17=UDP
- Pseudo-header checksum helper for TCP/UDP (src IP + dst IP + protocol + length)

### ICMP

- Echo reply: receives type 8 (echo request), responds with type 0 (echo reply)
- Swaps source/destination addresses, recalculates checksum
- Sends ICMP destination unreachable (type 3, code 3 = port unreachable) when
  UDP receives a datagram for an unbound port
- Diagnostic counters: `pingRxCount`, `pingSentCount`, `pingFailCount`

### UDP

- Checksum includes pseudo-header (IP src, dst, protocol, length)
- Checksum value of 0 means "no checksum" (allowed by RFC 768)
- Delivery: find `UDPConnection` by destination port; if no match, send ICMP
  port unreachable
- `UDPConnection`: single-datagram receive buffer (latest-wins, no queuing)

### TCP

Ported from jtcpip (original JOP project) with bug fixes:

| Bug | Fix |
|-----|-----|
| `getChecksum()` returned length not checksum | Use `& 0xFFFF` on correct half-word |
| `newLocalPort()` recursive | Iterative loop (100 attempts) |
| Passive open dangling semicolon | Rewritten in ServerSocket |
| `wait()`/`notify()` usage | Replaced with polling |

**State machine**: All 11 RFC 793 states:
```
CLOSED → LISTEN → SYN_RCVD → ESTABLISHED → CLOSE_WAIT → LAST_ACK → CLOSED
CLOSED → SYN_SENT → ESTABLISHED → FIN_WAIT_1 → FIN_WAIT_2 → TIME_WAIT → CLOSED
                                  FIN_WAIT_1 → CLOSING → TIME_WAIT → CLOSED
```

**Key parameters**:
- MSS: 1460 bytes (default), negotiated via SYN option
- Window: 4096 bytes (TCPConnection.RCV_BUF_SIZE)
- Retransmit timeout: 1000ms (×3 for SYN_SENT)
- Max retransmissions: 8 (or 60 seconds total)
- TIME_WAIT: 2 seconds
- ISS: incrementing counter for initial sequence number

**TCPOutputStream 3-pointer design**:
```
ackWaitPtr ──────── readPtr ──────── writePtr
  │                    │                  │
  │  Unacked data      │  Unsent data    │  Free space
  │  (retransmit)      │  (next segment) │  (user writes)
```
- `ackWaitPtr`: oldest unacknowledged byte
- `readPtr`: next byte to include in outgoing segment
- `writePtr`: next free position for user writes
- Retransmit: reset `readPtr = ackWaitPtr` to re-send unacknowledged data

### NetLoop

Single entry point for all network processing:

```java
NetLoop.poll()
  ├── Try receive frame from EthDriver
  │   ├── If ARP: Arp.process()
  │   └── If IP:  IP.receive()
  │               ├── ICMP: echo reply
  │               ├── UDP:  UDPConnection dispatch
  │               └── TCP:  state machine
  ├── Poll one TCP connection (round-robin)
  │   └── Check retransmit, send data, handle timeouts
  └── Arp.tick() (pending request timeout)
```

Called from the application main loop. **Must be called frequently** — TCP
retransmit timers and ARP timeouts depend on it.

## Build and Test

### Build

```bash
# Build networking test app
cd java/apps/Small
make clean && make all APP_NAME=NetTest EXTRA_SRC=../../net/src

# Generate Verilog (if RTL changed)
cd /home/peter/jop-spinalhdl
sbt "runMain jop.system.JopDbFpgaTopVerilog"

# Build FPGA
cd fpga/qmtech-ep4cgx150-sdram
make build-dbfpga

# Program and download
make program-dbfpga
make download SERIAL_PORT=/dev/ttyUSB0 JOP_FILE=../../java/apps/Small/NetTest.jop
```

### Test from Host

```bash
# Ping (ICMP echo)
ping 192.168.0.123

# UDP echo (port 7)
echo "hello" | nc -u -w1 192.168.0.123 7

# TCP echo (port 7)
echo "hello" | nc -w1 192.168.0.123 7
```

### UART Diagnostics

NetTest prints periodic status lines:
```
Rx=195 Tx=195 Fl=0 Er=12 Dr=7
```

| Field | Meaning |
|-------|---------|
| Rx | ICMP echo requests received (passed CRC + IP + ICMP checksum) |
| Tx | ICMP echo replies sent |
| Fl | ICMP send failures (packet alloc fail) |
| Er | MAC RX CRC errors (accumulated, 8-bit counter wraps) |
| Dr | MAC RX drops (buffer overflow, 8-bit counter wraps) |

## JOP-Specific Workarounds

These apply to all Java code in the networking module:

1. **No StringBuilder** — `System.arraycopy` is broken on JOP, crashes
   StringBuilder resize. Use `char[]` with two-pass construction or
   manual character output.

2. **No string + int** — `"text" + n` compiles to `StringBuilder.append(int)`
   which calls `Integer.toString()` and crashes. Use `wrInt()` helper.

3. **Poll-based only** — JOP has no `wait()`/`notify()`. All socket operations
   return immediately (-1 if no data). Application must call `NetLoop.poll()`
   in its main loop.

4. **Static allocation** — All pools (Packet, UDPConnection, TCPConnection)
   pre-allocated at init. No GC pressure during operation.

5. **int[] for packet buffers** — JOP byte arrays use 1 word per byte.
   `int[]` with manual packing is 4× more memory-efficient.

6. **Heap size** — `Startup.java` heap fallback increased to 1 MB (262144
   words) to accommodate the networking stack's buffers.

## Provenance

The TCP/IP stack was ported from **jtcpip**, the TCP/IP implementation from
the original JOP project (`/srv/git/jop.original/java/target/src/common/ejip/jtcpip/`).
jtcpip was a ~31-file stack using the J2ME `javax.microedition.io` API,
tightly coupled to the CS8900 ISA bus NIC via the **ejip** embedded IP library.

The port:
- Replaced ejip/CS8900 link layer with our BmbEth MAC driver
- Replaced J2ME API with standard `java.net` wrappers
- Fixed bugs (UDP checksum, recursive port allocation, dangling semicolons)
- Removed `wait()`/`notify()` (unsupported on JOP)
- Adapted to JOP workarounds (no StringBuilder, static allocation)
- Reduced from ~31 files to 20 files (merged some, removed unused)

## Progress Log

### 2026-02-28: Ethernet RX clocking fix

**Problem**: GMII RX at 1Gbps had 14-34% packet loss depending on PLL phase.

**Root cause**: The initial implementation used mesochronous clocking — an FPGA
PLL generated a 125 MHz clock (same frequency as the PHY's `e_rxc`) to capture
RX data. But `e_rxc` is a CDR-recovered clock with no phase relationship to
the FPGA oscillator. The random phase offset caused intermittent setup/hold
violations, corrupting data and failing CRC-32 checks.

**Investigation steps**:
1. Verified pin assignments match reference design (identical)
2. Verified I/O standard (3.3V LVCMOS, same as reference)
3. Studied SpinalHDL MacEth RX pipeline (preamble → CRC → aligner → buffer)
4. Checked Quartus fitter report: `e_rxc` (PIN_B10) is Column I/O, NOT a
   dedicated clock input — cannot reach global clock network
5. Tested PLL phase shifts: 0° (24% loss), 90° (14%), 180° (34%)
6. Created MacEthNoCrc (disabled CRC checking): 30% loss — proved data was
   genuinely corrupted, not a CRC checker bug
7. Switched to source-synchronous `e_rxc` with `FAST_INPUT_REGISTER ON`
   constraints for I/O block register placement

**Result**: 2.5% ICMP loss (down from 14-34%). Rising edge best; falling edge
gave 16% loss. The residual 2.5% is from Column I/O clock routing limitations —
acceptable for TCP retransmission.

**Files changed**:
- `JopSdramTop.scala` — EthPll simplified (removed unused c1), RX clock uses
  source-synchronous `e_rxc`
- `pll_125.v` — Simplified to single c0 output
- `jop_sdram.sdc` — Updated comments and clock groups
- `jop_dbfpga.qsf` — Added FAST_INPUT_REGISTER for all 10 Ethernet RX pins
- `MacEthNoCrc.scala` — Created for debugging (not used in production)

### 2026-03-01: TCP echo verified — two bugs fixed

**Problem**: TCP connections established (3-way handshake OK) but data was never
echoed back. ICMP ping and UDP echo both worked fine.

**Bug 1 — Duplicate SYN-ACK with wrong sequence number**: `TCPConnection.poll()`
called `TCP.sendSegment()` on every round-robin poll while in SYN_RCVD state
with `synToSend=true`. Each call re-sent a SYN-ACK and **advanced `sndNext`**,
corrupting all subsequent sequence numbers. The remote host received the echo
data at an unexpected sequence number and refused to ACK it.

*Diagnosis*: tcpdump showed two SYN-ACKs — the second with seq=ISN+1 instead of
ISN. Then the data packet had seq=ISN+1 but the client ACKed with `ack 0`
(relative), indicating it didn't accept the data.

*Fix*: Added guard in `poll()`: in SYN_RCVD/SYN_SENT states, return early if
`sndNext != sndUnack` (SYN already sent, waiting for ACK). Only proceed to
`sendSegment()` when retransmission resets `sndNext` to `sndUnack`.

**Bug 2 — No data sent in CLOSE_WAIT**: `TCP.sendSegment()` only attached data
when `state == STATE_ESTABLISHED`. After receiving the remote's FIN (transition
to CLOSE_WAIT), echo data written to `oStream` was never read into outgoing
segments. The condition now includes `STATE_CLOSE_WAIT` (RFC 793 permits
sending in CLOSE_WAIT).

**Verification**: 5/5 sequential TCP echo connections succeeded. 1001-byte
payload echoed correctly. tcpdump confirms clean sequence numbers — no duplicate
SYN-ACKs, proper ACKs, correct data+FIN teardown.

**Files changed**:
- `TCP.java` — Line 577: data attachment includes CLOSE_WAIT
- `TCPConnection.java` — `poll()`: guard against re-sending in SYN_RCVD/SYN_SENT

### 2026-02-27: Networking module written

Wrote 20 Java source files implementing the full TCP/IP stack:
- Link layer: EthDriver with PHY init, MDIO, frame send/receive
- Network layer: ARP with snooping, IP with checksum
- Transport layer: ICMP echo, UDP with connections, TCP state machine
- API layer: java.net Socket, ServerSocket, DatagramSocket
- Test app: NetTest with UDP+TCP echo on port 7

Ported from jtcpip with bug fixes (UDP checksum, recursive newLocalPort,
wait/notify removal).
