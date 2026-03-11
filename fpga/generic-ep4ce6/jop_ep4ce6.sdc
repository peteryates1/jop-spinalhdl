# EP4CE6 timing constraints (fit-check)
create_clock -name clk_in -period 20.0 [get_ports clk_in]
derive_pll_clocks
derive_clock_uncertainty
