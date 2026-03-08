# JOP SDRAM - Timing Constraints
# QMTECH EP4CGX150, 50 MHz input clock, PLL to 80 MHz

# 50 MHz input clock
create_clock -period 20.000 -name clk_in [get_ports clk_in]

derive_pll_clocks
derive_clock_uncertainty

# PLL 125 MHz for GMII TX is auto-derived by derive_pll_clocks

# RTL8211EG GMII timing constraints (shared)
source ../constraints/rtl8211eg_gmii.sdc

# All clock domains are asynchronous to each other:
#   - DRAM PLL 80 MHz (clk[1], clk[2]) and 25 MHz VGA (clk[3])
#   - Ethernet PLL 125 MHz TX (clk[0])
#   - PHY RX clock 125 MHz (e_rxc, source-synchronous)
set_clock_groups -asynchronous \
    -group {pll|altpll_component|auto_generated|pll1|clk[1] \
            pll|altpll_component|auto_generated|pll1|clk[2] \
            pll|altpll_component|auto_generated|pll1|clk[3]} \
    -group {ethPll|altpll_component|auto_generated|pll1|clk[0]} \
    -group {e_rxc}
