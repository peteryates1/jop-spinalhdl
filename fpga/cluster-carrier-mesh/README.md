# JOP cluster carrier: the mesh build check

The carrier holds four Colorlight i9+ modules (XC7A50T-FGG484) in DDR2-SODIMM
sockets U1-U4, nodes 0-3. Mesh port k links node i to node i XOR k; each
direction is a forwarded clock and D[3:0] + FR, LVCMOS33, with a 33 ohm resistor
at the driver. The plan is the carrier designer's handoff, v2 of 2026-11-05, in
[`plan/`](plan/JOP_cluster_carrier_handoff.md). Its source of truth is
`plan/mesh_pads.csv`. The four per-socket XDCs the designer generated from it
are in `plan/xdc/` as delivered; no build reads them.

This flow is the handoff's test plan step 1: it builds `jop.system.MeshPrbsTop`,
so Vivado checks the pin plan before the board is made. The same bitstream is
the step 3 bring-up test on the assembled board, in every socket.

    make           RTL and constraints from the plan, a Vivado build, the verdict
    make report    the verdict from the last build

A build writes `build/meshPrbs/`: the RTL with its generated XDC
(`rtl/MeshPrbsTop.xdc`), and under `vivado/build/` the bitstream,
`post_route.dcp`, and timing, DRC, methodology, I/O, clock-interaction,
check-timing and datasheet reports, with `verdict.txt` summarising them
(`reports.tcl` says what each count means).

## One bitstream, every socket

The pin plan already allows it. Grouped by socket, port and direction,
`mesh_pads.csv` has 24 six-pin groups, and they are only ten distinct pin
sets: a link uses the same lane at both ends, so a bundle of pins keeps one
role in every socket that uses it. Only its direction changes. `MeshPlan`
derives the bundles from the CSV, and `MeshPlanTest` holds them to this table:

| Bundle | Pins: CLK, D0-D3, FR | U1 | U2 | U3 | U4 | Buffer |
|---|---|---|---|---|---|---|
| mesh58 | 58, 60, 62, 64, 66, 68 | P2 TX | P2 TX | P2 RX | P2 RX | IOBUF |
| mesh88 | 88, 74, 76, 78, 80, 70 | P2 RX | P2 RX | P2 TX | P2 TX | IOBUF |
| mesh89 | 89, 83, 111, 85, 103, 109 | – | P1 RX | P1 RX | – | input |
| mesh90 | 90, 84, 112, 86, 104, 110 | P1 TX | – | – | P1 TX | OBUFT |
| mesh91 | 91, 93, 95, 97, 99, 101 | – | P1 TX | P1 TX | – | OBUFT |
| mesh92 | 92, 94, 96, 98, 100, 102 | P1 RX | – | – | P1 RX | input |
| mesh131 | 131, 123, 125, 127, 129, 133 | – | P3 TX | P3 RX | – | IOBUF |
| mesh143 | 143, 135, 137, 139, 141, 145 | – | P3 RX | P3 TX | – | IOBUF |
| mesh164 | 164, 160, 162, 166, 168, 170 | P3 TX | – | – | P3 RX | IOBUF |
| mesh192 | 192, 172, 174, 176, 178, 180 | P3 RX | – | – | P3 TX | IOBUF |

A dash is a bundle the socket leaves unconnected. Every bundle that is ever
an RX has its clock on the P side of a clock-capable pair.

**The socket straps** tell the design where it is. `plan/straps.csv` is the
PCB netlist's strap wiring (the third to fifth netlists of 2026-11-08), and the design
takes its strap pins from it. Each grounded strap goes through 1 kohm
(R91-R98); the FPGA pulls all three up, so an open strap reads 1:

| Socket | 120 (presence) | 122 (LSB) | 124 (MSB) | Reads as |
|---|---|---|---|---|
| U1 | GND | GND | GND | node 0 |
| U2 | GND | open | GND | node 1 |
| U3 | GND | GND | open | node 2 |
| U4 | GND | open | open | node 3 |
| another board | open | – | – | drives nothing |

120 reading 0 is what "on the carrier" means: the design drives nothing until
the straps have held one value for 1 ms, and never on a board where 120 reads
1. A board that grounds 120 for some other reason would pass that check.

**The UART** to the carrier's RP2350B is SODIMM 197 and 198 on every socket.
`plan/uart.csv` is the netlist's UART wiring (the fourth and fifth of
2026-11-08): the RP2350B drives 197, through 470 ohm, so the design receives
on 197 (`uart_rx`) and sends on 198 (`uart_tx`). It takes both pins from that
table, which must give every socket the same two: one bitstream serves all
four, so a socket whose UART ran the other way would need a PCB fix, not a
second build. While the RP2350B boots, or sits in BOOTSEL, its pads pull
down, so `uart_rx` reads a break until its firmware drives the line. This
design ignores what it receives; a JOP build must ignore a break.

