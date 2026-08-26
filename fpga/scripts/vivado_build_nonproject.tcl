# ---------------------------------------------------------------------------
# Run a Vivado NON-PROJECT (in-process) build, once.
#
# The third of the shared Vivado scripts, and the one that carried the most
# duplication: seven of these existed across two boards, 429 lines, all the
# same nine steps in the same order.
#
#   JOP_TOP        top-level entity                            (required)
#   JOP_PART       e.g. xc7a100tfgg676-2                       (required)
#   JOP_BUILD_DIR  where checkpoints, reports and the .bit go  (required)
#   JOP_RTL        space-separated RTL files                   (required)
#   JOP_IP         space-separated .xci files                  (optional)
#   JOP_XDC        space-separated constraint files, IN ORDER  (optional)
#   JOP_BIN_GLOB   glob for BRAM-init .bin sidecars            (optional)
#   JOP_SYNTH_DIRECTIVE, JOP_SYNTH_RETIMING                    (optional)
#   JOP_OPT_DIRECTIVE, JOP_PLACE_DIRECTIVE, JOP_ROUTE_DIRECTIVE (optional)
#   JOP_PHYS_OPT_DIRECTIVE  set => phys_opt_design runs after place AND route
#   JOP_POST_SYNTH_TCL  file sourced after synth, before opt   (optional)
#   JOP_DRC_WAIVE  DRC checks to disable before write_bitstream (optional)
#   JOP_SUMMARY    path to write the fit summary to            (optional)
#   JOP_BIT        bitstream file name, default $JOP_TOP.bit   (optional)
#
# WHAT IS A PARAMETER AND WHAT IS NOT. The directives are genuinely per-design
# and are NOT normalised: the SDRAM exerciser runs bare opt/place/route because
# it has timing margin to spare, while the JOP builds need
# Explore/ExtraTimingOpt/AggressiveExplore to close. Flattening those into one
# "standard" flow would silently re-tune every build. Contrast the incremental-
# synthesis divergence in vivado_build_project.tcl, which was drift nobody chose.
#
# XDC ORDER IS LOAD-BEARING, so JOP_XDC is an ordered list and is read in the
# order given. rtl8211eg_gmii.xdc does `create_clock -name e_rxc`, and
# wukong_ddr3.xdc then references [get_clocks e_rxc] in a set_clock_groups.
# Read the other way round that get_clocks matches nothing -- "WARNING: [Vivado
# 12-627] No clocks matched 'e_rxc'" -- the asynchronous exclusion silently does
# not apply, and the build reports a violation it should not. See status item 58.
#
# JOP_POST_SYNTH_TCL is the escape hatch for constraints that can only be
# applied to a synthesised netlist -- the dual-cluster build's set_max_delay and
# set_clock_groups over the SDR controller. That is real per-design Tcl, not a
# field, and pretending otherwise would have meant either a parameter nobody
# else could use or leaving that one build unconverted.
# ---------------------------------------------------------------------------

proc jop_env {name {default ""}} {
  if {[info exists ::env($name)] && $::env($name) ne ""} { return $::env($name) }
  return $default
}

proc jop_require {name} {
  set v [jop_env $name]
  if {$v eq ""} {
    puts "ERROR: $name must be set (vivado_build_nonproject.tcl)"
    exit 1
  }
  return $v
}

set top       [jop_require JOP_TOP]
set part      [jop_require JOP_PART]
set build_dir [file normalize [jop_require JOP_BUILD_DIR]]
set rtl_files [jop_require JOP_RTL]
set ip_files  [jop_env JOP_IP]
set xdc_files [jop_env JOP_XDC]
set bin_glob  [jop_env JOP_BIN_GLOB]
set post_tcl  [jop_env JOP_POST_SYNTH_TCL]
set drc_waive [jop_env JOP_DRC_WAIVE]
set summary   [jop_env JOP_SUMMARY]
set bit_name  [jop_env JOP_BIT "$top.bit"]

file mkdir $build_dir

foreach f $ip_files {
  set f [file normalize $f]
  if {![file exists $f]} {
    puts "ERROR: IP not found: $f"
    puts "       run the board's create-ip target first"
    exit 1
  }
  read_ip $f
}

foreach f $rtl_files {
  set f [file normalize $f]
  if {![file exists $f]} {
    puts "ERROR: RTL not found: $f"
    puts "       run the board's generate target first"
    exit 1
  }
  read_verilog $f
}

# COPIED, not merely read: $readmemb paths in the generated Verilog are bare
# file names, resolved relative to the working directory rather than to the RTL.
if {$bin_glob ne ""} {
  foreach f [glob -nocomplain $bin_glob] { file copy -force $f $build_dir }
}

foreach f $xdc_files {
  set f [file normalize $f]
  if {![file exists $f]} {
    puts "ERROR: constraints not found: $f"
    exit 1
  }
  read_xdc $f
}

set synth_args [list -top $top -part $part]
set sd [jop_env JOP_SYNTH_DIRECTIVE]
if {$sd ne ""} { lappend synth_args -directive $sd }
if {[jop_env JOP_SYNTH_RETIMING] eq "yes"} { lappend synth_args -retiming }
synth_design {*}$synth_args

write_checkpoint -force [file join $build_dir post_synth.dcp]
report_utilization -file [file join $build_dir utilization_synth.rpt]

if {$post_tcl ne ""} {
  set post_tcl [file normalize $post_tcl]
  if {![file exists $post_tcl]} {
    puts "ERROR: JOP_POST_SYNTH_TCL not found: $post_tcl"
    exit 1
  }
  puts "INFO: sourcing post-synthesis constraints $post_tcl"
  source $post_tcl
}

proc jop_step {cmd var} {
  set d [jop_env $var]
  if {$d ne ""} { $cmd -directive $d } else { $cmd }
}

jop_step opt_design   JOP_OPT_DIRECTIVE
jop_step place_design JOP_PLACE_DIRECTIVE
set phys [jop_env JOP_PHYS_OPT_DIRECTIVE]
if {$phys ne ""} { phys_opt_design -directive $phys }
jop_step route_design JOP_ROUTE_DIRECTIVE
if {$phys ne ""} { phys_opt_design -directive $phys }

write_checkpoint -force [file join $build_dir post_route.dcp]
report_utilization -file [file join $build_dir utilization_impl.rpt]
report_timing_summary -file [file join $build_dir timing_summary.rpt]

# Waive combinatorial-loop DRC from SpinalHDL StreamFifoLowLatency (SD native
# controller): transparent-latch loops that are functionally correct but flagged.
foreach chk $drc_waive { set_property IS_ENABLED FALSE [get_drc_checks $chk] }

write_bitstream -force [file join $build_dir $bit_name]
puts "INFO: Bitstream at [file join $build_dir $bit_name]"

if {$summary ne ""} {
  source [file join [file dirname [file normalize [info script]]] vivado_fit_summary.tcl]
  emit_fit_summary $build_dir $summary
}
