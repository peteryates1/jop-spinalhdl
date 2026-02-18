#!/usr/bin/env bash
set -u

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
run_vivado="$repo_root/scripts/run_vivado.sh"
hw_tcl="$repo_root/vivado/tcl/check_hw_target.tcl"

found_usb=0
found_tty=0
found_jtag=0

print_header() {
  echo
  echo "== $1 =="
}

print_header "Timestamp"
date -Is

print_header "USB (FTDI / Alchitry)"
if command -v lsusb >/dev/null 2>&1; then
  usb_lines="$(lsusb | grep -Ei '0403:6010|ftdi|alchitry' || true)"
  if [[ -n "$usb_lines" ]]; then
    found_usb=1
    echo "$usb_lines"
  else
    echo "No matching FTDI/Alchitry USB device found via lsusb."
  fi
else
  echo "lsusb not found."
fi

print_header "Serial Nodes"
serial_nodes=()
for dev in /dev/ttyUSB* /dev/ttyACM*; do
  [[ -e "$dev" ]] || continue
  serial_nodes+=("$dev")
done

if [[ ${#serial_nodes[@]} -eq 0 ]]; then
  echo "No /dev/ttyUSB* or /dev/ttyACM* nodes found."
else
  found_tty=1
  for dev in "${serial_nodes[@]}"; do
    resolved="$(readlink -f "$dev" 2>/dev/null || echo "$dev")"
    echo "$dev -> $resolved"
    if command -v udevadm >/dev/null 2>&1; then
      props="$(udevadm info -q property -n "$dev" 2>/dev/null | grep -E '^(ID_VENDOR_ID|ID_MODEL_ID|ID_SERIAL_SHORT|ID_MODEL|ID_VENDOR)=' || true)"
      if [[ -n "$props" ]]; then
        while IFS= read -r p; do
          echo "  $p"
        done <<< "$props"
      fi
    fi
  done
fi

print_header "Persistent Serial IDs"
if [[ -d /dev/serial/by-id ]]; then
  ls -l /dev/serial/by-id 2>/dev/null || true
else
  echo "/dev/serial/by-id not present."
fi

print_header "Vivado JTAG Probe"
if [[ -x "$run_vivado" && -f "$hw_tcl" ]]; then
  vivado_out="$("$run_vivado" "$hw_tcl" 2>&1)"
  echo "$vivado_out"
  if grep -q '^HW_DEVICE ' <<< "$vivado_out"; then
    found_jtag=1
  fi
else
  echo "Missing $run_vivado or $hw_tcl."
fi

print_header "Summary"
echo "USB match found:   $found_usb"
echo "TTY nodes found:   $found_tty"
echo "JTAG device found: $found_jtag"

if [[ $found_usb -eq 0 && $found_tty -eq 0 && $found_jtag -eq 0 ]]; then
  exit 2
fi
exit 0
