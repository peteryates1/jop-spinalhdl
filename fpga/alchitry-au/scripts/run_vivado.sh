#!/usr/bin/env bash
set -euo pipefail

# Override with: VIVADO_HOME=/path/to/Vivado
VIVADO_HOME="${VIVADO_HOME:-/opt/xilinx/2025.2/Vivado}"
SETTINGS_SH="$VIVADO_HOME/settings64.sh"

if [[ ! -f "$SETTINGS_SH" ]]; then
  echo "ERROR: Vivado settings script not found at: $SETTINGS_SH" >&2
  exit 1
fi

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <tcl_script> [vivado args...]" >&2
  echo "Example: $0 vivado/tcl/create_project.tcl" >&2
  exit 1
fi

TCL_SCRIPT="$1"
shift

if [[ ! -f "$TCL_SCRIPT" ]]; then
  echo "ERROR: TCL script not found: $TCL_SCRIPT" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "$SETTINGS_SH"

exec vivado -mode batch -source "$TCL_SCRIPT" "$@"
