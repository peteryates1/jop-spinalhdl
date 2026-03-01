# Indirect SPI flash programming via JTAG for JOP flash boot.
# Programs a combined flash image (bitstream + JOP app) into the
# on-board SPI flash via Vivado's indirect programming.
#
# Flash: SST26VF032BT-104I/MF (Microchip, 32Mbit/4MB)
#
# WARNING: The SST26VF032B may not be in Vivado's standard cfgmem_parts
# list.  If get_cfgmem_parts fails, use the UART flash programmer instead
# (FlashProgrammerDdr3Top + flash_program.py with --sst26 flag).
#
# NOTE: The SST26VF032B ships with global block protection enabled.
# A ULBPR (0x98) command must be sent before erase/write operations.

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build]]
set flash_bin  [file join $build_dir flash_image.bin]

# Alchitry Au V2 flash: SST26VF032B (Microchip, 32Mbit/4MB, SPI x1)
# If Vivado doesn't recognize this part name, try:
#   get_cfgmem_parts *sst*    (in Vivado TCL console)
set flash_part "sst26vf032b-spi-x1_x2_x4"

if {![file exists $flash_bin]} {
  puts "ERROR: Flash image not found: $flash_bin"
  puts "Run 'make flash-image' first."
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

puts "INFO: Programming flash on [get_property PART $dev] ([get_property NAME $dev])"
puts "INFO: Flash part: $flash_part"
puts "INFO: Image: $flash_bin"

create_hw_cfgmem -hw_device $dev \
  -mem_dev [lindex [get_cfgmem_parts $flash_part] 0]

set cfgmem [current_hw_cfgmem]
set_property PROGRAM.FILES [list $flash_bin] $cfgmem
set_property PROGRAM.ADDRESS_RANGE {use_file} $cfgmem
set_property PROGRAM.ERASE 1 $cfgmem
set_property PROGRAM.CFG_PROGRAM 1 $cfgmem
set_property PROGRAM.VERIFY 1 $cfgmem
set_property PROGRAM.CHECKSUM 0 $cfgmem

program_hw_cfgmem $cfgmem

puts "INFO: Flash programming complete"
close_hw_manager
