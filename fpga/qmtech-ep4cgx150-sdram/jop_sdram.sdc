# JOP SDRAM - Timing Constraints
# QMTECH EP4CGX150, 50 MHz input clock, PLL to 80 MHz

# 50 MHz input clock
create_clock -period 20.000 -name clk_in [get_ports clk_in]

derive_pll_clocks
derive_clock_uncertainty

# PLL 125 MHz for GMII TX is auto-derived by derive_pll_clocks

# Ethernet PHY RX clock (125 MHz GMII, source-synchronous from PHY).
# RX data is captured directly by e_rxc using I/O block registers
# (FAST_INPUT_REGISTER constraint in .qsf).
create_clock -period 8.000 -name e_rxc [get_ports e_rxc]

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

# PHY RX data: source-synchronous with e_rxc.
# Data transitions on e_rxc rising edge with ~2ns PHY output delay.
# FAST_INPUT_REGISTER places capture registers in I/O block for
# minimal clock-to-register delay (~6ns setup margin at 125 MHz).
# Quartus can't analyze the source-synchronous relationship, so false_path.
set_false_path -from [get_ports {e_rxd[*] e_rxdv e_rxer}]

# GMII TX: e_gtxc is driven from ethPll c0 (same clock as TX data logic).
# Both clock and data come from the same PLL output, so no output_delay needed.
# The PHY samples TXD on GTXC rising edge; board trace delay provides margin.
set_false_path -to [get_ports {e_gtxc}]
set_false_path -to [get_ports {e_txd[*] e_txen e_txer}]
