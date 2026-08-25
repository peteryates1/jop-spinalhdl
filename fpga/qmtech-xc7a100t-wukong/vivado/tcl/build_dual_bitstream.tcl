# Bitstream build script for dual-cluster JOP (DDR3 + SDR SDRAM) on QMTECH XC7A100T Wukong.
# Uses non-project (in-process) flow to reduce memory footprint.

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
if {[info exists ::env(JOP_CFG_DIR)] && $::env(JOP_CFG_DIR) ne ""} {
    set cfg_dir   [file normalize $::env(JOP_CFG_DIR)]
    set rtl_dir   [file join $cfg_dir rtl]
    set build_dir [file join $cfg_dir vivado build]
} else {
    set build_dir [file normalize [file join $repo_root vivado/build/wukong_jop_dual_np]]
    set rtl_dir   [file normalize [file join $repo_root ../../spinalhdl/generated]]
}
set ip_root    [file normalize [file join $repo_root vivado/ip]]

file mkdir $build_dir

# Read IP netlists (DDR3 ClkWiz + MIG + SDR ClkWiz)
read_ip [file join $ip_root ddr3_clk/ddr3_clk.xci]
read_ip [file join $ip_root sdr_clk/sdr_clk.xci]
read_ip [file join $ip_root mig_7series_0/mig_7series_0.xci]

# Read RTL
read_verilog [file join $rtl_dir JopDualWukongTop.v]

# Read constraints
read_xdc [file join $repo_root vivado/constraints/wukong_dual.xdc]

# Synthesize (performance-optimized: retiming, resource sharing, LUT combining)
synth_design -top JopDualWukongTop -part xc7a100tfgg676-2 \
  -directive PerformanceOptimized -retiming
write_checkpoint -force [file join $build_dir post_synth.dcp]
report_utilization -file [file join $build_dir utilization_synth.rpt]

# Post-synthesis timing constraints — IPs are now resolved, internal pins available.
# These MUST be applied here because the non-project flow doesn't reliably defer
# constraints that reference IP-internal pins (clkout2_buf, mmcm_adv_inst, etc.).

# SDR SDRAM timing strategy:
# All SDRAM data I/O registers are in the clk_100_sdr_clk (80 MHz system) domain.
# clk_100_shift_sdr_clk only drives the sdram_clk output pad (phase-shifted -108°).
# The fixed phase relationship (3.0ns lead at 100 MHz) provides setup/hold margin at the
# SDRAM chip. This is an absolute time but is specified in degrees, so it scales with the
# period -- changing the SDR clock without re-deriving the phase detunes it.
# IOB packing (in XDC) ensures minimal I/O delay for all SDRAM pins.
#
# We do NOT use set_output_delay/set_input_delay with the phase-shifted clock because
# Vivado picks pathological near-coincident edges (0.091ns requirement at cycle ~97)
# between clk_100 and clk_100_shift, causing -10ns timing violations that corrupt
# the optimizer's placement decisions.
#
# Instead: constrain max data path delay from clk_100 registers to SDRAM output pins.
# This ensures Vivado keeps SDRAM output registers near the I/O bank.
set sdram_all_out [get_ports {sdram_ADDR[*] sdram_BA[*] sdram_CKE sdram_CSn sdram_RASn sdram_CASn sdram_WEn sdram_DQM[*] sdram_DQ[*]}]
set_max_delay 5.0 -datapath_only -from [get_clocks clk_100_sdr_clk] -to $sdram_all_out
set_max_delay 5.0 -datapath_only -from [get_ports {sdram_DQ[*]}] -to [get_clocks clk_100_sdr_clk]

# Async clock groups: all three system clocks are mutually asynchronous (CDC via BufferCC)
#   sys_clk:           50 MHz board clock (HangDetector, heartbeat)
#   clk_pll_i:         DDR3 MIG ui_clk (~100 MHz, DDR3 cluster)
#   clk_100_sdr_clk: 100 MHz SDR system clock (SDR cluster)
# clk_100_shift_sdr_clk is same group as clk_100_sdr_clk (same MMCM, output-only)
set_clock_groups -asynchronous \
  -group [get_clocks sys_clk] \
  -group [get_clocks clk_pll_i] \
  -group [get_clocks {clk_100_sdr_clk clk_100_shift_sdr_clk}]

# Implement (aggressive timing closure)
opt_design -directive Explore
place_design -directive ExtraTimingOpt
phys_opt_design -directive AggressiveExplore
route_design -directive AggressiveExplore
phys_opt_design -directive AggressiveExplore
write_checkpoint -force [file join $build_dir post_route.dcp]
report_utilization -file [file join $build_dir utilization_impl.rpt]
report_timing_summary -file [file join $build_dir timing_summary.rpt]

# Write bitstream
write_bitstream -force [file join $build_dir JopDualWukongTop.bit]

source [file join $script_dir ../../../scripts/vivado_fit_summary.tcl]
puts "INFO: Bitstream at [file join $build_dir JopDualWukongTop.bit]"
emit_fit_summary $build_dir [file join $rtl_dir JopDualWukongTop.summary.txt]
