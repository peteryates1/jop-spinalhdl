# Self-contained: everything this jig needs sits beside it -- see ../README.md.
#   vivado -mode batch -source build.tcl [-tclargs <part>]
# Default part is -1 (the conservative grade): the bitstream loads on a -2 too.
set here      [file normalize [file dirname [info script]]]
set build_dir [file join $here build]
set part      [expr {[llength $argv] ? [lindex $argv 0] : "xc7a50tfgg484-1"}]
file mkdir $build_dir
read_verilog [file join $here ColorlightDbBringup.v]
read_xdc     [file join $here colorlight_db_i9plus.xdc]
synth_design -top ColorlightDbBringup -part $part
opt_design
place_design
route_design
report_timing_summary -file [file join $build_dir timing.rpt]
report_utilization    -file [file join $build_dir utilization.rpt]
write_bitstream -force [file join $build_dir ColorlightDbBringup.bit]
puts "BRINGUP BITSTREAM DONE ($part)"
