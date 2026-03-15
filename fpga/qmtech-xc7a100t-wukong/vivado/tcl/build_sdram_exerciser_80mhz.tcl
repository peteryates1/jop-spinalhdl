# SDRAM exerciser at 80 MHz using clk_wiz_1 (SDR SDR clock from dual-cluster build)

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build/wukong_sdram_exerciser]]
set rtl_dir    [file normalize [file join $repo_root ../../spinalhdl/generated]]
set ip_root    [file normalize [file join $repo_root vivado/ip]]

file mkdir $build_dir

# Read IP netlist (clk_wiz_1 at 80 MHz instead of clk_wiz_0 at 100 MHz)
read_ip [file join $ip_root clk_wiz_1/clk_wiz_1.xci]

# Read RTL (modified to instantiate clk_wiz_1)
read_verilog [file join $rtl_dir SdramExerciserWukongTop_80mhz.v]

# Read constraints
read_xdc [file join $repo_root vivado/constraints/wukong_sdram.xdc]

# Synthesize
synth_design -top SdramExerciserWukongTop -part xc7a100tfgg676-2
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
write_bitstream -force [file join $build_dir SdramExerciserWukongTop.bit]

puts "INFO: Bitstream at [file join $build_dir SdramExerciserWukongTop.bit]"
