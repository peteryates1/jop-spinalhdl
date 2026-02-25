# JOP SDRAM - Timing Constraints
# QMTECH EP4CGX150, 50 MHz input clock, PLL to 80 MHz

# 50 MHz input clock
create_clock -period 20.000 -name clk_in [get_ports clk_in]

derive_pll_clocks
derive_clock_uncertainty

# Ethernet PHY clocks (25 MHz MII at 100 Mbps)
create_clock -name "e_rxc" -period 40.000 [get_ports {e_rxc}]
create_clock -name "e_txc" -period 40.000 [get_ports {e_txc}]

# PHY clocks are asynchronous to all PLL clocks
set_clock_groups -asynchronous \
    -group {e_rxc} \
    -group {e_txc} \
    -group {pll|altpll_component|pll|clk[1] \
            pll|altpll_component|pll|clk[2]}
