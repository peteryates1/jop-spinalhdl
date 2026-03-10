# Synth-only script for utilization analysis.
# Usage: vivado -mode batch -tclargs <top_module> -source synth_only.tcl

set top_module [lindex $argv 0]

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build/util_sweep]]
set rtl_dir    [file normalize [file join $repo_root ../../spinalhdl/generated]]
set ip_root    [file normalize [file join $repo_root vivado/ip]]

file mkdir $build_dir

# Read IP netlists
read_ip [file join $ip_root clk_wiz_0/clk_wiz_0.xci]
read_ip [file join $ip_root mig_7series_0/mig_7series_0.xci]

# Read RTL
read_verilog [file join $rtl_dir ${top_module}.v]

# Read constraints
read_xdc [file join $repo_root vivado/constraints/wukong_ddr3_base.xdc]

# Synthesize
synth_design -top $top_module -part xc7a100tfgg676-2
report_utilization -file [file join $build_dir ${top_module}_util.rpt]

puts "INFO: Utilization report at [file join $build_dir ${top_module}_util.rpt]"
