# Bitstream build script for DDR3 GC Exerciser on Alchitry AU V2.
# Requires an existing project created via create_project_exerciser.tcl.

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build]]
set proj_name  "ddr3_gc_exerciser"
set proj_xpr   [file join $build_dir $proj_name $proj_name.xpr]

if {![file exists $proj_xpr]} {
  puts "ERROR: Project not found: $proj_xpr"
  exit 1
}

open_project $proj_xpr

# Clear incremental synthesis checkpoint (may reference deleted files)
set_property AUTO_INCREMENTAL_CHECKPOINT 0 [get_runs synth_1]
catch {set_property INCREMENTAL_CHECKPOINT {} [get_runs synth_1]}

reset_run synth_1
launch_runs synth_1 -jobs 8
wait_on_run synth_1

reset_run impl_1
launch_runs impl_1 -to_step write_bitstream -jobs 8
wait_on_run impl_1

puts "INFO: Bitstream available under [get_property DIRECTORY [get_runs impl_1]]"
