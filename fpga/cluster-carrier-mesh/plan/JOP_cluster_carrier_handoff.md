# JOP Cluster Carrier: Design Handoff (v2, 2026-11-05)

This is a four-module Colorlight i9+ carrier for a JOP cluster (`peteryates1/jop-spinalhdl`). It records what was decided about the hardware, the mesh link and the software. It is the starting point for a **test JOP implementation that must build cleanly in Vivado** against this pin plan.

Files in this folder:
- `xdc/carrier_U1.xdc` to `carrier_U4.xdc`: one constraints file per socket, generated from `mesh_pads.csv`.
- `mesh_pads.csv`: one row per socket pad, with pin, ball, bank, net, IN/OUT, port and lane. **This is the source of truth.**
- `mesh_nets.csv`: one row per net with both ends.
- `mesh_resistor_placement.csv`: placement for the 72 series resistors (designator, layer, X, Y, rotation).
- `pads.csv` and `pairs.csv`: every connector pad from pin 41 to 198, and every complete P/N pair, for reference.

---

## 1. Hardware

| Item | Decision |
|---|---|
| Module | Colorlight i9+ v6.1: XC7A50T-FGG484 (speed grade is on the chip; not yet checked), 8 MB SDR SDRAM, 16 MB SPI flash, 2× B50612D GbE PHY, 25 MHz oscillator on **K4** (bank 35), LED on **A18** |
| Pin map source | wuxx/Colorlight-FPGA-Projects i9plus table, joined with the prjxray `xc7a50tfgg484` package file. The table lists ball T4 at both pin 51 and pin 53, so **avoid 51 and 53** |
| Sockets | DDR2 SODIMM-200, all at x = 4 mm, rotation 180°. U1 top at y = 45, U2 top at y = −10, U3 bottom at y = 10, U4 bottom at y = −45 |
| Pad geometry | Pin n is at the same X on all four sockets. Lane pos = ceil(pin/2). X = 35.591 − 0.6·(pos−1) for pos ≤ 20, and 31.689 − 0.6·(pos−1) for pos ≥ 21 (a 3.9 mm key-notch gap after pin 40). Row Y: U1 odd 48.8 / even 41.2; U3 even 13.8 / odd 6.2; U2 odd −6.2 / even −13.8; U4 even −41.2 / odd −48.8 |
| Stackup | 4 layers: top, GND, GND, bottom. The top has the `1_5V` pour (5 V for U1/U2) and the bottom has `2_5V` (5 V for U3/U4). Mesh signals use the **outer layers only** |
| Other module nets (all sockets) | Ethernet ETH1 MDI on pins 15–35 (odd), to RJ1–RJ4. `ETH1_LED-L+` on pin 41, `ETH1_LED-R+` on pin 43. UART `TX` on pin 197 (H19) and `RX` on pin 198 (B17), to the RP2040 (U5, at x = −48, y = −4). JTAG on 203–206, to the RP2040 and pogo connectors CN1–CN4 |
| Module GND pads inside the I/O range | 55/56, 105–108 and 157/158 (lanes 28, 53/54, 79). Use these for stitching vias |
| Bank VCCO | **Not confirmed.** Assumed 3.3 V (LVCMOS33). To measure: build a bitstream with `PULLUP` on an unconnected input in each of banks 14, 15, 16, 34 and 35, then read each pad with a DMM. If a bank reads 2.5 V, change LVCMOS33 to LVCMOS25 in the XDC. Nothing else changes |

## 2. Cluster topology

- **Node IDs:** U1 = 0, U2 = 1, U3 = 2, U4 = 3.
- **Mesh port k (1–3)** connects node `i` to node `i XOR k`. The routing table is therefore trivial: to reach node `j`, use port `i XOR j`, which is always one hop away.

| Port | Links | Physical route |
|---|---|---|
| P1 (L1) | U1↔U2, U3↔U4 | U1↔U2 on the top layer, U3↔U4 on the bottom layer; no via |
| P2 (L2) | U1↔U3, U2↔U4 | top layer, via at y ≈ ±27, bottom layer |
| P3 (L3) | U1↔U4, U2↔U3 | U1↔U4: top layer to y ≈ 0, via, bottom layer. U2↔U3: via in the 12.4 mm gap |

