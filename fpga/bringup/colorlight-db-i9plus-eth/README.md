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

## Stage 2: RGMII at 100 Mb/s (`EthRgmiiTest.v`)

    vivado -mode batch -source build_rgmii.tcl      # build/EthRgmiiTest.bit
    sim/run.sh                                      # Verilator checks; ALL PASS
    sudo openFPGALoader -c dirtyJtag --busdev-num "$(../../scripts/jtag_probe_map --busdev colorlight-db)" build/EthRgmiiTest.bit
    sudo tcpdump -i <lan> -n -e -X 'udp port 5005 or ether src 02:4a:4f:50:00:01'

The header of `EthRgmiiTest.v` has the console format, the buttons and the
LEDs. `make_frame.py` writes `frame.hex`: a UDP broadcast, 192.168.0.250 ->
192.168.0.255:5005 from 02:4a:4f:50:00:01, its FCS checked by the CRC residue.

### Result, 2026-10-11

- **RX works.** PHY1's RX pins (RXC L3, RXD N2 N3 P1 P2, RX_CTL R1) deliver
  byte-exact frames from the LAN at 100 Mb/s, low nibble first, sampled on
  RXC's falling edge, e.g. the router's DHCP broadcast
  `55 55 55 55 55 55 55 d5 ff ff ff ff ff ff c4 eb 43 23 ff f1 08 00 45 c0 01 48 ...`;
  557 frames in 22 minutes.
- **MDIO writes work.** reg 9 <- 0, then BMCR <- 0x1200: the link comes back
  at 100 FD (AUX 851c), BMCR reads 1000 and reg 9 0000.
- **Clocks.** Counted against clk25 each second: PHY0's RXC (no link)
  125,000,000, PHY1's at 100 FD 25,000,08x, steady for 22 minutes.
- **TX does not reach the LAN yet.** The first version (TXC = inverted clk25
  from an ODDR, TXD/TX_CTL from fabric flops) sent a frame a second for six
  minutes; a VM on the same switch saw the router's broadcasts and none of
  ours. The FCS is right. Suspect: RGMII samples TX_CTL on both TXC edges
  (TX_EN rising, TX_EN ^ TX_ER falling) and that TXC's falling edge sat within
  about 2 ns of the TX_CTL transitions, i.e. TX_ER at the frame edges. The
  current version shifts TXC 90 degrees (MMCM) with the data in IOB flops
  (sim: TXC edges 10 and 30 ns into the nibble), and START puts PHY1 in MII
  loopback to show what the PHY takes from our TX. It has not had a clean run
  on hardware: its one load started with TXC off and loopback on (next section).

## After a JTAG load, some flip-flops start at 1 -- open

Seen on every load of a stage 2 build (installed openFPGALoader, dirtyJtag):

- The first sequencer (5-bit state, no default branch) sat in state 31, an
  illegal all-ones state, from the start: no console at all, the MDIO writes
  never went out (BMCR still 1140 afterwards), and LED0-3 showed all-ones
  registers, not reads.
- With a default branch that counts and records illegal states, two builds
  read `faults 0000 badst 0f prevst 0f`: the state and its copy started at
  1111 and the 16-bit fault counter at ffff, which the one fault wrapped to 0.
- `txc_mode` started at 3 (only 0-2 are reachable), `lb_want` at 1, `tx_busy`
  at 1 (one frame more than reports). Others started right (uptime, txcount).

The INIT values in the routed checkpoint are right. Not root-caused. One
hypothesis: the module boots in master SPI mode; after JPROGRAM openFPGALoader
waits 120,000 TCK (20 ms at 6 MHz) before CFG_IN, and the flash now holds a
compressed 33 MHz image (`../colorlight-i9plus-idle`) that is part-way
through loading by then. A scratch openFPGALoader with a 16-TCK wait failed to
configure (DONE=0); one with 10,000 TCK (UG470's figure) is untested. Until
this is settled, a design here must not rely on INIT values.

The H2 section above predates this. Its failure (the design looked alive, the
console pin never toggled) is also what an all-ones start produces, so H2 is
unproven either way.

## Traps

- A load that loses JTAG contact after the IDCODE read still streams to 100 %
  and prints `done 1`: with TDO stuck high every status bit reads 1 (the tell
  is `isc_ena 1`), and the old design keeps running. Accept a load only when
  the console shows a fresh uptime.
- The stamp forwards no console data while JTAG data streams: pico-dirtyJtag's
  `jtag_main_task()` calls `cdc_uart_task()` only in the `else` of
  `tud_vendor_available()`, so a load overruns the 4 KB UART ring (0.36 s at
  115200) and the console comes out with gaps. Read it after a load.
