# JOP CYC5000 - Timing Constraints
# Trenz CYC5000, 12 MHz input clock, PLL to 80 MHz

# 12 MHz input clock
create_clock -period 83.333 -name clk_in [get_ports clk_in]

derive_pll_clocks
derive_clock_uncertainty
