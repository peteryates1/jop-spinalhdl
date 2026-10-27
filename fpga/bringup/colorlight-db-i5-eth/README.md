# Ethernet bring-up: fpga-db-colorlight-i5 + Colorlight i5

The i5 version of `../colorlight-db-i9plus-eth`: stage 1 (MDIO) and stage 2
(RGMII frames at 100 Mb/s, below). Stage 1 checks the base
board's RJ45 (RJ1), its magnetics and the SODIMM ETH1 pairs, plus the module's
B50612D PHYs, without any RGMII: the PHY autonegotiates on its own, and this
design only reads it over MDIO. The logic is the i9+ probe's, unchanged; only
the pins differ.

    make                                              # both bitstreams
    make sram CABLE="-c dirtyJtag --busdev-num B:D"   # stage 1: load (volatile), then check
    # console: stamp CDC0 (if01 of the board's stamp), 115200 8N1

Take B:D from sysfs for the board's stamp (`4636323337058419` on 2026-10-27):
this board has no `jtag_probe_map` alias yet. There is no flash target. The
module's boot image stays `../colorlight-i5-idle`, and a power cycle returns to
it. `make check` reads USERCODE `0xE7D10005` and DONE.

Once a second it prints one line per answering MDIO address, then `--`; see
`EthMdioProbe.v` for the fields. LED0-7 = link per address 0-7, RJ1 left =
any link, RJ1 right and module LED D2 = heartbeat.

## Pins

The PHYs sit on the module and are wired straight to the i5's own balls, so
none of the i9+'s pins apply. Two sources agree ball for ball:

- the wuxx Colorlight-FPGA-Projects README, "ETH-PHY0 (U28)" and "ETH-PHY1
  (U29)". It is also the source of the repo's SODIMM tables.
- LiteX `colorlight_i5.py` (`_io_v7_0`), which numbers the PHYs the other way
  round: its `eth 0` is U29 and its `eth 1` is U28. Its comment says the order
  follows the PHYs' PHYA[0] straps, i.e. their MDIO addresses.

| signal | U28 | U29 |
|---|---|---|
| MDC / MDIO / RESET (shared) | N5 / P5 / P4 | N5 / P5 / P4 |
| GTXCLK | U19 | G1 |
| TX_EN | P19 | K1 |
| TXD0-3 | U20 T19 T20 R20 | G2 H1 J1 J3 |
| RXC | L19 | H2 |
| RX_DV | M20 | P2 |
| RXD0-3 | P20 N19 N20 M19 | K2 L1 N1 P1 |

None of these balls is on the SODIMM edge in `ColorlightSodimm.SodimmI5`. The
design drives MDC and MDIO, and holds both PHYs' GTXCLK and TX_EN low. P4 is
NOT driven: on the i9+, driving the shared PHY reset broke the stamp's links
(see the i9+ README).

Base-board pins are SODIMM pins from the 2026-10-10 netlist, turned into i5
balls by `SodimmI5`, and each was checked back against the netlist. The UART is
E1 = SODIMM 153 = GPIO5, the stamp's uart1 RX. LED0-7 are T3 M3 T2 N3 N2 M1 V1
W1 (SODIMM 66 64 62 60 58 54 52 50), and the RJ1 LEDs are K18 / C18 (SODIMM 49 /
51). D2 is U16, which is also SODIMM 41, a pin with no net on this board. The
stamp's TX lines (SODIMM 155 = F2, 127 = A18) and the reset button (SODIMM 42 =
R1) are not driven.

## Result, 2026-10-27 (RJ1 to a gigabit switch, loaded through the stamp)

    PHY 00 ID 0362:5e62 BMCR 1140 BMSR 7949 LPA 0000 1KS 0000 AUX 1000
    PHY 01 ID 0362:5e62 BMCR 1140 BMSR 796d LPA c5e1 1KS 3800 AUX 871c

- **RJ1 is the PHY at MDIO address 1**, as on the i9+ on the same base board:
  autonegotiation complete, link up, 1000BASE-T full duplex (AUX 10:8 = 7),
  local and remote receivers OK (1KS 3800). The jack, magnetics, SODIMM pairs
  and PHY all work at gigabit. Address 1 is U28: stage 2 measured it.
- Address 0 is the other PHY: ETH2 has no jack on this board, so no link.
- Every register matches the i9+'s 2026-10-10 result on this base board.
- The PHYs answer with P4 undriven, so the module keeps them out of reset on
  its own.
