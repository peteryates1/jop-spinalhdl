# Clock wizard generation script for JOP DDR3 on Alchitry AU V2.
# Run with: vivado -mode batch -source vivado/tcl/create_clk_wiz.tcl

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set ip_root    [file normalize [file join $repo_root vivado/ip]]
set ip_proj    [file normalize [file join $ip_root managed_ip_project]]
set clk_name   "clk_wiz_0"

file mkdir $ip_root

foreach stale [glob -nocomplain [file join $ip_root ${clk_name}*]] {
  file delete -force $stale
}
file delete -force $ip_proj

create_project -force managed_ip_project $ip_proj -part xc7a35tftg256-2

create_ip \
  -name clk_wiz \
  -vendor xilinx.com \
  -library ip \
  -module_name $clk_name \
  -dir $ip_root

set clk_ip [lindex [get_ips $clk_name] 0]
set clk_xci [get_property IP_FILE $clk_ip]

set_property -dict [list \
  CONFIG.PRIM_IN_FREQ {100.000} \
  CONFIG.PRIMARY_PORT {clk_in} \
  CONFIG.CLKOUT2_USED {true} \
  CONFIG.NUM_OUT_CLKS {2} \
  CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {100.000} \
  CONFIG.CLKOUT2_REQUESTED_OUT_FREQ {200.000} \
  CONFIG.CLK_OUT1_PORT {clk_100} \
  CONFIG.CLK_OUT2_PORT {clk_200} \
  CONFIG.USE_RESET {true} \
  CONFIG.RESET_PORT {resetn} \
  CONFIG.USE_LOCKED {true} \
  CONFIG.LOCKED_PORT {locked} \
  CONFIG.CLKOUT1_DRIVES {BUFG} \
  CONFIG.CLKOUT2_DRIVES {BUFG} \
] $clk_ip

generate_target all $clk_ip

create_ip_run [get_files $clk_xci]
launch_runs -jobs 8 ${clk_name}_synth_1
wait_on_run ${clk_name}_synth_1

puts "INFO: Clock wizard generated at [file join $ip_root $clk_name]"
puts "INFO: XCI: $clk_xci"

close_project
