# SDR SDRAM I/O Block Packing (Xilinx)
#
# Place data and DQM registers in I/O blocks for deterministic timing.
# Include this in any Vivado project using SDR SDRAM with the standard
# sdram_DQ/sdram_DQM signal naming.

set_property IOB TRUE [get_ports {sdram_DQ[*]}]
set_property IOB TRUE [get_ports {sdram_DQM[*]}]
