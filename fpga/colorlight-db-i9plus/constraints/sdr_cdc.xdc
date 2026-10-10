# The board-input clock (sys_clk, 25 MHz) and the clk_wiz outputs are
# asynchronous for timing purposes: the only crossings are the debug paths
# (pc, jpc, memState -> HangDetector) and they go through BufferCC.
#
# Same content as the Wukong's wukong_sdr_cdc.xdc (status item 159), because
# this board uses the same clk_wiz shape under the same IP name, `sdr_clk`.
# A copy rather than a reference: the jig/board directories must not reach
# into each other.
set_clock_groups -asynchronous \
  -group [get_clocks -quiet sys_clk] \
  -group [get_clocks -quiet {clk_100_sdr_clk clk_100_shift_sdr_clk clk_125_sdr_clk}]
