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
    """Stream packed data to serial port in chunks with progress.

    Paced to the line rate. Most paths apply USB backpressure, so write()
    blocks and the pacing below sleeps ~0 — the CH340 and the DB V5's RP2040
    both behave that way. The Wukong's Pico 2 W CDC-UART bridge does not: it
    accepts 47 KB at USB speed and drops whatever its buffer cannot drain at
    115200, which showed up as "Sent in 0.1s (820 KB/s)" followed by a checksum
    timeout with 0 bytes back. Sleeping off the shortfall between elapsed and
    theoretical time self-limits to the line rate and costs nothing on paths
    that already backpressure.
    """
    total_bytes = len(data)
    sent = 0
    # 10 bits per byte on the wire: 8 data + start + stop.
    bytes_per_sec = ser.baudrate / 10.0
    start = time.time()
    while sent < total_bytes:
        chunk_end = min(sent + CHUNK_SIZE, total_bytes)
        ser.write(data[sent:chunk_end])
        sent = chunk_end
        behind = sent / bytes_per_sec - (time.time() - start)
        if behind > 0:
            time.sleep(behind)
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


RESET_MAGIC = b"R"


def send_reset(ser, baud, settle=0.4):
    """Reset the JOP core over the serial line, without reprogramming the FPGA.

    The FPGA trigger is a UART BREAK followed by 'R'. A break is a framing
    violation -- at 8N1 the longest low run a valid frame can produce is 9
    bit-times against the receiver's 13 bit-time threshold -- so no data an
    application receives can ever forge it. The confirming byte covers what a
    break alone does not: a floating line looks like an infinite break, bridges
    can glitch the line on open/close, and at a mismatched baud a 0x00 reads as
    a break.

    HOW THE BREAK IS GENERATED, and why not pyserial's send_break(). Measured
    on a CP2102N: send_break() is accepted by the ioctl and never reaches the
    wire -- the FPGA's own UartCtrl, which discards TX while a break is
    asserted, showed no suppression during a 2-second break, and the core never
    reset. Rather than depend on each bridge's break support (CP2102N, CH340,
    FT2232H and DAPLink CDC all differ), generate the break out of ORDINARY
    DATA: drop the host baud, send 0x00, restore it. At 1/16th baud a 0x00
    holds the line low for 9 host bits = 144 bit-times at the FPGA's rate, far
    past the 13 needed. Every bridge can do this, because it is just a byte.

    Requires a bitstream with UartResetEscape (2026-08-18 or later). On older
    ones it is inert: the low period is discarded and 'R' is consumed as
    ordinary input, so it is safe to send unconditionally.
    """
    low = min(9600, baud // 16)
    ser.reset_input_buffer()
    ser.baudrate = low
    ser.write(b"\x00")
    ser.flush()
    time.sleep(0.01)                 # let it clear the USB buffer and the wire
    ser.baudrate = baud
    ser.write(RESET_MAGIC)           # inside the FPGA's 100 ms window
    ser.flush()
    time.sleep(settle)               # reset hold, then boot up to the ready byte
    ser.reset_input_buffer()


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    flags = {a for a in sys.argv[1:] if a.startswith("-")}

    echo_mode = "-e" in flags
    # -r: reset the core first, so a new app can be downloaded without
    # reprogramming the FPGA. -R: reset only, download nothing.
    do_reset = "-r" in flags or "-R" in flags
    reset_only = "-R" in flags

    if len(args) < 1 and not reset_only:
        print(
            "Usage: python3 download.py [-e] [-r|-R] <jop_file> [serial_port] [baud_rate]\n"
            "  -e  monitor UART output after download\n"
            "  -r  reset the core over UART first (no FPGA reprogram needed)\n"
            "  -R  reset only, then exit (no jop_file needed)\n"
            "  Port auto-detected via usb_serial_map if not specified.",
            file=sys.stderr,
        )
        sys.exit(1)

    # With -R there is no file argument, so the positional slots shift down.
    jop_file = args[0] if not reset_only else None
    pos = args if not reset_only else [None] + args
    baud = int(pos[2]) if len(pos) > 2 else 2000000

    if len(pos) > 1 and pos[1]:
        port = pos[1]
    else:
        port = find_uart_port()
        if port is None:
            print("Error: no UART port detected; specify port explicitly",
                  file=sys.stderr)
            sys.exit(1)

    if reset_only:
        ser = serial.Serial(port, baud, timeout=2)
        ser.dtr = True
        print(f"Opened {ser.port} at {ser.baudrate} baud")
        send_reset(ser, baud)
        print("Reset sent (BREAK + 'R')")
        ser.close()
        return

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
    ser.dtr = True   # required for RP2040 DirtyJTAG CDC bridge (cdc_uart.c stops on DTR=0)
    ser.reset_input_buffer()
    ser.reset_output_buffer()
    print(f"Opened {ser.port} at {ser.baudrate} baud")

    if do_reset:
        # Before the ready handshake, not after: the reset restarts the boot
        # loader, which is what emits the 0xAA we are about to wait for.
        send_reset(ser, baud)
        print("Reset sent (BREAK + 'R')")

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
