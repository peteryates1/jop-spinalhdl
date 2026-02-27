#!/usr/bin/env python3
"""Program an Altera FPGA via pico-fpga JTAG (binary streaming).

Usage: pico_jtag_program.py <serial_device> <rbf_file>

Sends the RBF bitstream using binary streaming protocol:
  1. JTAG PROGRAM ALTERA BEGIN <size>  →  OK STREAM <size>
  2. Raw binary data (no framing)
  3. Pico responds OK done / ERROR: ...
"""

import sys
import serial
import os
import time

WRITE_CHUNK = 32768  # bytes per write for progress display


def send_cmd(ser, cmd, timeout=5.0):
    """Send a command and return the response line."""
    ser.write((cmd + "\n").encode())
    ser.flush()
    deadline = time.time() + timeout
    while time.time() < deadline:
        line = ser.readline().decode(errors="replace").strip()
        if line:
            return line
    return "TIMEOUT"


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <serial_device> <rbf_file>")
        sys.exit(1)

    dev = sys.argv[1]
    rbf_path = sys.argv[2]

    if not os.path.exists(rbf_path):
        print(f"Error: {rbf_path} not found")
        sys.exit(1)

    rbf_data = open(rbf_path, "rb").read()
    rbf_size = len(rbf_data)
    print(f"RBF: {rbf_path} ({rbf_size} bytes)")

    ser = serial.Serial(dev, 115200, timeout=1)
    time.sleep(0.1)
    ser.reset_input_buffer()

    # Verify connection
    resp = send_cmd(ser, "PING")
    if not resp.startswith("OK"):
        print(f"PING failed: {resp}")
        sys.exit(1)
    print(f"Connected: {resp}")

    # Reset to clear any previous state
    resp = send_cmd(ser, "RESET")
    if not resp.startswith("OK"):
        print(f"RESET failed: {resp}")
        sys.exit(1)

    # Assign JTAG pins
    for pin_cmd in [
        "PIN 2 FUNC JTAG_TCK",
        "PIN 3 FUNC JTAG_TMS",
        "PIN 4 FUNC JTAG_TDI",
        "PIN 5 FUNC JTAG_TDO",
    ]:
        resp = send_cmd(ser, pin_cmd)
        if not resp.startswith("OK"):
            print(f"{pin_cmd} failed: {resp}")
            sys.exit(1)

    # Initialize JTAG
    resp = send_cmd(ser, "JTAG INIT")
    if not resp.startswith("OK"):
        print(f"JTAG INIT failed: {resp}")
        sys.exit(1)
    print(f"JTAG INIT: {resp}")

    # Detect devices
    resp = send_cmd(ser, "JTAG DETECT")
    if not resp.startswith("OK"):
        print(f"JTAG DETECT failed: {resp}")
        sys.exit(1)
    print(f"JTAG: {resp}")
    while True:
        line = ser.readline().decode(errors="replace").strip()
        if not line:
            break
        print(f"  {line}")

    # Begin programming (binary streaming)
    ser.write((f"JTAG PROGRAM ALTERA BEGIN {rbf_size}\n").encode())
    ser.flush()
    # Read response, printing DEBUG lines
    stream_resp = None
    deadline = time.time() + 10
    while time.time() < deadline:
        line = ser.readline().decode(errors="replace").strip()
        if not line:
            continue
        if line.startswith("DEBUG:"):
            print(f"  {line}")
            continue
        stream_resp = line
        break
    if not stream_resp or not stream_resp.startswith("OK STREAM"):
        print(f"PROGRAM BEGIN failed: {stream_resp}")
        sys.exit(1)
    print(f"Streaming: {stream_resp}")

    # Send raw binary data
    offset = 0
    t0 = time.time()
    while offset < rbf_size:
        end = min(offset + WRITE_CHUNK, rbf_size)
        ser.write(rbf_data[offset:end])
        offset = end
        pct = 100.0 * offset / rbf_size
        sys.stdout.write(f"\r  Sent {offset}/{rbf_size} bytes ({pct:.0f}%)")
        sys.stdout.flush()
    ser.flush()
    elapsed = time.time() - t0
    rate = rbf_size / elapsed / 1024 if elapsed > 0 else 0
    print(f"\n  Transfer: {elapsed:.1f}s ({rate:.0f} KB/s)")

    # Wait for final response (skip DEBUG lines)
    ser.timeout = 30
    result = None
    while True:
        line = ser.readline().decode(errors="replace").strip()
        if not line:
            break
        if line.startswith("DEBUG:"):
            print(f"  {line}")
            continue
        result = line
        break
    # Drain any remaining debug lines
    ser.timeout = 0.5
    while True:
        line = ser.readline().decode(errors="replace").strip()
        if not line:
            break
        if line.startswith("DEBUG:"):
            print(f"  {line}")

    if result and result.startswith("OK"):
        print(f"Success: {result}")
    else:
        print(f"Programming failed: {result}")
        sys.exit(1)

    ser.close()


if __name__ == "__main__":
    main()
