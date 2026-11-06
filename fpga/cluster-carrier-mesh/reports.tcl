# ---------------------------------------------------------------------------
# What a socket's build says about the pin plan, from its routed checkpoint:
# the reports a pre-spin check reads, and one VERDICT line.
#
#   vivado -mode batch -source reports.tcl -tclargs <build_dir> <socket>
#
# The verdict counts what would make the plan wrong or the link untestable:
#   setup/hold   worst slack over every constrained path
#   drc          DRC violations of severity ERROR (write_bitstream also stops
#                on these; counted here so a failed build still reports)
#   iob          mesh registers NOT in their I/O tile: every TX line is an
#                ODDR (OLOGIC) and every RX D/FR capture flip-flop must be in
#                ILOGIC, or the link's timing is the fabric's, not the pins'
#   rxclk        RX clocks not driven IBUF -> BUFG directly
# ---------------------------------------------------------------------------
set build_dir [lindex $argv 0]
set socket    [lindex $argv 1]
open_checkpoint [file join $build_dir post_route.dcp]

report_drc               -file [file join $build_dir drc.rpt]
report_methodology       -file [file join $build_dir methodology.rpt]
report_io                -file [file join $build_dir io.rpt]
report_clock_interaction -file [file join $build_dir clock_interaction.rpt]
report_clock_utilization -file [file join $build_dir clock_utilization.rpt]
check_timing -verbose    -file [file join $build_dir check_timing.rpt]
report_datasheet         -file [file join $build_dir datasheet.rpt]

proc worst {kind} {
  set p [get_timing_paths -$kind -max_paths 1 -nworst 1]
  if {[llength $p] == 0} { return "none" }
  return [format %.3f [get_property SLACK $p]]
}

set drc_errors 0
foreach v [get_drc_violations -quiet] {
  if {[get_property SEVERITY $v] eq "Error"} { incr drc_errors }
}

# TX: every mesh output port driven by an ODDR.
set tx_ports [get_ports -regexp {p[123]_tx_.*}]
set tx_bad 0
foreach p $tx_ports {
  set drv [get_cells -quiet -of_objects [get_pins -quiet -leaf -of_objects [get_nets -of_objects $p] -filter {DIRECTION == OUT}]]
  set obuf_in [get_nets -quiet -of_objects [get_pins -quiet -of_objects $drv -filter {REF_PIN_NAME == I}]]
  set src [get_cells -quiet -of_objects [get_pins -quiet -leaf -of_objects $obuf_in -filter {DIRECTION == OUT}]]
  if {[get_property REF_NAME $src] ne "ODDR"} { incr tx_bad; puts "TX not from an ODDR: $p ([get_property REF_NAME $src])" }
}
# RX: every D/FR capture register in ILOGIC.
# IS_SEQUENTIAL: Vivado puts a ZHOLD_DELAY cell ("_OPT_INSERTED") beside each
# one, its input delay compensating the BUFG's clock insertion; not a register.
set rx_regs [get_cells -hierarchical -filter {(NAME =~ *dIob_reg* || NAME =~ *frIob_reg*) && IS_SEQUENTIAL}]
set rx_bad 0
foreach c $rx_regs {
  if {![string match "ILOGIC*" [get_property SITE_TYPE [get_sites -of_objects $c]]]} {
    incr rx_bad; puts "RX capture not in ILOGIC: $c at [get_sites -of_objects $c]"
  }
}
# RX clocks: the pin's IBUF drives a BUFG and nothing else.
set rxclk_bad 0
foreach k {1 2 3} {
  set ibuf [get_cells -of_objects [get_pins -leaf -of_objects [get_nets -of_objects [get_ports p${k}_rx_clk]] -filter {DIRECTION == IN}]]
  set loads [get_cells -quiet -of_objects [get_pins -quiet -leaf -of_objects [get_nets -of_objects [get_pins -of_objects $ibuf -filter {REF_PIN_NAME == O}]] -filter {DIRECTION == IN}]]
  if {[llength $loads] != 1 || [get_property REF_NAME $loads] ne "BUFG"} {
    incr rxclk_bad; puts "p${k}_rx_clk: IBUF drives [llength $loads] cell(s): [get_property REF_NAME $loads]"
  }
}

set line [format "VERDICT %s setup=%s hold=%s drc_errors=%d iob_tx_bad=%d/%d iob_rx_bad=%d/%d rxclk_bad=%d/3" \
  $socket [worst setup] [worst hold] $drc_errors $tx_bad [llength $tx_ports] $rx_bad [llength $rx_regs] $rxclk_bad]
puts $line

# The link's own numbers, from the datasheet: each RX port's capture window at
# the pins (setup + hold; the bit must be valid for at least this long) and how
# far it sits from centre, and each TX port's spread of clock-to-pad times over
# its six lines -- the forwarded clock, D0-D3 and FR -- in the slow corner.
set ds [read [set f [open [file join $build_dir datasheet.rpt]]]]
close $f
set windows [regexp -all -inline {Worst Case Data Window: ([0-9.]+) ns} $ds]
set offsets [regexp -all -inline {Ideal Clock Offset to Actual Clock: ([-0-9.]+) ns} $ds]
set rxw {}; foreach {- v} $windows { lappend rxw $v }
set rxo {}; foreach {- v} $offsets { lappend rxo $v }
set txs {}
foreach k {1 2 3} {
  set t {}
  foreach {- v} [regexp -all -inline [subst -nocommands -nobackslashes {\| p${k}_tx_[a-z0-9\[\]]+ *\| ODDR \(IO\) \| - *\| *([0-9.]+) \(r\) \| SLOW}] $ds] { lappend t $v }
  if {[llength $t] != 6} { lappend txs "?([llength $t]_lines)"; continue }
  lappend txs [format %.3f [expr {[tcl::mathfunc::max {*}$t] - [tcl::mathfunc::min {*}$t]}]]
}
set link [format "LINK %s rx_window_ns=%s rx_offset_from_centre_ns=%s tx_skew_ns=%s" \
  $socket [join $rxw /] [join $rxo /] [join $txs /]]
puts $link

set fh [open [file join $build_dir verdict.txt] w]
puts $fh $line
puts $fh $link
close $fh
