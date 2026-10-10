# vivado -mode batch -source build.tcl [-tclargs <part>]   (default -1)
set here [file normalize [file dirname [info script]]]
set part [expr {[llength $argv] ? [lindex $argv 0] : "xc7a50tfgg484-1"}]
set bdir [file join $here build]
file mkdir $bdir
read_verilog [file join $here EthMdioProbe.v]
read_xdc     [file join $here eth_mdio_probe.xdc]
synth_design -top EthMdioProbe -part $part
opt_design; place_design; route_design
report_timing_summary -file [file join $bdir timing.rpt]
report_io             -file [file join $bdir io.rpt]
write_bitstream -force [file join $bdir EthMdioProbe.bit]
puts "ETH PROBE DONE ($part)"
