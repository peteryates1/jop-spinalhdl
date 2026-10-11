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


# RGMII (LiteX colorlight_i9plus.py = wuxx). 100 Mb/s: 25 MHz, SDR nibbles.
# PHY0 (U5)
set_property -dict {PACKAGE_PIN A1 IOSTANDARD LVCMOS33} [get_ports {txc[0]}]
set_property -dict {PACKAGE_PIN B2 IOSTANDARD LVCMOS33} [get_ports {txd0[0]}]
set_property -dict {PACKAGE_PIN B1 IOSTANDARD LVCMOS33} [get_ports {txd0[1]}]
set_property -dict {PACKAGE_PIN C2 IOSTANDARD LVCMOS33} [get_ports {txd0[2]}]
set_property -dict {PACKAGE_PIN D2 IOSTANDARD LVCMOS33} [get_ports {txd0[3]}]
set_property -dict {PACKAGE_PIN D1 IOSTANDARD LVCMOS33} [get_ports {tx_ctl[0]}]
set_property -dict {PACKAGE_PIN H4 IOSTANDARD LVCMOS33} [get_ports {rxc[0]}]
set_property -dict {PACKAGE_PIN E3 IOSTANDARD LVCMOS33} [get_ports {rxd0[0]}]
set_property -dict {PACKAGE_PIN E2 IOSTANDARD LVCMOS33} [get_ports {rxd0[1]}]
set_property -dict {PACKAGE_PIN E1 IOSTANDARD LVCMOS33} [get_ports {rxd0[2]}]
set_property -dict {PACKAGE_PIN F3 IOSTANDARD LVCMOS33} [get_ports {rxd0[3]}]
set_property -dict {PACKAGE_PIN F1 IOSTANDARD LVCMOS33} [get_ports {rx_ctl[0]}]
# PHY1 (U9)
set_property -dict {PACKAGE_PIN M6 IOSTANDARD LVCMOS33} [get_ports {txc[1]}]
set_property -dict {PACKAGE_PIN M5 IOSTANDARD LVCMOS33} [get_ports {txd1[0]}]
set_property -dict {PACKAGE_PIN M2 IOSTANDARD LVCMOS33} [get_ports {txd1[1]}]
set_property -dict {PACKAGE_PIN N4 IOSTANDARD LVCMOS33} [get_ports {txd1[2]}]
set_property -dict {PACKAGE_PIN P4 IOSTANDARD LVCMOS33} [get_ports {txd1[3]}]
set_property -dict {PACKAGE_PIN N5 IOSTANDARD LVCMOS33} [get_ports {tx_ctl[1]}]
set_property -dict {PACKAGE_PIN L3 IOSTANDARD LVCMOS33} [get_ports {rxc[1]}]
set_property -dict {PACKAGE_PIN N2 IOSTANDARD LVCMOS33} [get_ports {rxd1[0]}]
set_property -dict {PACKAGE_PIN N3 IOSTANDARD LVCMOS33} [get_ports {rxd1[1]}]
set_property -dict {PACKAGE_PIN P1 IOSTANDARD LVCMOS33} [get_ports {rxd1[2]}]
set_property -dict {PACKAGE_PIN P2 IOSTANDARD LVCMOS33} [get_ports {rxd1[3]}]
set_property -dict {PACKAGE_PIN R1 IOSTANDARD LVCMOS33} [get_ports {rx_ctl[1]}]
# 8 ns: at 1000BASE-T (before START forces 100) the PHYs send a 125 MHz RXC
create_clock -period 8.000 -name rxc0 [get_ports {rxc[0]}]
create_clock -period 8.000 -name rxc1 [get_ports {rxc[1]}]
set_clock_groups -asynchronous -group clk25 -group rxc0 -group rxc1
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
# buttons to GND, no board pull-ups
set_property -dict {PACKAGE_PIN P5 IOSTANDARD LVCMOS33 PULLTYPE PULLUP} [get_ports {sw_n[0]}] ;# SW_RESET
set_property -dict {PACKAGE_PIN T6 IOSTANDARD LVCMOS33 PULLTYPE PULLUP} [get_ports {sw_n[1]}] ;# SW_OPTION
set_property -dict {PACKAGE_PIN U7 IOSTANDARD LVCMOS33 PULLTYPE PULLUP} [get_ports {sw_n[2]}] ;# SW_SELECT
set_property -dict {PACKAGE_PIN U6 IOSTANDARD LVCMOS33 PULLTYPE PULLUP} [get_ports {sw_n[3]}] ;# SW_START
