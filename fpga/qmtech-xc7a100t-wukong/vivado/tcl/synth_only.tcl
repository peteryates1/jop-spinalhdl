# Synth-only script for utilization analysis.
# Usage: vivado -mode batch -tclargs <top_module> -source synth_only.tcl

set top_module [lindex $argv 0]

# THE RTL DIRECTORY IS AN ARGUMENT, NOT A DEFAULT. It used to be the single
# shared source-tree directory, so this script always found *a* netlist -- the
# one the last generator happened to leave. UtilSweep now writes one directory
# per label (build/utilsweep-<label>/rtl), so the caller must say which variant
# it means. There is deliberately no fallback: a default would synthesise a
# WELL-FORMED report for the wrong configuration, which is worse than an error.
set rtl_arg [lindex $argv 1]
if {$rtl_arg eq ""} {
    puts stderr "synth_only.tcl: no RTL directory given."
    puts stderr "  usage: vivado -mode batch -tclargs <top_module> <rtl_dir> -source synth_only.tcl"
    puts stderr "  e.g.   ... -tclargs JopDdr3WukongTop build/utilsweep-baseline/rtl ..."
    exit 1
}

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build/util_sweep]]
set rtl_dir    [file normalize [file join $repo_root ../.. $rtl_arg]]
set ip_root    [file normalize [file join $repo_root vivado/ip]]

file mkdir $build_dir

# Read IP netlists
read_ip [file join $ip_root ddr3_clk/ddr3_clk.xci]
read_ip [file join $ip_root mig_7series_0/mig_7series_0.xci]

# Read RTL
read_verilog [file join $rtl_dir ${top_module}.v]

# Read constraints
read_xdc [file join $repo_root vivado/constraints/wukong_ddr3_base.xdc]

# Synthesize
synth_design -top $top_module -part xc7a100tfgg676-2
report_utilization -file [file join $build_dir ${top_module}_util.rpt]

puts "INFO: Utilization report at [file join $build_dir ${top_module}_util.rpt]"
