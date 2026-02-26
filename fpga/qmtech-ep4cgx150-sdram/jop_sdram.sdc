# JOP SDRAM - Timing Constraints
# QMTECH EP4CGX150, 50 MHz input clock, PLL to 80 MHz

# 50 MHz input clock
create_clock -period 20.000 -name clk_in [get_ports clk_in]

derive_pll_clocks
derive_clock_uncertainty

# PLL 125 MHz for GMII TX is auto-derived by derive_pll_clocks

# Ethernet PHY RX clock (125 MHz GMII, source-synchronous with RX data)
create_clock -period 8.000 -name e_rxc [get_ports e_rxc]

# All clock domains are asynchronous to each other:
#   - DRAM PLL 80 MHz (clk[1], clk[2]) and 25 MHz VGA (clk[3])
#   - Ethernet PLL 125 MHz (TX)
#   - PHY RX clock 125 MHz (e_rxc)
set_clock_groups -asynchronous \
    -group {pll|altpll_component|auto_generated|pll1|clk[1] \
            pll|altpll_component|auto_generated|pll1|clk[2] \
            pll|altpll_component|auto_generated|pll1|clk[3]} \
    -group {ethPll|altpll_component|auto_generated|pll1|clk[0]} \
    -group {e_rxc}

# PHY RX data: source-synchronous with e_rxc, no FPGA timing constraints
# (matches reference design approach: data captured by inverted e_rxc with
#  ~4ns setup margin; Quartus doesn't need to constrain these paths)
set_false_path -from [get_ports {e_rxd[*] e_rxdv e_rxer}]
