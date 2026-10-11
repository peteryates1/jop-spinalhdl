# vivado -mode batch -source build_rgmii.tcl [-tclargs <part>]   (default -1)
set here [file normalize [file dirname [info script]]]
set part [expr {[llength $argv] ? [lindex $argv 0] : "xc7a50tfgg484-1"}]
set bdir [file join $here build]
file mkdir $bdir
cd $here    ;# $readmemh("frame.hex") resolves against the run directory
read_verilog [file join $here EthRgmiiTest.v]
set defs [expr {[info exists ::env(DEFS)] ? $::env(DEFS) : ""}]
set tag  [expr {[info exists ::env(TAG)] ? $::env(TAG) : ""}]
read_xdc     [file join $here eth_rgmii_test.xdc]
# keep_equivalent_registers: txd0/txd1 (and both tx_ctl bits) are equal but
# each must stay its own flop to go into its own IOB
synth_design -top EthRgmiiTest -part $part -keep_equivalent_registers {*}[expr {$defs ne "" ? [list -verilog_define $defs] : {}}]
opt_design; place_design; route_design
report_timing_summary -file [file join $bdir rgmii${tag}_timing.rpt]
write_checkpoint -force [file join $bdir rgmii${tag}.dcp]
write_bitstream -force [file join $bdir EthRgmiiTest${tag}.bit]
puts "RGMII TEST DONE ($part)"
