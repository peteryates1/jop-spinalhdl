# ---------------------------------------------------------------------------
# The receive window of the RGMII RX pins on a ROUTED design: for each
# IDELAYE2 tap count, the worst setup and hold slack from e_rxd[*] and
# e_rxctl, both RXC edges, as the XDC's input delays judge them. Written for
# the Colorlight i9+ at 1000 Mb/s (colorlightDbI9PlusEth1000), whose
# rxDelayTaps is the centre of the counts that pass.
#
#   vivado -mode batch -nojournal -nolog -source fpga/scripts/rx_window.tcl \
#       -tclargs build/colorlightDbI9PlusEth1000/vivado/build/post_route.dcp
#
# Prints the design's clock buffers, then "TAPS n setup s hold h" for n = 0
# to 31, and writes the full clock paths at the built count next to the
# checkpoint (<dcp>_rx_setup.rpt, <dcp>_rx_hold.rpt). The tap counts are set
# in memory only; the checkpoint is not written.
# ---------------------------------------------------------------------------
set dcp [lindex $argv 0]
open_checkpoint $dcp
foreach b {BUFG BUFR BUFIO} {
  puts "$b cells: [llength [get_cells -hier -filter "REF_NAME == $b"]]"
}
set cells [get_cells -hier -filter {REF_NAME == IDELAYE2}]
puts "IDELAYE2 cells: [llength $cells] at IDELAY_VALUE [lsort -unique [get_property IDELAY_VALUE $cells]]"
set rx [get_ports {e_rxd[*] e_rxctl}]
set orig [lindex [get_property IDELAY_VALUE $cells] 0]
for {set t 0} {$t <= 31} {incr t} {
  set_property IDELAY_VALUE $t $cells
  set sp [get_timing_paths -from $rx -setup -max_paths 1 -nworst 1]
  set hp [get_timing_paths -from $rx -hold -max_paths 1 -nworst 1]
  puts [format "TAPS %2d setup %7.3f hold %7.3f" $t [get_property SLACK $sp] [get_property SLACK $hp]]
}
set_property IDELAY_VALUE $orig $cells
report_timing -from $rx -setup -max_paths 1 -nworst 1 -path_type full_clock -file [file rootname $dcp]_rx_setup.rpt
report_timing -from $rx -hold -max_paths 1 -nworst 1 -path_type full_clock -file [file rootname $dcp]_rx_hold.rpt
puts "RX_WINDOW_DONE"
