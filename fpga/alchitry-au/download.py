#!/usr/bin/env python3
"""Serial .jop download tool for JOP DDR3 on Alchitry AU V2.

Implements the JOP serial download protocol (matching down_posix.c):
  1. Parse .jop file (decimal text, skip // comments)
  2. First word = total length; verify against parsed count
  3. Send each 32-bit word as 4 bytes MSB-first, read 4-byte echo, verify
  4. Optionally monitor UART output after download (-e flag)

Usage: python3 download.py [-e] <jop_file> [serial_port] [baud_rate]
  Defaults: /dev/ttyUSB1, 1000000
  -e: Continue monitoring UART output after download
"""

import sys
import struct
import serial
import time


def parse_jop_file(filepath):
    """Parse a .jop file into a list of 32-bit unsigned words."""
    words = []
    with open(filepath, "r") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            # Strip // comments
            if "//" in line:
                line = line[: line.index("//")]
            # Strip /* */ comments (single-line only)
            while "/*" in line and "*/" in line:
                start = line.index("/*")
                end = line.index("*/") + 2
                line = line[:start] + line[end:]
            # Split on commas and whitespace
            for token in line.replace(",", " ").split():
                token = token.strip()
                if not token:
                    continue
                try:
                    val = int(token)
                    # Convert to unsigned 32-bit
                    words.append(val & 0xFFFFFFFF)
                except ValueError:
                    continue
    return words


def write32_check(ser, word):
    """Send a 32-bit word as 4 bytes MSB-first, read 4-byte echo, verify."""
    data = struct.pack(">I", word & 0xFFFFFFFF)
    ser.write(data)
    echo = ser.read(4)
    if len(echo) != 4:
        raise TimeoutError(
            f"Timeout: sent word 0x{word:08x}, got {len(echo)} echo bytes"
        )
    if echo != data:
        for i in range(4):
            if i < len(echo) and echo[i] != data[i]:
                raise ValueError(
                    f"Echo mismatch byte {i}: sent 0x{data[i]:02x}, got 0x{echo[i]:02x}"
                )


def print_progress(done, total, width=50):
    """Print a progress bar."""
    frac = done / total if total > 0 else 1.0
    filled = int(width * frac)
    bar = "#" * filled + " " * (width - filled)
    sys.stderr.write(f"\r [{bar}] {done}/{total}")
    sys.stderr.flush()


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    flags = {a for a in sys.argv[1:] if a.startswith("-")}

    echo_mode = "-e" in flags

    if len(args) < 1:
        print(
            "Usage: python3 download.py [-e] <jop_file> [serial_port] [baud_rate]",
            file=sys.stderr,
        )
        sys.exit(1)

    jop_file = args[0]
    port = args[1] if len(args) > 1 else "/dev/ttyUSB1"
    baud = int(args[2]) if len(args) > 2 else 1000000

    # Parse .jop file
    words = parse_jop_file(jop_file)
    if not words:
        print("Error: no data parsed from .jop file", file=sys.stderr)
        sys.exit(1)

    expected_len = words[0]
    if expected_len != len(words):
        print(
            f"Warning: header says {expected_len} words, parsed {len(words)}",
            file=sys.stderr,
        )

    bc_words = words[1] - 1 if len(words) > 1 else 0
    print(f"Parsed {jop_file}: {len(words)} words ({len(words) * 4 // 1024} KB)")
    print(f"  * {bc_words} words of Java bytecode ({bc_words // 256} KB)")
    print(f"  * {len(words)} words external RAM ({len(words) // 256} KB)")

    # Open serial port
    ser = serial.Serial(port, baud, timeout=2)
    ser.reset_input_buffer()
    ser.reset_output_buffer()
    print(f"Opened {ser.port} at {ser.baudrate} baud")
    print("Transmitting data via serial...")

    # Send all words with echo verification
    total = len(words)
    for i, word in enumerate(words):
        if i % 128 == 0:
            print_progress(i, total)
        write32_check(ser, word)
    print_progress(total, total)
    sys.stderr.write("\n")
    print("Done.")

    # Monitor UART output
    if echo_mode:
        print("Monitoring UART output (Ctrl+C to exit)...")
        try:
            while True:
                data = ser.read(256)
                if data:
                    sys.stdout.buffer.write(data)
                    sys.stdout.flush()
        except KeyboardInterrupt:
            print("\nDone.")
    ser.close()


if __name__ == "__main__":
    main()
