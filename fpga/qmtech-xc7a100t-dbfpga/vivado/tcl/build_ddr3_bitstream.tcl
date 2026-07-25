# Bitstream build for JOP DDR3 on QMTECH XC7A100T Core Board + DB_FPGA V5.
# Non-project flow: reads IP + RTL + constraints, synthesizes, implements, writes bit.

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build/xc7a100t_ddr3]]
set rtl_dir    [file normalize [file join $repo_root ../../spinalhdl/generated]]
set ip_root    [file normalize [file join $repo_root vivado/ip]]

file mkdir $build_dir

# Read IP netlists
read_ip [file join $ip_root clk_wiz_0/clk_wiz_0.xci]
read_ip [file join $ip_root mig_7series_0/mig_7series_0.xci]

# Read RTL
read_verilog [file join $rtl_dir JopDdr3Top.v]

# Read constraints
read_xdc [file join $repo_root vivado/constraints/xc7a100t_dbv5_base.xdc]

# Synthesize
synth_design -top JopDdr3Top -part xc7a100tfgg676-2 \
  -directive PerformanceOptimized -retiming
write_checkpoint -force [file join $build_dir post_synth.dcp]
report_utilization -file [file join $build_dir utilization_synth.rpt]

# Implement
opt_design -directive Explore
place_design -directive ExtraTimingOpt
phys_opt_design -directive AggressiveExplore
route_design -directive AggressiveExplore
phys_opt_design -directive AggressiveExplore
write_checkpoint -force [file join $build_dir post_route.dcp]
report_utilization -file [file join $build_dir utilization_impl.rpt]
report_timing_summary -file [file join $build_dir timing_summary.rpt]

# Write bitstream
write_bitstream -force [file join $build_dir JopDdr3Top.bit]

# --- Concise fit summary: config + utilization + timing, in one file ---
proc _util_row {rpt name} {
  foreach line [split $rpt "\n"] {
    if {[regexp "\\| ${name} +\\| +(\[0-9.\]+) +\\|.*\\| +(\[0-9.\]+) +\\|" $line -> used pct]} {
      return [format "%-8s (%s%%)" $used $pct]
    }
  }
  return "n/a"
}

set util [report_utilization -return_string]
set wns  [get_property SLACK [lindex [get_timing_paths -max_paths 1 -nworst 1 -setup] 0]]
set whs  [get_property SLACK [lindex [get_timing_paths -max_paths 1 -nworst 1 -hold] 0]]
set tmet [expr {$wns >= 0 && $whs >= 0 ? "MET" : "VIOLATED"}]

set fit_path [file join $build_dir fit_summary.txt]
set fh [open $fit_path w]
# Prepend the generation-time config summary if present.
set cfg_path [file join $rtl_dir JopDdr3Top.summary.txt]
if {[file exists $cfg_path]} {
  set cf [open $cfg_path r]; puts -nonewline $fh [read $cf]; close $cf
  puts $fh ""
}
puts $fh "=== FPGA Fit Summary (xc7a100tfgg676-2) ==="
puts $fh [format "  Slice LUTs:    %s" [_util_row $util "Slice LUTs\\*?"]]
puts $fh [format "  Slice Regs:    %s" [_util_row $util "Slice Registers"]]
puts $fh [format "  Block RAM:     %s" [_util_row $util "Block RAM Tile"]]
puts $fh [format "  DSPs:          %s" [_util_row $util "DSPs"]]
puts $fh [format "  Timing:        %s  (WNS %+.3f ns, WHS %+.3f ns)" $tmet $wns $whs]
puts $fh [format "  Built:         %s" [clock format [clock seconds] -format {%Y-%m-%d %H:%M:%S}]]
close $fh

puts "INFO: Bitstream at [file join $build_dir JopDdr3Top.bit]"
puts "INFO: Fit summary at $fit_path"
puts "----------------------------------------------------------------"
set sf [open $fit_path r]; puts -nonewline [read $sf]; close $sf
puts "----------------------------------------------------------------"
