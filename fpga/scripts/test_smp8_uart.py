#!/usr/bin/env python3
"""
8-Core SMP UART Test via Pico Debug Probe

Verifies all 8 JOP cores are running by reading per-core UART TX lines
on JP1 header pins via the Pico's PIO UART receivers.

The Pico has 4 PIO UARTs (pio0..pio3). Each needs a TX and RX pin assigned.
We use even GP pins as RX (connected to FPGA TXD) and odd GP pins as
dummy TX (unused but required by the UART init). Two rounds of 4 UARTs:
  Round 1: cores 0-3 (RX on GP0, GP2, GP4, GP6; dummy TX on GP1, GP3, GP5, GP7)
  Round 2: cores 4-7 (RX on GP8, GP10, GP12, GP14; dummy TX on GP9, GP11, GP13, GP15)

Each core prints "C<id>\r\n" every ~500ms at 1 Mbaud.

Pico command protocol:
  RESET                              — release all pins, deinit modules
  PIN <gpio> FUNC UART_RX <pio_id>   — assign pin as UART RX
  PIN <gpio> FUNC UART_TX <pio_id>   — assign pin as UART TX
  UART <pio_id> INIT <baud>          — init PIO UART
  UART <pio_id> RECV <max> <timeout> — read received data (hex)
  UART <pio_id> DEINIT               — tear down
  PIN <gpio> RELEASE                 — release pin

Usage:
  python3 fpga/scripts/test_smp8_uart.py [--port /dev/ttyACM0]
"""

import argparse
import sys
import time
import serial


# Core-to-GP pin mapping (even GP pins = RX from FPGA TXD)
CORE_GP_RX = {
    0: 0,   1: 2,   2: 4,   3: 6,
    4: 8,   5: 10,  6: 12,  7: 14,
}

# Odd GP pins: dummy TX during UART rounds, then WD monitoring
CORE_GP_WD = {
    0: 1,   1: 3,   2: 5,   3: 7,
    4: 9,   5: 11,  6: 13,  7: 15,
}

JOP_BAUD = 1000000


def send_cmd(ser, cmd, timeout=1.0):
    """Send a command and return the response line."""
    ser.reset_input_buffer()
    ser.write((cmd + "\n").encode())
    ser.flush()

    deadline = time.time() + timeout
    while time.time() < deadline:
        if ser.in_waiting:
            line = ser.readline().decode(errors="replace").strip()
            if line:
                return line
        time.sleep(0.005)
    return None


def send_cmd_check(ser, cmd, label=""):
    """Send command, print and check for OK response."""
    resp = send_cmd(ser, cmd)
    if resp is None:
        print(f"  TIMEOUT: {cmd}")
        return False
    if not resp.startswith("OK"):
        print(f"  ERROR: {cmd} -> {resp}")
        return False
    return True


def setup_round(ser, cores):
    """Set up PIO UARTs for a group of 4 cores. Returns True on success."""
    for i, core in enumerate(cores):
        pio_id = f"pio{i}"
        rx_pin = CORE_GP_RX[core]
        tx_pin = CORE_GP_WD[core]

        # Assign pins
        if not send_cmd_check(ser, f"PIN {tx_pin} FUNC UART_TX {pio_id}"):
            return False
        if not send_cmd_check(ser, f"PIN {rx_pin} FUNC UART_RX {pio_id}"):
            return False

        # Init UART
        if not send_cmd_check(ser, f"UART {pio_id} INIT {JOP_BAUD}"):
            return False

    return True


def flush_round(ser):
    """Flush any buffered data from all 4 PIO UARTs."""
    for i in range(4):
        send_cmd(ser, f"UART pio{i} RECV 512 100", timeout=0.5)


def recv_round(ser, cores):
    """Receive data from 4 PIO UARTs, return dict of core -> decoded text."""
    results = {}
    for core in cores:
        results[core] = ""

    # Wait for fresh data, then read each UART once with a generous timeout
    time.sleep(1.0)
    for i, core in enumerate(cores):
        pio_id = f"pio{i}"
        resp = send_cmd(ser, f"UART {pio_id} RECV 128 1500", timeout=3.0)
        if resp and resp.startswith("OK"):
            hex_data = resp[3:].strip() if len(resp) > 3 else ""
            if hex_data:
                try:
                    text = bytes.fromhex(hex_data).decode(errors="replace")
                    results[core] += text
                except ValueError:
                    pass

    return results


def teardown_uarts(ser):
    """Deinit all 4 PIO UARTs, then reset."""
    for i in range(4):
        send_cmd(ser, f"UART pio{i} DEINIT", timeout=0.3)
    send_cmd_check(ser, "RESET")


def extract_core_id(text):
    """Check if text contains 'C<digit>' pattern, return set of core IDs found."""
    found = set()
    for i in range(len(text) - 1):
        if text[i] == 'C' and text[i + 1].isdigit():
            found.add(int(text[i + 1]))
    return found


