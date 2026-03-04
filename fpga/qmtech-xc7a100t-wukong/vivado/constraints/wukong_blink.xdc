# QMTECH XC7A100T Wukong V3 — LED Blinker constraints

# 50 MHz system clock
set_property PACKAGE_PIN M21 [get_ports {clk_in}]
set_property IOSTANDARD LVCMOS33 [get_ports {clk_in}]
create_clock -period 20.000 -name clk_in [get_ports {clk_in}]

# On-board LEDs (active high)
set_property PACKAGE_PIN G21 [get_ports {led[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[0]}]
set_property PACKAGE_PIN G20 [get_ports {led[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[1]}]

# PMOD J10 (Bank 35, LVCMOS33)
# Pin 1-4
set_property PACKAGE_PIN D5 [get_ports {pmod[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {pmod[0]}]
set_property PACKAGE_PIN G5 [get_ports {pmod[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {pmod[1]}]
set_property PACKAGE_PIN G7 [get_ports {pmod[2]}]
set_property IOSTANDARD LVCMOS33 [get_ports {pmod[2]}]
set_property PACKAGE_PIN G8 [get_ports {pmod[3]}]
set_property IOSTANDARD LVCMOS33 [get_ports {pmod[3]}]
# Pin 7-10
set_property PACKAGE_PIN E5 [get_ports {pmod[4]}]
set_property IOSTANDARD LVCMOS33 [get_ports {pmod[4]}]
set_property PACKAGE_PIN E6 [get_ports {pmod[5]}]
set_property IOSTANDARD LVCMOS33 [get_ports {pmod[5]}]
set_property PACKAGE_PIN D6 [get_ports {pmod[6]}]
set_property IOSTANDARD LVCMOS33 [get_ports {pmod[6]}]
set_property PACKAGE_PIN G6 [get_ports {pmod[7]}]
set_property IOSTANDARD LVCMOS33 [get_ports {pmod[7]}]

# Configuration
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]
