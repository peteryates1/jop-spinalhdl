# Bitstream build script for JOP DDR3 on QMTECH XC7A100T Wukong.
# Uses non-project (in-process) flow to reduce memory footprint.

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
# WHERE EVERYTHING LIVES. JOP_CFG_DIR (from the Makefile, via BuildLayout) puts
# the RTL and the outputs under build/<config>/; unset, the old in-tree paths
# are used, so unconverted flows are unaffected.
if {[info exists ::env(JOP_CFG_DIR)] && $::env(JOP_CFG_DIR) ne ""} {
    set cfg_dir   [file normalize $::env(JOP_CFG_DIR)]
    set rtl_dir   [file join $cfg_dir rtl]
    set build_dir [file join $cfg_dir vivado build]
    # PINS are generated; the hand-written files that remain carry TIMING
    # EXCEPTIONS and the PHY interface, which are not derivable from the board
    # data -- see docs/architecture/peripheral-portability-plan.md.
    set gen_pins  [file join $cfg_dir vivado wukong_ddr3_base.xdc]
} else {
    set build_dir [file normalize [file join $repo_root vivado/build/wukong_jop_ddr3_np]]
    set rtl_dir   [file normalize [file join $repo_root ../../spinalhdl/generated]]
    set gen_pins  ""
}
set ip_root    [file normalize [file join $repo_root vivado/ip]]

file mkdir $build_dir

# Read IP netlists
read_ip [file join $ip_root ddr3_clk/ddr3_clk.xci]
read_ip [file join $ip_root mig_7series_0/mig_7series_0.xci]

# Read RTL
read_verilog [file join $rtl_dir JopDdr3WukongTop.v]

# Read constraints (read base + full separately; XDC source doesn't resolve in non-project mode)
# GMII constraints FIRST: rtl8211eg_gmii.xdc does `create_clock -name e_rxc`,
# and wukong_ddr3.xdc below references [get_clocks e_rxc] in its
# set_clock_groups. Read the other way round, that get_clocks matches
# nothing ("WARNING: [Vivado 12-627] No clocks matched 'e_rxc'") and the
# asynchronous exclusion silently does not apply -- so the RX crossings get
# analysed as real paths and the build reports a violation it should not.
# Read here rather than `source`d from inside an xdc: Vivado ignores that
# and only logs a CRITICAL WARNING (item 58).
read_xdc [file join $repo_root ../constraints/rtl8211eg_gmii.xdc]
read_xdc [expr {$gen_pins ne "" ? $gen_pins : [file join $repo_root vivado/constraints/wukong_ddr3_base.xdc]}]
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

source [file join $script_dir ../../../scripts/vivado_fit_summary.tcl]
puts "INFO: Bitstream at [file join $build_dir JopDdr3WukongTop.bit]"
emit_fit_summary $build_dir [file join $rtl_dir JopDdr3WukongTop.summary.txt]
