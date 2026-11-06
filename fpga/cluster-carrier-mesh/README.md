# JOP cluster carrier: the mesh build check

The carrier holds four Colorlight i9+ modules (XC7A50T-FGG484) in DDR2-SODIMM
sockets U1-U4, nodes 0-3. Mesh port k links node i to node i XOR k; each
direction is a forwarded clock and D[3:0] + FR, LVCMOS33, with a 33 ohm resistor
at the driver. The plan is the carrier designer's handoff, v2 of 2026-11-05, in
[`plan/`](plan/JOP_cluster_carrier_handoff.md); `plan/mesh_pads.csv` is its
source of truth and generated `constraints/carrier_U<n>.xdc`, which are here as
delivered.

This flow is the handoff's test plan step 1: it builds `jop.system.MeshPrbsTop`
for each socket against that socket's XDC, so Vivado checks the pin plan before
the board is made. The same design is the step 3 bring-up test on the assembled
board.

    make            RTL for all four sockets, a Vivado build of each, the verdicts
    make u1 .. u4   one socket; make -j4 builds them side by side
    make report     each socket's VERDICT and LINK lines from its last build

A build writes `build/meshPrbsU<n>/vivado/build/`: the bitstream,
`post_route.dcp`, and timing, DRC, methodology, I/O, clock-interaction,
check-timing and datasheet reports, with `verdict.txt` summarising them
(`reports.tcl` says what each count means).

## The design

- **Clock**: the design's PLL (an MMCM, `mesh_clk`) makes the 50 MHz link
  clock from the module's 25 MHz oscillator; 50 MHz SDR is the handoff's
  bring-up rate.
- **TX**, per port: PRBS7, five bits a clock across D0-D3 and FR. The clock
  is forwarded through an ODDR, and every data line comes from an ODDR too,
  changing on the falling edge so the data is centred on the far end's rising
  edge (handoff section 4).
- **RX**, per port: the received clock through its own BUFG; D and FR
  captured in I/O-tile flip-flops; a self-synchronising PRBS7 checker. A
  window is clean only if it had no error and saw a 1, so a peer holding its
  pins low does not look like a working link.
- **Report**: once a second on `uart_tx`, 115200 8N1:

      N0 P1 L1 C05F6 E00000000 W000004C4 P2 ... P3 ...

  L: the last window was clean. C: the RX clock's activity, its 32768-cycle
  toggles in that second (05F6 at 50 MHz, 0000 with no peer). E: bit errors
  since the port first locked. W: 2^20-cycle windows since then, so the bit
  error rate is E / (W * 2^20 * 5). The LED blinks at 1 Hz.

`spinalhdl/src/test/scala/jop/io/MeshPrbsLinkTest.scala` runs one TX into one
RX on the forwarded clock. It checks four things. The data never changes on
the clock's rising edge. A clean link locks with no errors. A single flipped
bit costs exactly three errors. A line held at 0 never locks. The edge test
was proved red on data launched from the rising edge, which the PRBS checker
alone cannot see in a zero-delay simulation. The held-at-0 test was proved red
on a checker without the "saw a 1" rule.

## Results, 2026-11-06 (Vivado 2025.2, xc7a50tfgg484-1)

| Socket | Setup WNS | Hold WHS | DRC | TX lines from ODDR | RX D/FR in ILOGIC | RX clocks IBUF->BUFG |
|---|---|---|---|---|---|---|
| U1 | +5.353 ns | +0.121 ns | 0 checks found | 18/18 | 15/15 | 3/3 |
| U2 | +5.349 ns | +0.106 ns | 0 | 18/18 | 15/15 | 3/3 |
| U3 | +5.366 ns | +0.117 ns | 0 | 18/18 | 15/15 | 3/3 |
| U4 | +5.358 ns | +0.102 ns | 0 | 18/18 | 15/15 | 3/3 |

Every socket builds to a bitstream, with about 530 LUTs of the part's 32,600.
The worst hold path is an ordinary flop-to-flop path on the link clock, not a
pin.

The link, from each build's datasheet. For each socket the three values are
ports P1/P2/P3.