- **Each module also has its own Ethernet port to an external switch.** The software uses the same packet format over both transports.

## 3. Mesh link: electrical

- **Single-ended LVCMOS33, source-synchronous.** Each direction of each port carries `CLK, D[3:0], FR` (FR is frame/valid). That's 6 nets per direction, 12 per link, 36 pins per module and 72 nets in total.
- **Straight lanes.** Every net runs straight down one lane (the same pin position at both ends), so there are no crossings and no jogs. Lengths within a link match automatically, give or take via placement (target ≤ 10 mm).
- **Received clocks** are on the **P side** of a clock-capable pin, so each one can drive a BUFG directly. Single-ended clocks must use the P side on 7-series parts. v1 of the pin plan got this wrong, and it is fixed in v2.
- **Series resistors:** 33 Ω, 0402, at the **driving** end on all 72 nets (R1, R14–R86), in 2 staggered rows 2.2 and 4.0 mm from the driver's pad row. Tune them on the scope: use a higher value if you see ringing, a lower value if the edges are slow. They matter most on L1 (about 48 mm) and on L3 U1↔U4 (about 82 mm).
- **Outputs:** `DRIVE 8`, `SLEW SLOW`.
- **Rates:**
  - Bring-up: a 50 MHz SDR forwarded clock, so 50 Mb/s per line and 200 Mb/s per direction.
  - Target: 50–100 MHz DDR, so 100–200 Mb/s per line and 400–800 Mb/s per direction.
  - Differential signalling was rejected. It would only pay off above about 1 Gb/s per link, which is more than JOP can consume.
- **Spare lanes:** 21–28, 36, 41, 53/54, 57–61, 74–79 and 91–95. They take GND stitching vias next to layer changes. Lanes 57–61 have 10 free pins on both rows for widening a link.

## 4. Mesh link: RTL and protocol plan

- **TX:**
  - Forward the clock with an `ODDR` (D1 = 1, D2 = 0), never by routing a clock net to a pin.
  - Launch data and FR from IOB flip-flops on the **falling** edge, so the data arrives centre-aligned with the receiver's rising edge.
- **RX:**
  - Feed the received clock pin straight into a `BUFG`. Note the bank split: on U1, the P3 RX clock is pin 192 in bank 16 while its data is in banks 15/16. The P2 RX clock is pin 88 in bank 34 while its data is in bank 35. A BUFG works across banks; a BUFR or BUFIO would not.
  - Capture D and FR in IOB flip-flops (`IOB TRUE`), then write them into an **asynchronous FIFO** into the JOP clock domain. Each module has its own 25 MHz oscillator, so the received clocks are plesiochronous, not shared.
  - The XDC sets each RX clock as an asynchronous clock group, with a ±1.5 ns input-delay template. That template needs checking against the first timing report.
- **Link layer, first version:**
  - Idle: FR = 0, data = 0.
  - Frame: FR = 1 for the duration of a frame. Two nibbles make a byte, sent MSB nibble first. A 16-bit length comes first and a CRC-16 last.
  - Link up: periodic idle words carrying the node ID. The link is up only when both ends see the expected peer ID. This also covers the case where a peer is still configuring and its pins are floating.
  - Flow control is credit-based, signalled in the reverse-direction idle words.
- **Later:**
  - Move to DDR with `IDDR`/`ODDR`.
  - Optionally add per-bit `IDELAYE2` training (needs `IDELAYCTRL` with a 200 MHz reference).

## 5. JOP integration plan

- **The JOP side is new code, following the existing jop-spinalhdl patterns:**
  - BMB bus.
  - SMP with the round-robin arbiter, plus `CmpSync`/`IHLU`.
  - Hardware objects in `com.jopdesign.hw.*`, alongside `EthMac`, `Mdio`, `SerialPort` and `SysDevice`.
  - `HAS_*` presence flags in `Const.java`.
- **New IO device:** `MeshPort`, with three instances.
  - Registers: status (link up, peer ID, TX credits, RX frame ready, error counters), a TX FIFO data/commit register, and an RX FIFO data/release register.
  - Polled first. Interrupts can come later.
