#!/usr/bin/env python3
"""Stream a synthetic N-word image and check the XOR checksum JOP sends back.

Isolates the serial-boot download path (UART -> microcode -> cache -> DRAM)
from anything Java: no .jop file, no class loading, no Startup. JOP's download
loop takes the first word as the image size, writes N words to external memory
from address 0, and replies with a 4-byte XOR checksum MSB-first — so a correct
checksum proves every word reached memory.

Written to bisect the A-E115FB DDR2 download hang, which turned out to sit at
exactly 8193 words: 8192 words = 32 KB = the full LruCacheCore (4 ways x 256
sets x 32 B), making word 8192 the first write that must evict a dirty line.
See docs/boards/ae115fb-ddr2-bringup.md.

The FPGA accepts one download per configuration, so reprogram between runs:

  for N in 8192 8193; do
    (cd fpga/a-e115fb-ddr2 && quartus_pgm -c "$CABLE" -m JTAG \
       -o "p;output_files/jop_ddr2.sof" >/dev/null)
    python3 fpga/scripts/download_sizetest.py $N /dev/ttyUSB0 115200
  done

Usage: download_sizetest.py <n_words> [port] [baud]
"""

import importlib.util
import os
import struct
import sys
import time

import serial

_HERE = os.path.dirname(os.path.abspath(__file__))


def _load_download_module():
    """Reuse download.py's ready handshake rather than duplicating it."""
    spec = importlib.util.spec_from_file_location(
        "download", os.path.join(_HERE, "download.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2

    n = int(args[0])
    port = args[1] if len(args) > 1 else "/dev/ttyUSB0"
    baud = int(args[2]) if len(args) > 2 else 115200

    # First word is the image size; the rest are distinguishable filler so a
    # partial or duplicated write shows up as a checksum mismatch.
    words = [n] + [(0x01000000 + i) & 0xFFFFFFFF for i in range(n - 1)]
    expected = 0
    for w in words:
        expected ^= w
    data = b"".join(struct.pack(">I", w) for w in words)

    dl = _load_download_module()
    ser = serial.Serial(port, baud, timeout=2)
    if not dl.wait_for_ready(ser):
        print(f"N={n}: no ready byte", file=sys.stderr)
        return 2

    t0 = time.monotonic()
    ser.write(data)
    ser.flush()

    ser.timeout = 8
    reply = ser.read(4)
    elapsed = time.monotonic() - t0

    if len(reply) != 4:
        print(f"N={n} ({len(data)} B): HANG — got {len(reply)}/4 checksum "
              f"bytes after {elapsed:.1f}s")
        return 1

    got = struct.unpack(">I", reply)[0]
    ok = got == expected
    print(f"N={n} ({len(data)} B): checksum 0x{got:08x} expected "
          f"0x{expected:08x} {'MATCH' if ok else 'MISMATCH'} [{elapsed:.1f}s]")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
