# Colorlight i5 idle image (module flash)

The power-up image for a Colorlight i5 module's SPI flash (GD25Q16, 2 MB, JEDEC
`C8 40 15`). It drives one ball: the module LED D2 (U16), blinking at about
0.75 Hz from the module's 25 MHz clock (P3). U16 also reaches SODIMM pin 41,
which has no net on the fpga-db-colorlight-i5 base board; the ext-board JOP
build drives it as its LED. Every other ball is unused, so it is an input. Its
USERCODE is `0x1D1E0005` ("IDLE", i5), so a JTAG read proves that this image is
the one running.

Why: the flash image travels with the MODULE. On 2026-10-27 the i5 went onto the
second fpga-db-colorlight-i5 base board and booted an unknown image from its
flash: DONE set, USERCODE 0, an uncompressed 572 KB bitstream plus data blocks
up to 0x130000. Our own builds are compressed and about 100 KB, so it was not
one of ours. On that board the RP2040-STAMP drives GPIO4 (SODIMM 155) and GPIO16
(SODIMM 127), and the reset button pulls SODIMM 42 to GND, so any design that
drives one of those balls fights. The same thing happened with the i9+, whose
factory image drove the console pin; see `../colorlight-i9plus-idle`. This
image is safe on both carriers; on any other, check SODIMM 41.

    make                                              # build/I5Idle.bit
    make check CABLE="-c dirtyJtag --busdev-num B:D"  # read-only: USERCODE + DONE
    make sram  CABLE=...                              # load into the FPGA (volatile)
    make flash CABLE=...                              # write the flash with --verify

CABLE is whatever reaches the module. On the second base board it is that
board's stamp, `-c dirtyJtag --busdev-num B:D`: take B:D from sysfs for USB
serial `4636323337058419`, because that board has no `jtag_probe_map` alias
yet. On the ext board it is `-b colorlight-i5`.

The flow is yosys, then nextpnr-ecp5 (`--seed 1`), then ecppack. It is
deterministic: the build reproduced the flashed image byte for byte (sha256
`0ed48461d924ec80...`).

## Before the first write: back the flash up

Read it twice and compare:

    openFPGALoader $CABLE --dump-flash --file-size 2097152 --skip-reset read1.bin
    openFPGALoader $CABLE --dump-flash --file-size 2097152 --skip-reset read2.bin
    cmp read1.bin read2.bin

Use `--skip-reset`. Every openFPGALoader flash access clears the FPGA's SRAM
first, and without `--skip-reset` it ends with a REFRESH, which boots the OLD
image again. With it, the FPGA stays cleared and drives nothing until the write
REFRESHes it into this image.

## Checks

`check_idle.svf` reads USERCODE (`0xC0`) and the status register's DONE bit
(`0x3C`, bit 8). It is pass/fail. When a value does not match, openFPGALoader
prints the TDO value it read, but without zero-padding each byte (0x00000000
prints as `0000`), so don't try to read a number from that output.

After `make flash` the check can only pass if the FPGA booted this image FROM
FLASH. The write cleared SRAM before it started, so the USERCODE cannot be left
over from an earlier SRAM load.

## What a read-back looks like

openFPGALoader drops the `.bit` file's 29-byte text header
(`Part: LFE5U-25F-6CABGA381`) and writes `0xFF` followed by the bitstream from
its sync word, so `FF FF BD B3` sits at offset 1. A read-back therefore matches
`I5Idle.bit` from byte 29, not byte 0. The image it replaced had the same
layout.

## History

Written 2026-10-27 to the i5 on the second base board, through its stamp:

- `--verify` passed.
- An independent read-back matched the bitstream from the sync word, with the
  rest of the erased 128 KB blank.
- After the write's REFRESH, and again after a read's REFRESH, USERCODE read
  `0x1D1E0005` with DONE set.
- After a cold power cycle of the board, D2 blinked, and `make check` passed
  (USERCODE `0x1D1E0005`, DONE set).

Only 0 to 0x20000 was erased. The rest of the old image is still in the flash
above that, and the FPGA never reads it.

The original contents are backed up in
`~/workspaces/colorlight-db-backup/i5-module-original-flash-2M.bin`: two
identical 2 MB reads, md5 `4bce954153bc0513ccc5f4b2ba225262`, also listed in
that directory's MD5SUMS. To restore, write that file back raw at offset 0.
