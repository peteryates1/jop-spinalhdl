#!/usr/bin/env python3
"""Serial .jop download tool for JOP FPGA boards.

Streaming protocol with ready handshake, XOR checksum + retry:
  1. Parse .jop file (decimal text, skip // comments)
  2. First word = total length; verify against parsed count
  3. Wait for FPGA ready byte (0xAA), send ACK (0x55)
  4. Stream all 32-bit words as 4 bytes MSB-first (no per-byte echo)
  5. Read 4-byte XOR checksum from FPGA, verify against host-computed
  6. Send ACK (0x00) on match, NACK (0xFF) on mismatch; NACK triggers retry
  7. Optionally monitor UART output after download (-e flag)

The ready handshake solves DDR3 timing: MIG calibration holds the JOP
processor in reset for several seconds after FPGA programming. The FPGA
sends 0xAA periodically once ready; the host waits for it before streaming.

Usage: python3 download.py [-e] <jop_file> [serial_port] [baud_rate]
  Defaults: auto-detect FPGA UART port, 2000000 baud
  -e: Continue monitoring UART output after download

Auto-detection uses usb_serial_map (if available) to find FPGA UART ports,
filtering out JTAG interfaces. Supports Alchitry Au V2, Arrow USB Blaster,
and CP2102N UART bridges.
"""

import os
import sys
import struct
import time
import subprocess
import serial


MAX_RETRIES = 3
CHUNK_SIZE = 4096  # bytes per write() call for efficient buffering
READY_BYTE = 0xAA  # FPGA sends this when ready
READY_ACK = 0x55   # Host sends this to acknowledge
READY_TIMEOUT = 30  # seconds to wait for FPGA ready


def find_uart_port():
    """Auto-detect FPGA UART port using usb_serial_map."""
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


def pack_words(words):
    """Pack word list into bytes (MSB-first) and compute XOR checksum."""
    checksum = 0
    data = bytearray(len(words) * 4)
    for i, word in enumerate(words):
        struct.pack_into(">I", data, i * 4, word)
        checksum ^= word
    return bytes(data), checksum & 0xFFFFFFFF


def print_progress(done, total, width=50):
    """Print a progress bar."""
    frac = done / total if total > 0 else 1.0
    filled = int(width * frac)
    bar = "#" * filled + " " * (width - filled)
    sys.stderr.write(f"\r [{bar}] {done}/{total}")
    sys.stderr.flush()


def wait_for_ready(ser):
    """Wait for FPGA ready byte (0xAA), then send ACK (0x55).

    The FPGA sends 0xAA every ~500 ms once it has booted and memory is
    ready. We wait up to READY_TIMEOUT seconds, consuming and discarding
    any non-0xAA bytes (noise, stale data from prior runs).

    Returns True if handshake succeeded, False on timeout.
    """
    ser.reset_input_buffer()
    ser.timeout = 0.1  # 100 ms poll intervals
    t0 = time.monotonic()
    dots = 0

    sys.stderr.write("Waiting for FPGA ready ")
    sys.stderr.flush()

    while time.monotonic() - t0 < READY_TIMEOUT:
        b = ser.read(1)
        if b and b[0] == READY_BYTE:
            # Got 0xAA — send ACK
            ser.write(bytes([READY_ACK]))
            ser.flush()
            sys.stderr.write(" ready!\n")
            sys.stderr.flush()
            # Drain any remaining 0xAA bytes from the USB serial pipeline.
            # The FPGA may have sent multiple 0xAA bytes before seeing
            # our 0x55 ACK; those bytes can be in-flight in the FTDI
            # chip's buffer or the USB transfer pipeline.
            time.sleep(0.05)
            ser.reset_input_buffer()
            # Active drain: keep reading until 20ms of silence
            ser.timeout = 0.02
            while ser.read(64):
                pass
            ser.timeout = 0.1
            return True

        # Print a dot every ~1 second
        elapsed = time.monotonic() - t0
        if int(elapsed) > dots:
            dots = int(elapsed)
            sys.stderr.write(".")
            sys.stderr.flush()

    sys.stderr.write(f" timeout ({READY_TIMEOUT}s)\n")
    sys.stderr.flush()
    return False


