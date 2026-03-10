#!/bin/bash
# Utilization sweep: generate Verilog for each config variant, run Vivado synth, extract LUT counts.
# Usage: ./util_sweep.sh
# Results written to vivado/build/util_sweep/results.csv

set -e

REPO_ROOT="$(cd ../.. && pwd)"
VIVADO="/opt/xilinx/2025.2/Vivado/bin/vivado"
BUILD_DIR="$REPO_ROOT/fpga/qmtech-xc7a100t-wukong/vivado/build/util_sweep"
RTL_DIR="$REPO_ROOT/spinalhdl/generated"
IP_ROOT="$REPO_ROOT/fpga/qmtech-xc7a100t-wukong/vivado/ip"
XDC="$REPO_ROOT/fpga/qmtech-xc7a100t-wukong/vivado/constraints/wukong_ddr3_base.xdc"
export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8

LABELS="baseline icu_full icu_dsp fcu lcu dcu all_cu eth sd eth_sd full"

mkdir -p "$BUILD_DIR"

# Step 1: Generate all Verilog variants (fast, sequential via sbt)
echo "=== Generating all Verilog variants ==="
for label in $LABELS; do
    echo "--- $label ---"
    cd "$REPO_ROOT"
    sbt -error "runMain jop.system.UtilSweep $label" 2>&1 | tail -3
    # Copy the generated .v to a unique name (entity is always JopDdr3WukongTop)
    cp "$RTL_DIR/JopDdr3WukongTop.v" "$BUILD_DIR/${label}.v"
    # Also copy any .bin files needed
    cp "$RTL_DIR"/JopDdr3WukongTop.v_*.bin "$BUILD_DIR/" 2>/dev/null || true
done

# Step 2: Create per-variant TCL scripts and run synth (2 at a time)
synth_one() {
    local label=$1
    local logdir="$BUILD_DIR/$label"
    mkdir -p "$logdir"

    cat > "$logdir/synth.tcl" << TCLEOF
read_ip [glob $IP_ROOT/clk_wiz_0/clk_wiz_0.xci]
read_ip [glob $IP_ROOT/mig_7series_0/mig_7series_0.xci]
read_verilog $BUILD_DIR/${label}.v
read_xdc $XDC
synth_design -top JopDdr3WukongTop -part xc7a100tfgg676-2
report_utilization -file $logdir/util.rpt
TCLEOF

    echo "--- Synthesizing $label ---"
    $VIVADO -mode batch -notrace \
        -source "$logdir/synth.tcl" \
        -log "$logdir/vivado.log" \
        -journal "$logdir/vivado.jou" > /dev/null 2>&1

    if [ -f "$logdir/util.rpt" ]; then
        LUTS=$(grep "| Slice LUTs" "$logdir/util.rpt" | head -1 | awk '{print $4}')
        REGS=$(grep "| Slice Registers" "$logdir/util.rpt" | head -1 | awk '{print $4}')
        BRAM=$(grep "| Block RAM Tile" "$logdir/util.rpt" | head -1 | awk '{print $4}')
        DSP=$(grep "| DSPs" "$logdir/util.rpt" | head -1 | awk '{print $3}')
        echo "$label,$LUTS,$REGS,$BRAM,$DSP"
    else
        echo "$label,FAIL,FAIL,FAIL,FAIL"
    fi
}

echo ""
echo "=== Running Vivado synthesis (2 parallel) ==="
echo "label,luts,regs,bram,dsp" > "$BUILD_DIR/results.csv"

# Run 2 at a time to stay within memory
PIDS=()
TMPFILES=()
for label in $LABELS; do
    tmpf=$(mktemp)
    TMPFILES+=("$tmpf")
    synth_one "$label" > "$tmpf" &
    PIDS+=($!)

    # Wait if we have 2 running
    if [ ${#PIDS[@]} -ge 2 ]; then
        for pid in "${PIDS[@]}"; do
            wait $pid
        done
        for tf in "${TMPFILES[@]}"; do
            cat "$tf" >> "$BUILD_DIR/results.csv"
            cat "$tf"
            rm "$tf"
        done
        PIDS=()
        TMPFILES=()
    fi
done

# Wait for remaining
for pid in "${PIDS[@]}"; do
    wait $pid
done
for tf in "${TMPFILES[@]}"; do
    cat "$tf" >> "$BUILD_DIR/results.csv"
    cat "$tf"
    rm "$tf"
done

echo ""
echo "=== RESULTS ==="
column -t -s, "$BUILD_DIR/results.csv"
