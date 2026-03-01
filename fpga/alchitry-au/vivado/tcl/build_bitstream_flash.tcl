# Bitstream build script for JOP DDR3 flash boot on Alchitry AU V2.
# Requires an existing project created via create_project_flash.tcl.
#
# After building the bitstream, generates a .bin file suitable for SPI flash
# programming via write_cfgmem.

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build]]
set proj_name  "jop_ddr3_flash"
set proj_xpr   [file join $build_dir $proj_name $proj_name.xpr]

if {![file exists $proj_xpr]} {
  puts "ERROR: Project not found: $proj_xpr"
  exit 1
}

open_project $proj_xpr

# Clear incremental synthesis checkpoint (may reference deleted files)
set_property AUTO_INCREMENTAL_CHECKPOINT 0 [get_runs synth_1]
catch {set_property INCREMENTAL_CHECKPOINT {} [get_runs synth_1]}

# Run synth + impl in-process to reduce memory usage on low-RAM systems.
# launch_runs spawns a child Vivado (2x memory); synth_design runs in-process.
synth_design
write_checkpoint -force [file join $build_dir $proj_name post_synth.dcp]

opt_design
place_design
route_design
write_checkpoint -force [file join $build_dir $proj_name post_route.dcp]
write_bitstream -force [file join $build_dir $proj_name JopDdr3Top.bit]

# Generate .bin for SPI flash programming
set bit_file [file join $build_dir $proj_name JopDdr3Top.bit]
set bin_file [file join $build_dir jop_flash.bin]

if {[file exists $bit_file]} {
  write_cfgmem -format bin -interface SPIx1 -size 4 \
    -loadbit "up 0x0 $bit_file" \
    -force -file $bin_file
  puts "INFO: Flash .bin generated: $bin_file"
} else {
  puts "WARNING: Bitstream not found, skipping write_cfgmem: $bit_file"
}

puts "INFO: Bitstream: $bit_file"
