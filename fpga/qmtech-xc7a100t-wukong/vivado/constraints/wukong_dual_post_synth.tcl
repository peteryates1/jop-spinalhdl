# Post-synthesis constraints for the dual-cluster (DDR3 + SDR) build.
#
# These are applied to the SYNTHESISED NETLIST, between synth_design and
# opt_design, which is why they live here rather than in an .xdc read up front:
# get_ports/get_clocks below resolve against the elaborated design.
#
# Sourced by fpga/scripts/vivado_build_nonproject.tcl via JOP_POST_SYNTH_TCL.
# It is the escape hatch that let this build share the flow with the other six
# instead of keeping its own 83-line copy of it.

set sdram_all_out [get_ports {sdram_ADDR[*] sdram_BA[*] sdram_CKE sdram_CSn sdram_RASn sdram_CASn sdram_WEn sdram_DQM[*] sdram_DQ[*]}]
set_max_delay 5.0 -datapath_only -from [get_clocks clk_100_sdr_clk] -to $sdram_all_out
set_max_delay 5.0 -datapath_only -from [get_ports {sdram_DQ[*]}] -to [get_clocks clk_100_sdr_clk]
set_clock_groups -asynchronous \
  -group [get_clocks sys_clk] \
  -group [get_clocks clk_pll_i] \
  -group [get_clocks {clk_100_sdr_clk clk_100_shift_sdr_clk}]
