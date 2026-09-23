# Clock wizard generation script for SDRAM on QMTECH XC7A100T Wukong.
# 50 MHz input -> N MHz system + N MHz phase-shifted SDRAM + 125 MHz ETH GMII.
# The 125 MHz output is optimized away by Vivado if unused (non-GMII builds).
#
# Run with: vivado -mode batch -source vivado/tcl/create_sdram_clk_wiz.tcl
#           vivado -mode batch -source ... -tclargs -freq 80 -ip_root <dir>
#
# WHY THE FREQUENCY IS A PARAMETER. The 2-core SMP SDR build does not close
# timing at 100 MHz -- WNS -1.451 ns even on the closure directives, on the
# combinational ready path core -> memCtrl -> BMB arbiter -> SDRAM controller
# -> core FSM (status items 5 and 31). It needs a slower clock, and a slower
# clock needs its own IP: regenerating THIS instance at 80 would leave the
# single-core SDR and exerciser flows silently running a 100 MHz constraint
# against an 80 MHz part. So each frequency gets its own `ip_root`, and the
# flow that wants one points JOP_IP at it.
#
# THE PHASE SHIFT IS A TIME, NOT AN ANGLE. -108 deg at 100 MHz is -3.0 ns, and
# that number is physical: board trace plus the SDRAM's own setup/hold. Carry
# the ANGLE to another frequency and the delay moves with it -- -108 deg at
# 80 MHz is -3.75 ns, a 0.75 ns error that appears as read corruption rather
# than as a build failure. So the shift is held in ns and converted here.
set script_dir [file dirname [file normalize [info script]]]
set board_root [file normalize [file join $script_dir ../..]]
set repo_root  [file normalize [file join $board_root ../..]]
# INPUTS stay with the board (mig.prj is tracked); GENERATED IP goes under
# build/, board-scoped because one board's IP serves every configuration built
# from it -- the Wukong's serves seven.
set src_ip     [file normalize [file join $board_root vivado/ip]]
set ip_root    [file normalize [file join $repo_root build/ip [file tail $board_root]]]
set clk_name   "sdr_clk"
set sys_mhz    100.000
set shift_ns   -3.0

for {set i 0} {$i < [llength $argv]} {incr i} {
  switch -- [lindex $argv $i] {
    -freq    { set sys_mhz [expr {double([lindex $argv [incr i]])}] }
    -ip_root { set ip_root [file normalize [lindex $argv [incr i]]] }
    -shift   { set shift_ns [expr {double([lindex $argv [incr i]])}] }
  }
}

# Degrees for the requested delay at the requested frequency.
set period_ns  [expr {1000.0 / $sys_mhz}]
set shift_deg  [expr {360.0 * $shift_ns / $period_ns}]
puts "create_sdram_clk_wiz: ${sys_mhz} MHz, SDRAM shift ${shift_ns} ns = ${shift_deg} deg, ip_root ${ip_root}"

set ip_proj    [file normalize [file join $ip_root managed_ip_project]]

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

# THE PORT NAMES ARE NOT A FREQUENCY CLAIM. clk_100 / clk_100_shift are what
# SdramExerciserClkWiz (Board.scala) declares, so they are fixed by the RTL and
# not by this script -- at `-freq 80` the port called clk_100 carries 80 MHz.
# Renaming them means renaming them in the BlackBox too, which regenerates the
# 100 MHz instance and so re-verifies the exerciser and single-core SDR flows.
# Left as a wart deliberately. The frequency that DECIDES anything is the
# preset's, and the XDC is generated from that.
set_property -dict [list \
  CONFIG.PRIM_IN_FREQ {50.000} \
  CONFIG.PRIMARY_PORT {clk_in} \
  CONFIG.NUM_OUT_CLKS {3} \
  CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $sys_mhz \
  CONFIG.CLK_OUT1_PORT {clk_100} \
  CONFIG.CLKOUT1_DRIVES {BUFG} \
  CONFIG.CLKOUT2_USED {true} \
  CONFIG.CLKOUT2_REQUESTED_OUT_FREQ $sys_mhz \
  CONFIG.CLKOUT2_REQUESTED_PHASE $shift_deg \
  CONFIG.CLK_OUT2_PORT {clk_100_shift} \
  CONFIG.CLKOUT2_DRIVES {BUFG} \
  CONFIG.CLKOUT3_USED {true} \
  CONFIG.CLKOUT3_REQUESTED_OUT_FREQ {125.000} \
  CONFIG.CLK_OUT3_PORT {clk_125} \
  CONFIG.CLKOUT3_DRIVES {BUFG} \
  CONFIG.USE_RESET {true} \
  CONFIG.RESET_TYPE {ACTIVE_LOW} \
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
