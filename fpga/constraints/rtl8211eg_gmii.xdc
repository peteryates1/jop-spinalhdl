# RTL8211EG GMII Timing Constraints (Xilinx)
#
# Source-synchronous 125 MHz GMII interface.
# Include this in any Vivado project using RTL8211EG in GMII mode.
#
# After sourcing, add e_rxc to your project's set_clock_groups:
#   set_clock_groups -asynchronous \
#       -group [get_clocks <your_clocks>] \
#       -group [get_clocks e_rxc]

# 125 MHz source-synchronous RX clock from PHY
create_clock -period 8.000 -name e_rxc [get_ports e_rxc]

# RX data: source-synchronous with e_rxc.
# IOB TRUE on RX pins (in pin constraints) places capture registers in I/O block
# for minimal clock-to-register delay.
# Vivado can't analyze the source-synchronous relationship, so false_path.
set_false_path -from [get_ports {e_rxd[*] e_rxdv e_rxer}]

# TX clock: driven from PLL (same clock domain as TX data logic).
# PHY samples TXD on GTXC rising edge; board trace delay provides margin.
set_false_path -to [get_ports {e_gtxc}]
set_false_path -to [get_ports {e_txd[*] e_txen e_txer}]

# Place RX input registers in I/O block for source-synchronous capture
set_property IOB TRUE [get_ports {e_rxd[*]}]
set_property IOB TRUE [get_ports {e_rxdv}]
set_property IOB TRUE [get_ports {e_rxer}]
