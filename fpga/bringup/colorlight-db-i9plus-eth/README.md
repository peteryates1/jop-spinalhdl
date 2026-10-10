# Ethernet bring-up, stage 1 (MDIO): fpga-db-colorlight-i5 + i9+

Checks the base board's RJ45 (RJ1), its magnetics and the SODIMM ETH1 pairs
(15-35), plus the module's B50612D PHYs, without any RGMII: the PHY
autonegotiates on its own, and this design only reads it over MDIO.

    vivado -mode batch -source build.tcl
    sudo openFPGALoader -c dirtyJtag --busdev-num "$(../../scripts/jtag_probe_map --busdev colorlight-db)" build/EthMdioProbe.bit
    # console: stamp CDC0 (usb_serial_map colorlight-db-0), 115200 8N1

Once a second it prints one line per answering MDIO address, then `--`; see
`EthMdioProbe.v` for the fields. LED0-7 = link per address 0-7, RJ1 left =
any link, RJ1 right = heartbeat.

## Result, 2026-10-10 (module #3, RJ1 to a gigabit switch)

    PHY 00 ID 0362:5e62 BMCR 1140 BMSR 7949 LPA 0000 1KS 0000 AUX 1000
    PHY 01 ID 0362:5e62 BMCR 1140 BMSR 796d LPA c5e1 1KS 3800 AUX 871c

- **RJ1 is the PHY at MDIO address 1**: autonegotiation complete, link up,
  1000BASE-T full duplex (AUX 10:8 = 7), local and remote receivers OK
  (1KS 3800). The jack, magnetics, SODIMM pairs and PHY all work at gigabit.
- Address 0 is the other PHY; ETH2 has no jack on this board, so no link.
- Both report ID 0362:5e62 (Broadcom, the BCM5461-family OUI).

## H2 (PHY reset) must not be driven -- unexplained

The first version held H2 low for 20 ms, then high (LiteX's `rst_n`). On
hardware the design ran and printed (its LEDs showed completed lines), yet
the stamp's RX pin (GPIO5, sampled over SWD) never toggled, and JTAG lost the
FPGA minutes later; the board needed a power cycle. A variant differing ONLY
in leaving H2 undriven works, console and JTAG included. The PHYs leave reset
on their own, so the module must hold the line high. One trial each way; the
mechanism is not understood. Settle it before an RGMII stage drives H2.

Also seen on the way: a module that is not fully seated fails the jig's SRAM
test (every read 0x4A) and drops JTAG; re-seating fixed both.
