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

# Vivado writes vivado.jou/vivado.log into the CWD, which for every caller here
# is a BOARD directory. Point them at the build tree instead; the repo root is
# two levels above this script's own directory (fpga/<board>/scripts/).
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LOG_DIR="${JOP_VIVADO_LOGS:-$REPO_ROOT/build/vivado-logs}"
mkdir -p "$LOG_DIR"

exec vivado -journal "$LOG_DIR/vivado.jou" -log "$LOG_DIR/vivado.log" \
     -mode batch -source "$TCL_SCRIPT" "$@"