**The reset** is SODIMM 194 (ball C17) on every socket, driven by the RP2350B
through 470 ohm and pulled to ground by 10 kohm at the module (R116,
R120-R122, the fifth netlist). A module therefore runs until the RP2350B
drives it high, including while the RP2350B boots, so a design that uses it
takes high as reset. It must not pull it up: the FPGA's pull-up against the
10k could hold it at up to half the rail. This design does not use it;
Vivado pulls an unused pin down, which agrees.

## The design

- **Clock**: the design's PLL (an MMCM, `mesh_clk`) makes the 50 MHz link
  clock from the module's 25 MHz oscillator; 50 MHz SDR is the handoff's
  bring-up rate.
- **TX**, per bundle some socket drives: PRBS7, five bits a clock across D0-D3
  and FR. The clock is forwarded through an ODDR, and every data line comes
  from an ODDR too, changing on the falling edge so the data is centred on the
  far end's rising edge (handoff section 4). The bundle's buffers drive only in
  the sockets the table says.
- **RX**, per bundle some socket receives on: the received clock through its
  own BUFG; D and FR captured in I/O-tile flip-flops; a self-synchronising
  PRBS7 checker. A window is clean only if it had no error and saw a 1, so a
  peer holding its pins low does not look like a working link. Each port
  reports the bundle its socket receives on. A bundle the socket drives also
  loops back through its pads to its own checker, which nothing reports.
- **Pulls**: every mesh pin has a pull-down, so an idle line reads 0 and an
  unused RX clock does not float into its BUFG.
- **Report**: once a second on `uart_tx`, 115200 8N1:

      N0 P1 L1 C05F6 E00000000 W000004C4 P2 ... P3 ...

  N: the node the straps say, `-` on another board, `?` before they are read.
  L: the last window was clean. C: the RX clock's activity, its 32768-cycle
  toggles in that second (05F6 at 50 MHz, 0000 with no peer). E: bit errors
  since the port first locked. W: 2^20-cycle windows since then, so the bit
  error rate is E / (W * 2^20 * 5). The LED blinks at 1 Hz.

## The tests (jop.io, which CI runs)

- `MeshPrbsLinkTest`: one TX into one RX on the forwarded clock. The data
  never changes on the clock's rising edge; a clean link locks with no errors;
  a single flipped bit costs exactly three errors; a line held at 0 never
  locks. The edge test was proved red on data launched from the rising edge,
  which the PRBS checker alone cannot see in a zero-delay simulation, and the
  held-at-0 test on a checker without the "saw a 1" rule.
- `MeshPlanTest`: the bundle table above, one TX and one RX bundle per port
  in every socket, P-side clock pins, no pin in two bundles, every net of
  `mesh_nets.csv` joining a TX line to the same line of its peer's RX bundle,
  each socket's straps, as `straps.csv` wires them, reading as its node, and
  the UART on the pins `uart.csv` gives, receiving on the one the RP2350B
  drives, with a table that reverses one socket's UART refused. Both UART
  tests were proved red on the fixed pins the design used before.
- `MeshStrapsTest`: each socket's straps read as its node, and only after they
  have settled; an open presence strap means drive nothing; a strap that
  changes restarts the wait, and once read they are never read again.
- `MeshPrbsCarrierTest`: the one design in all four sockets, on four clocks up
  to 6 % apart, with the straps `straps.csv` gives each socket, wired as
  `mesh_nets.csv` wires them. All twelve links lock with no errors, no net is
  ever driven from both ends, and nothing is driven before the straps are
  read. A module whose presence strap is open drives nothing, and only the
  links from it stay down. Proved red with the node straps read bit-swapped,
  with TX enabled without the straps, with each port reporting its own
  looped-back TX bundle (the first test passed that one; the second caught
  it), and with two sockets' LSB straps swapped in `straps.csv`.

## Results, 2026-11-08 (Vivado 2025.2, xc7a50tfgg484-1)

One build, one bitstream for all four sockets, with the straps on 120/122/124
and the UART receiving on 197:

| Setup WNS | Hold WHS | DRC | TX lines from ODDR | RX D/FR in ILOGIC | RX clocks IBUF->BUFG | Outputs that turn off | Pulls |
|---|---|---|---|---|---|---|---|
| +5.349 ns | +0.029 ns | 0 checks found | 48/48 | 40/40 | 8/8 | 48/48 | 63/63 |

