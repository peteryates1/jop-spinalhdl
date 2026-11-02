# The board-input clock (sys_clk, 25 MHz) and the clk_wiz outputs are
# asynchronous for timing purposes: the only crossings are the debug paths
# (pc, jpc, memState -> HangDetector) and they go through BufferCC.
#
# Same content as the Wukong's wukong_sdr_cdc.xdc (status item 159), because
# this board's designs define the same PLL, `sdr_clk` (JopConfig.xilinxSdrPll).
# Vivado names each clock it derives <port>_<module>, and clk_wiz's ports are
# named for their roles (XilinxClkWiz); clk_eth_sdr_clk is the Ethernet
# design's RGMII clock. A copy rather than a reference: the jig/board
# directories must not reach into each other.
set_clock_groups -asynchronous \
  -group [get_clocks -quiet sys_clk] \
  -group [get_clocks -quiet {clk_sys_sdr_clk clk_sdram_sdr_clk clk_eth_sdr_clk}]
