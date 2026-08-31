# DDR3 system clock: MIG dictates the sys_clk it wants for a given memory
# period, so this frequency is not free -- see jop.config.MigProfile. The
# generated fragment wins when a preset has been generated; the literal below is
# the stock Ddr3_400 value for standalone runs.
set ddr3_clkwiz_mhz 100.000
# THE PATH COMES FROM THE MAKEFILE, not from a guess. This used to resolve
# ../ip/generated/ddr3_clocks.tcl relative to this script -- i.e.
# fpga/<board>/vivado/ip/generated/ -- while MigProfile.emit writes to
# build/ip/<board>/generated/. Those never agreed, so `file exists` was always
# false and the literal above always won: harmless on the default 100 MHz
# preset, and silently wrong for any preset carrying another MigProfile, which
# is exactly the failure the comment above warns about.
if {[info exists ::env(JOP_MIG_TCL)]} {
    set _gen $::env(JOP_MIG_TCL)
    if {[file exists $_gen]} {
        source $_gen
        puts "INFO: clk_wiz from profile: $ddr3_clkwiz_mhz MHz ($_gen)"
    } else {
        puts "INFO: no generated MIG fragment at $_gen; using $ddr3_clkwiz_mhz MHz"
    }
} else {
    puts "INFO: JOP_MIG_TCL unset; using the stock $ddr3_clkwiz_mhz MHz"
}

# Clock wizard generation script for JOP DDR3 on QMTECH XC7A100T Wukong.
# 50 MHz input -> 100 MHz (MIG sys_clk) + 200 MHz (MIG ref_clk) + 125 MHz (ETH GMII).
# The 125 MHz output is optimized away by Vivado if unused (non-GMII builds).
# Run with: vivado -mode batch -source vivado/tcl/create_ddr3_clk_wiz.tcl

set script_dir [file dirname [file normalize [info script]]]
set board_root [file normalize [file join $script_dir ../..]]
set repo_root  [file normalize [file join $board_root ../..]]
# INPUTS stay with the board (mig.prj is tracked); GENERATED IP goes under
# build/, board-scoped because one board's IP serves every configuration built
# from it -- the Wukong's serves seven.
set src_ip     [file normalize [file join $board_root vivado/ip]]
set ip_root    [file normalize [file join $repo_root build/ip [file tail $board_root]]]
set ip_proj    [file normalize [file join $ip_root managed_ip_project]]
set clk_name   "ddr3_clk"

file mkdir $ip_root

foreach stale [glob -nocomplain [file join $ip_root ${clk_name}*]] {
  file delete -force $stale
}
file delete -force $ip_proj

create_project -force managed_ip_project $ip_proj -part xc7a100tfgg676-2

create_ip \
  -name clk_wiz \
  -vendor xilinx.com \
  -library ip \
  -module_name $clk_name \
  -dir $ip_root

set clk_ip [lindex [get_ips $clk_name] 0]
set clk_xci [get_property IP_FILE $clk_ip]

set_property -dict [list \
  CONFIG.PRIM_IN_FREQ {50.000} \
  CONFIG.PRIMARY_PORT {clk_in} \
  CONFIG.NUM_OUT_CLKS {3} \
  CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $ddr3_clkwiz_mhz \
  CONFIG.CLK_OUT1_PORT {clk_100} \
  CONFIG.CLKOUT1_DRIVES {BUFG} \
  CONFIG.CLKOUT2_USED {true} \
  CONFIG.CLKOUT2_REQUESTED_OUT_FREQ {200.000} \
  CONFIG.CLK_OUT2_PORT {clk_200} \
  CONFIG.CLKOUT2_DRIVES {BUFG} \
  CONFIG.CLKOUT3_USED {true} \
  CONFIG.CLKOUT3_REQUESTED_OUT_FREQ {125.000} \
  CONFIG.CLK_OUT3_PORT {clk_125} \
  CONFIG.CLKOUT3_DRIVES {BUFG} \
  CONFIG.USE_RESET {true} \
  CONFIG.RESET_PORT {resetn} \
  CONFIG.USE_LOCKED {true} \
  CONFIG.LOCKED_PORT {locked} \
] $clk_ip

generate_target all $clk_ip

create_ip_run [get_files $clk_xci]
launch_runs -jobs 8 ${clk_name}_synth_1
wait_on_run ${clk_name}_synth_1

puts "INFO: Clock wizard generated at [file join $ip_root $clk_name]"
puts "INFO: XCI: $clk_xci"

close_project
