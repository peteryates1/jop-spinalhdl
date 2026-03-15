#!/usr/bin/env python3
"""Control the FPGA UART MUX via Pico GP2 (CDC1 / ttyACM1).

Usage:
  uart_mux.py 0   # DDR3 on CH340, SDR on J12 (default)
  uart_mux.py 1   # SDR on CH340, DDR3 on J12
  uart_mux.py     # Query current state (reads GP2)
"""

import sys
import serial
import glob

def find_cdc1():
    """Find the Pico DirtyJTAG CDC1 port (interface 03)."""
    # CDC1 is the second CDC interface — typically ttyACM1 for the first Pico
    # Use usb_serial_map style detection if available
    candidates = sorted(glob.glob("/dev/ttyACM*"))
    # CDC1 is interface 03 — for the Pico DirtyJTAG, it's the odd-numbered port
    # (CDC0 = if01 = ttyACMx, CDC1 = if03 = ttyACMx+1)
    for i, port in enumerate(candidates):
        try:
            # Try opening and sending — CDC1 accepts control bytes
            return port if (i % 2 == 1) else None
        except:
            continue
    return candidates[1] if len(candidates) > 1 else None

def main():
    port = None
    # Find CDC1 port — second ACM port for the Pico
    import subprocess
    result = subprocess.run(
        [sys.path[0] + "/../scripts/usb_serial_map"] if False else ["true"],
        capture_output=True, text=True)

    # Simple heuristic: CDC1 for "Pico Dirty JTAG" is ttyACM1
    candidates = sorted(glob.glob("/dev/ttyACM*"))
    for c in candidates:
        # CDC1 is if03, which is the +1 port from the base
        pass

    # Default to ttyACM1 (second CDC of first Pico DirtyJTAG)
    port = "/dev/ttyACM1"
    if len(sys.argv) > 2:
        port = sys.argv[2]

    if len(sys.argv) < 2:
        print("Usage: uart_mux.py <0|1> [cdc1_port]")
        print("  0 = DDR3 on CH340, SDR on J12 (default)")
        print("  1 = SDR on CH340, DDR3 on J12")
        sys.exit(0)

    val = sys.argv[1]
    if val not in ('0', '1'):
        print(f"Error: expected 0 or 1, got '{val}'")
        sys.exit(1)

    ser = serial.Serial(port, 115200, timeout=0.5)
    ser.write(val.encode())
    ser.flush()
    ser.close()

    if val == '0':
        print(f"UART MUX → sel=0: DDR3 on CH340 (/dev/ttyUSB0), SDR on J12 (ttyACM0)")
    else:
        print(f"UART MUX → sel=1: SDR on CH340 (/dev/ttyUSB0), DDR3 on J12 (ttyACM0)")

if __name__ == "__main__":
    main()