- The first scan after the load read AUX `ff3c`; every later scan read `871c`.
- Timing: 109 MHz post-route against the 25 MHz clock.

## Stage 2: RGMII at 100 Mb/s (`EthRgmiiTest.v`)

    make sim                                          # Verilator checks; ALL PASS
    make rgmii-sram CABLE="-c dirtyJtag --busdev-num B:D"
    sudo tcpdump -i <lan> -n -e -X 'ether src 02:4a:4f:50:00:05'

The i9+ stage 2 (`../colorlight-db-i9plus-eth/EthRgmiiTest.v`) ported to the
ECP5, with the logic unchanged. The design's header has the console format,
the buttons and the LEDs. The Xilinx parts are replaced:

- TXC's 90-degree copy of clk25: `Pll90.v`, an EHXPLLL from
  `ecppll -n Pll90 --clkin_name clkin -i 25 --clkout0_name clk0 -o 25
  --clkout1_name clk90 --clkout1 25 --phase1 90`. The VCO runs at 600 MHz,
  and CLKOS is 6 VCO cycles (10 ns) after CLKOP.
- TXC itself: ODDRX1F on clk90, (D0, D1) = (1, 0) for 90 degrees, (0, 1) for
  270, (0, 0) off.
- TXD and TX_CTL: ODDRX1F with D0 = D1 on clk25, so every TX pin leaves an
  I/O register. That is one clk25 of latency more, the same on all of them.

`make_frame.py` writes `frame.hex`: a UDP broadcast, 192.168.0.251 ->
192.168.0.255:5005 from 02:4a:4f:50:00:05 (the i9+ uses .250 and ...01), with
the FCS checked by the CRC residue. The payload is kept at 42 bytes, so
FRAME_LEN stays 96.

The testbench models ODDRX1F and EHXPLLL. The PLL model takes CLKOS's phase
from the CPHASE/FPHASE parameters, so a PLL at the wrong phase fails.
`PLL=<file> sim/run.sh` runs another Pll90.v. Red, with CLKOS_CPHASE 11 (0
degrees): the four TXC phase checks failed (TXC rose 0 ns into the nibble, on
the data change). Green, with Pll90.v: ALL PASS (rise 10 ns, fall 30 ns at 90;
30/10 at 270), plus the MDIO writes, the buttons, the PHY0 loopback capture
and the recovery from a forced illegal state. The checker exits non-zero on a
failure.

Timing, post-route: clk25 93.7 MHz (25 needed), RXC 134.4 / 127.1 MHz (125
needed, before the writes drop the PHY to 100 Mb/s). The cross-domain paths
are the deliberate ones: gray-coded counts and the RX captures, which can
tear, as on the i9+.

Pins: index 0 is U29 (LiteX eth 0), index 1 is U28 (eth 1), from the table
above. The buttons are SODIMM 42/44/46/48 (R1 T1 U1 Y2), inputs with pull-ups,
each checked against the netlist. Only RESET is fitted on the second board, and
the others read released.

### Result, 2026-10-27 (RJ1 to a gigabit switch, loaded through the stamp)

- **TX works.** The VM received `JOP colorlight-db i5 RGMII stage 2 hello!`
  from 02:4a:4f:50:00:05, 192.168.0.251 -> 192.168.0.255:5005, once a second
  and intact. The switch passed the frames on, so their FCS was right.
- **RX works.** 22 UDP broadcasts from the VM raised PHY1's frame count by
  exactly 22, and its capture is byte-exact:
  `55 55 55 55 55 55 55 d5 ff ff ff ff ff ff 52 54 00 09 84 6c 08 00 45 00 00 4b ...`
  (the VM's MAC, IPv4, total length 75). PHY0 (no jack) counted nothing.
- **RJ1 is U28** (MDIO 1, LiteX's eth 1), measured: the frames arrive on U28's
  RX pins.
- The MDIO writes put address 1 at 100BASE-TX FD (AUX 851c, reg 9 = 0000).
  RXC measured 25.00 MHz on U28 and 125 MHz on U29, which stays at its
  1000BASE-T default.
- The first seconds after the load counted 58 RX events with an all-zero
  capture while the link renegotiated. After that the count rose only with
  traffic: 33 frames in the next ~45 s of LAN background, then exactly 22 for
  the VM's 22 broadcasts.
