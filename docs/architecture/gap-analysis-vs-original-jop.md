# Gap Analysis: jop-spinalhdl vs Original JOP

Comparison of the SpinalHDL reimplementation against the original JOP
(VHDL) at `/srv/git/jop.original`. Identifies features present in the
original that are missing or incomplete in jop-spinalhdl.

## Summary

| Category | Original | SpinalHDL | Gap |
|----------|----------|-----------|-----|
| CPU/Microcode | Complete | Complete | Minimal |
| I/O Peripherals | ~15 types | 10 types | Medium |
| Memory Controllers | SRAM/SDRAM/scratchpad | BRAM/SDR SDRAM/DDR3 | Low |
| CMP | 5 arbiters + RTTM + NoC | RR arbiter + CmpSync + IHLU | Medium |
| Java Runtime | RTSJ + SCJ + JDK11/16 | Core + JDK base | Large |
| Networking | ejip + jtcpip + NFS | Custom TCP/IP + java.net | Low |
| Tools | WCET + JCopter + DFA | Debug subsystem + sim | Medium |
| Boards | 50+ targets | 5 targets | N/A (different HW) |
| Applications | Industrial + benchmarks | Tests + demos | Large |
| Debug | JopSim + UDPDbg | HW debug controller | **Ahead** |

## Features Where SpinalHDL Is Ahead

These exist in jop-spinalhdl but NOT in the original:

- **Hardware debug subsystem** — halt/resume, single-step, 8 HW
  breakpoints per core, register/memory read, UART debug transport
  (`DebugController.scala`, `DebugBreakpoints.scala`)
- **DDR3 support** — 4-way LRU write-back cache + Xilinx MIG integration
- **Gigabit Ethernet** — GMII (1 Gbps) MAC, not just 100 Mbps MII
- **SD Native 4-bit mode** — original only had SPI-mode SD via MMC
- **FAT32 with LFN** — full read-write FAT32 (original had FAT16/32
  without LFN)
- **Cross-core cache snoop invalidation** — object/array cache coherence
  for SMP
- **IHLU** — per-object hardware locking unit (original only had global
  lock + RTTM)
- **3-bank rotating stack cache with DMA** — burst spill/fill
- **DNS resolver** and **DHCP client** in the TCP/IP stack
- **java.net Socket API** (Socket, ServerSocket, DatagramSocket)
- **HTTP file server** serving from FAT32 SD card
- **Flash boot on Artix-7** with STARTUPE2 and SST26 flash support
- **UART flash programmer** with batched I/O (~2 min for 2.3 MB)

## Gaps by Category

### 1. I/O Peripherals

| Peripheral | Original | SpinalHDL | Priority |
|------------|----------|-----------|----------|
| SPI master (general purpose) | `sc_spi.vhd` | Missing | High |
| I2C controller | `sc_i2c.vhd` | Missing | High |
| Hardware FPU | `fpu/` (OpenCores FPU100) | Software only | Medium |
| PS/2 keyboard | `kbd_cntrl/` | Missing | Low |
| PS/2 mouse | `mouse_cntrl/` | Missing | Low |
| USB (FTDI parallel) | `sc_usb.vhd` | Missing | Low |
| Sigma-delta ADC/DAC | `sc_sigdel.vhd` | Missing | Low |
| AC97 audio | `ext/ac97/` | Missing | Low |
| MAC (DSP multiply-accumulate) | `sc_mac.vhd` | Missing | Low |
| GPIO (LED/switch) | `led_switch.vhd` | Missing | Low |
| Wishbone bridge | `sc2wb.vhd` | Missing | Low |
| LEGO Mindstorms | `sc_lego.vhd` + drivers | Missing | N/A |

**Notes:**
- General-purpose SPI and I2C are the most impactful gaps — they enable
  a wide range of sensors, displays, and expansion devices.
- Hardware FPU would eliminate the SoftFloat overhead (~100x speedup for
  float ops). The microcode already has `#ifdef FPU_ATTACHED` hooks.
- PS/2, USB, sigma-delta, AC97, LEGO are board-specific and only needed
  for those particular hardware configurations.

