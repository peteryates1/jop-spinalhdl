# vivado -mode batch -source build.tcl [-tclargs <part>]   (default -1)
set here [file normalize [file dirname [info script]]]
set part [expr {[llength $argv] ? [lindex $argv 0] : "xc7a50tfgg484-1"}]
set bdir [file join $here build]
file mkdir $bdir
read_verilog [file join $here I9PlusIdle.v]
read_xdc     [file join $here i9plus_idle.xdc]
synth_design -top I9PlusIdle -part $part
opt_design; place_design; route_design
report_timing_summary -file [file join $bdir timing.rpt]
report_io             -file [file join $bdir io.rpt]
write_bitstream -force [file join $bdir I9PlusIdle.bit]
puts "IDLE BITSTREAM DONE ($part)"
