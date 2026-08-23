# Bitstream build script for JOP SMP DDR3 on QMTECH XC7A100T Wukong.
# Uses non-project (in-process) flow to reduce memory footprint.

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
# Both are overridable so an A/B (e.g. blocking vs non-blocking L2) can build in
# parallel from separate copies of the generated RTL instead of racing over the
# one shared spinalhdl/generated directory.
if {[info exists ::env(JOP_BUILD_DIR)]} {
  set build_dir [file normalize $::env(JOP_BUILD_DIR)]
} else {
  set build_dir [file normalize [file join $repo_root vivado/build/wukong_jop_ddr3_smp_np]]
}
if {[info exists ::env(JOP_RTL_DIR)]} {
  set rtl_dir [file normalize $::env(JOP_RTL_DIR)]
} else {
  set rtl_dir [file normalize [file join $repo_root ../../spinalhdl/generated]]
}
puts "INFO: build_dir = $build_dir"
puts "INFO: rtl_dir   = $rtl_dir"
set ip_root    [file normalize [file join $repo_root vivado/ip]]

file mkdir $build_dir

# Read IP netlists
read_ip [file join $ip_root ddr3_clk/ddr3_clk.xci]
read_ip [file join $ip_root mig_7series_0/mig_7series_0.xci]

# Read RTL
read_verilog [file join $rtl_dir JopSmpDdr3WukongTop.v]

# Read constraints (read base + full separately; XDC source doesn't resolve in non-project mode)
read_xdc [file join $repo_root vivado/constraints/wukong_ddr3_base.xdc]
read_xdc [file join $repo_root vivado/constraints/wukong_ddr3.xdc]
# Shared constraints, read here rather than `source`d from inside an xdc --
# Vivado ignores that and only logs a CRITICAL WARNING, so these had never
# been applied to any build (item 58).
read_xdc [file join $repo_root ../constraints/rtl8211eg_gmii.xdc]

# Synthesize (performance-optimized: retiming, resource sharing, LUT combining)
synth_design -top JopSmpDdr3WukongTop -part xc7a100tfgg676-2 \
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

# Waive combinatorial loop DRC from SpinalHDL StreamFifoLowLatency
set_property IS_ENABLED FALSE [get_drc_checks LUTLP-1]

# Write bitstream
write_bitstream -force [file join $build_dir JopSmpDdr3WukongTop.bit]

source [file join $script_dir ../../../scripts/vivado_fit_summary.tcl]
puts "INFO: Bitstream at [file join $build_dir JopSmpDdr3WukongTop.bit]"
emit_fit_summary $build_dir [file join $rtl_dir JopSmpDdr3WukongTop.summary.txt]
