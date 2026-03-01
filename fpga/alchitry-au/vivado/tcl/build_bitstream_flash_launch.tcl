# Build flash boot bitstream using launch_runs (out-of-process).
# Avoids potential issues with in-process synth_design + MIG IP.

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

# Ensure no stale incremental checkpoints
set_property AUTO_INCREMENTAL_CHECKPOINT 0 [get_runs synth_1]
catch {set_property INCREMENTAL_CHECKPOINT {} [get_runs synth_1]}

# Reset runs to ensure clean build
reset_run synth_1
launch_runs synth_1
wait_on_run synth_1

if {[get_property STATUS [get_runs synth_1]] ne "synth_design Complete!"} {
  puts "ERROR: Synthesis failed"
  exit 1
}

# Reset and launch implementation
reset_run impl_1
launch_runs impl_1 -to_step write_bitstream
wait_on_run impl_1

if {[get_property STATUS [get_runs impl_1]] ne "write_bitstream Complete!"} {
  puts "ERROR: Implementation failed"
  exit 1
}

set bit_file [file join $build_dir $proj_name $proj_name.runs impl_1 JopDdr3Top.bit]

# Generate .bin for SPI flash
set bin_file [file join $build_dir jop_flash.bin]
if {[file exists $bit_file]} {
  write_cfgmem -format bin -interface SPIx1 -size 4 \
    -loadbit "up 0x0 $bit_file" \
    -force -file $bin_file
  puts "INFO: Flash .bin generated: $bin_file"
} else {
  puts "WARNING: Bitstream not found: $bit_file"
}

puts "INFO: Bitstream: $bit_file"
