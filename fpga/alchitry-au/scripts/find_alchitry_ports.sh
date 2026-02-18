#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
run_vivado="$repo_root/scripts/run_vivado.sh"
hw_tcl="$repo_root/vivado/tcl/check_hw_target.tcl"

mode="summary"
case "${1:-}" in
  --serial) mode="serial" ;;
  --programming) mode="programming" ;;
  --help|-h)
    cat <<'USAGE'
Usage: scripts/find_alchitry_ports.sh [--serial|--programming]

Modes:
  --serial       Print the recommended UART tty node only (e.g. /dev/ttyUSB1)
  --programming  Print the programming endpoint only (Vivado HW target if available)

Default mode prints a detailed summary.
USAGE
    exit 0
    ;;
esac

read_props() {
  local dev="$1"
  udevadm info -q property -n "$dev" 2>/dev/null || true
}

is_alchitry_ftdi_tty() {
  local dev="$1"
  local props vid pid
  props="$(read_props "$dev")"
  vid="$(awk -F= '/^ID_VENDOR_ID=/{print $2}' <<<"$props" | head -n1)"
  pid="$(awk -F= '/^ID_MODEL_ID=/{print $2}' <<<"$props" | head -n1)"
  [[ "$vid" == "0403" && "$pid" == "6010" ]]
}

get_iface_num() {
  local dev="$1"
  local props
  props="$(read_props "$dev")"
  awk -F= '/^ID_USB_INTERFACE_NUM=/{print $2}' <<<"$props" | head -n1
}

serial_candidates=()
for dev in /dev/ttyUSB* /dev/ttyACM*; do
  [[ -e "$dev" ]] || continue
  if is_alchitry_ftdi_tty "$dev"; then
    serial_candidates+=("$dev")
  fi
done

recommended_serial=""
if [[ ${#serial_candidates[@]} -gt 0 ]]; then
  # Heuristic for FT2232H dual-port boards: interface 01 is usually UART.
  for dev in "${serial_candidates[@]}"; do
    if [[ "$(get_iface_num "$dev")" == "01" ]]; then
      recommended_serial="$dev"
      break
    fi
  done
  if [[ -z "$recommended_serial" ]]; then
    recommended_serial="${serial_candidates[0]}"
  fi
fi

vivado_target=""
if [[ "$mode" != "serial" ]]; then
  if [[ -x "$run_vivado" && -f "$hw_tcl" ]]; then
    vivado_out="$("$run_vivado" "$hw_tcl" 2>/dev/null || true)"
    vivado_target="$(awk '/^HW_TARGET /{sub(/^HW_TARGET /, ""); print; exit}' <<<"$vivado_out")"
  fi
fi

usb_match=""
if [[ "$mode" != "serial" ]]; then
  if command -v lsusb >/dev/null 2>&1; then
    usb_match="$(lsusb 2>/dev/null | awk '/0403:6010/{print; exit}' || true)"
  fi
fi

if [[ "$mode" == "serial" ]]; then
  if [[ -n "$recommended_serial" ]]; then
    echo "$recommended_serial"
    exit 0
  fi
  echo "No matching Alchitry/FTDI serial tty node found." >&2
  exit 1
fi

if [[ "$mode" == "programming" ]]; then
  if [[ -n "$vivado_target" ]]; then
    echo "$vivado_target"
    exit 0
  fi
  if [[ -n "$usb_match" ]]; then
    echo "$usb_match"
    exit 0
  fi
  echo "No programming endpoint found (Vivado HW target or FTDI USB match)." >&2
  exit 1
fi

echo "Alchitry FTDI USB:"
if [[ -n "$usb_match" ]]; then
  echo "  $usb_match"
else
  echo "  (not found)"
fi

echo
if [[ ${#serial_candidates[@]} -gt 0 ]]; then
  echo "Serial candidates:"
  for dev in "${serial_candidates[@]}"; do
    iface="$(get_iface_num "$dev")"
    if [[ "$dev" == "$recommended_serial" ]]; then
      echo "  $dev (recommended, interface=${iface:-??})"
    else
      echo "  $dev (interface=${iface:-??})"
    fi
  done
else
  echo "Serial candidates: (none)"
fi

echo
if [[ -n "$vivado_target" ]]; then
  echo "Programming endpoint (Vivado HW target):"
  echo "  $vivado_target"
else
  echo "Programming endpoint (Vivado HW target): (not found)"
fi
