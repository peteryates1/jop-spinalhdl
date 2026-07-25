# Shared Vivado fit-summary emitter.
#
# Writes a concise <build_dir>/fit_summary.txt with FPGA resource utilization
# (Slice LUTs / Registers / Block RAM / DSPs, used + %) and setup/hold slack
# (WNS/WHS, MET/VIOLATED), and echoes it to the log. Optionally prepends the
# generation-time config summary (spinalhdl/generated/<Top>.summary.txt).
#
# Usage (non-project flow — routed design open in memory after route_design):
#   source [file join $script_dir ../../../scripts/vivado_fit_summary.tcl]
#   emit_fit_summary $build_dir [file join $rtl_dir <Top>.summary.txt]
#
# Usage (project flow): open_run impl_1 first, then call emit_fit_summary.

proc _fit_util_row {rpt name} {
  foreach line [split $rpt "\n"] {
    if {[regexp "\\| ${name} +\\| +(\[0-9.\]+) +\\|.*\\| +(\[0-9.\]+) +\\|" $line -> used pct]} {
      return [format "%-8s (%s%%)" $used $pct]
    }
  }
  return "n/a"
}

# build_dir : directory to write fit_summary.txt into
# cfg_path  : optional path to the <Top>.summary.txt config summary to prepend
# A fit summary is a reporting nicety and must never fail a build, so any
# error here is caught and downgraded to a warning.
proc emit_fit_summary {build_dir {cfg_path ""}} {
  if {[catch {_emit_fit_summary $build_dir $cfg_path} err]} {
    puts "WARNING: fit summary generation failed: $err"
  }
}

proc _emit_fit_summary {build_dir cfg_path} {
  set part [get_property PART [current_design]]
  set util [report_utilization -return_string]
  set wns  [get_property SLACK [lindex [get_timing_paths -max_paths 1 -nworst 1 -setup] 0]]
  set whs  [get_property SLACK [lindex [get_timing_paths -max_paths 1 -nworst 1 -hold] 0]]
  if {$wns eq ""} { set wns 0 }
  if {$whs eq ""} { set whs 0 }
  set tmet [expr {$wns >= 0 && $whs >= 0 ? "MET" : "VIOLATED"}]

  set fit_path [file join $build_dir fit_summary.txt]
  set fh [open $fit_path w]
  if {$cfg_path ne "" && [file exists $cfg_path]} {
    set cf [open $cfg_path r]; puts -nonewline $fh [read $cf]; close $cf
    puts $fh ""
  }
  puts $fh "=== FPGA Fit Summary ($part) ==="
  puts $fh [format "  Slice LUTs:    %s" [_fit_util_row $util "Slice LUTs\\*?"]]
  puts $fh [format "  Slice Regs:    %s" [_fit_util_row $util "Slice Registers"]]
  puts $fh [format "  Block RAM:     %s" [_fit_util_row $util "Block RAM Tile"]]
  puts $fh [format "  DSPs:          %s" [_fit_util_row $util "DSPs"]]
  puts $fh [format "  Timing:        %s  (WNS %+.3f ns, WHS %+.3f ns)" $tmet $wns $whs]
  puts $fh [format "  Built:         %s" [clock format [clock seconds] -format {%Y-%m-%d %H:%M:%S}]]
  close $fh

  puts "INFO: Fit summary at $fit_path"
  puts "----------------------------------------------------------------"
  set sf [open $fit_path r]; puts -nonewline [read $sf]; close $sf
  puts "----------------------------------------------------------------"
}
