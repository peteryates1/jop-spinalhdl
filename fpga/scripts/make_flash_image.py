#!/usr/bin/env python3
"""
Create a combined flash image for JOP autonomous boot.

Combines an FPGA bitstream (.rbf or .rpd) and a JOP application (.jop) into
a single binary image for programming into the W25Q128 SPI config flash.

Flash layout:
    0x000000  FPGA bitstream              ~4.7 MB
    <offset>  JOP application (.jop)       variable
    ...       (free)                       remainder of 16 MB

RPD files from Quartus are padded to full flash size (e.g. 16 MB for EPCS128).
Trailing 0xFF bytes are stripped automatically.

The bitstream is bit-reversed (within each byte) for Active Serial
configuration.  RPD/RBF store data in Passive Serial bit order (LSB-first
per byte), but the Cyclone IV AS controller reads from SPI flash MSB-first.
Quartus's built-in SFL programmer does this reversal automatically; our
custom UART-SPI flash programmer writes raw bytes, so we must reverse here.

The .jop file is a text file with one decimal 32-bit word per line.
The first word is the application size (in words). Words are stored
in the flash image as big-endian 4-byte values.

Usage:
    make_flash_image.py <bitstream> <app.jop> <output.bin> [--jop-offset 0x800000]
"""

import argparse
import struct
import sys
import os

# Bit-reversal table for Active Serial configuration.
# RPD/RBF use Passive Serial bit order (LSB-first per byte).
# The Cyclone IV AS controller reads SPI flash MSB-first, so each byte
# must be bit-reversed before programming.
_BITREV = bytes(int(f'{i:08b}'[::-1], 2) for i in range(256))


def main():
    parser = argparse.ArgumentParser(
        description="Create combined flash image (bitstream + JOP application)"
    )
    parser.add_argument("bitstream", help="FPGA bitstream (.rbf or .rpd)")
    parser.add_argument("jop", help="JOP application (.jop text file)")
    parser.add_argument("output", help="Output flash image (.bin)")
    parser.add_argument(
        "--jop-offset",
        default="0x800000",
        help="Flash offset for .jop data (default: 0x800000 = 8 MB)",
    )
    parser.add_argument(
        "--jop-binary",
        metavar="FILE",
        help="Also write raw .jop binary data to FILE (for Quartus .cof)",
    )
    args = parser.parse_args()

    jop_offset = int(args.jop_offset, 0)

    # Read bitstream
    with open(args.bitstream, "rb") as f:
        bs_data = f.read()

    raw_size = len(bs_data)

    # Strip trailing 0xFF bytes (RPD files are padded to full flash size)
    bs_data = bs_data.rstrip(b"\xFF")
    bs_size = len(bs_data)
    if raw_size != bs_size:
        print(
            f"Bitstream: {args.bitstream} ({raw_size} bytes on disk, "
            f"{bs_size} bytes after stripping 0xFF padding, "
            f"{bs_size / 1024 / 1024:.2f} MB)"
        )
    else:
        print(f"Bitstream: {args.bitstream} ({bs_size} bytes, {bs_size / 1024 / 1024:.2f} MB)")

    # Bit-reverse each byte for Active Serial configuration
    bs_data = bytes(_BITREV[b] for b in bs_data)
    print(f"  Bit-reversed for Active Serial mode")

    if bs_size > jop_offset:
        print(
            f"ERROR: Bitstream ({bs_size} bytes) exceeds JOP offset ({jop_offset} = 0x{jop_offset:X})",
            file=sys.stderr,
        )
        sys.exit(1)

    # Read .jop file (text format with decimal words, commas, and // comments)
    # Lines may contain: multiple comma-separated values, trailing comments,
    # pure comment lines, or blank lines.
    # Examples:
    #   8545,//  length of the application in words
    #   0, 0,   //  16: MIN_VALUEJ
    #   157698,
    #   // comment only
    import re
    words = []
    with open(args.jop, "r") as f:
        for line in f:
            # Strip comments
            if "//" in line:
                line = line[: line.index("//")]
            # Extract all integers (possibly negative)
            for m in re.finditer(r"-?\d+", line):
                words.append(int(m.group()))

    jop_size_bytes = len(words) * 4
    print(
        f"JOP app:   {args.jop} ({len(words)} words, {jop_size_bytes} bytes, "
        f"{jop_size_bytes / 1024:.1f} KB)"
    )

    if len(words) > 0:
        print(f"  First word (size): {words[0]}")

    # Convert .jop words to big-endian binary
    jop_binary = b""
    for w in words:
        # Handle signed 32-bit values
        jop_binary += struct.pack(">I", w & 0xFFFFFFFF)

    # Write raw .jop binary if requested (for Quartus .cof flow)
    if args.jop_binary:
        with open(args.jop_binary, "wb") as f:
            f.write(jop_binary)
        print(f"JOP binary: {args.jop_binary} ({jop_size_bytes} bytes)")

    # Create output image
    # Start with bitstream, pad with 0xFF to jop_offset, append jop data
    padding_size = jop_offset - bs_size
    output = bs_data + (b"\xFF" * padding_size) + jop_binary

    total_size = len(output)
    flash_size = 16 * 1024 * 1024  # W25Q128 = 16 MB
    if total_size > flash_size:
        print(
            f"WARNING: Image ({total_size} bytes) exceeds flash size ({flash_size} bytes)",
            file=sys.stderr,
        )

    with open(args.output, "wb") as f:
        f.write(output)

    print(f"Output:    {args.output} ({total_size} bytes, {total_size / 1024 / 1024:.2f} MB)")
    print(f"Layout:")
    print(f"  0x{0:06X}-0x{bs_size - 1:06X}  Bitstream ({bs_size} bytes)")
    print(f"  0x{bs_size:06X}-0x{jop_offset - 1:06X}  Padding ({padding_size} bytes, 0xFF)")
    print(
        f"  0x{jop_offset:06X}-0x{jop_offset + jop_size_bytes - 1:06X}  "
        f"JOP app ({jop_size_bytes} bytes)"
    )
    remaining = flash_size - total_size
    if remaining > 0:
        print(f"  0x{total_size:06X}-0x{flash_size - 1:06X}  Free ({remaining} bytes)")


if __name__ == "__main__":
    main()
