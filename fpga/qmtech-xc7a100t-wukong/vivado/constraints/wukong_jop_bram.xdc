# QMTECH XC7A100T Wukong V3 — JOP BRAM constraints

# 50 MHz system clock (Y1 oscillator)
# The port is `clk`, not `clk_in` -- this file said clk_in, which matched
# nothing, so `clk` and `resetn` reached implementation unconstrained and the
# build died in DRC (NSTD-1 / UCIO-1) rather than at the stale constraint.
set_property PACKAGE_PIN M21 [get_ports {clk}]
set_property IOSTANDARD LVCMOS33 [get_ports {clk}]
create_clock -period 20.000 -name sys_clk [get_ports {clk}]

# Reset (active-low from SpinalHDL default CD)
set_property PACKAGE_PIN H7 [get_ports {resetn}]
set_property IOSTANDARD LVCMOS33 [get_ports {resetn}]

# UART (CH340N on-board)
set_property PACKAGE_PIN E3 [get_ports {ser_txd}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_txd}]
set_property PACKAGE_PIN F3 [get_ports {ser_rxd}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_rxd}]

# On-board LEDs (active high)
set_property PACKAGE_PIN G21 [get_ports {led[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[0]}]
set_property PACKAGE_PIN G20 [get_ports {led[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[1]}]

# Configuration
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]
