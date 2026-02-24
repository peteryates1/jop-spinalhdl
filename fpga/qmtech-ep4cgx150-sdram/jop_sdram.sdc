# JOP SDRAM - Timing Constraints
# QMTECH EP4CGX150, 50 MHz input clock, PLL to 80 MHz

# 50 MHz input clock
create_clock -period 20.000 -name clk_in [get_ports clk_in]

derive_pll_clocks
derive_clock_uncertainty

# VGA pixel clock (PLL c3, 25 MHz) <-> system clock (PLL c1, 80 MHz)
set_false_path -from {pll|altpll_component|auto_generated|pll1|clk[1]} \
               -to   {pll|altpll_component|auto_generated|pll1|clk[3]}
set_false_path -from {pll|altpll_component|auto_generated|pll1|clk[3]} \
               -to   {pll|altpll_component|auto_generated|pll1|clk[1]}

# Ethernet PHY clocks (25 MHz from PHY) <-> system clock
set_false_path -from {e_txc} -to {pll|altpll_component|auto_generated|pll1|clk[1]}
set_false_path -from {pll|altpll_component|auto_generated|pll1|clk[1]} -to {e_txc}
set_false_path -from {e_rxc} -to {pll|altpll_component|auto_generated|pll1|clk[1]}
set_false_path -from {pll|altpll_component|auto_generated|pll1|clk[1]} -to {e_rxc}
