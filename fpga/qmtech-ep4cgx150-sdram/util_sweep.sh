#!/bin/bash
# Utilization sweep: generate Verilog for each EP4CGX150 config variant,
# run Quartus Analysis & Synthesis (quartus_map), extract LE counts.
# Usage: ./util_sweep.sh [label ...]
# Results written to output_files/util_sweep/results.csv

set -e

REPO_ROOT="$(cd ../.. && pwd)"
QUARTUS_BIN="${QUARTUS_DIR:-/opt/altera/25.1/quartus}/bin"
BUILD_DIR="$PWD/output_files/util_sweep"
RTL_DIR="$REPO_ROOT/spinalhdl/generated"
SDRAM_IP="$REPO_ROOT/fpga/ip/altera_sdram_tri_controller"
PLL_VHDL="$PWD/dram_pll.vhd"
ETH_PLL="$PWD/pll_125.v"
SDC_FILE="$PWD/jop_sdram.sdc"

ALL_LABELS="no_icu baseline icu_full icu_dsp fcu lcu dcu all_cu eth sd_native sd_spi vga_text vga_dma eth_sd_native eth_sd_spi full"

# Allow subset of labels on command line
if [ $# -gt 0 ]; then
    LABELS="$@"
else
    LABELS="$ALL_LABELS"
fi

mkdir -p "$BUILD_DIR"

# Step 1: Generate all Verilog variants (fast, sequential via sbt)
echo "=== Generating all Verilog variants ==="
for label in $LABELS; do
    echo "--- $label ---"
    cd "$REPO_ROOT"
    sbt -error "runMain jop.system.AlteraUtilSweep $label" 2>&1 | tail -3
    # Copy the generated .v to a unique name (entity is always JopSdramTop)
    cp "$RTL_DIR/JopSdramTop.v" "$BUILD_DIR/${label}.v"
    # Also copy any .bin files needed (ROM/RAM init data)
    cp "$RTL_DIR"/JopSdramTop.v_*.bin "$BUILD_DIR/" 2>/dev/null || true
done

# Step 2: Create per-variant Quartus projects and run map
synth_one() {
    local label=$1
    local projdir="$BUILD_DIR/$label"
    mkdir -p "$projdir/output_files"

    # Copy .bin files into project directory (quartus_map looks there for $readmemb)
    cp "$BUILD_DIR"/JopSdramTop.v_*.bin "$projdir/" 2>/dev/null || true

    # Create minimal QPF
    cat > "$projdir/util_sweep.qpf" << 'QPF'
QUARTUS_VERSION = "25.1"
PROJECT_REVISION = "util_sweep"
QPF

    # Determine if this variant needs the Ethernet PLL (pll_125)
    local needs_eth_pll=""
    case "$label" in
        eth|eth_sd_native|eth_sd_spi|full) needs_eth_pll=1 ;;
    esac

    # Create QSF (minimal: device + sources, no pin assignments needed for map-only)
    cat > "$projdir/util_sweep.qsf" << QSF
set_global_assignment -name FAMILY "Cyclone IV GX"
set_global_assignment -name DEVICE EP4CGX150DF27I7
set_global_assignment -name TOP_LEVEL_ENTITY JopSdramTop
set_global_assignment -name PROJECT_OUTPUT_DIRECTORY output_files
set_global_assignment -name VERILOG_FILE ../${label}.v
set_global_assignment -name VHDL_FILE $PLL_VHDL
set_global_assignment -name VERILOG_FILE $SDRAM_IP/altera_sdram_tri_controller.v
set_global_assignment -name VERILOG_FILE $SDRAM_IP/efifo_module.v
set_global_assignment -name SDC_FILE $SDC_FILE
set_global_assignment -name STRATIX_DEVICE_IO_STANDARD "3.3-V LVCMOS"
set_global_assignment -name MIN_CORE_JUNCTION_TEMP "-40"
set_global_assignment -name MAX_CORE_JUNCTION_TEMP 100
set_global_assignment -name NOMINAL_CORE_SUPPLY_VOLTAGE 1.2V
QSF

    # Add Ethernet PLL if needed
    if [ -n "$needs_eth_pll" ]; then
        echo "set_global_assignment -name VERILOG_FILE $ETH_PLL" >> "$projdir/util_sweep.qsf"
    fi

    echo "--- Synthesizing $label ---"
    cd "$projdir"
    "$QUARTUS_BIN/quartus_map" util_sweep > /dev/null 2>&1

    local summary="$projdir/output_files/util_sweep.map.summary"
    if [ -f "$summary" ]; then
        LES=$(grep "Total logic elements" "$summary" | head -1 | sed 's/.*: *//' | sed 's/ .*//' | tr -d ',')
        COMB=$(grep "Total combinational functions" "$summary" | head -1 | sed 's/.*: *//' | sed 's/ .*//' | tr -d ',')
        REGS=$(grep "Dedicated logic registers" "$summary" | head -1 | sed 's/.*: *//' | sed 's/ .*//' | tr -d ',')
        MEM=$(grep "Total memory bits" "$summary" | head -1 | sed 's/.*: *//' | sed 's/ .*//' | tr -d ',')
        MULT=$(grep "Embedded Multiplier" "$summary" | head -1 | sed 's/.*: *//' | sed 's/ .*//' | tr -d ',')
        echo "$label,$LES,$COMB,$REGS,$MEM,$MULT"
    else
        echo "$label,FAIL,FAIL,FAIL,FAIL,FAIL"
    fi
}

echo ""
echo "=== Running Quartus Analysis & Synthesis ==="
echo "label,les,comb,regs,mem_bits,mult9" > "$BUILD_DIR/results.csv"

# Run sequentially (Quartus map is fast enough, avoids license conflicts)
for label in $LABELS; do
    result=$(synth_one "$label")
    echo "$result" >> "$BUILD_DIR/results.csv"
    echo "$result"
done

echo ""
echo "=== RESULTS ==="
column -t -s, "$BUILD_DIR/results.csv"
