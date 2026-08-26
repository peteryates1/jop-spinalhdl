# ---------------------------------------------------------------------------
# Run a Vivado PROJECT-mode build, once. Sibling of vivado_create_project.tcl.
#
#   JOP_PROJ       project name                              (required)
#   JOP_BUILD_DIR  directory the project lives under         (required)
#   JOP_JOBS       parallel jobs, default 8                  (optional)
#   JOP_BIT        bitstream file name, for the closing line (optional)
#   JOP_SUMMARY    path to write a fit summary to            (optional)
#
# WHY THIS EXISTS. Eight of these existed across four boards. They differed in
# the project name and the closing message -- and in one thing that was never a
# decision: four disabled incremental synthesis
#
#     set_property AUTO_INCREMENTAL_CHECKPOINT 0 [get_runs synth_1]
#     catch {set_property INCREMENTAL_CHECKPOINT {} [get_runs synth_1]}
#
# and four did not. Incremental synthesis reuses a previous checkpoint when
# Vivado judges the design close enough, which is exactly what you do NOT want
# when the question is "does this change still fit and still meet timing" --
# the answer can come back from a stale checkpoint. That divergence was drift,
# not intent, so it is applied uniformly here. A build that reuses a checkpoint
# silently is the same failure shape as a fit report that lost its provenance
# header, or a .cdf that named the wrong revision.
# ---------------------------------------------------------------------------

proc jop_env {name {default ""}} {
  if {[info exists ::env($name)] && $::env($name) ne ""} { return $::env($name) }
  return $default
}

set proj_name [jop_env JOP_PROJ]
set build_dir [jop_env JOP_BUILD_DIR]
if {$proj_name eq "" || $build_dir eq ""} {
  puts "ERROR: JOP_PROJ and JOP_BUILD_DIR must be set (vivado_build_project.tcl)"
  exit 1
}
set build_dir [file normalize $build_dir]
set jobs      [jop_env JOP_JOBS 8]
set bit_name  [jop_env JOP_BIT]
set summary   [jop_env JOP_SUMMARY]

set proj_xpr [file join $build_dir $proj_name $proj_name.xpr]
if {![file exists $proj_xpr]} {
  puts "ERROR: Project not found: $proj_xpr"
  puts "       run the board's create-project target first"
  exit 1
}

open_project $proj_xpr

# Always a full synthesis -- see the header.
set_property AUTO_INCREMENTAL_CHECKPOINT 0 [get_runs synth_1]
catch {set_property INCREMENTAL_CHECKPOINT {} [get_runs synth_1]}

reset_run synth_1
launch_runs synth_1 -jobs $jobs
wait_on_run synth_1

reset_run impl_1
launch_runs impl_1 -to_step write_bitstream -jobs $jobs
wait_on_run impl_1

set impl_dir [get_property DIRECTORY [get_runs impl_1]]
if {$bit_name ne ""} {
  puts "INFO: Bitstream at [file join $impl_dir $bit_name]"
} else {
  puts "INFO: Bitstream under $impl_dir"
}

# The fit summary carries the utilisation and timing numbers hw_verify.py reads.
# Only emitted when asked: the bring-up jigs have nothing worth recording.
if {$summary ne ""} {
  open_run impl_1
  source [file join [file dirname [file normalize [info script]]] vivado_fit_summary.tcl]
  emit_fit_summary $build_dir $summary
}