- **Java layering (poll-driven, no allocation in steady state, big-endian wire words):**
  - `Transport` handles raw frames. Implementations: `MeshTransport` (port k) and `EthTransport` (raw Ethernet, EtherType **0x88B5**, MAC derived from the node ID).
  - `Channel` provides reliable, ordered messages per peer: sequence numbers, acks and retransmit.
  - `Cluster` covers node ID, membership and routing. Routing is mesh-first (port = me XOR dst), with Ethernet for nodes beyond this board, so heterogeneous boards can join.
  - Higher services (remote objects, a shared block device) can follow the `BlockDevice`/`Fat32FileSystem` interface pattern.
- **One RTL design and four builds:**
  - Generics: `NODE_ID` = 0–3.
  - XDC: `carrier_U<n>.xdc`.
  - Top-level port names are the same for every socket: `p{1,2,3}_{tx,rx}_{clk,d[3:0],fr}`, `clk25`, `led_n`, `uart_tx`, `uart_rx`.
- **Known jop-spinalhdl state, as discussed:**
  - The Ethernet MAC is GMII/MII, and RGMII at 100 Mb/s exists. The B50612D connects to the FPGA over RGMII. Its pins are in the wuxx table under ETH-PHY0/1.
  - Flash boot was noted as regressed. Use JTAG load via the RP2040 for the first tests.

## 6. Test plan

1. **Build check.** Make a top level with all mesh ports and a PRBS7 generator and checker per port, plus a UART report. Build all four XDCs in Vivado and fix any DRC, placement or timing issues. This step is the main check of this pin plan.
2. **Bank voltage check.** Build a bitstream with `PULLUP` on a free pin per bank and measure each one; see section 1.
3. **Per-link PRBS on the assembled board.** Run at 25, then 50 MHz SDR and log bit errors per port over UART. Check the received clocks with a scope and adjust the series resistors if needed.
4. **JOP with `MeshPort`.** Ping-pong frames between each pair of nodes, then measure latency and throughput.
5. **Cluster software.** Run `Transport`/`Channel`/`Cluster` over the mesh and over Ethernet, with a two-node ping test first.

## 7. Netlist changes from v1 to v2 (receive clocks moved to P-side pins)

The 2026-11-04 schematic netlist matched v1 exactly. To reach v2, change these nets on the socket side. In every case the resistor stays at the driver end.

| Net | v1 (driver.pin → receiver.pin) | v2 | Lane, X |
|---|---|---|---|
| MX_U1_U2_CLK | U1.86 → U2.85 | **U1.90 → U2.89** | 45, 5.289 |
| MX_U1_U2_D2 | U1.90 → U2.89 | **U1.86 → U2.85** | 43, 6.489 |
| MX_U1_U2_D1 | U1.88 → U2.87 | **U1.112 → U2.111** | 56, −1.311 |
| MX_U4_U3_CLK | U4.86 → U3.85 | **U4.90 → U3.89** | 45, 5.289 |
| MX_U4_U3_D2 | U4.90 → U3.89 | **U4.86 → U3.85** | 43, 6.489 |
| MX_U4_U3_D1 | U4.88 → U3.87 | **U4.112 → U3.111** | 56, −1.311 |
| MX_U3_U1_CLK | U3.72 → U1.72 | **U3.88 → U1.88** | 44, 5.889 |
| MX_U4_U2_CLK | U4.72 → U2.72 | **U4.88 → U2.88** | 44, 5.889 |
| MX_U4_U1_CLK | U4.50 → U1.50 | **U4.192 → U1.192** | 96, −25.311 |

These pins become unused: 72 on all sockets, 50 on U1 and U4, and 87 on U2 and U3. Lane 96 (pin 192) is in line with the JTAG pogo holes, so jog around them if needed.

## 8. Open items

- Measure bank VCCO (sections 1 and 6).
- Read the module speed grade (-1 or -2) from the chip marking.
- Confirm which of pins 51 and 53 really connects to ball T4.
- Check the UART direction (the XDC assumes the FPGA drives `TX` on pin 197).
- Check the resistor rows against the SODIMM footprint's pad length. The placement assumes pads reach about 1 mm into the gap.
- Check via positions: L2 at y ≈ ±27; L3 U2↔U3 at y = +1.0 (lanes 62–67) and −1.0 (lanes 68–73); L3 U1↔U4 at y ≈ 0.
- Re-create the Ethernet differential pairs (DP1–DP17) in the PCB. Add a mesh equal-length group per link direction at about 10 mm tolerance.