def main():
    parser = argparse.ArgumentParser(description="8-Core SMP UART Test")
    parser.add_argument("--port", default="/dev/ttyACM0",
                        help="Pico serial port (default: /dev/ttyACM0)")
    parser.add_argument("--baud", type=int, default=115200,
                        help="Pico CDC baud rate (default: 115200)")
    args = parser.parse_args()

    print(f"Opening Pico on {args.port}...")
    try:
        ser = serial.Serial(args.port, args.baud, timeout=0.1)
    except serial.SerialException as e:
        print(f"ERROR: Cannot open {args.port}: {e}")
        sys.exit(1)

    time.sleep(0.5)
    ser.reset_input_buffer()

    # Verify Pico is responding
    resp = send_cmd(ser, "PING")
    if resp:
        print(f"  Pico: {resp}")
    else:
        print("  WARNING: No PING response")

    all_seen = set()

    # --- Round 1: Cores 0-3 ---
    print("\n=== Round 1: Cores 0-3 ===")
    teardown_uarts(ser)
    time.sleep(0.2)

    cores_r1 = [0, 1, 2, 3]
    print(f"  Setting up PIO UARTs (RX on GP {[CORE_GP_RX[c] for c in cores_r1]})...")
    if not setup_round(ser, cores_r1):
        print("  Setup failed!")
        ser.close()
        sys.exit(1)

    print("  Flushing stale data...")
    flush_round(ser)

    print("  Receiving data...")
    data1 = recv_round(ser, cores_r1)

    for core, text in sorted(data1.items()):
        ids = extract_core_id(text)
        display = repr(text[:80]) if text else "(empty)"
        print(f"    Core {core} (GP{CORE_GP_RX[core]}): {display}  -> IDs: {sorted(ids)}")
        all_seen |= ids

    # --- Round 2: Cores 4-7 ---
    print("\n=== Round 2: Cores 4-7 ===")
    teardown_uarts(ser)
    time.sleep(0.2)

    cores_r2 = [4, 5, 6, 7]
    print(f"  Setting up PIO UARTs (RX on GP {[CORE_GP_RX[c] for c in cores_r2]})...")
    if not setup_round(ser, cores_r2):
        print("  Setup failed!")
        ser.close()
        sys.exit(1)

    print("  Flushing stale data...")
    flush_round(ser)

    print("  Receiving data...")
    data2 = recv_round(ser, cores_r2)

    for core, text in sorted(data2.items()):
        ids = extract_core_id(text)
        display = repr(text[:80]) if text else "(empty)"
        print(f"    Core {core} (GP{CORE_GP_RX[core]}): {display}  -> IDs: {sorted(ids)}")
        all_seen |= ids

    # --- Watchdog monitoring: odd GP pins read FPGA jp1_wd[0..7] ---
    print("\n=== Watchdog Monitor ===")
    teardown_uarts(ser)
    time.sleep(0.2)

    # Configure all odd GP pins as inputs
    for core in range(8):
        gp = CORE_GP_WD[core]
        send_cmd_check(ser, f"PIN {gp} FUNC INPUT")

    # Sample each pin multiple times over ~3s to detect toggling (~500ms WD)
    NUM_SAMPLES = 8
    SAMPLE_GAP = 0.35  # seconds between full rounds
    print(f"  Sampling watchdog pins ({NUM_SAMPLES} rounds, {SAMPLE_GAP}s apart)...")
    time.sleep(0.2)

    # Collect samples: core -> set of observed values
    wd_values = {core: set() for core in range(8)}
    for _ in range(NUM_SAMPLES):
        for core in range(8):
            resp = send_cmd(ser, f"GPIO READ {CORE_GP_WD[core]}")
            if resp and resp.startswith("OK"):
                wd_values[core].add(resp.split()[-1])
        time.sleep(SAMPLE_GAP)

    wd_toggling = set()
    for core in range(8):
        vals = wd_values[core]
        toggled = "0" in vals and "1" in vals
        status = "TOGGLE" if toggled else f"STUCK({','.join(sorted(vals)) or '?'})"
        print(f"    Core {core} (GP{CORE_GP_WD[core]}): seen {sorted(vals)}  {status}")
        if toggled:
            wd_toggling.add(core)

    # Cleanup
    send_cmd_check(ser, "RESET")
    ser.close()

    # --- Summary ---
    print("\n" + "=" * 50)
    print(f"UART verified: {sorted(all_seen)}")
    print(f"WD toggling:   {sorted(wd_toggling)}")
    expected = set(range(8))
    uart_missing = expected - all_seen
    wd_missing = expected - wd_toggling
    if not uart_missing and not wd_missing:
        print("PASS: All 8 cores responding (UART + WD)!")
        sys.exit(0)
    else:
        if uart_missing:
            print(f"FAIL: UART missing cores: {sorted(uart_missing)}")
        if wd_missing:
            print(f"FAIL: WD stuck cores: {sorted(wd_missing)}")
        sys.exit(1)


if __name__ == "__main__":
    main()