It uses 906 LUTs of 32,600, 2,767 registers and 10 of the 32 BUFGs. The worst
setup path is an RX input (`mesh143_d3` into its I/O-tile flip-flop) against
the ±1.5 ns input-delay template. The worst hold path is flop-to-flop in an
RX clock (mesh92's checker into its clock crossing), not a pin; before the
UART swap it was +0.052 ns in the link clock, so it moves with placement. Each
mesh output's buffer is switched by its own register driving T, never a
constant. The only ports check_timing finds unconstrained are the ones the XDC
means to leave: the straps and `uart_rx` (false paths), and the outputs, which
have no output delay (finding 2 below).

The link, from the datasheet's per-port tables, slow corner. The windows now
cover FR as well as D0-D3:

| Bundle | RX capture window at the pins | Its centre from the clock edge | TX skew over the six lines |
|---|---|---|---|
| mesh58 | 2.912 ns | -1.678 ns | 15 ps |
| mesh88 | 2.948 ns | -1.673 ns | 24 ps |
| mesh89 | 2.922 ns | -1.670 ns | (RX only) |
| mesh90 | (TX only) | | 52 ps |
| mesh91 | (TX only) | | 43 ps |
| mesh92 | 2.928 ns | -1.654 ns | (RX only) |
| mesh131 | 2.923 ns | -1.659 ns | 19 ps |
| mesh143 | 2.923 ns | -1.690 ns | 28 ps |
| mesh164 | 2.939 ns | -1.672 ns | 40 ps |
| mesh192 | 2.924 ns | -1.377 ns | 188 ps |

mesh192's 188 ps is its clock in bank 16 and most of its data in bank 15: the
U4 -> U1 link, as the handoff notes from U1's side. These agree with the
per-socket builds of 2026-11-06 (setup +5.35, hold +0.10 ns, the same windows
and skews), which built each socket against the designer's own XDC; that flow
was replaced by this one.

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

   Four pins are legal at LVCMOS33 but worth knowing:
   - R14 (pin 125, `mesh131_d1`) is VREF-capable. That matters only if bank
     14 ever takes a VREF standard.
   - AA19 (pin 141, `mesh143_d3`) doubles as RDWR_B, and two straps double as
     configuration pins too: R16 (pin 120) as BPI address A04, and V17 (pin
     124) as SelectMAP's CSI_B. Only parallel configuration uses them; the
     modules boot from SPI, after which they are ordinary I/O.

**The PCB netlist** (EasyEDA, the fifth of 2026-11-08) matches plan v2 on all
72 mesh nets: each is driver pin, one 33 ohm resistor, receiver pin, nothing
else, and the resistor designators match `mesh_resistor_placement.csv`. Its
straps are as tabled above. The U4 -> U1 clock stays on pin 192 with a jog
around the JTAG pogo holes, which adds 0.64 mm (about 4 ps) to it; the only
other pin that could take it is 198, the UART TX. Each module's UART, JTAG
and reset go to a Waveshare Core2350B (RP2350B), powered from USB VBUS,
`1_5V` or `2_5V` through three Schottky diodes, so it is up whenever any
module is. It reads each 5 V rail on its ADC, through 100k/100k with 100 nF.

Every line the RP2350B drives has a series resistor: 220 ohm on TCK, TMS and
TDI, 470 ohm on the UART and the reset. A module whose rail is off would be
fed through its clamp diodes by a line driven high, so the firmware holds a
module's pins as inputs while the ADC says its rail is down, and the
resistor limits the current if it ever does not. Each module's UART lines
up with a hardware UART: U2 and U3 with UART0, U1 and U4 with UART1, so one
of each pair can have it and the other a PIO UART.

## Findings for the plan's XDC

These are about the designer's per-socket XDCs in `plan/xdc/`; the generated
XDC already does each.

1. **The clk25 clock group must take in its generated clocks.**
   `set_clock_groups ... -group [get_clocks clk25]` leaves out the clocks the
   MMCM derives from clk25: the 50 MHz link clock here, and the system clock
   in a JOP build. Built against the plan alone (2026-11-06), Vivado
   classified every RX-clock-to-link-clock crossing as "No Common Clock,
   Timed (unsafe)": 67 endpoints per port. Fix:
   `-group [get_clocks -include_generated_clocks clk25]`.
2. **The TX side is unconstrained.** The mesh TX ports have no output delay.
   At 50 MHz SDR the measured skew (above) makes that harmless. A DDR link
   should constrain it: a generated clock on each forwarded clock and output
   delays on its data.
3. **The UART and LED pins** have no input or output delay; they want false
   paths.
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
- **Pins 41 and 43**, the carrier's ETH1 LED nets, are FPGA balls R2 and P6
  on the i9+, not PHY outputs. The jack's LEDs light only if the FPGA drives
  them.
- **What each module's flash holds at first power-up.** A module runs its
  flash image until the RP2350B loads it over JTAG. The Atari 800 base board's
  JOP image drives SODIMM 50-66 as LEDs, and on the carrier 58-66 are mesh
  lines: a module carrying it would fight its peer's driver through the 33 ohm
  resistor. Erase the flash, or write this bitstream to it, first.
- **The resistor rows, the via positions and the Ethernet pairs.** These are
  PCB checks; nothing here covers them. The netlist has no equal-length groups
  yet.
