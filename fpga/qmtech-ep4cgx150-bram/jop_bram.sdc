# JOP BRAM - Timing Constraints
# QMTECH EP4CGX150, 50 MHz input clock, PLL to 100 MHz

# 50 MHz input clock
create_clock -period 20.000 -name clk_in [get_ports clk_in]

derive_pll_clocks
derive_clock_uncertainty
