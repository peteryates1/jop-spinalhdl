# Bitstream build script for JOP DDR3 on QMTECH XC7A100T Wukong.
# Uses non-project (in-process) flow to reduce memory footprint.

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build/wukong_jop_ddr3_np]]
set rtl_dir    [file normalize [file join $repo_root ../../spinalhdl/generated]]
set ip_root    [file normalize [file join $repo_root vivado/ip]]

file mkdir $build_dir

# Read IP netlists
read_ip [file join $ip_root clk_wiz_0/clk_wiz_0.xci]
read_ip [file join $ip_root mig_7series_0/mig_7series_0.xci]

# Read RTL
read_verilog [file join $rtl_dir JopDdr3WukongTop.v]

# Read constraints (read base + full separately; XDC source doesn't resolve in non-project mode)
read_xdc [file join $repo_root vivado/constraints/wukong_ddr3_base.xdc]
read_xdc [file join $repo_root vivado/constraints/wukong_ddr3.xdc]

# Synthesize (performance-optimized: retiming, resource sharing, LUT combining)
synth_design -top JopDdr3WukongTop -part xc7a100tfgg676-2 \
  -directive PerformanceOptimized -retiming
write_checkpoint -force [file join $build_dir post_synth.dcp]
report_utilization -file [file join $build_dir utilization_synth.rpt]

# Implement (aggressive timing closure)
opt_design -directive Explore
place_design -directive ExtraTimingOpt
phys_opt_design -directive AggressiveExplore
route_design -directive AggressiveExplore
phys_opt_design -directive AggressiveExplore
write_checkpoint -force [file join $build_dir post_route.dcp]
report_utilization -file [file join $build_dir utilization_impl.rpt]
report_timing_summary -file [file join $build_dir timing_summary.rpt]

# Waive combinatorial loop DRC from SpinalHDL StreamFifoLowLatency (SD native controller)
# These transparent-latch loops are functionally correct but flagged by Xilinx DRC.
set_property IS_ENABLED FALSE [get_drc_checks LUTLP-1]

# Write bitstream
write_bitstream -force [file join $build_dir JopDdr3WukongTop.bit]

puts "INFO: Bitstream at [file join $build_dir JopDdr3WukongTop.bit]"