def stream_download(ser, data, total_words):
    """Stream packed data to serial port in chunks with progress."""
    total_bytes = len(data)
    sent = 0
    while sent < total_bytes:
        chunk_end = min(sent + CHUNK_SIZE, total_bytes)
        ser.write(data[sent:chunk_end])
        sent = chunk_end
        words_sent = sent // 4
        if words_sent % 128 == 0 or sent == total_bytes:
            print_progress(words_sent, total_words)
    print_progress(total_words, total_words)
    sys.stderr.write("\n")
    # Wait for all data to be transmitted
    ser.flush()


def verify_checksum(ser, expected_checksum):
    """Read 4-byte XOR checksum from FPGA, compare, send ACK/NACK."""
    # Give FPGA time to process the last bytes and compute checksum
    ser.timeout = 10  # generous timeout for checksum response
    cksum_bytes = ser.read(4)
    if len(cksum_bytes) != 4:
        print(f"  Checksum timeout: got {len(cksum_bytes)} bytes", file=sys.stderr)
        return False

    fpga_checksum = struct.unpack(">I", cksum_bytes)[0]
    if fpga_checksum == expected_checksum:
        ser.write(b'\x00')  # ACK
        ser.flush()
        return True
    else:
        print(f"  Checksum mismatch: expected 0x{expected_checksum:08x}, "
              f"got 0x{fpga_checksum:08x}", file=sys.stderr)
        ser.write(b'\xff')  # NACK
        ser.flush()
        return False


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
    baud = int(args[2]) if len(args) > 2 else 2000000

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
    total_bytes = len(words) * 4
    print(f"Parsed {jop_file}: {len(words)} words ({total_bytes // 1024} KB)")
    print(f"  * {bc_words} words of Java bytecode ({bc_words * 4 // 1024} KB)")
    print(f"  * {len(words)} words external RAM ({total_bytes // 1024} KB)")

    data, checksum = pack_words(words)

    ser = serial.Serial(port, baud, timeout=2)
    ser.reset_input_buffer()
    ser.reset_output_buffer()
    print(f"Opened {ser.port} at {ser.baudrate} baud")

    for attempt in range(1, MAX_RETRIES + 1):
        if attempt > 1:
            print(f"Retry {attempt}/{MAX_RETRIES}...")

        # Wait for FPGA ready handshake
        if not wait_for_ready(ser):
            print("Error: FPGA not responding (no ready signal)",
                  file=sys.stderr)
            ser.close()
            sys.exit(1)

        t0 = time.monotonic()
        print(f"Streaming {total_bytes} bytes...")
        stream_download(ser, data, len(words))
        elapsed = time.monotonic() - t0
        rate = total_bytes / elapsed / 1024
        print(f"Sent in {elapsed:.1f}s ({rate:.0f} KB/s). Verifying checksum...")

        if verify_checksum(ser, checksum):
            print(f"Download OK (checksum 0x{checksum:08x})")
            break
        else:
            if attempt == MAX_RETRIES:
                print(f"Download failed after {MAX_RETRIES} attempts", file=sys.stderr)
                ser.close()
                sys.exit(1)
    else:
        ser.close()
        sys.exit(1)

    if echo_mode:
        ser.timeout = 0.1  # short timeout so partial reads flush promptly
        print("Monitoring UART output (Ctrl+C to exit)...")
        try:
            while True:
                data = ser.read(1)  # block for first byte (up to timeout)
                if data:
                    data += ser.read(ser.in_waiting)  # grab any remaining
                    sys.stdout.buffer.write(data)
                    sys.stdout.flush()
        except KeyboardInterrupt:
            print("\nDone.")
    ser.close()


if __name__ == "__main__":
    main()
