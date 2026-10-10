# Colorlight i9+ idle image (module flash)

The power-up image for an i9+ module's SPI flash (MX25L12833F, 16 MB). It
drives nothing outside the module: the module's 25 MHz clock (K4) in, and the
module LED D2 (A18) blinking at about 0.75 Hz out. Every other ball is unused,
so high-Z with no pull.

Why: the flash image travels with the MODULE. The factory image drives pins on
the fpga-db-colorlight-i5 base board (seen on the console pin), and once that
board's HDMI caps became 0 Ω links, a driven HDMI ball would go DC into the
monitor's terminations. The bring-up jig (`../colorlight-db-i9plus`) drives
the base board's LEDs, SRAM and UARTs, and those would land on whatever another
carrier wires to the same balls. This image is safe on any carrier.

The bitstream is compressed and configures at 33 MHz x1, so it boots in well
under a second. x4 would need the flash's QE bit.

    vivado -mode batch -source build.tcl
    # write the flash (persistent); back the old contents up first
    sudo openFPGALoader -c dirtyJtag --busdev-num "$(../../scripts/jtag_probe_map --busdev colorlight-db)" \
        --fpga-part xc7a50tfgg484 -f build/I9PlusIdle.bit

JTAG loading over it still works as usual (volatile; a power cycle returns to
this image).

The factory flash of the module on the base board is backed up (two identical
reads) in `~/workspaces/colorlight-db-backup/i9plus-module-factory-flash.bin`.
