# Clock-domain crossings for the Wukong SDR JOP builds.
#
# WHY THIS EXISTS, AND WHY IT DID NOT BEFORE. The design runs on the MMCM's
# system output; the board's 50 MHz input clock (`sys_clk`) drives only the
# reset generator and the hang detector. The debug signals the hang detector
# watches -- pc, jpc, memBusy, uart_txd, debugMemState -- cross between the two
# through SpinalHDL `BufferCC` instances, i.e. two-flop synchronisers, which is
# the correct structure for an unrelated clock.
#
# At 100 MHz nothing complained, because the MMCM output was exactly 2x the
# 50 MHz input: the two clocks were phase-related, the crossings had a clean
# capture relationship, and the missing constraint was invisible. Ask the MMCM
# for 80 MHz and it delivers 79.927, which is mutually unrelated to 50 -- so
# Vivado aligns the worst-case edges over the common period and demands
#
#   Requirement: 0.091 ns   (fetch/romAddrReg_reg -> io_pc_0_buffercc)
#
# which nothing can meet, at any frequency. WNS went from -1.451 ns at 100 MHz
# to -3.303 ns at 80 -- slower clock, worse slack, because it is a different
# path in a different clock group. The constraint was always missing; the
# frequency ratio was hiding it.
#
# This is status item 153's shape one board over: a timing result that looks
# fine only because of an accident of the clock setup, not because the design
# was constrained.
#
# SAFE because every crossing between these domains goes through a BufferCC,
# and they carry debug state to the hang detector, not functional data. If a
# future crossing is added WITHOUT a synchroniser, this constraint will hide
# it -- that is the cost, and the reason this is scoped to the SDR JOP flows
# rather than written as a blanket rule in the generator.
# NOT `-include_generated_clocks sys_clk`: the MMCM outputs ARE generated
# clocks of the clk port, so that spelling puts them in the same group as the
# clock they are being separated from, and the constraint does nothing. Name
# both sides explicitly. -quiet tolerates clk_125, which Vivado optimises away
# in the non-GMII builds.
set_clock_groups -asynchronous \
  -group [get_clocks -quiet sys_clk] \
  -group [get_clocks -quiet {clk_100_sdr_clk clk_100_shift_sdr_clk clk_125_sdr_clk}]
