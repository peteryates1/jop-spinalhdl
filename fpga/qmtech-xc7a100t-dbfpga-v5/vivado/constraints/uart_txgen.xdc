# UartTxGen: FPGA independently drives 0xAA at 2 Mbaud on both A5 and P25.
# Tests: A5→GPIO1 (UART0 RX) and P25→GPIO5 (UART1 RX) independently.

set_property PACKAGE_PIN U22 [get_ports {clk}]
set_property IOSTANDARD LVCMOS33 [get_ports {clk}]
create_clock -period 20.000 -name clk [get_ports {clk}]

# A5: FPGA TX → RP2040 GPIO1 (UART0 RX, ttyACM0 with original UART0 firmware)
set_property PACKAGE_PIN A5 [get_ports {txd_a5}]
set_property IOSTANDARD LVCMOS33 [get_ports {txd_a5}]

# P25: FPGA TX → RP2040 GPIO5 (UART1 RX, ttyACM0 with UART1 firmware)
set_property PACKAGE_PIN P25 [get_ports {txd_p25}]
set_property IOSTANDARD LVCMOS33 [get_ports {txd_p25}]

set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]
