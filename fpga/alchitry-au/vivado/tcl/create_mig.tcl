# MIG generation script for JOP DDR3 on Alchitry AU V2.
# Run with: vivado -mode batch -source vivado/tcl/create_mig.tcl

set script_dir [file dirname [file normalize [info script]]]
set board_root [file normalize [file join $script_dir ../..]]
set repo_root  [file normalize [file join $board_root ../..]]
# INPUTS stay with the board (mig.prj is a tracked source); GENERATED IP goes
# under build/, like everything else generated. Board-scoped rather than
# config-scoped because one board's IP serves every configuration built from
# it -- the Wukong's serves seven.
set src_ip     [file normalize [file join $board_root vivado/ip]]
set ip_root    [file normalize [file join $repo_root build/ip [file tail $board_root]]]
set ip_proj    [file normalize [file join $ip_root managed_ip_project]]
set mig_prj    [file join $src_ip mig.prj]
set mig_name   "mig_7series_0"

if {![file exists $mig_prj]} {
  puts "ERROR: Missing MIG project config: $mig_prj"
  exit 1
}

file mkdir $ip_root

# Keep MIG generation deterministic across repeated runs.
foreach stale [glob -nocomplain [file join $ip_root ${mig_name}*]] {
  file delete -force $stale
}
file delete -force $ip_proj

create_project -force managed_ip_project $ip_proj -part xc7a35tftg256-2

create_ip \
  -name mig_7series \
  -vendor xilinx.com \
  -library ip \
  -module_name $mig_name \
  -dir $ip_root

set mig_ip [lindex [get_ips $mig_name] 0]
set mig_xci [get_property IP_FILE $mig_ip]

# Drive the MIG configuration from version-controlled XML.
set_property -dict [list \
  CONFIG.XML_INPUT_FILE $mig_prj \
  CONFIG.RESET_BOARD_INTERFACE {Custom} \
  CONFIG.MIG_DONT_TOUCH_PARAM {Custom} \
  CONFIG.BOARD_MIG_PARAM {Custom} \
] $mig_ip

generate_target all $mig_ip

create_ip_run [get_files $mig_xci]
launch_runs -jobs 8 ${mig_name}_synth_1
wait_on_run ${mig_name}_synth_1

puts "INFO: MIG generated at [file join $ip_root $mig_name]"
puts "INFO: XCI: $mig_xci"

close_project
