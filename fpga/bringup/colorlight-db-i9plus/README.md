# colorlight-db-i9plus — bring-up jig

Deliberately independent of the JOP build system. See ../README.md for why.

Board: fpga-db-colorlight-i5 base board (netlist 2026-10-10) carrying a
Colorlight i9+ (XC7A50T-FGG484). The XDC was rendered once from that netlist
and is now hand-maintained, as the jig rules require.

    vivado -mode batch -source build.tcl            # xc7a50tfgg484-1 by default
    openFPGALoader -c dirtyJtag --busdev-num "$(../../scripts/jtag_probe_map --busdev colorlight-db)" build/ColorlightDbBringup.bit

Console: `usb_serial_map --by-id colorlight-db-0`, 115200 8N1. It prints a
banner and runs the SRAM test at configuration time. After that, `t` re-runs
the test, `?` reprints the banner, and anything else echoes.
`colorlight-db-1` is wired straight back through the FPGA (P14 -> R14).

What to see:
- LED0-7 run a chaser. A held button shows on the LEDs instead
  ({START,SELECT,OPTION,RESET} on LED3..0, repeated on LED7..4).
- All LEDs blink after an SRAM failure.
- Module D2 blinks as a heartbeat. The RJ1 link LEDs alternate.

The SRAM test makes four passes over all 128K: hash(a), ~hash(a), a
checkerboard and its inverse. Because hash(a) changes with every address bit,
an open or shorted address line shows up as misses. The test is
simulation-checked against a model with A5 open: 0x20000 misses, all of them
in the two hash passes.

Hardware, 2026-10-10, i9+ module #2: `SRAM PASS` at power-up and on 5 more
runs; 250/250 single-byte echoes; 10,000 paced random bytes back exact; CDC1
4096 random bytes exact. Timing met at 25 MHz (WNS +30.4 ns).
On the first load only, ~29 garbage bytes arrived during the power-up test and
CDC0 was then briefly unresponsive. That did not reproduce on a re-load and is
unexplained.

Module #3, same day: IDCODE 0362C093; boot banner + `SRAM PASS`, then 5 more
PASS runs, 250/250 single echoes, 10,000 paced bytes exact, CDC1 clean. Its
capture also showed what a module's FACTORY image does to the console pin before
we load anything: bytes 00/01/02/04 at 60 Hz (every 16.7 ms), stopping when JTAG
configuration starts. That is a plausible source of module #2's first-load
garbage, but not a proven one: there the garbage arrived mid-test.

Module #1 never answered JTAG: TDO stuck at 1 while the pulls on all four pins
looked right, so its TAP never drove TDO. Still to be re-seated.
