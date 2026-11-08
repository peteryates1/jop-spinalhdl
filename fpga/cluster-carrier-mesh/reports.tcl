# ---------------------------------------------------------------------------
# What the one-bitstream build says about the pin plan, from its routed
# checkpoint: the reports a pre-spin check reads, and one VERDICT line.
#
#   vivado -mode batch -source reports.tcl -tclargs <build_dir>
#
# The verdict counts what would make the plan wrong, the link untestable, or
# the bitstream unsafe in some socket:
#   setup/hold   worst slack over every constrained path
#   drc          DRC violations of severity ERROR (write_bitstream also stops
#                on these; counted here so a failed build still reports)
#   iob_tx       mesh outputs NOT launched from an ODDR (OLOGIC), and
#   iob_rx       RX capture flip-flops NOT in ILOGIC: either way the link's
#                timing would be the fabric's, not the pins'
#   rxclk        RX clocks not driven IBUF -> BUFG directly
#   tristate     mesh outputs whose buffer cannot be turned off -- an OBUF, or
#                an OBUFT with T tied to a constant. One bitstream serves every
#                socket only because each line is driven in the sockets that
#                should drive it and read in the others
#   pulls        mesh pins without a PULLDOWN (an idle line must read 0, and
#                an unused RX clock must not float into its BUFG), straps
#                without a PULLUP (an open strap must read 1)
# The ports are classified from the routed netlist, not from the plan: a TX
# line is one a buffer drives, an RX clock one a buffer reads.
# ---------------------------------------------------------------------------
set build_dir [lindex $argv 0]
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

# The leaf cell on a port's net with a pin of direction `dir`: the buffer
# that drives the port (OUT) or reads it (IN). An IOBUF is both.
proc buffer_pin {port dir} {
  return [get_pins -quiet -leaf -of_objects [get_nets -of_objects $port] -filter "DIRECTION == $dir"]
}
proc driver_of {pin} {
  set net [get_nets -quiet -of_objects $pin]
  return [get_cells -quiet -of_objects [get_pins -quiet -leaf -of_objects $net -filter {DIRECTION == OUT}]]
}

set mesh [get_ports -regexp {mesh[0-9]+_(clk|d[0-3]|fr)}]
set tx_ports {}
set rx_clocks {}
foreach p $mesh {
  if {[llength [buffer_pin $p OUT]]} { lappend tx_ports $p }
  if {[string match *_clk $p] && [llength [buffer_pin $p IN]]} { lappend rx_clocks $p }
}

# TX: every mesh output launched from an ODDR, through a buffer it can turn off.
set tx_bad 0
set tri_bad 0
foreach p $tx_ports {
  set buf [get_cells -of_objects [buffer_pin $p OUT]]
  set src [driver_of [get_pins -of_objects $buf -filter {REF_PIN_NAME == I}]]
  if {[get_property REF_NAME $src] ne "ODDR"} { incr tx_bad; puts "TX not from an ODDR: $p ([get_property REF_NAME $src])" }
  set t [get_pins -quiet -of_objects $buf -filter {REF_PIN_NAME == T}]
  set tsrc [driver_of $t]
  if {[llength $t] == 0 || [lsearch -exact {GND VCC} [get_property REF_NAME $tsrc]] >= 0} {
    incr tri_bad; puts "TX cannot be turned off: $p ([get_property REF_NAME $buf], T from [get_property REF_NAME $tsrc])"
  }
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
# RX clocks: the pin's input buffer drives a BUFG and nothing else.
set rxclk_bad 0
foreach p $rx_clocks {
  set ibuf [get_cells -of_objects [buffer_pin $p IN]]
  set loads [get_cells -quiet -of_objects [get_pins -quiet -leaf -of_objects [get_nets -of_objects [get_pins -of_objects $ibuf -filter {REF_PIN_NAME == O}]] -filter {DIRECTION == IN}]]
  if {[llength $loads] != 1 || [get_property REF_NAME $loads] ne "BUFG"} {
    incr rxclk_bad; puts "$p: input buffer drives [llength $loads] cell(s): [get_property REF_NAME $loads]"
  }
}
# Pulls.
set straps [get_ports -regexp {strap_.*}]
set pull_bad 0
foreach p $mesh   { if {[get_property PULLTYPE $p] ne "PULLDOWN"} { incr pull_bad; puts "no PULLDOWN: $p" } }
foreach p $straps { if {[get_property PULLTYPE $p] ne "PULLUP"}   { incr pull_bad; puts "no PULLUP: $p" } }

set line [format "VERDICT setup=%s hold=%s drc_errors=%d iob_tx_bad=%d/%d iob_rx_bad=%d/%d rxclk_bad=%d/%d tristate_bad=%d/%d pulls_bad=%d/%d" \
  [worst setup] [worst hold] $drc_errors $tx_bad [llength $tx_ports] $rx_bad [llength $rx_regs] \
  $rxclk_bad [llength $rx_clocks] $tri_bad [llength $tx_ports] $pull_bad [expr {[llength $mesh] + [llength $straps]}]]
puts $line

# The link's own numbers, from the datasheet's per-port tables, slow corner.
# RX, per bundle: the capture window at the pins over D0-D3 and FR -- worst
# setup plus worst hold; the bit must be valid for at least this long -- and
# where its centre sits from the clock edge, (hold - setup) / 2. TX, per
# bundle: the spread of clock-to-pad times over its six lines.
set ds [read [set f [open [file join $build_dir datasheet.rpt]]]]
close $f
set rxs {}
foreach clk $rx_clocks {
  set b [string range $clk 0 end-4]
  set su {}; set ho {}
  foreach {- s h} [regexp -all -inline [subst -nocommands -nobackslashes {${clk} +\| ${b}_(?:d[0-3]|fr) +\| FDRE \(IO\) \| [A-Z_-]+ +\| +([-0-9.]+) \(r\) \| SLOW +\| +([-0-9.]+) \(r\) \| FAST}] $ds] {
    lappend su $s; lappend ho $h
  }
  if {[llength $su] != 5} { lappend rxs "$b:?([llength $su]_lines)"; continue }
  set s [tcl::mathfunc::max {*}$su]
  set h [tcl::mathfunc::max {*}$ho]
  lappend rxs [format "%s:%.3f@%.3f" $b [expr {$s + $h}] [expr {($h - $s) / 2.0}]]
}
# A tri-stated output has two rows: its enable register's (through T) and the
# ODDR's, which shows only the worse of its two edges.
set txs {}
foreach b [lsort -unique [lmap p $tx_ports {string range $p 0 [expr {[string first _ $p] - 1}]}]] {
  set t {}
  foreach {- v} [regexp -all -inline [subst -nocommands -nobackslashes {\| ${b}_(?:clk|d[0-3]|fr) +\| ODDR \(IO\) \| - +\| +([0-9.]+) \([rf]\) \| SLOW}] $ds] { lappend t $v }
  if {[llength $t] != 6} { lappend txs "$b:?([llength $t]_lines)"; continue }
  lappend txs [format "%s:%.3f" $b [expr {[tcl::mathfunc::max {*}$t] - [tcl::mathfunc::min {*}$t]}]]
}
set link [format "LINK rx_window_ns@offset_from_centre_ns=%s tx_skew_ns=%s" [join $rxs " "] [join $txs " "]]
puts $link

set fh [open [file join $build_dir verdict.txt] w]
puts $fh $line
puts $fh $link
close $fh
