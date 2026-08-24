# Bitstream build script for JOP SDRAM on QMTECH XC7A100T Wukong.
# Uses non-project (in-process) flow to reduce memory footprint.

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]

# WHERE EVERYTHING LIVES. If JOP_CFG_DIR is set (by the Makefile, from
# BuildLayout) the RTL is read from and the outputs written to build/<config>/;
# otherwise the old in-tree locations are used. Parameterised so the two layouts
# coexist while boards are converted one at a time -- unset, this script behaves
# exactly as it did.
if {[info exists ::env(JOP_CFG_DIR)] && $::env(JOP_CFG_DIR) ne ""} {
    set cfg_dir   [file normalize $::env(JOP_CFG_DIR)]
    set rtl_dir   [file join $cfg_dir rtl]
    set build_dir [file join $cfg_dir vivado build]
    set xdc_file  [file join $cfg_dir vivado wukong_jop_sdram.xdc]
} else {
    set rtl_dir   [file normalize [file join $repo_root ../../spinalhdl/generated]]
    set build_dir [file normalize [file join $repo_root vivado/build/wukong_jop_sdram]]
    set xdc_file  [file join $rtl_dir wukong_jop_sdram.xdc]
}
set ip_root    [file normalize [file join $repo_root vivado/ip]]

file mkdir $build_dir

# Read IP netlist
read_ip [file join $ip_root sdr_clk/sdr_clk.xci]

# Read RTL
read_verilog [file join $rtl_dir JopSdramWukongTop.v]

# Copy $readmemb data files to build dir (Vivado resolves relative to working dir)
foreach f [glob -nocomplain [file join $rtl_dir JopSdramWukongTop.v_*.bin]] {
    file copy -force $f $build_dir
}

# Read constraints.
#
# GENERATED from JopConfig (jop.generate.XdcGenerator), not hand-maintained --
# the first board to make the switch. `make jop-sdram-generate` writes it next
# to the Verilog. Proven equivalent first: the only difference from the old
# vivado/constraints/wukong_jop_sdram.xdc was that the hand file sourced
# sdram_sdr.xdc where the generated one inlines the same two IOB constraints;
# all 45 pins matched exactly. ConstraintDriftTest keeps the two from diverging
# while the hand file still exists.
#
# To go back: point this at vivado/constraints/wukong_jop_sdram.xdc.
read_xdc $xdc_file
# Ethernet/SD pins, needed by wukongSdrFull. Harmless for configs without those
# peripherals: Vivado ignores constraints for ports that do not exist.
read_xdc [file join $repo_root vivado/constraints/wukong_peripherals.xdc]

# Synthesize
synth_design -top JopSdramWukongTop -part xc7a100tfgg676-2
write_checkpoint -force [file join $build_dir post_synth.dcp]
report_utilization -file [file join $build_dir utilization_synth.rpt]

# Implement
opt_design
place_design
route_design
write_checkpoint -force [file join $build_dir post_route.dcp]
report_utilization -file [file join $build_dir utilization_impl.rpt]
report_timing_summary -file [file join $build_dir timing_summary.rpt]

# Write bitstream
write_bitstream -force [file join $build_dir JopSdramWukongTop.bit]

source [file join $script_dir ../../../scripts/vivado_fit_summary.tcl]
puts "INFO: Bitstream at [file join $build_dir JopSdramWukongTop.bit]"
emit_fit_summary $build_dir [file join $rtl_dir JopSdramWukongTop.summary.txt]
