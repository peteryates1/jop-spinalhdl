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

## JOP networking on these pins

The JOP MAC and the Java TCP/IP stack run on PHY1 (U9) through `jop.io.Rgmii100`,
which carries this design's TX timing over: TXC 90 degrees from the data, data
in IOB flops, RX on RXC's falling edge. Preset `colorlightDbI9PlusEth`. On
2026-10-11: ping, UDP and TCP echo, DHCP and DNS from the LAN; see
docs/peripherals/networking.md. Getting there also found the first of two causes
of status item 68, JOP's Ethernet RX dead on the EP4CGX150: the memory
controller re-latched every I/O read after its strobe, so the MAC's pop-on-read
RX register reached the CPU one word late. Fixed in `BmbMemoryController` on
2026-10-12; with the re-latch restored, the Wukong's GMII build links at 1000M
and receives nothing, and without it passes everything. The EP4CGX150 also had
a receive clock its generated SDC never created (item 68 again).

## H2 (PHY reset) must not be driven -- unproven

The first version held H2 low for 20 ms, then high (LiteX's `rst_n`). On
hardware the design ran and printed (its LEDs showed completed lines), yet
the stamp's RX pin (GPIO5, sampled over SWD) never toggled, and JTAG lost the
FPGA minutes later; the board needed a power cycle. A variant differing ONLY
in leaving H2 undriven works, console and JTAG included. The PHYs leave reset
on their own, so the module must hold the line high. One trial each way.

Both trials were loaded by the stock openFPGALoader, so the designs did not
start from their INIT values (see "JTAG loads landed on a running FPGA" below),
and the failure fits that as well. Retest it with a clean load before an RGMII
stage drives H2.

Also seen on the way: a module that is not fully seated fails the jig's SRAM
test (every read 0x4A) and drops JTAG; re-seating fixed both.

## Stage 2: RGMII at 100 Mb/s (`EthRgmiiTest.v`)

    vivado -mode batch -source build_rgmii.tcl      # build/EthRgmiiTest.bit
    sim/run.sh                                      # Verilator checks; ALL PASS
    sudo openFPGALoader -c dirtyJtag --busdev-num "$(../../scripts/jtag_probe_map --busdev colorlight-db)" build/EthRgmiiTest.bit
    sudo tcpdump -i <lan> -n -e -X 'udp port 5005 or ether src 02:4a:4f:50:00:01'

The load needs the patched openFPGALoader in `/usr/local/bin`. A stock v1.1.1
waits 240 ms between INIT_B and CFG_IN on dirtyJtag, and its loads here are
faulty (see "JTAG loads landed on a running FPGA" below). A clean start prints
`F 00 00 00 00 0d`.

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
- **TX works** (evening, after a clean load). TXC is clk25 shifted 90 degrees
  by an MMCM, and TXD/TX_CTL leave IOB flops on clk25 (sim: TXC edges 10 and
  30 ns into the nibble). A VM on the same switch received the frame once a
  second, 11 in 12 s, every one intact:
  `02:4a:4f:50:00:01 > ff:ff:ff:ff:ff:ff ... 192.168.0.250.5005 > 192.168.0.255.5005: UDP, length 42`
  with the payload `JOP colorlight-db i9+ RGMII stage 2 hello`. Since the switch
  forwarded the frames, the FCS is right. The console at the same time read
  `S 79 6d 85 1c 10 00 00 00 00 ad 00 00 00 53 00 ad`: link up at 100 FD,
  173 frames sent in 173 s, and PHY1 still receiving LAN traffic.
  `F 00 00 00 00 0d` means no faults, TXC at 90 degrees, TX on, loopback off.
- **The first TX version's failure is unexplained.** It used TXC = inverted
  clk25 from an ODDR, with TXD/TX_CTL from fabric flops. It sent a frame a
  second for six minutes, and the VM saw none of them. The suspect was TXC's
  falling edge sitting about 2 ns from the TX_CTL transitions: RGMII samples
  TX_EN ^ TX_ER on that edge, so the PHY would see TX_ER at the frame edges.
  That load came from the stock openFPGALoader, so its registers did not start
  from INIT either (next section). Either cause fits. The 270-degree TXC mode
  (OPTION) is untested.

## JTAG loads landed on a running FPGA -- flip-flops did not start at INIT

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

The INIT values in the routed checkpoint are right.

**Cause: the flash had already configured the FPGA when the JTAG bitstream
arrived.** The module boots in master SPI mode, so when INIT_B rises after
JPROGRAM the FPGA starts loading its flash. Every module's flash now holds the
idle image (`../colorlight-i9plus-idle`, 217 KB at 33 MHz x1), which finishes
about 55-60 ms later. openFPGALoader v1.1.1's `program_mem` polls INIT_B and
then waits `toggleClk(10000*12)` in Run-Test/Idle before CFG_IN. dirtyJtag runs
that clock at about 0.5 MHz, not the 6 MHz it shifts at, so the wait is
240 ms, not 20. The flash load finishes first. The JTAG bitstream then
reconfigures a running device: the load reports DONE=1 and the new design
runs, but its registers do not start from INIT. The uptime and TX counters
read 2 s ahead of a clean load's at the same point after it, as if the
design had already been running for part of the load.

Measured on 2026-10-11 with a scratch openFPGALoader. Its wait comes from an
environment variable, and it logs the IR capture (DONE, bit 5) just before
CFG_IN. Wall times were measured, not computed. The same bitstream was used
throughout:

| wait after INIT_B | DONE before CFG_IN | start-up record |
|---|---|---|
| 1-53 ms (16 to 25,000 TCK) | 0 | clean (`F 00 00 00 00 0d`), 14 of 14 loads |
| 58 ms (27,500 TCK) | 0; the flash load finishes during CFG_IN | faulty |
| 63 ms to 2.4 s (30,000 to 1,200,000 TCK) | 1 | faulty (`F 00 00 0f 0f 1f`), 10 of 10 |

JOP was tested with the bitstream that passed DoAll on all three modules on
2026-10-10, before the idle image went into their flash. Running DeepAll:
- loaded by the stock tool (`hw_verify.py`): 0 of 2 runs; it never got as far
  as the download's ready signal;
- loaded by the 10,000-TCK tool (20 ms wait): 2 of 2 runs, 4/4 ok, `JVM exit!`.

**So with the idle image in flash, no JTAG load from a stock openFPGALoader on
dirtyJtag is trustworthy, JOP included.**

**Fixed 2026-10-11 in the installed loader.** `/usr/local/bin/openFPGALoader`
is built from `~/workspaces/openFPGALoader`, with uncommitted changes there:
the busdev patch, and in `program_mem` the step-8 wait cut from 120,000 to
16 TCK (as the Spartan-3 path already does). INIT has already been polled, and
UG470's 10,000 TCK only cover that. It also checks DONE in the IR capture just
before CFG_IN, and if it is set it stops with "the FPGA configured itself from
its flash before CFG_IN" and exit 1. The earlier 16-TCK failure (DONE=0) was
pogo contact, not the wait. Checked with the new binary:
- this design: 4 of 4 loads clean (3 from the build directory, then 1 through
  the installed loader with the command above), and the frames reach the LAN;
- JOP: `make program`, then `hw_verify` DoAll 68/68 and DeepAll 4/4;
- the Wukong (`wukongSdram 80`, which has its own bootable flash image):
  DoAll 4 of 5, then 10 of 10 ready, the same as the old loader's 10 of 10;
- a test build with the old wait: refused 2 of 2 loads, exit 1.

The single Wukong failure was the session's first load. JOP never sent its
ready byte, and it did not happen again. On 2026-10-03 the old loader's first
Wukong run failed the same way, so the cause is open.

The other route is an idle image that boots more slowly than any loader waits,
for example uncompressed at the default 3 MHz rate. Not done.

## Traps

- A load that loses JTAG contact after the IDCODE read still streams to 100 %
  and prints `done 1`: with TDO stuck high every status bit reads 1 (the tell
  is `isc_ena 1`), and the old design keeps running. Accept a load only when
  the console shows a fresh uptime.
- The stamp forwards no console data while JTAG data streams: pico-dirtyJtag's
  `jtag_main_task()` calls `cdc_uart_task()` only in the `else` of
  `tud_vendor_available()`, so a load overruns the 4 KB UART ring (0.36 s at
  115200) and the console comes out with gaps. Read it after a load.
