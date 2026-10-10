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
    ./flash_idle.sh      # all of the steps below, each checked; prints IDLE IMAGE FLASHED AND BOOTED

By hand:

    B=$(../../scripts/jtag_probe_map --busdev colorlight-db)
    # 1. the factory leaves the WHOLE flash block-protected (status 0x3c, BP=0xF):
    #    clear it on its own first -- see the trap below
    sudo openFPGALoader -c dirtyJtag --busdev-num $B --fpga-part xc7a50tfgg484 --unprotect-flash
    # 2. write (persistent) WITH --verify; back the old contents up first
    sudo openFPGALoader -c dirtyJtag --busdev-num $B --fpga-part xc7a50tfgg484 --verify -f build/I9PlusIdle.bit
    # 3. reload from flash without a power cycle, then check DONE/CRC
    sudo openFPGALoader -c dirtyJtag --busdev-num $B -r
    sudo openFPGALoader -c dirtyJtag --busdev-num $B --fpga-part xc7a50tfgg484 --read-register STAT

**Trap: `-f --unprotect-flash` together can write NOTHING and exit 0.** openFPGALoader
v1.1.1's database entry for the MX25L12833 says `bp_len = 5` but lists four BP bit offsets,
so the protected range it computes is garbage (`00000000-3fffffff` on one run,
`c1000000-00ffffff` on the next). On the second it decided address 0 was unprotected,
skipped the unlock, and "erased" and "wrote" a chip that ignored all of it: exit 0, 100 %,
and the flash read back byte-identical to the factory image. `--verify` catches it. The
standalone `--unprotect-flash` (no file) doesn't use that range, so it works.

JTAG loading over it still works as usual (volatile; a power cycle returns to
this image).

Written 2026-10-10 to the module on the base board: `--verify` passed, an independent
read-back matched the bitstream from the sync word with the rest of the 256 KB erased,
and after `-r` the STAT register read DONE=1, no CRC error. The flash was left
UNPROTECTED (BP=0). Its factory contents are backed up (two identical 16 MB reads,
md5 29015ce7...) in `~/workspaces/colorlight-db-backup/i9plus-module-factory-flash.bin`;
to restore, unprotect and write that file back raw at offset 0.
