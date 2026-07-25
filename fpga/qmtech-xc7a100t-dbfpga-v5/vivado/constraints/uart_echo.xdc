# QMTECH XC7A100T + DB_FPGA V5 — UART Echo Test
#
# RP2040 UART0: GPIO0 (TX) → J3 pin 5 = B5 (FPGA rxd)
#               GPIO1 (RX) → J3 pin 6 = A5 (FPGA txd)
# Verified working with pin loopback test.

set_property PACKAGE_PIN U22 [get_ports {clk_in}]
set_property IOSTANDARD LVCMOS33 [get_ports {clk_in}]
create_clock -period 20.000 -name clk_in [get_ports {clk_in}]

# UART0: FPGA reads B5 (RP2040 TX), writes A5 (RP2040 RX)
set_property PACKAGE_PIN B5 [get_ports {rxd}]
set_property IOSTANDARD LVCMOS33 [get_ports {rxd}]
set_property PACKAGE_PIN A5 [get_ports {txd}]
set_property IOSTANDARD LVCMOS33 [get_ports {txd}]

set_property PACKAGE_PIN T23 [get_ports {led[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[0]}]
set_property PACKAGE_PIN R23 [get_ports {led[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[1]}]

set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]
