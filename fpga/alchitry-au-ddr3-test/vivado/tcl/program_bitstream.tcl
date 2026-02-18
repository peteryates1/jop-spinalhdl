# Program the first detected hardware target with the DDR3 exerciser bitstream.
# Run via: vivado -mode batch -source vivado/tcl/program_bitstream.tcl

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set bit_file   [file join $repo_root vivado/build/ddr3_test/ddr3_test.runs/impl_1/Ddr3ExerciserTop.bit]

if {![file exists $bit_file]} {
  puts "ERROR: Bitstream not found: $bit_file"
  exit 1
}

open_hw_manager
connect_hw_server -allow_non_jtag

set hw_targets [get_hw_targets]
if {[llength $hw_targets] == 0} {
  puts "ERROR: No hardware targets detected (check cable/permissions/power)"
  close_hw_manager
  exit 1
}

set target [lindex $hw_targets 0]
current_hw_target $target
open_hw_target $target

set hw_devices [get_hw_devices]
if {[llength $hw_devices] == 0} {
  puts "ERROR: No hardware devices found on current target"
  close_hw_manager
  exit 1
}

set dev [lindex $hw_devices 0]
current_hw_device $dev
refresh_hw_device -update_hw_probes false $dev

puts "INFO: Programming [get_property PART $dev] ([get_property NAME $dev]) with $bit_file"
set_property PROGRAM.FILE $bit_file $dev
program_hw_devices $dev
refresh_hw_device $dev

puts "INFO: Programming complete"
close_hw_manager
