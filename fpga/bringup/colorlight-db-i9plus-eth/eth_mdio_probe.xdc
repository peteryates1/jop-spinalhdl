# fpga-db-colorlight-i5 + i9+ : Ethernet stage 1 (MDIO). Module pins from wuxx
# colorlight_i9plus_v6.1.md; base-board pins from the 2026-10-10 netlist.
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property BITSTREAM.CONFIG.UNUSEDPIN PULLNONE [current_design]

set_property -dict {PACKAGE_PIN K4  IOSTANDARD LVCMOS33} [get_ports clk25]
create_clock -period 40.000 -name clk25 [get_ports clk25]
set_property -dict {PACKAGE_PIN AB21 IOSTANDARD LVCMOS33} [get_ports uart_tx]   ;# -> stamp GPIO5, CDC0

# module PHYs (shared MDIO bus)
set_property -dict {PACKAGE_PIN G1 IOSTANDARD LVCMOS33} [get_ports mdc]
set_property -dict {PACKAGE_PIN G2 IOSTANDARD LVCMOS33 PULLTYPE PULLUP} [get_ports mdio]
# H2 (PHY reset) deliberately NOT driven: see EthMdioProbe.v.
set_property -dict {PACKAGE_PIN D1 IOSTANDARD LVCMOS33} [get_ports {phy_tx_en[0]}]  ;# PHY0 (U5)
set_property -dict {PACKAGE_PIN N5 IOSTANDARD LVCMOS33} [get_ports {phy_tx_en[1]}]  ;# PHY1 (U9)
set_property -dict {PACKAGE_PIN A1 IOSTANDARD LVCMOS33} [get_ports {phy_gtxclk[0]}]
set_property -dict {PACKAGE_PIN M6 IOSTANDARD LVCMOS33} [get_ports {phy_gtxclk[1]}]

# base board
set_property -dict {PACKAGE_PIN M1 IOSTANDARD LVCMOS33} [get_ports {led[0]}]
set_property -dict {PACKAGE_PIN L1 IOSTANDARD LVCMOS33} [get_ports {led[1]}]
set_property -dict {PACKAGE_PIN K1 IOSTANDARD LVCMOS33} [get_ports {led[2]}]
set_property -dict {PACKAGE_PIN J1 IOSTANDARD LVCMOS33} [get_ports {led[3]}]
set_property -dict {PACKAGE_PIN H3 IOSTANDARD LVCMOS33} [get_ports {led[4]}]
set_property -dict {PACKAGE_PIN U1 IOSTANDARD LVCMOS33} [get_ports {led[5]}]
set_property -dict {PACKAGE_PIN V5 IOSTANDARD LVCMOS33} [get_ports {led[6]}]
set_property -dict {PACKAGE_PIN U5 IOSTANDARD LVCMOS33} [get_ports {led[7]}]
set_property -dict {PACKAGE_PIN T3 IOSTANDARD LVCMOS33} [get_ports {eth_led[0]}]  ;# RJ1 LED: any link
set_property -dict {PACKAGE_PIN T4 IOSTANDARD LVCMOS33} [get_ports {eth_led[1]}]  ;# RJ1 LED: heartbeat
