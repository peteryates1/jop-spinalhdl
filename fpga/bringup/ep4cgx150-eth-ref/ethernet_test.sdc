# Clock constraints

# 50MHz board oscillator
create_clock -name "fpga_gclk" -period 20.000ns [get_ports {fpga_gclk}]

# 125MHz PHY RX clock (directly connected but unused by logic in PLL version)
create_clock -name "e_rxc" -period 8.000ns [get_ports {e_rxc}]

# Automatically constrain PLL and other generated clocks
derive_pll_clocks -create_base_clocks

# Automatically calculate clock uncertainty to jitter and other effects.
derive_clock_uncertainty

# e_rxc and PLL clock are unrelated
set_clock_groups -asynchronous -group {e_rxc} -group {fpga_gclk}

# tsu/th constraints (RX data relative to PLL clock — relaxed, RX not critical for this test)
set_false_path -from [get_ports {e_rxd[*] e_rxdv}]

# tco constraints (TX data relative to PLL clock)
set_output_delay -clock "pll_inst|altpll_component|auto_generated|pll1|clk[0]" -max 2ns [get_ports {e_txd[*]}]
set_output_delay -clock "pll_inst|altpll_component|auto_generated|pll1|clk[0]" -min -0.000ns [get_ports {e_txd[*]}]
set_output_delay -clock "pll_inst|altpll_component|auto_generated|pll1|clk[0]" -max 2ns [get_ports {e_txen}]
set_output_delay -clock "pll_inst|altpll_component|auto_generated|pll1|clk[0]" -min -0.000ns [get_ports {e_txen}]

# e_gtxc is driven directly from PLL — constrain as clock output
set_false_path -to [get_ports {e_gtxc}]
