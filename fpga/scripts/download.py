#!/usr/bin/env python3
"""Serial .jop download tool for JOP FPGA boards.

Implements the JOP serial download protocol (matching down_posix.c):
  1. Parse .jop file (decimal text, skip // comments)
  2. First word = total length; verify against parsed count
  3. Send each 32-bit word as 4 bytes MSB-first, read 4-byte echo, verify
  4. Optionally monitor UART output after download (-e flag)

Usage: python3 download.py [-e] <jop_file> [serial_port] [baud_rate]
  Defaults: auto-detect FPGA UART port, 1000000 baud
  -e: Continue monitoring UART output after download

Auto-detection uses usb_serial_map (if available) to find FPGA UART ports,
filtering out JTAG interfaces. Supports Alchitry Au V2, Arrow USB Blaster,
and CP2102N UART bridges.
"""

import os
import sys
import struct
import subprocess
import serial


def find_uart_port():
    """Auto-detect FPGA UART port using usb_serial_map.

    Calls usb_serial_map --if01-only to list UART ports (excluding JTAG
    interfaces on FT2232 chips). Returns the first matching port, or None.
    """
    script_dir = os.path.dirname(os.path.abspath(__file__))
    map_script = os.path.join(script_dir, "usb_serial_map")
    if not os.path.isfile(map_script):
        return None

    try:
        result = subprocess.run(
            [map_script, "--if01-only"],
            capture_output=True, text=True, timeout=5
        )
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return None

    if result.returncode != 0:
        return None

    ports = []
    for line in result.stdout.strip().splitlines():
        if line.startswith("SerialPort") or line.startswith("-"):
            continue
        fields = line.split()
        if fields and fields[0].startswith("/dev/"):
            ports.append((fields[0], " ".join(fields[4:6]) if len(fields) > 5 else ""))

    if len(ports) == 1:
        port, desc = ports[0]
        print(f"Auto-detected: {port} ({desc})")
        return port

    if len(ports) > 1:
        print("Multiple UART ports detected:", file=sys.stderr)
        for port, desc in ports:
            print(f"  {port}  {desc}", file=sys.stderr)
        print("Specify port explicitly, e.g.: download.py <file> /dev/ttyUSB2",
              file=sys.stderr)
        return None

    return None


def parse_jop_file(filepath):
    """Parse a .jop file into a list of 32-bit unsigned words."""
    words = []
    with open(filepath, "r") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if "//" in line:
                line = line[: line.index("//")]
            while "/*" in line and "*/" in line:
                start = line.index("/*")
                end = line.index("*/") + 2
                line = line[:start] + line[end:]
            for token in line.replace(",", " ").split():
                token = token.strip()
                if not token:
                    continue
                try:
                    val = int(token)
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
            "Usage: python3 download.py [-e] <jop_file> [serial_port] [baud_rate]\n"
            "  Port auto-detected via usb_serial_map if not specified.",
            file=sys.stderr,
        )
        sys.exit(1)

    jop_file = args[0]
    baud = int(args[2]) if len(args) > 2 else 1000000

    if len(args) > 1:
        port = args[1]
    else:
        port = find_uart_port()
        if port is None:
            print("Error: no UART port detected; specify port explicitly",
                  file=sys.stderr)
            sys.exit(1)

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

    ser = serial.Serial(port, baud, timeout=2)
    ser.reset_input_buffer()
    ser.reset_output_buffer()
    print(f"Opened {ser.port} at {ser.baudrate} baud")
    print("Transmitting data via serial...")

    total = len(words)
    for i, word in enumerate(words):
        if i % 128 == 0:
            print_progress(i, total)
        write32_check(ser, word)
    print_progress(total, total)
    sys.stderr.write("\n")
    print("Done.")

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
