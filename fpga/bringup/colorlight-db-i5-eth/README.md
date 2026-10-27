# Ethernet bring-up, stage 1 (MDIO): fpga-db-colorlight-i5 + Colorlight i5

The i5 version of `../colorlight-db-i9plus-eth` stage 1. It checks the base
board's RJ45 (RJ1), its magnetics and the SODIMM ETH1 pairs, plus the module's
B50612D PHYs, without any RGMII: the PHY autonegotiates on its own, and this
design only reads it over MDIO. The logic is the i9+ probe's, unchanged; only
the pins differ.

    make                                              # build/EthMdioProbe.bit
    make sram CABLE="-c dirtyJtag --busdev-num B:D"   # load (volatile), then check
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
  and PHY all work at gigabit. By LiteX's numbering, address 1 is U28. That
  comes from LiteX's comment and is not measured here; RGMII traffic on U28's
  pins would show it.
- Address 0 is the other PHY: ETH2 has no jack on this board, so no link.
- Every register matches the i9+'s 2026-10-10 result on this base board.
- The PHYs answer with P4 undriven, so the module keeps them out of reset on
  its own.
- The first scan after the load read AUX `ff3c`; every later scan read `871c`.
- Timing: 109 MHz post-route against the 25 MHz clock.

Stage 2 (RGMII traffic) is not ported: `EthRgmiiTest.v` uses Xilinx
primitives (MMCM, ODDR, IOB flops), so the ECP5 needs its own TX clocking.