| Socket | RX capture window at the pins | RX window offset from centre | TX skew over a port's six lines |
|---|---|---|---|
| U1 | 2.917 / 2.948 / 2.813 ns | -1.659 / -1.673 / -1.433 ns | 52 / 15 / 40 ps |
| U2 | 2.920 / 2.948 / 2.914 ns | -1.670 / -1.673 / -1.694 ns | 43 / 15 / 19 ps |
| U3 | 2.920 / 2.912 / 2.913 ns | -1.670 / -1.678 / -1.654 ns | 43 / 24 / 28 ps |
| U4 | 2.917 / 2.912 / 2.939 ns | -1.659 / -1.678 / -1.673 ns | 52 / 24 / 188 ps |

Against the handoff's ±1.5 ns input-delay template at 50 MHz SDR, every RX
port has more than 5.3 ns of setup slack and more than 8.5 ns of hold slack.
U4's P3 TX skew of 188 ps is its clock in bank 16 and most of its data in bank
15. That is the same link the handoff notes from U1's side.

## The pin plan, checked against three sources

1. **The repo's i9+ table** (`hw.ColorlightSodimm.SodimmI9Plus`): all 106
   pins it shares with `plan/pads.csv` agree. It has no entry for 43, 45, 47,
   53 or 159-198.
2. **The wuxx i9+ v6.1 table**, fetched 2026-11-06: all 150 pads agree,
   159-198 included. The only ball it lists twice is T4, at pins 51 and 53.
   The module wires both pins to T4 (confirmed 2026-11-06), so 51 and 53 are
   one net, and the plan uses neither.
3. **Vivado's package data** for xc7a50tfgg484:
   - The bank and pin function of all 144 mesh pads agree with `plan/`.
   - All 12 RX clocks are on the P side of a clock-capable pair (MRCC or
     SRCC).
   - No ball is used twice on a socket.
   - No mesh ball is one the module uses itself (78 balls, from the repo's
     i9+ device map).
   - Both ends of every net agree on lane and direction, and each socket's XDC
     matches `mesh_pads.csv`.

   Two pins are legal at LVCMOS33 but worth knowing:
   - R14 (pin 125, `MX_U2_U3_D1`) is VREF-capable. That matters only if bank
     14 ever takes a VREF standard.
   - AA19 (pin 141, `MX_U3_U2_D3`) doubles as RDWR_B, which only SelectMAP
     configuration uses; the modules boot from SPI.

## Findings for the plan's XDC

1. **The clk25 clock group must take in its generated clocks.**
   `set_clock_groups ... -group [get_clocks clk25]` leaves out the clocks the
   MMCM derives from clk25: the 50 MHz link clock here, and the system clock
   in a JOP build. Built against the plan alone (`make u1 DESIGN_XDC=`),
   Vivado classifies every RX-clock-to-link-clock crossing as "No Common
   Clock, Timed (unsafe)": 67 endpoints per port. Fix:
   `-group [get_clocks -include_generated_clocks clk25]`, as
   `constraints/mesh_prbs.xdc` does.
2. **The TX side is unconstrained.** All 18 mesh TX ports have no output
   delay. At 50 MHz SDR the measured skew (above) makes that harmless. A DDR
   link should constrain it: a generated clock on each `p<k>_tx_clk` and
   output delays on its data.
3. **The UART and LED pins** have no input or output delay.
   `mesh_prbs.xdc` false-paths them; any design on the carrier will want the
   same.
4. **For the DDR target**, each RX window is about 2.9 ns wide and sits
   1.4-1.7 ns off centre, because the clock comes through a BUFG. At 100 MHz
   DDR a bit lasts 5 ns, and 2.9 ns plus the ±1.5 ns template is 5.9 ns. That
   rate needs the clock centred (IDELAYE2, or an MMCM phase shift) and a skew
   budget tighter than ±1.5 ns. 50 MHz DDR (10 ns a bit) fits as it stands.

## Open before the board is made

These come from handoff section 8, with what this check adds:

- **Bank VCCO** (14, 15, 16, 34, 35) is assumed 3.3 V. This check cannot
  measure it; LVCMOS33 is legal in all five, which are HR banks.
- **The speed grade.** This was built at -1, the slower grade.
- **The UART direction** on pins 197/198. Both are ordinary I/O, so either
  direction builds; the RP2040's wiring decides.
- **Pins 41 and 43**, the carrier's ETH1 LED nets, are FPGA balls R2 and P6
  on the i9+, not PHY outputs. The jack's LEDs light only if the FPGA drives
  them.
- **The resistor rows, the via positions and the Ethernet pairs.** These are
  PCB checks; nothing here covers them.