### 2. CMP / Multi-Processor

| Feature | Original | SpinalHDL | Priority |
|---------|----------|-----------|----------|
| TDMA memory arbiter | `sc_arbiter_tdma.vhd` | Missing | High |
| Fixed-priority arbiter | `sc_arbiter_fixedpr.vhd` | Missing | Medium |
| Fair arbiter | `sc_arbiter_fair.vhd` | Missing | Low |
| RTTM (transactional memory) | `vhdl/rttm/` | Missing | Low |
| Network-on-Chip | `vhdl/noc/` | Missing | Low |
| Control channel | `sc_control_channel.vhd` | Missing | Low |

**Notes:**
- **TDMA arbiter is the critical gap** for real-time CMP. It provides
  deterministic, WCET-analyzable memory access times. Without it,
  jop-spinalhdl's round-robin arbiter gives good average-case but
  unpredictable worst-case latency.
- RTTM is research-grade (Martin Schoeberl's PhD work). Interesting but
  the IHLU in SpinalHDL provides a more practical solution for most use
  cases.
- NoC is needed only for large core counts (>4) where a shared bus
  becomes a bottleneck.

### 3. Java Runtime / API

| Feature | Original | SpinalHDL | Priority |
|---------|----------|-----------|----------|
| RTSJ (javax.realtime) | Full implementation | Missing | High |
| SCJ (javax.safetycritical) | Level 0 + Level 1 | Missing | High |
| JDK 1.1 extensions | java.util, java.io expanded | Missing | Medium |
| JDK 1.6 extensions | Additional compat classes | Missing | Low |
| CLDC 1.1 (J2ME) | Full CLDC API | Missing | Low |
| JOP UI framework | Canvas, Graphics, widgets | Missing | Low |
| YAFFS2 filesystem | NAND flash filesystem | Missing | Low |
| Scoped memory (Memory.java) | Region-based allocation | Partial | Medium |
| CORDIC trig functions | `Cordic.java` | Missing | Low |

**Notes:**
- **RTSJ and SCJ** are the most significant runtime gaps. The original
  JOP was designed as a real-time Java platform and had full RTSJ/SCJ
  implementations. These are needed for safety-critical and hard real-time
  applications.
- JDK 1.1 extensions (Vector, Hashtable, Random, Calendar, Date,
  DataInputStream/OutputStream, Reader/Writer) would be useful for
  general-purpose applications.
- CLDC and UI framework are niche — only relevant for specific embedded
  GUI or J2ME scenarios.

### 4. Tools

| Tool | Original | SpinalHDL | Priority |
|------|----------|-----------|----------|
| WCET analysis | Full IPET + UPPAAL tool | Missing | High |
| JCopter optimizer | Bytecode optimization | Missing | Medium |
| DFA framework | Abstract interpretation | Missing | Medium |
| Cache simulators | Object/data cache sim | Missing | Low |
| TMSim | RTTM simulator | Missing | Low |
| UDP debug interface | `UDPDbg.java` | Missing | Low |
| UDP/TFTP flash | Network-based flashing | Missing | Low |

**Notes:**
- **WCET analysis** is the biggest tool gap. It's essential for any
  hard real-time certification. The original tool used IPET (Implicit
  Path Enumeration Technique) with ILP solving and UPPAAL model checking.
- JCopter performed WCA-guided bytecode optimization (inlining, dead code
  elimination based on worst-case analysis). Useful but not critical.
- UDP-based tools are less relevant now that UART flash programming is
  fast (~2 min).

### 5. Networking

| Feature | Original | SpinalHDL | Status |
|---------|----------|-----------|--------|
| ARP | ejip + jtcpip | Custom stack | **Equivalent** |
| IPv4 | ejip + jtcpip | Custom stack | **Equivalent** |
| TCP | ejip + jtcpip | Custom stack | **Equivalent** |
| UDP | ejip + jtcpip | Custom stack | **Equivalent** |
| ICMP (ping) | jtcpip | Custom stack | **Equivalent** |
| DHCP | jtcpip | Custom stack | **Equivalent** |
| DNS | Not in original | Custom stack | **SpinalHDL ahead** |
| java.net sockets | Not in original | Custom stack | **SpinalHDL ahead** |
| HTTP server | ejip (simple) | FAT32-backed | **SpinalHDL ahead** |
| SLIP link layer | ejip | Missing | Low priority |
| PPP link layer | ejip | Missing | Low priority |
| TFTP client | ejip | Missing | Low priority |
| NFS client | ejip/nfs | Missing | Low priority |
| NFS server | ninjaFS | Missing | Low priority |
| SMTP client | ejip | Missing | Low priority |

**Notes:**
- The SpinalHDL networking stack is more complete and modern than the
  original for common use cases. It has DNS, DHCP, java.net sockets, and
  a file-serving HTTP server — none of which existed in the original.
- SLIP/PPP are legacy serial-line protocols, unlikely to be needed.
- NFS is interesting for development (load programs over network) but
  the SD card boot loader design supersedes this use case.

### 6. Memory

| Feature | Original | SpinalHDL | Priority |
|---------|----------|-----------|----------|
| SRAM controllers (8/16/32-bit) | 4 variants | Missing | Low |
| Synchronous SRAM | `sc_ssram32.vhd` | Missing | Low |
| Scratchpad RAM | Dedicated address range | Missing | Medium |
| SimpCon-to-Avalon bridge | `sc2avalon.vhd` | Missing | Low |
| SimpCon-to-AHB bridge | `sc2ahbsl.vhd` | Missing | Low |
| Direct-mapped data cache | `directmapped.vhd` | Missing | Low |

**Notes:**
- SRAM is less relevant today — modern FPGA boards use SDRAM or DDR.
- Scratchpad RAM is useful for real-time systems (predictable access
  time, no cache misses). The original used it at address 0x400000.
- Bus bridges (Avalon, AHB) would only be needed for SoC integration
  with other IP cores.

### 7. Applications and Benchmarks

| Category | Original | SpinalHDL | Priority |
|----------|----------|-----------|----------|
| Industrial apps (KFL, OeBB, TAL) | 3 apps | None | N/A |
| JBE benchmarks | Full suite | Missing | Medium |
| JemBench | Full suite | Missing | Medium |
| PapaBench (UAV) | Full app | Missing | Low |
| CDX (SCJ collision detection) | Full app | Missing | Low |
| SCJ TCK test suite | Full suite | Missing | Medium |

**Notes:**
- Industrial applications (KFL heating controller, OeBB railway, TAL
  lift control) are specific to their hardware and deployment contexts.
  Not directly portable but serve as reference architectures.
- **Benchmark suites** (JBE, JemBench) would be valuable for performance
  regression testing and comparison with the original. Should be ported.
- CDX and SCJ TCK depend on the SCJ runtime being implemented first.

## Recommended Priority Order

### High Priority (enables key use cases)

1. **TDMA memory arbiter** — required for WCET-analyzable CMP systems
2. **WCET analysis tool** — required for hard real-time certification
3. **RTSJ / SCJ runtime** — required for real-time Java applications
4. **General-purpose SPI master** — enables sensors, displays, expansion
5. **I2C controller** — enables sensors, EEPROMs, PMICs

### Medium Priority (significant value)

6. **Hardware FPU** — eliminates SoftFloat overhead for float-heavy apps
7. **JDK 1.1 extensions** — java.util collections, expanded java.io
8. **Scratchpad RAM** — predictable-latency memory for RT code
9. **JBE/JemBench** port — performance regression testing
10. **Fixed-priority arbiter** — alternative CMP scheduling policy

### Low Priority (niche or legacy)

11. SRAM controllers (legacy boards)
12. PS/2 keyboard/mouse (specific boards)
13. SLIP/PPP (legacy serial networking)
14. NFS client/server (SD card boot loader is better)
15. RTTM (research-grade, IHLU covers practical cases)
16. NoC (only needed for large core counts)
17. LEGO Mindstorms (specific hardware)
18. AC97 audio, sigma-delta ADC/DAC (specific boards)
19. CLDC 1.1, JOP UI framework (niche)
20. YAFFS2 (FAT32 on SD card is more practical)
