# Project constraints for the A-E115FB DDR2 exerciser.
#
# The memory interface is NOT constrained here — ddr2_64bit_phy_ddr_timing.sdc
# (pulled in by ddr2_64bit_phy.qip) does that, including create_clock on the
# 25 MHz reference and derive_pll_clocks. Verified from the timing report:
# clk = 40.000 ns base, phy_clk = 6.021 ns generated (166 MHz).
#
# This file only covers the pins that belong to this design rather than to the
# IP, so they stop showing up as unconstrained paths.

# Reset and the UART receive line are asynchronous to everything.
set_false_path -from [get_ports rst_n] -to *
set_false_path -from [get_ports uart_rx] -to *

# Status outputs: no timing relationship worth analysing.
set_false_path -from * -to [get_ports {led[*]}]
set_false_path -from * -to [get_ports uart_tx]