## 9. Pin tables per socket (mesh plan v2)

Each cell is `SODIMM pin / FPGA ball`. TX means the FPGA drives the pin, through a 33 Ω resistor at this end. RX clocks are P-side clock-capable pins.

#### U1 (node 0)

| Port (peer) | Dir | CLK | D0 | D1 | D2 | D3 | FR |
|---|---|---|---|---|---|---|---|
| P1 (U2) | TX | 90/R4 | 84/R3 | 112/N13 | 86/M3 | 104/W6 | 110/V7 |
| P1 (U2) | RX | 92/T5 | 94/J5 | 96/J6 | 98/W5 | 100/L5 | 102/L6 |
| P2 (U3) | TX | 58/H3 | 60/J1 | 62/K1 | 64/L1 | 66/M1 | 68/J2 |
| P2 (U3) | RX | 88/V4 | 74/G3 | 76/J4 | 78/G4 | 80/F4 | 70/K2 |
| P3 (U4) | TX | 164/J19 | 160/J14 | 162/J15 | 166/H13 | 168/H14 | 170/H17 |
| P3 (U4) | RX | 192/C18 | 172/H15 | 174/G18 | 176/G17 | 178/G16 | 180/F16 |

#### U2 (node 1)

| Port (peer) | Dir | CLK | D0 | D1 | D2 | D3 | FR |
|---|---|---|---|---|---|---|---|
| P1 (U1) | TX | 91/AB5 | 93/AA5 | 95/Y6 | 97/AB6 | 99/AA6 | 101/Y7 |
| P1 (U1) | RX | 89/Y4 | 83/Y3 | 111/AB8 | 85/W4 | 103/AB7 | 109/AA8 |
| P2 (U4) | TX | 58/H3 | 60/J1 | 62/K1 | 64/L1 | 66/M1 | 68/J2 |
| P2 (U4) | RX | 88/V4 | 74/G3 | 76/J4 | 78/G4 | 80/F4 | 70/K2 |
| P3 (U3) | TX | 131/Y18 | 123/V9 | 125/R14 | 127/P14 | 129/W17 | 133/AA18 |
| P3 (U3) | RX | 143/V18 | 135/W19 | 137/AB18 | 139/Y19 | 141/AA19 | 145/V19 |

#### U3 (node 2)

| Port (peer) | Dir | CLK | D0 | D1 | D2 | D3 | FR |
|---|---|---|---|---|---|---|---|
| P1 (U4) | TX | 91/AB5 | 93/AA5 | 95/Y6 | 97/AB6 | 99/AA6 | 101/Y7 |
| P1 (U4) | RX | 89/Y4 | 83/Y3 | 111/AB8 | 85/W4 | 103/AB7 | 109/AA8 |
| P2 (U1) | TX | 88/V4 | 74/G3 | 76/J4 | 78/G4 | 80/F4 | 70/K2 |
| P2 (U1) | RX | 58/H3 | 60/J1 | 62/K1 | 64/L1 | 66/M1 | 68/J2 |
| P3 (U2) | TX | 143/V18 | 135/W19 | 137/AB18 | 139/Y19 | 141/AA19 | 145/V19 |
| P3 (U2) | RX | 131/Y18 | 123/V9 | 125/R14 | 127/P14 | 129/W17 | 133/AA18 |

#### U4 (node 3)

| Port (peer) | Dir | CLK | D0 | D1 | D2 | D3 | FR |
|---|---|---|---|---|---|---|---|
| P1 (U3) | TX | 90/R4 | 84/R3 | 112/N13 | 86/M3 | 104/W6 | 110/V7 |
| P1 (U3) | RX | 92/T5 | 94/J5 | 96/J6 | 98/W5 | 100/L5 | 102/L6 |
| P2 (U2) | TX | 88/V4 | 74/G3 | 76/J4 | 78/G4 | 80/F4 | 70/K2 |
| P2 (U2) | RX | 58/H3 | 60/J1 | 62/K1 | 64/L1 | 66/M1 | 68/J2 |
| P3 (U1) | TX | 192/C18 | 172/H15 | 174/G18 | 176/G17 | 178/G16 | 180/F16 |
| P3 (U1) | RX | 164/J19 | 160/J14 | 162/J15 | 166/H13 | 168/H14 | 170/H17 |
