#!/usr/bin/env python3
"""Test SDRAM write with chunked sending (like download.py) vs byte-by-byte."""

import sys
import struct
import time
import serial

READY_BYTE = 0xAA
READY_ACK = 0x55

def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyACM0"
    baud = int(sys.argv[2]) if len(sys.argv) > 2 else 2000000
    num_words = int(sys.argv[3]) if len(sys.argv) > 3 else 11083
    chunk_size = int(sys.argv[4]) if len(sys.argv) > 4 else 4096

    ser = serial.Serial(port, baud, timeout=0.1)
    ser.reset_input_buffer()
    print(f"Opened {port} at {baud} baud, chunk_size={chunk_size}")

    # Wait for 0xAA ready
    print("Waiting for ready (0xAA)...", end="", flush=True)
    t0 = time.monotonic()
    while time.monotonic() - t0 < 10:
        b = ser.read(1)
        if b and b[0] == READY_BYTE:
            print(f" got it after {time.monotonic()-t0:.1f}s")
            break
    else:
        print(" TIMEOUT")
        ser.close()
        return

    # Send ACK
    ser.write(bytes([READY_ACK]))
    ser.flush()
    time.sleep(0.05)
    ser.reset_input_buffer()
    print(f"Sent ACK (0x55)")

    # Build data
    words = [num_words]
    for i in range(1, num_words):
        words.append(0xDEAD0000 + (i & 0xFFFF))

    checksum = 0
    data = bytearray(num_words * 4)
    for i, w in enumerate(words):
        struct.pack_into(">I", data, i * 4, w)
        checksum ^= w
    checksum &= 0xFFFFFFFF
    data = bytes(data)

    print(f"Sending {num_words} words ({len(data)} bytes) in {chunk_size}-byte chunks...")
    t_start = time.monotonic()

    # Send in chunks (matching download.py behavior)
    sent = 0
    while sent < len(data):
        chunk_end = min(sent + chunk_size, len(data))
        ser.write(data[sent:chunk_end])
        sent = chunk_end
    ser.flush()

    elapsed = time.monotonic() - t_start
    print(f"All data sent in {elapsed*1000:.1f}ms ({len(data)/elapsed/1024:.0f} KB/s)")
    print(f"Expected checksum: 0x{checksum:08X}")

    # Wait for checksum response
    print("Waiting for 4-byte checksum...", end="", flush=True)
    ser.timeout = 10
    resp = ser.read(4)
    elapsed = time.monotonic() - t_start

    if len(resp) == 4:
        fpga_cksum = struct.unpack(">I", resp)[0]
        print(f" got 0x{fpga_cksum:08X} after {elapsed*1000:.0f}ms")
        if fpga_cksum == checksum:
            print("CHECKSUM MATCH!")
        else:
            print(f"CHECKSUM MISMATCH (expected 0x{checksum:08X})")
    elif len(resp) > 0:
        print(f" got {len(resp)} bytes: {resp.hex()}")
    else:
        print(f" TIMEOUT (0 bytes after 10s)")

    ser.close()


if __name__ == "__main__":
    main()
