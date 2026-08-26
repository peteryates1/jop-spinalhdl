# Program the FPGA with the UART Flash Programmer bitstream.
# After loading, use flash_program.py --sst26 to program the SPI flash.

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
# The bitstream path comes from the caller. It was hardcoded to
# vivado/build/... -- the pre-build-tree location -- which after the 2026-08-26
# conversion is a stale file or none at all, and programming a stale bitstream
# succeeds silently.
if {[info exists ::env(JOP_BIT_FILE)] && $::env(JOP_BIT_FILE) ne ""} {
  set bit_file [file normalize $::env(JOP_BIT_FILE)]
} else {
  set bit_file [file join $repo_root vivado/build/flash_programmer/flash_programmer.runs/impl_1/FlashProgrammerDdr3Top.bit]
}

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

# ASSERT, do not guess. Taking [lindex ... 0] is correct only while exactly one
# target is attached, which is true on this host today -- Vivado claims the
# Alchitry's FT2232H and ignores the CYC5000's Arrow blaster and the two
# dirtyJtag Picos -- but it is true by accident, not by selection. Programming
# the wrong board succeeds silently, so this fails loudly instead.
if {[llength $hw_targets] > 1} {
  puts "ERROR: [llength $hw_targets] JTAG targets attached; refusing to guess:"
  foreach t $hw_targets { puts "         $t" }
  puts "       set JOP_HW_TARGET to the one you want."
  close_hw_manager
  exit 1
}
set target [lindex $hw_targets 0]
if {[info exists ::env(JOP_HW_TARGET)] && $::env(JOP_HW_TARGET) ne ""} {
  set target $::env(JOP_HW_TARGET)
}
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

puts "INFO: Flash programmer loaded. Use flash_program.py --sst26 to program flash."
close_hw_manager
